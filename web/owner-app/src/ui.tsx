/** 管理台的公共小件：加载状态、筛选片、格式化。
 *
 * 只放"没有业务含义"的东西——各页自己决定怎么用，不在这里藏接口调用。
 */
import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { Link } from './router';
import { Select } from './select';

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

/** 页面标题区：标题 + 一行灰色说明（说明可选），右侧留给动作按钮。
 *
 *  各页原来各写一遍 `<div class="page-head"><h1>…</h1></div>`，字号和上下间距靠
 *  `.page-head` 兜着；抽成组件后「有没有副标题」不再影响标题的基线，各页也不会各长一样。
 *  概览用它带副标题，其余页只传 title，观感与改动前一致。
 */
export function PageHead({ title, sub, actions }: {
  title: ComponentChildren;
  /** 标题下的一行说明。写实际含义，别写「这里是概览」这种废话。 */
  sub?: ComponentChildren;
  actions?: ComponentChildren;
}) {
  return (
    <div class="page-head">
      <div class="page-head-main">
        <h1>{title}</h1>
        {sub ? <p class="page-sub">{sub}</p> : null}
      </div>
      {actions ? <div class="page-head-acts">{actions}</div> : null}
    </div>
  );
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
          {/* 数量为 0 的段（「排队中 0」）压淡一点：它还是能点，只是提示"点进去是空的" */}
          {typeof it.n === 'number'
            ? <span class={it.n === 0 ? 'seg-n zero' : 'seg-n'}>{it.n}</span>
            : null}
        </button>
      ))}
    </div>
  );
}

export interface MenuItem {
  text: string;
  onClick: () => void;
  danger?: boolean;
  /** 在这一项**上方**画一条分隔线，用来隔开"危险操作"这一组。 */
  divider?: boolean;
}

/** 小下拉菜单：低频/有风险的操作用它收起来（行尾的「⋯」、「快捷」）。
 *
 * 弹层用 `position: fixed` + 打开时算一次坐标：表格外面套着 `overflow: auto` 的滚动容器，
 * 用 absolute 会被裁掉一半；fixed 的包含块是视口，不受祖先 overflow 影响。
 * 下方放不下就翻到上方；`align="right"` 时右边缘与按钮右边缘对齐。
 * 滚动/点别处/Esc 都关——坐标是打开那一刻算的，滚走就不准了。
 * 键盘：↑↓ 移动、Home/End 跳首尾、Enter 选中（按钮原生）、Esc 关闭并把焦点还给按钮。
 */
