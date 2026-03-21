import DOMPurify from 'dompurify';
import { marked } from 'marked';

export function renderAnalysisMarkdown(content: string) {
  const safeContent = content ?? '';
  const html = marked.parse(safeContent, {
    headerIds: false,
    mangle: false,
  });

  return DOMPurify.sanitize(html);
}
