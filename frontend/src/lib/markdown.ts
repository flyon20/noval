import DOMPurify from 'dompurify';
import { marked } from 'marked';

export function renderAnalysisMarkdown(content: string) {
  const safeContent = normalizeMarkdownForRendering(content ?? '');
  const html = marked.parse(safeContent, {
    headerIds: false,
    mangle: false,
  });

  const sanitized = DOMPurify.sanitize(html);
  const responsive = wrapResponsiveTables(sanitized);
  const wrapped = `<div class="analysis-result__markdown">${responsive}</div>`;
  return DOMPurify.sanitize(wrapped);
}

function wrapResponsiveTables(html: string) {
  if (typeof document === 'undefined') {
    return html;
  }
  const template = document.createElement('template');
  template.innerHTML = html;
  for (const table of Array.from(template.content.querySelectorAll('table'))) {
    if (table.parentElement?.classList.contains('analysis-result__table-scroll')) {
      continue;
    }
    const wrapper = document.createElement('div');
    wrapper.className = 'analysis-result__table-scroll';
    wrapper.setAttribute('role', 'region');
    wrapper.setAttribute('aria-label', '表格，可横向滚动');
    wrapper.tabIndex = 0;
    table.parentNode?.insertBefore(wrapper, table);
    wrapper.appendChild(table);
  }
  return template.innerHTML;
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
      if (/^\s*[.]\s*$/.test(line)) {
        return '';
      }
      return line.replace(/^(\s{0,3})(#{1,6})([^\s#].*)$/, '$1$2 $3');
    })
    .join('\n');
}
