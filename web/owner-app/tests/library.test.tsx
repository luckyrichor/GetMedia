/** 5c：媒体库（行内按钮 + 序号、双击改名、多选批量、筛选排序）。 */
import { act } from 'preact/test-utils';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { navigate } from '../src/router';
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

/** 会跟着 DELETE 变的列表桩：批量删除后会重新拉一次列表（对齐服务端），
 *  静态桩会把刚删掉的条目又吐回来，用例就永远等不到"列表空了"。
 *  这里按已发出的 DELETE 请求现算剩余条目，既保留了原有断言，也顺带验证了"删完真的重新取过列表"。 */
function liveList(items: ReturnType<typeof track>[]) {
  return () => {
    const gone = new Set(
      calls.filter((c) => c.method === 'DELETE').map((c) => Number(c.path.split('/').pop())),
    );
    const left = items.filter((t) => !gone.has(t.id));
    return { json: { items: left, count: left.length } };
  };
}

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

/** 页面内确认弹窗里的按钮（`.modal` 底下找文字对得上的那个）。 */
function dialogButton(label: string): HTMLButtonElement {
  const b = Array.from(document.querySelectorAll('.modal button'))
    .find((x) => (x.textContent || '').trim() === label);
  if (!b) {
    throw new Error(`弹窗上没有「${label}」（有：${
      Array.from(document.querySelectorAll('.modal button')).map((x) => x.textContent).join('|')}）`);
  }
  return b as HTMLButtonElement;
}

const dialogText = () => document.querySelector('.modal')?.textContent ?? '';

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

