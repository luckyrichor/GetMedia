import { render } from 'preact';
import { describe, expect, test } from 'vitest';
import { Markdown, parseMarkdown } from '@shared/markdown';
import privacy from '../../docs/legal/privacy-policy.md?raw';
import agreement from '../../docs/legal/user-agreement.md?raw';

const html = (text: string) => {
  const el = document.createElement('div');
  render(<Markdown text={text} />, el);
  return el;
};

describe('parseMarkdown', () => {
  test('headings, lists, tables, hr', () => {
    const b = parseMarkdown('# 标题\n\n- 一\n- 二\n\n1. 甲\n2. 乙\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n---');
    expect(b.map((x) => x.t)).toEqual(['h', 'ul', 'ol', 'table', 'hr']);
    const t = b[3];
    expect(t && t.t === 'table' && t.rows).toEqual([['1', '2']]);
  });

  test('"1.1 条款" is a paragraph line, not an ordered list; single newlines are kept as lines', () => {
    const b = parseMarkdown('1.1 第一条\n1.2 第二条');
    expect(b).toEqual([{ t: 'p', lines: ['1.1 第一条', '1.2 第二条'] }]);
  });

  test('real legal seeds parse and contain headings, paragraphs and (privacy) a table', () => {
    for (const src of [agreement, privacy]) {
      const kinds = new Set(parseMarkdown(src).map((x) => x.t));
      expect(kinds.has('h') && kinds.has('p')).toBe(true);
    }
    expect(parseMarkdown(privacy).some((x) => x.t === 'table')).toBe(true);
  });
});

describe('Markdown rendering is safe', () => {
  test('raw HTML is shown as text, never parsed', () => {
    const el = html('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>');
    expect(el.querySelector('script')).toBeNull();
    expect(el.querySelector('img')).toBeNull();
    expect(el.textContent).toContain('<script>alert(1)</script>');
  });

  test('links and images are not generated', () => {
    const el = html('[点我](javascript:alert(1)) ![x](https://e.invalid/a.png) https://e.invalid');
    expect(el.querySelector('a')).toBeNull();
    expect(el.querySelector('img')).toBeNull();
    expect(el.textContent).toContain('javascript:alert(1)');
  });

  test('bold and code work; heading offset keeps a single h1 per page', () => {
    const el = html('# 标题\n\n**重点** 与 `code`');
    expect(el.querySelector('h1')).toBeNull();
    expect(el.querySelector('h2')?.textContent).toBe('标题');
    expect(el.querySelector('strong')?.textContent).toBe('重点');
    expect(el.querySelector('code')?.textContent).toBe('code');
  });

  test('no inline event handlers or style attributes in the output', () => {
    const el = html('# a\n\n- b\n\n| x | y |\n|---|---|\n| 1 | 2 |');
    expect(el.innerHTML).not.toMatch(/\son\w+=|style=/);
  });
});