export function Menu({ label, items, ariaLabel, align = 'left', disabled, variant = 'ghost', title }: {
  label?: ComponentChildren;
  items: MenuItem[];
  ariaLabel: string;
  align?: 'left' | 'right';
  disabled?: boolean;
  /** ghost = 裸文字；boxed = 跟输入框同款边框（筛选栏的「快捷」）；icon = 32×32 图标按钮。
   *  注意别叫 `field`：styles.css 里 `.field` 是表单行（`display: grid` + 下边距），会串味。 */
  variant?: 'ghost' | 'boxed' | 'icon';
  title?: string;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [pos, setPos] = useState({ top: 0, left: 0 });
  const root = useRef<HTMLSpanElement>(null);
  const pop = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    const onScroll = () => setOpen(false);
    document.addEventListener('mousedown', onDown);
    window.addEventListener('scroll', onScroll, true);
    window.addEventListener('resize', onScroll);
    return () => {
      document.removeEventListener('mousedown', onDown);
      window.removeEventListener('scroll', onScroll, true);
      window.removeEventListener('resize', onScroll);
    };
  }, [open]);

  // 打开后把焦点放到第一项，↑↓ 立刻可用
  useEffect(() => {
    if (!open) return;
    const els = pop.current?.querySelectorAll<HTMLElement>('[role="menuitem"]');
    els?.[active]?.focus();
  }, [open, active]);

  /** 贴在按钮下方；下面放不下就翻到上方，左右按 align 对齐并夹在视口内。 */
  function toggle() {
    const r = root.current?.getBoundingClientRect();
    if (r) {
      const w = 168;
      const h = items.length * 36 + 8 + items.filter((i) => i.divider).length * 9;
      const below = r.bottom + 6;
      const top = below + h > window.innerHeight - 8 ? Math.max(8, r.top - h - 6) : below;
      const raw = align === 'right' ? r.right - w : r.left;
      const left = Math.min(Math.max(8, raw), Math.max(8, window.innerWidth - w - 8));
      setPos({ top, left });
      setActive(0);
    }
    setOpen((v) => !v);
  }

  function onMenuKey(e: KeyboardEvent) {
    const n = items.length;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => (i + 1) % n);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => (i - 1 + n) % n);
    } else if (e.key === 'Home') {
      e.preventDefault();
      setActive(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      setActive(n - 1);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      setOpen(false);
      trigger.current?.focus();
    } else if (e.key === 'Tab') {
      setOpen(false);
    }
  }

  const btnClass = ['menu-btn', variant === 'icon' ? 'icon' : '', variant === 'boxed' ? 'boxed' : '', open ? 'on' : '']
    .filter(Boolean).join(' ');

  return (
    <span class="menu-wrap" ref={root}>
      <button
        type="button"
        ref={trigger}
        class={btnClass}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={ariaLabel}
        title={title ?? (variant === 'icon' ? ariaLabel : undefined)}
        disabled={disabled}
        onClick={toggle}
        onKeyDown={(e) => {
          if (!open && e.key === 'ArrowDown') {
            e.preventDefault();
            toggle();
          }
        }}
      >
        {variant === 'icon' ? <span aria-hidden="true">⋯</span> : label}
      </button>
      {open ? (
        <div
          class="menu-pop"
          role="menu"
          aria-label={ariaLabel}
          ref={pop}
          style={{ top: `${pos.top}px`, left: `${pos.left}px` }}
          onKeyDown={onMenuKey}
        >
          {items.map((it, i) => (
            <>
              {it.divider ? <div class="menu-sep" role="separator" /> : null}
              <button
                type="button"
                role="menuitem"
                class={[it.danger ? 'menu-item danger' : 'menu-item', i === active ? 'active' : ''].filter(Boolean).join(' ')}
                onClick={() => {
                  setOpen(false);
                  it.onClick();
                }}
                onMouseEnter={() => setActive(i)}
              >
                {it.text}
              </button>
            </>
          ))}
        </div>
      ) : null}
    </span>
  );
}

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/** 体积拆成"数字 + 单位"两段：占用列要按数字右对齐，单位单独一栏。 */
export function fmtBytesParts(n: number | null | undefined): { num: string; unit: string } {
  if (n == null || !Number.isFinite(n) || n < 0) return { num: '—', unit: '' };
  let v = n, i = 0;
  while (v >= 1024 && i < UNITS.length - 1) { v /= 1024; i++; }
  return { num: i === 0 ? String(v) : v.toFixed(v < 10 ? 1 : 0), unit: UNITS[i] ?? 'B' };
}

/** 体积的「数字 + 单位」两段式：权限管理、批量提取两页的占用列共用。
 *
 *  外面这层是固定宽度的 inline-grid：左格数字右对齐、右格单位定宽左对齐——
 *  不这么拆，「229 MB」和「0 B」的数字右边缘就对不齐（位数不同，单位会被顶来顶去）。
 *  容器本身在单元格里居中，所以整列的视觉中心落在"数字+单位"这个整体上，而不是贴着右边缘。
 */
export function BytesCell({ n }: { n: number | null | undefined }) {
  const p = fmtBytesParts(n);
  return (
    <span class="bytes">
      <span class="bytes-n">{p.num}</span>
      <span class="bytes-u">{p.unit}</span>
    </span>
  );
}

/** 卡片里的「数字 + 单位」：数字大号加粗、单位小号淡灰，按基线对齐。
 *
 *  和表格里的 `BytesCell` 是同一套观感（数字等宽、单位弱化），区别只在大字号。
 *  数字与单位之间那个空格写成 `{' '}`：flex 容器不会把它渲染成间距（间距由 gap 给），
 *  但 `textContent` 里留着它——复制出来、读屏念出来都还是「5 GB」。
 */
export function NumUnit({ num, unit, size = 'lg' }: {
  num: ComponentChildren; unit?: string; size?: 'lg' | 'sm';
}) {
  return (
    <span class={size === 'sm' ? 'num-unit sm' : 'num-unit'}>
      <span class="num-unit-n">{num}</span>
      {unit ? <>{' '}<span class="num-unit-u">{unit}</span></> : null}
    </span>
  );
}

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
 * - 没值也没聚焦：把内部字段涂透明，盖一行调用方给的占位（「开始日期」），
 *   框里不至于空得像坏了；
 * - 聚焦但仍没值：换成自己的「年/月/日」，不让系统那串英文/数字格式露出来；
 * - 一按数字键就撤掉这行字、把原生字段放回来，否则看不见正在输入的数字。
 *
 * 只有数字键算"开始手输"。空框上按退格/方向键什么都不会发生，没必要把英文占位放回来。
 * 点一下直接弹日历——空着时框里什么都没写，不去瞄右下角的小图标也能用。
 */
