import DOMPurify from 'dompurify';
import { marked } from 'marked';

export function renderAnalysisMarkdown(content: string) {
  const safeContent = content ?? '';
  const html = marked.parse(safeContent, {
    headerIds: false,
    mangle: false,
  });

  const sanitized = DOMPurify.sanitize(html);
  const wrapped = `<div class="analysis-result__markdown">${sanitized}</div>`;
  return DOMPurify.sanitize(wrapped);
}
