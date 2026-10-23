package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class LicenseTest {
    /** 和作者的签发脚本一样签发：DER 签名拆成 r、s 各 32 字节。 */
    private fun issue(priv: java.security.PrivateKey, device: String): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv); s.update(License.payload(device).toByteArray(Charsets.UTF_8))
        val der = s.sign()
        var i = 2
        fun next(): ByteArray { val n = der[i + 1].toInt(); val v = der.copyOfRange(i + 2, i + 2 + n); i += 2 + n; return v }
        fun fix(v: ByteArray) = ByteArray(32).also { o -> val t = if (v.size > 32) v.copyOfRange(v.size - 32, v.size) else v; System.arraycopy(t, 0, o, 32 - t.size, t.size) }
        val r = fix(next()); val sv = fix(next())
        return License.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(r + sv)
    }

    @Test fun deviceCodeShape() {
        val c = License.deviceCode("9774d56d682e549c")
        assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}").matches(c))
        assertEquals(c, License.deviceCode("9774d56d682e549c"))
        assertFalse(c == License.deviceCode("9774d56d682e549d"))
        assertEquals("ABCDEFGH", License.normalize(" abcd-efgh "))
    }

    @Test fun signAndVerify() {
        val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        val pub = Base64.getEncoder().encodeToString(kp.public.encoded)
        repeat(20) {   // r、s 有时首字节 ≥ 0x80、有时有前导 0：多签几次把各种情况都走到
            val key = issue(kp.private, "ABCD-EFGH")
            assertTrue(License.verify(key, "abcd efgh", pub))
            assertTrue(License.verify(" " + key.lowercase().replaceFirst("ng1-", "NG1-").let { key } + "\n", "ABCD-EFGH", pub))
            assertFalse(License.verify(key, "ABCD-EFGJ", pub))
        }
        assertFalse(License.verify("NG1-abc", "ABCD-EFGH", pub))
        assertFalse(License.verify("", "ABCD-EFGH", pub))
        // app 里的公钥验证不了别的私钥签的
        assertFalse(License.verify(issue(kp.private, "ABCD-EFGH"), "ABCD-EFGH"))
    }
}
