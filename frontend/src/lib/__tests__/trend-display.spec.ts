import { buildTrendDisplayModel } from '../trend-display';

describe('buildTrendDisplayModel', () => {
  test('keeps trend contract fields structured instead of falling back to raw prose', () => {
    const result = buildTrendDisplayModel({
      resultContent: 'RAW-FALLBACK-CONTENT '.repeat(40),
      resultJson: {
        summary: '最近三次样本继续向都市脑洞聚焦。',
        boardSummary: '当前榜单摘要应该直接来自结构化字段。',
        themeDistribution: [
          { theme: '都市脑洞-系统流', count: 12, ratio: 40 },
          { theme: '都市脑洞-反套路', count: 6, ratio: 20 },
        ],
        themeTable: [
          {
            theme: '都市脑洞-系统流',
            count: 12,
            ratio: 40,
            trend: 'up',
            representativeBooks: [
              {
                theme: '都市脑洞-系统流',
                bookName: '脑洞之王',
                author: '作者甲',
                rankNo: 1,
                reason: '该题材下排名最高，且概念辨识度最强。',
              },
            ],
          },
        ],
        hotBooks: [
          {
            theme: '都市脑洞-系统流',
            bookName: '脑洞之王',
            author: '作者甲',
            rankNo: 1,
            reason: '当前榜单代表热书。',
          },
        ],
      },
    }) as any;

    expect(result.summaryText).toContain('都市脑洞');
    expect(result.boardSummary).toBe('当前榜单摘要应该直接来自结构化字段。');
    expect(result.themeDistribution).toEqual([
      { theme: '都市脑洞-系统流', count: 12, ratio: 40 },
      { theme: '都市脑洞-反套路', count: 6, ratio: 20 },
    ]);
    expect(result.themeTable[0].representativeBooks?.[0]).toMatchObject({
      bookName: '脑洞之王',
      rankNo: 1,
    });
    expect(result.previewText).not.toContain('RAW-FALLBACK-CONTENT');
  });

  test('extracts structured detail content from stored raw json text', () => {
    const rawJsonText = JSON.stringify({
      analysisType: 'theme',
      summary: '最近三次样本继续向都市脑洞聚焦。',
      boardSummary: '榜单摘要应该从 JSON 合同字段里提取。',
      trendPreview: '都市脑洞仍是当前榜单主赛道。',
      detailContent: '这里才是真正要展示的趋势正文，而不是整段 JSON。',
      hotBooks: [
        {
          theme: '都市脑洞',
          bookName: '脑洞之王',
          author: '作者甲',
          rankNo: 1,
          reason: '该题材下排名最高的代表热书。',
        },
      ],
      insightCards: [
        {
          label: 'Lead lane',
          value: 'urban-brain',
          note: 'Derived from the latest board-scoped trend sample',
        },
      ],
    });

    const result = buildTrendDisplayModel({
      detailContent: rawJsonText,
      resultContent: rawJsonText,
    });

    expect(result.summaryText).toContain('都市脑洞');
    expect(result.boardSummary).toBe('榜单摘要应该从 JSON 合同字段里提取。');
    expect(result.hotBooks[0]).toMatchObject({
      bookName: '脑洞之王',
      rankNo: 1,
    });
    expect(result.insightCards[0]).toMatchObject({
      label: '主赛道',
      value: 'urban-brain',
    });
    expect(result.detailContent).toBe('这里才是真正要展示的趋势正文，而不是整段 JSON。');
    expect(result.detailContent).not.toContain('"analysisType"');
  });

  test('uses nested summary values when resultJson summary is itself json text', () => {
    const result = buildTrendDisplayModel({
      resultJson: {
        summary: '{"summary":"本次榜单娱乐明星与都市脑洞分庭抗礼","boardSummary":"主赛道为娱乐明星","trendPreview":"宽泛系统流持续退潮"}',
      },
    });

    expect(result.summaryText).toBe('本次榜单娱乐明星与都市脑洞分庭抗礼');
    expect(result.boardSummary).toBe('主赛道为娱乐明星');
    expect(result.previewText).not.toContain('{"summary"');
  });

  test('normalizes legacy trend json fields before rendering', () => {
    const legacyJsonText = JSON.stringify({
      summary: {
        coreTrend: '当前榜单正在向都市脑洞混合钩子集中。',
      },
      historicalWordCloud: [
        { word: '都市脑洞', count: 18, percentage: '75.0%' },
      ],
      themeTable: [
        {
          theme: '都市脑洞混合流',
          count: 3,
          percentage: '50.0%',
          top3Examples: ['脑洞之王 (stable #1)'],
          trend: 'rising',
        },
      ],
      hotBooks: [
        {
          title: '脑洞之王',
          rankTrend: ['S3#1', 'S2#1'],
          coreEmotion: '规则打破后的爽感兑现',
        },
      ],
    });

    const result = buildTrendDisplayModel({
      resultContent: legacyJsonText,
      detailContent: legacyJsonText,
    });

    expect(result.summaryText).toContain('都市脑洞');
    expect(result.boardSummary).toBe('当前榜单正在向都市脑洞混合钩子集中。');
    expect(result.themeDistribution).toEqual([
      { theme: '都市脑洞混合流', count: 3, ratio: 50 },
    ]);
    expect(result.themeTable[0].representativeBooks?.[0]).toMatchObject({
      bookName: '脑洞之王',
      rankLabel: 'stable #1',
    });
    expect(result.hotBooks[0]).toMatchObject({
      bookName: '脑洞之王',
      rankLabel: 'S3#1',
      reason: '规则打破后的爽感兑现',
    });
    expect(result.detailContent).toBe('当前榜单正在向都市脑洞混合钩子集中。');
  });
});
