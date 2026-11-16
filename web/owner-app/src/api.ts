/** owner 通道的接口封装。
 *
 * 同源，但基址与公开页不同：`/getmedia/owner/api`（nginx 用 auth_request 先判断是否 owner）。
 * 边缘对"匿名"和"不是 owner"都回 404（与"路径不存在"完全一致），所以这里不能靠状态码区分原因；
 * 应用里的 `require_tier("owner")` 再校验一次，两层都在。
 *
 * 会话令牌仍然只在 HttpOnly Cookie 里，这里不接触、不保存任何令牌。
 */
import { createApi } from '@shared/api';
import type { Me } from '@shared/api';

export interface OwnerConfig {
  cache_ttl_seconds: number;
  cache_quota_bytes: number;
  archive_quota_bytes: number;
  creator: { max_items: number; max_start: number };
  credential_vault: boolean;
}

/** `/getmedia/owner/` → `/getmedia/owner`；Vite 的 base 保证部署前缀变了也不用改代码。 */
export const BASE = (import.meta.env.BASE_URL ?? '/').replace(/\/$/, '');
/** 公开站根：`/getmedia/`（登录页、退出后的落地页都在那边）。 */
export const PUBLIC_BASE = BASE.replace(/owner$/, '');

export const LOGIN_URL = `${PUBLIC_BASE}login?next=${encodeURIComponent('/owner/')}`;

const api = createApi({ base: `${BASE}/api` });

export const ownerApi = {
  /** 认证探针：匿名与非 owner 在边缘都被回成 404，只有 owner 能拿到 200。 */
  config: () => api.request<OwnerConfig>('GET', '/v2/config/owner'),
  me: () => api.me(),
  logout: () => api.logout(),
};

export type { Me };
