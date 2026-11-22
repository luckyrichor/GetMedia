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
/** 下载到本机：同一地址加 `download=1`，服务端按附件下发，文件名是条目标题。 */
export const downloadUrl = (trackId: number) => `${mediaUrl(trackId)}?download=1`;
/** 批量任务里的单条作品：服务端用扫描时存下的播放地址转发，过期时 B 站会现解析。 */
export const creatorItemUrl = (jobId: number, videoId: string, fmt?: string) =>
  `${BASE}/api/v2/creators/jobs/${jobId}/items/${encodeURIComponent(videoId)}/file`
  + (fmt ? `?fmt=${encodeURIComponent(fmt)}` : '');
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

/** 批量提取要产出的格式：视频四种、音频四种（source = 平台给什么就存什么）。 */
export type FormatKey = 'source' | 'mp4' | 'mkv' | 'webm' | 'm4a' | 'mp3' | 'flac' | 'wav';

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

// ---------------- 提取（5d） ----------------
export type Storage = 'metadata' | 'archive' | 'cache';
/** 批量/定时允许的存储口径：phone 表示"手机上自己下载，服务器只记账"。 */
export type JobStorage = 'phone' | Storage;

export interface ResolvedMedia {
  platform: string;
  video_id: string;
  title: string;
  duration_sec: number | null;
  uploader: string | null;
  cover_url: string | null;
  source_url: string | null;
  estimated_bytes: number;
}

export type JobState = 'queued' | 'running' | 'done' | 'failed';

export interface ExtractionJob {
  id: number;
  status: JobState;
  media_type: MediaType;
  storage: string;
  input: string;
  platform?: string | null;
  video_id?: string | null;
  track_id?: number | null;
  error_code?: string | null;
  error_detail?: string | null;
  created_at: number;
  started_at?: number | null;
  finished_at?: number | null;
}

// ---------------- 批量提取（5d） ----------------
export type SelectionMode = 'latest_n' | 'date_range' | 'all' | 'from_start';

export interface CreatorProfile {
  platform: string;
  creator_id: string;
  name: string;
  avatar_url: string | null;
  url: string;
  total_videos: number | null;
  follower_count: number | null;
  signature: string | null;
}

export interface CreatorVideo {
  position: number;
  video_id: string;
  title: string;
  source_url: string;
  cover_url: string | null;
  duration_sec: number | null;
  published_at: string | null;
  is_pinned: boolean;
  estimated_bytes: number;
}

export interface CreatorPreview {
  profile: CreatorProfile;
  items: CreatorVideo[];
  total: number | null;
  has_more: boolean;
  fetched_at: string;
  cookie_source: string;
  mode: string;
  estimated_bytes: number;
}

export interface CreatorJob {
  id: number;
  platform: string;
  creator_id: string;
  creator_name: string;
  source_url: string;
  mode: SelectionMode;
  since_ts: number | null;
  until_ts: number | null;
  limit_n: number | null;
  /** 指定起点的起点序号（普通作品，1=最新）与方向 */
  start_n: number | null;
  direction: 'newer' | 'older' | null;
  include_pinned: boolean;
  storage: string;
  media_type: MediaType;
  /** 这一批要产出哪些格式；老任务没有这个字段，按媒体类型给默认值 */
  formats: FormatKey[];
  /** 用户一开始填的内容（抖音号 / B 站号 / 主页链接），任务卡上用来交代「抓的是谁」 */
  source_input: string;
  status: 'pending' | 'running' | 'paused' | 'done' | 'failed' | 'cancelled';
  pause_reason: string | null;
  total: number;
  done: number;
  failed: number;
  estimated_bytes: number;
  /** 已经在服务器上的条目数与占用（缓存 / 存储才有） */
  stored: number;
  store_bytes: number;
  created_at: number;
  updated_at: number;
  started_at: number | null;
  finished_at: number | null;
}

export type ItemState = 'pending' | 'downloading' | 'done' | 'failed' | 'skipped';

export interface CreatorItem {
  id: number;
  video_id: string;
  platform: string;
  page: number;
  title: string;
  duration_sec: number | null;
  published_at: number | null;
  is_pinned: boolean;
  pinned_order: number | null;
  /** 普通作品在发布时间倒序里的全局序号（从 1 起）；置顶作品为 null */
  seq: number | null;
  cover_url: string | null;
  source_url: string;
  download_state: ItemState;
  track_id: number | null;
  error: string | null;
  /** 服务器上有没有这一份；有就点下载直接从服务器读 */
  stored: boolean;
  bytes: number;
  store_error: string | null;
  /** 服务器上每个格式对应哪个文件（{mp4: {path, bytes}}）；只记清单的任务是空的 */
  files: Record<string, { path: string; bytes: number }>;
  /** 条目入库时间（扫描到它的时候） */
  added_at: number | null;
  /** 落盘时间；没落盘就是 null */
  stored_at: number | null;
}

