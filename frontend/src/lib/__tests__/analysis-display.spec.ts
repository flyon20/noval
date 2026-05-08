import { buildAnalysisDisplayContent, buildAnalysisStreamingPreviewText } from '../analysis-display';

describe('analysis-display', () => {
  test('builds friendly preview text instead of raw json while streaming', () => {
    expect(
      buildAnalysisStreamingPreviewText('{"summary":"开篇钩子明确，冲突进入很快","detailContent":"完整内容"}'),
    ).toContain('开篇钩子明确');
  });

  test('builds readable markdown content from structured single-book result', () => {
    const content = buildAnalysisDisplayContent('deconstruct', {
      resultContent: '{"summary":"JSON summary","sellingPoints":["反差感强"],"detailContent":"完整拆文正文"}',
      resultJson: {
        summary: 'JSON summary',
        sellingPoints: ['反差感强'],
        openingHooks: ['首章直接冲突'],
        detailContent: '完整拆文正文',
      },
    });

    expect(content).toContain('JSON summary');
    expect(content).toContain('反差感强');
    expect(content).toContain('首章直接冲突');
    expect(content).not.toContain('{"summary"');
  });
});

test('shows friendly progress copy when the marker uses a space separator', () => {
  const preview = buildAnalysisStreamingPreviewText('[analysis progress] 正在分析中，请稍候...');
  expect(preview).toContain('正在分析中');
  expect(preview).not.toContain('[analysis progress]');
});

test('shows friendly progress copy instead of raw analysis progress marker', () => {
  expect(
    buildAnalysisStreamingPreviewText('[analysis-progress] 正在分析中，请稍候...'),
  ).toContain('正在分析中');
  expect(
    buildAnalysisStreamingPreviewText('[analysis-progress] 正在分析中，请稍候...'),
  ).not.toContain('[analysis-progress]');
});
