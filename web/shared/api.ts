/**
 * GetMedia 接口客户端（同源、不开 CORS）。契约见 docs/contracts/。
 *
 * 会话：网页的会话令牌只存在于 HttpOnly 的 `__Secure-gm_session` Cookie 里，响应体不含令牌，
 * 所以这里的代码**不接触、不保存任何令牌**，也不往 localStorage/sessionStorage 写机密。
 * 写操作（非 GET）一律带自定义头 X-GetMedia-CSRF；服务端同时校验 Origin。
 */
import type { ClientInfo } from './clientinfo';
import { ApiError, NetworkError, statusText } from './errors';

export interface Account { id: string; login_name: string; tier: string }
export interface SessionInfo { expires_at: number; device_id?: string }

export interface LegalDoc { version: number; effective_date: string; sha256: string; text: string }
export interface LegalDocs { user_agreement: LegalDoc; privacy_policy: LegalDoc }
export interface DocConsent { version: number; sha256: string }
export interface ConsentBody { user_agreement: DocConsent; privacy_policy: DocConsent }

export interface LatestRelease { version: string; notes: string; apk: string | null; sha256: string | null }
export interface Config {
  registration_open: boolean;
  invite_required: boolean;
  maintenance: string | null;
  min_version: string | null;
  latest: LatestRelease | null;
  server_time: number;
}

export interface Me {
  id: string;
  login_name: string;
  tier: string;
  tier_expires_at: number | null;
  consent_required: boolean;
  recovery_confirmed: boolean;
  status: string;
}

export interface Device {
  id: string;
  channel: 'android' | 'web';
  device: string;
  app_version: string;
  created_at: number;
  last_used_at: number;
  current: boolean;
}

export interface AuthResult {
  session: SessionInfo;
  account: Account;
  consent_required?: boolean;
  recovery_confirmed?: boolean;
  recovery_code?: string;
}

export interface RegisterBody {
  login_name: string;
  password: string;
  invite_code?: string;
  age_confirmed: boolean;
  consent: ConsentBody;
  client: ClientInfo;
}

export interface ApiOptions {
  base?: string;
  fetch?: typeof fetch;
}

function defaultBase(): string {
  // Vite 的 base 是 /getmedia/，接口在同源的 /getmedia/api
  const b = (import.meta as { env?: { BASE_URL?: string } }).env?.BASE_URL ?? '/';
  return `${b.replace(/\/$/, '')}/api`;
}

function parseRetryAfter(res: Response): number | null {
  const n = Number(res.headers.get('Retry-After'));
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : null;
}

function toApiError(res: Response, data: unknown): ApiError {
  const d = (data && typeof data === 'object' ? data : {}) as Record<string, unknown>;
  const { detail, code, ...extra } = d;
  return new ApiError(
    res.status,
    typeof code === 'string' ? code : `http_${res.status}`,
    typeof detail === 'string' && detail ? detail : statusText(res.status),
    extra,
    parseRetryAfter(res),
  );
}

export function createApi(opts: ApiOptions = {}) {
  const base = (opts.base ?? defaultBase()).replace(/\/$/, '');
  const doFetch: typeof fetch = opts.fetch ?? ((input, init) => fetch(input, init));

  async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (method !== 'GET') headers['X-GetMedia-CSRF'] = '1';
    let payload: BodyInit | undefined;
    if (body instanceof FormData) {
      payload = body; // multipart 的 Content-Type 由浏览器带上 boundary
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    let res: Response;
    try {
      res = await doFetch(base + path, {
        method,
        headers,
        body: payload,
        credentials: 'same-origin',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
      });
    } catch {
      throw new NetworkError();
    }
    if (res.status === 204) return undefined as T;
    let data: unknown = null;
    try {
      const text = await res.text();
      data = text ? JSON.parse(text) : null;
    } catch {
      data = null; // 中间层（如 nginx）的 HTML 错误页：按状态码给文案
    }
    if (!res.ok) throw toApiError(res, data);
    return data as T;
  }

  return {
    config: () => request<Config>('GET', '/v2/config'),
    legal: async (): Promise<LegalDocs> => (await request<{ documents: LegalDocs }>('GET', '/v2/legal')).documents,

    register: (b: RegisterBody) => request<AuthResult>('POST', '/account/register', b),
    login: (b: { login_name: string; password: string; client: ClientInfo; evict_device_id?: string }) =>
      request<AuthResult>('POST', '/account/login', b),
    logout: () => request<void>('POST', '/account/logout'),
    me: () => request<Me>('GET', '/account/me'),
    consent: (c: ConsentBody) => request<void>('POST', '/account/consent', c),
    recoveryAck: () => request<void>('POST', '/account/recovery/ack'),

    devices: async (): Promise<Device[]> => (await request<{ devices: Device[] }>('GET', '/account/devices')).devices,
    revokeDevice: (id: string) => request<void>('DELETE', `/account/devices/${encodeURIComponent(id)}`),
    revokeOthers: () => request<{ revoked: number }>('POST', '/account/devices/revoke-others'),

    changePassword: (old_password: string, new_password: string) =>
      request<void>('POST', '/account/password', { old_password, new_password }),
    recover: (login_name: string, recovery_code: string, new_password: string) =>
      request<{ recovery_code: string }>('POST', '/account/recover', { login_name, recovery_code, new_password }),
    deleteAccount: (password: string) => request<{ delete_at: number }>('DELETE', '/account/me', { password }),

    heartbeat: (client: ClientInfo) => request<void>('POST', '/v2/heartbeat', { client }),
    feedback: (form: FormData) => request<{ id: string }>('POST', '/v2/feedback', form),
  };
}

export type Api = ReturnType<typeof createApi>;

/** 默认实例：基址取自 Vite 的 base。 */
export const api: Api = createApi();
