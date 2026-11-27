/** 5g：权限管理 + 邀请码（管理员专属两页）。
 *
 * 这里只测"界面按规则长出来"和"参数真的发出去了"：权限判断由服务端负责，
 * 但界面也不该让 full 账号点进去、更不该把 admin 行渲染出"停用"按钮。
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act } from 'preact/test-utils';
import { ADMIN_ME, button, calls, choose, cleanup, click, mount, ownerRoutes, server, text, until } from './harness';

/** 往普通 input 里塞值：直接在 act 里派发，否则随后的 click 会拿到上一轮的 state。 */
async function setInput(sel: string, value: string) {
  const el = document.querySelector(sel) as HTMLInputElement;
  await act(async () => {
    el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

/** epoch 秒 → datetime-local 认的本地串，和页面里的换算保持一致，用来断言预填值。 */
function localInput(unix: number): string {
  const d = new Date(unix * 1000);
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`
    + `T${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
}

/** 等级分段控件：按钮文字是「免费版 4」，按前缀找。 */
const seg = (label: string) => Array.from(document.querySelectorAll('.seg-btn'))
  .find((b) => (b.textContent ?? '').startsWith(label)) as HTMLElement;

/** 最近一次账号列表请求的查询串（筛选参数真的发出去了没有，就看它）。 */
function lastAccountsQuery(): URLSearchParams {
  const hit = calls.filter((c) => c.path === '/v2/admin/accounts').pop();
  return new URLSearchParams(hit?.search ?? '');
}

const dayStartOf = (s: string) => Math.floor(new Date(`${s}T00:00:00`).getTime() / 1000);
const dayEndOf = (s: string) => Math.floor(new Date(`${s}T23:59:59`).getTime() / 1000);

const NOW = Math.floor(Date.now() / 1000);

const ACCOUNT = {
  id: 'acct_2', login_name: 'alice', tier: 'full', tier_expires_at: NOW + 120 * 86400,
  status: 'active', created_at: NOW - 86400, last_seen_at: NOW - 3600,
  active_sessions: 2, audio: 30, video: 6, media_total: 36, bytes: 1_200_000_000,
  playlists: 1, deletion: null, admin: false,
};
const ADMIN_ROW = {
  ...ACCOUNT, id: 'acct_admin', login_name: 'luckyrichor', tier: 'admin',
  tier_expires_at: null, status: 'active', admin: true, active_sessions: 1,
};
const PENDING_ROW = {
  ...ACCOUNT, id: 'acct_3', login_name: 'bob', status: 'pending_deletion',
  deletion: { requested_at: NOW - 86400, execute_at: NOW + 5 * 86400 + 43205, days_left: 6 },
};

const SUMMARY = { free: 4, full: 2, admin: 1, expiring_30d: 0, disabled: 0, pending_deletion: 1 };

function accountsPage(rows: unknown[]) {
  return {
    json: { total: rows.length, page: 1, size: 50, summary: SUMMARY, items: rows },
  };
}

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('权限管理', () => {
  test('完整版账号：导航里没有这一栏，直接输地址也只给「无权访问」', async () => {
    server(ownerRoutes({
      'GET /v2/admin/accounts': { status: 403, json: { detail: '需要管理员权限', code: 'forbidden_tier' } },
    }));
    mount('/accounts');
    await until(() => text().includes('无权访问'));
    // 导航里看不到这两栏
    const nav = Array.from(document.querySelectorAll('nav.side a')).map((a) => a.textContent);
    expect(nav).not.toContain('权限管理');
    expect(nav).not.toContain('邀请码');
    expect(text()).not.toContain('账号总数');
  });

  test('列表：统计条、等级徽章、待注销的「还剩 N 天」都渲染出来（整行单行）', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ADMIN_ROW, ACCOUNT, PENDING_ROW]),
    }));
    mount('/accounts');
    await until(() => text().includes('账号总数'));

    expect(text()).toContain('免费版');
    expect(text()).toContain('30 天内到期');
    expect(text()).toContain('已停用 / 待注销');
    // 等级徽章：alice 与 bob 都是完整版，管理员一个
    expect(document.querySelectorAll('.badge-full').length).toBe(2);
    expect(document.querySelectorAll('.badge-admin').length).toBe(1);
    // 管理员标识只在「等级」列出现一次，不再挂在登录名下面
    expect(document.querySelectorAll('.cell-name .tag').length).toBe(0);
    expect(document.querySelectorAll('tbody .badge-admin').length).toBe(1);
    // 待注销：列里只留「还剩 N 天」，到秒的截止时间放 title（塞进列里必然折行）
    expect(text()).toContain('待注销 · 6 天');
    const bad = document.querySelector('.status-bad') as HTMLElement;
    expect(bad.getAttribute('title')).toMatch(/^截止 \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    // 管理员行只有「重置密码」：整行不该出现「设为免费」「停用」
    const adminRow = Array.from(document.querySelectorAll('tbody tr'))
      .find((tr) => tr.textContent?.includes('luckyrichor'))!;
    expect(adminRow.textContent).toContain('重置密码');
    expect(adminRow.textContent).not.toContain('停用');
    expect(adminRow.textContent).not.toContain('设为');
    expect(adminRow.querySelector('.menu-btn')).toBeNull();      // 连「更多」都不给
    // 待注销行的风险操作收进「更多」：默认不占位置，点开才出现
    const pendingRow = Array.from(document.querySelectorAll('tbody tr'))
      .find((tr) => tr.textContent?.includes('bob'))!;
    expect(pendingRow.textContent).not.toContain('撤销注销');
    await click(pendingRow.querySelector('.menu-btn') as HTMLElement);
    const pop = document.querySelector('.menu-pop') as HTMLElement;
    expect(pop.textContent).toContain('撤销注销');
    expect(pop.textContent).toContain('停用');
    expect(pop.textContent).not.toContain('设为完整版');
  });

  test('设为完整版：选永久 → 提交 tier=full + duration=permanent，并提示踢下线数', async () => {
    let round = 0;
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      // 第一次返回 free 行，提交后刷新成 full 行
      'GET /v2/admin/accounts': () => (round++ === 0
        ? accountsPage([{ ...ACCOUNT, tier: 'free', tier_expires_at: null }])
        : accountsPage([ACCOUNT])),
      'POST /v2/admin/accounts/acct_2/tier': {
        json: { ok: true, tier: 'full', tier_expires_at: null, revoked_sessions: 2 },
      },
    }));
    mount('/accounts');
    await until(() => text().includes('alice'));
    // 「设为完整版」在行尾的「更多 ⋯」里
    await click(document.querySelector('tbody .menu-btn') as HTMLElement);
    await until(() => text().includes('设为完整版'));

    await click(button('设为完整版'));
    await until(() => text().includes('把「alice」设为完整版'));
    // 默认 6 个月；这里改选永久
    const perm = Array.from(document.querySelectorAll('input[type="radio"]'))
      .find((el) => (el.parentElement?.textContent ?? '').includes('永久'))!;
    await click(perm);
    await click(button('确认'));

    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toEqual({ tier: 'full', duration: 'permanent' });
    await until(() => text().includes('已设为完整版'));
    expect(text()).toContain('2 个登录设备已退出');
  });

  test('设为免费：确认文案说明会失去哪些能力（不再给期限选项）', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ACCOUNT]),
      'POST /v2/admin/accounts/acct_2/tier': {
        json: { ok: true, tier: 'free', tier_expires_at: null, revoked_sessions: 1 },
      },
    }));
    mount('/accounts');
    await until(() => text().includes('alice'));
    await click(document.querySelector('tbody .menu-btn') as HTMLElement);
    await until(() => text().includes('设为免费'));
    await click(button('设为免费'));
    await until(() => text().includes('把「alice」设为免费版'));
    expect(text()).toContain('失去云端同步、服务器提取');
    expect(document.querySelectorAll('input[type="radio"]').length).toBe(0);

    await click(button('确认'));
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toEqual({ tier: 'free', duration: '6m' });
  });

  test('换等级筛选：旧表格留在原位（只淡出），不闪骨架、行数不变', async () => {
    // 第二次请求先挂住，好在"还没回来"这一刻检查页面
    let release!: (v: unknown) => void;
    const gate = new Promise((r) => { release = r; });
    let first = true;
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': () => {
        if (first) { first = false; return accountsPage([ADMIN_ROW, ACCOUNT, PENDING_ROW]); }
        return gate as never;
      },
    }));
    mount('/accounts');
    await until(() => text().includes('alice'));
    const rows = document.querySelectorAll('tbody tr').length;
    expect(rows).toBe(3);

    await click(seg('免费版')); // 只选「免费版」→ 触发重取

    // 请求未完成：不能出现骨架（骨架一出现，页面高度就塌，滚动位置会被夹回顶部）
    expect(document.querySelector('.skeleton')).toBeNull();
    expect(document.querySelectorAll('tbody tr').length).toBe(rows);
    expect(document.querySelector('.is-refreshing')).not.toBeNull();

    release(accountsPage([]));
    await until(() => text().includes('没有符合条件的账号'));
  });

  test('改有效期：预填当前到期时间，改完按秒提交，并说明不会踢人下线', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ACCOUNT]),
      'POST /v2/admin/accounts/acct_2/expiry': {
        json: { ok: true, tier: 'full', tier_expires_at: 1_900_000_000 },
      },
    }));
    mount('/accounts');
    await until(() => text().includes('改有效期'));
    await click(button('改有效期'));
    await until(() => text().includes('设置「alice」的有效期'));

    // 是弹窗，不是页面里插一块
    expect(document.querySelector('[role="dialog"]')).not.toBeNull();
    const input = document.querySelector('input[type="datetime-local"]') as HTMLInputElement;
    expect(input.value).toBe(localInput(ACCOUNT.tier_expires_at));   // 预填 = 现在的到期时间

    await setInput('input[type="datetime-local"]', '2027-03-15T09:08:07');
    await click(button('保存'));

    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toEqual({
      expires_at: Math.floor(new Date('2027-03-15T09:08:07').getTime() / 1000),
    });
    // 存完直接关弹窗，不再弹成功提示（表格里那一行变了就是反馈）
    await until(() => document.querySelector('[role="dialog"]') === null);
    expect(text()).not.toContain('有效期已改为');
  });

  test('改有效期：选「永久」时发 null；免费版行没有这个按钮', async () => {
    const freeRow = { ...ACCOUNT, id: 'acct_4', login_name: 'carol', tier: 'free', tier_expires_at: null };
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ACCOUNT, freeRow]),
      'POST /v2/admin/accounts/acct_2/expiry': { json: { ok: true, tier: 'full', tier_expires_at: null } },
    }));
    mount('/accounts');
    await until(() => text().includes('carol'));

    // 只有完整版那一行有「改有效期」
    expect(Array.from(document.querySelectorAll('button')).filter((b) => b.textContent === '改有效期').length).toBe(1);

    await click(button('改有效期'));
    await until(() => text().includes('设置「alice」的有效期'));
    const perm = Array.from(document.querySelectorAll('input[type="radio"]'))
      .find((el) => (el.parentElement?.textContent ?? '').includes('永久'))!;
    await click(perm);
    expect(document.querySelector('input[type="datetime-local"]')).toBeNull();   // 选永久就不再要时间
    await click(button('保存'));

    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toEqual({ expires_at: null });
  });

  test('改有效期是弹窗：Esc 关闭、点遮罩关闭、点弹窗内部不关，打开时锁住背景滚动', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ACCOUNT]),
    }));
    mount('/accounts');
    await until(() => text().includes('改有效期'));

    const open = async () => {
      await click(button('改有效期'));
      await until(() => document.querySelector('[role="dialog"]') !== null);
    };

    // Esc
    await open();
    expect(document.body.style.overflow).toBe('hidden');
    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    await until(() => document.querySelector('[role="dialog"]') === null);
    expect(document.body.style.overflow).toBe('');

    // 点弹窗内部不关
    await open();
    await click(document.querySelector('[role="dialog"]') as HTMLElement);
    expect(document.querySelector('[role="dialog"]')).not.toBeNull();

    // 点遮罩关
    await click(document.querySelector('.modal-mask') as HTMLElement);
    await until(() => document.querySelector('[role="dialog"]') === null);

    // 取消按钮也关
    await open();
    await click(button('取消'));
    await until(() => document.querySelector('[role="dialog"]') === null);
  });
  test('创建时间范围：起止按当天 00:00:00 / 23:59:59 传参，只填一端也生效', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ACCOUNT]),
    }));
    mount('/accounts');
    await until(() => text().includes('alice'));

    await setInput('input[aria-label="创建时间开始"]', '2026-11-01');
    await until(() => lastAccountsQuery().get('created_from') === String(dayStartOf('2026-11-01')));
    expect(lastAccountsQuery().get('created_to')).toBeNull();   // 只填起始也算生效

    await setInput('input[aria-label="创建时间截止"]', '2026-11-27');
    await until(() => lastAccountsQuery().get('created_to') === String(dayEndOf('2026-11-27')));
    expect(lastAccountsQuery().get('created_from')).toBe(String(dayStartOf('2026-11-01')));
  });

  test('起始晚于结束：自动对调两个框，并按对调后的区间查询', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ACCOUNT]),
    }));
    mount('/accounts');
    await until(() => text().includes('alice'));

    await setInput('input[aria-label="创建时间开始"]', '2026-11-27');
    await setInput('input[aria-label="创建时间截止"]', '2026-11-01');

    await until(() => lastAccountsQuery().get('created_from') === String(dayStartOf('2026-11-01')));
    expect(lastAccountsQuery().get('created_to')).toBe(String(dayEndOf('2026-11-27')));
    expect((document.querySelector('input[aria-label="创建时间开始"]') as HTMLInputElement).value).toBe('2026-11-01');
    expect((document.querySelector('input[aria-label="创建时间截止"]') as HTMLInputElement).value).toBe('2026-11-27');
  });

  test('快捷「今天」：两个框都填今天，参数是今天 00:00:00 到 23:59:59', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ACCOUNT]),
    }));
    mount('/accounts');
    await until(() => text().includes('alice'));

    await click(button('快捷 ▾'));
    await click(button('今天'));

    const today = new Date();
    const two = (x: number) => String(x).padStart(2, '0');
    const ymd = `${today.getFullYear()}-${two(today.getMonth() + 1)}-${two(today.getDate())}`;
    await until(() => lastAccountsQuery().get('created_from') === String(dayStartOf(ymd)));
    expect(lastAccountsQuery().get('created_to')).toBe(String(dayEndOf(ymd)));
    expect((document.querySelector('input[aria-label="创建时间开始"]') as HTMLInputElement).value).toBe(ymd);
  });

  test('重置筛选：有筛选才出现，点完清空并回到第一页', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ACCOUNT]),
    }));
    mount('/accounts');
    await until(() => text().includes('alice'));
    expect(document.querySelector('.filter-reset')).toBeNull();   // 什么都没选时不显示

    await setInput('input[aria-label="创建时间开始"]', '2026-11-01');
    await until(() => document.querySelector('.filter-reset') !== null);
    await click(button('重置筛选'));

    await until(() => lastAccountsQuery().get('created_from') === null);
    expect((document.querySelector('input[aria-label="创建时间开始"]') as HTMLInputElement).value).toBe('');
    expect(document.querySelector('.filter-reset')).toBeNull();
    expect((seg('全部') as HTMLElement).getAttribute('aria-selected')).toBe('true');
  });

  test('「更多」里的停用是危险项，点了要先确认再发请求', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage([ACCOUNT]),
      'POST /v2/admin/accounts/acct_2/status': {
        json: { ok: true, status: 'disabled', revoked_sessions: 2 },
      },
    }));
    mount('/accounts');
    await until(() => text().includes('alice'));
    await click(document.querySelector('tbody .menu-btn') as HTMLElement);

    const item = Array.from(document.querySelectorAll('.menu-item')).find((b) => b.textContent === '停用')!;
    expect(item.className).toContain('danger');
    await click(item);

    expect(confirmSpy).toHaveBeenCalled();
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toEqual({ status: 'disabled' });
    await until(() => text().includes('已停用 alice'));
  });

});

