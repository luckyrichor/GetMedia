/** 管理台的公共小件：加载状态、筛选片、格式化。
 *
 * 只放"没有业务含义"的东西——各页自己决定怎么用，不在这里藏接口调用。
 */
import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { Link } from './router';

export type Loaded<T> = { k: 'loading' } | { k: 'error'; message: string } | { k: 'ok'; data: T };

/** 取一次数据；`deps` 变了就重取（换筛选条件时用）。
 *
 * `keep: true` 时保留上一次成功的数据，只把 `refreshing` 置真——换筛选/翻页时不要把
 * 列表抽掉换成骨架：高度一塌，浏览器会把滚动位置夹回顶部，看起来就是"页面跳了"。
 * 首次加载仍然给 loading（那时本来就没有内容可留）。
 */
export function useLoad<T>(
  fn: () => Promise<T>,
  deps: unknown[] = [],
  opts: { keep?: boolean } = {},
): { state: Loaded<T>; reload: () => void; refreshing: boolean } {
  const [state, setState] = useState<Loaded<T>>({ k: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  const [tick, setTick] = useState(0);
  /** 手上是否已经有成功数据：有才值得"保留旧内容"，没有就只能给骨架。 */
  const hasData = useRef(false);
  useEffect(() => {
    let alive = true;
    if (opts.keep && hasData.current) setRefreshing(true);
    else setState({ k: 'loading' });
    fn().then(
      (data) => {
        if (!alive) return;
        hasData.current = true;
        setState({ k: 'ok', data });
        setRefreshing(false);
      },
      (e) => {
        if (!alive) return;
        hasData.current = false;
        setState({ k: 'error', message: messageFor(e) });
        setRefreshing(false);
      },
    );
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick, ...deps]);
  return { state, reload: () => setTick((n) => n + 1), refreshing };
}

export function Loading({ what = '数据' }: { what?: string }) {
  return (
    <div class="skeleton" aria-busy="true" aria-label={`正在加载${what}`}>
      <div /><div /><div />
    </div>
  );
}

export function ErrorBox({ message, retry }: { message: string; retry: () => void }) {
  return (
    <div class="alert warn">
      <strong>{message}</strong>
      <p class="small muted" style="margin:6px 0 0">重试只重新读取，不会改动任何数据。</p>
      <p style="margin:8px 0 0"><button type="button" class="btn small" onClick={retry}>重试</button></p>
    </div>
  );
}

export function Empty({ children }: { children: ComponentChildren }) {
  return <div class="empty">{children}</div>;
}

/** 管理员专属页被非 admin 直接输 URL 打开时的占位。
 *  导航里本来就不显示，所以走到这里基本是手输地址或旧书签；服务端那边也会回 403。 */
export function NoAccess({ what }: { what: string }) {
  return (
    <>
      <h1>无权访问</h1>
      <div class="alert warn">
        <strong>{what}</strong>只有管理员能打开。当前账号权限不足，服务端接口同样会拒绝。
      </div>
      <p><Link to="/">回到概览</Link></p>
    </>
  );
}

export interface ChipItem { key: string; label: string; n?: number }

export function Chips({ items, value, onChange, ariaLabel }: {
  items: ChipItem[]; value: string; onChange: (k: string) => void; ariaLabel: string;
}) {
  return (
    <div class="chips" role="tablist" aria-label={ariaLabel}>
      {items.map((it) => (
        <button
          type="button"
          role="tab"
          aria-selected={value === it.key}
          class={value === it.key ? 'chip on' : 'chip'}
          onClick={() => onChange(it.key)}
        >
          {it.label}
          {typeof it.n === 'number' ? <span class="chip-n">{it.n}</span> : null}
        </button>
      ))}
    </div>
  );
}

export interface SegItem { key: string; label: string; n?: number }

/** 分段控件：单选，选中项浮起来（浅色底 + 轻阴影），每段可带一个数量。
 *  与 `Chips` 的区别是语义——这里"永远有一项被选中"（含"全部"），不是多选开关。 */
export function Segmented({ items, value, onChange, ariaLabel }: {
  items: SegItem[]; value: string; onChange: (k: string) => void; ariaLabel: string;
}) {
  return (
    <div class="seg" role="tablist" aria-label={ariaLabel}>
      {items.map((it) => (
        <button
          type="button"
          role="tab"
          aria-selected={value === it.key}
          class={value === it.key ? 'seg-btn on' : 'seg-btn'}
          onClick={() => onChange(it.key)}
        >
          {it.label}
          {typeof it.n === 'number' ? <span class="seg-n">{it.n}</span> : null}
        </button>
      ))}
    </div>
  );
}

export interface MenuItem { text: string; onClick: () => void; danger?: boolean }

/** 小下拉菜单：低频/有风险的操作用它收起来（"更多 ⋯"、"快捷"）。
 *
 * 弹层用 `position: fixed` + 打开时算一次坐标：表格外面套着 `overflow: auto` 的滚动容器，
 * 用 absolute 会被裁掉一半；fixed 的包含块是视口，不受祖先 overflow 影响。
 * 滚动/点别处/Esc 都关——坐标是打开那一刻算的，滚走就不准了。
 */
export function Menu({ label, items, ariaLabel, align = 'left', disabled }: {
  label: ComponentChildren;
  items: MenuItem[];
  ariaLabel: string;
  align?: 'left' | 'right';
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState({ top: 0, left: 0 });
  const root = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    const onScroll = () => setOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    window.addEventListener('scroll', onScroll, true);
    window.addEventListener('resize', onScroll);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('scroll', onScroll, true);
      window.removeEventListener('resize', onScroll);
    };
  }, [open]);

  /** 贴在按钮下方；下面放不下就翻到上方，左右按 align 对齐并夹在视口内。 */
  function toggle() {
    const r = root.current?.getBoundingClientRect();
    if (r) {
      const w = 176;
      const h = items.length * 34 + 10;
      const below = r.bottom + 6;
      const top = below + h > window.innerHeight - 8 ? Math.max(8, r.top - h - 6) : below;
      const raw = align === 'right' ? r.right - w : r.left;
      const left = Math.min(Math.max(8, raw), Math.max(8, window.innerWidth - w - 8));
      setPos({ top, left });
    }
    setOpen((v) => !v);
  }

  return (
    <span class="menu-wrap" ref={root}>
      <button
        type="button"
        class={open ? 'menu-btn on' : 'menu-btn'}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={ariaLabel}
        disabled={disabled}
        onClick={toggle}
      >
        {label}
      </button>
      {open ? (
        <div class="menu-pop" role="menu" aria-label={ariaLabel} style={{ top: `${pos.top}px`, left: `${pos.left}px` }}>
          {items.map((it) => (
            <button
              type="button"
              role="menuitem"
              class={it.danger ? 'menu-item danger' : 'menu-item'}
              onClick={() => {
                setOpen(false);
                it.onClick();
              }}
            >
              {it.text}
            </button>
          ))}
        </div>
      ) : null}
    </span>
  );
}

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];
export function fmtBytes(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n) || n < 0) return '—';
  let v = n, i = 0;
  while (v >= 1024 && i < UNITS.length - 1) { v /= 1024; i++; }
  return `${i === 0 ? v : v.toFixed(v < 10 ? 1 : 0)} ${UNITS[i]}`;
}

