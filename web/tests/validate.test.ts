import { describe, expect, test } from 'vitest';
import { cleanRecoveryCode, formatRecoveryCode, loginNameProblem, normalizeLoginName, passwordProblem } from '@shared/validate';

describe('login name', () => {
  test('normalizes like the server (NFKC + lowercase + trim)', () => {
    expect(normalizeLoginName('  ＡＬＩＣＥ_1 ')).toBe('alice_1');
  });
  test.each([
    ['ab', true], ['abc', false], ['a'.repeat(32), false], ['a'.repeat(33), true],
    ['has space', true], ['中文名字', true], ['ok-name_9', false],
    ['13800138000', true], ['138-0013-8000', true], ['123456', false], ['user12345678', false],
  ])('%s → problem=%s', (name, bad) => {
    expect(loginNameProblem(normalizeLoginName(name)) !== null).toBe(bad);
  });
});

describe('password', () => {
  test('length counts characters, not UTF-16 units', () => {
    expect(passwordProblem('1234567', 'bob')).not.toBeNull();
    expect(passwordProblem('12345678', 'bob')).toBeNull();
    expect(passwordProblem('😀'.repeat(8), 'bob')).toBeNull();   // 8 个字符（16 个 UTF-16 单元）
    expect(passwordProblem('x'.repeat(129), 'bob')).not.toBeNull();
  });
  test('must differ from the login name; NUL rejected', () => {
    expect(passwordProblem('Alice_001', 'alice_001')).not.toBeNull();
    expect(passwordProblem('abc\u0000defgh', 'bob')).not.toBeNull();
  });
});

describe('recovery code', () => {
  test('tolerates spaces, dashes and case', () => {
    expect(cleanRecoveryCode(' abcd-efgh ijkl\nmnop ')).toBe('ABCDEFGHIJKLMNOP');
    expect(formatRecoveryCode('abcdefghijklmnop')).toBe('ABCD-EFGH-IJKL-MNOP');
  });
});
