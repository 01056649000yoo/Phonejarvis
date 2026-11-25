package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🗓 하루 정리 총정리(2026-11-24 선생님: 하루 500개를 가정해 하루 종일 온 알림을 전체적으로) ·
 * 🕳 요약에서 빠지는 알림(9시 55분 문자) 시험
 */
class TotalDigestTest {
    private val day = SummarySchedule.startOfDay(System.currentTimeMillis())
    private fun at(h: Int, m: Int = 0) = day + h * 3600_000L + m * 60_000L

    // ── 🕳 빠지는 알림 ──
    @Test fun 늦게_저장된_문자도_다음_요약이_맡음() {
        // 21:55:00 미리 준비가 알림 번호 10까지 맡음. 문자는 21:54:50 에 보냈는데 21:55:20 에 도착해 번호 11로 저장
        val cutoff = at(21, 55)
        val sms = NotiItem(11, "com.samsung.android.messaging", "메시지", "학부모", "내일 상담 가능할까요?", cutoff - 10_000L)
        val late = listOf(NotiItem(9, "p", "a", "t", "앞서 맡은 것", cutoff - 60_000L), sms)
        // 예전(시각으로): 지난 요약 시각 다음부터라 빠짐
        assertTrue(Briefings.uncovered(late, cutoff + 1, afterId = 0L).isEmpty())
        // 지금(번호로): 지난 요약이 맡은 10번 다음이라 들어감
        assertEquals(listOf(sms), Briefings.uncovered(late, cutoff + 1, afterId = 10L))
        // 이번 요약이 맡을 번호(uptoId)보다 뒤에 저장된 것은 다음 요약으로
        assertTrue(Briefings.uncovered(late, cutoff + 1, afterId = 10L, uptoId = 10L).isEmpty())
    }

    // ── 방별 하루 기록 ──
    private fun entry(room: String, count: Int, parts: List<RoomDay.Part>, asks: List<RoomDay.Ask> = emptyList(),
                      kind: String = RoomDay.TALK, app: String = "카카오톡", level: Int = Relevance.ME) =
        RoomDay.Entry("$app\u0001$room", app, room, kind, count, parts.firstOrNull()?.from ?: at(9), parts.lastOrNull()?.to ?: at(9), parts, asks, level)

    @Test fun 같은_방은_건수를_더하고_구간을_이어_붙임() {
        val a = entry("6학년방", 30, listOf(RoomDay.Part(at(9), at(10), 30, "김: 학예회 금요일")), listOf(RoomDay.Ask(at(10), "명단 금요일까지 내 달라고 함")))
        val b = entry("6학년방", 20, listOf(RoomDay.Part(at(13), at(14), 20, "박: 리허설 3시")), listOf(RoomDay.Ask(at(14), "명단 금요일까지 내 달라고 함")))
        val m = RoomDay.merge(a, b)
        assertEquals(50, m.count)
        assertEquals(2, m.parts.size)
        assertEquals(1, m.asks.size)                        // 같은 부탁은 한 번(최근 것)
        assertEquals(at(14), m.asks.single().time)
    }

    @Test fun 요약한_방_하나를_기록으로() {
        val conv = BriefingComposer.Conversation("카카오톡", "학부모 김민준", listOf(
            NotiItem(1, "com.kakao.talk", "카카오톡", "학부모 김민준", "선생님 내일 준비물이 뭔가요?", at(16, 10))))
        val r = BriefingComposer.RoomSummary(conv, "내일 준비물을 물음", "내일 준비물 알려 달라고 함", true, byModel = true)
        val e = RoomDay.of(r, RoomDay.TALK, Relevance.ME, at(15))
        assertEquals(1, e.count)
        assertTrue(e.parts.single().asked)
        assertEquals("내일 준비물 알려 달라고 함 (내일 준비물을 물음)", e.asks.single().text)
        val chatter = RoomDay.of(r.copy(point = "", ask = "", needReply = false), RoomDay.TALK, Relevance.JOINED, at(15))
        assertFalse(chatter.hasContent)
    }