describe('邀请码', () => {
  test('生成 2 个：明文出现一次、可下载；关闭后明文消失', async () => {
    const list = { json: { total: 0, page: 1, size: 50, items: [] } };
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/invites?page=1': list,
      'POST /v2/admin/invites': { json: { codes: ['AAA-111', 'BBB-222'], count: 2, expires_at: NOW + 86400 } },
    }));
    mount('/invites');
    await until(() => text().includes('还没有生成过邀请码'));

    await setInput('input#inv-count', '2');
    await click(button('生成'));
    await until(() => text().includes('已生成 2 个'));
    expect(text()).toContain('AAA-111');
    expect(text()).toContain('BBB-222');
    expect(text()).toContain('只显示这一次');
    expect(button('下载 .txt')).toBeTruthy();
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ count: 2 });

    await click(button('关闭'));
    await until(() => !text().includes('AAA-111'));
    expect(text()).not.toContain('BBB-222');
  });

  test('数量越界（51）：就地提示、按钮禁用、一个请求都不发', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/invites?page=1': { json: { total: 0, page: 1, size: 50, items: [] } },
    }));
    mount('/invites');
    await until(() => text().includes('生成数量'));

    await setInput('input#inv-count', '51');
    await until(() => text().includes('数量要是 1–50 之间的整数'));

    const gen = button('生成');
    expect(gen.disabled).toBe(true);
    await click(gen);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  test('列表：状态徽章与使用人；未使用的码不显示明文', async () => {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/invites?page=1': {
        json: {
          total: 2, page: 1, size: 50,
          items: [
            { id: 'i1', state: 'used', created_at: NOW - 7200, expires_at: NOW - 3600, used_at: NOW - 4000, used_by: 'alice' },
            { id: 'i2', state: 'unused', created_at: NOW - 600, expires_at: NOW + 85800, used_at: null, used_by: null },
          ],
        },
      },
    }));
    mount('/invites');
    await until(() => text().includes('alice'));
    expect(text()).toContain('luckyrichor'); // 页头账号名，确认进的是管理员会话
    expect(document.querySelectorAll('.tag.used').length).toBe(1);
    // 列表只有元数据，没有码明文
    expect(text()).not.toMatch(/[A-Z0-9]{4}-[A-Z0-9]{4}/);
  });
  test('换状态筛选：旧列表留在原位（只淡出），不闪骨架', async () => {
    let release!: (v: unknown) => void;
    const gate = new Promise((r) => { release = r; });
    const page1 = {
      json: {
        total: 1, page: 1, size: 50,
        items: [{ id: 'i1', state: 'used', created_at: NOW - 7200, expires_at: NOW - 3600, used_at: NOW - 4000, used_by: 'alice' }],
      },
    };
    let first = true;
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/invites': () => {
        if (first) { first = false; return page1; }
        return gate as never;
      },
    }));
    mount('/invites');
    await until(() => text().includes('alice'));

    await choose('按状态筛选', '已使用');

    expect(document.querySelector('.skeleton')).toBeNull();
    expect(document.querySelectorAll('tbody tr').length).toBe(1);
    expect(document.querySelector('.is-refreshing')).not.toBeNull();

    release(page1);
    await until(() => text().includes('alice'));
  });
});

