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
});
