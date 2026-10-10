import { mergeToolProgress, normalizeToolProgress, readToolProgressSnapshot, settleToolProgress } from '../knowledgeToolProgress';

const item = (index: number, itemStatus = 'running') => ({
  schemaVersion: 1, itemId: `tool_${index.toString(16).padStart(32, '0')}`, itemType: 'tool',
  itemStatus, toolName: 'knowledge.search',
});

test('tool progress is bounded, strips content and keeps the first terminal outcome', () => {
  let items = mergeToolProgress([], { ...item(0), arguments: { query: 'private fixture' }, usage: { inputTokens: 100 } });
  expect(items[0]).not.toHaveProperty('arguments');
  expect(items[0]).not.toHaveProperty('usage');
  items = mergeToolProgress(items, item(0, 'unknown'));
  items = mergeToolProgress(items, item(0, 'running'));
  items = mergeToolProgress(items, item(0, 'completed'));
  expect(items[0]?.itemStatus).toBe('unknown');
  for (let index = 1; index <= 64; index++) items = mergeToolProgress(items, item(index));
  expect(items).toHaveLength(64);
  expect(items.at(-1)?.itemId).toBe(item(64).itemId);
});

test('snapshots ignore unknown versions and malformed items and permit an empty projection', () => {
  expect(readToolProgressSnapshot(undefined, undefined)).toBeUndefined();
  expect(readToolProgressSnapshot(2, [item(1)])).toBeUndefined();
  expect(readToolProgressSnapshot(1, [])).toEqual([]);
  expect(readToolProgressSnapshot(1, [item(1), { ...item(2), itemStatus: 'future' }])).toHaveLength(1);
  expect(normalizeToolProgress({ ...item(1), itemId: 'raw external identifier' })).toBeUndefined();
});

test('run termination leaves unfinished tool effects unknown and preserves observed terminals', () => {
  const items = mergeToolProgress(mergeToolProgress([], item(1)), item(2, 'completed'));
  expect(settleToolProgress(items)?.map((entry) => entry.itemStatus)).toEqual(['unknown', 'completed']);
});
