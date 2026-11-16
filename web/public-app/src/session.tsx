/**
 * 登录状态与站点配置。
 *
 * 会话令牌在 HttpOnly Cookie 里，页面代码拿不到，所以"是否已登录"只能问服务端（GET /account/me）。
 * 新注册/找回得到的恢复码只存在内存里（pendingRecovery），刷新页面即丢失，**从不写入任何存储**。
 */
import { createContext, type ComponentChildren } from 'preact';
import { useCallback, useContext, useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { api, type Config, type Me } from '@shared/api';
import { clientInfo } from '@shared/clientinfo';
import { ApiError, isApiError, messageFor } from '@shared/errors';
import { prefs } from './hooks';

export type SessionState = { status: 'loading' } | { status: 'anon' } | { status: 'authed'; me: Me };

export interface SessionCtx {
  state: SessionState;
  config: Config | null;
  configError: boolean;
  /** 重新向服务端确认登录状态；返回最新账号信息，未登录返回 null */
  refresh(): Promise<Me | null>;
  logout(): Promise<void>;
  /** 把异常转成可展示的文案；若是"会话失效"则同时回到未登录状态 */
  fail(e: unknown): string;
  /** 刚生成、尚未确认保存的恢复码；绑定登录名，只给对应账号看。仅在内存里。 */
  pendingRecovery: PendingRecovery | null;
  setPendingRecovery(v: PendingRecovery | null): void;
  /** 跳转后在下一个页面显示一次的提示（例如注销成功） */
  flash: Flash | null;
  setFlash(v: Flash | null): void;
}

export interface PendingRecovery { code: string; loginName: string }
export interface Flash { kind: 'ok' | 'info' | 'warn'; text: string }

const Ctx = createContext<SessionCtx | null>(null);

export function useSession(): SessionCtx {
  const v = useContext(Ctx);
  if (!v) throw new Error('SessionProvider 缺失');
  return v;
}

const today = () => new Date().toISOString().slice(0, 10);

export function SessionProvider({ children }: { children: ComponentChildren }) {
  const [state, setState] = useState<SessionState>({ status: 'loading' });
  const [config, setConfig] = useState<Config | null>(null);
  const [configError, setConfigError] = useState(false);
  const [pendingRecovery, setPendingRecovery] = useState<PendingRecovery | null>(null);
  const [flash, setFlash] = useState<Flash | null>(null);
  const beat = useRef(false);

  const refresh = useCallback(async (): Promise<Me | null> => {
    try {
      const me = await api.me();
      setState({ status: 'authed', me });
      return me;
    } catch (e) {
      if (isApiError(e) && e.status === 401) {
        setState({ status: 'anon' });
        return null;
      }
      // 网络/服务异常：不能断定已退出，保持原状；首次加载时按未登录处理，让用户能看到公开页面
      setState((s) => (s.status === 'loading' ? { status: 'anon' } : s));
      return null;
    }
  }, []);

  useEffect(() => {
    void refresh();
    api.config().then(
      (c) => setConfig(c),
      () => setConfigError(true),
    );
  }, [refresh]);

  // 已登录时每天最多上报一次活跃（服务端也会去重）；失败不打扰用户
  useEffect(() => {
    if (state.status !== 'authed' || beat.current || prefs.get('heartbeat-day') === today()) return;
    beat.current = true;
    api.heartbeat(clientInfo()).then(
      () => prefs.set('heartbeat-day', today()),
      () => undefined,
    );
  }, [state.status]);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } catch {
      /* 会话本来就失效也无妨 */
    }
    setPendingRecovery(null);
    setState({ status: 'anon' });
  }, []);

  const fail = useCallback((e: unknown): string => {
    if (e instanceof ApiError && e.status === 401 && e.code === 'unauthenticated') setState({ status: 'anon' });
    return messageFor(e);
  }, []);

  const value = useMemo<SessionCtx>(
    () => ({ state, config, configError, refresh, logout, fail, pendingRecovery, setPendingRecovery, flash, setFlash }),
    [state, config, configError, refresh, logout, fail, pendingRecovery, flash],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
