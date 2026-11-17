/** 5f：AI 视频重建。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act } from 'preact/test-utils';
import { button, calls, cleanup, click, mount, ownerRoutes, server, text, until } from './harness';

const CAPS_ON = { json: { available: true, modes: ['analyze', 'generate'], max_upload_bytes: 200 * 1024 ** 2, reason: null } };
const CAPS_OFF = { json: { available: false, modes: ['analyze', 'generate'], max_upload_bytes: 200 * 1024 ** 2, reason: '服务端未配置模型，AI 重建暂不可用' } };

const TRACK = {
  id: 12, media_type: 'video', platform: 'bilibili', video_id: 'BV1', page: 1,
  title: '一个视频', uploader: 'UP', duration_sec: 204, cover_url: null, source_url: 'x',
  storage: 'archive', bytes: 10 * 1024 ** 2, pinned: false, deleted: false, updated_seq: 1,
  created_at: 1_800_000_000, updated_at: 1_800_000_000, cache_expires_at: null, has_file: true,
};

function proj(over: Record<string, unknown> = {}) {
  return {
    id: 4, title: '一个视频', source_track_id: 12, source_path: 'ai/4/source.mp4',
    description: '', status: 'draft', created_at: 1_800_000_000, updated_at: 1_800_000_000,
    ...over,
  };
}

const TRACKS = 'GET /v2/tracks?since_seq=0&limit=500';

function detail(over: Record<string, unknown> = {}) {
  return { json: { ...proj(), runs: [], ...over } };
}

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('AI 视频重建', () => {
  test('未开通：说明原因，新建与生成全部禁用（不给点了才报错的按钮）', async () => {
    server(ownerRoutes({
      [TRACKS]: { json: { items: [], count: 0 } },
      'GET /v2/recreations/capabilities': CAPS_OFF,
      'GET /v2/recreations': { json: { recreations: [] } },
    }));
    mount('/ai');
    await until(() => text().includes('AI 重建当前不可用'));

    expect(text()).toContain('服务端未配置模型');
    expect(text()).toContain('GETMEDIA_AI_ENABLED');
    expect(button('用这个视频建项目').disabled).toBe(true);
    const file = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(file.disabled).toBe(true);
    expect(text()).toContain('服务端开通 AI 后才能新建');
  });

  test('已开通：用媒体库里的视频建项目，随后打开详情', async () => {
    server(ownerRoutes({
      [TRACKS]: { json: { items: [TRACK, { ...TRACK, id: 13, media_type: 'audio', has_file: true }], count: 2 } },
      'GET /v2/recreations/capabilities': CAPS_ON,
      'GET /v2/recreations': { json: { recreations: [] } },
      'POST /v2/recreations/from-track/12': { json: proj() },
      'GET /v2/recreations/4': detail(),
    }));
    mount('/ai');
    await until(() => document.querySelectorAll('select option').length > 1);

    // 只列"视频且服务器上有文件"的条目：音频不在选项里
    const opts = Array.from(document.querySelectorAll('select option')).map((o) => o.textContent ?? '');
    expect(opts.some((t) => t.includes('#12'))).toBe(true);
    expect(opts.some((t) => t.includes('#13'))).toBe(false);

    const sel = document.querySelector('select') as HTMLSelectElement;
    await act(async () => {
      sel.value = '12';
      sel.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await click(button('用这个视频建项目'));
    await until(() => text().includes('已用媒体库 #12 建项目 #4'));

    expect(calls.some((c) => c.path === '/v2/recreations/from-track/12')).toBe(true);
    expect(text()).toContain('源视频');
  });

  test('描述：保存后 PUT 只带 description；没描述时生成按钮禁用', async () => {
    server(ownerRoutes({
      [TRACKS]: { json: { items: [], count: 0 } },
      'GET /v2/recreations/capabilities': CAPS_ON,
      'GET /v2/recreations': { json: { recreations: [proj()] } },
      'GET /v2/recreations/4': detail(),
      'PUT /v2/recreations/4/description': detail({ description: '做成赛博风格' }),
    }));
    mount('/ai');
    await until(() => text().includes('一个视频'));
    await click(button('打开'));
    await until(() => text().includes('源视频'));

    expect(button('生成视频').disabled).toBe(true);   // 还没有描述
    const ta = document.querySelector('textarea') as HTMLTextAreaElement;
    await act(async () => {
      ta.value = '做成赛博风格';
      ta.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await click(button('保存描述'));
    await until(() => calls.some((c) => c.method === 'PUT'));

    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.path).toBe('/v2/recreations/4/description');
    expect(put.body).toEqual({ description: '做成赛博风格' });
  });

  test('入队分析后轮询，进行中的任务可取消，完成的生成任务给播放器', async () => {
    let round = 0;
    server(ownerRoutes({
      [TRACKS]: { json: { items: [], count: 0 } },
      'GET /v2/recreations/capabilities': CAPS_ON,
      'GET /v2/recreations': { json: { recreations: [proj({ description: '描述', status: 'queued' })] } },
      'GET /v2/recreations/4': () => detail({
        description: '描述',
        status: 'running',
        runs: round++ === 0
          ? [{ id: 7, kind: 'analyze', status: 'running', progress: 40, error_code: null, created_at: 1_800_000_000, started_at: 1_800_000_001, finished_at: null }]
          : [{ id: 8, kind: 'generate', status: 'done', progress: 100, error_code: null, created_at: 1_800_000_000, started_at: 1_800_000_001, finished_at: 1_800_000_500 }],
      }),
      'POST /v2/recreations/4/analyze': { json: { recreation_id: 4, run_id: 7, kind: 'analyze', status: 'queued' } },
      'POST /v2/recreations/4/runs/7/cancel': { json: { run_id: 7, status: 'cancelled' } },
      'POST /v2/recreations/4/runs/8/save': { json: { recreation_id: 4, run_id: 8, saved: true, bytes: 5 * 1024 ** 2 } },
    }));
    mount('/ai');
    await until(() => text().includes('一个视频'));
    await click(button('打开'));
    await until(() => text().includes('分析画面'));

    expect(text()).toContain('进行中');
    expect(text()).toContain('40%');
    await click(button('取消'));
    await until(() => calls.some((c) => c.path === '/v2/recreations/4/runs/7/cancel'));

    // 再打开一次：换成"生成完成"的那次
    await click(button('刷新'));
    await until(() => text().includes('产物'));
    const video = Array.from(document.querySelectorAll('video')).find((v) => v.getAttribute('src')?.includes('/runs/8/video'));
    expect(video).toBeTruthy();

    await click(button('标记已保存'));
    await until(() => text().includes('已标记保存'));
  });

  test('入队失败（未开通）时显示服务端原因，不吞掉', async () => {
    server(ownerRoutes({
      [TRACKS]: { json: { items: [], count: 0 } },
      'GET /v2/recreations/capabilities': CAPS_ON,
      'GET /v2/recreations': { json: { recreations: [proj({ description: '描述' })] } },
      'GET /v2/recreations/4': detail({ description: '描述' }),
      'POST /v2/recreations/4/generate': { status: 503, json: { detail: '服务端未配置模型，AI 重建暂不可用', code: 'ai_unconfigured' } },
    }));
    mount('/ai');
    await until(() => text().includes('一个视频'));
    await click(button('打开'));
    await until(() => text().includes('生成视频'));
    await click(button('生成视频'));
    await until(() => text().includes('未配置模型'));
    expect(text()).not.toContain('已加入生成队列');
  });
});
