/** 5d：服务器提取。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  button, calls, callKeys, choose, cleanup, click, mount, ownerRoutes, server, text, until,
} from './harness';
import { act } from 'preact/test-utils';

const JOBS = 'GET /v2/extraction-jobs?limit=50';

function job(over: Record<string, unknown> = {}) {
  return {
    id: 7, status: 'done', media_type: 'video', storage: 'metadata',
    input: 'https://www.bilibili.com/video/BV1xx411c7mD', platform: 'bilibili',
    video_id: 'BV1xx411c7mD', track_id: 12, error_code: null, error_detail: null,
    created_at: 1_800_000_000, started_at: 1_800_000_001, finished_at: 1_800_000_009,
    ...over,
  };
}

async function type(el: Element, value: string) {
  await act(async () => {
    (el as HTMLTextAreaElement).value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('服务器提取', () => {
  test('只解析：提示"还没入库"，并显示标题/UP 主/预估大小', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [] } },
      'POST /v2/resolve': {
        json: {
          platform: 'bilibili', video_id: 'BV1xx411c7mD', title: '一个测试视频',
          duration_sec: 204, uploader: 'UP 主', cover_url: null, source_url: 'https://example.invalid/v',
          estimated_bytes: 12 * 1024 * 1024,
        },
      },
    }));
    mount('/extract');
    await until(() => text().includes('还没有提取任务'));

    await type(document.querySelector('textarea')!, 'https://www.bilibili.com/video/BV1xx411c7mD');
    await click(button('只解析，不入库'));
    await until(() => text().includes('一个测试视频'));

    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      text: 'https://www.bilibili.com/video/BV1xx411c7mD', media_type: 'video',
    });
    expect(text()).toContain('UP 主');
    expect(text()).toContain('3:24');
    expect(text()).toContain('约 12 MB');
    expect(text()).toContain('还没有入库');
  });

  test('提取：带上格式与存放方式，任务出现在列表里', async () => {
    let round = 0;
    server(ownerRoutes({
      [JOBS]: () => (round++ === 0 ? { json: { jobs: [] } } : { json: { jobs: [job()] } }),
      'POST /v2/extract': { json: { id: 7, status: 'queued' } },
    }));
    mount('/extract');
    await until(() => text().includes('还没有提取任务'));

    await type(document.querySelector('textarea')!, '  BV1xx411c7mD  ');
    await choose('存放方式', '服务器存储');
    await click(button('提取并入库'));
    await until(() => text().includes('已入库'));

    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.path).toBe('/v2/extract');
    expect(post.body).toEqual({ input: 'BV1xx411c7mD', media_type: 'video', storage: 'archive' });
    expect(text()).toContain('#7');
    expect(text()).toContain('媒体库 #12');
  });

  test('任务失败时把服务端给的原因原样显示', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'failed', track_id: null, error_detail: '平台拒绝了这次请求（403）' })] } },
    }));
    mount('/extract');
    await until(() => text().includes('平台拒绝了这次请求'));
    expect(text()).toContain('失败');
    // 页面明确写了抖音在这台机器上的预期
    expect(text()).toContain('抖音');
    expect(text()).toContain('403');
  });

  test('空输入不发请求', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/extract');
    await until(() => text().includes('还没有提取任务'));
    await click(button('提取并入库'));
    await until(() => text().includes('先把作品链接'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});
