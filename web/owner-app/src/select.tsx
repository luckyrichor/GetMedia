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
  /** 悬停在这一项上时弹出的小框（长一点的解释放这里，别把选项撑成两行） */
  tip?: string;
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
  const [tipAt, setTipAt] = useState<number | null>(null);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLUListElement>(null);

  const current = options.find((o) => o.value === value);
  const hasTips = options.some((o) => o.tip);

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
    if (!open) {
      setTipAt(null);
      return;
    }
    const idx = options.findIndex((o) => o.value === value);
    setActive(idx >= 0 ? idx : 0);
  }, [open]);

  // 键盘移动时让高亮项始终在可视区域内（jsdom 等环境没有 scrollIntoView，直接跳过）
  useEffect(() => {
    if (!open) return;
    const el = menu.current?.querySelectorAll<HTMLElement>('[role="option"]')[active];
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' });
  }, [open, active]);

  /** 鼠标悬停 / 键盘移动时只对「有说明的那一项」弹提示；刚展开时不弹，免得一开就顶一个框。 */
  function moveTo(index: number) {
    setActive(index);
    setTipAt(options[index]?.tip ? index : null);
  }

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
      moveTo(Math.min(options.length - 1, active + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      moveTo(Math.max(0, active - 1));
    } else if (e.key === 'Home') {
      e.preventDefault();
      moveTo(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      moveTo(Math.max(0, options.length - 1));
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
        <ul class={hasTips ? 'select-menu tips' : 'select-menu'} role="listbox" aria-label={ariaLabel} ref={menu}>
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
              onMouseEnter={() => moveTo(i)}
              onMouseLeave={() => setTipAt((cur) => (cur === i ? null : cur))}
              onClick={() => choose(i)}
            >
              <span class="select-label">{o.label}</span>
              {o.hint ? <span class="select-hint">{o.hint}</span> : null}
              {o.tip && tipAt === i ? <span class="select-tip" role="tooltip">{o.tip}</span> : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** 多选下拉：候选是一列勾选项，勾完不关闭；框里始终显示「已选哪几个」。 */
export function MultiSelect({ values, options, onChange, ariaLabel, disabled, placeholder = '请选择' }: {
  values: string[];
  options: SelectOption[];
  onChange: (values: string[]) => void;
  ariaLabel?: string;
  disabled?: boolean;
  placeholder?: string;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

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

  const picked = options.filter((o) => values.includes(o.value));

  /** 至少留一个：全不选等于没说，而「要什么格式」没有默认含义。 */
  function toggle(value: string) {
    if (values.includes(value)) {
      if (values.length === 1) return;
      onChange(values.filter((v) => v !== value));
    } else {
      onChange([...values, value]);
    }
  }

  return (
    <div class="select" ref={root}>
      <button
        type="button"
        class={open ? 'select-trigger on' : 'select-trigger'}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        onClick={() => setOpen((v) => !v)}
      >
        <span class="select-value">
          {picked.length ? picked.map((o) => o.label).join('、') : placeholder}
        </span>
        <span class="select-caret" aria-hidden="true">▾</span>
      </button>
      {open && (
        <ul class="select-menu multi" role="listbox" aria-label={ariaLabel} aria-multiselectable="true">
          {options.map((o) => {
            const on = values.includes(o.value);
            return (
              <li
                key={o.value}
                role="option"
                aria-selected={on}
                class={on ? 'select-opt on' : 'select-opt'}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => toggle(o.value)}
              >
                {/* 方框只是「看得见选了几项」的标记，点这一行任何地方都算点它 */}
                <span class={on ? 'select-box on' : 'select-box'} aria-hidden="true" />
                <span class="select-label">{o.label}</span>
                {o.hint ? <span class="select-hint">{o.hint}</span> : null}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
