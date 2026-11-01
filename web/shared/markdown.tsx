/**
 * 极小的 Markdown 渲染器，只用于渲染服务器下发的协议文本。
 *
 * 安全设计：输出的是 Preact 虚拟节点，文本由框架转义；**不使用 innerHTML，不透传任何 HTML，
 * 不生成链接或图片**（`<script>`、`[x](javascript:…)`、`![](…)` 都只会当作普通文字显示）。
 * 支持：# 标题、段落（单个换行保留为换行）、- / * 列表、1. 有序列表、| 表格、---、**粗体**、`行内代码`。
 */
import type { ComponentChildren } from 'preact';
import { useMemo } from 'preact/hooks';

export type Block =
  | { t: 'h'; level: number; text: string }
  | { t: 'p'; lines: string[] }
  | { t: 'ul'; items: string[] }
  | { t: 'ol'; items: string[] }
  | { t: 'table'; head: string[]; rows: string[][] }
  | { t: 'hr' };

const isSeparatorRow = (l: string) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(l);
const splitRow = (l: string) =>
  l.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());

export function parseMarkdown(src: string): Block[] {
  const lines = src.replace(/\r\n?/g, '\n').split('\n');
  const out: Block[] = [];
  let para: string[] = [];
  let list: { t: 'ul' | 'ol'; items: string[] } | null = null;

  const flush = () => {
    if (para.length) out.push({ t: 'p', lines: para });
    if (list) out.push(list);
    para = [];
    list = null;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = (lines[i] ?? '').replace(/\s+$/, '');
    if (!line.trim()) { flush(); continue; }

    const h = /^(#{1,6})\s+(.*)$/.exec(line);
    if (h) { flush(); out.push({ t: 'h', level: (h[1] as string).length, text: (h[2] as string).trim() }); continue; }

    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) { flush(); out.push({ t: 'hr' }); continue; }

    // 表格：当前行以 | 开头，且下一行是分隔行
    if (line.trim().startsWith('|') && isSeparatorRow(lines[i + 1] ?? '')) {
      flush();
      const head = splitRow(line);
      const rows: string[][] = [];
      i += 2;
      while (i < lines.length && (lines[i] ?? '').trim().startsWith('|')) { rows.push(splitRow(lines[i] as string)); i++; }
      i--;
      out.push({ t: 'table', head, rows });
      continue;
    }

    const ul = /^\s*[-*]\s+(.*)$/.exec(line);
    if (ul) {
      if (para.length || (list && list.t !== 'ul')) flush();
      list = list ?? { t: 'ul', items: [] };
      list.items.push((ul[1] as string).trim());
      continue;
    }
    const ol = /^\s*\d+\.\s+(.*)$/.exec(line); // "1.1 条款" 的点后不是空白，不会被当作有序列表
    if (ol) {
      if (para.length || (list && list.t !== 'ol')) flush();
      list = list ?? { t: 'ol', items: [] };
      list.items.push((ol[1] as string).trim());
      continue;
    }

    if (list) flush();
    para.push(line.trim());
  }
  flush();
  return out;
}

/** 行内：**粗体** 与 `代码`；其余原样当文本（由 Preact 转义）。 */
export function inline(text: string): ComponentChildren {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  return parts.map((p, i) => {
    if (p.length > 4 && p.startsWith('**') && p.endsWith('**')) return <strong key={i}>{p.slice(2, -2)}</strong>;
    if (p.length > 2 && p.startsWith('`') && p.endsWith('`')) return <code key={i}>{p.slice(1, -1)}</code>;
    return p;
  });
}

function renderBlock(b: Block, i: number, offset: number): ComponentChildren {
  switch (b.t) {
    case 'h': {
      const Tag = `h${Math.min(6, b.level + offset)}` as 'h2';
      return <Tag key={i}>{inline(b.text)}</Tag>;
    }
    case 'p':
      return (
        <p key={i}>
          {b.lines.map((l, j) => (
            <>
              {j > 0 && <br />}
              {inline(l)}
            </>
          ))}
        </p>
      );
    case 'ul':
      return <ul key={i}>{b.items.map((t, j) => <li key={j}>{inline(t)}</li>)}</ul>;
    case 'ol':
      return <ol key={i}>{b.items.map((t, j) => <li key={j}>{inline(t)}</li>)}</ol>;
    case 'table':
      return (
        <div class="table-wrap" key={i}>
          <table>
            <thead><tr>{b.head.map((c, j) => <th key={j}>{inline(c)}</th>)}</tr></thead>
            <tbody>{b.rows.map((r, j) => <tr key={j}>{r.map((c, k) => <td key={k}>{inline(c)}</td>)}</tr>)}</tbody>
          </table>
        </div>
      );
    case 'hr':
      return <hr key={i} />;
  }
}

/** headingOffset：页面自己已有一个 h1，所以文档里的 # 默认渲染为 h2。 */
export function Markdown({ text, headingOffset = 1 }: { text: string; headingOffset?: number }) {
  const blocks = useMemo(() => parseMarkdown(text), [text]);
  return <div class="md">{blocks.map((b, i) => renderBlock(b, i, headingOffset))}</div>;
}