    @Test fun 조각은_앞_사람_이름을_이어받음() {
        val segs = DailyDigest.segments(listOf(RoomDay.Part(at(9), at(10), 5, "김선생: 회의 3시 / 장소는 도서실 / 박선생: 알겠습니다")))
        assertEquals(listOf("김선생: 회의 3시", "김선생: 장소는 도서실", "박선생: 알겠습니다"), segs)
        assertEquals("김선생: 회의 3시 / 장소는 도서실", DailyDigest.joinSegs(segs.take(2)))
    }

    @Test fun 구간별_요약_줄은_위_줄에_딸림() {
        val g = attachDetails(listOf("💬 방별 하루 (1곳)", "- 6학년방 (50건): 리허설 3시", "${DailyDigest.DETAIL}09:00~10:00 · 30건: 학예회", "${DailyDigest.DETAIL}13:00~14:00 · 20건: 리허설"))
        assertEquals(2, g.size)
        assertEquals(listOf("09:00~10:00 · 30건: 학예회", "13:00~14:00 · 20건: 리허설"), g[1].second)
        assertFalse(isSectionHead("${DailyDigest.DETAIL}09:00~10:00 · 30건: 학예회"))
    }

    /** 하루 500건: 나를 부른 큰 단톡방 5곳(구간 4개씩) · 보통 방 15곳(3곳은 나에게, 12곳은 내가 속한 방) · 맞장구뿐인 방 15곳 · 그 밖의 방 10곳 · 🙋 12건 · 업무 앱 · 앱 알림 */
    private fun day500(): DailyDigest.Day {
        val big = (1..5).map { i ->
            entry("큰방$i", 60, (0 until 4).map { k ->
                RoomDay.Part(at(8 + k * 3), at(9 + k * 3), 15, "사람$k: 큰방$i 이야기$k / 사람$k: ${k + 1}시 모임 / 다른$k: 좋아요 장소는 강당$k")
            })
        }
        val mid = (1..15).map { i -> entry("보통방$i", 10, listOf(RoomDay.Part(at(12), at(13), 10, "보통방$i 내용")), level = if (i <= 3) Relevance.ME else Relevance.JOINED) }
        val open = (1..10).map { i -> entry("오픈방$i", 8, listOf(RoomDay.Part(at(12), at(13), 8, "오픈방$i 잡담")), level = Relevance.OTHER) }
        val chatter = (1..15).map { i -> entry("잡담방$i", 3, emptyList()) }
        val asks = (1..12).map { i ->
            entry("부탁방$i", 4, listOf(RoomDay.Part(at(15), at(15, i), 4, "부탁방$i 요점", asked = true)), listOf(RoomDay.Ask(at(15, i), "자료$i 내 달라고 함")))
        }
        val work = listOf(entry("학교종이", 6, listOf(RoomDay.Part(at(8), at(8, 30), 6, "설문지 배포")), kind = RoomDay.WORK, app = BriefingComposer.WORK_TITLE))
        val apps = listOf(RoomDay.Entry("📦\u0001쿠팡", "쿠팡", "쿠팡", RoomDay.APP, 40, at(7), at(20)),
            RoomDay.Entry("📦\u0001Strava", "Strava", "Strava", RoomDay.APP, 5, at(7), at(20)))
        val all = big + mid + chatter + open + asks + work + apps
        return DailyDigest.Day(all, listOf("- 10:00 [6학년방] 김: 조퇴 알림"), "", 6, all.sumOf { it.count })
    }

