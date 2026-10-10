import type { KnowledgeToolProgressItem } from '@/types/knowledge';

export const MAX_TOOL_PROGRESS_ITEMS = 64;
const statuses = new Set(['running', 'completed', 'failed', 'cancelled', 'unknown']);

export function normalizeToolProgress(value: unknown): KnowledgeToolProgressItem | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const item = value as Record<string, unknown>;
  if (item.schemaVersion !== 1 || item.itemType !== 'tool'
    || typeof item.itemId !== 'string' || !/^tool_[0-9a-f]{32}$/.test(item.itemId)
    || typeof item.itemStatus !== 'string' || !statuses.has(item.itemStatus)) return undefined;
  return {
    schemaVersion: 1, itemId: item.itemId, itemType: 'tool',
    itemStatus: item.itemStatus as KnowledgeToolProgressItem['itemStatus'],
    toolName: typeof item.toolName === 'string' && /^[A-Za-z0-9_.:-]{1,80}$/.test(item.toolName)
      ? item.toolName : 'tool',
  };
}

export function mergeToolProgress(
  existing: KnowledgeToolProgressItem[] | undefined, value: unknown,
): KnowledgeToolProgressItem[] {
  const items = new Map<string, KnowledgeToolProgressItem>();
  for (const raw of existing ?? []) {
    const item = normalizeToolProgress(raw);
    if (item) items.set(item.itemId, item);
  }
  const update = normalizeToolProgress(value);
  if (update) {
    const previous = items.get(update.itemId);
    if (!previous || previous.itemStatus === 'running') items.set(update.itemId, update);
  }
  return [...items.values()].slice(-MAX_TOOL_PROGRESS_ITEMS);
}

export function readToolProgressSnapshot(version: unknown, value: unknown): KnowledgeToolProgressItem[] | undefined {
  if (version !== 1 || !Array.isArray(value)) return undefined;
  return value.slice(-MAX_TOOL_PROGRESS_ITEMS).reduce<KnowledgeToolProgressItem[]>(
    (items, item) => mergeToolProgress(items, item), [],
  );
}

export function settleToolProgress(items: KnowledgeToolProgressItem[] | undefined) {
  // Run termination alone cannot prove that an in-flight external operation completed.
  return items?.map((item): KnowledgeToolProgressItem => (
    item.itemStatus === 'running' ? { ...item, itemStatus: 'unknown' } : item
  ));
}

export function toolProgressLabel(item: KnowledgeToolProgressItem): string {
  const labels: Record<string, string> = {
    'knowledge.search': '检索资料', 'project.retrieve': '检索作品资料',
    'rank.lookup': '查询榜单', 'book.search': '查找作品',
    'book.chapters': '读取章节', 'book.profile': '读取作品信息',
  };
  return labels[item.toolName] ?? '工具调用';
}

export function toolProgressStatusLabel(status: KnowledgeToolProgressItem['itemStatus']): string {
  return { running: '正在执行', completed: '已完成', failed: '执行失败', cancelled: '已取消', unknown: '结果待确认' }[status];
}
