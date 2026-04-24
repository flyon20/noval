import type { HotBook, InsightCard, ThemeDistributionItem, ThemeTableItem } from '@/types/data';

const CODE_LABEL_MAP: Record<string, string> = {
  'male-new': '男频新书榜',
  'male-read': '男频阅读榜',
  'female-new': '女频新书榜',
  'female-read': '女频阅读榜',
};

const EXACT_TEXT_MAP: Record<string, string> = {
  'Lead lane': '主赛道',
  'Lead theme': '主赛道',
  'Lead title': '代表热书',
  'Hot title': '代表热书',
  'up': '上升',
  'stable': '稳定',
  'holding': '保持高位',
  'baseline': '基线样本',
  'snapshot': '快照样本',
  'Latest snapshot top ranked title': '当前快照榜首作品',
  'Derived from the latest board-scoped trend sample': '基于当前榜单趋势样本提炼',
};

export function localizeTrendText(content?: string | null) {
  const rawText = content?.trim() ?? '';

  if (!rawText) {
    return '';
  }

  const exactMatched = CODE_LABEL_MAP[rawText] ?? EXACT_TEXT_MAP[rawText];

  if (exactMatched) {
    return exactMatched;
  }

  return rawText
    .replace(
      /Board\s+(.+?)\s+is currently led by\s+(.+?),\s+with\s+(.+?)\s+as a representative title\.?/iu,
      '当前 $1 榜单由 $2 领跑，代表作品为 $3。',
    )
    .replace(/Observed across\s+(\d+)\s+recent snapshots/iu, '来自最近 $1 次快照样本');
}

