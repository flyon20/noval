import type { HotBook, InsightCard, ThemeTableItem } from '@/types/data';

const CODE_LABEL_MAP: Record<string, string> = {
  'male-new': '男频新书榜',
  'male-read': '男频阅读榜',
  'female-new': '女频新书榜',
  'female-read': '女频阅读榜',
};

const EXACT_TEXT_MAP: Record<string, string> = {
  'Lead theme': '主赛道',
  'Hot title': '代表热书',
  up: '上升',
  stable: '稳定',
  snapshot: '快照样本',
  'Latest snapshot top ranked title': '最新快照榜首作品',
  'Derived from the latest board-scoped trend sample': '基于当前榜单最近趋势样本',
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
    const preferredKeys = ['summary', 'overview', 'conclusion', 'headline', 'detailContent', 'content', 'text', 'value', 'analysis', 'insight'];
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

function normalizeThemeTableItem(value: unknown): ThemeTableItem | null {
  if (!isRecord(value)) {
    return null;
  }

  const theme = typeof value.theme === 'string' ? value.theme.trim() : '';
  const count = typeof value.count === 'number' ? value.count : Number(value.count ?? 0);
  const trend = typeof value.trend === 'string' ? localizeTrendText(value.trend) : '';

  if (!theme) {
    return null;
  }

  return {
    theme: localizeTrendText(theme),
    count: Number.isFinite(count) ? count : 0,
    trend,
  };
}

function normalizeHotBook(value: unknown): HotBook | null {
  if (!isRecord(value)) {
    return null;
  }

  const bookName = typeof value.bookName === 'string' ? value.bookName.trim() : '';
  const author = typeof value.author === 'string' ? value.author.trim() : '';
  const rawRankLabel = typeof value.rankLabel === 'string' ? value.rankLabel.trim() : '';
  const rankLabel = /^#(\d+)$/u.test(rawRankLabel)
    ? `第 ${rawRankLabel.slice(1)} 名`
    : rawRankLabel;
  const reason = typeof value.reason === 'string' ? localizeTrendText(value.reason) : '';

  if (!bookName) {
    return null;
  }

  return {
    bookName,
    author: author || null,
    rankLabel: rankLabel || null,
    reason: reason || null,
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
    label,
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
  keyPoints: string[];
  insightCards: InsightCard[];
  themeTable: ThemeTableItem[];
  hotBooks: HotBook[];
  detailContent: string;
}

export interface BuildTrendDisplayModelInput {
  resultJson?: Record<string, unknown> | null;
  resultContent?: string | null;
  detailContent?: string | null;
  comparisonSummary?: string | null;
  trendPreview?: string | null;
  insightCards?: InsightCard[] | null;
  themeTable?: ThemeTableItem[] | null;
  hotBooks?: HotBook[] | null;
}

export function extractTrendSummary(
  resultJson?: Record<string, unknown> | null,
  fallbackContent?: string | null,
  ) {
  const text = readNestedText(resultJson?.summary).join(' ')
    || readNestedText(resultJson?.detailContent).join(' ')
    || readNestedText(resultJson?.content).join(' ')
    || extractJsonLikeFieldText(fallbackContent, ['overview', 'conclusion', 'comparisonSummary'])
    || stripMarkdownToText(fallbackContent);

  return localizeTrendText(text.trim());
}

export function buildTrendDisplayModel(input: BuildTrendDisplayModelInput): TrendDisplayModel {
  const parsedFallbackJson = input.resultJson
    ?? parseStructuredJsonText(input.resultContent)
    ?? parseStructuredJsonText(input.detailContent)
    ?? parseStructuredJsonText(input.trendPreview);
  const resultInsightCards = readArray(parsedFallbackJson?.insightCards, normalizeInsightCard);
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
    }));
  const hotBooks = resultHotBooks.length
    ? resultHotBooks
    : (input.hotBooks ?? []).map((item) => ({
      ...item,
      rankLabel: item.rankLabel && /^#(\d+)$/u.test(item.rankLabel) ? `第 ${item.rankLabel.slice(1)} 名` : item.rankLabel,
      reason: item.reason ? localizeTrendText(item.reason) : null,
    }));
  const comparisonSummary = cleanNestedText(parsedFallbackJson?.comparisonSummary)
    || localizeTrendText(stripMarkdownToText(input.comparisonSummary))
    || extractJsonLikeFieldText(input.detailContent || input.resultContent || input.trendPreview, ['comparisonSummary'])
    || '';
  const summaryText = extractTrendSummary(
    parsedFallbackJson,
    input.detailContent || input.resultContent || input.trendPreview || input.comparisonSummary,
  ) || comparisonSummary;
  const detailContent = input.resultContent || input.detailContent || summaryText;

  const keyPoints: string[] = [];
  extractSentences(comparisonSummary).slice(0, 1).forEach((sentence) => pushUnique(keyPoints, sentence));
  extractSentences(summaryText).slice(0, 2).forEach((sentence) => pushUnique(keyPoints, sentence));
  insightCards.slice(0, 3).forEach((item) => {
    const note = item.note ? `，${item.note}` : '';
    pushUnique(keyPoints, `${item.label}：${item.value}${note}`);
  });

  if (keyPoints.length < 4) {
    themeTable.slice(0, 2).forEach((item) => {
      const trendText = item.trend ? `，${item.trend}` : '';
      pushUnique(keyPoints, `${item.theme}出现 ${item.count} 次${trendText}`);
    });
  }

  if (keyPoints.length < 4) {
    hotBooks.slice(0, 2).forEach((item) => {
      const label = item.rankLabel ? `（${item.rankLabel}）` : '';
      const reason = item.reason ? `：${item.reason}` : '';
      pushUnique(keyPoints, `${item.bookName}${label}${reason}`);
    });
  }

  if (!keyPoints.length) {
    extractSentences(summaryText).slice(0, 4).forEach((sentence) => pushUnique(keyPoints, sentence));
  }

  return {
    summaryText,
    previewText: buildPreviewText(summaryText || comparisonSummary || detailContent, 300),
    comparisonSummary,
    keyPoints: keyPoints.slice(0, 4),
    insightCards,
    themeTable,
    hotBooks,
    detailContent,
  };
}
