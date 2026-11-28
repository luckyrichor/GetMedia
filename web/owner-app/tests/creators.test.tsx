/** 5d：批量提取。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act } from 'preact/test-utils';
import {
  button, calls, choose, cleanup, click, mount, ownerRoutes, server, text, until,
} from './harness';

const JOBS = 'GET /v2/creators/jobs?limit=50';

function job(over: Record<string, unknown> = {}) {
  return {
    id: 3, platform: 'bilibili', creator_id: '12345', creator_name: '某个 UP 主',
    source_url: 'https://space.bilibili.com/12345', mode: 'latest_n',
    since_ts: null, until_ts: null, limit_n: 20, include_pinned: true,
    storage: 'phone', media_type: 'video', status: 'running', pause_reason: null,
    total: 20, done: 6, failed: 1, estimated_bytes: 300 * 1024 * 1024,
    created_at: 1_700_000_000, updated_at: 1_700_000_100, started_at: 1_700_000_001, finished_at: null,
    ...over,
  };
}

function row(over: Record<string, unknown> = {}) {
  return {
    id: 1, video_id: 'BV1', platform: 'bilibili', page: 1, title: '第一条作品',
    duration_sec: 204, published_at: null, is_pinned: false, cover_url: null, source_url: 'x',
    download_state: 'pending', track_id: null, error: null, ...over,
  };
}

const PREVIEW = {
  profile: { platform: 'bilibili', creator_id: '12345', name: '某个 UP 主', avatar_url: null,
    url: 'https://space.bilibili.com/12345', total_videos: 88, follower_count: 1000, signature: null },
  items: [
    { position: 1, video_id: 'BV1', title: '第一条作品', source_url: 'https://example.invalid/1',
      cover_url: null, duration_sec: 204, published_at: null, is_pinned: true, estimated_bytes: 10 },
    { position: 2, video_id: 'BV2', title: '第二条作品', source_url: 'https://example.invalid/2',
      cover_url: null, duration_sec: 60, published_at: null, is_pinned: false, estimated_bytes: 10 },
  ],
  total: 88, has_more: true, fetched_at: '2026-11-16T00:00:00Z', cookie_source: 'guest',
  mode: 'latest_n', estimated_bytes: 200 * 1024 * 1024,
};

async function fillUrl(v: string) {
  const input = document.querySelector('input[type="text"]') as HTMLInputElement;
  await act(async () => {
    input.value = v;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  return input;
}

/** 表格里的任务行（展开的条目子行没有 data-job，不会混进来）。 */
const jobRows = () => Array.from(document.querySelectorAll('.cr-table tbody tr[data-job]')) as HTMLElement[];
const rowText = (i = 0) => jobRows()[i]?.textContent ?? '';

/** 打开某一行的「⋯」，返回菜单项文字（分隔线不是 menuitem，不在里面）。 */
async function openRowMenu(i = 0) {
  const btns = Array.from(
    document.querySelectorAll('.cr-table tbody tr[data-job] button[aria-label="更多操作"]'),
  ) as HTMLButtonElement[];
  const b = btns[i];
  if (!b) throw new Error(`没有第 ${i + 1} 行的「更多操作」`);
  await click(b);
  return Array.from(document.querySelectorAll('[role="menuitem"]')) as HTMLElement[];
}

async function menuTexts(i = 0) {
  return (await openRowMenu(i)).map((n) => n.textContent?.trim());
}

/** 点状态分段里的某一段（分段控件不是下拉，按文字找 tab）。 */
async function pickStatus(label: string) {
  const tabs = Array.from(
    document.querySelectorAll('[role="tablist"][aria-label="按状态筛选"] [role="tab"]'),
  ) as HTMLElement[];
  const hit = tabs.find((t) => (t.textContent ?? '').startsWith(label));
  if (!hit) throw new Error(`找不到状态段：${label}（有：${tabs.map((t) => t.textContent).join('|')}）`);
  await click(hit);
}

