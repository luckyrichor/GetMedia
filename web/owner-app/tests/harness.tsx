/** owner 管理台的测试夹具：桩 fetch + 挂载 + 轮询等待。
 *
 * 约定：路由表键是 `方法 路径`，可以带查询串（`GET /v2/feedback?status=all&limit=20&offset=0`）；
 * 没写查询串就匹配同一路径的任意查询。这样既能只关心路径，也能断言筛选参数真的发出去了。
 */
import { render } from 'preact';
import { act } from 'preact/test-utils';
import { vi } from 'vitest';
import { App } from '../src/app';

export interface Reply { status?: number; json?: unknown }
export interface Call { method: string; path: string; search: string; body?: unknown }

export const CONFIG = {
  cache_ttl_seconds: 86400,
  cache_quota_bytes: 5 * 1024 ** 3,
  archive_quota_bytes: 20 * 1024 ** 3,
  creator: { max_items: 200, max_start: 60 },
  credential_vault: true,
};
export const ME = {
  id: 'acct_1', login_name: 'owner1', tier: 'full', tier_expires_at: null,
  consent_required: false, recovery_confirmed: true, status: 'active',
};

/** 管理员：比 full 多「权限管理」与「邀请码」两栏，页头多一个绿色标识。 */
export const ADMIN_ME = { ...ME, id: 'acct_admin', login_name: 'luckyrichor', tier: 'admin' };

export const calls: Call[] = [];
let container: HTMLElement | null = null;

export function server(routes: Record<string, Reply | (() => Reply)>) {
  const fn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'https://gm.test');
    const path = url.pathname.replace(/^\/api/, '');
    const search = url.search;
    const method = (init.method ?? 'GET').toUpperCase();
    const raw = typeof init.body === 'string' ? init.body : undefined;
    calls.push({ method, path, search, body: raw ? JSON.parse(raw) : undefined });
    const h = routes[`${method} ${path}${search}`] ?? routes[`${method} ${path}`];
    if (!h) throw new Error(`未登记的请求：${method} ${path}${search}`);
    const r = typeof h === 'function' ? h() : h;
    const status = r.status ?? (r.json === undefined ? 204 : 200);
    return new Response(r.json === undefined ? null : JSON.stringify(r.json), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fn);
}

/** 已登录（owner）时的两条前置请求，省得每个用例都写一遍。 */
export function ownerRoutes(extra: Record<string, Reply | (() => Reply)> = {}) {
  return { 'GET /v2/config/owner': { json: CONFIG }, 'GET /account/me': { json: ME }, ...extra };
}

export function mount(path = '/') {
  history.replaceState(null, '', path);
  container = document.createElement('div');
  document.body.appendChild(container);
  render(<App />, container);
}

export async function until(fn: () => boolean, ms = 2000) {
  const t0 = Date.now();
  for (;;) {
    await act(async () => { await new Promise((r) => setTimeout(r, 5)); });
    if (fn()) return;
    if (Date.now() - t0 > ms) throw new Error(`超时：${document.body.textContent?.slice(0, 400)}`);
  }
}

export const text = () => document.body.textContent ?? '';
export const callKeys = () => calls.map((c) => `${c.method} ${c.path}${c.search}`);

export function button(label: string): HTMLButtonElement {
  const b = Array.from(document.querySelectorAll('button')).find((x) => x.textContent?.trim() === label);
  if (!b) throw new Error(`找不到按钮：${label}（页面上有：${Array.from(document.querySelectorAll('button')).map((x) => x.textContent).join('|')}）`);
  return b as HTMLButtonElement;
}

export async function click(el: Element) {
  await act(async () => { (el as HTMLElement).click(); });
}

/** 自定义下拉框：先点开（按 aria-label 找控件），再点名字对得上的选项。 */
export async function choose(ariaLabel: string, option: string) {
  const trigger = document.querySelector(`button[aria-label="${ariaLabel}"]`);
  if (!trigger) throw new Error(`找不到下拉框：${ariaLabel}`);
  await click(trigger);
  const opts = Array.from(document.querySelectorAll('[role="option"]'));
  const hit = opts.find((li) => li.textContent?.trim() === option)
    ?? opts.find((li) => li.textContent?.trim().startsWith(option));
  if (!hit) throw new Error(`找不到选项：${option}（有：${opts.map((o) => o.textContent).join("|")}）`);
  await click(hit);
}

export function cleanup() {
  if (container) {
    render(null, container);
    container.remove();
    container = null;
  }
  calls.length = 0;
}
