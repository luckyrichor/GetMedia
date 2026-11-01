/** 接口错误与面向用户的文案。服务端的 `detail` 已是中文用户文案，这里只补网络层与非 JSON 响应。 */

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail: string;
  readonly extra: Record<string, unknown>;
  readonly retryAfter: number | null;

  constructor(status: number, code: string, detail: string, extra: Record<string, unknown> = {}, retryAfter: number | null = null) {
    super(detail);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.extra = extra;
    this.retryAfter = retryAfter;
  }
}

/** 请求没有拿到任何 HTTP 响应（断网、被拦截、证书问题等）。 */
export class NetworkError extends Error {
  constructor() {
    super('network');
    this.name = 'NetworkError';
  }
}

export function isApiError(e: unknown, code?: string): e is ApiError {
  return e instanceof ApiError && (code === undefined || e.code === code);
}

// nginx 或其他中间层直接返回的响应没有 JSON 体，按状态码给文案。
const STATUS_TEXT: Record<number, string> = {
  400: '请求格式不正确',
  401: '登录已失效，请重新登录',
  403: '没有权限执行该操作',
  404: '未找到',
  405: '不支持的请求方法',
  413: '提交的内容太大，请减少图片后重试',
  429: '操作太频繁，请稍后再试',
  500: '服务暂时出了点问题，请稍后再试',
  502: '服务暂时不可用，请稍后再试',
  503: '服务维护中，请稍后再试',
  504: '服务响应超时，请稍后再试',
};

export function statusText(status: number): string {
  return STATUS_TEXT[status] ?? `请求失败（状态码 ${status}）`;
}

/** 把任何异常转成可直接展示给用户的一句话；不展示异常原文或内部细节。 */
export function messageFor(e: unknown): string {
  if (e instanceof NetworkError) return '无法连接服务器，请检查网络后重试';
  if (e instanceof ApiError) {
    const base = e.detail || statusText(e.status);
    if (e.status === 429 && e.retryAfter && e.retryAfter > 0) {
      const wait = e.retryAfter >= 90 ? `${Math.ceil(e.retryAfter / 60)} 分钟` : `${e.retryAfter} 秒`;
      return `${base}（约 ${wait}后可重试）`;
    }
    return base;
  }
  return '出现了未预期的错误，请刷新页面后重试';
}
