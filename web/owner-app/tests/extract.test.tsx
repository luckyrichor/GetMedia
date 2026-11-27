/** 5d：单个提取（原「服务器提取」）。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  button, calls, choose, cleanup, click, mount, ownerRoutes, server, text, until,
} from './harness';
import { act } from 'preact/test-utils';

const JOBS = 'GET /v2/extraction-jobs?limit=50';

function job(over: Record<string, unknown> = {}) {
  return {
    id: 7, status: 'done', media_type: 'video', storage: 'metadata',
    input: 'https://www.bilibili.com/video/BV1xx411c7mD', platform: 'bilibili',
    video_id: 'BV1xx411c7mD', track_id: 12, error_code: null, error_detail: null,
    title: '一个测试视频', uploader: 'UP 主', bytes: 0, formats: ['source'],
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

/** 表格里的作品标题（按出现顺序）。 */
function titles(): string[] {
  return Array.from(document.querySelectorAll('.ex-table .t-main')).map((el) => el.textContent ?? '');
}

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('单个提取', () => {
  test('只解析：显示标题/UP 主/时长/预估大小，并说明还没入库', async () => {
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

  test('提取：带上存放方式与输出格式，任务落进下方表格', async () => {
    let round = 0;
    server(ownerRoutes({
      [JOBS]: () => (round++ === 0
        ? { json: { jobs: [] } }
        : { json: { jobs: [job({ storage: 'archive', formats: ['mp4'], bytes: 2048 })] } }),
      'POST /v2/extract': { json: { id: 7, status: 'queued' } },
    }));
    mount('/extract');
    await until(() => text().includes('还没有提取任务'));

    await type(document.querySelector('textarea')!, '  BV1xx411c7mD  ');
    await choose('存放方式', '服务器存储');
    await choose('输出格式', '.mp4');
    await click(button('开始提取'));
    await until(() => text().includes('已入库'));

    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.path).toBe('/v2/extract');
    expect(post.body).toEqual({
      input: 'BV1xx411c7mD', media_type: 'video', storage: 'archive', formats: ['mp4'],
    });
    // 表格里能看到是谁的作品、存了什么
    expect(titles()).toEqual(['一个测试视频']);
    expect(text()).toContain('UP 主');
    expect(text()).toContain('视频 · .mp4');
  });

  test('切到音频时输出格式跟着换成音频那一类，不会留下无效组合', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } }, 'POST /v2/extract': { json: { id: 8, status: 'queued' } } }));
    mount('/extract');
    await until(() => text().includes('还没有提取任务'));

    await type(document.querySelector('textarea')!, 'BV1xx411c7mD');
    await choose('格式', '音频');
    await click(button('开始提取'));
    await until(() => calls.some((c) => c.method === 'POST'));

    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      input: 'BV1xx411c7mD', media_type: 'audio', storage: 'metadata', formats: ['m4a'],
    });
  });

  test('失败的任务把服务端给的原因原样显示，并给一个重试', async () => {
    server(ownerRoutes({
      [JOBS]: { json: { jobs: [job({ status: 'failed', track_id: null, error_detail: '平台拒绝了这次请求（403）' })] } },
      'POST /v2/extract': { json: { id: 9, status: 'queued' } },
    }));
    mount('/extract');
    await until(() => text().includes('平台拒绝了这次请求'));
    expect(text()).toContain('失败');

    await click(button('重试'));
    await until(() => calls.some((c) => c.method === 'POST'));
    // 重试用的是这条任务当时的链接与设置
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      input: 'https://www.bilibili.com/video/BV1xx411c7mD',
      media_type: 'video', storage: 'metadata', formats: ['source'],
    });
  });

  test('空输入不发请求', async () => {
    server(ownerRoutes({ [JOBS]: { json: { jobs: [] } } }));
    mount('/extract');
    await until(() => text().includes('还没有提取任务'));
    await click(button('开始提取'));
    await until(() => text().includes('先把作品链接'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  test('按昵称、平台、存放方式筛选，重置后全部回来', async () => {
    server(ownerRoutes({
      [JOBS]: {
        json: {
          jobs: [
            job({ id: 1, title: '甲作品', uploader: '张三' }),
            job({ id: 2, title: '乙作品', uploader: '李四', platform: 'douyin' }),
            job({ id: 3, title: '丙作品', uploader: '张三', storage: 'archive', bytes: 2048 }),
          ],
        },
      },
    }));
    mount('/extract');
    await until(() => titles().length === 3);

    await type(document.querySelector('input[aria-label="搜索昵称"]')!, '张三');
    await until(() => titles().length === 2);
    expect(titles()).toEqual(['甲作品', '丙作品']);

    await type(document.querySelector('input[aria-label="搜索昵称"]')!, '');
    await choose('筛选：平台', '抖音');
    await until(() => titles().length === 1);
    expect(titles()).toEqual(['乙作品']);

    // 昵称 + 平台 + 存放方式之间是「且」：抖音 + 张三 一条都没有
    await type(document.querySelector('input[aria-label="搜索昵称"]')!, '张三');
    await until(() => text().includes('没有符合条件的任务'));

    await click(button('重置筛选'));
    await until(() => titles().length === 3);
  });

  test('只有真的存了文件的才给「下载」', async () => {
    server(ownerRoutes({
      [JOBS]: {
        json: {
          jobs: [
            job({ id: 1, storage: 'archive', track_id: 12, bytes: 2048, title: '有文件' }),
            job({ id: 2, storage: 'metadata', track_id: 13, bytes: 0, title: '只记清单' }),
          ],
        },
      },
    }));
    mount('/extract');
    await until(() => titles().length === 2);

    const links = Array.from(document.querySelectorAll('.ex-table a.text-btn'));
    expect(links).toHaveLength(1);
    expect(links[0]!.getAttribute('href')).toContain('/v2/media/12');
  });
});
