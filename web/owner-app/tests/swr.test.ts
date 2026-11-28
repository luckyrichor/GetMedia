/** 列表缓存（`src/swr.ts`）：并发合并、失败不留缓存、清空后不命中、sessionStorage 那一份。 */
import { afterEach, describe, expect, test } from 'vitest';
import { clearCache, drop, loadOnce, peek } from '../src/swr';

const SS = (key: string) => sessionStorage.getItem(`getmedia.cache.v1.${key}`);

afterEach(() => clearCache());

describe('列表缓存', () => {
  test('同一个 key 并发只发一次请求，结果进缓存', async () => {
    let n = 0;
    const fn = () => { n += 1; return Promise.resolve({ v: n }); };
    const [a, b] = await Promise.all([loadOnce('k', fn), loadOnce('k', fn)]);
    expect(n).toBe(1);
    expect(a).toEqual({ v: 1 });
    expect(b).toEqual({ v: 1 });
    expect(peek('k')).toEqual({ v: 1 });
  });

  test('请求完成后同一个 key 会重新发（不是永久缓存）', async () => {
    let n = 0;
    const fn = () => { n += 1; return Promise.resolve(n); };
    expect(await loadOnce('k', fn)).toBe(1);
    expect(await loadOnce('k', fn)).toBe(2);
    expect(peek('k')).toBe(2);
  });

  test('失败不写缓存，也不留 in-flight，下次会重试', async () => {
    let n = 0;
    const bad = () => { n += 1; return Promise.reject(new Error('上游 502')); };
    await expect(loadOnce('bad', bad)).rejects.toThrow('上游 502');
    await expect(loadOnce('bad', bad)).rejects.toThrow('上游 502');
    expect(n).toBe(2);
    expect(peek('bad')).toBeUndefined();
  });

  test('drop 之后不命中，clearCache 清空全部', async () => {
    await loadOnce('a', () => Promise.resolve(1));
    await loadOnce('b', () => Promise.resolve(2));
    drop('a');
    expect(peek('a')).toBeUndefined();
    expect(peek('b')).toBe(2);
    clearCache();
    expect(peek('b')).toBeUndefined();
  });

  test('写缓存时同步存一份到 sessionStorage', async () => {
    await loadOnce('k', () => Promise.resolve({ v: 1 }));
    expect(SS('k')).toBe('{"v":1}');
  });

  test('刷新之后（内存空了、sessionStorage 还在）能直接读回来', () => {
    // 模拟上一次会话留下的那份：只往 sessionStorage 写，不经过 loadOnce
    sessionStorage.setItem('getmedia.cache.v1.tracks', JSON.stringify([{ id: 7, title: '上次的曲目' }]));
    expect(peek<{ id: number; title: string }[]>('tracks')).toEqual([{ id: 7, title: '上次的曲目' }]);
    // 读一次就回填内存：之后即使 sessionStorage 没了也还在
    sessionStorage.removeItem('getmedia.cache.v1.tracks');
    expect(peek<{ id: number }[]>('tracks')).toEqual([{ id: 7, title: '上次的曲目' }]);
  });

  test('sessionStorage 里是坏数据时当作没有，不抛错', () => {
    sessionStorage.setItem('getmedia.cache.v1.broken', '{不是 JSON');
    expect(peek('broken')).toBeUndefined();
  });

  test('drop 与 clearCache 都会把 sessionStorage 那份一起清掉', async () => {
    await loadOnce('a', () => Promise.resolve(1));
    await loadOnce('b', () => Promise.resolve(2));
    expect(SS('a')).toBe('1');
    drop('a');
    expect(SS('a')).toBeNull();
    expect(SS('b')).toBe('2');
    clearCache();
    expect(SS('b')).toBeNull();
  });
});
