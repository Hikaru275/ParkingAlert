package com.hikaru.parkingalert.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordMatcherTest {

    private val presets = listOf("交警", "触发", "违停", "汽车")

    @Test
    fun `matches any preset keyword by default`() {
        val result = KeywordMatcher.match(
            body = "【交警】您的汽车涉嫌违停，请立即挪车。",
            keywords = presets,
            strictMode = false,
            ignoreMarketing = true,
        )

        assertTrue(result.shouldAlert)
        assertEquals(listOf("交警", "违停", "汽车"), result.matchedKeywords)
        assertFalse(result.ignoredAsMarketing)
    }

    @Test
    fun `strict mode needs two keywords`() {
        val single = KeywordMatcher.match(
            body = "您的汽车今天限行",
            keywords = presets,
            strictMode = true,
            ignoreMarketing = true,
        )
        assertFalse(single.shouldAlert)
        assertEquals(listOf("汽车"), single.matchedKeywords)

        val double = KeywordMatcher.match(
            body = "交警提示：您的汽车违停了",
            keywords = presets,
            strictMode = true,
            ignoreMarketing = true,
        )
        assertTrue(double.shouldAlert)
        assertEquals(listOf("交警", "违停", "汽车"), double.matchedKeywords)
    }

    @Test
    fun `marketing opt-out marker is ignored when filter is on`() {
        val body = "【某汽车广场】周末购车优惠，回T退订。"
        val filtered = KeywordMatcher.match(body, presets, strictMode = false, ignoreMarketing = true)
        assertFalse(filtered.shouldAlert)
        assertTrue(filtered.ignoredAsMarketing)

        val unfiltered = KeywordMatcher.match(body, presets, strictMode = false, ignoreMarketing = false)
        assertTrue(unfiltered.shouldAlert)
    }

    @Test
    fun `full width latin characters are normalised`() {
        assertTrue(
            KeywordMatcher.match(
                body = "回复ＴＤ退订，交警提示挪车",
                keywords = presets,
                strictMode = false,
                ignoreMarketing = false,
            ).shouldAlert
        )
        assertEquals("回复td退订,交警提示挪车", KeywordMatcher.normalize("回复ＴＤ退订， 交警提示挪车"))
    }

    @Test
    fun `whitespace and zero width characters do not break a match`() {
        val result = KeywordMatcher.match(
            body = "交\u200B警 提 示：违 停",
            keywords = presets,
            strictMode = false,
            ignoreMarketing = true,
        )
        assertTrue(result.shouldAlert)
        assertEquals(listOf("交警", "违停"), result.matchedKeywords)
    }

    @Test
    fun `english keywords are case insensitive`() {
        val result = KeywordMatcher.match(
            body = "PARKING violation notice",
            keywords = listOf("parking"),
            strictMode = false,
            ignoreMarketing = true,
        )
        assertTrue(result.shouldAlert)
    }

    @Test
    fun `duplicate keywords are not counted twice in strict mode`() {
        val result = KeywordMatcher.match(
            body = "交警提示",
            keywords = listOf("交警", "交警", " 交警 "),
            strictMode = true,
            ignoreMarketing = true,
        )
        assertFalse(result.shouldAlert)
        assertEquals(listOf("交警"), result.matchedKeywords)
    }

    @Test
    fun `blank keyword list never alerts`() {
        val result = KeywordMatcher.match(
            body = "交警提示：请挪车",
            keywords = listOf(" ", ""),
            strictMode = false,
            ignoreMarketing = true,
        )
        assertFalse(result.shouldAlert)
        assertTrue(result.matchedKeywords.isEmpty())
    }

    @Test
    fun `realistic test message matches three presets`() {
        val body = "【测试】交警提示：您的汽车涉嫌违停，请立即挪车。如有疑问请忽略本短信。"
        val result = KeywordMatcher.match(body, presets, strictMode = false, ignoreMarketing = true)
        assertTrue(result.shouldAlert)
        assertEquals(listOf("交警", "违停", "汽车"), result.matchedKeywords)
    }
}
