import DOMPurify from 'dompurify';
import { marked } from 'marked';

export function renderAnalysisMarkdown(content: string) {
  const safeContent = normalizeMarkdownForRendering(content ?? '');
  const html = marked.parse(safeContent, {
    headerIds: false,
    mangle: false,
  });

  const sanitized = DOMPurify.sanitize(html);
  const wrapped = `<div class="analysis-result__markdown">${sanitized}</div>`;
  return DOMPurify.sanitize(wrapped);
}

function normalizeMarkdownForRendering(content: string) {
  let inFence = false;
  return content
    .split('\n')
    .map((line) => {
      if (/^\s*(`{3,}|~{3,})/.test(line)) {
        inFence = !inFence;
        return line;
      }
      if (inFence) {
        return line;
      }
      return line.replace(/^(\s{0,3})(#{1,6})([^\s#].*)$/, '$1$2 $3');
    })
    .join('\n');
}
