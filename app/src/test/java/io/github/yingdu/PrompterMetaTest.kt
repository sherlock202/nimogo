package io.github.yingdu

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PrompterMetaTest {
    @Test fun percent() {
        assertArrayEquals(longArrayOf(0, 37, 100), PrompterMeta.of(37.6))
        assertArrayEquals(longArrayOf(0, 0, 100), PrompterMeta.of(-1.0))
        assertArrayEquals(longArrayOf(0, 100, 100), PrompterMeta.of(100.0))
    }

    @Test fun headerBytes() {
        // 不填时和原来一样：a、b、c 都是 0
        val d = NimoFrameCodec.prompterTextContent("ab")
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 'a'.code.toByte(), 'b'.code.toByte(), 0), d)
        val m = NimoFrameCodec.prompterTextContent("ab", 0x01020304, 0x0A0B0C0D, 0x0506)
        assertArrayEquals(byteArrayOf(4, 3, 2, 1, 2, 0, 0x0D, 0x0C, 0x0B, 0x0A, 6, 5, 0), m.copyOfRange(0, 13))
    }
}
