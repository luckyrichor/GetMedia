/** 列表结果的缓存（stale-while-revalidate）：内存一份，sessionStorage 再存一份。
 *
 *  管理台这几个列表接口都是「一次拉全量」，而服务器在境外，一个来回几百毫秒。
 *  每次切页都从零等一遍，切三次就白等三次——这就是「切换页面要加载好几秒」的来源。
 *
 *  这里的取舍：**宁可先显示上一次的数据，再在后台悄悄刷新**，也不要把用户按在骨架上。
 *  数据不会因此变旧太多：页面挂载时一定会重新拉一次，写操作后调用方也会再拉。
 *
 *  为什么要 sessionStorage 那一份：内存缓存只活到页面卸载，**刷新一次就没了**，
 *  刷新后又得等一个完整往返。sessionStorage 的寿命是「这个标签页」——
 *  刷新、前进后退都还在，关掉标签页就清空，正好是我们要的粒度（不会像 localStorage
 *  那样把上周的数据一直留到下周）。
 *
 *  只缓存读结果；不做 TTL（过期判断交给"一定会刷新"这件事）。
 *  键名带版本号：字段结构变了就升版本，老数据自然读不到，不用写迁移。
 */
import { ownerApi } from './api';

/** sessionStorage 键前缀。改数据结构时把 v1 改成 v2，旧缓存自动失效。 */
const SS_PREFIX = 'getmedia.cache.v1.';

const mem = new Map<string, unknown>();
const inflight = new Map<string, Promise<unknown>>();
/** 清缓存时递增：清之前发出去的请求即便晚到，也不再写回缓存（否则测试之间会串数据）。 */
let gen = 0;
let prefetched = false;
/** 预取还没真正发出时的定时器 id：清缓存时要一起取消，
 *  否则上个用例排的预取会在下个用例里跑，把旧数据写进新用例的缓存。 */
let prefetchTimer: number | null = null;
let prefetchIdle: number | null = null;

/** sessionStorage 可能被禁用（隐私模式）或写满（配额），一律当成「没有」处理。 */
function ssGet<T>(key: string): T | undefined {
  try {
    const raw = sessionStorage.getItem(SS_PREFIX + key);
    return raw == null ? undefined : (JSON.parse(raw) as T);
  } catch {
    return undefined;
  }
}

function ssSet(key: string, data: unknown): void {
  try {
    sessionStorage.setItem(SS_PREFIX + key, JSON.stringify(data));
  } catch {
    /* 配额满 / 被禁用：内存那份还在，功能不受影响 */
  }
}

function ssDrop(key: string): void {
  try {
    sessionStorage.removeItem(SS_PREFIX + key);
  } catch {
    /* 忽略 */
  }
}

/** 取上次的结果；没有就是 `undefined`（调用方据此决定要不要显示骨架）。
 *  内存没有时读 sessionStorage 并回填内存——刷新之后第一次进来就走这条。 */
export function peek<T>(key: string): T | undefined {
  if (mem.has(key)) return mem.get(key) as T;
  const fromSs = ssGet<T>(key);
  if (fromSs !== undefined) mem.set(key, fromSs);
  return fromSs;
}

/** 主动作废某个 key（比如刚删完条目，不想让旧列表再闪一下）。 */
export function drop(key: string): void {
  mem.delete(key);
  ssDrop(key);
}

/** 同一个 key 同时只跑一个请求：预取和页面自己拉的时候不会各发一遍。
 *  成功后把结果写进两份缓存，失败只清 in-flight（页面自己决定怎么显示错误）。 */
export function loadOnce<T>(key: string, fn: () => Promise<T>): Promise<T> {
  const cur = inflight.get(key);
  if (cur) return cur as Promise<T>;
  const at = gen;
  const p = fn().then(
    (d) => {
      if (at === gen) {
        mem.set(key, d);
        ssSet(key, d);
      }
      inflight.delete(key);
      return d;
    },
    (e) => {
      inflight.delete(key);
      throw e;
    },
  );
  inflight.set(key, p);
  return p;
}

/** 三个「列表页」的缓存键，页面与预取共用同一份常量，避免两边写错字符串。 */
export const KEYS = {
  tracks: 'tracks',
  extractionJobs: 'extraction-jobs',
  creatorJobs: 'creator-jobs',
} as const;

/** 首屏就绪后空闲时预取这三张表：它们是最常切过去的页面，
 *  提前拉好，第一次点进去也不用等。失败静默——页面自己还会再拉一次。 */
export function prefetchLists(): void {
  if (prefetched) return;
  prefetched = true;
  const at = gen;
  const run = () => {
    // 排队期间清过缓存（测试之间、或用户重试）：这次预取作废
    if (at !== gen) return;
    // 内存里已经有（用户直接落在该页、页面自己拉过了）就不必再拉一遍。
    // 只有 sessionStorage 有不算：那是上一次会话的，得刷新一次才对。
    if (!mem.has(KEYS.tracks)) void loadOnce(KEYS.tracks, () => ownerApi.tracks()).catch(() => {});
    if (!mem.has(KEYS.extractionJobs)) {
      void loadOnce(KEYS.extractionJobs, () => ownerApi.extractionJobs()).catch(() => {});
    }
    if (!mem.has(KEYS.creatorJobs)) {
      void loadOnce(KEYS.creatorJobs, () => ownerApi.creatorJobs()).catch(() => {});
    }
  };
  const w = window as Window & {
    requestIdleCallback?: (cb: () => void, o?: { timeout: number }) => number;
    cancelIdleCallback?: (id: number) => void;
  };
  // 别跟首屏资源抢带宽：空闲了再拉，最多等 1.5s
  if (typeof w.requestIdleCallback === 'function') prefetchIdle = w.requestIdleCallback(run, { timeout: 1500 });
  else prefetchTimer = window.setTimeout(run, 300);
}

/** 仅供测试：清空缓存（内存 + sessionStorage），避免用例之间互相污染。 */
export function clearCache(): void {
  gen += 1;
  mem.clear();
  inflight.clear();
  prefetched = false;
  if (prefetchTimer != null) {
    window.clearTimeout(prefetchTimer);
    prefetchTimer = null;
  }
  const w = window as Window & { cancelIdleCallback?: (id: number) => void };
  if (prefetchIdle != null && typeof w.cancelIdleCallback === 'function') w.cancelIdleCallback(prefetchIdle);
  prefetchIdle = null;
  try {
    const gone: string[] = [];
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const k = sessionStorage.key(i);
      if (k && k.startsWith(SS_PREFIX)) gone.push(k);
    }
    for (const k of gone) sessionStorage.removeItem(k);
  } catch {
    /* 忽略 */
  }
}
