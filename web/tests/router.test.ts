import { describe, expect, it } from 'vitest';
import { isOwnerPath, safeNext, currentPath } from '../public-app/src/router';

describe('isOwnerPath（管理台路径识别）', () => {
  it.each(['/owner', '/owner/', '/owner/creators'])('识别 %s', (p) => expect(isOwnerPath(p)).toBe(true));
  it.each(['/owners', '/ownerx', '/', '/account', '', null, undefined])('不误判 %s', (p) => expect(isOwnerPath(p as string)).toBe(false));
  it('safeNext 允许 /owner/（登录后回跳要用）', () => {
    expect(safeNext('/owner/')).toBe('/owner/');
  });
});

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
