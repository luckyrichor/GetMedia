/** owner 认证壳的三态与失败分支。只测"门"，功能页在 feedback/library 两个用例里。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { callKeys, cleanup, click, CONFIG, ADMIN_ME, ME, mount, server, text, until } from './harness';
import { SECTIONS, visibleSections } from '../src/sections';

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
    expect(text()).toContain('200');
    // 导航齐全，且每个栏目都标了交付阶段
    for (const label of ['反馈收件箱', '媒体库', '单个提取', '批量提取', '定时任务', 'Agent', '平台授权', '库维护', 'AI 视频']) {
      expect(text()).toContain(label);
    }
    // 完整版看不到管理员专属的两栏，页头也不加绿色标识
    expect(text()).not.toContain('权限管理');
    expect(text()).not.toContain('邀请码');
    expect(document.querySelector('.tag.admin')).toBeNull();
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
    server({ 'GET /v2/config/owner': { status: 403, json: { detail: '需要完整版权限', code: 'forbidden_tier' } } });
    mount();
    await until(() => text().includes('当前账号没有完整版权限'));
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

  test('九栏都是真实页面，没有一栏落到占位页', async () => {
    server({
      'GET /v2/config/owner': { json: CONFIG },
      'GET /account/me': { json: ME },
      'GET /v2/feedback?status=all&limit=20&offset=0': { json: { items: [], total: 0, limit: 20, offset: 0, has_more: false, counts: { new: 0, handled: 0, ignored: 0, all: 0 } } },
      'GET /v2/tracks?since_seq=0&limit=500': { json: { items: [], count: 0 } },
      'GET /v2/extraction-jobs?limit=50': { json: { jobs: [] } },
      'GET /v2/creators/jobs?limit=50': { json: { jobs: [] } },
      'GET /v2/schedules': { json: { schedules: [] } },
      'GET /v2/agent/status': { json: { engine: 'rules', models: [], calls_today: 0, daily_cap: 200, vault_ready: true } },
      'GET /v2/agent/conversations': { json: { conversations: [] } },
      'GET /v2/memories': { json: { memories: [] } },
      'GET /account/platforms': { json: { platforms: [{ platform: 'bilibili', name: '哔哩哔哩', methods: ['cookie'], hint: '', oauth: 'unconfigured' }], oauth_configured: false } },
      'GET /account/platform-bindings': { json: { bindings: [], credentials: [] } },
      'GET /v2/maintenance/storage': { json: { by_storage: {}, ai: { files: 0, bytes: 0 }, quota: { archive: 1, cache: 1 }, used: { archive: 0, cache: 0 } } },
      'GET /v2/diagnostics/extractions?limit=50': { json: { reports: [] } },
      'GET /v2/recreations/capabilities': { json: { available: false, reason: '未配置模型', max_upload_bytes: 209715200, modes: [], retention_days: 14, storage: { quota: 1, used: 0, ai_bytes: 0, ai_files: 0 } } },
      'GET /v2/recreations': { json: { recreations: [] } },
    });
    // 完整版能看到的九栏，每个栏目都注册了真实页面，所以"还没实现"这句话不该再出现在任何一栏
    const sections = visibleSections(ME.tier);
    expect(sections.length).toBe(9);
    const markers: Record<string, string> = {
      '/feedback': '这个筛选下没有反馈',
      '/library': '媒体库里还没有内容',
      '/extract': '还没有提取任务',
      '/creators': '还没有批量任务',
      '/schedules': '还没有定时任务',
      '/agent': '还没有会话',
      '/platforms': '哔哩哔哩',
      '/maintenance': '提取失败上报',
      '/ai': '还没有重建项目',
    };
    for (const s of sections) {
      cleanup();
      mount(s.path);
      const marker = markers[s.path];
      expect(marker, `${s.path} 没有空态标记`).toBeTruthy();
      await until(() => text().includes(marker!));
      expect(text()).not.toContain('这一栏还没实现');
      expect(s.done).toBe(true);
    }
  });

  test('管理员：多出「权限管理」「邀请码」两栏，页头带绿色标识', async () => {
    server({ 'GET /v2/config/owner': { json: CONFIG }, 'GET /account/me': { json: ADMIN_ME } });
    mount();
    await until(() => text().includes('概览'));
    // 十一个栏目：九栏 + 两个管理员专属页，且「权限管理」排在「概览」之后
    expect(SECTIONS.length).toBe(11);
    const nav = Array.from(document.querySelectorAll('nav.side a')).map((a) => a.textContent);
    expect(nav.slice(0, 3)).toEqual(['概览', '权限管理', '邀请码']);
    expect(document.querySelector('.top .tag.admin')?.textContent).toBe('管理员');
  });

  test('概览：五张配置卡数值不变，栏目改成整张可点的卡片', async () => {
    server({ 'GET /v2/config/owner': { json: CONFIG }, 'GET /account/me': { json: ME } });
    mount();
    await until(() => text().includes('概览'));

    // 数值与改造前一致：数字与单位是两个 span，但 textContent 里仍带空格（「5 GB」而不是「5GB」）
    const metrics = Array.from(document.querySelectorAll('.metric'));
    expect(metrics).toHaveLength(5);
    expect(metrics.map((m) => m.textContent)).toEqual([
      '媒体缓存时间1 天',
      '媒体缓存上限5 GB',
      '媒体存储上限20 GB',
      '最近 N 条上限200 条',
      '提取起点上限60 条',
    ]);
    // 只读卡不做成可点的样子：没有 a/button，也没有 tabindex
    expect(document.querySelector('.metric a, .metric button, .metric[tabindex]')).toBeNull();

    // 栏目：每栏一张卡，整张是链接，href 指到该栏目；说明全文进 title
    const cards = Array.from(document.querySelectorAll('.sec-card'));
    expect(cards).toHaveLength(visibleSections(ME.tier).length);
    expect(cards.map((c) => c.querySelector('.sec-name')?.textContent)).toEqual(
      visibleSections(ME.tier).map((s) => s.label),
    );
    const first = cards[0] as HTMLAnchorElement;
    expect(first.getAttribute('href')).toContain('/feedback');
    expect(first.querySelector('.sec-desc')?.getAttribute('title')).toBe(visibleSections(ME.tier)[0]!.blurb);
    // 图标是内联 SVG，不带任何文字（读屏只念栏目名）
    expect(first.querySelector('.sec-ico svg')).not.toBeNull();
    expect(first.querySelector('.sec-ico')?.textContent).toBe('');
  });

  test('导航：只有当前页那项带 aria-current', async () => {
    server({ 'GET /v2/config/owner': { json: CONFIG }, 'GET /account/me': { json: ME } });
    mount();
    await until(() => text().includes('概览'));
    const current = () => Array.from(document.querySelectorAll('nav.side a[aria-current="page"]')).map((a) => a.textContent);
    expect(current()).toEqual(['概览']);
    expect(document.querySelectorAll('nav.side a')).toHaveLength(10);

    cleanup();
    mount('/library');
    await until(() => text().includes('媒体库'));
    expect(current()).toEqual(['媒体库']);
  });
});