/** 第二轮精修：列拆分、数字列对齐、操作列固定槽位、筛选栏统一。
 *  jsdom 没有布局，量不出"右边缘对不对齐"；这里守住的是**结构和类名**——
 *  真正的位置关系靠浏览器量测（见 docs/STATUS.local.md 的记录）。
 */
describe('权限管理 · 表格列与操作列', () => {
  async function mountRows(rows: unknown[]) {
    server(ownerRoutes({
      'GET /account/me': { json: ADMIN_ME },
      'GET /v2/admin/accounts': accountsPage(rows),
    }));
    mount('/accounts');
    await until(() => document.querySelector('.accounts-table') !== null);
  }

  const row = (name: string) => Array.from(document.querySelectorAll('tbody tr'))
    .find((tr) => tr.textContent?.includes(name))!;

  test('拆成 11 列：音频 / 视频 / 列表数 / 占用 各自一列，都是纯数字', async () => {
    await mountRows([ACCOUNT]);
    const heads = Array.from(document.querySelectorAll('.accounts-table thead th'))
      .map((th) => (th.textContent ?? '').trim().replace(/\s*[↑↓]$/, ''));
    expect(heads).toEqual([
      '登录名', '等级', '有效期', '状态', '创建时间', '最近活跃', '音频', '视频', '列表数', '占用', '操作',
    ]);

    // 四个数字列：表头与单元格都带 num（右对齐），且不再有「音频 30 · 视频 6」这种混排
    const numTh = Array.from(document.querySelectorAll('.accounts-table thead th.num'))
      .map((th) => (th.textContent ?? '').trim());
    expect(numTh).toEqual(['音频', '视频', '列表数', '占用']);
    const nums = Array.from(document.querySelectorAll('tbody td.num')).map((td) => (td.textContent ?? '').trim());
    expect(nums).toEqual(['30', '6', '1', '1.1GB']);
    expect(text()).not.toContain('音频 30');
    expect(text()).not.toContain('个 ·');
  });

  test('有效期与创建时间都精确到秒（改有效期按秒设，只到分钟看不出差别）', async () => {
    await mountRows([ACCOUNT]);
    // 一行里只有这两个 .dt：日期一段、时分秒一段
    const dts = Array.from(document.querySelectorAll('tbody tr .dt'));
    expect(dts.length).toBe(2);
    for (const dt of dts) {
      expect(dt.children[0]?.textContent).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(dt.querySelector('.dt-t')?.textContent).toMatch(/^\d{2}:\d{2}:\d{2}$/);
    }
  });

  test('占用列：数字与单位是两个 span，单位单独占一栏', async () => {
    await mountRows([ACCOUNT]);
    const bytes = document.querySelectorAll('tbody td.num')[3] as HTMLElement;
    const spans = Array.from(bytes.children) as HTMLElement[];
    expect(spans.map((s) => s.textContent)).toEqual(['1.1', 'GB']);
    expect(spans[1]?.className).toBe('unit');
  });

  test('全 0 的行：数字仍是 0，只是标了 zero（淡灰）', async () => {
    await mountRows([{ ...ACCOUNT, audio: 0, video: 0, playlists: 0, bytes: 0 }]);
    const cells = Array.from(document.querySelectorAll('tbody td.num'));
    expect(cells.map((td) => (td.textContent ?? '').trim())).toEqual(['0', '0', '0', '0B']);
    expect(cells.every((td) => td.classList.contains('zero'))).toBe(true);
  });

  test('操作列固定槽位：管理员用占位顶住「改有效期」，重置密码不往左偏', async () => {
    await mountRows([ADMIN_ROW, ACCOUNT]);

    // 管理员：改有效期位置是隐藏占位，没有「更多」
    const adminOps = row('luckyrichor').querySelector('.row-ops')!;
    expect(adminOps.querySelector('.op-ph')!.textContent).toBe('改有效期');
    expect(adminOps.querySelector('.menu-btn')).toBeNull();
    expect(adminOps.querySelector('.op-ph-icon')).not.toBeNull();

    // 完整版：三个槽位齐全，⋯ 是纯图标按钮
    const ops = row('alice').querySelector('.row-ops')!;
    const icon = ops.querySelector('.menu-btn') as HTMLElement;
    expect(icon.className).toContain('icon');
    expect(icon.textContent).toBe('⋯');
    expect(icon.getAttribute('aria-label')).toBe('更多操作');
    expect(icon.getAttribute('title')).toBe('更多操作');
    // 占位不参与无障碍树，也不是可点按钮
    expect(adminOps.querySelector('button.op-ph')).toBeNull();
  });

  test('「更多」里「停用」前有一条分隔线', async () => {
    await mountRows([ACCOUNT]);
    await click(row('alice').querySelector('.menu-btn') as HTMLElement);
    const pop = document.querySelector('.menu-pop')!;
    const kids = Array.from(pop.children) as HTMLElement[];
    const sepAt = kids.findIndex((el) => el.classList.contains('menu-sep'));
    const stopAt = kids.findIndex((el) => el.textContent === '停用');
    expect(sepAt).toBeGreaterThanOrEqual(0);
    expect(stopAt).toBe(sepAt + 1);
  });

  test('筛选栏：「快捷」是带边框按钮，日期框有「开始日期 / 结束日期」占位', async () => {
    await mountRows([ACCOUNT]);
    const quick = document.querySelector('.date-range .menu-btn') as HTMLElement;
    expect(quick.className).toContain('boxed');
    expect(quick.textContent).toContain('快捷');

    const hints = Array.from(document.querySelectorAll('.date-range .date-hint')).map((el) => el.textContent);
    expect(hints).toEqual(['开始日期', '结束日期']);
  });

  test('底部常显「共 N 条」：只有一页时也给总数', async () => {
    await mountRows([ADMIN_ROW, ACCOUNT]);
    const foot = document.querySelector('.box-foot')!;
    expect(foot.textContent).toContain('共 2 条');
    expect(foot.textContent).not.toContain('上一页');
  });
});
