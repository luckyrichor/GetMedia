import { useEffect, useState } from 'preact/hooks';

export interface AsyncState<T> {
  loading: boolean;
  data?: T;
  error?: unknown;
  reload: () => void;
}

/** 在依赖变化或手动 reload 时重新执行；组件卸载后丢弃结果。 */
export function useAsync<T>(fn: () => Promise<T>, deps: readonly unknown[] = []): AsyncState<T> {
  const [s, set] = useState<{ loading: boolean; data?: T; error?: unknown }>({ loading: true });
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    set((p) => ({ ...p, loading: true, error: undefined }));
    fn().then(
      (data) => alive && set({ loading: false, data }),
      (error) => alive && set({ loading: false, error }),
    );
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { ...s, reload: () => setTick((n) => n + 1) };
}

/** 非敏感偏好才可以用；读写都可能抛错（隐私模式/被禁用），一律吞掉。键统一加 getmedia: 前缀。 */
export const prefs = {
  get(key: string): string | null {
    try {
      return localStorage.getItem(`getmedia:${key}`);
    } catch {
      return null;
    }
  },
  set(key: string, value: string): void {
    try {
      localStorage.setItem(`getmedia:${key}`, value);
    } catch {
      /* 忽略 */
    }
  },
};