// ---------------- 定时（5d） ----------------
export interface Schedule {
  id: number;
  name: string;
  platform: string;
  input: string;
  mode: SelectionMode;
  since_ts: number | null;
  until_ts: number | null;
  limit_n: number | null;
  interval_minutes: number;
  include_pinned: boolean;
  storage: string;
  media_type: MediaType;
  enabled: boolean;
  next_run: number | null;
  last_run: number | null;
  last_status: string | null;
  last_job_id: number | null;
  created_at: number;
  updated_at: number;
}

export interface ScheduleRun {
  id?: number;
  job_id?: number;
  status?: string;
  started_at?: number | null;
  finished_at?: number | null;
  [k: string]: unknown;
}

// ---------------- Agent（5d） ----------------
export interface AgentStatus {
  engine: 'model' | 'rules';
  models: string[];
  calls_today: number;
  daily_cap: number;
  vault_ready: boolean;
}

export interface Conversation {
  id: string;
  title: string;
  created_at: number;
  updated_at: number;
}

export interface ActionOut {
  id: string;
  conversation_id: string;
  kind: string;
  platform: string;
  input_url: string;
  status: 'pending' | 'running' | 'done' | 'failed';
  mode: string | null;
  result: Record<string, unknown> | null;
  created_at: number;
  claimed_at: number | null;
  finished_at: number | null;
}

export interface AgentMessage {
  id: number;
  role: 'user' | 'assistant';
  content: string;
  meta: { engine?: string; model?: string; reason?: string; action_id?: string };
  created_at: number;
}

export interface ConversationDetail extends Conversation {
  messages: AgentMessage[];
  actions: ActionOut[];
}

export interface Memory {
  key: string;
  value: string;
  created_at: number;
  updated_at: number;
}

export interface ChatReply {
  conversation_id: string;
  reply: string;
  created_at: number;
  engine: string;
  model: string | null;
  reason: string | null;
  action: ActionOut | null;
}

// ---------------- 平台授权（5e） ----------------
export interface PlatformInfo {
  platform: string;
  name: string;
  methods: string[];
  hint: string;
  oauth: string;
}

export interface Binding {
  platform: string;
  bound_at: number;
  source: string;
  status: string;
  label: string | null;
  updated_at: number | null;
}

/** 只有状态与数量，没有 Cookie 内容——服务端本来就不返回。 */
export interface CredentialState {
  platform: string;
  status: string;
  label: string;
  updated_at: number;
  decryptable: boolean;
}

export interface AuthSession {
  session_id: string;
  platform: string;
  method: string;
  status: string;
  expires_at: number;
}

// ---------------- 库维护（5e） ----------------
export interface StorageReport {
  by_storage: Record<string, { tracks: number; bytes: number }>;
  /** 批量提取落在服务器上的文件：与曲库同一个配额池，单独列出只是便于看清构成。 */
  batch: Record<string, { items: number; bytes: number }>;
  /** AI 素材与产物：目录求和，已计入 used.archive，不能与 by_storage 相加。 */
  ai: { files: number; bytes: number };
  quota: { archive: number; cache: number };
  used: { archive: number; cache: number };
}

export interface DiagReport {
  id: number;
  platform: string;
  video_id: string;
  track_id: number | null;
  stage: string;
  error_code: string;
  detail: string;
  client_version: string;
  created_at: number;
}

// ---------------- AI 视频重建（5f） ----------------
export interface AiCapabilities {
  available: boolean;
  modes: string[];
  max_upload_bytes: number;
  /** 素材与产物的保留天数（服务端 GETMEDIA_AI_RETENTION_DAYS）。 */
  retention_days: number;
  /** 服务器库配额：used 已含 AI 自己的占用。 */
  storage: { quota: number; used: number; ai_bytes: number; ai_files: number };
  reason: string | null;
}

export type RunKind = 'analyze' | 'generate';
export type RunState = 'queued' | 'running' | 'done' | 'failed' | 'cancelled';

export interface RecreationRun {
  id: number;
  kind: RunKind;
  status: RunState;
  progress: number | null;
  error_code: string | null;
  created_at: number;
  started_at: number | null;
  finished_at: number | null;
}

export interface Recreation {
  id: number;
  title: string;
  source_track_id: number | null;
  source_path: string | null;
  description: string;
  status: string;
  created_at: number;
  updated_at: number;
  /** 保留期：到点由服务端的 gc 删文件、留记录（status 变 expired）。 */
  expires_at?: number;
  days_left?: number;
  retention_days?: number;
  runs?: RecreationRun[];
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