beforeEach(() => {
  window.localStorage.clear();
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('批量提取', () => {
  test('预览：显示会选中多少条、预计占用、列表来源', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await fillUrl('12345');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    expect(text()).toContain('某个 UP 主');
    expect(text()).toContain('共 88 条');
    expect(text()).toContain('200 MB');
    expect(text()).toContain('游客态');
    expect(text()).toContain('第一条作品');
    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.mode).toBe('latest_n');
    expect(body.n).toBe(20);
    expect(body.include_pinned).toBe(true);
  });

  test('预览只发选取参数，不混入建任务专用的输出格式（否则服务端 422）', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('输入类型', '抖音');
    await fillUrl('67194609689');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    // 预览接口是 extra="forbid"：多一个字段整单 422，页面就显示「提交的内容格式不正确」。
    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body).not.toHaveProperty('formats');
    expect(body).not.toHaveProperty('source_input');
    expect(body).not.toHaveProperty('storage');
    expect(body).toMatchObject({ url: '67194609689', platform: 'douyin', mode: 'latest_n', n: 20 });
  });

  test('切到音频后预览体仍然干净，只多一个用于估算的 media_type', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('输入类型', '抖音');
    await fillUrl('67194609689');
    await choose('格式', '音频');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body).not.toHaveProperty('formats');
    expect(body).not.toHaveProperty('source_input');
    expect(body.media_type).toBe('audio');
  });

  test('预览失败：淡红信息条原样给服务端原因，不粉饰成网络问题', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { status: 400, json: { detail: '拿不到作品列表：平台拒绝了这次请求（403）', code: 'creator_input' } },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));
    await fillUrl('67194609689');
    await click(button('预览'));
    await until(() => text().includes('平台拒绝了这次请求'));

    expect(document.querySelector('.info-bar.bad')).not.toBeNull();
    expect(text()).not.toContain('网络不稳定');
  });

  test('预览结果在任意字段改动后清空，不会留一条对不上的数字', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));
    await fillUrl('12345');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    await choose('格式', '音频');
    expect(text()).not.toContain('预计选取');
  });

  test('建任务：把存放方式与选取条件一起提交，随后列表刷新并高亮新行', async () => {
    let round = 0;
    server(ownerRoutes({
      [JOBS]: () => (round++ === 0 ? { json: { jobs: [] } } : { json: { jobs: [job()] } }),
      'POST /v2/creators/jobs': { json: job({ status: 'pending', done: 0, failed: 0 }) },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await fillUrl('12345');
    await choose('存放方式', '服务器存储');
    await click(button('开始提取'));
    await until(() => text().includes('任务已创建'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body).toMatchObject({ url: '12345', mode: 'latest_n', n: 20, storage: 'archive', media_type: 'video' });

    // 新任务那一行高亮 2 秒，且进度、失败数都能读到
    await until(() => jobRows().length === 1);
    expect(jobRows()[0]!.className).toContain('job-fresh');
    expect(rowText()).toContain('20');
    expect(rowText()).toContain('1 条失败');
  });

  test('建任务失败：错误 toast，已填内容保留', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/jobs': { status: 400, json: { detail: '这个博主已经有一批任务在跑' } },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));
    await fillUrl('12345');
    await click(button('开始提取'));
    await until(() => text().includes('这个博主已经有一批任务在跑'));

    expect(document.querySelector('.toast')).not.toBeNull();
    expect((document.querySelector('input[type="text"]') as HTMLInputElement).value).toBe('12345');
  });
});

describe('批量提取：表单校验与动态字段', () => {
  test('条数填 0 / 空 / 小数都标红并拦下，不发请求', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));
    await fillUrl('12345');

    const nInput = () => document.querySelector('input[aria-label="条数"]') as HTMLInputElement;
    const setN = async (v: string) => {
      await act(async () => {
        nInput().value = v;
        nInput().dispatchEvent(new Event('input', { bubbles: true }));
      });
    };

    for (const bad of ['0', '', '2.5']) {
      await setN(bad);
      await click(button('预览'));
      await until(() => text().includes('条数要是 1–100000 之间的整数'));
      expect(document.querySelector('.field.is-bad')).not.toBeNull();
    }
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  test('四种选取方式各自的动态字段：条数 / 日期 / 无 / 起点+方向', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    // 默认「最近 N 条」→ 只有条数
    expect(document.querySelector('input[aria-label="条数"]')).not.toBeNull();
    expect(document.querySelectorAll('.ext-form input[type="date"]')).toHaveLength(0);

    await choose('选取方式', '指定日期');
    expect(document.querySelector('input[aria-label="条数"]')).toBeNull();
    expect(document.querySelectorAll('.ext-form input[type="date"]')).toHaveLength(2);

    await choose('选取方式', '全部作品');
    expect(document.querySelector('input[aria-label="条数"]')).toBeNull();
    expect(document.querySelectorAll('.ext-form input[type="date"]')).toHaveLength(0);
    expect(document.querySelector('input[aria-label="起始序号"]')).toBeNull();

    await choose('选取方式', '指定起点');
    expect(document.querySelector('input[aria-label="起始序号"]')).not.toBeNull();
    expect(document.querySelector('button[aria-label="提取方向"]')).not.toBeNull();
  });

  test('切换输入类型后标签与 placeholder 跟着变，不永远显示 B 站示例', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    const input = () => document.querySelector('input[type="text"]') as HTMLInputElement;
    const label = () => document.querySelector('.ext-url > span')!.textContent;
    // 默认抖音：标签和占位都把「号」和「主页链接」两种写法写出来
    expect(input().placeholder).toBe('例如 67194609689、luoyonghao，或粘贴 douyin.com/user/… 的主页链接');
    expect(label()).toBe('抖音号 / 主页链接');

    await choose('输入类型', 'B 站');
    expect(input().placeholder).toBe('例如 12345，或粘贴 space.bilibili.com/12345');
    expect(label()).toBe('B 站号 / 主页链接');
  });

  test('指定日期的结束日期默认今天，两个日期都是日期选择器', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('选取方式', '指定日期');

    // 只数创建表单里的日期框（筛选栏里也有发起时间日期框，不在这里面）
    const dates = Array.from(document.querySelectorAll('.ext-form input[type="date"]')) as HTMLInputElement[];
    expect(dates).toHaveLength(2);
    const d = new Date();
    const two = (x: number) => String(x).padStart(2, '0');
    expect(dates[1]!.value).toBe(`${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`);
    // 只选了结束日期还不够，开始日期为空时要点出来
    expect(dates[0]!.value).toBe('');
  });

  test('存放方式的说明做成悬停提示，不再在下面平铺三行', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    // 页面正文里没有那段逐行说明了
    expect(document.querySelectorAll('.storage-notes')).toHaveLength(0);

    await click(document.querySelector('button[aria-label="存放方式"]')!);
    const opts = Array.from(document.querySelectorAll('[role="option"]'));
    expect(opts.map((o) => o.querySelector('.select-label')?.textContent))
      .toEqual(['只存元数据', '服务器缓存', '服务器存储']);
    // 刚展开不弹提示，悬停到某一项才弹（页面上的 ⓘ 说明一直都在，只看下拉里的）
    expect(document.querySelector('.select-menu [role="tooltip"]')).toBeNull();

    await act(async () => {
      opts[1]!.dispatchEvent(new MouseEvent('mouseenter', { bubbles: false }));
    });
    const tip = document.querySelector('.select-menu [role="tooltip"]');
    expect(tip?.textContent).toContain('一天后失效，7 天后自动清理');
  });

  test('选「抖音号」时显式带 platform=douyin，纯数字不再靠后端猜', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('输入类型', '抖音');
    await fillUrl('67194609689');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.platform).toBe('douyin');
    expect(body.url).toBe('67194609689');
    // 抓不到更深时如实说还有更多，而不是报「不足以覆盖起点」
    expect(text()).toContain('还有更多');
  });

  test('B 站主页链接直接提交：url 原样带上，platform=bilibili', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('输入类型', 'B 站');
    await fillUrl('https://space.bilibili.com/12345');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    // 链接原样交给服务端：短链展开、sec_uid 提取都在那边，前端不截也不改写
    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.url).toBe('https://space.bilibili.com/12345');
    expect(body.platform).toBe('bilibili');
  });

  test('贴抖音主页链接会把类型自动切到抖音，再提交 platform=douyin', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('输入类型', 'B 站');
    expect(document.querySelector('.ext-url > span')!.textContent).toBe('B 站号 / 主页链接');

    const url = 'https://www.douyin.com/user/MS4wLjABAAAAR1Hyk5la2AbZ_a-00wgh6UnypbV35hOG_8bQvsBqDrNGzpxcHBu7ueuCbA';
    await fillUrl(url);
    // 平台由链接说了算，不用人再去改一遍下拉
    expect(document.querySelector('.ext-url > span')!.textContent).toBe('抖音号 / 主页链接');

    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));
    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.url).toBe(url);
    expect(body.platform).toBe('douyin');
  });

  test('字母型抖音号也按号提交：不当昵称，也不用先贴链接', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    // `luoyonghao` 这种字母号过去被「只能填纯数字」挡在门外，只能贴链接
    await fillUrl('luoyonghao');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.url).toBe('luoyonghao');
    expect(body.platform).toBe('douyin');
  });

  test('号里带数字的字母号不被截成数字（`chenling2024` 不是 `2024`）', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await fillUrl('chenling2024');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.url).toBe('chenling2024');
  });

  test('B 站号只能是数字：填字母串被拦下，不发请求', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('输入类型', 'B 站');
    await fillUrl('luoyonghao');
    await click(button('预览'));
    await until(() => text().includes('只能填纯数字的 B 站号'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  test('不带协议的域名也能认出来，提交前补上 https', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await fillUrl('space.bilibili.com/12345');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.url).toBe('https://space.bilibili.com/12345');
    expect(body.platform).toBe('bilibili');
  });

  test('贴作品链接被拦下：提示去「单个提取」，不发请求', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('输入类型', 'B 站');
    await fillUrl('https://www.bilibili.com/video/BV1xx411c7mD');
    await click(button('预览'));
    await until(() => text().includes('单个提取'));
    expect(text()).toContain('要抓整个 UP 主请贴空间主页');
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  test('填昵称被拦下：只收号码或主页链接，不发请求', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('输入类型', 'B 站');
    await fillUrl('碧诗');
    await click(button('预览'));
    await until(() => text().includes('只能填纯数字的 B 站号'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  test('输入类型是平台而不是「号」：抖音 / B 站两项', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await click(document.querySelector('button[aria-label="输入类型"]')!);
    const opts = Array.from(document.querySelectorAll('[role="option"]')).map((o) => o.textContent?.trim());
    expect(opts).toEqual(['抖音', 'B 站']);
    // 两种写法都要在页面上说出来，别让人以为只能填数字
    expect(text()).toContain('主页链接');
  });

  test('带前缀的号码也能挑出来：填「抖音号：67194609689」提交的是纯数字', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await fillUrl('抖音号：67194609689');
    await click(button('预览'));
    await until(() => text().includes('预计选取 2 条'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.url).toBe('67194609689');
    expect(body.platform).toBe('douyin');
  });
});

describe('批量提取：任务表格与行内操作', () => {
  test('表格列顺序固定，序号是灰色纯数字，博主第二行是「平台 · 用户 ID」', async () => {
    const url = 'https://www.douyin.com/user/MS4wLjABAAAAR1Hyk5la2AbZ_a-00wgh6UnypbV35hOG_8bQvsBqDrNGzpxcHBu7ueuCbA';
    server(ownerRoutes({
      [JOBS]: {
        json: {
          jobs: [job({ platform: 'douyin', creator_name: '', source_input: '67194609689', source_url: url })],
        },
      },
    }));
    mount('/creators');
    await until(() => jobRows().length === 1);

    const heads = Array.from(document.querySelectorAll('.cr-table thead th')).map((n) => n.textContent);
    expect(heads).toEqual(['序号', '博主', '状态', '选取', '存放', '格式', '进度', '占用', '发起时间', '操作']);

    const r = jobRows()[0]!;
    expect(r.querySelector('.c-no')!.textContent).toBe('1');
    // 昵称取不到就说「昵称未知」，不空着；第二行是平台 + 解析出的号（普通字体，不做胶囊）
    expect(r.querySelector('.who-name')!.textContent).toBe('昵称未知');
    expect(r.querySelector('.who-name')!.getAttribute('title')).toBe(url);
    expect(r.querySelector('.who-sub')!.textContent).toBe('抖音 · 67194609689');
    // 状态是淡色标签（无描边），进度是「已抓 / 总数」
    expect(r.querySelector('.pill')!.textContent).toBe('进行中');
    expect(r.querySelector('.prog-n')!.textContent!.replace(/\s/g, '')).toBe('6/20');
    expect(r.querySelector('.prog-bar')).not.toBeNull();
    // 发起时间到分，秒进 tooltip（按本地时区算，不写死时区）
    const two = (x: number) => String(x).padStart(2, '0');
    const d = new Date(1_700_000_000 * 1000);
    expect(r.querySelector('.c-at')!.textContent)
      .toContain(`${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`);
    expect(r.querySelector('.c-at')!.getAttribute('title')).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
  });

  test('进度条只在没跑完时出现；占用为 0 时给一道横线', async () => {
    server(ownerRoutes({
      [JOBS]: {
        json: {
          jobs: [
            job({ id: 1, creator_name: '跑完的', status: 'done', done: 20, failed: 0, estimated_bytes: 0 }),
            job({ id: 2, creator_name: '在跑的', status: 'running', done: 6 }),
          ],
        },
      },
    }));
    mount('/creators');
    await until(() => jobRows().length === 2);

    expect(jobRows()[0]!.querySelector('.prog-bar')).toBeNull();
    expect(jobRows()[0]!.querySelector('.c-bytes')!.textContent).toBe('—');
    expect(jobRows()[1]!.querySelector('.prog-bar')).not.toBeNull();
    expect(jobRows()[1]!.querySelector('.c-bytes')!.textContent).toContain('300');
  });

  test('服务器存档：进度不再重复「已存服务器 N / M」，占用 tooltip 带上实际占用', async () => {
    server(ownerRoutes({
      [JOBS]: {
        json: {
          jobs: [job({
            id: 9, status: 'done', storage: 'cache', total: 20, done: 12, failed: 0,
            stored: 12, store_bytes: 240 * 1024 * 1024,
          })],
        },
      },
      'GET /v2/creators/jobs/9/items?limit=5&offset=0': {
        json: {
          items: [
            row({ id: 11, video_id: 'BV9', title: '存好的作品', stored: true, bytes: 1024 * 1024,
                  download_state: 'done' }),
            row({ id: 12, video_id: 'BV10', title: '没存上的作品', stored: false, bytes: 0,
                  download_state: 'pending', store_error: '空间已满' }),
          ],
          total: 2, limit: 100, offset: 0,
        },
      },
    }));
    mount('/creators');
    await until(() => jobRows().length === 1);
    // done 与 stored 由服务端同一条 UPDATE 写入，数值恒等，副行只是重复主行
    expect(text()).not.toContain('已存服务器');
    expect(jobRows()[0]!.querySelector('.prog-sub')).toBeNull();
    expect(rowText()).toContain('服务器缓存');
    // 列里显示的是估算值，服务器上实际占多少进 tooltip
    expect(jobRows()[0]!.querySelector('.c-bytes')!.getAttribute('title')).toContain('240 MB');

    await click(button('显示条目'));
    await until(() => text().includes('存好的作品'));
    const tags = Array.from(document.querySelectorAll('.item-row .tag')).map((t) => t.textContent);
    expect(tags).toContain('已在服务器');
    // 「只存元数据」的任务条目没有这份标记
    expect(tags.filter((t) => t === '已在服务器')).toHaveLength(1);
  });

  test('「显示条目」是行内展开：条目落在该行下方的整行子区域里', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job()] } },
      'GET /v2/creators/jobs/3/items?limit=5&offset=0': {
        json: {
          items: [
            { id: 1, video_id: 'BV1', platform: 'bilibili', page: 1, title: '第一条作品', duration_sec: 204,
              published_at: null, is_pinned: false, seq: 1, cover_url: null, source_url: 'x',
              download_state: 'done', track_id: 9, error: null },
            { id: 2, video_id: 'BV2', platform: 'bilibili', page: 1, title: '第二条作品', duration_sec: 60,
              published_at: null, is_pinned: false, seq: 2, cover_url: null, source_url: 'x',
              download_state: 'failed', track_id: null, error: '平台拒绝' },
          ],
          total: 2, limit: 100, offset: 0,
        },
      },
    }));
    mount('/creators');
    await until(() => text().includes('显示条目'));
    await click(button('显示条目'));
    await until(() => text().includes('第一条作品'));

    // 展开的是紧跟该行的一个 colspan 子行，不是弹窗、不是跳转
    const sub = document.querySelector('.cr-table tbody tr.job-sub')!;
    expect(sub).not.toBeNull();
    expect(sub.querySelector('td')!.getAttribute('colspan')).toBe('10');
    expect(sub.nextElementSibling === null || sub.previousElementSibling === jobRows()[0]).toBe(true);

    // 表头：序号 / 标题 / 时长 / 发布 / 提取
    const head = Array.from(document.querySelectorAll('.item-head span')).map((n) => n.textContent);
    expect(head.slice(0, 5)).toEqual(['序号', '标题', '时长', '发布', '提取']);
    expect(document.querySelectorAll('.kv-grid')).toHaveLength(0);
    expect(Array.from(document.querySelectorAll('.item-row .tag.bad')).map((n) => n.textContent))
      .toEqual(['失败']);

    // 每条都能直接下到本机：按钮点下去才发请求（先探 2 字节，能出流才真正下载）
    const btns = Array.from(document.querySelectorAll('.item-row button')) as HTMLButtonElement[];
    expect(btns.map((b) => b.textContent)).toEqual(['下载', '下载']);
    expect(text()).toContain('下载全部（2 条）');
    expect(Array.from(document.querySelectorAll('.item-row .item-no')).map((n) => n.textContent)).toEqual(['1', '2']);

    // 再点一次收起（展开时按钮变成「收起条目 ▴」）
    await click(button('收起条目 ▴'));
    expect(document.querySelector('.cr-table tbody tr.job-sub')).toBeNull();
  });

  test('手机下载的任务条目一直 pending，不显示「待处理」三个字', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ storage: 'phone' })] } },
      'GET /v2/creators/jobs/3/items?limit=5&offset=0': {
        json: {
          items: [
            { id: 1, video_id: 'BV1', platform: 'bilibili', page: 1, title: '第一条作品', duration_sec: 204,
              published_at: null, is_pinned: false, seq: 1, cover_url: null, source_url: 'x',
              download_state: 'pending', track_id: null, error: null },
            { id: 2, video_id: 'BV2', platform: 'bilibili', page: 1, title: '第二条作品', duration_sec: 60,
              published_at: null, is_pinned: false, seq: 2, cover_url: null, source_url: 'x',
              download_state: 'pending', track_id: null, error: null },
          ],
          total: 2, limit: 100, offset: 0,
        },
      },
    }));
    mount('/creators');
    await until(() => text().includes('显示条目'));
    await click(button('显示条目'));
    await until(() => text().includes('第一条作品'));

    expect(text()).not.toContain('待处理');
    expect(document.querySelectorAll('.item-row .tag')).toHaveLength(0);
    expect(Array.from(document.querySelectorAll('.item-row .item-no')).map((n) => n.textContent)).toEqual(['1', '2']);
    expect(document.querySelectorAll('.item-row button')).toHaveLength(2);
  });

  test('暂停态：菜单里是「继续 + 重试失败条目」，点了真的发请求', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'paused' })] } },
      'POST /v2/creators/jobs/3/resume': { json: job() },
      'POST /v2/creators/jobs/3/retry': { json: { job: job() } },
    }));
    mount('/creators');
    await until(() => jobRows().length === 1);

    expect(await menuTexts()).toEqual(['继续', '取消任务', '重试失败条目', '复制链接', '不再显示']);
    await click(button('重试失败条目'));
    await until(() => calls.some((c) => c.path === '/v2/creators/jobs/3/retry'));

    // 菜单关掉后重新打开，「继续」还在
    expect(document.querySelector('[role="menuitem"]')).toBeNull();
    await openRowMenu();
    await click(button('继续'));
    await until(() => calls.some((c) => c.path === '/v2/creators/jobs/3/resume'));
  });

  test('进行中：菜单里是「暂停 + 取消任务」，没有「重试失败条目」', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'running', failed: 0 })] } },
      'POST /v2/creators/jobs/3/pause': { json: job({ status: 'paused' }) },
    }));
    mount('/creators');
    await until(() => jobRows().length === 1);

    expect(await menuTexts()).toEqual(['暂停', '取消任务', '复制链接', '不再显示']);
    await click(button('暂停'));
    await until(() => calls.some((c) => c.path === '/v2/creators/jobs/3/pause'));
  });

  test('已完成的任务给「刷新地址」，点了就调 retry', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'done', platform: 'douyin', source_url: 'https://www.douyin.com/user/1' })] } },
      'POST /v2/creators/jobs/3/retry': {
        json: { requeued: 0, rescan: true, job: job({ status: 'done' }) },
      },
    }));
    mount('/creators');
    await until(() => jobRows().length === 1);

    // 跑完的任务没有「重试失败条目」，给的是「刷新地址」
    expect(await menuTexts()).not.toContain('重试失败条目');
    await click(button('刷新地址'));
    await until(() => calls.some((c) => c.path === '/v2/creators/jobs/3/retry'));
  });

  test('B 站任务不给「刷新地址」——它每次下载都现解析，换地址没有意义', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'done' })] } },
    }));
    mount('/creators');
    await until(() => jobRows().length === 1);

    expect(() => button('刷新地址')).toThrow();
    expect(document.querySelectorAll('.row-ops .text-btn')).toHaveLength(1);
  });

  test('已取消任务不给「刷新地址」——它不在下载，而且那个入口会改任务终态', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'cancelled', platform: 'douyin', source_url: 'https://www.douyin.com/user/1' })] } },
    }));
    mount('/creators');
    await until(() => jobRows().length === 1);

    const labels = Array.from(document.querySelectorAll('.row-ops .text-btn')).map((b) => b.textContent?.trim());
    expect(labels).toEqual(['显示条目']);
    expect(document.querySelector('.row-ops button[aria-label="更多操作"]')).not.toBeNull();
  });

  test('复制链接走「⋯」，回执是一条 toast', async () => {
    const writeText = vi.fn(async () => undefined);
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ source_url: 'https://space.bilibili.com/12345' })] } },
    }));
    mount('/creators');
    await until(() => jobRows().length === 1);

    await openRowMenu();
    await click(button('复制链接'));
    await until(() => text().includes('已复制'));
    expect(writeText).toHaveBeenCalledWith('https://space.bilibili.com/12345');
  });
});

