package io.github.yingdu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateTest {
    @Test fun guessJapanese() {
        assertEquals("ja", LangGuess.of("前職ではどんなプロジェクトを担当されましたか"))
        assertEquals("ja", LangGuess.of("Kubernetesの経験はありますか"))
        assertEquals("ja", LangGuess.of("ｶﾀｶﾅ"))
    }

    @Test fun guessEnglish() {
        assertEquals("en", LangGuess.of("Could you tell me about your last project?"))
        assertEquals("en", LangGuess.of("OK"))
    }

    @Test fun guessChineseWithEnglishTerms() {
        assertEquals("zh", LangGuess.of("我们用Kubernetes做PoC"))
        assertEquals("zh", LangGuess.of("你好"))
        // 中文里的「・」「ー」不当成假名
        assertEquals("zh", LangGuess.of("约翰・史密斯"))
    }

    @Test fun kanjiOnlyUsesHint() {
        assertEquals("ja", LangGuess.of("承知", "ja"))
        assertEquals("zh", LangGuess.of("承知", "zh"))
        assertEquals("zh", LangGuess.of("承知"))
    }

    @Test fun guessNothing() {
        assertEquals("", LangGuess.of("123，。"))
        assertEquals("", LangGuess.of(""))
    }

    @Test fun rules() {
        val sup = listOf("zh", "ja", "en")
        assertTrue(TranslateRules.wanted("経験はありますか", "ja", "zh", sup))
        assertFalse(TranslateRules.wanted("你好吗", "zh", "zh", sup))        // 本来就是目标语言
        assertFalse(TranslateRules.wanted("안녕하세요", "ko", "zh", sup))     // 没有语言包
        assertFalse(TranslateRules.wanted("あ。", "ja", "zh", sup))          // 太短
        assertFalse(TranslateRules.wanted("abc", "", "zh", sup))
        assertTrue(TranslateRules.wanted("Yes, OK", "en", "ja", sup))
    }

    @Test fun lineKeepsTranslation() {
        val l = AssistLine(1L, false, "経験はありますか").apply { tr = "有经验吗"; trFrom = "ja" }
        val b = AssistLine.fromJson(JSONObject(l.toJson().toString()))
        assertEquals("有经验吗", b.tr)
        assertEquals("ja", b.trFrom)
        // 没有译文时不写这两个字段
        assertFalse(AssistLine(1L, true, "我").toJson().has("tr"))
        assertEquals("", AssistLine.fromJson(AssistLine(1L, true, "我").toJson()).tr)
    }

    @Test fun sceneKeepsTranslateFlag() {
        val sc = AssistScene.DEFAULTS[1].copy(translate = true)
        assertTrue(AssistScene.fromJson(JSONObject(sc.toJson().toString())).translate)
        // 老数据没有这个字段：默认不翻
        val old = sc.toJson().apply { remove("translate") }
        assertFalse(AssistScene.fromJson(old).translate)
    }

    @Test fun transcriptWithTranslation() {
        val ls = listOf(AssistLine(0L, false, "経験はありますか").apply { tr = "有经验吗" }, AssistLine(0L, true, "有的"))
        assertEquals("对方：経験はありますか\n我：有的", AssistAi.transcript(ls))
        assertEquals("对方：経験はありますか\n　（译）有经验吗\n我：有的", AssistAi.transcript(ls, withTr = true))
    }
}
