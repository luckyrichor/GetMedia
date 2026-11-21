/** 自定义下拉框：候选在控件下方展开，当前值始终留在框里。 */
import { render } from 'preact';
import { useState } from 'preact/hooks';
import { act } from 'preact/test-utils';
import { afterEach, describe, expect, test } from 'vitest';
import { MultiSelect, Select } from '../src/select';

const OPTIONS = [
  { value: 'a', label: '甲' },
  { value: 'b', label: '乙' },
  { value: 'c', label: '丙', hint: '第三条' },
];

let host: HTMLElement | null = null;

function mountSelect(initial = 'b') {
  host = document.createElement('div');
  document.body.appendChild(host);
  function Demo() {
    const [v, setV] = useState(initial);
    return <Select ariaLabel="演示" value={v} options={OPTIONS} onChange={setV} />;
  }
  render(<Demo />, host);
  return host;
}

const trigger = () => document.querySelector('button[aria-label="演示"]') as HTMLButtonElement;
const menu = () => document.querySelector('[role="listbox"]');
const opts = () => Array.from(document.querySelectorAll('[role="option"]'));

async function click(el: Element) {
  await act(async () => { (el as HTMLElement).click(); });
}

afterEach(() => {
  if (host) {
    render(null, host);
    host.remove();
    host = null;
  }
});

describe('下拉框', () => {
  test('收起时只显示当前值；展开后候选出现在控件下方，当前值仍然看得见', async () => {
    mountSelect('b');
    expect(trigger().textContent).toContain('乙');
    expect(menu()).toBeNull();

    await click(trigger());
    expect(menu()).not.toBeNull();
    // 菜单挂在控件内部、靠 CSS 定位到下方——不是覆盖控件本身
    expect(menu()!.parentElement?.classList.contains('select')).toBe(true);
    expect(opts().map((o) => o.textContent)).toEqual(['甲', '乙', '丙第三条']);
    // 展开时按钮里的当前值没被候选盖掉（原生 select 的毛病）
    expect(trigger().textContent).toContain('乙');
    expect(trigger().getAttribute('aria-expanded')).toBe('true');
    expect(opts()[1]!.getAttribute('aria-selected')).toBe('true');
  });

  test('点一项就选中并收起', async () => {
    mountSelect('b');
    await click(trigger());
    await click(opts()[2]!);
    expect(trigger().textContent).toContain('丙');
    expect(menu()).toBeNull();
  });

  test('Esc 收起；点别处也收起，且不改变已选值', async () => {
    mountSelect('b');
    await click(trigger());
    await act(async () => {
      trigger().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(menu()).toBeNull();

    await click(trigger());
    await act(async () => {
      document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    });
    expect(menu()).toBeNull();
    expect(trigger().textContent).toContain('乙');
  });

  test('键盘：↓ 展开并移动，Enter 选中', async () => {
    mountSelect('b');
    await act(async () => {
      trigger().dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    });
    expect(menu()).not.toBeNull();
    await act(async () => {
      trigger().dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    });
    await act(async () => {
      trigger().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    });
    expect(trigger().textContent).toContain('丙');
  });
});

describe('多选下拉', () => {
  function mountMulti() {
    host = document.createElement('div');
    document.body.appendChild(host);
    function Demo() {
      const [v, setV] = useState(['a']);
      return <MultiSelect ariaLabel="多选演示" values={v} options={OPTIONS} onChange={setV} />;
    }
    render(<Demo />, host);
    return host;
  }
  const boxes = () => Array.from(document.querySelectorAll('.select-box'));
  const triggerM = () => document.querySelector('button[aria-label="多选演示"]') as HTMLButtonElement;
  const optsM = () => Array.from(document.querySelectorAll('[role="option"]'));
  const onBoxes = () => boxes().map((b) => b.classList.contains('on'));

  test('每行文字前有方框，勾选状态直接看出来；点整行就切换、菜单不收起', async () => {
    mountMulti();
    await click(triggerM());
    expect(boxes()).toHaveLength(3);
    expect(onBoxes()).toEqual([true, false, false]);
    // 方框只是标记，不是唯一的点击目标
    await click(optsM()[1]!.querySelector('.select-label')!);
    expect(onBoxes()).toEqual([true, true, false]);
    expect(menu()).not.toBeNull();
    // 再点一下同一行就等于取消勾选
    await click(optsM()[1]!);
    expect(onBoxes()).toEqual([true, false, false]);
    expect(triggerM().textContent).toContain('甲');
  });

  test('至少留一个：最后一项点不掉', async () => {
    mountMulti();
    await click(triggerM());
    await click(optsM()[0]!);
    expect(onBoxes()).toEqual([true, false, false]);
    expect(triggerM().textContent).toContain('甲');
  });
});
