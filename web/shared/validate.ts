/**
 * 登录名与密码的前端提示校验。规则与服务端 server/getmedia/names.py 一致，
 * 但**只是体验上的提前提示**：服务端才是权威，前端校验通过不代表一定被接受。
 */

/** NFKC 规范化 + 去首尾空白 + 转小写（与服务端同一规则，全角字母会归一）。 */
export function normalizeLoginName(raw: string): string {
  return raw.normalize('NFKC').trim().toLowerCase();
}

export function loginNameProblem(norm: string): string | null {
  if (!/^[a-z0-9_-]{3,32}$/.test(norm)) return '登录名需为 3–32 位小写字母、数字、下划线或连字符';
  const digits = norm.replace(/[-_]/g, '');
  if (/^\d+$/.test(digits) && digits.length >= 7) return '登录名不能是手机号或纯长数字';
  return null;
}

export function passwordProblem(password: string, normName: string): string | null {
  // 服务端按字符数计长度（Python len），这里用码点数保持一致
  const n = [...password].length;
  if (n < 8 || n > 128) return '密码长度需为 8–128 位';
  if (password.includes('\u0000')) return '密码包含不允许的字符';
  if (normalizeLoginName(password) === normName) return '密码不能与登录名相同';
  return null;
}

/** 恢复码显示用：16 位 base32，4 位一组。输入时容忍空格、连字符与大小写。 */
export function cleanRecoveryCode(raw: string): string {
  return raw.replace(/[\s-]+/g, '').toUpperCase();
}

export function formatRecoveryCode(code: string): string {
  const c = cleanRecoveryCode(code);
  return c.replace(/(.{4})(?=.)/g, '$1-');
}
