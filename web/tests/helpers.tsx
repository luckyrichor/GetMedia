/** 组件测试的公共设施：假服务端（替换全局 fetch）、挂载与等待、表单操作。 */
import { render } from 'preact';
import { act } from 'preact/test-utils';
import { vi } from 'vitest';
import { App } from '../public-app/src/App';

export interface Call { method: string; path: string; body: unknown; headers: Record<string, string>; init: RequestInit }
export interface Reply { status?: number; json?: unknown; headers?: Record<string, string> }
export type Handler = Reply | ((c: Call) => Reply);

export const sha = (c: string) => c.repeat(64);

export const LEGAL = {
  user_agreement: { version: 1, effective_date: '2026-10-30', sha256: sha('a'), text: '# GetMedia 用户协议\n\n版本：v1　生效日期：2026 年 10 月 30 日\n\n## 1. 本服务\n\n正文一' },
  privacy_policy: { version: 1, effective_date: '2026-10-30', sha256: sha('b'), text: '# GetMedia 隐私说明\n\n版本：v1\n\n## 1. 收集\n\n| 项 | 期限 |\n|---|---|\n| 账号 | 存续期间 |' },
};
export const CONFIG = {
  registration_open: true, invite_required: false, maintenance: null, min_version: '0.1.0', server_time: 1_800_000_000,
  latest: { version: '0.1.0', notes: '首个版本', apk: 'https://example.invalid/getmedia-0.1.0.apk', sha256: sha('c') },
};
export const ME = { id: 'acct_0123456789abcdef', login_name: 'alice', tier: 'free', tier_expires_at: null, consent_required: false, recovery_confirmed: true, status: 'active' };
export const unauthenticated: Reply = { status: 401, json: { detail: '请先登录', code: 'unauthenticated' } };

/** routes 的键是 "METHOD /服务端内部路径"。未登记的请求直接让测试失败（避免悄悄漏掉意外调用）。 */
export function fakeServer(routes: Record<string, Handler>) {
  const calls: Call[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'https://gm.test');
    const path = url.pathname.replace(/^\/api/, '');
    const method = (init.method ?? 'GET').toUpperCase();
    const headers = Object.fromEntries(Object.entries((init.headers ?? {}) as Record<string, string>));
    let body: unknown = init.body;
    if (typeof body === 'string') { try { body = JSON.parse(body); } catch { /* 保持原样 */ } }
    const call: Call = { method, path, body, headers, init };
    calls.push(call);
    const h = routes[`${method} ${path}`];
    if (!h) throw new Error(`测试里没有登记这个请求：${method} ${path}`);
    const r = typeof h === 'function' ? h(call) : h;
    const status = r.status ?? (r.json === undefined ? 204 : 200);
    return new Response(r.json === undefined ? null : JSON.stringify(r.json), { status, headers: { 'Content-Type': 'application/json', ...r.headers } });
  });
  vi.stubGlobal('fetch', fn);
  return { calls, count: (key: string) => calls.filter((c) => `${c.method} ${c.path}` === key).length, last: (key: string) => calls.filter((c) => `${c.method} ${c.path}` === key).at(-1) };
}

let container: HTMLElement | null = null;

export function mount(path: string) {
  history.replaceState(null, '', path);
  unmount();
  container = document.createElement('div');
  document.body.appendChild(container);
  render(<App />, container);
}

export function unmount() {
  if (container) { render(null, container); container.remove(); container = null; }
}

export async function until<T>(fn: () => T | null | undefined | false, ms = 3000): Promise<T> {
  const t0 = Date.now();
  for (;;) {
    await act(async () => { await new Promise((r) => setTimeout(r, 5)); });
    const v = fn();
    if (v) return v as T;
    if (Date.now() - t0 > ms) throw new Error(`等待超时。当前页面：${document.body.textContent?.slice(0, 600)}`);
  }
}

export const text = () => document.body.textContent ?? '';
export const hasText = (s: string) => text().includes(s);

export function inputByLabel(label: string): HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement | null {
  for (const l of Array.from(document.querySelectorAll('label'))) {
    if (l.textContent?.includes(label)) {
      const el = l.htmlFor ? document.getElementById(l.htmlFor) : l.querySelector('input,textarea,select');
      if (el) return el as HTMLInputElement;
    }
  }
  return null;
}

export function buttonByText(label: string): HTMLElement | null {
  return Array.from(document.querySelectorAll<HTMLElement>('button, a.btn')).find((b) => b.textContent?.trim() === label) ?? null;
}

export async function type(label: string, value: string) {
  const el = await until(() => inputByLabel(label));
  await act(() => { el.value = value; el.dispatchEvent(new Event('input', { bubbles: true })); });
}

export async function click(el: HTMLElement) {
  await act(() => { el.click(); });
}

export async function check(label: string) {
  const el = await until(() => inputByLabel(label) as HTMLInputElement | null);
  await click(el);
}

export function formData(call: Call | undefined): FormData {
  if (!call || !(call.body instanceof FormData)) throw new Error('该请求的请求体不是 FormData');
  return call.body;
}