export function DateInput({ value, onInput, label, placeholder, class: cls }: {
  value: string;
  onInput: (v: string) => void;
  label: string;
  /** 没聚焦、没值时的占位文案；不给就什么都不显示（保持原来的干净空框） */
  placeholder?: string;
  class?: string;
}) {
  const [focused, setFocused] = useState(false);
  const [typing, setTyping] = useState(false);
  const hintText = value || typing ? '' : focused ? '年/月/日' : placeholder ?? '';
  return (
    <span class={`date-wrap${focused && hintText ? ' hinting' : ''}`}>
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
      {hintText ? <span class="date-hint" aria-hidden="true">{hintText}</span> : null}
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

/** 确认弹窗：替掉 `window.confirm`。
 *
 *  原生 confirm 会把「<服务器 IP> 显示：」这句浏览器自带的前缀也画出来，跟页面完全不是一个语气；
 *  而且它拦不住"删多少条"这种需要列清单的场景。
 *
 *  - `danger` 时确认键走红色（`.btn-danger`），和页面里的「播放 / 下载」拉开距离；
 *  - **默认焦点放在「取消」**：这两个按钮是并排的，误按一次回车的代价是删数据，不值得；
 *  - 两个按钮都受 `busy` 控制，提交中连点不会重复发请求。
 */
export function ConfirmDialog({ title, children, confirmText = '确定', danger = false,
  busy = false, onConfirm, onCancel }: {
  title: string;
  children?: ComponentChildren;
  confirmText?: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Modal title={title} onClose={onCancel}>
      <div class="confirm-body">{children}</div>
      <div class="filter-row modal-actions">
        {/* 取消在前：Modal 打开时把焦点给第一个按钮，正好落在「取消」上 */}
        <button type="button" class="btn btn-ghost" disabled={busy} onClick={onCancel}>取消</button>
        <button type="button" class={danger ? 'btn btn-danger' : 'btn'} disabled={busy} onClick={onConfirm}>
          {confirmText}
        </button>
      </div>
    </Modal>
  );
}

/** 页码序列：≤7 页全列，否则首尾 + 当前左右各一页，中间省略号。
 *  批量提取的分页与媒体库共用（原来写在 `pages/creators.tsx` 里）。 */
export function pageList(cur: number, pages: number): (number | '…')[] {
  if (pages <= 7) return Array.from({ length: pages }, (_, i) => i + 1);
  const out: (number | '…')[] = [1];
  const from = Math.max(2, cur - 1);
  const to = Math.min(pages - 1, cur + 1);
  if (from > 2) out.push('…');
  for (let p = from; p <= to; p++) out.push(p);
  if (to < pages - 1) out.push('…');
  out.push(pages);
  return out;
}

/** 分页条：只有一页时整条不渲染（不占位置，也不留一条空导航）。
 *
 *  例外：给了 `onPageSize` 就**一定要渲染**——只有 1 页时也得让用户能把「每页 20 条」
 *  改回 5 条，否则切到 20 条后整条消失，就再也切不回来了。
 */
export function Pager({ page, pages, onPage, total, unit = '条', label = '分页',
  pageSize, sizeOptions, onPageSize }: {
  page: number;
  pages: number;
  onPage: (p: number) => void;
  /** 总条数：给了就在右侧补一句「共 N 条」，省得用户自己按页数乘 */
  total?: number;
  unit?: string;
  label?: string;
  /** 每页条数（配合 `sizeOptions` / `onPageSize` 才出现选择器） */
  pageSize?: number;
  sizeOptions?: readonly number[];
  onPageSize?: (n: number) => void;
}) {
  const canSize = !!(onPageSize && sizeOptions && sizeOptions.length > 1 && pageSize);
  if (pages <= 1 && !canSize) return null;
  const multi = pages > 1;
  return (
    <nav class="pager" aria-label={label}>
      {multi && (
        <button type="button" class="btn btn-ghost small" disabled={page <= 1}
                onClick={() => onPage(page - 1)}>
          上一页
        </button>
      )}
      {multi && pageList(page, pages).map((p) => (
        p === '…'
          ? <span class="pager-gap" aria-hidden="true">…</span>
          : (
            <button
              type="button"
              class={p === page ? 'btn small pager-on' : 'btn btn-ghost small'}
              aria-current={p === page ? 'page' : undefined}
              onClick={() => onPage(p)}
            >
              {p}
            </button>
          )
      ))}
      {/* 右侧一组：每页条数 + 「第 x / y 页，共 N 条」。整体 margin-left:auto 右推，
          这样「下一页」始终是最后一项、位置不随页码按钮数量漂移。 */}
      <div class="pager-meta">
        {canSize && (
          <span class="pager-size">
            <span class="small muted">每页</span>
            <Select
              value={String(pageSize)}
              options={sizeOptions!.map((n) => ({ value: String(n), label: `${n} 条` }))}
              onChange={(v) => onPageSize!(Number(v))}
              ariaLabel="每页显示条数"
            />
          </span>
        )}
        <span class="small muted pager-total">
          第 {page} / {pages} 页{total == null ? '' : `，共 ${total} ${unit}`}
        </span>
      </div>
      {multi && (
        <button type="button" class="btn btn-ghost small" disabled={page >= pages}
                onClick={() => onPage(page + 1)}>
          下一页
        </button>
      )}
    </nav>
  );
}

/** 表格里的时间：日期一段（正常色）+ 时分一段（小一号、淡灰），整格不显得笨重。
 *
 *  `at` 为空给「—」（邀请码的到期时间 / 使用时间本来就可能是空）。
 *  `cls` 用来整体染色，比如快到期给 `text-warn`、已过期给 `text-bad`。
 *  `secs` 默认带上秒（权限管理、邀请码要精确到秒）；批量提取的「发起时间」只要到分。
 */
export function DateTimeCell({ at, cls, secs = true }: {
  at: number | null | undefined; cls?: string; secs?: boolean;
}) {
  if (!at) return <>—</>;
  const s = fmtDateTime(at);
  return (
    <span class={cls ? `dt ${cls}` : 'dt'}>
      <span>{s.slice(0, 10)}</span><span class="dt-t">{secs ? s.slice(11) : s.slice(11, 16)}</span>
    </span>
  );
}

export type ToastKind = 'ok' | 'err' | 'warn';
export interface ToastAction { label: string; onClick: () => void }

/** 轻量提示条：底部居中浮一条，到点自己消失。
 *
 *  用它而不是把结果塞进页面流里——生成/复制这类"一次性回执"不该把下面的内容推下去。
 *  返回 `node` 直接放进页面，`show` 随时可以再叫。同一个页面只留一条（新的顶掉旧的）。
 *
 *  带 `action` 时（「不再显示」的撤销）多给一个按钮，停留时间也放宽到 6 秒——
 *  2.6 秒不够人反应过来去点。
 */
export function useToast(timeout = 2600): {
  show: (text: string, kind?: ToastKind, action?: ToastAction) => void;
  node: ComponentChildren;
} {
  const [t, setT] = useState<{ id: number; text: string; kind: ToastKind; action?: ToastAction } | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const clear = () => {
    if (timer.current !== undefined) window.clearTimeout(timer.current);
    timer.current = undefined;
  };
  useEffect(() => clear, []);
  const show = (text: string, kind: ToastKind = 'ok', action?: ToastAction) => {
    clear();
    setT({ id: Date.now(), text, kind, action });
    timer.current = window.setTimeout(() => setT(null), action ? Math.max(timeout, 6000) : timeout);
  };
  const node = t ? (
    <div class={`toast toast-${t.kind}`} role="status" aria-live="polite">
      <span class="toast-t">{t.text}</span>
      {t.action ? (
        <button
          type="button"
          class="toast-act"
          onClick={() => {
            t.action!.onClick();
            setT(null);
          }}
        >
          {t.action.label}
        </button>
      ) : null}
    </div>
  ) : null;
  return { show, node };
}

/** 本地时区的 `YYYY-MM-DD`，也就是 `input[type=date]` 的取值格式。
 *  日期框、`dayStart/dayEnd` 三者都按本地时区解释，口径一致。 */
export function ymd(d: Date): string {
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}

export function daysAgo(n: number): Date {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d;
}

/** 'YYYY-MM-DD' → 当天 00:00:00 的 unix 秒；空串或非法返回 null（不筛）。 */
export function dayStartSec(iso: string): number | null {
  const [y, m, d] = iso.split('-').map(Number);
  if (!y || !m || !d) return null;
  return Math.floor(new Date(y, m - 1, d).getTime() / 1000);
}

/** 'YYYY-MM-DD' → 当天 23:59:59 的 unix 秒（闭区间上限）；空串或非法返回 null。 */
export function dayEndSec(iso: string): number | null {
  const [y, m, d] = iso.split('-').map(Number);
  if (!y || !m || !d) return null;
  return Math.floor(new Date(y, m - 1, d, 23, 59, 59).getTime() / 1000);
}

/** 日期范围筛选：两个日期框用「至」连起来，右边跟一个「快捷」菜单。
 *
 *  权限管理（创建时间）与批量提取（发起时间）两页共用同一套交互：
 *  - 两端**默认都是空**（空 = 不筛），只填一端也生效；
 *  - 起晚于止时直接对调，不让人对着倒置的区间猜结果；
 *  - 区间含当天（换算成 00:00:00 ~ 23:59:59 由调用方做，这里只给 `YYYY-MM-DD`）。
 */
export function DateRange({ from, to, onChange, label, quickLabel = '快捷 ▾' }: {
  from: string;
  to: string;
  onChange: (from: string, to: string) => void;
  /** 「创建时间」「发起时间」这类列名：同时拼出 aria-label（`${label}开始` / `${label}截止`） */
  label: string;
  quickLabel?: string;
}) {
  function changeFrom(v: string) {
    // 起点跑到终点后面：两个框对调，而不是留一个倒置区间
    if (v && to && v > to) return onChange(to, v);
    onChange(v, to);
  }
  function changeTo(v: string) {
    if (v && from && v < from) return onChange(v, from);
    onChange(from, v);
  }
  const now = new Date();
  return (
    <span class="date-range">
      <span class="date-label">{label}</span>
      <DateInput label={`${label}开始`} placeholder="开始日期" value={from} onInput={changeFrom} />
      <span class="muted">至</span>
      <DateInput label={`${label}截止`} placeholder="结束日期" value={to} onInput={changeTo} />
      <Menu
        label={quickLabel}
        variant="boxed"
        ariaLabel={`${label}快捷范围`}
        items={[
          { text: '今天', onClick: () => onChange(ymd(now), ymd(now)) },
          { text: '近 7 天', onClick: () => onChange(ymd(daysAgo(6)), ymd(now)) },
          { text: '近 30 天', onClick: () => onChange(ymd(daysAgo(29)), ymd(now)) },
          { text: '本月', onClick: () => onChange(ymd(new Date(now.getFullYear(), now.getMonth(), 1)), ymd(now)) },
          { text: '清除日期', onClick: () => onChange('', '') },
        ]}
      />
    </span>
  );
}

/** 步进数字框：白底 + 1px 浅灰边 + 8px 圆角，和筛选栏的日期框、下拉同一副样子。
 *  越界（空 / 小数 / 超范围）由调用方判，`invalid` 只负责把边框染红。
 *  邀请码的「生成数量」与批量提取的「条数 / 起始序号」共用。
 */
export function Stepper({ value, onChange, min, max, invalid, disabled, id, placeholder, title, ariaLabel, describedBy }: {
  value: string;
  onChange: (v: string) => void;
  min: number;
  max: number;
  invalid?: boolean;
  disabled?: boolean;
  id?: string;
  placeholder?: string;
  title?: string;
  ariaLabel?: string;
  describedBy?: string;
}) {
  const n = Number(value);
  const base = Number.isFinite(n) ? n : min;
  const step = (d: number) => onChange(String(Math.min(max, Math.max(min, Math.round(base) + d))));
  return (
    <div class={invalid ? 'stepper is-bad' : 'stepper'}>
      <button type="button" class="stepper-btn" aria-label="减少一个"
              disabled={disabled || base <= min} onClick={() => step(-1)}>−</button>
      <input
        id={id}
        class="stepper-input"
        type="number"
        inputMode="numeric"
        min={min}
        max={max}
        step="1"
        placeholder={placeholder}
        title={title}
        aria-label={ariaLabel}
        aria-invalid={invalid}
        aria-describedby={invalid ? describedBy : undefined}
        value={value}
        onInput={(e) => onChange(e.currentTarget.value)}
      />
      <button type="button" class="stepper-btn" aria-label="增加一个"
              disabled={disabled || base >= max} onClick={() => step(1)}>+</button>
    </div>
  );
}

