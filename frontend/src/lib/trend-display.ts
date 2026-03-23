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
    const keys = ['summary', 'detailContent', 'content', 'text', 'value'];

    return keys.flatMap((key) => readNestedText(record[key], depth + 1));
  }

  return [];
}

export function extractTrendSummary(
  resultJson?: Record<string, unknown> | null,
  fallbackContent?: string | null,
) {
  const text = readNestedText(resultJson?.summary).join(' ')
    || readNestedText(resultJson?.detailContent).join(' ')
    || readNestedText(resultJson?.content).join(' ')
    || stripMarkdownToText(fallbackContent);

  return text.trim();
}
