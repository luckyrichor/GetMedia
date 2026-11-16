/** 管理台的公共小件：加载状态、筛选片、格式化。
 *
 * 只放"没有业务含义"的东西——各页自己决定怎么用，不在这里藏接口调用。
 */
import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';

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

export function Field({ label, value, mono }: { label: string; value: ComponentChildren; mono?: boolean }) {
  return (
    <div class="kv">
      <div class="kv-k">{label}</div>
      <div class={mono ? 'kv-v mono' : 'kv-v'}>{value}</div>
    </div>
  );
}
