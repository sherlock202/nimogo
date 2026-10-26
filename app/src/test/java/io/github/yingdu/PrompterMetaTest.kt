package io.github.yingdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrompterMetaTest {
    @Test fun utf8Length() {
        assertEquals(0L, PrompterMeta.utf8Len("你好", 0))
        assertEquals(3L, PrompterMeta.utf8Len("你好", 1))
        assertEquals(8L, PrompterMeta.utf8Len("ab你好", 4))
        assertEquals("a😀b".toByteArray(Charsets.UTF_8).size.toLong(), PrompterMeta.utf8Len("a😀b", 4))
        assertEquals("é".toByteArray(Charsets.UTF_8).size.toLong(), PrompterMeta.utf8Len("é", 1))
    }

    @Test fun modes() {
        assertNull(PrompterMeta.of(0, 10, 100, 2, 5, 40))
        assertArrayEquals(longArrayOf(10, 100, 0), PrompterMeta.of(1, 10, 100, 2, 5, 40))
        assertArrayEquals(longArrayOf(0, 2, 5), PrompterMeta.of(2, 10, 100, 2, 5, 40))
        assertArrayEquals(longArrayOf(0, 40, 100), PrompterMeta.of(3, 10, 100, 2, 5, 40))
    }

    @Test fun headerBytes() {
        // 默认和原来一样：a、b、c 都是 0
        val d = NimoFrameCodec.prompterTextContent("ab")
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 'a'.code.toByte(), 'b'.code.toByte(), 0), d)
        val m = NimoFrameCodec.prompterTextContent("ab", 0x01020304, 0x0A0B0C0D, 0x0506)
        assertArrayEquals(byteArrayOf(4, 3, 2, 1, 2, 0, 0x0D, 0x0C, 0x0B, 0x0A, 6, 5, 0), m.copyOfRange(0, 13))
    }
}
