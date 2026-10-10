import { renderAnalysisMarkdown } from '@/lib/markdown';

describe('Markdown sanitizer', () => {
  test('removes unsafe scripts and attributes while rendering markdown', () => {
    const dangerousMarkdown = '# 标题\n<script>alert("x")</script><img src=x onerror=alert(2) />\n- 1';

    const html = renderAnalysisMarkdown(dangerousMarkdown);

    expect(html).toContain('<h1>标题</h1>');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('onerror');
    expect(html).toContain('<img');
  });
  test('renders compact ATX headings without a space after hash markers', () => {
    const html = renderAnalysisMarkdown('##Rank Evidence\ncontent\n\n###Outline');

    expect(html).toContain('<h2>Rank Evidence</h2>');
    expect(html).toContain('<h3>Outline</h3>');
  });

  test('drops standalone period separator lines from assistant answers', () => {
    const html = renderAnalysisMarkdown('## Risk level\n.\nLow\n\n.\n## Next steps');

    expect(html).toContain('<h2>Risk level</h2>');
    expect(html).toContain('<p>Low</p>');
    expect(html).toContain('<h2>Next steps</h2>');
    expect(html).not.toContain('<p>.</p>');
  });

  test('wraps GFM tables in an accessible responsive region and keeps cells sanitized', () => {
    const html = renderAnalysisMarkdown([
      '| 题材主壳 | 数量 | 判断 |',
      '| --- | ---: | --- |',
      '| 校园高考 | 8 | 连续性强 <img src=x onerror=alert(1)> |',
    ].join('\n'));

    expect(html).toContain('class="analysis-result__table-scroll"');
    expect(html).toContain('role="region"');
    expect(html).toContain('tabindex="0"');
    expect(html).toContain('<table>');
    expect(html).toContain('<th>题材主壳</th>');
    expect(html).toContain('<td>校园高考</td>');
    expect(html).not.toContain('onerror');
  });
});
