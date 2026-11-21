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
    created_at: 1_800_000_000, updated_at: 1_800_000_100, started_at: 1_800_000_001, finished_at: null,
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

    await fillUrl('https://space.bilibili.com/12345');
    await click(button('预览'));
    await until(() => text().includes('会选中 2 条'));

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

  test('建任务：把存放方式与选取条件一起提交，随后列表刷新', async () => {
    let round = 0;
    server(ownerRoutes({
      [JOBS]: () => (round++ === 0 ? { json: { jobs: [] } } : { json: { jobs: [job()] } }),
      'POST /v2/creators/jobs': { json: job({ status: 'pending', done: 0, failed: 0 }) },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await fillUrl('12345');
    await choose('存放方式', '服务器保留');
    await click(button('开始提取'));
    await until(() => text().includes('某个 UP 主'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body).toMatchObject({ url: '12345', mode: 'latest_n', n: 20, storage: 'archive', media_type: 'video' });
    expect(text()).toContain('进度：7 / 20');
    expect(text()).toContain('失败 1');
  });

  test('暂停与重试只对相应状态出现，并且真的发请求', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'paused' })] } },
      'POST /v2/creators/jobs/3/resume': { json: job() },
      'POST /v2/creators/jobs/3/retry': { json: { job: job() } },
    }));
    mount('/creators');
    await until(() => text().includes('进度：7 / 20'));

    // 暂停态：有"继续"和"重试"，没有"暂停"
    expect(() => button('暂停')).toThrow();
    expect(() => button('继续')).not.toThrow();
    await click(button('继续'));
    await until(() => calls.some((c) => c.path === '/v2/creators/jobs/3/resume'));
    await click(button('重试失败条目'));
    await until(() => calls.some((c) => c.path === '/v2/creators/jobs/3/retry'));
  });

  test('已完成的任务给「刷新地址」，点了就调 retry', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'done', platform: 'douyin', source_url: 'https://www.douyin.com/user/1' })] } },
      'POST /v2/creators/jobs/3/retry': {
        json: { requeued: 0, rescan: true, job: job({ status: 'done' }) },
      },
    }));
    mount('/creators');
    await until(() => text().includes('进度：7 / 20'));

    // 跑完的任务没有「重试失败条目」，给的是「刷新地址」
    expect(() => button('重试失败条目')).toThrow();
    await click(button('刷新地址'));
    await until(() => calls.some((c) => c.path === '/v2/creators/jobs/3/retry'));
  });

  test('B 站任务不给「刷新地址」——它每次下载都现解析，换地址没有意义', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'done' })] } },
    }));
    mount('/creators');
    await until(() => text().includes('进度：7 / 20'));

    expect(() => button('刷新地址')).toThrow();
    expect(() => button('重试失败条目')).toThrow();
  });

  test('展开条目：取分页条目并显示每个条目的状态', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job()] } },
      'GET /v2/creators/jobs/3/items?limit=100&offset=0': {
        json: {
          items: [
            { id: 1, video_id: 'BV1', platform: 'bilibili', page: 1, title: '第一条作品', duration_sec: 204,
              published_at: null, is_pinned: false, cover_url: null, source_url: 'x',
              download_state: 'done', track_id: 9, error: null },
            { id: 2, video_id: 'BV2', platform: 'bilibili', page: 1, title: '第二条作品', duration_sec: 60,
              published_at: null, is_pinned: false, cover_url: null, source_url: 'x',
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

    // 表头：序号 / 标题 / 时长 / 发布 / 提取
    const head = Array.from(document.querySelectorAll('.item-head span')).map((n) => n.textContent);
    expect(head.slice(0, 5)).toEqual(['#', '标题', '时长', '发布', '提取']);
    // 失败的条目有标记，不再有「条目总数 / 已入库 / 失败」三栏统计
    expect(document.querySelectorAll('.kv-grid')).toHaveLength(0);
    expect(Array.from(document.querySelectorAll('.item-row .tag.bad')).map((n) => n.textContent))
      .toEqual(['失败']);

    // 每条都能直接下到本机：按钮点下去才发请求（先探 2 字节，能出流才真正下载）
    const rows = Array.from(document.querySelectorAll('.item-row button')) as HTMLButtonElement[];
    expect(rows.map((b) => b.textContent)).toEqual(['下载', '下载']);
    expect(text()).toContain('下载全部（2 条）');
    // 每条作品前有连续序号
    expect(Array.from(document.querySelectorAll('.item-no')).map((n) => n.textContent)).toEqual(['1', '2']);
  });

  test('手机下载的任务条目一直 pending，不显示「待处理」三个字', async () => {    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ storage: 'phone' })] } },
      'GET /v2/creators/jobs/3/items?limit=100&offset=0': {
        json: {
          items: [
            { id: 1, video_id: 'BV1', platform: 'bilibili', page: 1, title: '第一条作品', duration_sec: 204,
              published_at: null, is_pinned: false, cover_url: null, source_url: 'x',
              download_state: 'pending', track_id: null, error: null },
            { id: 2, video_id: 'BV2', platform: 'bilibili', page: 1, title: '第二条作品', duration_sec: 60,
              published_at: null, is_pinned: false, cover_url: null, source_url: 'x',
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
    // 序号与下载按钮不受影响
    expect(Array.from(document.querySelectorAll('.item-no')).map((n) => n.textContent)).toEqual(['1', '2']);
    expect(document.querySelectorAll('.item-row button')).toHaveLength(2);
  });

  test('抖音被挡时显示服务端给的原因，不粉饰成网络问题', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { status: 400, json: { detail: '拿不到作品列表：平台拒绝了这次请求（403）', code: 'creator_input' } },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));
    await fillUrl('抖音号');
    await click(button('预览'));
    await until(() => text().includes('平台拒绝了这次请求'));
    expect(text()).not.toContain('网络不稳定');
  });

  test('选「抖音号」时显式带 platform=douyin，纯数字不再靠后端猜', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    // 类型下拉切到「抖音号」
    await choose('输入类型', '抖音号');
    await fillUrl('67194609689');
    await click(button('预览'));
    await until(() => text().includes('会选中 2 条'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.platform).toBe('douyin');
    expect(body.url).toBe('67194609689');
    // 「全部作品」抓不到更深时如实说还有更多，而不是报「不足以覆盖起点」
    expect(text()).toContain('还有更多');
  });

  test('选「抖音号」后填非数字直接拦下，不发请求', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('输入类型', '抖音号');
    await fillUrl('https://space.bilibili.com/1');
    await click(button('预览'));
    await until(() => text().includes('只能填纯数字'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  test('任务按顺序编号，「不再显示」只隐藏这一个任务', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ id: 3, creator_name: '第一个博主' }), job({ id: 4, creator_name: '第二个博主' })] } },
    }));
    mount('/creators');
    await until(() => text().includes('第二个博主'));

    expect(Array.from(document.querySelectorAll('.job-no')).map((n) => n.textContent)).toEqual(['1', '2']);

    const hides = Array.from(document.querySelectorAll('.job-hide')) as HTMLButtonElement[];
    expect(hides.map((b) => b.textContent?.trim())).toEqual(['不再显示', '不再显示']);
    await click(hides[1]!);

    await until(() => !text().includes('第二个博主'));
    // 只影响被点的那一个；剩下那个重新从 1 开始编号
    expect(text()).toContain('第一个博主');
    expect(document.querySelectorAll('.job')).toHaveLength(1);
    expect(Array.from(document.querySelectorAll('.job-no')).map((n) => n.textContent)).toEqual(['1']);
    // 记在本机，刷新后仍然不出现
    expect(JSON.parse(window.localStorage.getItem('getmedia.creators.hiddenJobs')!)).toEqual([4]);
    expect(text()).toContain('已隐藏 1 个任务');
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

  test('粘贴整段分享文案，只把核心主页链接交给后端', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await fillUrl('7.53 复制打开抖音，看看【某某】的主页 https://www.douyin.com/user/MS4wLjABAAAAxxxx?from_tab_name=main 复制此链接');
    await click(button('预览'));
    await until(() => text().includes('会选中 2 条'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.url).toBe('https://www.douyin.com/user/MS4wLjABAAAAxxxx');
    // 输入框也被收回核心链接，用户看得到实际提交的是什么
    const input = document.querySelector('input[type="text"]') as HTMLInputElement;
    expect(input.value).toBe('https://www.douyin.com/user/MS4wLjABAAAAxxxx');
  });

  test('指定日期的结束日期默认今天，两个日期都是日期选择器', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await choose('选取方式', '指定日期');

    const dates = Array.from(document.querySelectorAll('input[type="date"]')) as HTMLInputElement[];
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
      .toEqual(['只记清单', '服务器暂存', '服务器保留']);
    // 刚展开不弹提示，悬停到某一项才弹
    expect(document.querySelector('[role="tooltip"]')).toBeNull();

    await act(async () => {
      opts[1]!.dispatchEvent(new MouseEvent('mouseenter', { bubbles: false }));
    });
    const tip = document.querySelector('[role="tooltip"]');
    expect(tip?.textContent).toContain('一天后失效，7 天后自动清理');
  });

  test('服务器存档：任务卡显示已存条数与占用，条目上标「已在服务器」', async () => {
    server(ownerRoutes({
      [JOBS]: {
        json: {
          jobs: [job({
            id: 9, status: 'done', storage: 'cache', total: 20, done: 12, failed: 0,
            stored: 12, store_bytes: 240 * 1024 * 1024,
          })],
        },
      },
      'GET /v2/creators/jobs/9/items?limit=100&offset=0': {
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
    await until(() => text().includes('已在服务器：12 条'));
    expect(text()).toContain('服务器暂存');
    expect(text()).toContain('240 MB');

    await click(button('显示条目'));
    await until(() => text().includes('存好的作品'));
    const tags = Array.from(document.querySelectorAll('.item-row .tag')).map((t) => t.textContent);
    expect(tags).toContain('已在服务器');
    // 「只记清单」的任务条目没有这份标记
    expect(tags.filter((t) => t === '已在服务器')).toHaveLength(1);
  });

  test('单条下载：先探 2 字节确认能出流，再交给浏览器存盘', async () => {
    const saved = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job()] } },
      'GET /v2/creators/jobs/3/items?limit=100&offset=0': {
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
      'GET /v2/creators/jobs/3/items?limit=100&offset=0': {
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
    // 409 不落盘：否则浏览器会把一段 JSON 报错存成 .mp4
    expect(saved).not.toHaveBeenCalled();
  });

  test('下载全部：先把没加载的条目取回来，运行中可以停', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job()] } },
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
    // 「全部」不等于「已加载的那 100 条」：第二页也要取回来
    await until(() => calls.some((c) => c.path.endsWith('/items') && c.search.includes('offset=2')));
    await until(() => text().includes('正在下载'));
    await click(button('停止下载'));
    await until(() => text().includes('已停止'));
  });
});


