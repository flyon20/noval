import type { AnalysisType } from '@/types/analysis';
import { buildPreviewText, stripMarkdownToText } from './trend-display';

type JsonMap = Record<string, unknown>;

interface BuildAnalysisDisplayContentInput {
  resultContent?: string | null;
  resultJson?: JsonMap | null;
}

function isRecord(value: unknown): value is JsonMap {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseStructuredJsonText(content?: string | null): JsonMap | null {
  const rawText = content?.trim();
  if (!rawText) {
    return null;
  }

  const withoutFence = rawText
    .replace(/^```json\s*/iu, '')
    .replace(/^```\s*/u, '')
    .replace(/\s*```$/u, '')
    .trim();

  const firstBrace = withoutFence.indexOf('{');
  const lastBrace = withoutFence.lastIndexOf('}');
  if (firstBrace < 0 || lastBrace <= firstBrace) {
    return null;
  }

  const candidate = withoutFence.slice(firstBrace, lastBrace + 1);
  try {
    const parsed = JSON.parse(candidate);
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function readPrimaryString(value: unknown): string {
  if (typeof value === 'string') {
    return value.trim();
  }
  if (Array.isArray(value)) {
    return value
      .map((item) => readPrimaryString(item))
      .find((item) => item.length > 0) ?? '';
  }
  if (isRecord(value)) {
    return Object.values(value)
      .map((item) => readPrimaryString(item))
      .find((item) => item.length > 0) ?? '';
  }
  return '';
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .map((item) => readPrimaryString(item))
    .map((item) => stripMarkdownToText(item))
    .map((item) => item.trim())
    .filter(Boolean);
}

function formatSection(title: string, items: string[]): string {
  if (!items.length) {
    return '';
  }

  return `### ${title}\n${items.map((item) => `- ${item}`).join('\n')}`;
}

function looksLikeJsonText(content?: string | null): boolean {
  const rawText = content?.trim() ?? '';
  if (!rawText) {
    return false;
  }

  return rawText.startsWith('{')
    || rawText.startsWith('```json')
    || rawText.includes('"summary"')
    || rawText.includes('"detailContent"');
}

function formatStructureStages(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.flatMap((item) => {
    if (!isRecord(item)) {
      return [];
    }
    const stageName = readPrimaryString(item.stageName);
    const purpose = readPrimaryString(item.purpose);
    const chapterRange = readPrimaryString(item.chapterRange);

    if (!stageName && !purpose) {
      return [];
    }

    const suffix = chapterRange ? `（${chapterRange}）` : '';
    return [`${stageName || '阶段'}：${purpose}${suffix}`.trim()];
  });
}

function formatPlotBeats(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.flatMap((item) => {
    if (!isRecord(item)) {
      return [];
    }
    const beat = readPrimaryString(item.beat);
    const trigger = readPrimaryString(item.trigger);
    const payoff = readPrimaryString(item.payoff);

    if (!beat && !trigger && !payoff) {
      return [];
    }

    const triggerText = trigger ? `触发：${trigger}` : '';
    const payoffText = payoff ? `兑现：${payoff}` : '';
    return [[beat, triggerText, payoffText].filter(Boolean).join('；')];
  });
}

function buildStructuredSections(analysisType: AnalysisType, resultJson: JsonMap): string[] {
  if (analysisType === 'deconstruct') {
    return [
      formatSection('核心卖点', readStringArray(resultJson.sellingPoints)),
      formatSection('开篇钩子', readStringArray(resultJson.openingHooks)),
      formatSection('人物关系', readStringArray(resultJson.characterRelations)),
      formatSection('节奏亮点', readStringArray(resultJson.rhythmHighlights)),
      formatSection('优化建议', readStringArray(resultJson.optimizationNotes)),
    ].filter(Boolean);
  }

  if (analysisType === 'structure') {
    return [
      formatSection('结构阶段', formatStructureStages(resultJson.structureStages)),
      formatSection('节奏观察', readStringArray(resultJson.pacingObservations)),
      formatSection('世界观锚点', readStringArray(resultJson.worldBuildingAnchors)),
    ].filter(Boolean);
  }

  return [
    formatSection('情节点', formatPlotBeats(resultJson.plotBeats)),
    formatSection('冲突线', readStringArray(resultJson.conflictLines)),
    formatSection('兑现点', readStringArray(resultJson.payoffPoints)),
    formatSection('风险提示', readStringArray(resultJson.riskFlags)),
  ].filter(Boolean);
}

function buildStructuredMarkdown(analysisType: AnalysisType, resultJson: JsonMap): string {
  const summary = stripMarkdownToText(readPrimaryString(resultJson.summary));
  const detailContent = readPrimaryString(resultJson.detailContent);
  const sections = buildStructuredSections(analysisType, resultJson);
  const parts = [
    summary,
    ...sections,
    detailContent && detailContent !== summary ? detailContent.trim() : '',
  ].filter(Boolean);

  return parts.join('\n\n').trim();
}

function extractStreamingField(content?: string | null): string {
  const rawText = content?.trim();
  if (!rawText) {
    return '';
  }

  const matched = /"(summary|detailContent)"\s*:\s*"([\s\S]*?)(?:"\s*(?:,|\}|$))/u.exec(rawText);
  if (!matched?.[2]) {
    return '';
  }

  return stripMarkdownToText(
    matched[2]
      .replace(/\\"/g, '"')
      .replace(/\\n/g, ' ')
      .replace(/\\r/g, ' ')
      .replace(/\\t/g, ' '),
  ).trim();
}

function stripProgressMarkers(content?: string | null): string {
  return (content ?? '')
    .replace(/\[analysis[- ]progress\][^\n\r]*/giu, ' ')
    .trim();
}

function extractLatestProgressMessage(content?: string | null): string {
  const matches = [...(content ?? '').matchAll(/\[analysis[- ]progress\]\s*([^\n\r]+)/giu)];
  const latest = matches.at(-1)?.[1]?.trim() ?? '';
  return stripMarkdownToText(latest);
}

export function buildAnalysisStreamingPreviewText(content?: string | null): string {
  const rawText = content?.trim() ?? '';
  if (!rawText) {
    return '';
  }

  const progressText = extractLatestProgressMessage(rawText);
  const sanitizedText = stripProgressMarkers(rawText);
  const parsed = parseStructuredJsonText(sanitizedText);
  const extracted = stripMarkdownToText(readPrimaryString(parsed?.summary || parsed?.detailContent)).trim()
    || extractStreamingField(sanitizedText);

  if (extracted) {
    return buildPreviewText(extracted, 220);
  }

  if (progressText) {
    return progressText;
  }

  if (looksLikeJsonText(sanitizedText)) {
    return '正在整理分析结果，完成后会自动展示为可读结论。';
  }

  return buildPreviewText(stripMarkdownToText(sanitizedText), 220);
}

export function buildAnalysisDisplayContent(
  analysisType: AnalysisType,
  input: BuildAnalysisDisplayContentInput,
): string {
  const rawText = input.resultContent?.trim() ?? '';
  const parsed = (isRecord(input.resultJson) && Object.keys(input.resultJson).length > 0
    ? input.resultJson
    : parseStructuredJsonText(rawText)) ?? null;

  if (!parsed) {
    return rawText;
  }

  if (!looksLikeJsonText(rawText)) {
    return rawText || buildStructuredMarkdown(analysisType, parsed);
  }

  const structured = buildStructuredMarkdown(analysisType, parsed);
  return structured || rawText;
}
