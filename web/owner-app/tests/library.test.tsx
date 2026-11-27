/** 5c：媒体库与播放（行内无按钮版：序号、双击改名、多选批量、筛选排序）。 */
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

async function chooseSelect(ariaLabel: string, value: string) {
  const el = document.querySelector(`select[aria-label="${ariaLabel}"]`) as HTMLSelectElement;
  await act(async () => {
    el.value = value;
    el.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

async function check(el: Element, on = true) {
  await act(async () => {
    const input = el as HTMLInputElement;
    input.checked = on;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

async function dblclick(el: Element) {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
  });
}

const titles = () => Array.from(document.querySelectorAll('.track-title')).map((e) => e.textContent);

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('媒体库与播放', () => {
  test('列表：只留音频/视频两个类型按钮，给序号与总数', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1), track(2, { media_type: 'video', storage: 'cache' })], count: 2 } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    // 顶部统计说明与「全部」筛选都已去掉，只剩两个类型按钮和一行总数
    expect(document.querySelector('.lead')).toBeNull();
    expect(text()).not.toContain('全部2');
    expect(text()).toContain('音频1');
    expect(text()).toContain('视频1');
    expect(text()).toContain('共 2 条');
    expect(text()).toContain('存储');

    // 默认看音频：视频那条不在列表里；序号从 1 起
    expect(titles()).toEqual(['曲目 1']);
    expect(document.querySelector('.track-no')!.textContent).toBe('1');

    await click(button('视频1'));
    await until(() => text().includes('曲目 2'));
    expect(titles()).toEqual(['曲目 2']);
    expect(text()).toContain('缓存');
  });

  test('筛选与排序：搜索、平台、时间范围、时长排序都在本地算，不打接口', async () => {
    server(ownerRoutes({
      [LIST]: {
        json: {
          items: [
            track(1, { duration_sec: 60 }),
            track(2, { duration_sec: 900, title: '长的那条', platform: 'local', source_url: '' }),
            track(3, { duration_sec: 300, title: '中间那条' }),
          ],
          count: 3,
        },
      },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));
    const before = calls.length;

    // 搜索：只留命中的
    await typeInto(document.querySelector('input[type="search"]')!, '中间');
    await until(() => titles().length === 1);
    expect(titles()).toEqual(['中间那条']);

    await typeInto(document.querySelector('input[type="search"]')!, '');
    await until(() => titles().length === 3);

    // 平台：本机导入只剩那一条
    await chooseSelect('按平台筛选', 'local');
    await until(() => titles().length === 1);
    expect(titles()).toEqual(['长的那条']);

    await chooseSelect('按平台筛选', 'all');
    await until(() => titles().length === 3);

    // 时长：长→短
    await chooseSelect('排序方式', 'duration_desc');
    await until(() => titles()[0] === '长的那条');
    expect(titles()).toEqual(['长的那条', '中间那条', '曲目 1']);

    // 时间范围：一条都不在这个区间里
    await act(async () => {
      const from = document.querySelector('input[aria-label="起始日期"]') as HTMLInputElement;
      from.value = '2030-01-01';
      from.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await until(() => titles().length === 0);
    expect(text()).toContain('没有匹配的条目');

    // 过滤是本地的：没有新增请求
    expect(calls.length).toBe(before);
  });

  test('播放：单击一行展开播放条，再点一下收起', async () => {
    server(ownerRoutes({ [LIST]: { json: { items: [track(1)], count: 1 } } }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    await click(document.querySelector('.track-main') as Element);
    // 双击判定窗口过了才播
    await until(() => document.querySelector('audio') !== null);
    expect(document.querySelector('audio')!.getAttribute('src')).toBe('/api/v2/media/1');
    // 播放条在这一行内部，不在列表顶部
    const row = Array.from(document.querySelectorAll('.track')).find((li) => li.querySelector('.player-inline'));
    expect(row!.querySelector('.track-title')!.textContent).toBe('曲目 1');
    expect(document.querySelector('.tracks > .player-inline')).toBeNull();

    await click(document.querySelector('.track-main') as Element);
    await until(() => document.querySelector('audio') === null);
  });

  test('改名：双击标题就地编辑，PATCH 只带 title', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1)], count: 1 } },
      'PATCH /v2/tracks/1': { json: track(1, { title: '新的标题' }) },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    await dblclick(document.querySelector('.track-title') as Element);
    const input = document.querySelector('input[aria-label="新标题"]') as HTMLInputElement;
    expect(input.value).toBe('曲目 1');
    await typeInto(input, '  新的标题  ');
    await act(async () => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    });
    await until(() => text().includes('新的标题'));

    const patches = calls.filter((c) => c.method === 'PATCH');
    expect(patches).toHaveLength(1);
    expect(patches[0]!.path).toBe('/v2/tracks/1');
    expect(patches[0]!.body).toEqual({ title: '新的标题' }); // 首尾空格已去掉
    // 改名不该顺手把它播起来
    expect(document.querySelector('audio')).toBeNull();
  });

  test('删除：勾选后只有一次确认，确认后从列表消失', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1), track(2)], count: 2 } },
      'DELETE /v2/tracks/1': { json: { ok: true, id: 1, deleted: true } },
      'DELETE /v2/tracks/2': { json: { ok: true, id: 2, deleted: true } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    // 没勾选时没有删除入口（行内已不放按钮）
    expect(() => button('删除')).toThrow();

    const boxes = Array.from(document.querySelectorAll('.track-pick input'));
    await check(boxes[0]!);
    await until(() => text().includes('已选 1 项'));

    vi.stubGlobal('confirm', (msg: string) => {
      // 只让用户确认"删不删"，不解释服务端怎么做
      expect(msg).toBe('确定删除选中的 1 项吗？');
      return false;
    });
    await click(button('删除'));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    vi.stubGlobal('confirm', () => true);
    await click(button('删除'));
    // 列表按入库时间倒序：第一个勾选框对应曲目 2
    await until(() => !text().includes('曲目 2'));
    expect(text()).toContain('曲目 1');
    expect(callKeys()).toContain('DELETE /v2/tracks/2');
  });

  test('批量：全选当前列表后可以一次删多条', async () => {
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1), track(2), track(3)], count: 3 } },
      'DELETE /v2/tracks/1': { json: { ok: true, id: 1, deleted: true } },
      'DELETE /v2/tracks/2': { json: { ok: true, id: 2, deleted: true } },
      'DELETE /v2/tracks/3': { json: { ok: true, id: 3, deleted: true } },
    }));
    vi.stubGlobal('confirm', () => true);
    mount('/library');
    await until(() => text().includes('曲目 1'));

    await check(document.querySelector('input[aria-label="全选当前列表"]') as Element);
    await until(() => text().includes('已选 3 项'));
    await click(button('删除'));
    await until(() => text().includes('媒体库里还没有内容'));
    expect(callKeys().filter((k) => k.startsWith('DELETE')).length).toBe(3);
  });

  test('批量下载：只给有文件或来源链接的条目，逐个点下载链接', async () => {
    const clicked: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this.getAttribute('href') || '');
    });
    server(ownerRoutes({
      [LIST]: {
        json: {
          items: [track(1), track(2, { has_file: false, source_url: null, storage: 'metadata' })],
          count: 2,
        },
      },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    await check(document.querySelector('input[aria-label="全选当前列表"]') as Element);
    await until(() => text().includes('已选 2 项'));
    await click(button('批量下载'));
    await until(() => clicked.length === 1);
    expect(clicked[0]).toMatch(/\/api\/v2\/media\/1\?download=1$/);
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
});