describe('批量提取：不再显示', () => {
  test('只隐藏被点的那一个，并且能当场撤销', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ id: 3, creator_name: '第一个博主' }), job({ id: 4, creator_name: '第二个博主' })] } },
    }));
    mount('/creators');
    await until(() => text().includes('第二个博主'));

    expect(Array.from(document.querySelectorAll('.cr-table tbody td.c-no')).map((n) => n.textContent)).toEqual(['1', '2']);

    await openRowMenu(1);
    await click(button('不再显示'));
    await until(() => !text().includes('第二个博主'));

    // 只影响被点的那一个；剩下那个重新从 1 开始编号
    expect(text()).toContain('第一个博主');
    expect(jobRows()).toHaveLength(1);
    expect(Array.from(document.querySelectorAll('.cr-table tbody td.c-no')).map((n) => n.textContent)).toEqual(['1']);
    // 记在本机，刷新后仍然不出现
    expect(JSON.parse(window.localStorage.getItem('getmedia.creators.hiddenJobs')!)).toEqual([4]);
    expect(text()).toContain('已隐藏 1 个任务');

    // 不弹确认框，改为 toast 里给一次撤销
    await click(button('撤销'));
    await until(() => text().includes('第二个博主'));
    expect(JSON.parse(window.localStorage.getItem('getmedia.creators.hiddenJobs')!)).toEqual([]);
  });

  test('隐藏是持久的，「恢复显示」能把任务放回来', async () => {
    window.localStorage.setItem('getmedia.creators.hiddenJobs', JSON.stringify([4]));
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ id: 3, creator_name: '第一个博主' }), job({ id: 4, creator_name: '被隐藏的博主' })] } },
    }));
    mount('/creators');
    await until(() => text().includes('第一个博主'));

    expect(text()).not.toContain('被隐藏的博主');
    await click(button('恢复显示'));
    await until(() => text().includes('被隐藏的博主'));
    expect(text()).not.toContain('已隐藏');
    expect(window.localStorage.getItem('getmedia.creators.hiddenJobs')).toBe('[]');
  });
});

