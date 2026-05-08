import fs from 'node:fs';
import path from 'node:path';

describe('AppHeader styles', () => {
  test('mobile fixed header avoids explicit full-width sizing that can cause horizontal overflow', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppHeader.vue'), 'utf-8');
    const mobileStyles = source.split('@media (max-width: 768px)')[1] ?? '';

    expect(mobileStyles).not.toContain('width: 100%;');
  });

  test('uses lighter glass effects on the fixed header to reduce scroll jank', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppHeader.vue'), 'utf-8');

    expect(source).not.toContain('blur(18px) saturate(1.2)');
    expect(source).not.toContain('blur(14px)');
  });
});
