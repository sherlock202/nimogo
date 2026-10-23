package io.github.yingdu

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 私人定制的密钥（NimoGo，2026-10-23）。纯 Kotlin，有单元测试。
 *
 * 普通版的场景助手只有「面试」一个场景；填了作者发的密钥才能用「相亲」等更多场景、自己添加场景。
 *  - 设备码：这台手机的 ANDROID_ID 取哈希后的 8 个字（XXXX-XXXX），客户把它发给作者；
 *  - 密钥：作者用自己的私钥给「设备码 + 版本」签名（ECDSA P-256；签发脚本和私钥放在作者电脑上，不在仓库里），写成 NG1- 开头的一串；
 *  - app 里只有公钥，只能验证、不能生成，所以公钥放在开源的代码里也没关系；私钥在作者电脑上，绝不提交。
 * 密钥只在这台手机上有效。恢复出厂设置、换手机，或者装了用别的签名打包的安装包（ANDROID_ID 跟着签名变），设备码会变，要重新申请。
 */
object License {
    /** 作者的公钥（X.509 SubjectPublicKeyInfo，Base64）。 */
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE4xbRZHRdfH1rQxtSDZMdO2wH2CxYMokOjkj31urmd0btpyJJVYuqQje2HWNesb6fasbbxswFlwyML2Kd9uav6g=="
    const val PLAN = "pro"
    /** 购买密钥找作者的联系方式（只在 app 里显示；仓库里不写明文，免得被搜到）。 */
    val WECHAT_ID: String by lazy { String(Base64.getDecoder().decode("c2hlcmxvY2syMDI="), Charsets.UTF_8) }
    const val WECHAT_NOTE = "加好友请注明 NimoGo"
    const val PREFIX = "NG1-"
    /** Crockford Base32：不用 I、L、O、U，抄写时不容易看错。 */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 设备码（XXXX-XXXX）。 */
    fun deviceCode(androidId: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(("nimogo-device|$androidId").toByteArray(Charsets.UTF_8))
        val s = base32(h.copyOf(5))
        return s.substring(0, 4) + "-" + s.substring(4)
    }

    /** 设备码只看字母和数字（客户抄写时多了空格、横线、小写都没关系）。 */
    fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    /** 签名的内容。 */
    fun payload(device: String, plan: String = PLAN) = "nimogo-license|1|${normalize(device)}|$plan"

    /** 密钥对不对（是不是作者给这台手机签的）。 */
    fun verify(key: String, device: String, publicKey: String = PUBLIC_KEY): Boolean = runCatching {
        val k = key.filterNot { it.isWhitespace() }
        if (!k.startsWith(PREFIX, ignoreCase = true)) return false
        val raw = Base64.getUrlDecoder().decode(k.substring(PREFIX.length).trimEnd('='))
        if (raw.size != 64) return false
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pub)
        sig.update(payload(device).toByteArray(Charsets.UTF_8))
        sig.verify(der(raw))
    }.getOrDefault(false)

    /** 密钥里是 r、s 各 32 字节（短一些），Java 验证要 DER 格式。 */
    fun der(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i].toInt() == 0) i++
            val v = b.copyOfRange(i, b.size)
            return if (v[0].toInt() and 0x80 != 0) byteArrayOf(0) + v else v
        }
        val r = int(raw.copyOfRange(0, 32)); val s = int(raw.copyOfRange(32, 64))
        val body = byteArrayOf(2, r.size.toByte()) + r + byteArrayOf(2, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun base32(b: ByteArray): String {
        val sb = StringBuilder()
        var buf = 0; var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buf shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }
}
