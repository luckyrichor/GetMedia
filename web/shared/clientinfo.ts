/** 注册/登录/心跳携带的 `client`（契约 conventions.md）。只取粗粒度信息，不含浏览器精确版本号。 */

// 与 web/package.json 的 version 保持一致（tests/clientinfo.test.ts 校验）。
export const APP_VERSION = '0.1.0';

export interface ClientInfo {
  channel: 'web';
  app_version: string;
  os: string;
  device: string;
}

function osName(ua: string): string {
  if (/Android/i.test(ua)) return 'Android';
  if (/iPhone|iPad|iPod/i.test(ua)) return 'iOS';
  if (/Windows/i.test(ua)) return 'Windows';
  if (/Mac OS X|Macintosh/i.test(ua)) return 'macOS';
  if (/CrOS/i.test(ua)) return 'ChromeOS';
  if (/Linux/i.test(ua)) return 'Linux';
  return '未知系统';
}

function browserName(ua: string): string {
  if (/Edg(e|A|iOS)?\//.test(ua)) return 'Edge';
  if (/OPR\/|Opera/.test(ua)) return 'Opera';
  if (/Firefox\/|FxiOS\//.test(ua)) return 'Firefox';
  if (/Chrome\/|CriOS\//.test(ua)) return 'Chrome';
  if (/Safari\//.test(ua)) return 'Safari';
  return '浏览器';
}

/** 设备列表里显示的名字，例如「Chrome（Windows）」。 */
export function describeBrowser(ua: string): { device: string; os: string } {
  const os = osName(ua);
  return { device: `${browserName(ua)}（${os}）`, os };
}

export function clientInfo(nav: { userAgent: string } = navigator): ClientInfo {
  const { device, os } = describeBrowser(nav.userAgent);
  return { channel: 'web', app_version: APP_VERSION, os, device };
}
