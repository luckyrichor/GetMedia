import { describe, expect, it } from 'vitest';
import { safeNext, currentPath } from '../public-app/src/router';

describe('safeNext（登录后跳转白名单）', () => {
  it.each(['/account', '/legal/privacy-policy', '/contact'])('接受站内路径 %s', (p) => expect(safeNext(p)).toBe(p));
  it.each([
    '//evil.example', 'https://evil.example', '/\\evil.example', 'javascript:alert(1)', '/../x', '/Account',
    '/a b', '/a?x=1', '/a#x', '', null, undefined, '/%2e%2e/x',
  ])('拒绝 %s', (p) => expect(safeNext(p as string)).toBeNull());
});

describe('currentPath', () => {
  it('去掉前缀与结尾斜杠', () => {
    // 测试环境的 BASE_URL 为 "/"；带 /getmedia 前缀的真实行为由 e2e（真实浏览器 + 构建产物）覆盖
    expect(currentPath({ pathname: '/' })).toBe('/');
    expect(currentPath({ pathname: '/login/' })).toBe('/login');
  });
});
