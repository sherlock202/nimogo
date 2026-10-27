package io.github.yingdu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun cleansLlmTranslation() {
        assertEquals("有经验吗？", AssistAi.cleanTr("「有经验吗？」"))
        assertEquals("有经验吗？", AssistAi.cleanTr("译文：有经验吗？\n"))
        assertEquals("用 Kubernetes 做过 PoC 吗", AssistAi.cleanTr("**用 Kubernetes 做过 PoC 吗**"))
        assertEquals("「A」和「B」", AssistAi.cleanTr("「A」和「B」"))   // 中间还有引号：不是整句加的，不去
    }

    @Test fun autoLanguageAsrPrompt() {
        // 自动语言：要有提示词（不然 Whisper 会套上默认的中文提示词，把日语写成中文）
        assertTrue(AssistAi.asrPrompt(AssistScene.DEFAULTS[1].copy(lang = "auto"))!!.contains("日本語"))
        assertNull(AssistAi.asrPrompt(AssistScene.DEFAULTS[1].copy(lang = "ja", terms = "")))
    }

    @Test fun llmTranslationPrompt() {
        val p = AssistAi.trSystem("日语", "中文", listOf("PoC", "UAT"))
        assertTrue(p.contains("日语") && p.contains("中文") && p.contains("PoC、UAT"))
        assertFalse(AssistAi.trSystem("英语", "中文", emptyList()).contains("专业名词："))
    }

    @Test fun transcriptWithTranslation() {
        val ls = listOf(AssistLine(0L, false, "経験はありますか").apply { tr = "有经验吗" }, AssistLine(0L, true, "有的"))
        assertEquals("对方：経験はありますか\n我：有的", AssistAi.transcript(ls))
        assertEquals("对方：経験はありますか\n　（译）有经验吗\n我：有的", AssistAi.transcript(ls, withTr = true))
    }
}
