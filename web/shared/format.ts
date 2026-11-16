/** 展示格式化。所有输入都是服务端给的 Unix 秒。 */

export function formatDateTime(epochSeconds: number, locale = 'zh-CN'): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(epochSeconds * 1000));
}

export function formatDate(epochSeconds: number, locale = 'zh-CN'): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(epochSeconds * 1000));
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

/** 反馈编号只展示前 8 位，便于口头/截图沟通。 */
export function shortId(id: string): string {
  return id.slice(0, 8);
}