describe('批量提取：条目下载', () => {
  test('单条下载：先探 2 字节确认能出流，再交给浏览器存盘', async () => {
    const saved = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job()] } },
      'GET /v2/creators/jobs/3/items?limit=5&offset=0': {
        json: { items: [row()], total: 1, limit: 100, offset: 0 },
      },
      'GET /v2/creators/jobs/3/items/BV1/file': { json: {} },
    }));
    mount('/creators');
    await until(() => text().includes('显示条目'));
    await click(button('显示条目'));
    await until(() => text().includes('第一条作品'));

    await click(button('下载'));
    await until(() => calls.some((c) => c.path.endsWith('/items/BV1/file')));
    await until(() => saved.mock.calls.length > 0);
  });

  test('直链过期时原样显示服务端提示，不把错误当文件存下来', async () => {
    const saved = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job()] } },
      'GET /v2/creators/jobs/3/items?limit=5&offset=0': {
        json: { items: [row()], total: 1, limit: 100, offset: 0 },
      },
      'GET /v2/creators/jobs/3/items/BV1/file': {
        status: 409,
        json: { detail: '该链接已失效，请 1 分钟后重试。' },
      },
    }));
    mount('/creators');
    await until(() => text().includes('显示条目'));
    await click(button('显示条目'));
    await until(() => text().includes('第一条作品'));

    await click(button('下载'));
    await until(() => text().includes('该链接已失效，请 1 分钟后重试。'));
    expect(saved).not.toHaveBeenCalled();
  });

  test('下载全部：先把没加载的条目取回来，运行中可以停', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job()] } },
      'GET /v2/creators/jobs/3/items?limit=5&offset=0': {
        json: { items: [row(), row({ id: 2, video_id: 'BV2', title: '第二条作品' })], total: 3, limit: 10, offset: 0 },
      },
      'GET /v2/creators/jobs/3/items?limit=100&offset=0': {
        json: { items: [row(), row({ id: 2, video_id: 'BV2', title: '第二条作品' })], total: 3, limit: 100, offset: 0 },
      },
      'GET /v2/creators/jobs/3/items?limit=100&offset=2': {
        json: { items: [row({ id: 3, video_id: 'BV3', title: '第三条作品' })], total: 3, limit: 100, offset: 2 },
      },
      'GET /v2/creators/jobs/3/items/BV1/file': { json: {} },
      'GET /v2/creators/jobs/3/items/BV2/file': { json: {} },
      'GET /v2/creators/jobs/3/items/BV3/file': { json: {} },
    }));
    mount('/creators');
    await until(() => text().includes('显示条目'));
    await click(button('显示条目'));
    await until(() => text().includes('下载全部（3 条）'));

    await click(button('下载全部（3 条）'));
    await until(() => calls.some((c) => c.path.endsWith('/items') && c.search.includes('offset=2')));
    await until(() => text().includes('正在下载'));
    await click(button('停止下载'));
    await until(() => text().includes('已停止'));
  });
});

