/** 邀请码入口的显示条件：free 与「有时限的完整版」才有，永久完整版不给。 */
import { describe, expect, test } from 'vitest';
import { canRedeemInvite } from '@shared/api';

describe('canRedeemInvite', () => {
  test('free 可以兑换（升级 6 个月）', () => {
    expect(canRedeemInvite('free', null)).toBe(true);
    expect(canRedeemInvite('free', undefined)).toBe(true);
  });

  test('有时限的完整版可以兑换（续期）；已过期但库里还没刷成 free 的也让续', () => {
    expect(canRedeemInvite('full', 1_800_000_000)).toBe(true);
    expect(canRedeemInvite('full', 1_000)).toBe(true);
  });

  test('永久完整版不给入口：兑换一次就会从"永久"缩水成"6 个月后到期"', () => {
    expect(canRedeemInvite('full', null)).toBe(false);
    expect(canRedeemInvite('full', undefined)).toBe(false);
  });

  test('admin / 旧档位 / 空值都不给', () => {
    for (const tier of ['admin', 'owner', '', null, undefined]) {
      expect(canRedeemInvite(tier, null)).toBe(false);
    }
  });
});