export function fmtDuration(sec: number | null | undefined): string {
  if (sec == null || !Number.isFinite(sec) || sec < 0) return '—';
  const s = Math.round(sec);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const two = (x: number) => String(x).padStart(2, '0');
  return h > 0 ? `${h}:${two(m)}:${two(r)}` : `${m}:${two(r)}`;
}

/** `2026-11-16 08:50`（本地时区）；只用于"什么时候提交的"这类展示。 */
export function fmtTime(unix: number | null | undefined): string {
  if (!unix) return '—';
  const d = new Date(unix * 1000);
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())} ${two(d.getHours())}:${two(d.getMinutes())}`;
}

/** `2026-11-16 08:50:12`：要精确到秒的场景（任务发起时间）用它。 */
export function fmtDateTime(unix: number | null | undefined): string {
  if (!unix) return '—';
  const d = new Date(unix * 1000);
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())} `
    + `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
}

export function relTime(unix: number | null | undefined, now = Date.now()): string {
  if (!unix) return '—';
  const d = Math.round(now / 1000 - unix);
  if (d < 0) return '刚刚';
  if (d < 60) return `${d} 秒前`;
  if (d < 3600) return `${Math.floor(d / 60)} 分钟前`;
  if (d < 86400) return `${Math.floor(d / 3600)} 小时前`;
  if (d < 86400 * 30) return `${Math.floor(d / 86400)} 天前`;
  return fmtTime(unix);
}

/** 日期选择框。
 *
 * 浏览器会给空着的 `input[type=date]` 自造一段占位，**语言跟系统走**：中文环境是
 * 「年/月/日」，英文环境是「yyyy/mm/dd」。这些筛选框旁边本来就有「从 / 到 / 提交时间」
 * 之类的说明，那串占位纯属噪音，所以：
 *
 * - 没值也没聚焦：把内部字段涂透明，框里干干净净（styles.css 的 `input.date-input`）；
 * - 聚焦但仍没值：盖一行自己的「年/月/日」，不让系统那串英文/数字格式露出来；
 * - 一按数字键就撤掉这行字、把原生字段放回来，否则看不见正在输入的数字。
 *
 * 只有数字键算"开始手输"。空框上按退格/方向键什么都不会发生，没必要把英文占位放回来。
 * 点一下直接弹日历——空着时框里什么都没写，不去瞄右下角的小图标也能用。
 */
export function DateInput({ value, onInput, label, class: cls }: {
  value: string;
  onInput: (v: string) => void;
  label: string;
  class?: string;
}) {
  const [focused, setFocused] = useState(false);
  const [typing, setTyping] = useState(false);
  const hint = focused && !value && !typing;
  return (
    <span class={`date-wrap${hint ? ' hinting' : ''}`}>
      <input
        type="date"
        class={`date-input${value ? ' has-value' : ''}${cls ? ` ${cls}` : ''}`}
        value={value}
        aria-label={label}
        onFocus={() => setFocused(true)}
        onBlur={() => {
          setFocused(false);
          setTyping(false);
        }}
        onKeyDown={(e) => {
          // 数字键 = 开始手输；Tab / Esc / 退格 / 方向键都不算，中文占位留着
          if (/^\d$/.test(e.key)) setTyping(true);
        }}
        onPaste={() => setTyping(true)}
        onInput={(e) => onInput(e.currentTarget.value)}
        onClick={(e) => {
          const el = e.currentTarget as HTMLInputElement & { showPicker?: () => void };
          try {
            el.showPicker?.();
          } catch {
            // 浏览器不支持或不在用户手势里：退回键盘输入，不影响使用
          }
        }}
      />
      {hint ? <span class="date-hint" aria-hidden="true">年/月/日</span> : null}
    </span>
  );
}

/** 覆盖整页的对话框：点遮罩或按 Esc 关闭，打开期间锁住背景滚动、焦点挪进来。
 *
 * 没用原生 `<dialog>`：`showModal()` 在 jsdom 里不可用，测试就没法断言"到底弹出来了没有"。
 */
export function Modal({ title, onClose, children }: {
  title: string;
  onClose: () => void;
  children: ComponentChildren;
}) {
  const box = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  useEffect(() => {
    close.current = onClose;
  });
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close.current();
    };
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    // 打开就把焦点挪进来，Tab 不会先跑到背后的表格上
    box.current?.querySelector<HTMLElement>('input, button, select, textarea')?.focus();
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, []);
  return (
    <div class="modal-mask" onClick={() => close.current()}>
      <div class="modal" role="dialog" aria-modal="true" aria-label={title} ref={box}
           onClick={(e) => e.stopPropagation()}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}

export function Field({ label, value, mono }: { label: string; value: ComponentChildren; mono?: boolean }) {
  return (
    <div class="kv">
      <div class="kv-k">{label}</div>
      <div class={mono ? 'kv-v mono' : 'kv-v'}>{value}</div>
    </div>
  );
}