describe('批量提取：多格式与任务行信息', () => {
  test('切到音频后格式候选跟着换，多选结果一起提交', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/jobs': { json: job({ status: 'pending' }) },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    // 视频默认「原始格式」
    expect(document.querySelector('button[aria-label="输出格式"]')!.textContent).toContain('原始格式');

    await choose('格式', '音频');
    await until(() => document.querySelector('button[aria-label="输出格式"]')!.textContent!.includes('.m4a'));

    // 多选：.m4a 已选，再勾 .mp3
    await click(document.querySelector('button[aria-label="输出格式"]')!);
    const opts = Array.from(document.querySelectorAll('[role="option"]'));
    expect(opts.map((o) => o.textContent)).toEqual(['.m4a', '.mp3', '.flac', '.wav']);
    await click(opts[1]!);
    expect(document.querySelector('button[aria-label="输出格式"]')!.textContent).toContain('.m4a、.mp3');

    await fillUrl('12345');
    await click(button('开始提取'));
    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.media_type).toBe('audio');
    expect(body.formats).toEqual(['m4a', 'mp3']);
  });

  test('存放与格式是两列：存放一格、格式一格（第二行是媒体类型）；指定日期只显示月日', async () => {
    server(ownerRoutes({
      [JOBS]: {
        json: {
          jobs: [
            job({ id: 1, mode: 'date_range', since_ts: 1_700_000_000, until_ts: 1_701_000_000, limit_n: null }),
            job({ id: 2, mode: 'all', limit_n: null, media_type: 'audio', formats: ['m4a'], storage: 'archive' }),
          ],
        },
      },
    }));
    mount('/creators');
    await until(() => jobRows().length === 2);

    const store = (i: number) => jobRows()[i]!.querySelector('.c-store')!.textContent;
    const fmt = (i: number) => {
      const cell = jobRows()[i]!.querySelector('.c-fmt')!;
      return [cell.querySelector('.fmt')!.textContent, cell.querySelector('.sub')!.textContent];
    };
    expect(store(0)).toBe('只存元数据');
    expect(fmt(0)).toEqual(['原始格式', '视频']);
    expect(store(1)).toBe('服务器存储');
    expect(fmt(1)).toEqual(['.m4a', '音频']);
    // 格式列窄（88px），完整含义（媒体类型 · 格式）进 tooltip
    expect(jobRows()[1]!.querySelector('.c-fmt .fmt')!.getAttribute('title')).toBe('音频 · .m4a');
    // 选取列：日期范围缩成月日，完整内容进 tooltip
    const two = (x: number) => String(x).padStart(2, '0');
    const md = (ts: number) => { const d = new Date(ts * 1000); return `${two(d.getMonth() + 1)}-${two(d.getDate())}`; };
    expect(jobRows()[0]!.querySelector('.c-sel')!.textContent)
      .toBe(`${md(1_700_000_000)} ~ ${md(1_701_000_000)}`);
    expect(jobRows()[0]!.querySelector('.c-sel')!.getAttribute('title')).toContain('指定日期');
    expect(jobRows()[1]!.querySelector('.c-sel')!.textContent).toBe('全部作品');
  });

  test('条目按表头对齐；提取时间用落盘时间；每种格式可单独下载', async () => {
    const saved = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ storage: 'archive' })] } },
      'GET /v2/creators/jobs/3/items?limit=5&offset=0': {
        json: {
          items: [row({
            stored: true, bytes: 100, stored_at: 1_800_000_500, added_at: 1_799_999_000,
            files: { source: { path: 'a.mp4', bytes: 50 }, mp3: { path: 'a.mp3', bytes: 50 } },
          })],
          total: 1, limit: 100, offset: 0,
        },
      },
      'GET /v2/creators/jobs/3/items/BV1/file?fmt=mp3': { json: {} },
    }));
    mount('/creators');
    await until(() => text().includes('显示条目'));
    await click(button('显示条目'));
    await until(() => text().includes('第一条作品'));

    const heads = Array.from(document.querySelectorAll('.item-head span')).map((n) => n.textContent);
    expect(heads.slice(0, 5)).toEqual(['序号', '标题', '时长', '发布', '提取']);
    expect(text()).toContain('2027-01-15 03:08');      // stored_at，不是 added_at

    const chips = Array.from(document.querySelectorAll('.fmt-chip')).map((b) => b.textContent);
    expect(chips).toEqual(['原始格式', '.mp3']);
    await click(document.querySelectorAll('.fmt-chip')[1]!);
    await until(() => calls.some((c) => (c.search ?? '').includes('fmt=mp3')));
    expect(saved).toHaveBeenCalled();
  });
});

