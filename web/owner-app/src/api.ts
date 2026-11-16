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

/** 交给 `<audio>/<video>/<img>` 直接加载的地址（不是 fetch，所以单独拼）。 */
export const mediaUrl = (trackId: number) => `${BASE}/api/v2/media/${trackId}`;
export const feedbackImageUrl = (id: string, index: number) =>
  `${BASE}/api/v2/feedback/${encodeURIComponent(id)}/images/${index}`;

// ---------------- 反馈收件箱 ----------------
export type FeedbackStatus = 'new' | 'handled' | 'ignored';

export interface FeedbackCounts {
  new: number;
  handled: number;
  ignored: number;
  all: number;
}

export interface FeedbackItem {
  id: string;
  created_at: number;
  /** 提交账号编号的前 8 位；接口不返回登录名 */
  user_ref: string;
  category: string;
  text: string;
  /** 是否留了联系方式（明文只在详情里） */
  contact: boolean;
  image_count: number;
  image_bytes: number;
  status: FeedbackStatus;
  mail_state: string;
  diag: Record<string, string>;
}

export interface FeedbackDetail {
  id: string;
  created_at: number;
  user_ref: string;
  category: string;
  text: string;
  contact: string;
  diag: Record<string, string>;
  images: { index: number; bytes: number }[];
  image_bytes: number;
  status: FeedbackStatus;
  mail_state: string;
}

export interface FeedbackPage {
  items: FeedbackItem[];
  total: number;
  limit: number;
  offset: number;
  has_more: boolean;
  counts: FeedbackCounts;
}

export interface FeedbackQuery {
  /** `all` 与不传等价，服务端只按 new/handled/ignored 过滤 */
  status?: FeedbackStatus | 'all';
  limit?: number;
  offset?: number;
}

// ---------------- 媒体库 ----------------
export type MediaType = 'audio' | 'video';

export interface Track {
  id: number;
  media_type: MediaType;
  platform: string;
  video_id: string;
  page: number;
  title: string;
  uploader: string | null;
  duration_sec: number | null;
  cover_url: string | null;
  source_url: string | null;
  storage: 'metadata' | 'archive' | 'cache';
  bytes: number;
  pinned: boolean;
  deleted: boolean;
  updated_seq: number;
  created_at: number;
  updated_at: number;
  cache_expires_at: number | null;
  has_file: boolean;
}

export const ownerApi = {
  /** 认证探针：匿名与非 owner 在边缘都被回成 404，只有 owner 能拿到 200。 */
  config: () => api.request<OwnerConfig>('GET', '/v2/config/owner'),
  me: () => api.me(),
  logout: () => api.logout(),

  // 反馈收件箱（5c）。注意这条通道跨账号读，只在 owner 级存在。
  feedbackPage: (q: FeedbackQuery = {}) => {
    const status = q.status ?? 'all';
    const limit = q.limit ?? 30;
    const offset = q.offset ?? 0;
    return api.request<FeedbackPage>('GET', `/v2/feedback?status=${status}&limit=${limit}&offset=${offset}`);
  },
  feedbackDetail: (id: string) => api.request<FeedbackDetail>('GET', `/v2/feedback/${encodeURIComponent(id)}`),
  feedbackStatus: (id: string, status: FeedbackStatus) =>
    api.request<{ id: string; status: FeedbackStatus }>('POST', `/v2/feedback/${encodeURIComponent(id)}/status`, { status }),

  // 媒体库（5c）：列表与单条都按当前登录账号取，不跨账号。
  // 服务端按 updated_seq 递增翻页，这里顺着游标走完，页面再按时间倒序展示。
  tracks: async (): Promise<Track[]> => {
    const all: Track[] = [];
    let cursor = 0;
    for (let page = 0; page < 20; page++) {
      const out = await api.request<{ items: Track[]; count: number }>(
        'GET',
        `/v2/tracks?since_seq=${cursor}&limit=500`,
      );
      all.push(...out.items);
      if (out.items.length < 500) break;
      const last = out.items[out.items.length - 1];
      if (!last) break;
      cursor = last.updated_seq;
    }
    return all;
  },
  track: (id: number) => api.request<Track>('GET', `/v2/tracks/${id}`),
  renameTrack: (id: number, title: string) => api.request<Track>('PATCH', `/v2/tracks/${id}`, { title }),
  dropFile: (id: number) => api.request<Track>('DELETE', `/v2/tracks/${id}/file`),
  deleteTrack: (id: number) =>
    api.request<{ ok: boolean; id: number; deleted: boolean }>('DELETE', `/v2/tracks/${id}`),
};

export type { Me };
