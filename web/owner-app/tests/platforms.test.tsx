/** 5e：平台授权。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act } from 'preact/test-utils';
import {
  button, calls, cleanup, click, CONFIG, mount, ownerRoutes, server, text, until,
} from './harness';

const PLATFORMS = {
  json: {
    platforms: [
      { platform: 'bilibili', name: '哔哩哔哩', methods: ['webview', 'cookie'], hint: '需要 SESSDATA 等登录 Cookie', oauth: 'unconfigured' },
      { platform: 'douyin', name: '抖音', methods: ['webview', 'cookie'], hint: '需要 sessionid / ttwid 等登录 Cookie', oauth: 'unconfigured' },
    ],
    oauth_configured: false,
  },
};

const BINDINGS = {
  json: {
    bindings: [{ platform: 'bilibili', bound_at: 1_800_000_000, source: 'cookie', status: 'active', label: '3 个 Cookie', updated_at: 1_800_000_000 }],
    credentials: [{ platform: 'bilibili', status: 'active', label: '3 个 Cookie', updated_at: 1_800_000_000, decryptable: true }],
  },
};

const SESSION = {
  json: { session_id: 'as_1234567890abcdef', platform: 'bilibili', method: 'cookie', status: 'pending', expires_at: 1_800_000_600 },
};

async function typeCookie(v: string) {
  const ta = document.querySelector('.panel-card textarea') as HTMLTextAreaElement;
  await act(async () => {
    ta.value = v;
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  return ta;
}

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('平台授权', () => {
  test('显示绑定与凭据状态，但不出现任何 Cookie 内容', async () => {
    server(ownerRoutes({ 'GET /account/platforms': PLATFORMS, 'GET /account/platform-bindings': BINDINGS }));
    mount('/platforms');
    await until(() => text().includes('哔哩哔哩'));

    expect(text()).toContain('已绑定');
    expect(text()).toContain('未绑定');           // 抖音
    expect(text()).toContain('3 个 Cookie');
    expect(text()).toContain('可以');             // 能否解密
    expect(text()).toContain('导入 Cookie');      // 绑定来源
    expect(text()).toContain('OAuth：未配置');
  });

  test('保险箱不可用：警示 + 授权按钮禁用', async () => {
    server(ownerRoutes({
      'GET /v2/config/owner': { json: { ...CONFIG, credential_vault: false } },
      'GET /account/platforms': PLATFORMS,
      'GET /account/platform-bindings': BINDINGS,
    }));
    mount('/platforms');
    await until(() => text().includes('凭据保险箱不可用'));

    expect(text()).toContain('GETMEDIA_CRED_KEY');
    const btn = button('粘贴 Cookie 授权');
    expect(btn.disabled).toBe(true);
  });

  test('导入 Cookie：会话 → 粘贴 → 提交；成功后清空，页面不保留明文', async () => {
    server(ownerRoutes({
      'GET /account/platforms': PLATFORMS,
      'GET /account/platform-bindings': BINDINGS,
      'POST /account/platform-auth/start': SESSION,
      'POST /account/platform-auth/cookie': { json: { platform: 'bilibili', status: 'done' } },
    }));
    vi.stubGlobal('confirm', () => true);
    mount('/platforms');
    await until(() => text().includes('哔哩哔哩'));

    await click(button('粘贴 Cookie 授权'));
    await until(() => text().includes('as_1234567890abcdef'));

    const secret = 'SESSDATA=abc123secret';
    await typeCookie(secret);
    await click(button('提交并保存'));
    await until(() => text().includes('页面不再保留这段文本'));

    const post = calls.find((c) => c.path.endsWith('/platform-auth/cookie'))!;
    expect(post.body).toEqual({ session_id: 'as_1234567890abcdef', cookie: secret });
    // 提交后页面里不再有这段文本
    expect(text()).not.toContain(secret);
    expect(document.querySelector('.panel-card textarea')).toBeNull();
  });

  test('服务端拒绝（缺 SESSDATA）时显示原因，不假装成功', async () => {
    server(ownerRoutes({
      'GET /account/platforms': PLATFORMS,
      'GET /account/platform-bindings': BINDINGS,
      'POST /account/platform-auth/start': SESSION,
      'POST /account/platform-auth/cookie': { status: 400, json: { detail: 'B 站 Cookie 里没有 SESSDATA，请重新登录后再复制', code: 'missing_cookie' } },
    }));
    vi.stubGlobal('confirm', () => true);
    mount('/platforms');
    await until(() => text().includes('哔哩哔哩'));
    await click(button('粘贴 Cookie 授权'));
    await until(() => text().includes('as_1234567890abcdef'));
    await typeCookie('foo=bar');
    await click(button('提交并保存'));
    await until(() => text().includes('没有 SESSDATA'));
    expect(text()).not.toContain('已保存');
  });

  test('解绑要二次确认，确认后刷新状态', async () => {
    let round = 0;
    server(ownerRoutes({
      'GET /account/platforms': PLATFORMS,
      'GET /account/platform-bindings': () => (round++ === 0 ? BINDINGS
        : { json: { bindings: [], credentials: [] } }),
      'POST /account/platform-bindings/bilibili/unbind': { json: { platform: 'bilibili', unbound: true } },
    }));
    mount('/platforms');
    await until(() => text().includes('哔哩哔哩'));

    vi.stubGlobal('confirm', () => false);
    await click(button('解绑'));
    expect(calls.some((c) => c.path.endsWith('/unbind'))).toBe(false);

    vi.stubGlobal('confirm', () => true);
    await click(button('解绑'));
    await until(() => text().includes('已解绑'));
    expect(calls.some((c) => c.path === '/account/platform-bindings/bilibili/unbind')).toBe(true);
    expect(text()).not.toContain('3 个 Cookie');
  });
});
