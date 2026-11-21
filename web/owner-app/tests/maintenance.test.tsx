/** 5e：库维护。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { button, calls, cleanup, click, mount, ownerRoutes, server, text, until } from './harness';

const STORAGE = {
  json: {
    by_storage: {
      archive: { tracks: 3, bytes: 300 * 1024 * 1024 },
      cache: { tracks: 2, bytes: 100 * 1024 * 1024 },
      metadata: { tracks: 4, bytes: 0 },
    },
    ai: { files: 0, bytes: 0 },
    quota: { archive: 20 * 1024 ** 3, cache: 5 * 1024 ** 3 },
    used: { archive: 300 * 1024 * 1024, cache: 1024 ** 3 },
  },
};

const REPORTS = {
  json: {
    reports: [
      { id: 9, platform: 'bilibili', video_id: 'BV1xx411c7mD', track_id: 12, stage: 'download',
        error_code: 'upstream_403', detail: '平台拒绝了这次请求', client_version: '0.1.0', created_at: 1_800_000_000 },
    ],
  },
};

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('库维护', () => {
  test('占用：归档/缓存 对配额，按存放方式列出条目', async () => {
    server(ownerRoutes({
      'GET /v2/maintenance/storage': STORAGE,
      'GET /v2/diagnostics/extractions?limit=50': REPORTS,
    }));
    mount('/maintenance');
    await until(() => text().includes('300 MB'));

    expect(text()).toContain('/ 20 GB');
    expect(text()).toContain('1.0 GB');
    expect(text()).toContain('归档');
    expect(text()).toContain('缓存');
    expect(text()).toContain('只记清单');
    // 百分比是本地算的
    expect(text()).toContain('% 已用');
  });

  test('清理缓存：确认后提交并显示释放量，然后刷新占用', async () => {
    let round = 0;
    server(ownerRoutes({
      'GET /v2/maintenance/storage': () => (round++ === 0 ? STORAGE
        : { json: { ...STORAGE.json, used: { archive: 300 * 1024 * 1024, cache: 0 } } }),
      'GET /v2/diagnostics/extractions?limit=50': REPORTS,
      'POST /v2/maintenance/prune-cache': { json: { rows: 2, bytes: 1024 ** 3 } },
    }));
    mount('/maintenance');
    await until(() => text().includes('清理过期缓存'));

    vi.stubGlobal('confirm', () => false);
    await click(button('清理过期缓存'));
    expect(calls.some((c) => c.path === '/v2/maintenance/prune-cache')).toBe(false);

    vi.stubGlobal('confirm', () => true);
    await click(button('清理过期缓存'));
    await until(() => text().includes('2 条记录'));

    expect(text()).toContain('释放 1.0 GB');
    expect(calls.filter((c) => c.path === '/v2/maintenance/storage').length).toBe(2);
  });

  test('孤儿文件：显示删除个数', async () => {
    server(ownerRoutes({
      'GET /v2/maintenance/storage': STORAGE,
      'GET /v2/diagnostics/extractions?limit=50': REPORTS,
      'POST /v2/maintenance/orphans': { json: { removed: 3 } },
    }));
    vi.stubGlobal('confirm', () => true);
    mount('/maintenance');
    await until(() => text().includes('清理孤儿文件'));
    await click(button('清理孤儿文件'));
    await until(() => text().includes('删除 3 个'));
    // 说明里写清楚这是全库操作
    expect(text()).toContain('全库');
  });

  test('失败上报：列出阶段、错误码与说明；空时说清楚', async () => {
    server(ownerRoutes({
      'GET /v2/maintenance/storage': STORAGE,
      'GET /v2/diagnostics/extractions?limit=50': REPORTS,
    }));
    mount('/maintenance');
    await until(() => text().includes('平台拒绝了这次请求'));
    expect(text()).toContain('upstream_403');
    expect(text()).toContain('媒体库 #12');
    expect(text()).toContain('阶段：download');
    cleanup();

    server(ownerRoutes({
      'GET /v2/maintenance/storage': STORAGE,
      'GET /v2/diagnostics/extractions?limit=50': { json: { reports: [] } },
    }));
    mount('/maintenance');
    await until(() => text().includes('没有失败上报'));
  });

  test('读取失败：给原因与重试', async () => {
    let round = 0;
    server(ownerRoutes({
      'GET /v2/maintenance/storage': () => (round++ === 0
        ? { status: 500, json: { detail: '统计失败', code: 'storage_error' } }
        : STORAGE),
      'GET /v2/diagnostics/extractions?limit=50': REPORTS,
    }));
    mount('/maintenance');
    await until(() => text().includes('统计失败'));
    await click(button('重试'));
    await until(() => text().includes('300 MB'));
  });

  test('AI 文件：单列一行、归档里注明含 AI，清理走 gc 且需确认', async () => {
    const withAi = {
      json: {
        ...STORAGE.json,
        ai: { files: 2, bytes: 200 * 1024 * 1024 },
        used: { archive: 500 * 1024 * 1024, cache: 1024 ** 3 },
      },
    };
    server(ownerRoutes({
      'GET /v2/maintenance/storage': withAi,
      'GET /v2/diagnostics/extractions?limit=50': { json: { reports: [] } },
      'POST /v2/recreations/gc': { json: { projects: 2, freed_bytes: 200 * 1024 * 1024, days: 14, dry_run: false } },
    }));
    mount('/maintenance');
    await until(() => text().includes('AI 素材与产物（计入归档）'));

    expect(text()).toContain('含 AI 200 MB');
    expect(text()).toContain('默认 14 天');

    vi.stubGlobal('confirm', () => false);
    await click(button('清理过期的 AI 文件'));
    expect(calls.some((c) => c.path === '/v2/recreations/gc')).toBe(false);

    vi.stubGlobal('confirm', () => true);
    await click(button('清理过期的 AI 文件'));
    await until(() => text().includes('清理完成：2 个项目'));
    expect(text()).toContain('释放 200 MB');
    expect(calls.find((c) => c.path === '/v2/recreations/gc')?.method).toBe('POST');
  });
});