export function stripMarkdownToText(content?: string | null) {
  if (!content) {
    return '';
  }

  return content
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!\[[^\]]*]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/<\/?[^>]+>/g, ' ')
    .replace(/^#{1,6}\s*/gm, '')
    .replace(/^\|/gm, '')
    .replace(/\|/g, ' ')
    .replace(/[*_~>-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export function buildPreviewText(content?: string | null, limit = 300) {
  const plainText = stripMarkdownToText(content);

  if (!plainText) {
    return '';
  }

  if (plainText.length <= limit) {
    return plainText;
  }

  return `${plainText.slice(0, limit).trim()}...`;
}

function readNestedText(value: unknown, depth = 0): string[] {
  if (depth > 3 || value === null || value === undefined) {
    return [];
  }

  if (typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed ? [trimmed] : [];
  }

  if (Array.isArray(value)) {
    return value.flatMap((item) => readNestedText(item, depth + 1));
  }

  if (typeof value === 'object') {
    const record = value as Record<string, unknown>;
    const preferredKeys = [
      'summary',
      'boardSummary',
      'overview',
      'conclusion',
      'headline',
      'detailContent',
      'content',
      'text',
      'value',
      'analysis',
      'insight',
    ];
    const preferredValues = preferredKeys.flatMap((key) => readNestedText(record[key], depth + 1));

    if (preferredValues.length) {
      return preferredValues;
    }

    return Object.values(record).flatMap((item) => readNestedText(item, depth + 1));
  }

  return [];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseStructuredJsonText(content?: string | null): Record<string, unknown> | null {
  const rawText = content?.trim();

  if (!rawText) {
    return null;
  }

  const withoutFence = rawText
    .replace(/^```json\s*/iu, '')
    .replace(/^```\s*/u, '')
    .replace(/\s*```$/u, '')
    .trim();
  const candidate = withoutFence.startsWith('{') && withoutFence.endsWith('}')
    ? withoutFence
    : withoutFence.slice(withoutFence.indexOf('{'), withoutFence.lastIndexOf('}') + 1);

  if (!candidate.startsWith('{') || !candidate.endsWith('}')) {
    return null;
  }

  try {
    const parsed = JSON.parse(candidate);
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function looksLikeStructuredJsonText(content?: string | null) {
  return parseStructuredJsonText(content) !== null;
}

function looksLikeTrendJsonStream(content?: string | null) {
  const rawText = content?.trim() ?? '';

  if (!rawText) {
    return false;
  }

  return rawText.startsWith('{')
    || rawText.startsWith('```json')
    || rawText.startsWith('```')
    || rawText.includes('"analysisType"')
    || rawText.includes('"summary"')
    || rawText.includes('"boardSummary"')
    || rawText.includes('"comparisonSummary"');
}

function readPrimaryString(value: unknown) {
  if (typeof value === 'string') {
    const parsed = parseStructuredJsonText(value);
    if (parsed) {
      return readPrimaryString(parsed.summary)
        || readPrimaryString(parsed.boardSummary)
        || readPrimaryString(parsed.comparisonSummary)
        || readPrimaryString(parsed.detailContent)
        || value.trim();
    }
    return value.trim();
  }

  const nested = readNestedText(value);
  return nested[0]?.trim() ?? '';
}

function decodeJsonLikeString(content: string) {
  return content
    .replace(/\\"/g, '"')
    .replace(/\\n/g, ' ')
    .replace(/\\r/g, ' ')
    .replace(/\\t/g, ' ')
    .trim();
}

function extractJsonLikeFieldText(content?: string | null, fieldNames: string[] = []) {
  const rawText = content?.trim();

  if (!rawText) {
    return '';
  }

  const withoutFence = rawText
    .replace(/^```json\s*/iu, '')
    .replace(/^```\s*/u, '')
    .replace(/\s*```$/u, '')
    .trim();

  for (const fieldName of fieldNames) {
    const pattern = new RegExp(`"${fieldName}"\\s*:\\s*"([\\s\\S]*?)"`, 'u');
    const matched = pattern.exec(withoutFence);

    if (matched?.[1]) {
      return stripMarkdownToText(decodeJsonLikeString(matched[1]));
    }
  }

  return '';
}

function extractJsonLikeStreamingFieldText(content?: string | null, fieldNames: string[] = []) {
  const rawText = content?.trim();

  if (!rawText) {
    return '';
  }

  const withoutFence = rawText
    .replace(/^```json\s*/iu, '')
    .replace(/^```\s*/u, '')
    .replace(/\s*```$/u, '')
    .trim();

  for (const fieldName of fieldNames) {
    const pattern = new RegExp(`"${fieldName}"\\s*:\\s*"([\\s\\S]*?)(?:"\\s*(?:,|\\}|\\n)|$)`, 'u');
    const matched = pattern.exec(withoutFence);

    if (matched?.[1]) {
      return stripMarkdownToText(decodeJsonLikeString(matched[1]));
    }
  }

  return '';
}

function cleanNestedText(value: unknown) {
  return localizeTrendText(stripMarkdownToText(readNestedText(value).join(' ')));
}

function readArray<T>(value: unknown, mapper: (item: unknown) => T | null) {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .map((item) => mapper(item))
    .filter((item): item is T => item !== null);
}

function formatRankLabel(rankNo: number | null, rankLabel?: string | null) {
  if (rankLabel?.trim()) {
    const normalized = rankLabel.trim();
    if (/^#(\d+)$/u.test(normalized)) {
      return `第${normalized.slice(1)} 名`;
    }
    return normalized;
  }

  if (typeof rankNo === 'number' && Number.isFinite(rankNo) && rankNo > 0) {
    return `第${rankNo} 名`;
  }

  return null;
}

function parseRatioValue(value: unknown) {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null;
  }

  if (typeof value !== 'string') {
    return null;
  }

  const normalized = value.trim().replace(/%$/u, '');
  if (!normalized) {
    return null;
  }

  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}

function extractTrailingRankNo(value?: string | null) {
  if (!value?.trim()) {
    return null;
  }

  const matched = /#(\d+)$/u.exec(value.trim());
  if (!matched) {
    return null;
  }

  const parsed = Number(matched[1]);
  return Number.isFinite(parsed) ? parsed : null;
}

function readLegacyRepresentativeBooks(value: unknown, theme: string) {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.flatMap((item) => {
    if (typeof item !== 'string' || !item.trim()) {
      return [];
    }

    const normalized = item.trim();
    const matched = /^(.*?)\s*\((.+)\)$/u.exec(normalized);
    const bookName = (matched?.[1] ?? normalized).trim();
    const rankLabel = matched?.[2]?.trim() ?? null;

    if (!bookName) {
      return [];
    }

    return [{
      theme: localizeTrendText(theme),
      bookName,
      author: null,
      rankNo: extractTrailingRankNo(rankLabel),
      rankLabel,
      reason: rankLabel,
    }];
  });
}

function normalizeThemeDistributionItem(value: unknown): ThemeDistributionItem | null {
  if (!isRecord(value)) {
    return null;
  }

  const theme = typeof value.theme === 'string' ? value.theme.trim() : '';
  const count = typeof value.count === 'number' ? value.count : Number(value.count ?? 0);
  const ratio = parseRatioValue(value.ratio) ?? parseRatioValue(value.percentage);

  if (!theme) {
    return null;
  }

  return {
    theme: localizeTrendText(theme),
    count: Number.isFinite(count) ? count : 0,
    ratio: Number.isFinite(ratio) ? ratio : null,
  };
}

function normalizeHotBook(value: unknown): HotBook | null {
  if (!isRecord(value)) {
    return null;
  }

  const bookName = typeof value.bookName === 'string'
    ? value.bookName.trim()
    : (typeof value.title === 'string' ? value.title.trim() : '');
  const author = typeof value.author === 'string' ? value.author.trim() : '';
  const theme = typeof value.theme === 'string' ? value.theme.trim() : '';
  const rankTrend = Array.isArray(value.rankTrend)
    ? value.rankTrend.find((item): item is string => typeof item === 'string' && !!item.trim()) ?? null
    : null;
  const numericRank = typeof value.rankNo === 'number' ? value.rankNo : Number(value.rankNo ?? Number.NaN);
  const rankNo = Number.isFinite(numericRank) ? numericRank : extractTrailingRankNo(rankTrend);
  const rankLabel = formatRankLabel(
    rankNo,
    typeof value.rankLabel === 'string' ? value.rankLabel : rankTrend,
  );
  const reason = typeof value.reason === 'string'
    ? localizeTrendText(value.reason)
    : (typeof value.coreEmotion === 'string'
      ? localizeTrendText(value.coreEmotion)
      : (typeof value.sellingPointAnalysis === 'string' ? localizeTrendText(value.sellingPointAnalysis) : ''));

  if (!bookName) {
    return null;
  }

  return {
    theme: theme ? localizeTrendText(theme) : null,
    bookName,
    author: author || null,
    rankNo,
    rankLabel,
    reason: reason || null,
  };
}

function normalizeThemeTableItem(value: unknown): ThemeTableItem | null {
  if (!isRecord(value)) {
    return null;
  }

  const theme = typeof value.theme === 'string' ? value.theme.trim() : '';
  const count = typeof value.count === 'number' ? value.count : Number(value.count ?? 0);
  const ratio = parseRatioValue(value.ratio) ?? parseRatioValue(value.percentage);
  const trend = typeof value.trend === 'string' ? localizeTrendText(value.trend) : '';
  const representativeBooks = readArray(value.representativeBooks, normalizeHotBook);
  const normalizedRepresentativeBooks = representativeBooks.length
    ? representativeBooks
    : readLegacyRepresentativeBooks(value.top3Examples, theme);

  if (!theme) {
    return null;
  }

  return {
    theme: localizeTrendText(theme),
    count: Number.isFinite(count) ? count : 0,
    ratio: Number.isFinite(ratio) ? ratio : null,
    trend,
    representativeBooks: normalizedRepresentativeBooks,
  };
}

function normalizeInsightCard(value: unknown): InsightCard | null {
  if (!isRecord(value)) {
    return null;
  }

  const label = typeof value.label === 'string' ? value.label.trim() : '';
  const note = typeof value.note === 'string' ? localizeTrendText(value.note) : '';
  const rawValue = value.value;
  const normalizedValue = typeof rawValue === 'string'
    ? localizeTrendText(rawValue)
    : (typeof rawValue === 'number' ? String(rawValue) : '');

  if (!label || !normalizedValue) {
    return null;
  }

  return {
    label: localizeTrendText(label),
    value: normalizedValue,
    note: note || null,
  };
}

function extractSentences(value?: string | null) {
  return localizeTrendText(stripMarkdownToText(value))
    .split(/[。！？；\n]+/u)
    .map((item) => item.trim())
    .filter(Boolean);
}

function pushUnique(target: string[], value?: string | null) {
  const cleaned = localizeTrendText(stripMarkdownToText(value)).trim();

  if (!cleaned || target.includes(cleaned)) {
    return;
  }

  target.push(cleaned);
}

export interface TrendDisplayModel {
  summaryText: string;
  previewText: string;
  comparisonSummary: string;
  boardSummary: string;
  keyPoints: string[];
  insightCards: InsightCard[];
  themeDistribution: ThemeDistributionItem[];
  themeTable: ThemeTableItem[];
  hotBooks: HotBook[];
  detailContent: string;
}

export interface BuildTrendDisplayModelInput {
  resultJson?: Record<string, unknown> | null;
  resultContent?: string | null;
  detailContent?: string | null;
  comparisonSummary?: string | null;
  boardSummary?: string | null;
  trendPreview?: string | null;
  insightCards?: InsightCard[] | null;
  themeDistribution?: ThemeDistributionItem[] | null;
  themeTable?: ThemeTableItem[] | null;
  hotBooks?: HotBook[] | null;
}

export function extractTrendSummary(
  resultJson?: Record<string, unknown> | null,
  fallbackContent?: string | null,
) {
  const summaryJson = typeof resultJson?.summary === 'string'
    ? parseStructuredJsonText(resultJson.summary)
    : null;
  const boardSummaryJson = typeof resultJson?.boardSummary === 'string'
    ? parseStructuredJsonText(resultJson.boardSummary)
    : null;
  const detailJson = typeof resultJson?.detailContent === 'string'
    ? parseStructuredJsonText(resultJson.detailContent)
    : null;
  const text = readNestedText(summaryJson?.summary).join(' ')
    || readNestedText(summaryJson?.boardSummary).join(' ')
    || readNestedText(resultJson?.summary).join(' ')
    || readNestedText(boardSummaryJson?.boardSummary).join(' ')
    || readNestedText(boardSummaryJson?.summary).join(' ')
    || readNestedText(resultJson?.boardSummary).join(' ')
    || readNestedText(detailJson?.detailContent).join(' ')
    || readNestedText(detailJson?.summary).join(' ')
    || readNestedText(resultJson?.detailContent).join(' ')
    || readNestedText(resultJson?.content).join(' ')
    || extractJsonLikeFieldText(fallbackContent, ['summary', 'boardSummary', 'overview', 'conclusion', 'comparisonSummary'])
    || stripMarkdownToText(fallbackContent);

  return localizeTrendText(text.trim());
}

export function buildTrendStreamingPreviewText(content?: string | null) {
  const rawText = content?.trim();

  if (!rawText) {
    return '';
  }

  const parsed = parseStructuredJsonText(rawText);
  const extracted = cleanNestedText(parsed?.boardSummary)
    || cleanNestedText(parsed?.trendPreview)
    || cleanNestedText(parsed?.comparisonSummary)
    || cleanNestedText(parsed?.summary)
    || extractJsonLikeStreamingFieldText(rawText, [
      'boardSummary',
      'trendPreview',
      'comparisonSummary',
      'summary',
      'detailContent',
      'overview',
      'conclusion',
    ]);

  if (extracted) {
    return buildPreviewText(extracted, 260);
  }

  if (looksLikeTrendJsonStream(rawText)) {
    return '正在接收结构化趋势结果，解析后会自动填充到摘要、题材表和词云里。';
  }

  return buildPreviewText(rawText, 260);
}

export function buildTrendDisplayModel(input: BuildTrendDisplayModelInput): TrendDisplayModel {
  const parsedFallbackJson = input.resultJson
    ?? parseStructuredJsonText(input.resultContent)
    ?? parseStructuredJsonText(input.detailContent)
    ?? parseStructuredJsonText(input.trendPreview);
  const nestedSummaryJson = typeof parsedFallbackJson?.summary === 'string'
    ? parseStructuredJsonText(parsedFallbackJson.summary)
    : null;
  const nestedBoardSummaryJson = typeof parsedFallbackJson?.boardSummary === 'string'
    ? parseStructuredJsonText(parsedFallbackJson.boardSummary)
    : null;
  const nestedDetailJson = typeof parsedFallbackJson?.detailContent === 'string'
    ? parseStructuredJsonText(parsedFallbackJson.detailContent)
    : null;
  const resultInsightCards = readArray(parsedFallbackJson?.insightCards, normalizeInsightCard);
  const resultThemeDistribution = readArray(parsedFallbackJson?.themeDistribution, normalizeThemeDistributionItem);
  const resultThemeTable = readArray(parsedFallbackJson?.themeTable, normalizeThemeTableItem);
  const resultHotBooks = readArray(parsedFallbackJson?.hotBooks, normalizeHotBook);
  const insightCards = resultInsightCards.length
    ? resultInsightCards
    : (input.insightCards ?? []).map((item) => ({
      ...item,
      label: localizeTrendText(item.label),
      value: localizeTrendText(item.value),
      note: item.note ? localizeTrendText(item.note) : null,
    }));
  const themeTable = resultThemeTable.length
    ? resultThemeTable
    : (input.themeTable ?? []).map((item) => ({
      ...item,
      theme: localizeTrendText(item.theme),
      trend: localizeTrendText(item.trend),
      representativeBooks: (item.representativeBooks ?? []).map((book) => ({
        ...book,
        theme: book.theme ? localizeTrendText(book.theme) : null,
        rankLabel: formatRankLabel(book.rankNo ?? null, book.rankLabel),
        reason: book.reason ? localizeTrendText(book.reason) : null,
      })),
    }));
  const themeDistribution = resultThemeDistribution.length
    ? resultThemeDistribution
    : ((input.themeDistribution?.length
      ? input.themeDistribution
      : themeTable.map((item) => ({
        theme: item.theme,
        count: item.count,
        ratio: item.ratio ?? null,
      }))) ?? []).map((item) => ({
        ...item,
        theme: localizeTrendText(item.theme),
      }));
  const hotBooks = resultHotBooks.length
    ? resultHotBooks
    : (input.hotBooks ?? []).map((item) => ({
      ...item,
      theme: item.theme ? localizeTrendText(item.theme) : null,
      rankLabel: formatRankLabel(item.rankNo ?? null, item.rankLabel),
      reason: item.reason ? localizeTrendText(item.reason) : null,
    }));
  const comparisonSummary = cleanNestedText(parsedFallbackJson?.comparisonSummary)
    || cleanNestedText(nestedSummaryJson?.comparisonSummary)
    || cleanNestedText(nestedBoardSummaryJson?.comparisonSummary)
    || cleanNestedText(nestedDetailJson?.comparisonSummary)
    || localizeTrendText(stripMarkdownToText(input.comparisonSummary))
    || extractJsonLikeFieldText(input.detailContent || input.resultContent || input.trendPreview, ['comparisonSummary'])
    || '';
  const boardSummary = cleanNestedText(parsedFallbackJson?.boardSummary)
    || cleanNestedText(nestedSummaryJson?.boardSummary)
    || cleanNestedText(nestedBoardSummaryJson?.boardSummary)
    || cleanNestedText(nestedDetailJson?.boardSummary)
    || cleanNestedText(parsedFallbackJson?.summary)
    || localizeTrendText(stripMarkdownToText(input.boardSummary))
    || extractJsonLikeFieldText(input.detailContent || input.resultContent || input.trendPreview, ['boardSummary'])
    || '';
  const summaryText = extractTrendSummary(
    parsedFallbackJson,
    input.detailContent || input.resultContent || input.trendPreview || input.comparisonSummary || input.boardSummary,
  ) || boardSummary || comparisonSummary;
  const detailContent = readPrimaryString(parsedFallbackJson?.detailContent)
    || readPrimaryString(nestedSummaryJson?.detailContent)
    || readPrimaryString(nestedBoardSummaryJson?.detailContent)
    || readPrimaryString(nestedDetailJson?.detailContent)
    || (looksLikeStructuredJsonText(input.detailContent) ? '' : (input.detailContent?.trim() ?? ''))
    || (looksLikeStructuredJsonText(input.resultContent) ? '' : (input.resultContent?.trim() ?? ''))
    || summaryText;

  const keyPoints: string[] = [];
  extractSentences(boardSummary).slice(0, 1).forEach((sentence) => pushUnique(keyPoints, sentence));
  extractSentences(comparisonSummary).slice(0, 1).forEach((sentence) => pushUnique(keyPoints, sentence));
  extractSentences(summaryText).slice(0, 2).forEach((sentence) => pushUnique(keyPoints, sentence));
  insightCards.slice(0, 3).forEach((item) => {
    const note = item.note ? `，${item.note}` : '';
    pushUnique(keyPoints, `${item.label}：${item.value}${note}`);
  });

  if (keyPoints.length < 4) {
    themeDistribution.slice(0, 2).forEach((item) => {
      const ratioText = typeof item.ratio === 'number' ? `，占比 ${item.ratio}%` : '';
      pushUnique(keyPoints, `${item.theme}出现 ${item.count} 次${ratioText}`);
    });
  }

  if (keyPoints.length < 4) {
    hotBooks.slice(0, 2).forEach((item) => {
      const label = item.rankLabel ? `（${item.rankLabel}）` : '';
      const reason = item.reason ? `，${item.reason}` : '';
      pushUnique(keyPoints, `${item.bookName}${label}${reason}`);
    });
  }

  if (!keyPoints.length) {
    extractSentences(summaryText).slice(0, 4).forEach((sentence) => pushUnique(keyPoints, sentence));
  }

  return {
    summaryText,
    previewText: buildPreviewText(boardSummary || summaryText || comparisonSummary || detailContent, 300),
    comparisonSummary,
    boardSummary,
    keyPoints: keyPoints.slice(0, 4),
    insightCards,
    themeDistribution,
    themeTable,
    hotBooks,
    detailContent,
  };
}
