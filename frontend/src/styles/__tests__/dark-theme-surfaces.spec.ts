import fs from 'node:fs';
import path from 'node:path';

function readSource(relativePath: string) {
  return fs.readFileSync(path.resolve(__dirname, relativePath), 'utf-8');
}

describe('dark theme surfaces', () => {
  test('element stylesheet defines dark-mode overrides for drawers and data surfaces', () => {
    const source = readSource('../element.scss');

    expect(source).toContain(":root[data-theme='dark']");
    expect(source).toContain('.el-drawer');
    expect(source).toContain('.el-drawer__body');
    expect(source).toContain('.el-select-dropdown');
    expect(source).toContain('.el-table');
  });

  test.each([
    '../../views/history/HistoryView.vue',
    '../../components/history/HistoryDetailPanel.vue',
    '../../components/history/HistoryFilterBar.vue',
    '../../components/history/HistoryListPanel.vue',
    '../../views/trend/TrendView.vue',
    '../../components/trend/TrendChartCard.vue',
    '../../components/trend/TrendComparisonList.vue',
    '../../components/trend/TrendContextBar.vue',
    '../../components/trend/TrendResultPreview.vue',
    '../../components/trend/TrendSnapshotTable.vue',
    '../../components/trend/TrendSummaryCards.vue',
    '../../components/trend/TrendTagCloud.vue',
    '../../components/rank/BookDetailDrawer.vue',
    '../../components/rank/ChapterPreviewDrawer.vue',
  ])('target surface %s does not keep hard-coded white translucent backgrounds', (relativePath) => {
    const source = readSource(relativePath);

    expect(source).not.toMatch(/rgba\(\s*255,\s*255,\s*255,\s*0\.\d+\)/);
  });
});
