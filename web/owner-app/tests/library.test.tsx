/** 5c：媒体库与播放（行内按钮 + 序号、双击改名、多选批量、筛选排序）。 */
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

/** 自绘下拉：先点开触发器，再点选项（原生 select 的弹出层会盖住控件，页面已不用它）。 */
async function chooseSelect(ariaLabel: string, label: string) {
  const trigger = document.querySelector(`button[aria-label="${ariaLabel}"]`) as HTMLElement;
  if (!trigger) throw new Error(`找不到下拉：${ariaLabel}`);
  await click(trigger);
  const opt = Array.from(document.querySelectorAll('.select-opt'))
    .find((o) => (o.textContent || '').trim() === label);
  if (!opt) throw new Error(`下拉 ${ariaLabel} 里没有「${label}」`);
  await click(opt);
}

/** 排序：点右侧箭头展开四位字段，再点其中一个（左半只翻方向，不给选项）。 */
async function chooseSort(label: string) {
  await click(document.querySelector('button[aria-label="选择排序依据"]') as HTMLElement);
  const opt = Array.from(document.querySelectorAll('.sort-menu .select-opt'))
    .find((o) => (o.querySelector('.select-label')?.textContent || '').trim() === label);
  if (!opt) throw new Error(`排序里没有「${label}」`);
  await click(opt);
}

/** 排序控件左半：显示当前字段与方向，点一下翻转正倒序。 */
const sortFlip = () => document.querySelector('.sort-split .sort-field') as HTMLElement;

/** 批量条上的按钮：行内也有「删除」，所以不能直接用 button('删除')。 */
function batchButton(label: string): HTMLButtonElement {
  const b = Array.from(document.querySelectorAll('.selectbar button'))
    .find((x) => (x.textContent || '').trim() === label);
  if (!b) throw new Error(`批量条上没有「${label}」`);
  return b as HTMLButtonElement;
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

    // 平台：手机导入只剩那一条
    await chooseSelect('按平台筛选', '手机导入');
    await until(() => titles().length === 1);
    expect(titles()).toEqual(['长的那条']);

    await chooseSelect('按平台筛选', '全部平台');
    await until(() => titles().length === 3);

    // 排序：换字段时回到该字段的默认方向（时长默认长→短）
    await chooseSort('时长');
    await until(() => titles()[0] === '长的那条');
    expect(titles()).toEqual(['长的那条', '中间那条', '曲目 1']);

    // 左半点一下 = 反序，再点一下 = 转回来
    expect(sortFlip().textContent).toContain('时长');
    await click(sortFlip());
    await until(() => titles()[0] === '曲目 1');
    expect(titles()).toEqual(['曲目 1', '中间那条', '长的那条']);
    await click(sortFlip());
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
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
      return Promise.resolve();
    });
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

    // 没勾选时批量条上没有任何动作（行内的「删除」只作用于这一条）
    expect(document.querySelector('.selectbar button')).toBeNull();

    const boxes = Array.from(document.querySelectorAll('.track-pick input'));
    await check(boxes[0]!);
    await until(() => text().includes('已选 1 项'));

    vi.stubGlobal('confirm', (msg: string) => {
      // 只让用户确认"删不删"，不解释服务端怎么做
      expect(msg).toBe('确定删除选中的 1 项吗？');
      return false;
    });
    await click(batchButton('删除'));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    vi.stubGlobal('confirm', () => true);
    await click(batchButton('删除'));
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
    await click(batchButton('删除'));
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
    await click(batchButton('批量下载'));
    await until(() => clicked.length === 1);
    expect(clicked[0]).toMatch(/\/api\/v2\/media\/1\?download=1$/);
  });

  test('行内按钮：播放、下载、删除三个，播放点了就直接出声', async () => {
    const played: string[] = [];
    const paused: string[] = [];
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
      played.push(this.getAttribute('src') || '');
      return Promise.resolve();
    });
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(function (this: HTMLMediaElement) {
      paused.push(this.getAttribute('src') || '');
    });
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1)], count: 1 } },
      'DELETE /v2/tracks/1': { json: { ok: true, id: 1, deleted: true } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    const actions = document.querySelector('.track-actions')!;
    expect(actions).not.toBeNull();
    // 下载是真的链接（带 ?download=1），不是按钮
    expect(actions.querySelector('a')!.getAttribute('href')).toBe('/api/v2/media/1?download=1');
    // 「删文件」已经并进「删除」，一行只剩播放和删除两个按钮
    expect(Array.from(actions.querySelectorAll('button')).map((b) => b.textContent?.trim()))
      .toEqual(['播放', '删除']);

    // 播放：点一下展开并直接开始播，不用再点播放器自带的播放键
    await click(Array.from(actions.querySelectorAll('button')).find((b) => b.textContent?.trim() === '播放')!);
    await until(() => played.length === 1);
    expect(played[0]).toBe('/api/v2/media/1');
    expect(document.querySelector('audio')).not.toBeNull();

    // 再点一下变暂停，但这一条仍然展开着
    await click(Array.from(actions.querySelectorAll('button')).find((b) => b.textContent?.trim() === '暂停')!);
    expect(paused).toEqual(['/api/v2/media/1']);
    expect(document.querySelector('audio')).not.toBeNull();

    // 删除：只问一次"确定删除吗"，删完从列表消失
    let asked = '';
    vi.stubGlobal('confirm', (msg: string) => { asked = msg; return true; });
    await click(Array.from(document.querySelectorAll('.track-actions button'))
      .find((b) => b.textContent?.trim() === '删除')!);
    expect(asked).toBe('确定删除《曲目 1》吗？');
    await until(() => text().includes('媒体库里还没有内容'));
    expect(callKeys()).toContain('DELETE /v2/tracks/1');
    expect(callKeys()).not.toContain('DELETE /v2/tracks/1/file');
  });

  test('筛选器分两行排、底部说明已去掉', async () => {
    server(ownerRoutes({ [LIST]: { json: { items: [track(1)], count: 1 } } }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    // 第一行：搜索 + 平台 + 排序；第二行：起止日期 + 只看有文件
    const rows = Array.from(document.querySelectorAll('.filter-row'));
    expect(rows).toHaveLength(2);
    expect(rows[0]!.querySelector('input[type="search"]')).not.toBeNull();
    // 平台是自绘下拉，排序是自己的拆分控件（左翻方向 + 右选字段）
    expect(rows[0]!.querySelectorAll('.select')).toHaveLength(1);
    expect(rows[0]!.querySelector('.sort-split')).not.toBeNull();
    expect(rows[1]!.querySelectorAll('input[type="date"]')).toHaveLength(2);

    // 那段"单击一行播放，双击标题改名…"的说明文字不再显示
    expect(text()).not.toContain('单击一行播放');
    expect(text()).not.toContain('列表最多取 10000 条');
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