  // ---------- 提取（5d） ----------
  resolve: (text: string, media_type: MediaType) =>
    api.request<ResolvedMedia>('POST', '/v2/resolve', { text, media_type }),
  extract: (input: string, media_type: MediaType, storage: Storage) =>
    api.request<{ id: number; status: JobState }>('POST', '/v2/extract', { input, media_type, storage }),
  extractionJobs: async (limit = 50): Promise<ExtractionJob[]> =>
    (await api.request<{ jobs: ExtractionJob[] }>('GET', `/v2/extraction-jobs?limit=${limit}`)).jobs,

  // ---------- 批量提取（5d） ----------
  creatorLimits: () => api.request<{ max_items: number; max_start: number }>('GET', '/v2/creators/limits'),
  creatorPreview: (body: {
    url: string; platform?: string | null; mode: SelectionMode;
    n?: number | null; since?: string | null; until?: string | null; include_pinned?: boolean;
    start_n?: number | null; direction?: 'newer' | 'older' | null;
  }) => api.request<CreatorPreview>('POST', '/v2/creators/preview', body),
  createCreatorJob: (body: {
    url: string; platform?: string | null; mode: SelectionMode; n?: number | null;
    since?: string | null; until?: string | null; include_pinned?: boolean;
    start_n?: number | null; direction?: 'newer' | 'older' | null;
    storage: JobStorage; media_type: MediaType; formats?: FormatKey[];
    /** 用户当初填的原始内容（抖音号 / B 站号 / 链接），任务卡上用来交代「抓的是谁」 */
    source_input?: string;
  }) => api.request<CreatorJob>('POST', '/v2/creators/jobs', body),
  creatorJobs: async (): Promise<CreatorJob[]> =>
    (await api.request<{ jobs: CreatorJob[] }>('GET', '/v2/creators/jobs?limit=50')).jobs,
  creatorJob: (id: number) => api.request<CreatorJob>('GET', `/v2/creators/jobs/${id}`),
  /** 条目分页：`limit` 是每页条数（服务端允许 1–500），`offset` 从 0 起。 */
  creatorItems: (id: number, offset = 0, limit = 10, state?: ItemState) => {
    const st = state ? `&state=${state}` : '';
    return api.request<{ items: CreatorItem[]; total: number; limit: number; offset: number }>(
      'GET', `/v2/creators/jobs/${id}/items?limit=${limit}&offset=${offset}${st}`);
  },
  pauseJob: (id: number) => api.request<CreatorJob>('POST', `/v2/creators/jobs/${id}/pause`),
  resumeJob: (id: number) => api.request<CreatorJob>('POST', `/v2/creators/jobs/${id}/resume`),
  cancelJob: (id: number) => api.request<CreatorJob>('POST', `/v2/creators/jobs/${id}/cancel`),
  retryJob: (id: number) => api.request<{ job: CreatorJob }>('POST', `/v2/creators/jobs/${id}/retry`),

  // ---------- 定时（5d） ----------
  schedules: async (): Promise<Schedule[]> =>
    (await api.request<{ schedules: Schedule[] }>('GET', '/v2/schedules')).schedules,
  createSchedule: (body: {
    name: string; platform: string; input: string; mode: SelectionMode; interval_minutes: number;
    n?: number | null; since?: string | null; until?: string | null;
    include_pinned?: boolean; storage: JobStorage; media_type: MediaType;
  }) => api.request<Schedule>('POST', '/v2/schedules', body),
  patchSchedule: (id: number, body: { enabled?: boolean; interval_minutes?: number; name?: string }) =>
    api.request<Schedule>('PATCH', `/v2/schedules/${id}`, body),
  deleteSchedule: (id: number) => api.request<void>('DELETE', `/v2/schedules/${id}`),
  scheduleRuns: async (id: number): Promise<ScheduleRun[]> =>
    (await api.request<{ runs: ScheduleRun[] }>('GET', `/v2/schedules/${id}/runs`)).runs,
  runSchedule: (id: number) => api.request<{ scheduled: boolean }>('POST', `/v2/schedules/${id}/run-once`),

  // ---------- Agent（5d） ----------
  agentStatus: () => api.request<AgentStatus>('GET', '/v2/agent/status'),
  conversations: async (): Promise<Conversation[]> =>
    (await api.request<{ conversations: Conversation[] }>('GET', '/v2/agent/conversations')).conversations,
  conversation: (id: string) =>
    api.request<ConversationDetail>('GET', `/v2/agent/conversations/${encodeURIComponent(id)}`),
  newConversation: (title: string) =>
    api.request<Conversation>('POST', '/v2/agent/conversations', { title }),
  dropConversation: (id: string) =>
    api.request<void>('DELETE', `/v2/agent/conversations/${encodeURIComponent(id)}`),
  chat: (message: string, conversation_id?: string | null) =>
    api.request<ChatReply>('POST', '/v2/agent/chat', conversation_id ? { message, conversation_id } : { message }),
  memories: async (): Promise<Memory[]> =>
    (await api.request<{ memories: Memory[] }>('GET', '/v2/memories')).memories,
  putMemory: (key: string, value: string) =>
    api.request<Memory>('PUT', `/v2/memories/${encodeURIComponent(key)}`, { value }),
  dropMemory: (key: string) => api.request<void>('DELETE', `/v2/memories/${encodeURIComponent(key)}`),

