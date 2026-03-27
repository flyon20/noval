import fs from 'node:fs';
import path from 'node:path';

describe('AppHeader styles', () => {
  test('mobile fixed header avoids explicit full-width sizing that can cause horizontal overflow', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../AppHeader.vue'), 'utf-8');
    const mobileStyles = source.split('@media (max-width: 768px)')[1] ?? '';

    expect(mobileStyles).not.toContain('width: 100%;');
  });
});
