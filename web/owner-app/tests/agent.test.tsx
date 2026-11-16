/** 5d：提取 Agent。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act } from 'preact/test-utils';
import {
  button, calls, cleanup, click, mount, ownerRoutes, server, text, until,
} from './harness';

const STATUS = 'GET /v2/agent/status';
const CONVS = 'GET /v2/agent/conversations';
const MEMS = 'GET /v2/memories';

const STATUS_RULES = { engine: 'rules', models: [], calls_today: 3, daily_cap: 200, vault_ready: true };

const CONV = { id: 'c_1', title: '最近 20 条', created_at: 1_800_000_000, updated_at: 1_800_000_100 };

function detail(over: Record<string, unknown> = {}) {
  return {
    ...CONV,
    messages: [
      { id: 1, role: 'user', content: '把最近 20 条里时长超过 10 分钟的发我', meta: {}, created_at: 1_800_000_000 },
      { id: 2, role: 'assistant', content: '好，我记下了这条偏好。', meta: { engine: 'rules', action_id: 'a_1' }, created_at: 1_800_000_001 },
    ],
    actions: [{
      id: 'a_1', conversation_id: 'c_1', kind: 'extract', platform: 'bilibili',
      input_url: 'https://www.bilibili.com/video/BV1xx411c7mD', status: 'pending',
      mode: null, result: null, created_at: 1_800_000_001, claimed_at: null, finished_at: null,
    }],
    ...over,
  };
}

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  // jsdom 没实现它；页面用它把视口带到最新一条消息
  if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = () => undefined;
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('提取 Agent', () => {
  test('规则应答时明确说明"不是故障"，并显示今日用量与上限', async () => {
    server(ownerRoutes({ [STATUS]: { json: STATUS_RULES }, [CONVS]: { json: { conversations: [] } }, [MEMS]: { json: { memories: [] } } }));
    mount('/agent');
    await until(() => text().includes('不发任何外网请求'));

    expect(text()).toContain('3 / 200');
    expect(text()).toContain('未配置');
    expect(text()).toContain('不发任何外网请求');
    expect(text()).toContain('还没有会话');
  });

  test('模型生效时显示模型名，不再显示规则提示', async () => {
    server(ownerRoutes({
      [STATUS]: { json: { engine: 'model', models: ['doubao-a', 'doubao-b'], calls_today: 0, daily_cap: 200, vault_ready: true } },
      [CONVS]: { json: { conversations: [] } },
      [MEMS]: { json: { memories: [] } },
    }));
    mount('/agent');
    await until(() => text().includes('doubao-a'));
    expect(text()).toContain('模型');
    expect(text()).not.toContain('不发任何外网请求');
  });

  test('发消息：带上会话号，回来刷新会话列表与消息', async () => {
    server(ownerRoutes({
      [STATUS]: { json: STATUS_RULES },
      [CONVS]: { json: { conversations: [CONV] } },
      [MEMS]: { json: { memories: [] } },
      'GET /v2/agent/conversations/c_1': { json: detail() },
      'POST /v2/agent/chat': {
        json: {
          conversation_id: 'c_1', reply: '好，我记下了这条偏好。', created_at: 1_800_000_001,
          engine: 'rules', model: null, reason: null,
          action: { id: 'a_1', conversation_id: 'c_1', kind: 'extract', platform: 'bilibili',
            input_url: 'https://www.bilibili.com/video/BV1xx411c7mD', status: 'pending', mode: null,
            result: null, created_at: 1_800_000_001, claimed_at: null, finished_at: null },
        },
      },
    }));
    mount('/agent');
    await until(() => document.querySelector('.conv-main') !== null);

    await click(document.querySelector('.conv-main')!);
    await until(() => text().includes('把最近 20 条里时长超过 10 分钟的发我'));

    const box = document.querySelector('.composer textarea') as HTMLTextAreaElement;
    await act(async () => {
      box.value = '再来一条';
      box.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await click(button('发送'));
    await until(() => calls.some((c) => c.path === '/v2/agent/chat'));

    const post = calls.find((c) => c.path === '/v2/agent/chat')!;
    expect(post.body).toEqual({ message: '再来一条', conversation_id: 'c_1' });
    expect(text()).toContain('好，我记下了这条偏好');
  });

  test('动作只读：写明由手机执行，页面上没有领取按钮', async () => {
    server(ownerRoutes({
      [STATUS]: { json: STATUS_RULES },
      [CONVS]: { json: { conversations: [CONV] } },
      [MEMS]: { json: { memories: [] } },
      'GET /v2/agent/conversations/c_1': { json: detail() },
    }));
    mount('/agent');
    await until(() => document.querySelector('.conv-main') !== null);
    await click(document.querySelector('.conv-main')!);
    await until(() => text().includes('这一轮的动作'));

    expect(text()).toContain('待领取');
    expect(text()).toContain('由手机端领取并回报结果');
    // 没有"领取"按钮，也没有发过 claim 请求：代领会把状态推到 running 却没人执行
    const labels = Array.from(document.querySelectorAll('button')).map((b) => b.textContent ?? '');
    expect(labels.some((l) => l.includes('领取'))).toBe(false);
    expect(calls.some((c) => c.path.includes('/claim'))).toBe(false);
  });

  test('偏好记忆：保存与删除', async () => {
    server(ownerRoutes({
      [STATUS]: { json: STATUS_RULES },
      [CONVS]: { json: { conversations: [] } },
      [MEMS]: { json: { memories: [{ key: 'prefer_audio', value: 'true', created_at: 1, updated_at: 1 }] } },
      'PUT /v2/memories/prefer_audio': { json: { key: 'prefer_audio', value: 'false', updated_at: 2 } },
      'DELETE /v2/memories/prefer_audio': {},
    }));
    mount('/agent');
    await until(() => text().includes('prefer_audio'));

    const keys = document.querySelectorAll('.row-actions input');
    await act(async () => {
      (keys[0] as HTMLInputElement).value = 'prefer_audio';
      keys[0]!.dispatchEvent(new Event('input', { bubbles: true }));
      (keys[1] as HTMLInputElement).value = 'false';
      keys[1]!.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await click(button('保存记忆'));
    await until(() => calls.some((c) => c.method === 'PUT'));

    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.path).toBe('/v2/memories/prefer_audio');
    expect(put.body).toEqual({ value: 'false' });

    await click(button('删'));
    await until(() => calls.some((c) => c.method === 'DELETE'));
    expect(calls.find((c) => c.method === 'DELETE')!.path).toBe('/v2/memories/prefer_audio');
  });
});
