package com.getmedia.app.account

import java.text.Normalizer
import java.util.Locale

/** 客户端侧的输入预检，只为尽早提示；最终以服务端校验为准。规则与 docs/contracts/conventions.md 一致。 */
object AccountRules {
    private val nameRe = Regex("^[a-z0-9_-]{3,32}$")

    /** 返回错误文案；合法返回 null。 */
    fun checkLoginName(raw: String): String? {
        val norm = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (!nameRe.matches(norm)) return "登录名需为 3–32 位英文字母、数字、下划线或连字符"
        if (norm.length >= 7 && norm.all { it.isDigit() }) return "登录名不能是一串数字（容易与手机号混淆）"
        return null
    }

    fun checkPassword(password: String, loginName: String): String? = when {
        password.length < 8 -> "密码至少 8 位"
        password.length > 128 -> "密码不能超过 128 位"
        password.equals(loginName.trim(), ignoreCase = true) -> "密码不能与登录名相同"
        else -> null
    }

    /** 恢复码展示成 4 位一组，便于抄写；输入时去掉空格和连字符。 */
    fun groupRecoveryCode(code: String): String = code.filter { it.isLetterOrDigit() }.chunked(4).joinToString(" ")
    fun cleanRecoveryCode(input: String): String = input.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)

    /**
     * 「我的」页要不要显示邀请码兑换入口。
     *
     * 服务端只允许 free 与**有时限的** full 兑换：
     * - free：兑换后升为完整版 6 个月；
     * - full 且 [expiresAt] 不为 null：在原到期日上顺延 6 个月，等于续期；
     * - full 且 [expiresAt] 为 null：永久完整版，不该出现入口（兑换一次就从"永久"
     *   缩水成"6 个月后到期"，服务端也会直接拒）；
     * - admin：不需要，也不允许。
     *
     * 永久 full 被管理员改成"6 个月"或 free 之后，`tier` / `expiresAt` 一变入口就
     * 自然回来了，不需要额外记状态。
     */
    fun canRedeemInvite(tier: String?, expiresAt: Long?): Boolean = when (tier?.lowercase()) {
        "free" -> true
        "full" -> expiresAt != null
        else -> false
    }
}
