/** 5d：定时任务。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act } from 'preact/test-utils';
import {
  button, calls, cleanup, click, mount, ownerRoutes, server, text, until,
} from './harness';

function schedule(over: Record<string, unknown> = {}) {
  return {
    id: 5, name: '每天看一次', platform: 'bilibili', input: '12345', mode: 'latest_n',
    since_ts: null, until_ts: null, limit_n: 20, interval_minutes: 1440, include_pinned: true,
    storage: 'phone', media_type: 'video', enabled: true,
    next_run: 1_800_086_400, last_run: 1_800_000_000, last_status: 'done', last_job_id: 3,
    created_at: 1_799_000_000, updated_at: 1_800_000_000,
    ...over,
  };
}

const LIST = 'GET /v2/schedules';

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('定时任务', () => {
  test('列表：间隔、下次执行、上次结果都用人话显示', async () => {
    server(ownerRoutes({ [LIST]: { json: { schedules: [schedule()] } } }));
    mount('/schedules');
    await until(() => text().includes('每天看一次'));

    expect(text()).toContain('每 1 天');
    expect(text()).toContain('已启用');
    expect(text()).toContain('上次任务 #3');
    expect(text()).toContain('最近 N 条（20 条）');
  });

  test('停用：PATCH 只发 enabled，并按返回值就地更新', async () => {
    server(ownerRoutes({
      [LIST]: { json: { schedules: [schedule()] } },
      'PATCH /v2/schedules/5': { json: schedule({ enabled: false }) },
    }));
    mount('/schedules');
    await until(() => text().includes('每天看一次'));

    await click(button('停用'));
    await until(() => text().includes('已停用，不会执行'));

    const patch = calls.find((c) => c.method === 'PATCH')!;
    expect(patch.path).toBe('/v2/schedules/5');
    expect(patch.body).toEqual({ enabled: false });
    expect(text()).toContain('已停用');
  });

  test('立刻跑一次：发请求并刷新列表', async () => {
    let round = 0;
    server(ownerRoutes({
      [LIST]: () => (round++ === 0
        ? { json: { schedules: [schedule({ last_status: null, last_job_id: null })] } }
        : { json: { schedules: [schedule({ last_job_id: 4 })] } }),
      'POST /v2/schedules/5/run-once': { json: { scheduled: true, schedule_id: 5 } },
    }));
    mount('/schedules');
    await until(() => text().includes('每天看一次'));
    await click(button('立刻跑一次'));
    await until(() => text().includes('上次任务 #4'));
    expect(calls.some((c) => c.path === '/v2/schedules/5/run-once')).toBe(true);
  });

  test('新建：表单校验与提交体', async () => {
    server(ownerRoutes({
      [LIST]: { json: { schedules: [] } },
      'POST /v2/schedules': { json: schedule({ id: 6, name: '新任务' }) },
    }));
    mount('/schedules');
    await until(() => text().includes('还没有定时任务'));

    await click(button('新建定时任务'));
    await until(() => text().includes('名字和博主要都填'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);

    const name = document.querySelectorAll('input[type="text"]')[0] as HTMLInputElement;
    const who = document.querySelectorAll('input[type="text"]')[1] as HTMLInputElement;
    await act(async () => {
      name.value = '新任务';
      name.dispatchEvent(new Event('input', { bubbles: true }));
      who.value = '12345';
      who.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await click(button('新建定时任务'));
    await until(() => calls.some((c) => c.method === 'POST'));

    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>;
    expect(body).toMatchObject({ name: '新任务', platform: 'bilibili', input: '12345', mode: 'latest_n', interval_minutes: 1440 });
  });

  test('删除要二次确认；取消就什么都不发', async () => {
    server(ownerRoutes({
      [LIST]: { json: { schedules: [schedule()] } },
      'DELETE /v2/schedules/5': {},
    }));
    mount('/schedules');
    await until(() => text().includes('每天看一次'));

    vi.stubGlobal('confirm', () => false);
    await click(button('删除'));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    vi.stubGlobal('confirm', () => true);
    await click(button('删除'));
    await until(() => text().includes('还没有定时任务'));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(true);
  });

  test('执行历史：展开才请求，空历史说清楚', async () => {
    server(ownerRoutes({
      [LIST]: { json: { schedules: [schedule()] } },
      'GET /v2/schedules/5/runs': { json: { runs: [] } },
    }));
    mount('/schedules');
    await until(() => text().includes('每天看一次'));
    expect(calls.some((c) => c.path === '/v2/schedules/5/runs')).toBe(false);
    await click(button('执行历史'));
    await until(() => text().includes('还没有执行记录'));
  });
});
