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
});