describe('批量任务筛选', () => {
  function threeJobs() {
    return [
      job({ id: 1, platform: 'douyin', creator_name: '小叶稚', source_input: '67194609689', status: 'done' }),
      job({ id: 2, platform: 'douyin', creator_name: '美食博主', source_input: '88888888888', status: 'cancelled' }),
      job({ id: 3, platform: 'bilibili', creator_name: '科技UP', source_input: 'https://space.bilibili.com/123', creator_id: '123', status: 'running' }),
    ];
  }
  const cardCount = () => jobRows().length;

  async function typeFilter(placeholder: string, v: string) {
    const input = document.querySelector(`input[placeholder="${placeholder}"]`) as HTMLInputElement;
    await act(async () => {
      input.value = v;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
  }

  test('状态分段带数量；状态、平台、用户 ID、昵称都能筛，条件是「且」关系', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: threeJobs() } } }));
    mount('/creators');
    await until(() => cardCount() === 3);

    // 分段控件（不是下拉），每段带这一状态的任务数
    const tabs = Array.from(
      document.querySelectorAll('[role="tablist"][aria-label="按状态筛选"] [role="tab"]'),
    ).map((t) => t.textContent?.trim());
    expect(tabs).toEqual(['全部3', '待开始0', '进行中1', '已暂停0', '已完成1', '失败0', '已取消1']);
    expect(document.querySelector('[aria-label="按状态筛选"] .select-trigger')).toBeNull();

    // 状态：已完成 → 只剩小叶稚
    await pickStatus('已完成');
    await until(() => cardCount() === 1);
    expect(text()).toContain('小叶稚');

    // 叠加平台=抖音 → 仍 1 个
    await choose('筛选：平台', '抖音');
    await until(() => cardCount() === 1);

    // 清掉状态（点「全部」），只剩平台=抖音 → 2 个
    await pickStatus('全部');
    await until(() => cardCount() === 2);

    // 再叠加用户 ID → 只剩小叶稚
    await typeFilter('输入用户 ID', '67194609689');
    await until(() => cardCount() === 1);
    expect(text()).toContain('小叶稚');
  });

  test('昵称筛选按包含匹配；重置筛选一键清空所有条件', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: threeJobs() } } }));
    mount('/creators');
    await until(() => cardCount() === 3);

    await typeFilter('输入昵称', '博主');
    await until(() => cardCount() === 1);
    expect(text()).toContain('美食博主');

    // 再设一个状态条件，触发「重置筛选」按钮出现
    await pickStatus('已完成');
    await until(() => cardCount() === 0);
    expect(text()).toContain('没有符合条件的任务');

    await click(button('重置筛选'));
    await until(() => cardCount() === 3);
  });

  test('发起时间：两端默认都为空；只填一端也生效，边界含当天', async () => {
    const nowSec = Math.floor(Date.now() / 1000);
    const day = 86400;
    const two = (x: number) => String(x).padStart(2, '0');
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [
        job({ id: 1, creator_name: '两天前', created_at: nowSec - 2 * day }),
        job({ id: 2, creator_name: '昨天', created_at: nowSec - day }),
        job({ id: 3, creator_name: '现在', created_at: nowSec }),
      ] } },
    }));
    mount('/creators');
    await until(() => cardCount() === 3);

    // 默认两端为空（不再预填今天），所以三个任务都在
    const start = () => document.querySelector('input[aria-label="发起时间开始"]') as HTMLInputElement;
    const end = () => document.querySelector('input[aria-label="发起时间截止"]') as HTMLInputElement;
    expect(start().value).toBe('');
    expect(end().value).toBe('');

    // 只填开始时间 = 昨天 → 筛掉「两天前」
    const y = new Date((nowSec - day) * 1000);
    const yesterdayIso = `${y.getFullYear()}-${two(y.getMonth() + 1)}-${two(y.getDate())}`;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => {
      setter.call(start(), yesterdayIso);
      start().dispatchEvent(new Event('input', { bubbles: true }));
    });
    await until(() => cardCount() === 2);
    expect(text()).toContain('昨天');
    expect(text()).toContain('现在');
    expect(text()).not.toContain('两天前');

    // 只填截止时间 = 昨天 → 只剩「两天前」和「昨天」（含当天 23:59:59）
    await act(async () => {
      setter.call(start(), '');
      start().dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => {
      setter.call(end(), yesterdayIso);
      end().dispatchEvent(new Event('input', { bubbles: true }));
    });
    await until(() => cardCount() === 2);
    expect(text()).toContain('两天前');
    expect(text()).toContain('昨天');
    expect(text()).not.toContain('现在');
  });

  test('「快捷」一键把发起时间设成近 7 天', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await click(button('快捷 ▾'));
    const items = Array.from(document.querySelectorAll('[role="menuitem"]')).map((n) => n.textContent);
    expect(items).toEqual(['今天', '近 7 天', '近 30 天', '本月', '清除日期']);

    await click(button('近 7 天'));
    const start = document.querySelector('input[aria-label="发起时间开始"]') as HTMLInputElement;
    const end = document.querySelector('input[aria-label="发起时间截止"]') as HTMLInputElement;
    expect(start.value).not.toBe('');
    expect(end.value).not.toBe('');
    // 区间倒着填会自动对调
    expect(start.value <= end.value).toBe(true);
  });

  test('存放方式筛「只存元数据」同时命中 metadata 与旧数据 phone', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [
        job({ id: 1, creator_name: '新只记', storage: 'metadata' }),
        job({ id: 2, creator_name: '旧手机', storage: 'phone' }),
        job({ id: 3, creator_name: '暂存', storage: 'cache' }),
      ] } },
    }));
    mount('/creators');
    await until(() => cardCount() === 3);

    await choose('筛选：存放方式', '只存元数据');
    await until(() => cardCount() === 2);
    expect(text()).toContain('新只记');
    expect(text()).toContain('旧手机');
    expect(text()).not.toContain('暂存');
  });
});

