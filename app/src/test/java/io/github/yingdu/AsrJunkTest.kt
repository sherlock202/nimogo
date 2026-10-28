package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrJunkTest {
    @Test fun knownHallucinations() {
        assertTrue(SpeechToText.isHallucination("ご視聴ありがとうございました"))
        assertTrue(SpeechToText.isHallucination("ご視聴ありがとうございました。"))
        assertTrue(SpeechToText.isHallucination("Thank you for watching!"))
        assertTrue(SpeechToText.isHallucination("请不吝点赞 订阅 转发 打赏支持明镜与点点栏目"))
        assertTrue(SpeechToText.isHallucination("谢谢观看"))
        assertFalse(SpeechToText.isHallucination("ありがとうございました"))       // 真的道谢不能丢
        assertFalse(SpeechToText.isHallucination("前職では何を担当されましたか"))
        assertFalse(SpeechToText.isHallucination("。"))
    }

    @Test fun whisperVerboseDropsNoiseSegments() {
        val body = """{"text":"x","segments":[
            {"text":"こんにちは。","no_speech_prob":0.1,"avg_logprob":-0.3,"compression_ratio":1.1},
            {"text":"ご視聴ありがとうございました","no_speech_prob":0.9,"avg_logprob":-0.2,"compression_ratio":1.0},
            {"text":"よろしくお願いします。","no_speech_prob":0.6,"avg_logprob":-0.4,"compression_ratio":1.2}]}"""
        assertEquals("こんにちは。よろしくお願いします。", SpeechToText.parseText(body))
        // 全是噪音
        assertEquals("", SpeechToText.parseText("""{"text":"ご視聴ありがとうございました","segments":[{"text":"ご視聴ありがとうございました","no_speech_prob":0.3,"avg_logprob":-0.2,"compression_ratio":1.0}]}"""))
        // 没有分段（普通 json）照旧
        assertEquals("今天天气不错", SpeechToText.parseText("{\"text\":\"<|zh|><|NEUTRAL|><|Speech|>今天天气不错 \"}"))
        assertTrue(SpeechToText.isNoise(0.95, -0.1, 1.0))
        assertTrue(SpeechToText.isNoise(0.6, -1.2, 1.0))
        assertTrue(SpeechToText.isNoise(0.0, -0.1, 3.0))
        assertFalse(SpeechToText.isNoise(0.6, -0.4, 1.2))
    }

    @Test fun whisperAsksVerbose() {
        assertTrue(String(SpeechToText.multipart("B", "whisper-large-v3", "ja", "a.wav", "audio/wav", ByteArray(0))).contains("verbose_json"))
        assertFalse(String(SpeechToText.multipart("B", "FunAudioLLM/SenseVoiceSmall", "zh", "a.wav", "audio/wav", ByteArray(0))).contains("verbose_json"))
    }

    @Test fun assistDropsRepeatsAndJunk() {
        val ls = listOf(AssistLine(1_000L, false, "すみません、もう一度お願いします"))
        assertTrue(AssistTranscript.junk("すみません、もう一度お願いします。", ls, 5_000L))     // 噪音反复触发同一句
        assertFalse(AssistTranscript.junk("すみません、もう一度お願いします。", ls, 60_000L))   // 隔得久：照常
        assertTrue(AssistTranscript.junk("ご視聴ありがとうございました", emptyList(), 0L))
        assertFalse(AssistTranscript.junk("はい", ls, 2_000L))
    }
}
