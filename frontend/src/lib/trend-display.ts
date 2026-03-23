import type { ChartItem, ThemeWordCloudItem } from '@/types/data';

const ANALYSIS_TYPE_LABELS: Record<string, string> = {
  deconstruct: '拆文分析',
  structure: '结构分析',
  plot: '情节分析',
  theme: '趋势分析',
};

const REQUEST_CATEGORY_LABELS: Record<string, string> = {
  'male-hot-a': '男频热门榜',
  'male-hot-b': '男频热门榜扩展',
  'male-new-a': '男频新书榜',
};

const CATEGORY_PREFIX_LABELS: Record<string, string> = {
  'male-hot': '男频热门榜',
  'male-new': '男频新书榜',
  'male-read': '男频在读榜',
  'female-hot': '女频热门榜',
  'female-new': '女频新书榜',
  'female-read': '女频在读榜',
};

export interface TrendRankingItem {
  label: string;
  value: number;
  note?: string;
}

export function formatAnalysisTypeLabel(value?: string | null) {
  if (!value) {
    return '未知分析';
  }

  return ANALYSIS_TYPE_LABELS[value] ?? value;
}

export function formatTrendRequestCategoryLabel(value?: string | null) {
  if (!value) {
    return '全部榜单';
  }

  return REQUEST_CATEGORY_LABELS[value] ?? formatRankCategoryLabel(value);
}

export function formatRankCategoryLabel(value?: string | null) {
  if (!value) {
    return '未知分类';
  }

  if (REQUEST_CATEGORY_LABELS[value]) {
    return REQUEST_CATEGORY_LABELS[value];
  }

  const [prefix, suffix] = value.split(':');
  const prefixLabel = CATEGORY_PREFIX_LABELS[prefix];

  if (!prefixLabel) {
    return value;
  }

  return suffix ? `${prefixLabel} · ${suffix}` : prefixLabel;
}

export function groupRankCategories(items: ChartItem[] = []): TrendRankingItem[] {
  const grouped = new Map<string, number>();

  for (const item of items) {
    const [prefix] = item.name.split(':');
    const label = CATEGORY_PREFIX_LABELS[prefix] ?? formatRankCategoryLabel(item.name);
    grouped.set(label, (grouped.get(label) ?? 0) + item.value);
  }

  return Array.from(grouped.entries())
    .map(([label, value]) => ({ label, value }))
    .sort((left, right) => right.value - left.value);
}

export function toAnalysisTypeRanking(items: ChartItem[] = []): TrendRankingItem[] {
  return items
    .map((item) => ({
      label: formatAnalysisTypeLabel(item.name),
      value: item.value,
    }))
    .sort((left, right) => right.value - left.value);
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
    const keys = ['summary', 'content', 'text', 'value'];

    return keys.flatMap((key) => readNestedText(record[key], depth + 1));
  }

  return [];
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

export function extractTrendSummary(
  resultJson?: Record<string, unknown> | null,
  fallbackContent?: string | null,
) {
  const text = readNestedText(resultJson?.summary).join(' ')
    || readNestedText(resultJson?.content).join(' ')
    || stripMarkdownToText(fallbackContent);

  return text.trim();
}

export function buildFallbackTagCloud(
  analysisItems: ChartItem[] = [],
  categories: TrendRankingItem[] = [],
  limit = 8,
): ThemeWordCloudItem[] {
  const merged = new Map<string, number>();

  for (const item of analysisItems) {
    const label = formatAnalysisTypeLabel(item.name);
    merged.set(label, (merged.get(label) ?? 0) + item.value);
  }

  for (const item of categories) {
    merged.set(item.label, (merged.get(item.label) ?? 0) + item.value);
  }

  return Array.from(merged.entries())
    .map(([name, value]) => ({ name, value }))
    .sort((left, right) => right.value - left.value)
    .slice(0, limit);
}
