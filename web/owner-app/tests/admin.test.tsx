/** 5g：权限管理 + 邀请码（管理员专属两页）。
 *
 * 这里只测"界面按规则长出来"和"参数真的发出去了"：权限判断由服务端负责，
 * 但界面也不该让 full 账号点进去、更不该把 admin 行渲染出"停用"按钮。
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act } from 'preact/test-utils';
import { ADMIN_ME, button, calls, cleanup, click, mount, ownerRoutes, server, text, until } from './harness';

/** 往普通 input 里塞值：直接在 act 里派发，否则随后的 click 会拿到上一轮的 state。 */
async function setInput(sel: string, value: string) {
  const el = document.querySelector(sel) as HTMLInputElement;
  await act(async () => {
    el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

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

  test('列表：统计条、等级徽章、待注销的「还剩 N 天 · 截止」都渲染出来', async () => {
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
    expect(document.querySelectorAll('.tag.tier-full').length).toBe(2);
    expect(document.querySelectorAll('.tag.tier-admin').length).toBe(1);
    // 待注销：还剩天数 + 到秒的截止时间
    expect(text()).toContain('待注销 · 还剩 6 天 · 截止');
    expect(text()).toMatch(/截止 \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
    // 管理员行只有「重置密码」：整行不该出现「设为免费」「停用」
    const adminRow = Array.from(document.querySelectorAll('tbody tr'))
      .find((tr) => tr.textContent?.includes('luckyrichor'))!;
    expect(adminRow.textContent).toContain('重置密码');
    expect(adminRow.textContent).not.toContain('停用');
    expect(adminRow.textContent).not.toContain('设为');
    // 待注销行给的是「撤销注销」而不是「设为…」
    const pendingRow = Array.from(document.querySelectorAll('tbody tr'))
      .find((tr) => tr.textContent?.includes('bob'))!;
    expect(pendingRow.textContent).toContain('撤销注销');
    expect(pendingRow.textContent).not.toContain('设为完整版');
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
    await until(() => text().includes('设为免费'));
    await click(button('设为免费'));
    await until(() => text().includes('把「alice」设为免费版'));
    expect(text()).toContain('失去云端同步、服务器提取');
    expect(document.querySelectorAll('input[type="radio"]').length).toBe(0);

    await click(button('确认'));
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toEqual({ tier: 'free', duration: '6m' });
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
});
