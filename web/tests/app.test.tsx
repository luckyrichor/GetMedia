import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  CONFIG, LEGAL, ME, buttonByText, check, click, fakeServer, formData, hasText, inputByLabel, mount, sha, text, type, unauthenticated, unmount, until,
  type Reply,
} from './helpers';

const PW = 'correct horse 1';
const DEVICE = (n: string, current = false) => ({ id: `dev_${n.repeat(16)}`, channel: 'web', device: `Chrome（Windows）${n}`, app_version: '0.1.0', created_at: 1_800_000_000, last_used_at: 1_800_000_100, current });

beforeEach(() => { localStorage.clear(); sessionStorage.clear(); vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined); });
afterEach(() => { unmount(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

/** 一个会随登录状态变化的假服务端。 */
function accountServer(over: Record<string, any> = {}) {
  const st = { authed: false, me: { ...ME } };
  const routes: Record<string, any> = {
    'GET /v2/config': { json: CONFIG },
    'GET /v2/legal': { json: { documents: LEGAL } },
    'GET /account/me': () => (st.authed ? { json: st.me } : unauthenticated),
    'POST /v2/heartbeat': {},
    'GET /account/devices': () => ({ json: { devices: [DEVICE('a', true), DEVICE('b')] } }),
    ...over,
  };
  const srv = fakeServer(routes);
  lastServer = srv;
  return { st, srv, routes };
}

let lastServer: ReturnType<typeof fakeServer>;
/** 取回最近一次 accountServer 安装的假服务端，用来断言发过哪些请求。 */
const fakeServerRef = () => lastServer;

describe('注册', () => {
  test('完整流程：提交协议版本与哈希 → 展示恢复码 → 确认保存 → 进入账号页；机密不落入浏览器存储', async () => {
    const s = accountServer({
      'POST /account/register': () => {
        s.st.authed = true;
        s.st.me = { ...ME, recovery_confirmed: false };
        return { status: 201, json: { session: { expires_at: 1_900_000_000 }, account: { id: ME.id, login_name: 'alice', tier: 'free' }, recovery_code: 'ABCDEFGHJKLMNPQR' } };
      },
      'POST /account/recovery/ack': () => { s.st.me.recovery_confirmed = true; return {}; },
    });
    const srv = fakeServerRef();
    mount('/register');
    await until(() => hasText('用户协议 v1'));
    await type('登录名', 'Alice');
    await type('密码', PW);
    await type('再次输入密码', PW);
    await check('我已年满 18 周岁');
    await click((await until(() => buttonByText('注册')))!);

    const code = await until(() => document.querySelector('[data-testid="recovery-code"]'));
    expect(code.textContent).toBe('ABCD-EFGH-JKLM-NPQR');

    const req = srv.last('POST /account/register')!;
    expect(req.body).toMatchObject({
      login_name: 'alice', password: PW, age_confirmed: true,
      consent: { user_agreement: { version: 1, sha256: sha('a') }, privacy_policy: { version: 1, sha256: sha('b') } },
      client: { channel: 'web' },
    });
    expect((req.body as any).invite_code).toBeUndefined();
    expect(req.headers['X-GetMedia-CSRF']).toBeTruthy();
    expect(req.init.credentials).toBe('same-origin');

    // 未勾选"已保存"前不能继续
    expect((buttonByText('继续') as HTMLButtonElement).disabled).toBe(true);
    await check('我已把恢复码保存在安全的地方');
    await click(buttonByText('继续')!);
    await until(() => document.querySelector('[data-testid="login-name"]')?.textContent === 'alice');
    expect(srv.count('POST /account/recovery/ack')).toBe(1);

    // 恢复码与密码绝不进入任何存储
    const stored = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage });
    expect(stored).not.toMatch(/ABCD|EFGH|correct horse/);
    for (const k of Object.keys(localStorage)) expect(k.startsWith('getmedia:')).toBe(true);
  });

  test('协议在提交时已更新（409 legal_outdated）：取消勾选、重新拉取协议、提示重读', async () => {
    accountServer({ 'POST /account/register': { status: 409, json: { detail: '协议已更新，请阅读最新版本后重新同意', code: 'legal_outdated' } } });
    const srv = fakeServerRef();
    mount('/register');
    await until(() => hasText('用户协议 v1'));
    await type('登录名', 'alice'); await type('密码', PW); await type('再次输入密码', PW);
    await check('我已年满 18 周岁');
    await click(buttonByText('注册')!);
    await until(() => hasText('协议刚刚更新了'));
    expect((inputByLabel('我已年满 18 周岁') as HTMLInputElement).checked).toBe(false);
    await until(() => srv.count('GET /v2/legal') >= 2);
  });

  test('未勾选同意、密码不一致、登录名不合规时不发请求', async () => {
    accountServer({ 'POST /account/register': { status: 500 } });
    const srv = fakeServerRef();
    mount('/register');
    await until(() => hasText('用户协议 v1'));
    await type('登录名', '13800138000'); await type('密码', PW); await type('再次输入密码', PW + 'x');
    await click(buttonByText('注册')!);
    await until(() => hasText('登录名不能是手机号'));
    expect(hasText('两次输入的密码不一致')).toBe(true);
    await type('登录名', 'alice'); await type('再次输入密码', PW);
    await click(buttonByText('注册')!);
    await until(() => hasText('请先阅读并勾选同意'));
    expect(srv.count('POST /account/register')).toBe(0);
  });

  test('注册未开放：提示 + 显示邀请码框；没填邀请码不发请求；填了就带上', async () => {
    accountServer({
      'GET /v2/config': { json: { ...CONFIG, registration_open: false } },
      'POST /account/register': { status: 403, json: { detail: '邀请码无效或已使用', code: 'invite_invalid' } },
    });
    const srv = fakeServerRef();
    mount('/register');
    await until(() => hasText('目前未开放自由注册'));
    await until(() => inputByLabel('邀请码'));
    await type('登录名', 'alice'); await type('密码', PW); await type('再次输入密码', PW);
    await check('我已年满 18 周岁');
    await click(buttonByText('注册')!);
    await until(() => hasText('需要邀请码才能注册'));
    expect(srv.count('POST /account/register')).toBe(0);
    await type('邀请码', ' ABCD-1234 ');
    await click(buttonByText('注册')!);
    await until(() => hasText('邀请码无效或已使用'));
    expect((srv.last('POST /account/register')!.body as any).invite_code).toBe('ABCD-1234');
  });
});

describe('登录与路由守卫', () => {
  test('设备数超限：展示设备并要求选择一个；重试时带 evict_device_id；登录后回到 next', async () => {
    const logins: any[] = [];
    const s = accountServer({
      'POST /account/login': (c: any) => {
        logins.push(c.body);
        if (!c.body.evict_device_id) return { status: 409, json: { detail: '已登录的设备数量已达上限', code: 'device_limit', devices: [DEVICE('a'), DEVICE('b'), DEVICE('c')] } };
        s.st.authed = true;
        return { json: { session: { expires_at: 1 }, account: { id: ME.id, login_name: 'alice', tier: 'free' }, consent_required: false, recovery_confirmed: true } };
      },
    });
    fakeServerRef();
    mount('/contact');                                    // 未登录访问受保护页面
    await until(() => location.pathname === '/login');
    expect(location.search).toBe('?next=%2Fcontact');
    await type('登录名', 'Alice'); await type('密码', PW);
    await click(buttonByText('登录')!);
    await until(() => hasText('请选择一个退出后继续'));
    expect(logins).toHaveLength(1);
    await click(buttonByText('退出所选设备并登录')!);
    await until(() => hasText('请选择一个要退出的设备'));
    expect(logins).toHaveLength(1);                         // 没选就不重试
    await click(document.querySelector<HTMLInputElement>(`input[value="dev_${'b'.repeat(16)}"]`)!);
    await click(buttonByText('退出所选设备并登录')!);
    await until(() => location.pathname === '/contact');
    expect(logins[1]).toMatchObject({ login_name: 'alice', evict_device_id: `dev_${'b'.repeat(16)}` });
  });

  test('统一失败文案原样展示；429 展示等待时间', async () => {
    accountServer({ 'POST /account/login': () => ({ status: 401, json: { detail: '登录名或密码不正确', code: 'invalid_credentials' } }) });
    mount('/login');
    await type('登录名', 'alice'); await type('密码', 'wrong-password');
    await click((await until(() => buttonByText('登录')))!);
    await until(() => hasText('登录名或密码不正确'));
    expect(location.pathname).toBe('/login');              // 密码错不是"会话失效"，不会被踢出页面
    vi.unstubAllGlobals();
    accountServer({ 'POST /account/login': { status: 429, json: { detail: '操作太频繁，请稍后再试', code: 'rate_limited' }, headers: { 'Retry-After': '120' } } as Reply });
    await click(buttonByText('登录')!);
    await until(() => hasText('约 2 分钟后可重试'));
  });

  test('协议版本变更：登录后先进入重新同意页，同意后才能看到账号页', async () => {
    const s = accountServer({ 'POST /account/consent': () => { s.st.me.consent_required = false; return {}; } });
    s.st.authed = true; s.st.me.consent_required = true;
    const srv = fakeServerRef();
    mount('/account');
    await until(() => hasText('协议已更新'));
    expect(document.querySelector('[data-testid="login-name"]')).toBeNull();
    await check('我已年满 18 周岁');
    await click(buttonByText('同意并继续')!);
    await until(() => document.querySelector('[data-testid="login-name"]'));
    expect(srv.last('POST /account/consent')!.body).toEqual({
      user_agreement: { version: 1, sha256: sha('a') }, privacy_policy: { version: 1, sha256: sha('b') },
    });
  });

  test('会话在别处被撤销（401 unauthenticated）：账号页回到登录页', async () => {
    const s = accountServer({ 'GET /account/devices': unauthenticated });
    s.st.authed = true;
    fakeServerRef();
    mount('/account');
    await until(() => location.pathname === '/login');
    expect(location.search).toBe('?next=%2Faccount');
  });

  test('恢复码已无法显示（刷新后）：只给说明与确认，不显示任何恢复码', async () => {
    const s = accountServer({ 'POST /account/recovery/ack': () => { s.st.me.recovery_confirmed = true; return {}; } });
    s.st.authed = true; s.st.me.recovery_confirmed = false;
    fakeServerRef();
    mount('/account');
    await until(() => hasText('现在已经无法再次显示'));
    expect(document.querySelector('[data-testid="recovery-code"]')).toBeNull();
    await click(buttonByText('我已了解，继续')!);
    await until(() => document.querySelector('[data-testid="login-name"]'));
  });
});

describe('找回账号', () => {
  test('重置后展示新恢复码，去登录后以同一账号再次要求确认保存（不静默确认）', async () => {
    const s = accountServer({
      'POST /account/recover': { json: { recovery_code: 'ZZZZYYYYXXXXWWWW' } },
      'POST /account/login': () => { s.st.authed = true; s.st.me.recovery_confirmed = false; return { json: { session: { expires_at: 1 }, account: { id: ME.id, login_name: 'alice', tier: 'free' }, consent_required: false, recovery_confirmed: false } }; },
    });
    const srv = fakeServerRef();
    mount('/recover');
    await type('登录名', 'Alice'); await type('恢复码', 'abcd efgh-ijkl mnop');
    await type('新密码', PW); await type('再次输入新密码', PW);
    await click(buttonByText('重置密码')!);
    await until(() => document.querySelector('[data-testid="recovery-code"]'));
    expect(srv.last('POST /account/recover')!.body).toEqual({ login_name: 'alice', recovery_code: 'abcd efgh-ijkl mnop', new_password: PW });
    await click(buttonByText('我已保存，去登录')!);
    await type('登录名', 'alice'); await type('密码', PW);
    await click(buttonByText('登录')!);
    await until(() => document.querySelector('[data-testid="recovery-code"]')?.textContent === 'ZZZZ-YYYY-XXXX-WWWW');
    expect(srv.count('POST /account/recovery/ack')).toBe(0);
  });
});

describe('联系作者', () => {
  test('提交：multipart 字段、幂等键、诊断白名单；成功显示编号；429 提示等待', async () => {
    const s = accountServer({ 'POST /v2/feedback': { status: 201, json: { id: '0f1e2d3c-aaaa-bbbb-cccc-1234567890ab' } } });
    s.st.authed = true;
    const srv = fakeServerRef();
    mount('/contact');
    await until(() => inputByLabel('内容'));
    await click(buttonByText('提交')!);
    await until(() => hasText('请填写要反馈的内容'));
    expect(srv.count('POST /v2/feedback')).toBe(0);

    await type('内容', '  播放时闪退  '); await type('联系方式', 'me@example.invalid');
    await click(buttonByText('提交')!);
    await until(() => document.querySelector('[data-testid="feedback-id"]'));
    expect(document.querySelector('[data-testid="feedback-id"]')!.textContent).toBe('0f1e2d3c');
    const fd = formData(srv.last('POST /v2/feedback'));
    expect(fd.get('text')).toBe('播放时闪退');
    expect(fd.get('category')).toBe('bug');
    expect(fd.get('contact')).toBe('me@example.invalid');
    expect(String(fd.get('idempotency_key'))).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    const diag = JSON.parse(String(fd.get('diagnostics')));
    expect(Object.keys(diag).sort()).toEqual(['app_version', 'channel', 'device', 'locale', 'os']);   // 与服务端白名单一致
    expect(JSON.stringify(diag)).not.toMatch(/cookie|token|password|authorization/i);
    expect(srv.last('POST /v2/feedback')!.headers['Content-Type']).toBeUndefined();                  // 让浏览器带 boundary
  });

  test('可取消诊断信息；服务端 429 展示等待时间；失败后重试沿用同一个幂等键', async () => {
    let n = 0;
    const s = accountServer({
      'POST /v2/feedback': () => (++n === 1
        ? { status: 429, json: { detail: '提交太频繁', code: 'rate_limited' }, headers: { 'Retry-After': '30' } }
        : { status: 201, json: { id: 'abcdef12-0000-0000-0000-000000000000' } }),
    });
    s.st.authed = true;
    const srv = fakeServerRef();
    mount('/contact');
    await type('内容', 'x');
    await check('附带诊断信息');
    await click(buttonByText('提交')!);
    await until(() => hasText('约 30 秒后可重试'));
    await click(buttonByText('提交')!);
    await until(() => document.querySelector('[data-testid="feedback-id"]'));
    const [a, b] = srv.calls.filter((c) => c.path === '/v2/feedback').map((c) => formData(c));
    expect(a!.get('diagnostics')).toBeNull();
    expect(a!.get('idempotency_key')).toBe(b!.get('idempotency_key'));
  });
});

describe('账号与安全', () => {
  test('设备列表：当前设备不可退出；退出其他设备需确认', async () => {
    const s = accountServer({ 'POST /account/devices/revoke-others': { json: { revoked: 1 } } });
    s.st.authed = true;
    const srv = fakeServerRef();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    mount('/account');
    await until(() => hasText('Chrome（Windows）a'));
    expect(hasText('当前设备')).toBe(true);
    await click(buttonByText('退出其他所有设备')!);
    expect(confirm).toHaveBeenCalled();
    expect(srv.count('POST /account/devices/revoke-others')).toBe(0);
    confirm.mockReturnValue(true);
    await click(buttonByText('退出其他所有设备')!);
    await until(() => hasText('已退出其他 1 台设备'));
  });

  test('改密码：新旧密码与确认校验；成功后清空并提示其他设备已退出', async () => {
    const s = accountServer({ 'POST /account/password': {} });
    s.st.authed = true;
    const srv = fakeServerRef();
    mount('/account');
    await type('当前密码', PW); await type('新密码', 'short'); await type('再次输入新密码', 'short');
    await click(buttonByText('修改密码')!);
    await until(() => hasText('密码长度需为 8–128 位'));
    expect(srv.count('POST /account/password')).toBe(0);
    await type('新密码', 'a brand new pass 2'); await type('再次输入新密码', 'a brand new pass 2');
    await click(buttonByText('修改密码')!);
    await until(() => hasText('密码已修改，其他设备已退出登录'));
    expect(srv.last('POST /account/password')!.body).toEqual({ old_password: PW, new_password: 'a brand new pass 2' });
    expect((inputByLabel('当前密码') as HTMLInputElement).value).toBe('');
  });

  test('注销：需要密码和勾选；成功后回到登录页并说明 7 天冷静期', async () => {
    const s = accountServer({
      'DELETE /account/me': () => { s.st.authed = false; return { json: { delete_at: 1_800_000_000 + 7 * 86400 } }; },
    });
    s.st.authed = true;
    const srv = fakeServerRef();
    mount('/account');
    await until(() => document.querySelector('details'));
    await type('输入密码确认', PW);
    await click(buttonByText('注销账号')!);
    await until(() => hasText('请先勾选确认'));
    expect(srv.count('DELETE /account/me')).toBe(0);
    await check('我了解注销后数据将被永久删除');
    await click(buttonByText('注销账号')!);
    await until(() => location.pathname === '/login');
    await until(() => hasText('注销申请已提交'));
    expect(srv.last('DELETE /account/me')!.body).toEqual({ password: PW });
    expect(srv.last('DELETE /account/me')!.headers['X-GetMedia-CSRF']).toBeTruthy();
    expect(text()).toContain('只能由运营者处理');
  });
});

describe('首页、协议与心跳', () => {
  test('首页：展示最新版本、https 下载链接与 SHA-256；维护提示显示在页面顶部', async () => {
    accountServer({ 'GET /v2/config': { json: { ...CONFIG, maintenance: '今晚 23:00 维护' } } });
    mount('/');
    await until(() => document.querySelector('[data-testid="apk-link"]'));
    const a = document.querySelector<HTMLAnchorElement>('[data-testid="apk-link"]')!;
    expect(a.href).toBe('https://example.invalid/getmedia-0.1.0.apk');
    expect(a.rel).toContain('noopener');
    expect(document.querySelector('[data-testid="apk-sha"]')!.textContent).toBe(sha('c'));
    expect(document.querySelector('[data-testid="latest-version"]')!.textContent).toBe('0.1.0');
    expect(hasText('今晚 23:00 维护')).toBe(true);
  });

  test('首页：服务器不可达时不白屏，给出提示；仍可看到注册/登录入口', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    mount('/');
    await until(() => hasText('暂时无法连接服务器'));
    expect(buttonByText('注册')).not.toBeNull();
  });

  test('协议页：渲染 Markdown（去掉与页面标题重复的一级标题，表格可见）', async () => {
    accountServer();
    mount('/legal/privacy-policy');
    await until(() => document.querySelector('.md table'));
    expect(document.querySelectorAll('h1')).toHaveLength(1);
    expect(document.querySelector('h1')!.textContent).toBe('隐私说明');
    expect(document.querySelector('[data-testid="legal-meta"]')!.textContent).toContain('v1');
    expect(document.querySelector('.md h2')!.textContent).toBe('1. 收集');
    expect(document.querySelector('.md h3')).toBeNull();                       // 不跳级：h1 之后直接是 h2
  });

  test('心跳：登录后上报一次，并只在 getmedia: 前缀下记一个日期', async () => {
    const s = accountServer();
    s.st.authed = true;
    const srv = fakeServerRef();
    mount('/account');
    await until(() => srv.count('POST /v2/heartbeat') === 1);
    expect(srv.last('POST /v2/heartbeat')!.body).toMatchObject({ client: { channel: 'web' } });
    await until(() => localStorage.getItem('getmedia:heartbeat-day'));
    expect(Object.keys(localStorage)).toEqual(['getmedia:heartbeat-day']);
  });

  test('未知路径：显示“页面不存在”，路径内容不会出现在页面文案里', async () => {
    accountServer();
    mount('/owner/anything');
    await until(() => hasText('页面不存在'));
    expect(text()).not.toMatch(/owner/i);
  });
});
