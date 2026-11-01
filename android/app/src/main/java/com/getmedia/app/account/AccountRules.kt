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
}
