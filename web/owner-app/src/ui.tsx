/** 管理台的公共小件：加载状态、筛选片、格式化。
 *
 * 只放"没有业务含义"的东西——各页自己决定怎么用，不在这里藏接口调用。
 */
import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { Link } from './router';

export type Loaded<T> = { k: 'loading' } | { k: 'error'; message: string } | { k: 'ok'; data: T };

/** 取一次数据；`deps` 变了就重取（换筛选条件时用）。 */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []): { state: Loaded<T>; reload: () => void } {
  const [state, setState] = useState<Loaded<T>>({ k: 'loading' });
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    setState({ k: 'loading' });
    fn().then(
      (data) => alive && setState({ k: 'ok', data }),
      (e) => alive && setState({ k: 'error', message: messageFor(e) }),
    );
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick, ...deps]);
  return { state, reload: () => setTick((n) => n + 1) };
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
 * 浏览器会给空着的 `input[type=date]` 自造一段占位：Chrome 画「年/月/日」、Safari 画
 * 「yyyy/mm/dd」。这些筛选框旁边本来就有「从 / 到 / 提交时间」之类的说明，那串占位只是
 * 噪音。所以这里按「有没有值」打 `has-value`，具体隐藏规则在 styles.css 的
 * `input.date-input`：没值时内部字段涂成透明，选完日期再正常显示。
 *
 * 点一下直接弹日历——空着时框里什么都看不见，不去瞄右下角的小图标也能用。
 */
export function DateInput({ value, onInput, label, class: cls }: {
  value: string;
  onInput: (v: string) => void;
  label: string;
  class?: string;
}) {
  return (
    <input
      type="date"
      class={`date-input${value ? ' has-value' : ''}${cls ? ` ${cls}` : ''}`}
      value={value}
      aria-label={label}
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
