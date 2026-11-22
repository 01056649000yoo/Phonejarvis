package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ShadeSeedTest {
    private fun seen(pkg: String, title: String) = ShadeSeed.Seen(pkg, pkg.substringAfterLast('.'), title)

    @Test fun mergeCountsPerAppAndKeepsMaxOnReconnect() {
        val first = ShadeSeed.merge(emptyMap(), listOf(seen("a.coupang", "특가"), seen("a.coupang", "배송"), seen("a.kakao", "민준 어머님")))
        assertEquals(2, first["a.coupang"]!!.count)
        assertEquals(listOf("특가", "배송"), first["a.coupang"]!!.samples)
        // 다시 연결될 때 같은 알림을 또 읽어도 두 번 세지 않음(큰 쪽)
        val again = ShadeSeed.merge(first, listOf(seen("a.coupang", "특가")))
        assertEquals(2, again["a.coupang"]!!.count)
        assertEquals(1, again["a.kakao"]!!.count)
    }

    @Test fun secretTitlesAreBlankAndDropped() {
        val m = ShadeSeed.merge(emptyMap(), listOf(seen("a.bank", "")))
        assertEquals(1, m["a.bank"]!!.count)
        assertEquals(emptyList<String>(), m["a.bank"]!!.samples)
    }

    @Test fun combineTakesLargerCountAndAppendsShadeOnlyApps() {
        val recent = listOf(AppStat("a.kakao", "카카오톡", 1, emptyList()))
        val shade = mapOf(
            "a.kakao" to AppStat("a.kakao", "카카오톡", 3, listOf("민준 어머님")),
            "a.game" to AppStat("a.game", "게임", 5, listOf("보상")),
        )
        val out = ShadeSeed.combine(recent, shade)
        assertEquals(listOf("a.kakao", "a.game"), out.map { it.pkg })
        assertEquals(3, out[0].count)
        assertEquals(listOf("민준 어머님"), out[0].samples)
        // 규칙 추천: 게임은 빼기
        assertEquals(false, AppAdvisor.byRules(out).first { it.stat.pkg == "a.game" }.keep)
    }
}