/** 在分页条右侧的「每页」下拉里选一项（`Select` 是自绘的，先点开再点选项）。 */
async function pickPageSize(label: string) {
  await click(document.querySelector('.pager .pager-size .select-trigger')!);
  const opt = Array.from(document.querySelectorAll('.pager .pager-size [role="option"]'))
    .find((o) => (o.textContent || '').trim() === label);
  if (!opt) throw new Error(`每页条数里没有「${label}」`);
  await click(opt);
}

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('媒体库', () => {
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

    // 平台：导入只剩那一条
    await chooseSelect('按平台筛选', '导入');
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

  test('播放：只有行内「播放」按钮能播，点内容区不播', async () => {
    const played: string[] = [];
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
      played.push(this.getAttribute('src') || '');
      return Promise.resolve();
    });
    server(ownerRoutes({ [LIST]: { json: { items: [track(1)], count: 1 } } }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    // 标题 + 元信息这一整块只是用来看的：点了不播，播放条也不出现
    await click(document.querySelector('.track-main') as Element);
    expect(played).toEqual([]);
    expect(document.querySelector('audio')).toBeNull();

    // 播放按钮：展开播放条并直接开始，不用再点播放器自带的播放键
    const playBtn = Array.from(document.querySelectorAll('.track-actions button'))
      .find((b) => b.textContent?.trim() === '播放')!;
    await click(playBtn);
    await until(() => document.querySelector('audio') !== null);
    expect(played).toEqual(['/api/v2/media/1']);
    expect(document.querySelector('audio')!.getAttribute('src')).toBe('/api/v2/media/1');

    // 播放条在这一行内部，不在列表顶部
    const row = Array.from(document.querySelectorAll('.track')).find((li) => li.querySelector('.player-inline'));
    expect(row!.querySelector('.track-title')!.textContent).toBe('曲目 1');
    expect(document.querySelector('.tracks > .player-inline')).toBeNull();
  });

  test('收起：点播放条上的「收起」停声并卸掉播放器，按钮回到「播放」', async () => {
    const played: string[] = [];
    const paused: string[] = [];
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
      played.push(this.getAttribute('src') || '');
      return Promise.resolve();
    });
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(function (this: HTMLMediaElement) {
      paused.push(this.getAttribute('src') || '');
    });
    server(ownerRoutes({ [LIST]: { json: { items: [track(1)], count: 1 } } }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    const actionBtn = (label: string) => Array.from(document.querySelectorAll('.track-actions button'))
      .find((b) => b.textContent?.trim() === label)!;

    await click(actionBtn('播放'));
    await until(() => document.querySelector('.player-inline') !== null);
    // 播放条顶上有「正在播放：标题」，收起按钮也在那里（不塞进行内按钮区）
    expect(document.querySelector('.player-now')!.textContent).toContain('曲目 1');
    // 「收起」在播放条上，不挤进行内按钮区（这条夹具没有文件、没有来源链接，所以没有「下载」）
    expect(Array.from(document.querySelectorAll('.track-actions button'))
      .map((b) => b.textContent?.trim())).toEqual(['暂停', '删除']);

    await click(Array.from(document.querySelectorAll('.player-head button'))
      .find((b) => b.textContent?.trim() === '收起')!);

    // 先停声，再把播放器卸掉；行内按钮回到「播放」，不停在「暂停」
    expect(paused).toEqual(['/api/v2/media/1']);
    expect(document.querySelector('.player-inline')).toBeNull();
    expect(actionBtn('播放')).toBeTruthy();

    // 收起后再点播放是重新开始，不是接着上次的进度
    await click(actionBtn('播放'));
    await until(() => document.querySelector('.player-inline') !== null);
    expect(played).toEqual(['/api/v2/media/1', '/api/v2/media/1']);
  });

  test('元信息：平台、时长、入库时间、文件状态四个标签，没文件写「仅元数据」', async () => {
    server(ownerRoutes({
      [LIST]: {
        json: {
          items: [
            track(1, { platform: 'douyin', uploader: '诗和远方つ.', duration_sec: 276, bytes: 3 * 1024 * 1024 }),
            track(2, { has_file: false, storage: 'metadata', bytes: 0 }),
            track(3, { storage: 'cache', bytes: 2 * 1024 * 1024 }),
          ],
          count: 3,
        },
      },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    const rowOf = (title: string) => Array.from(document.querySelectorAll('.track'))
      .find((li) => li.querySelector('.track-title')?.textContent === title)!;
    const tags = (title: string) => Array.from(rowOf(title).querySelectorAll('.meta-tag'))
      .map((e) => (e.textContent || '').trim());
    const tagClasses = (title: string) => Array.from(rowOf(title).querySelectorAll('.meta-tag'))
      .map((e) => Array.from(e.classList).filter((c) => c.startsWith('t-')).join(','));

    // 顺序：平台、时长、入库时间、文件状态（作者在标题那一行）
    const t1 = tags('曲目 1');
    expect(t1).toHaveLength(4);
    expect(t1.slice(0, 2)).toEqual(['抖音', '4:36']);
    expect(t1[2]).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    // 「存放方式」+「文件大小」并成一格：archive 说「已存储」
    expect(t1[3]).toBe('已存储 3.0 MB');
    expect(tagClasses('曲目 1')).toEqual(['t-plat', 't-dur', 't-time', 't-file']);

    // cache 不能说成「已存储」——它到期会被清掉
    expect(tags('曲目 3')[3]).toBe('已缓存 2.0 MB');

    // 没有文件：一句话说清，且带 none 类（虚线灰底，不抢眼）
    expect(tags('曲目 2')[3]).toBe('仅元数据');
    expect(rowOf('曲目 2').querySelector('.meta-tag.t-file')!.classList.contains('none')).toBe(true);

    // 作者在标题行，不在标签行
    expect(rowOf('曲目 1').querySelector('.track-author')!.textContent).toBe('诗和远方つ.');
    expect(tags('曲目 1')).not.toContain('诗和远方つ.');
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

  test('删除：勾选后弹页面内确认框，取消不删、确认后从列表消失', async () => {
    server(ownerRoutes({
      [LIST]: liveList([track(1), track(2)]),
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

    // 弹窗：写清数量与前三个标题；默认焦点在「取消」上
    await click(batchButton('批量删除'));
    expect(dialogText()).toContain('删除选中的 1 项？');
    expect(dialogText()).toContain('曲目 2');
    expect(document.activeElement).toBe(dialogButton('取消'));

    // 取消：不发请求，弹窗关掉
    await click(dialogButton('取消'));
    expect(document.querySelector('.modal')).toBeNull();
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    // 再来一次并确认
    await click(batchButton('批量删除'));
    await click(dialogButton('删除 1 项'));
    // 列表按入库时间倒序：第一个勾选框对应曲目 2
    await until(() => !text().includes('曲目 2'));
    expect(text()).toContain('曲目 1');
    expect(callKeys()).toContain('DELETE /v2/tracks/2');
  });

  test('批量：全选当前列表后可以一次删多条，确认框列前三个标题', async () => {
    server(ownerRoutes({
      [LIST]: liveList([track(1), track(2), track(3), track(4)]),
      'DELETE /v2/tracks/1': { json: { ok: true, id: 1, deleted: true } },
      'DELETE /v2/tracks/2': { json: { ok: true, id: 2, deleted: true } },
      'DELETE /v2/tracks/3': { json: { ok: true, id: 3, deleted: true } },
      'DELETE /v2/tracks/4': { json: { ok: true, id: 4, deleted: true } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    await check(document.querySelector('input[aria-label="全选当前列表"]') as Element);
    await until(() => text().includes('已选 4 项'));
    await click(batchButton('批量删除'));
    // 超过 3 条时列出前 3 个标题，再用「等 N 项」概括
    expect(dialogText()).toContain('删除选中的 4 项？');
    expect(document.querySelectorAll('.modal .confirm-list li')).toHaveLength(3);
    expect(dialogText()).toContain('等 4 项');
    await click(dialogButton('删除 4 项'));
    await until(() => text().includes('媒体库里还没有内容'));
    expect(callKeys().filter((k) => k.startsWith('DELETE')).length).toBe(4);
  });

  test('选中状态：改筛选、换标签页、批量删完都会清空', async () => {
    server(ownerRoutes({
      [LIST]: liveList([track(1), track(2), track(3), track(4)]),
      'DELETE /v2/tracks/1': { json: { ok: true, id: 1, deleted: true } },
      'DELETE /v2/tracks/2': { json: { ok: true, id: 2, deleted: true } },
      'DELETE /v2/tracks/3': { json: { ok: true, id: 3, deleted: true } },
      'DELETE /v2/tracks/4': { json: { ok: true, id: 4, deleted: true } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    const boxes = () => Array.from(document.querySelectorAll('.track-pick input'));
    const pickAll4 = async () => {
      for (const b of boxes()) await check(b);
      await until(() => text().includes('已选 4 项'));
    };

    // 1）勾满 4 项 → 搜一个搜不到的词：列表空、选中清空、批量动作跟着消失
    await pickAll4();
    await typeInto(document.querySelector('input[aria-label="搜索媒体库"]')!, 'zzz-查无此条');
    await until(() => text().includes('没有匹配的条目'));
    expect(text()).not.toContain('已选');
    expect(document.querySelector('.selectbar button')).toBeNull();

    // 2）清掉搜索 → 再勾满 → 切到「视频」标签页（一条都没有）：同样清空
    await typeInto(document.querySelector('input[aria-label="搜索媒体库"]')!, '');
    await until(() => text().includes('曲目 1'));
    await pickAll4();
    await click(Array.from(document.querySelectorAll('.chips .chip')).find((c) => c.textContent?.includes('视频'))!);
    await until(() => text().includes('没有匹配的条目'));
    expect(text()).not.toContain('已选');

    // 3）切回音频、勾 2 项批量删除：删完勾选清空，且列表重新向服务端对齐
    await click(Array.from(document.querySelectorAll('.chips .chip')).find((c) => c.textContent?.includes('音频'))!);
    await until(() => text().includes('曲目 1'));
    await check(boxes()[0]!);
    await check(boxes()[1]!);
    await until(() => text().includes('已选 2 项'));
    await click(batchButton('批量删除'));
    await click(dialogButton('删除 2 项'));
    await until(() => !text().includes('已选'));
    // 列表按入库时间倒序：勾的是曲目 4、曲目 3
    expect(titles()).toEqual(['曲目 2', '曲目 1']);
    expect(callKeys().filter((k) => k.startsWith('DELETE')).length).toBe(2);
    // 删完又拉了一次列表（对齐服务端），不是只改了本地数组
    expect(callKeys().filter((k) => k === LIST).length).toBeGreaterThan(1);
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
    // 跳过的要说清楚跳了几条、为什么
    await until(() => text().includes('已下载 1 项，跳过 1 项（无文件）'));
  });

  test('无文件：播放与下载都禁用并说明原因，删除仍然可用（危险色）', async () => {
    server(ownerRoutes({
      [LIST]: {
        json: {
          items: [track(1, { has_file: false, storage: 'metadata', bytes: 0, source_url: null })],
          count: 1,
        },
      },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    const btns = Array.from(document.querySelectorAll('.track-actions button'));
    const byText = (t: string) => btns.find((b) => b.textContent?.trim() === t)!;

    const play = byText('播放') as HTMLButtonElement;
    expect(play.disabled).toBe(true);
    expect(play.getAttribute('title')).toContain('没有这条的文件');

    const dl = byText('下载') as HTMLButtonElement;
    expect(dl.disabled).toBe(true);
    expect(dl.getAttribute('title')).toContain('没有这条的文件');

    // 删除不受影响，而且是危险样式
    const del = byText('删除') as HTMLButtonElement;
    expect(del.disabled).toBe(false);
    expect(del.classList.contains('btn-danger')).toBe(true);
  });

  test('分页：每页 5 条，序号跨页连续，翻页会清空选中', async () => {
    const many = Array.from({ length: 12 }, (_, i) => track(i + 1));
    server(ownerRoutes({ [LIST]: { json: { items: many, count: 12 } } }));
    mount('/library');
    await until(() => text().includes('曲目 12'));

    // 默认按入库时间倒序：第一页是 12..8
    expect(document.querySelectorAll('.track')).toHaveLength(5);
    expect(titles()).toEqual(['曲目 12', '曲目 11', '曲目 10', '曲目 9', '曲目 8']);
    expect(Array.from(document.querySelectorAll('.track-no')).map((e) => e.textContent))
      .toEqual(['1', '2', '3', '4', '5']);
    expect(document.querySelector('.pager .pager-total')!.textContent).toContain('第 1 / 3 页');

    // 勾一条 → 翻到第 2 页：选中清空，序号接着 6
    await check(document.querySelectorAll('.track-pick input')[0]!);
    await until(() => text().includes('已选 1 项'));
    await click(Array.from(document.querySelectorAll('.pager button'))
      .find((b) => b.textContent?.trim() === '2')!);
    await until(() => titles()[0] === '曲目 7');
    expect(text()).not.toContain('已选');
    expect(Array.from(document.querySelectorAll('.track-no')).map((e) => e.textContent))
      .toEqual(['6', '7', '8', '9', '10']);
    expect(document.querySelector('.pager .pager-total')!.textContent).toContain('第 2 / 3 页');
  });

  test('每页条数：默认 5 条，可切 10 / 20 条，切换后回第 1 页并清空选中', async () => {
    const many = Array.from({ length: 12 }, (_, i) => track(i + 1));
    server(ownerRoutes({ [LIST]: { json: { items: many, count: 12 } } }));
    localStorage.removeItem('getmedia.library.pageSize'); // 别受同文件里别的用例影响
    mount('/library');
    await until(() => text().includes('曲目 12'));

    // 默认 5 条/页 → 3 页，选择器显示当前值
    expect(document.querySelectorAll('.track')).toHaveLength(5);
    expect(document.querySelector('.pager .pager-size .select-value')!.textContent).toBe('5 条');
    expect(document.querySelector('.pager .pager-total')!.textContent).toContain('第 1 / 3 页');

    // 先翻到第 2 页并勾一条，用来验证「切每页条数也会清空选中」
    await click(Array.from(document.querySelectorAll('.pager button'))
      .find((b) => b.textContent?.trim() === '2')!);
    await until(() => titles()[0] === '曲目 7');
    await check(document.querySelectorAll('.track-pick input')[0]!);
    await until(() => text().includes('已选 1 项'));

    // 切 20 条/页：12 条一屏放完，回第 1 页，选中清空
    await pickPageSize('20 条');
    await until(() => document.querySelectorAll('.track').length === 12);
    expect(titles()[0]).toBe('曲目 12');
    expect(text()).not.toContain('已选');
    expect(document.querySelector('.pager .pager-total')!.textContent).toContain('第 1 / 1 页');
    // 只有一页时页码/上一页/下一页都不渲染，但「每页」选择器必须留着，否则切不回来
    expect(document.querySelector('.pager .pager-on')).toBeNull();
    expect(Array.from(document.querySelectorAll('.pager button'))
      .some((b) => b.textContent?.trim() === '下一页')).toBe(false);
    expect(document.querySelector('.pager .pager-size')).not.toBeNull();

    // 切 10 条：12 条 → 2 页，序号 1..10
    await pickPageSize('10 条');
    await until(() => document.querySelectorAll('.track').length === 10);
    expect(document.querySelector('.pager .pager-total')!.textContent).toContain('第 1 / 2 页');

    // 切回 5 条：分页按钮回来，且选择被记住
    await pickPageSize('5 条');
    await until(() => document.querySelectorAll('.track').length === 5);
    expect(document.querySelector('.pager .pager-total')!.textContent).toContain('第 1 / 3 页');
    expect(localStorage.getItem('getmedia.library.pageSize')).toBe('5');
    localStorage.removeItem('getmedia.library.pageSize');
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
      [LIST]: liveList([track(1)]),
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

    // 删除：弹页面内确认框，标题写清楚删的是哪一条
    await click(Array.from(document.querySelectorAll('.track-actions button'))
      .find((b) => b.textContent?.trim() === '删除')!);
    expect(dialogText()).toContain('确定删除《曲目 1》吗？');
    await click(dialogButton('删除'));
    await until(() => text().includes('媒体库里还没有内容'));
    expect(callKeys()).toContain('DELETE /v2/tracks/1');
    expect(callKeys()).not.toContain('DELETE /v2/tracks/1/file');
  });

  test('切到上面那一条：新播放器照样自动播，旧的那条停掉', async () => {
    const played: string[] = [];
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
      played.push(this.getAttribute('src') || '');
      return Promise.resolve();
    });
    server(ownerRoutes({
      [LIST]: { json: { items: [track(1), track(2)], count: 2 } },
    }));
    mount('/library');
    await until(() => text().includes('曲目 1'));
    // 默认按入库时间倒序：第一行是曲目 2
    expect(titles()).toEqual(['曲目 2', '曲目 1']);

    const playBtn = (row: number) => Array.from(
      document.querySelectorAll('.track-actions')[row]!.querySelectorAll('button'),
    ).find((b) => /播放|暂停/.test(b.textContent || ''))!;

    // 先放下面那条，再点上一条：旧播放器卸载时不能把新播放器的引用抹掉
    await click(playBtn(1));
    await until(() => played.length === 1);
    await click(playBtn(0));
    await until(() => played.length === 2);
    expect(played).toEqual(['/api/v2/media/1', '/api/v2/media/2']);
    // 同一时刻只有一个播放器
    expect(document.querySelectorAll('audio')).toHaveLength(1);
  });

  test('筛选区一行排开：搜索、平台、起止日期、只看有文件、排序都在同一个 .filters 里', async () => {
    server(ownerRoutes({ [LIST]: { json: { items: [track(1)], count: 1 } } }));
    mount('/library');
    await until(() => text().includes('曲目 1'));

    const filters = document.querySelector('.filters')!;
    expect(filters).not.toBeNull();
    // 不再人为拆成两个 .filter-row
    expect(filters.querySelectorAll('.filter-row')).toHaveLength(0);
    expect(filters.querySelector('input[type="search"]')).not.toBeNull();
    // 平台是自绘下拉，排序是自己的拆分控件（左翻方向 + 右选字段）
    expect(filters.querySelectorAll('.select')).toHaveLength(1);
    expect(filters.querySelectorAll('input[type="date"]')).toHaveLength(2);
    expect(filters.querySelector('input[type="checkbox"]')).not.toBeNull();
    expect(filters.querySelector('.sort-split')).not.toBeNull();
    // 排序方向旁边有文字（光看箭头得先想一下是哪一头）
    expect(filters.querySelector('.sort-dir')!.textContent).toBe('最新在前');

    // 那段"单击一行播放，双击标题改名…"的说明文字不再显示
    expect(text()).not.toContain('单击一行播放');
    expect(text()).not.toContain('列表最多取 10000 条');
    // 但"双击标题可改名"这条提示要看得见
    expect(text()).toContain('双击标题可改名');
  });

  test('空库与读取失败两种状态', async () => {
    server(ownerRoutes({ [LIST]: { json: { items: [], count: 0 } } }));
    mount('/library');
    await until(() => text().includes('媒体库里还没有内容'));
    expect(text()).toContain('单个提取');
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

  test('切走再切回：列表直接用上次的数据，不回到骨架，同时在背后刷新', async () => {
    server(ownerRoutes({ [LIST]: liveList([track(1), track(2)]) }));
    mount('/library');
    await until(() => text().includes('曲目 1'));
    const before = callKeys().filter((k) => k === LIST).length;

    // 切到一个不发请求的页面（路径不在栏目表里 → 「页面不存在」）
    await act(async () => { navigate('/no-such-page'); });
    await until(() => text().includes('页面不存在'));

    // 切回来：同一帧就有列表，不再出现骨架
    await act(async () => { navigate('/library'); });
    expect(document.querySelectorAll('.track')).toHaveLength(2);
    expect(document.querySelector('.skeleton')).toBeNull();
    expect(text()).toContain('曲目 1');

    // 但背后仍然刷新了一次：数据不会永远停在旧的那份
    await until(() => callKeys().filter((k) => k === LIST).length > before);
  });
});
