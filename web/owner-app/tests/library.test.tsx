/** 5c：媒体库与播放。 */
import { act } from 'preact/test-utils';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  button, calls, callKeys, cleanup, click, mount, ownerRoutes, server, text, until,
} from './harness';

function track(id: number, over: Record<string, unknown> = {}) {
  return {
    id,
    media_type: 'audio' as const,
    platform: 'bilibili',
    video_id: `BV000${id}`,
    page: 1,
    title: `曲目 ${id}`,
    uploader: '某个 UP 主',
    duration_sec: 204,
    cover_url: null,
    source_url: 'https://example.invalid/v',
    storage: 'archive' as const,
    bytes: 1024 * 1024,
    pinned: false,
    deleted: false,
    updated_seq: id,
    created_at: 1_800_000_000 + id,
    updated_at: 1_800_000_000 + id,
    cache_expires_at: null,
    has_file: true,
    ...over,
  };
}

const LIST = 'GET /v2/tracks?since_seq=0&limit=500';

async function typeInto(el: Element, value: string) {
  await act(async () => {
    (el as HTMLInputElement).value = value;
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

describe('媒体库与播放', () => {
  test('列表：按入库时间倒序，给类型计数与存储角标', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1), track(2, { media_type: 'video', storage: 'cache' })], count: 2 } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 2'));

    expect(text()).toContain('共 2 条（音频 1、视频 1）');
    expect(text()).toContain('存储');
    expect(text()).toContain('缓存');
    expect(text()).toContain('有文件 1.0 MB');
    // 新的在前：第 2 条（created_at 更大）排在前面
    const titles = Array.from(document.querySelectorAll('.track-title')).map((e) => e.textContent);
    expect(titles).toEqual(['曲目 2', '曲目 1']);
  });

  test('筛选与搜索：只在已取到的列表里过滤，不再打接口', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1), track(2, { media_type: 'video', title: '视频标题' })], count: 2 } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));
    const before = calls.length;

    await click(button('视频1'));
    await until(() => !text().includes('曲目 1'));
    expect(text()).toContain('视频标题');

    await click(button('全部2'));
    await until(() => text().includes('曲目 1'));
    await typeInto(document.querySelector('input[type="search"]')!, '视频');
    await until(() => !text().includes('曲目 1'));
    expect(text()).toContain('视频标题');
    // 过滤是本地的：没有新增请求
    expect(calls.length).toBe(before);
  });

  test('播放：音频取 /v2/media/{id}，切到视频换成 <video>', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1), track(2, { media_type: 'video' })], count: 2 } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 2'));

    // 第二行是曲目 1（音频）
    const rows = Array.from(document.querySelectorAll('.track'));
    await click(Array.from(rows[1]!.querySelectorAll('button')).find((b) => b.textContent === '播放')!);
    await until(() => document.querySelector('audio') !== null);
    expect(document.querySelector('audio')!.getAttribute('src')).toBe('/api/v2/media/1');
    expect(text()).toContain('支持拖动进度条');

    // 第一行是曲目 2（视频）
    await click(Array.from(document.querySelectorAll('.track-main'))[0]!);
    await until(() => document.querySelector('video') !== null);
    expect(document.querySelector('video')!.getAttribute('src')).toBe('/api/v2/media/2');
    expect(document.querySelector('audio')).toBeNull();
  });

  test('改名：PATCH 只带 title，成功后就地更新', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1)], count: 1 } },
      'PATCH /v2/tracks/1': { json: track(1, { title: '新的标题' }) },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    await click(button('改名'));
    const input = document.querySelector('input[aria-label="新标题"]') as HTMLInputElement;
    expect(input.value).toBe('曲目 1');
    await typeInto(input, '  新的标题  ');
    await click(button('保存'));
    await until(() => text().includes('新的标题'));

    const patch = calls.find((c) => c.method === 'PATCH')!;
    expect(patch.path).toBe('/v2/tracks/1');
    expect(patch.body).toEqual({ title: '新的标题' });   // 首尾空格已去掉
  });

  test('删文件：确认后才发请求；取消就什么都不做', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1)], count: 1 } },
      'DELETE /v2/tracks/1/file': { json: track(1, { storage: 'metadata', bytes: 0, has_file: false }) },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    vi.stubGlobal('confirm', () => false);
    await click(button('删文件'));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    vi.stubGlobal('confirm', () => true);
    await click(button('删文件'));
    await until(() => text().includes('只记清单'));
    expect(callKeys()).toContain('DELETE /v2/tracks/1/file');
    expect(text()).toContain('无文件');
  });

  test('删除记录：逻辑删除后从列表消失，播放器一起关掉', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1), track(2)], count: 2 } },
      'DELETE /v2/tracks/1': { json: { ok: true, id: 1, deleted: true } },
      'DELETE /v2/tracks/2': { json: { ok: true, id: 2, deleted: true } },
    }));
    vi.stubGlobal('confirm', () => true);
    mount('/library');
    await until(() => text().includes('曲目 1'));
    await click(Array.from(document.querySelectorAll('.track-main'))[1]!);   // 选中曲目 1
    await until(() => document.querySelector('audio') !== null);

    const rows = Array.from(document.querySelectorAll('.track'));
    await click(Array.from(rows[1]!.querySelectorAll('button')).find((b) => b.textContent === '删除记录')!);
    await until(() => !text().includes('曲目 1'));
    expect(text()).toContain('曲目 2');
    expect(document.querySelector('audio')).toBeNull();
  });

  test('空库与读取失败两种状态', async () => {
    server(ownerRoutes({ [LIST]: { json: { items: [], count: 0 } } }));
    mount('/library');
    await until(() => text().includes('媒体库里还没有内容'));
    expect(text()).toContain('服务器提取');
    cleanup();

    let n = 0;
    server(ownerRoutes({
      [LIST]: () => (n++ === 0
        ? { status: 502, json: { detail: '上游拒绝取流', code: 'media_upstream_failed' } }
        : { json: { items: [track(1)], count: 1 } }),
    }));
    mount('/library');
    await until(() => text().includes('上游拒绝取流'));
    await click(button('重试'));
    await until(() => text().includes('曲目 1'));
  });

  test('下载：行内与播放器都给下载链接，指向 ?download=1 且带 download 属性', async () => {
    server(ownerRoutes({ [LIST]: { json: { items: [track(1)], count: 1 } } }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    const rowLink = Array.from(document.querySelectorAll('.row-actions a')).find(
      (a) => a.textContent === '下载',
    ) as HTMLAnchorElement | undefined;
    expect(rowLink).toBeTruthy();
    expect(rowLink!.getAttribute('href')).toMatch(/\/api\/v2\/media\/1\?download=1$/);
    expect(rowLink!.hasAttribute('download')).toBe(true);

    await click(document.querySelector('.track-main') as Element);
    await until(() => document.querySelector('audio') !== null);
    const playerLink = Array.from(document.querySelectorAll('.player-head a')).find(
      (a) => a.textContent === '下载到本机',
    ) as HTMLAnchorElement | undefined;
    expect(playerLink?.getAttribute('href')).toMatch(/\/api\/v2\/media\/1\?download=1$/);
  });

  test('下载：没有文件也没有来源链接时按钮置灰，不给死链', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1, { storage: 'metadata', has_file: false, source_url: null })], count: 1 } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    const dl = Array.from(document.querySelectorAll('.row-actions button')).find(
      (b) => b.textContent === '下载',
    ) as HTMLButtonElement | undefined;
    expect(dl).toBeTruthy();
    expect(dl!.disabled).toBe(true);
    const link = Array.from(document.querySelectorAll('.row-actions a')).find(
      (a) => a.textContent === '下载',
    );
    expect(link).toBeUndefined();
  });
});