describe('条目翻页', () => {
  const rows = (from: number, n: number) => Array.from({ length: n }, (_, i) => row({
    id: from + i, video_id: `BV${from + i}`, title: `作品 ${from + i}`,
    seq: from + i,   // 普通作品的全局序号：从 1 起，跨页连续
  }));

  function routes() {
    return ownerRoutes({
      [JOBS]: { json: { jobs: [job({ total: 25 })] } },
      'GET /v2/creators/jobs/3/items?limit=5&offset=0': { json: { items: rows(1, 5), total: 25, limit: 5, offset: 0 } },
      'GET /v2/creators/jobs/3/items?limit=5&offset=5': { json: { items: rows(6, 5), total: 25, limit: 5, offset: 5 } },
      'GET /v2/creators/jobs/3/items?limit=20&offset=0': { json: { items: rows(1, 20), total: 25, limit: 20, offset: 0 } },
      'GET /v2/creators/jobs/3/items?limit=10&offset=0': { json: { items: rows(1, 10), total: 25, limit: 10, offset: 0 } },
    });
  }

  const shown = () => Array.from(document.querySelectorAll('.item-row')).length;
  const firstNo = () => document.querySelector('.item-row .item-no')!.textContent;

  test('默认每页 5 条；翻页只换这一页的内容，序号接着往下数', async () => {
    server(routes());
    mount('/creators');
    await until(() => text().includes('显示条目'));
    await click(button('显示条目'));
    await until(() => shown() === 5);

    expect(calls.some((c) => c.search.includes('limit=5&offset=0'))).toBe(true);
    expect(() => button('加载更多（还有 20 条）')).toThrow();
    expect(text()).toContain('第 1 / 5 页');
    expect(firstNo()).toBe('1');

    await click(button('2'));
    await until(() => shown() === 5 && text().includes('作品 6'));
    expect(firstNo()).toBe('6');
    expect(text()).toContain('第 2 / 5 页');

    await click(button('上一页'));
    await until(() => text().includes('作品 1') && firstNo() === '1');
  });

  test('每页条数可以选 5 / 10 / 20，换完回到第一页', async () => {
    server(routes());
    mount('/creators');
    await until(() => text().includes('显示条目'));
    await click(button('显示条目'));
    await until(() => shown() === 5);

    await click(button('2'));
    await until(() => firstNo() === '6');

    await choose('每页条数', '20 条');
    await until(() => shown() === 20);
    expect(firstNo()).toBe('1');
    expect(text()).toContain('第 1 / 2 页');

    await choose('每页条数', '10 条');
    await until(() => shown() === 10);
    expect(text()).toContain('第 1 / 3 页');
    await click(document.querySelector('button[aria-label="每页条数"]')!);
    expect(Array.from(document.querySelectorAll('[role="option"]')).map((o) => o.textContent))
      .toEqual(['5 条', '10 条', '20 条']);
  });
});