    @Test fun 하루_500건도_빠짐없이_총정리_AI_없이() = runBlocking {
        val d = day500()
        val t = DailyDigest.total(d, emptyMap(), at(22), ask = null)
        assertFalse(t.byAi)
        // 🙋 답할 것은 상한 없이 모두
        (1..12).forEach { assertTrue(t.full, "자료$it 내 달라고 함" in t.full) }
        assertTrue(t.full, "🙋 답할 것 (12)" in t.full)
        // 나에게 온 대화·내가 속한 방은 한 줄씩, 맞장구뿐인 방·그 밖의 방(오픈채팅 등)은 이름과 건수만
        (1..5).forEach { assertTrue(t.full, "- 큰방$it (60건): " in t.full) }
        (1..15).forEach { assertTrue(t.full, "- 보통방$it (10건): 보통방$it 내용" in t.full) }
        assertTrue(t.full, "💬 그 밖의 방 (25곳" in t.full && "잡담방15 3건" in t.full && "오픈방1 8건" in t.full)
        assertFalse(t.full, "- 잡담방1 (3건)" in t.full)
        assertFalse(t.full, "오픈방1 잡담" in t.full)
        // 부탁방 내용은 🙋 에 있으므로 방별 하루에 다시 쓰지 않음
        assertFalse(t.full, "- 부탁방1 (4건)" in t.full)
        // 큰 방은 한 줄에 3조각까지, 구간별 요약은 접어 둔 줄로
        val bigLine = t.full.lines().first { it.startsWith("- 큰방1 (60건): ") }
        assertTrue(bigLine, bigLine.split(" / ").size <= DailyDigest.MAX_SEGS)
        assertEquals(5 * 4, t.full.lines().count { it.startsWith(DailyDigest.DETAIL) })
        assertTrue(t.full, "💼 업무·일정 (앱 1개 · 6건)" in t.full && "설문지 배포" in t.full)
        assertTrue(t.full, "📦 앱 알림 45건 · 쿠팡 40, Strava 5" in t.full)
        assertTrue(t.full, "⭐ 키워드 알림 (1)" in t.full)
        // 나에게 온 대화 → 내가 속한 방 순서, 내가 속한 방은 구간별 요약 없이 한 줄
        val lines = t.full.lines()
        val me = lines.indexOfFirst { it.startsWith("💬 나에게 온 대화 (8곳") }
        val joined = lines.indexOfFirst { it.startsWith("💬 내가 속한 방 (12곳") }
        assertTrue(t.full, me in 0 until joined)
        assertTrue(t.full, lines.indexOf("- 보통방1 (10건): 보통방1 내용") in me until joined)
        assertTrue(t.full, lines.indexOf("- 보통방4 (10건): 보통방4 내용") > joined)
        // 알림창용은 1층과 🙋 만
        assertTrue(t.short, "자료12 내 달라고 함" in t.short)
        assertFalse(t.short, "💬 내가 속한 방" in t.short)
        assertTrue(t.short, "나에게 온 대화 8곳" in t.short && "내가 속한 방 12곳" in t.short)
        assertTrue(t.full, t.full.startsWith("요약 6번 · 알림 ${d.count}건을 정리했어요. 대화방 57곳 중 나에게 온 대화 20곳 · 내가 속한 방 12곳 · 그 밖의 방 25곳."))
    }

    @Test fun AI가_핵심을_쓰고_큰_방은_조각을_고름() = runBlocking {
        val asked = mutableListOf<String>()
        val t = DailyDigest.total(day500(), emptyMap(), at(22)) { p ->
            asked += p
            if ("줄 번호" in p) "1, 2, 3"
            else "✨ 오늘의 핵심:\n- 부탁방12: 자료12 내 달라고 함\n- 큰방1: 99시 모임\n- 학교종이: 설문지 배포\n📅 내일 챙길 것:\n- 없음"
        }
        assertTrue(t.byAi)
        assertEquals(5 + 1, asked.size)                                   // 큰 방 5곳 고르기 + 핵심 한 번
        assertTrue(t.full, "✨ 오늘의 핵심" in t.full && "- 부탁방12: 자료12 내 달라고 함" in t.full)
        assertFalse(t.full, "99시" in t.full)                              // 원문에 없는 숫자는 버림
        assertFalse(t.full, "📅 내일 챙길 것" in t.full)                    // 없음이면 칸 없이
        assertTrue(t.full, "- 큰방1 (60건): 사람0: 큰방1 이야기0 / 1시 모임 / 다른0: 좋아요 장소는 강당0" in t.full)
        assertTrue(t.short, "✨ 오늘의 핵심" in t.short)
    }

    @Test fun 답한_물음은_내리고_답함_표시() = runBlocking {
        val e = entry("학부모방", 4, listOf(RoomDay.Part(at(15), at(15, 30), 4, "준비물 물음", asked = true)), listOf(RoomDay.Ask(at(15, 30), "준비물 알려 달라고 함")))
        val t = DailyDigest.total(DailyDigest.Day(listOf(e), emptyList(), "", 1, 4), mapOf("학부모방" to at(16)), at(22), null)
        assertFalse(t.full, "🙋 답할 것" in t.full)
        assertTrue(t.full, "- 학부모방 (4건): 준비물 물음 · ✔ 답함" in t.full)
    }
}
