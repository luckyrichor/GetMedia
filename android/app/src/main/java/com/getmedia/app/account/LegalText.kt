package com.getmedia.app.account

import android.content.Context
import java.security.MessageDigest

/**
 * 协议文本的解析与内置种子。
 * 服务端对协议文件的原始字节做 sha256，并从首部读取「版本：vN　生效日期：…」；
 * 这里必须用同样的规则，客户端展示的文本和同意记录里的哈希才对得上。
 */
object LegalText {
    // 与服务端 legal.py 的 _HEADER 一致；注意 Java 的 \s 不含全角空格，所以显式加 \u3000。
    private val header = Regex("版本：v(\\d+)[\\s\\u3000]*生效日期：(\\d{4})[\\s\\u3000]*年[\\s\\u3000]*(\\d{1,2})[\\s\\u3000]*月[\\s\\u3000]*(\\d{1,2})[\\s\\u3000]*日")

    fun parse(raw: ByteArray): LegalDocument {
        val body = String(raw, Charsets.UTF_8)
        val m = header.find(body.lineSequence().take(5).joinToString("\n")) ?: error("协议首部缺少版本与生效日期")
        val (v, y, mo, d) = m.destructured
        val date = "%04d-%02d-%02d".format(y.toInt(), mo.toInt(), d.toInt())
        return LegalDocument(v.toInt(), date, sha256(raw), body)
    }

    fun sha256(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

    /** APK 内置的协议文本：离线时也能展示；联网后以服务端版本为准。 */
    fun seed(context: Context): LegalBundle {
        fun read(name: String) = context.assets.open("legal/$name").use { it.readBytes() }
        return LegalBundle(parse(read("user-agreement.md")), parse(read("privacy-policy.md")))
    }
}
