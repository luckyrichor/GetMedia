/** owner 认证壳的三态与失败分支。只测"门"，不测功能页（5c 起再补）。 */
import { render } from 'preact';
import { act } from 'preact/test-utils';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { App } from '../src/app';

interface Reply { status?: number; json?: unknown }
interface Call { method: string; path: string }

const CONFIG = {
  cache_ttl_seconds: 86400,
  cache_quota_bytes: 5 * 1024 ** 3,
  archive_quota_bytes: 20 * 1024 ** 3,
  creator: { max_items: 200, max_start: 60 },
  credential_vault: true,
};
const ME = { id: 'acct_1', login_name: 'owner1', tier: 'owner', tier_expires_at: null, consent_required: false, recovery_confirmed: true, status: 'active' };

let container: HTMLElement | null = null;
const calls: Call[] = [];

function server(routes: Record<string, Reply | (() => Reply)>) {
  const fn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'https://gm.test');
    const path = url.pathname.replace(/^\/api/, '');
    const method = (init.method ?? 'GET').toUpperCase();
    calls.push({ method, path });
    const h = routes[`${method} ${path}`];
    if (!h) throw new Error(`未登记的请求：${method} ${path}`);
    const r = typeof h === 'function' ? h() : h;
    const status = r.status ?? (r.json === undefined ? 204 : 200);
    return new Response(r.json === undefined ? null : JSON.stringify(r.json), { status, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fn);
}

function mount(path = '/') {
  history.replaceState(null, '', path);
  container = document.createElement('div');
  document.body.appendChild(container);
  render(<App />, container);
}

async function until(fn: () => boolean, ms = 2000) {
  const t0 = Date.now();
  for (;;) {
    await act(async () => { await new Promise((r) => setTimeout(r, 5)); });
    if (fn()) return;
    if (Date.now() - t0 > ms) throw new Error(`超时：${document.body.textContent?.slice(0, 400)}`);
  }
}

const text = () => document.body.textContent ?? '';

beforeEach(() => {
  calls.length = 0;
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  if (container) { render(null, container); container.remove(); container = null; }
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('owner 认证壳', () => {
  test('owner：先探针再取身份，显示概览与配额', async () => {
    server({ 'GET /v2/config/owner': { json: CONFIG }, 'GET /account/me': { json: ME } });
    mount();
    await until(() => text().includes('概览'));
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual(['GET /v2/config/owner', 'GET /account/me']);
    expect(text()).toContain('owner1');
    expect(text()).toContain('5 GB');
    expect(text()).toContain('1 天');
    expect(text()).toContain('200 条');
    // 导航齐全，且每个栏目都标了交付阶段
    for (const label of ['反馈收件箱', '媒体库与播放', '服务器提取', '博主批量', '定时任务', 'Agent', '平台授权', '库维护', 'AI 视频']) {
      expect(text()).toContain(label);
    }
  });

  test('未登录（401，未经边缘时）：给登录入口，不自动跳转，不显示任何 owner 内容', async () => {
    server({ 'GET /v2/config/owner': { status: 401, json: { detail: '请先登录', code: 'unauthenticated' } } });
    const before = location.href;
    mount('/');
    await until(() => text().includes('需要先登录'));
    expect(text()).not.toContain('概览');
    // 不自动跳：地址不变，也没有"几秒后跳转"的倒计时文案
    expect(location.href).toBe(before);
    expect(text()).not.toContain('正在跳到登录页');
    const link = Array.from(document.querySelectorAll('a')).find((a) => a.textContent?.includes('去登录'))!;
    expect(link.getAttribute('href')).toContain('login?next=');
    expect(calls).toEqual([{ method: 'GET', path: '/v2/config/owner' }]);
  });

  test('匿名/非 owner（边缘 404）：回中性"页面不存在"，不透露管理台存在', async () => {
    server({ 'GET /v2/config/owner': { status: 404, json: { error: 'not_found' } } });
    mount('/');
    await until(() => text().includes('页面不存在'));
    expect(text()).toContain('这个地址下没有内容');
    // 不能出现任何暗示"这是权限问题"的字样，也不能出现 owner 相关内容
    for (const w of ['owner', '管理台', '登录', '权限']) expect(text()).not.toContain(w);
    expect(calls).toEqual([{ method: 'GET', path: '/v2/config/owner' }]);
  });

  test('非 owner（403）：说明原因，不把人绕回登录页', async () => {
    server({ 'GET /v2/config/owner': { status: 403, json: { detail: '当前账号不是 owner', code: 'forbidden_tier' } } });
    mount();
    await until(() => text().includes('当前账号不是 owner'));
    expect(Array.from(document.querySelectorAll('a.btn')).some((a) => a.getAttribute('href')?.includes('login'))).toBe(false);
    expect(text()).toContain('查看当前账号');
  });

  test('服务异常：显示原因与重试；重试后能进', async () => {
    let n = 0;
    server({
      'GET /v2/config/owner': () => (n++ === 0 ? { status: 503, json: { detail: '服务维护中', code: 'unavailable' } } : { json: CONFIG }),
      'GET /account/me': { json: ME },
    });
    mount();
    await until(() => text().includes('管理台暂时打不开'));
    expect(text()).toContain('服务维护中');
    await act(() => (Array.from(document.querySelectorAll('button')).find((b) => b.textContent === '重试') as HTMLButtonElement).click());
    await until(() => text().includes('概览'));
  });

  test('保险箱不可用时给出警告（凭据功能会只读）', async () => {
    server({ 'GET /v2/config/owner': { json: { ...CONFIG, credential_vault: false } }, 'GET /account/me': { json: ME } });
    mount();
    await until(() => text().includes('凭据保险箱不可用'));
    expect(text()).toContain('GETMEDIA_CRED_KEY');
  });

  test('栏目占位页写明"还差什么"与将用的接口', async () => {
    server({ 'GET /v2/config/owner': { json: CONFIG }, 'GET /account/me': { json: ME } });
    mount('/creators');
    await until(() => text().includes('博主批量'));
    expect(text()).toContain('这一栏还没实现');
    expect(text()).toContain('/v2/creators/preview');
    expect(text()).toContain('items:import');
  });
});
