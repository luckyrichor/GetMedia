/** 日期框的占位行为：空着不显示系统占位；聚焦换成自己的「年/月/日」；一敲数字就交回原生字段。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { render } from 'preact';
import { useState } from 'preact/hooks';
import { DateInput } from '../src/ui';

let host: HTMLDivElement | null = null;

/** 受控组件：把 value 接到 state 上，模拟真实用法。 */
function Host({ initial = '' }: { initial?: string }) {
  const [v, setV] = useState(initial);
  return <DateInput value={v} onInput={setV} label="开始日期" />;
}

function mount(initial = '') {
  host = document.createElement('div');
  document.body.appendChild(host);
  render(<Host initial={initial} />, host);
  const wrap = host.querySelector('.date-wrap') as HTMLElement;
  const input = host.querySelector('input.date-input') as HTMLInputElement;
  return { wrap, input, hint: () => host!.querySelector('.date-hint') as HTMLElement | null };
}

const key = (el: Element, k: string) =>
  el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));

/** preact 的状态更新在微任务里落地，断言前让它跑完。 */
const flush = () => new Promise((r) => setTimeout(r, 0));

beforeEach(() => {
  // jsdom 里 showPicker 不存在，组件已经做了 try/catch + 可选调用，这里不用桩
});
afterEach(() => {
  if (host) {
    render(null, host);
    host.remove();
    host = null;
  }
  vi.restoreAllMocks();
});

describe('DateInput 占位', () => {
  test('空着且没聚焦：没有提示文字，也没有 hinting 标记', () => {
    const { wrap, hint } = mount();
    expect(hint()).toBeNull();
    expect(wrap.classList.contains('hinting')).toBe(false);
  });

  test('聚焦且为空：显示「年/月/日」，并给原生字段打上 hinting', async () => {
    const { wrap, input, hint } = mount();
    input.focus();
    await flush();
    expect(hint()?.textContent).toBe('年/月/日');
    expect(wrap.classList.contains('hinting')).toBe(true);
  });

  test('聚焦后按数字键：撤掉提示，把原生字段交回来（否则看不见输入的数字）', async () => {
    const { wrap, input, hint } = mount();
    input.focus();
    await flush();
    expect(hint()).not.toBeNull();
    key(input, '2');
    await flush();
    expect(hint()).toBeNull();
    expect(wrap.classList.contains('hinting')).toBe(false);
  });

  test('空框上按退格/方向键不算开始手输，中文占位留着', async () => {
    const { input, hint } = mount();
    input.focus();
    await flush();
    for (const k of ['Backspace', 'Delete', 'ArrowLeft', 'ArrowRight', 'Tab', 'Escape']) key(input, k);
    await flush();
    expect(hint()?.textContent).toBe('年/月/日');
  });

  test('有值时聚焦：不再显示提示', async () => {
    const { input, hint } = mount('2026-11-27');
    input.focus();
    await flush();
    expect(hint()).toBeNull();
    expect(input.classList.contains('has-value')).toBe(true);
  });

  test('失焦后提示消失，再次聚焦重新出现', async () => {
    const { input, hint } = mount();
    input.focus();
    await flush();
    expect(hint()).not.toBeNull();
    input.blur();
    await flush();
    expect(hint()).toBeNull();
    input.focus();
    await flush();
    expect(hint()).not.toBeNull();
  });
});
