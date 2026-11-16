/** 5d：博主批量。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act } from 'preact/test-utils';
import {
  button, calls, cleanup, click, mount, ownerRoutes, server, text, until,
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
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('博主批量', () => {
  test('预览：显示会选中多少条、预计占用、列表来源', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { json: PREVIEW },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));

    await fillUrl('https://space.bilibili.com/12345');
    await click(button('先预览会选哪些'));
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
    const selects = document.querySelectorAll('select');
    await act(async () => {
      // 0 选取方式 / 1 存放方式 / 2 格式
      const storage = selects[1] as HTMLSelectElement;
      storage.value = 'archive';
      storage.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await click(button('建批量任务'));
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
    await until(() => text().includes('看条目'));
    await click(button('看条目'));
    await until(() => text().includes('第一条作品'));

    expect(text()).toContain('已入库');
    expect(text()).toContain('失败');
    expect(text()).toContain('平台拒绝');
    expect(text()).toContain('媒体库 #9');
  });

  test('抖音被挡时显示服务端给的原因，不粉饰成网络问题', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/creators/preview': { status: 400, json: { detail: '拿不到作品列表：平台拒绝了这次请求（403）', code: 'creator_input' } },
    }));
    mount('/creators');
    await until(() => text().includes('还没有批量任务'));
    await fillUrl('抖音号');
    await click(button('先预览会选哪些'));
    await until(() => text().includes('平台拒绝了这次请求'));
    expect(text()).not.toContain('网络不稳定');
  });
});