describe('批量提取：多格式与任务卡信息', () => {
  test('切到音频后格式候选跟着换，多选结果一起提交', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/jobs': { json: job({ status: 'pending' }) },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    // 视频默认「原样保存」
    expect(document.querySelector('button[aria-label="输出格式"]')!.textContent).toContain('原样保存');

    await choose('格式', '音频');
    await until(() => document.querySelector('button[aria-label="输出格式"]')!.textContent!.includes('M4A'));

    // 多选：M4A 已选，再勾 MP3
    await click(document.querySelector('button[aria-label="输出格式"]')!);
    const opts = Array.from(document.querySelectorAll('[role="option"]'));
    expect(opts.map((o) => o.textContent)).toEqual(['M4A', 'MP3', 'FLAC', 'WAV']);
    await click(opts[1]!);
    expect(document.querySelector('button[aria-label="输出格式"]')!.textContent).toContain('M4A、MP3');

    await fillUrl('https://space.bilibili.com/12345');
    await click(button('开始提取'));
    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body.media_type).toBe('audio');
    expect(body.formats).toEqual(['m4a', 'mp3']);
  });

  test('任务卡：昵称、抖音号、完整链接、绝对时间与「显示条目」', async () => {
    const url = 'https://www.douyin.com/user/MS4wLjABAAAAR1Hyk5la2AbZ_a-00wgh6UnypbV35hOG_8bQvsBqDrNGzpxcHBu7ueuCbA';
    server(ownerRoutes({
      [JOBS]: {
        json: {
          jobs: [job({
            platform: 'douyin', creator_name: '', source_input: '67194609689',
            source_url: url, created_at: 1_800_000_000,
          })],
        },
      },
    }));
    mount('/creators');
    await until(() => text().includes('抖音号 67194609689'));

    // 没取到昵称时用号码交代「抓的是谁」，链接完整显示、可复制
    expect(document.querySelector('.job-url')!.textContent).toBe(url);
    expect(text()).toContain('2027-01-15 03:00:00');
    expect(() => button('复制链接')).not.toThrow();
    expect(() => button('显示条目')).not.toThrow();
  });

  test('条目按表头对齐；提取时间用落盘时间；每种格式可单独下载', async () => {
    const saved = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ storage: 'archive' })] } },
      'GET /v2/creators/jobs/3/items?limit=100&offset=0': {
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
    expect(heads.slice(0, 5)).toEqual(['#', '标题', '时长', '发布', '提取']);
    expect(text()).toContain('2027-01-15 03:08');      // stored_at，不是 added_at

    const chips = Array.from(document.querySelectorAll('.fmt-chip')).map((b) => b.textContent);
    expect(chips).toEqual(['原样保存', 'MP3']);
    await click(document.querySelectorAll('.fmt-chip')[1]!);
    await until(() => calls.some((c) => (c.search ?? '').includes('fmt=mp3')));
    expect(saved).toHaveBeenCalled();
  });
});
