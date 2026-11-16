/** owner 认证壳的三态与失败分支。只测"门"，功能页在 feedback/library 两个用例里。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { callKeys, cleanup, click, CONFIG, ME, mount, server, text, until } from './harness';

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('owner 认证壳', () => {
  test('owner：先探针再取身份，显示概览与配额', async () => {
    server({ 'GET /v2/config/owner': { json: CONFIG }, 'GET /account/me': { json: ME } });
    mount();
    await until(() => text().includes('概览'));
    expect(callKeys()).toEqual(['GET /v2/config/owner', 'GET /account/me']);
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
    expect(callKeys()).toEqual(['GET /v2/config/owner']);
  });

  test('匿名/非 owner（边缘 404）：回中性"页面不存在"，不透露管理台存在', async () => {
    server({ 'GET /v2/config/owner': { status: 404, json: { error: 'not_found' } } });
    mount('/');
    await until(() => text().includes('页面不存在'));
    expect(text()).toContain('这个地址下没有内容');
    // 不能出现任何暗示"这是权限问题"的字样，也不能出现 owner 相关内容
    for (const w of ['owner', '管理台', '登录', '权限']) expect(text()).not.toContain(w);
    expect(callKeys()).toEqual(['GET /v2/config/owner']);
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
    await click(Array.from(document.querySelectorAll('button')).find((b) => b.textContent === '重试')!);
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
    mount('/ai');
    await until(() => text().includes('这一栏还没实现'));
    expect(text()).toContain('AI 视频');
    expect(text()).toContain('/v2/recreations');
    expect(text()).toContain('GETMEDIA_AI_ENABLED');
  });

  test('5c 已接入的两栏不再是占位页', async () => {
    server({
      'GET /v2/config/owner': { json: CONFIG },
      'GET /account/me': { json: ME },
      'GET /v2/feedback?status=all&limit=20&offset=0': { json: { items: [], total: 0, limit: 20, offset: 0, has_more: false, counts: { new: 0, handled: 0, ignored: 0, all: 0 } } },
      'GET /v2/tracks?since_seq=0&limit=500': { json: { items: [], count: 0 } },
    });
    mount('/feedback');
    await until(() => text().includes('这个筛选下没有反馈'));
    expect(text()).not.toContain('这一栏还没实现');
    cleanup();

    mount('/library');
    await until(() => text().includes('媒体库里还没有内容'));
    expect(text()).not.toContain('这一栏还没实现');
  });

  test('5d/5e 六栏都接上了真实页面（只剩 5f 还是占位）', async () => {
    server({
      'GET /v2/config/owner': { json: CONFIG },
      'GET /account/me': { json: ME },
      'GET /v2/extraction-jobs?limit=50': { json: { jobs: [] } },
      'GET /v2/creators/jobs?limit=50': { json: { jobs: [] } },
      'GET /v2/schedules': { json: { schedules: [] } },
      'GET /v2/agent/status': { json: { engine: 'rules', models: [], calls_today: 0, daily_cap: 200, vault_ready: true } },
      'GET /v2/agent/conversations': { json: { conversations: [] } },
      'GET /v2/memories': { json: { memories: [] } },
      'GET /account/platforms': { json: { platforms: [{ platform: 'bilibili', name: '哔哩哔哩', methods: ['cookie'], hint: '', oauth: 'unconfigured' }], oauth_configured: false } },
      'GET /account/platform-bindings': { json: { bindings: [], credentials: [] } },
      'GET /v2/maintenance/storage': { json: { by_storage: {}, quota: { archive: 1, cache: 1 }, used: { archive: 0, cache: 0 } } },
      'GET /v2/diagnostics/extractions?limit=50': { json: { reports: [] } },
    });
    const expects: [string, string][] = [
      ['/extract', '还没有提取任务'],
      ['/creators', '还没有批量任务'],
      ['/schedules', '还没有定时任务'],
      ['/agent', '还没有会话'],
      ['/platforms', '哔哩哔哩'],
      ['/maintenance', '提取失败上报'],
    ];
    for (const [path, marker] of expects) {
      cleanup();
      mount(path);
      await until(() => text().includes(marker));
      expect(text()).not.toContain('这一栏还没实现');
    }
  });
});
