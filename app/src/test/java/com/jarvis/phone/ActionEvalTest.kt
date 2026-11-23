package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Calendar
import java.util.TimeZone

/**
 * ✅ 할 일 중심 요약 채점(2026-11-23): 정답 할 일·바뀐 것이 있는 가짜 하루(11/23 월)를
 * 지금 방식 · 사실 먼저 · 할 일 중심으로 요약해 같은 잣대로 잼. OLLAMA_MODEL 이 없으면 채점은 건너뛰고 규칙 시험만.
 */
class ActionEvalTest {
    private val model = System.getenv("OLLAMA_MODEL").orEmpty()
    private fun ollama(prompt: String): String {
        val body = """{"model":${q(model)},"prompt":${q(prompt)},"stream":false,"think":false,"keep_alive":"1m","options":{"temperature":0.2,"num_ctx":4096,"num_predict":240}}"""
        val c = URI("http://127.0.0.1:11434/api/generate").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("content-type", "application/json")
        c.connectTimeout = 10_000; c.readTimeout = 300_000
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().readText()
        return Regex("\"response\":\"((?:\\\\.|[^\"\\\\])*)\"").find(text)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }.orEmpty()
    }
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private val base = Calendar.getInstance(TimeZone.getDefault()).apply { set(2026, Calendar.NOVEMBER, 23, 8, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private var t = base
    private fun k(room: String, who: String, text: String) = NotiItem(0, "com.kakao.talk", "카카오톡", who, text, (t + 120_000).also { t = it }, room)

    /** 정답 하나: 찾을 낱말(| 로 여러 개), 보낸 사람, 마감(월/일, 없으면 null) */
    data class Gold(val key: String, val who: String, val due: String? = null)

    private fun day(): Triple<List<NotiItem>, List<Gold>, List<Gold>> {
        t = base
        val items = mutableListOf<NotiItem>()
        val chat = listOf("네 알겠습니다", "확인했어요", "ㅋㅋㅋ", "감사합니다~", "저도 그렇게 할게요", "혹시 시간 바뀌면 알려 주세요", "넵", "좋아요 그렇게 해요", "저희 반은 조금 늦을 수도 있어요", "회의실 어디였죠?")
        val bg = "이번 주는 행사가 많아서 일정이 조금 복잡합니다. 학년 전체가 움직이는 행사라 안전 지도와 이동 동선을 미리 점검해야 하고, 학부모 안내도 함께 나가야 합니다. 세부 사항은 첨부한 계획서를 참고해 주세요. "
        items += k("", "민준 어머님", "선생님 안녕하세요~ 내일 체험학습 도시락 싸야 하나요?")
        items += k("교무 공지방", "교무부장", "오늘 급식 메뉴는 카레라이스입니다.")
        items += k("6학년 동학년방", "김부장", "각 반 학예회 참가 명단 금요일까지 단톡에 올려 주세요. (1차 안내)")
        for (i in 0 until 14) items += k("6학년 동학년방", listOf("오선생", "윤선생", "이선생", "박선생")[i % 4], chat[i % chat.size] + " ($i)")
        items += k("교무 공지방", "교무부장", "2학기 학업성적관리위원회 회의록을 11월 28일까지 나이스에 입력하시기 바랍니다.")
        items += k("", "도윤 아버님", "도윤이가 병원 들렀다 가야 해서요. 내일 2교시 후에 등교해도 될까요?")
        items += k("6학년 동학년방", "이선생", bg + bg + "그리고 현장학습 버스 인원은 수요일까지 확인해서 알려 주세요.")
        items += k("6학년 동학년방", "김부장", "리허설은 목요일 5교시에서 금요일 3교시로 변경됐어요.")
        for (i in 14 until 28) items += k("6학년 동학년방", listOf("오선생", "윤선생", "이선생", "박선생")[i % 4], chat[i % chat.size] + " ($i)")
        items += k("6학년 동학년방", "오선생", "저희 반은 명단 다 올렸어요")
        items += k("", "지호 어머니", "선생님, 수요일 상담 2시로 잡았는데 회사 일 때문에 4시로 바꿔도 될까요?")
        items += k("6학년 동학년방", "김부장", "다시 한번 알려 드려요. 각 반 학예회 참가 명단 금요일까지 단톡에 올려 주세요. (2차 안내)")
        items += k("교무 공지방", "교무부장", "교원능력개발평가 참여 마감은 이번 주 목요일입니다. 미참여자는 꼭 참여 바랍니다.")
        items += k("교무 공지방", "교무부장", "내일 직원회의는 취소되었습니다.")
        items += k("2반 학부모회", "회장", "회장단 회의 잘 마쳤습니다. 감사합니다.")
        items += k("2반 학부모회", "총무", "선생님께서도 반 아이들한테 졸업 앨범 신청 안내 한 번 부탁드려요.")
        for (i in 28 until 40) items += k("6학년 동학년방", listOf("오선생", "윤선생", "이선생", "박선생")[i % 4], chat[i % chat.size] + " ($i)")
        items += k("6학년 동학년방", "김부장", "명단 아직 안 올리신 반 계세요~ 각 반 학예회 참가 명단 금요일까지 꼭 올려 주세요 (3차 안내)")
        items += k("우리 가족", "엄마", "일요일 할머니 생신 점심 12시 한정식집이야. 너는 꽃 좀 사 와 줄래?")
        items += k("", "택배기사", "택배 문 앞에 둘까요?")
        val tasks = listOf(
            Gold("도시락", "민준 어머님"), Gold("등교", "도윤 아버님"), Gold("명단", "김부장", "11/27"),
            Gold("버스", "이선생", "11/25"), Gold("회의록", "교무부장", "11/28"), Gold("능력개발|교원평가|교원능력", "교무부장", "11/26"),
            Gold("앨범", "총무"), Gold("꽃", "엄마"), Gold("택배", "택배기사"),
        )
        val changes = listOf(Gold("상담|4시", "지호 어머니"), Gold("리허설", "김부장"), Gold("직원회의", "교무부장"))
        return Triple(items, tasks, changes)
    }

    private fun has(text: String, g: Gold) = g.key.split('|').any { it in text }

    @Test fun 마감_읽기와_분류() {
        val mon = base
        assertEquals("11/27(금)", ActionDigest.parseDue("명단 금요일까지 올려 주세요", mon)!!.label)
        assertEquals("내일 11/24(화)", ActionDigest.parseDue("내일 2교시 후에", mon)!!.label)
        assertEquals("11/28(토)", ActionDigest.parseDue("11월 28일까지 입력", mon)!!.label)
        assertEquals("12/4(금)", ActionDigest.parseDue("다음 주 금요일까지", mon)!!.label)
        assertEquals(ActionDigest.Kind.TASK, ActionDigest.kindOf("회의록을 나이스에 입력하시기 바랍니다.", false))
        assertEquals(ActionDigest.Kind.CHANGE, ActionDigest.kindOf("리허설은 목요일 5교시에서 금요일 3교시로 변경됐어요.", false))
        assertEquals(null, ActionDigest.kindOf("오늘 급식 메뉴는 카레라이스입니다.", false))
        assertEquals(ActionDigest.Kind.TASK, ActionDigest.kindOf("택배 문 앞에 둘까요?", true))
        assertEquals(null, ActionDigest.kindOf("회의실 어디였죠?", false))
    }

    @Test fun 세_방식_비교() = runBlocking {
        assumeTrue("OLLAMA_MODEL 이 없어 건너뜀", model.isNotEmpty())
        ollama("안녕")
        val (items, tasks, changes) = day()
        val out = StringBuilder("# 할 일 중심 요약 비교 ($model) — 가짜 하루 ${items.size}건, 정답 할 일 ${tasks.size}개 · 바뀐 것 ${changes.size}개\n\n")
        out.append("| 방식 | 찾은 할 일 | 바뀐 것 | 엉뚱한 할 일 | 보낸 사람 맞음 | 마감 맞음 | 반복 공지 합침 | 요약 줄 수 | AI 횟수 | 시간 |\n|---|---|---|---|---|---|---|---|---|---|\n")
        val detail = StringBuilder()
        fun mode(fact: Boolean) {
            BriefingComposer.factFirst = fact; BriefingComposer.chunkByChars = fact; BriefingComposer.keepTail = fact
            BriefingComposer.roomChars = if (fact) 2400 else BriefingComposer.ROOM_CHARS; BriefingComposer.lineChars = if (fact) 600 else BriefingComposer.LINE_CHARS
        }
        for ((name, fact) in listOf("지금 방식" to false, "사실 먼저" to true)) {
            mode(fact); var calls = 0
            val t0 = System.currentTimeMillis()
            val r = BriefingComposer.compose(items, ask = { calls++; ollama(it) })
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            val text = r.people
            val lines = text.lines().count { it.startsWith("- ") }
            out.append("| $name | ${tasks.count { has(text, it) }}/${tasks.size} | ${changes.count { has(text, it) }}/${changes.size} | – | – | – | – | $lines | $calls | ${sec}초 |\n")
            detail.append("\n### $name\n```\n$text\n```\n")
        }
        mode(false)
        var calls = 0
        val t0 = System.currentTimeMillis()
        val r = ActionDigest.compose(items) { calls++; ollama(it) }
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        val text = r.text
        val taskLines = text.substringAfter("✅", "").substringBefore("\n\n").lines().filter { it.startsWith("- ") }
        val changeLines = text.substringAfter("📢", "").substringBefore("\n\n").lines().filter { it.startsWith("- ") }
        val found = tasks.filter { g -> taskLines.any { has(it, g) } || (g.key == "명단" && taskLines.any { "명단" in it }) }
        val wrong = taskLines.count { l -> tasks.none { has(l, it) } && changes.none { has(l, it) } }
        val whoOk = found.count { g -> taskLines.any { has(it, g) && g.who in it } }
        val dueOk = found.filter { it.due != null }.count { g -> taskLines.any { has(it, g) && g.due!! in it } }
        val dueAll = tasks.count { it.due != null }
        val dedup = taskLines.count { "명단" in it }.let { if (it == 1) "1줄(3번 합침)" else "${it}줄" }
        val chg = changes.count { g -> changeLines.any { has(it, g) } || taskLines.any { has(it, g) } }
        out.append("| 할 일 중심 | ${found.size}/${tasks.size} | $chg/${changes.size} | $wrong | $whoOk/${found.size} | $dueOk/$dueAll | $dedup | ${taskLines.size + changeLines.size + 1} | $calls | ${sec}초 |\n")
        detail.append("\n### 할 일 중심\n```\n$text\n```\n")
        BriefingComposer.factFirst = false; BriefingComposer.chunkByChars = false; BriefingComposer.keepTail = false
        File("build/action-eval.md").writeText(out.toString() + detail)
        println(out.toString() + detail)
    }
}