  // ---------- 平台授权（5e） ----------
  // 这里经手的只有"状态"和"会话号"；Cookie 文本只在提交那一刻穿过内存，不落地、不回显。
  platforms: () =>
    api.request<{ platforms: PlatformInfo[]; oauth_configured: boolean }>('GET', '/account/platforms'),
  bindings: () =>
    api.request<{ bindings: Binding[]; credentials: CredentialState[] }>('GET', '/account/platform-bindings'),
  startAuth: (platform: string, method: 'webview' | 'cookie') =>
    api.request<AuthSession>('POST', '/account/platform-auth/start', { platform, method }),
  authStatus: (session_id: string) =>
    api.request<AuthSession>('POST', '/account/platform-auth/status', { session_id }),
  submitCookie: (session_id: string, cookie: string) =>
    api.request<{ platform: string; status: string }>('POST', '/account/platform-auth/cookie', { session_id, cookie }),
  cancelAuth: (session_id: string) =>
    api.request<{ session_id: string; status: string }>('POST', '/account/platform-auth/cancel', { session_id }),
  unbind: (platform: string) =>
    api.request<{ platform: string; unbound: boolean }>(
      'POST', `/account/platform-bindings/${encodeURIComponent(platform)}/unbind`),

  // ---------- 库维护（5e） ----------
  storage: () => api.request<StorageReport>('GET', '/v2/maintenance/storage'),
  pruneCache: () => api.request<{ rows: number; bytes: number }>('POST', '/v2/maintenance/prune-cache'),
  purgeOrphans: () => api.request<{ removed: number }>('POST', '/v2/maintenance/orphans'),
  diagnostics: async (limit = 50): Promise<DiagReport[]> =>
    (await api.request<{ reports: DiagReport[] }>('GET', `/v2/diagnostics/extractions?limit=${limit}`)).reports,

  // ---------- AI 视频重建（5f） ----------
  aiCapabilities: () => api.request<AiCapabilities>('GET', '/v2/recreations/capabilities'),
  recreations: async (): Promise<Recreation[]> =>
    (await api.request<{ recreations: Recreation[] }>('GET', '/v2/recreations')).recreations,
  recreation: (id: number) => api.request<Recreation>('GET', `/v2/recreations/${id}`),
  uploadRecreation: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.request<Recreation>('POST', '/v2/recreations/upload', form);
  },
  recreationFromTrack: (trackId: number) =>
    api.request<Recreation>('POST', `/v2/recreations/from-track/${trackId}`),
  setDescription: (id: number, description: string) =>
    api.request<Recreation>('PUT', `/v2/recreations/${id}/description`, { description }),
  analyze: (id: number) =>
    api.request<{ recreation_id: number; run_id: number; kind: RunKind; status: string }>(
      'POST', `/v2/recreations/${id}/analyze`),
  generate: (id: number) =>
    api.request<{ recreation_id: number; run_id: number; kind: RunKind; status: string }>(
      'POST', `/v2/recreations/${id}/generate`),
  cancelRun: (id: number, rid: number) =>
    api.request<{ run_id: number; status: string }>('POST', `/v2/recreations/${id}/runs/${rid}/cancel`),
  saveOutput: (id: number, rid: number) =>
    api.request<{ saved: boolean; bytes: number }>('POST', `/v2/recreations/${id}/runs/${rid}/save`),
  /** 保留期清理：dry_run 只统计；不给 days 用服务端配置的 14 天。 */
  gcAi: (opts: { days?: number; dryRun?: boolean } = {}) => {
    // 查询串写在 ? 之后（契约测试按 "?" 归一，路径部分必须是字面量）
    const q = new URLSearchParams({ dry_run: String(Boolean(opts.dryRun)) });
    if (opts.days) q.set('days', String(opts.days));
    return api.request<{ projects: number; freed_bytes: number; days: number; dry_run: boolean }>(
      'POST', `/v2/recreations/gc?${q}`);
  },
};

/** AI 产物与源视频：直接交给 `<video>` 播，不经过 fetch。 */
export const recreationVideoUrl = (id: number, rid: number) => `${BASE}/api/v2/recreations/${id}/runs/${rid}/video`;
export const recreationSourceUrl = (id: number) => `${BASE}/api/v2/recreations/${id}/source`;

export type { Me };
