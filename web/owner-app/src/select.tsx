/** 下拉选择：候选列表展开在**输入框下方**，已经选中的值始终留在框里看得见。
 *
 *  为什么不用原生 `<select>`：macOS/Windows 的弹出层会盖住控件本身，用户点开后
 *  看不到「我现在选的是什么」，长列表（比如媒体库几十条视频）尤其难受。原生弹出层
 *  是浏览器画的，没法用 CSS 调整，所以这里自己画一个：外观与原生一致（高度、边框、
 *  内边距沿用输入框样式），展开后是一块带阴影的列表，压在下方内容上，不会遮住当前值。
 *
 *  键盘：Enter/Space/↑/↓ 展开，↑↓ 移动，Home/End 跳首尾，Enter 选中，Esc 关闭。
 */
import { useEffect, useRef, useState } from 'preact/hooks';

export interface SelectOption {
  value: string;
  label: string;
  /** 可选的一行说明（如存放方式），显示在名称右侧 */
  hint?: string;
}

export function Select({ value, options, onChange, ariaLabel, disabled, placeholder = '请选择' }: {
  value: string;
  options: SelectOption[];
  onChange: (value: string) => void;
  ariaLabel?: string;
  disabled?: boolean;
  placeholder?: string;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLUListElement>(null);

  const current = options.find((o) => o.value === value);

  // 点别处关掉；页面滚动也关掉，免得列表跟控件脱节
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    const onScroll = () => setOpen(false);
    document.addEventListener('mousedown', onDown);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      document.removeEventListener('mousedown', onDown);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [open]);

  // 每次展开都把高亮落在当前值上
  useEffect(() => {
    if (!open) return;
    const idx = options.findIndex((o) => o.value === value);
    setActive(idx >= 0 ? idx : 0);
  }, [open]);

  // 键盘移动时让高亮项始终在可视区域内（jsdom 等环境没有 scrollIntoView，直接跳过）
  useEffect(() => {
    if (!open) return;
    const el = menu.current?.querySelectorAll<HTMLElement>('[role="option"]')[active];
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' });
  }, [open, active]);

  function choose(index: number) {
    const opt = options[index];
    if (!opt) return;
    onChange(opt.value);
    setOpen(false);
    trigger.current?.focus();
  }

  function onKeyDown(e: KeyboardEvent) {
    if (disabled) return;
    if (!open) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        setOpen(true);
      }
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      setOpen(false);
      trigger.current?.focus();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => Math.min(options.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => Math.max(0, i - 1));
    } else if (e.key === 'Home') {
      e.preventDefault();
      setActive(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      setActive(Math.max(0, options.length - 1));
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      choose(active);
    } else if (e.key === 'Tab') {
      setOpen(false);
    }
  }

  return (
    <div class="select" ref={root}>
      <button
        type="button"
        ref={trigger}
        class={open ? 'select-trigger on' : 'select-trigger'}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={onKeyDown}
      >
        <span class="select-value">{current ? current.label : placeholder}</span>
        <span class="select-caret" aria-hidden="true">▾</span>
      </button>
      {open && (
        <ul class="select-menu" role="listbox" aria-label={ariaLabel} ref={menu}>
          {options.map((o, i) => (
            <li
              key={o.value}
              role="option"
              aria-selected={o.value === value}
              class={[
                'select-opt',
                o.value === value ? 'on' : '',
                i === active ? 'active' : '',
              ].filter(Boolean).join(' ')}
              onMouseDown={(e) => e.preventDefault()}
              onMouseEnter={() => setActive(i)}
              onClick={() => choose(i)}
            >
              <span class="select-label">{o.label}</span>
              {o.hint ? <span class="select-hint">{o.hint}</span> : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
