/** 5c：反馈收件箱。 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  button, calls, callKeys, cleanup, click, mount, ownerRoutes, server, text, until,
} from './harness';

const COUNTS = { new: 2, handled: 1, ignored: 0, all: 3 };

function item(n: number, over: Record<string, unknown> = {}) {
  return {
    id: `fb-000${n}-aaaa-bbbb`,
    created_at: 1_800_000_000 - n * 3600,
    user_ref: `acct00${n}`,
    category: 'bug',
    text: `第 ${n} 条反馈：列表不刷新`,
    contact: false,
    image_count: 0,
    image_bytes: 0,
    status: 'new' as const,
    mail_state: 'sent',
    diag: { app_version: '0.1.0', channel: 'android' },
    ...over,
  };
}

function page(items: unknown[], over: Record<string, unknown> = {}) {
  return { json: { items, total: items.length, limit: 20, offset: 0, has_more: false, counts: COUNTS, ...over } };
}

const LIST = 'GET /v2/feedback?status=all&limit=20&offset=0';

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('反馈收件箱', () => {
  test('列表：按状态给计数，正文可见，联系方式先不出现', async () => {
    server(ownerRoutes({
      [LIST]: page([
        item(1, { contact: true }),
        item(2, { status: 'handled', category: 'suggestion', image_count: 2, mail_state: 'pending' }),
      ]),
    }));
    mount('/feedback');
    await until(() => text().includes('第 1 条反馈'));

    expect(text()).toContain('第 2 条反馈');
    expect(text()).toContain('已留');       // 只是"有没有"的信号
    expect(text()).not.toContain('@example');        // 明文只在详情里
    expect(text()).toContain('图片 2 张');
    expect(text()).toContain('邮件待发');
    for (const f of ['全部', '待处理', '已处理', '已忽略']) expect(text()).toContain(f);
    // 截图/正文之外还给了版本与渠道，便于判断"是哪个包的问题"
    expect(text()).toContain('v0.1.0');
  });

  test('详情：点开才请求单条，显示正文、诊断与图片序号地址', async () => {
    const it = item(1, { contact: true, image_count: 1, image_bytes: 2048 });
    server(ownerRoutes({
      [LIST]: page([it]),
      [`GET /v2/feedback/${it.id}`]: {
        json: {
          ...it,
          contact: 'someone@example.invalid',
          images: [{ index: 0, bytes: 2048 }],
          diag: { app_version: '0.1.0', channel: 'android', device: 'Pixel 7', last_error_code: 'E_NET' },
        },
      },
    }));
    mount('/feedback');
    await until(() => text().includes('第 1 条反馈'));
    expect(callKeys()).not.toContain(`GET /v2/feedback/${it.id}`);

    await click(document.querySelector('.feed-head')!);
    await until(() => text().includes('someone@example.invalid'));

    expect(callKeys()).toContain(`GET /v2/feedback/${it.id}`);
    expect(text()).toContain('Pixel 7');
    expect(text()).toContain('E_NET');
    const img = document.querySelector('.thumbs img') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe(`/api/v2/feedback/${it.id}/images/0`);
    expect(text()).toContain('共 2.0 KB');
  });

  test('改状态：提交 handled，然后从服务端重取列表（不本地猜计数）', async () => {
    const it = item(1);
    let round = 0;
    server(ownerRoutes({
      [LIST]: () => (round++ === 0 ? page([it]) : page([{ ...it, status: 'handled' }], { counts: { new: 1, handled: 1, ignored: 0, all: 2 } })),
      [`GET /v2/feedback/${it.id}`]: { json: { ...it, contact: '', images: [], diag: {} } },
      [`POST /v2/feedback/${it.id}/status`]: { json: { id: it.id, status: 'handled' } },
    }));
    mount('/feedback');
    await until(() => text().includes('第 1 条反馈'));
    await click(document.querySelector('.feed-head')!);
    await until(() => text().includes('内容'));

    await click(button('标记已处理'));
    await until(() => callKeys().filter((k) => k === LIST).length === 2);

    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.path).toBe(`/v2/feedback/${it.id}/status`);
    expect(post.body).toEqual({ status: 'handled' });
    // 改完关闭详情，列表回到第一页
    expect(document.querySelector('.feed-body')).toBeNull();
    expect(text()).toContain('已处理');
  });

  test('空收件箱：说清楚是"没人提交"，不装成加载失败', async () => {
    server(ownerRoutes({ [LIST]: page([]) }));
    mount('/feedback');
    await until(() => text().includes('这个筛选下没有反馈'));
    expect(text()).toContain('还没有人提交过反馈');
  });

  test('读取失败：给原因与重试，重试成功后正常渲染', async () => {
    let n = 0;
    server(ownerRoutes({
      [LIST]: () => (n++ === 0
        ? { status: 503, json: { detail: '服务暂时不可用', code: 'unavailable' } }
        : page([item(1)])),
    }));
    mount('/feedback');
    await until(() => text().includes('服务暂时不可用'));
    await click(button('重试'));
    await until(() => text().includes('第 1 条反馈'));
  });

  test('分页：加载更多带 offset，并显示剩余条数', async () => {
    server(ownerRoutes({
      [LIST]: page([item(1)], { total: 30, has_more: true }),
      'GET /v2/feedback?status=all&limit=20&offset=1': page([item(2)], { total: 30, offset: 1, has_more: false }),
    }));
    mount('/feedback');
    await until(() => text().includes('第 1 条反馈'));
    expect(text()).toContain('还有 29 条');
    await click(button('加载更多（还有 29 条）'));
    await until(() => text().includes('第 2 条反馈'));
    expect(callKeys()).toContain('GET /v2/feedback?status=all&limit=20&offset=1');
    expect(text()).toContain('已显示全部 30 条');
  });

  test('筛选：类别与邮件状态等条件拼进请求参数', async () => {
    const filtered = 'GET /v2/feedback?status=all&limit=20&offset=0&category=suggestion&mail_state=pending';
    server(ownerRoutes({
      [LIST]: page([item(1)]),
      [filtered]: page([item(2, { category: 'suggestion', mail_state: 'pending' })]),
    }));
    mount('/feedback');
    await until(() => text().includes('第 1 条反馈'));

    // 选「建议」类别 + 「邮件待发」状态，触发带参数的重新请求
    await click(document.querySelector('[aria-label="筛选：类别"]')!);
    await click(Array.from(document.querySelectorAll('.select-opt')).find((o) => o.textContent?.includes('建议'))!);
    await until(() => callKeys().includes('GET /v2/feedback?status=all&limit=20&offset=0&category=suggestion'));

    await click(document.querySelector('[aria-label="筛选：邮件状态"]')!);
    await click(Array.from(document.querySelectorAll('.select-opt')).find((o) => o.textContent?.includes('邮件待发'))!);
    await until(() => callKeys().includes(filtered));
    expect(text()).toContain('第 2 条反馈');
  });
});
