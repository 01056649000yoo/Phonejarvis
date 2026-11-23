package com.jarvis.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * 📏 요약 채점 세트(2026-11-23 선생님: "속도는 빠르게, 내용은 정확하게" — 고칠 때마다 숫자로 비교).
 * 가짜 대화(실제 사람·학생 자료 아님) 10개를 폰과 같은 Gemma 계열 모델(맥미니 Ollama)로 요약하고
 *  - 꼭 들어가야 할 말(날짜·시간·요청)이 몇 개 들어갔나(맞힌 수/전체)
 *  - 원문에 없는 숫자를 지어냈나
 *  - 요청이 2개를 넘나, 걸린 시간, AI 부른 횟수
 * 를 센다. OLLAMA_MODEL=gemma4:e4b ./gradlew testDebugUnitTest --tests '*BriefingEvalTest*'
 * 결과: app/build/briefing-eval.md (EVAL_TAG 를 주면 파일 이름에 붙임). OLLAMA_MODEL 이 없으면 건너뜀.
 */
class BriefingEvalTest {
    private val model = System.getenv("OLLAMA_MODEL").orEmpty()
    /** 실제로 읽은(앞부분 재사용으로 건너뛰지 않은) 질문 토큰 수·시간 합 */
    private var evalTokens = 0; private var evalNanos = 0L

    private fun ollama(prompt: String): String {
        val body = """{"model":${q(model)},"prompt":${q(prompt)},"stream":false,"keep_alive":"1m","think":false,"options":{"temperature":0.2,"num_ctx":4096,"num_predict":${BriefingComposer.MAX_ANSWER_TOKENS}}}"""
        val c = URI("http://127.0.0.1:11434/api/generate").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("content-type", "application/json")
        c.connectTimeout = 10_000; c.readTimeout = 300_000
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().readText()
        Regex("\"prompt_eval_count\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt()?.let { evalTokens += it }
        Regex("\"prompt_eval_duration\":(\\d+)").find(text)?.groupValues?.get(1)?.toLong()?.let { evalNanos += it }
        return Regex("\"response\":\"((?:\\\\.|[^\"\\\\])*)\"").find(text)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }.orEmpty()
    }
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** must: 한 칸에 여러 말이면 그중 하나만 있어도 맞음("4시|16시") */
    data class Case(val name: String, val items: List<NotiItem>, val must: List<String>)

    private var t = 1_763_856_000_000L   // 가짜 시각
    private fun k(room: String, who: String, text: String) = NotiItem(0, "com.kakao.talk", "카카오톡", who, text, (t + 60_000).also { t = it }, room)
    private fun sms(who: String, text: String) = NotiItem(0, "com.samsung.android.messaging", "메시지", who, text, (t + 60_000).also { t = it })

    private fun cases(): List<Case> {
        val topics = listOf(
            "학예회 연습 때문에 이번 주 방과후 시간이 바뀝니다", "수요일은 4시까지 강당 리허설이에요", "의상은 흰 티셔츠에 청바지로 통일했어요",
            "소품은 각 반에서 준비해 주세요", "학부모 초대장은 목요일에 나눠 줄게요", "사진 촬영은 학부모회에서 맡기로 했어요",
            "리허설 날 1교시는 그대로 수업합니다", "음향 담당은 박선생님이 해 주시기로 했어요", "무대 순서는 3반-1반-2반 그대로예요",
            "공연 시간은 반별 7분을 넘기지 말아 주세요",
        )
        return listOf(
            Case("1:1 짧은 세 마디", listOf(k("", "도윤 아버님", "선생님 안녕하세요"), k("", "도윤 아버님", "내일 체험학습 가는 날이죠"), k("", "도윤 아버님", "도시락 싸야 하나요?")),
                listOf("도시락", "체험학습")),
            Case("1:1 두 부탁", listOf(k("", "서윤 어머니", "선생님 서윤이가 열이 나서 병원 들렀다가 2교시 후에 등교해도 될까요?"), k("", "서윤 어머니", "감기래요. 오늘 체육은 쉬게 해 주시면 감사하겠습니다.")),
                listOf("2교시", "체육")),
            Case("동학년방 40개", (0 until 40).map { i -> k("6학년 동학년방", "김부장", "(${i / 10 + 1}차 안내) " + topics[i % 10] + if (i % 10 == 9) " 금요일까지 반별 참가 명단 단톡에 올려 주세요." else "") },
                listOf("금요일", "명단", "4시")),
            Case("부탁 다섯 개", listOf(k("", "하준 어머니", "선생님 하준이 내일 병원 때문에 2교시 후 등교해도 될까요? 그리고 체육은 쉬게 해 주시고요, 지난번 가정통신문 회신서 한 장 더 보내 주실 수 있나요? 급식 우유도 당분간 빼 주세요. 상담 날짜도 다음 주로 바꿀 수 있을까요?")),
                listOf("2교시")),
            Case("가족방", listOf(k("우리 가족", "엄마", "일요일 할머니 생신 점심 12시 한정식집이야. 늦지 말고 와"), k("우리 가족", "동생", "나 케이크 사 갈게"), k("우리 가족", "엄마", "너는 꽃 좀 사 와 줄래?")),
                listOf("12시", "꽃")),
            Case("잡담 속 공지", (1..18).map { i -> k("6학년 동학년방", "선생$i", listOf("네 알겠습니다", "ㅋㅋㅋ", "감사합니다", "넵!", "ㅎㅎ 좋네요", "수고하셨어요")[i % 6]) } +
                    listOf(k("6학년 동학년방", "교감", "내일 아침 8시 30분 교무회의 있습니다. 2학기 평가 계획 자료 꼭 챙겨 오세요."), k("6학년 동학년방", "선생19", "네")),
                listOf("8시 30분|8:30", "교무회의", "평가")),
            Case("시간 바뀜", listOf(sms("지호 어머니", "선생님, 수요일 상담 2시로 잡았는데 회사 일 때문에 4시로 바꿔도 될까요?"), sms("지호 어머니", "안 되면 목요일 같은 시간도 괜찮아요")),
                listOf("4시|16시", "수요일")),
            Case("동료 마감", listOf(k("", "이선생", "선생님 12월 3일 수업 공개 지도안 11월 28일까지 교무부에 내 달래요"), k("", "이선생", "양식은 메신저로 보냈어요")),
                listOf("28일", "지도안")),
            Case("학부모회 회비", listOf(k("2반 학부모회", "회장", "졸업 앨범 추가 구매하실 분은 1권당 3만 원, 12월 5일까지 신청해 주세요"), k("2반 학부모회", "총무", "선생님께서도 반 아이들한테 한 번 안내 부탁드려요")),
                listOf("3만", "5일", "안내")),
            Case("택배 한 마디", listOf(sms("택배기사", "택배 문 앞에 둘까요?")), listOf("문 앞")),
        )
    }

    private val NUM = Regex("\\d+")

    /** EVAL_MODE=fact 면 '사실 먼저'+절충안 설정으로(2026-11-23 비교) */
    private fun applyMode() {
        val fact = System.getenv("EVAL_MODE") == "fact"
        BriefingComposer.factFirst = fact; BriefingComposer.chunkByChars = fact; BriefingComposer.keepTail = fact
        BriefingComposer.roomChars = if (fact) 2400 else BriefingComposer.ROOM_CHARS; BriefingComposer.lineChars = if (fact) 600 else BriefingComposer.LINE_CHARS
        BriefingComposer.rulesFirst = System.getenv("EVAL_RULES_FIRST") == "1"
    }

    @Test fun 채점() = runBlocking {
        assumeTrue("OLLAMA_MODEL 이 없어 채점을 건너뜀", model.isNotEmpty())
        applyMode()
        ollama("안녕")   // 모델 불러오기는 시간에서 뺌
        val out = StringBuilder()
        var hit = 0; var total = 0; var made = 0; var over = 0; var calls = 0; var ms = 0L
        for (c in cases()) {
            var n = 0
            val t0 = System.currentTimeMillis()
            val r = BriefingComposer.compose(c.items, ask = { p -> n++; ollama(p).also { if (c.name == System.getenv("EVAL_RAW")) File("build/eval-raw.txt").writeText(p.takeLast(400) + "\n---\n" + it) } })
            val took = System.currentTimeMillis() - t0
            val text = r.people
            val got = c.must.filter { m -> m.split('|').any { it in text } }
            val srcNums = c.items.flatMap { NUM.findAll(it.text + " " + it.title + " " + it.room).map { m -> m.value } }.toSet()
            // 줄머리의 받은 시각(· 09:03)·건수((40건))·방 수는 빼고 셈
            val body = text.replace(Regex("· \\d{2}:\\d{2}"), "").replace(Regex("\\(\\d+(건|개 방)?\\)"), "")
            val invented = NUM.findAll(body).map { it.value }.filter { it !in srcNums }.toSet()
            val asks = text.lines().filter { it.startsWith("- ") }.maxOfOrNull { l -> l.substringAfter(": ").substringBefore(" (").split(", ").size } ?: 0
            hit += got.size; total += c.must.size; made += invented.size; if (asks > 2) over++; calls += n; ms += took
            out.append("\n### ${c.name} — ${got.size}/${c.must.size}${if (invented.isNotEmpty()) " · 지어낸 숫자 $invented" else ""} · AI ${n}번 ${took / 1000.0}초\n```\n$text\n```\n")
        }
        val head = "# 요약 채점 (${System.getenv("EVAL_TAG") ?: "지금"}, $model)\n\n" + "읽은 질문 토큰 ${evalTokens}개 · 질문 읽기 ${evalNanos / 1_000_000}ms\n\n" +
            "**꼭 들어갈 말 $hit/$total · 지어낸 숫자 ${made}개 · 요청 2개 넘음 ${over}곳 · AI ${calls}번 · ${ms / 1000.0}초**\n"
        val name = System.getenv("EVAL_TAG")?.let { "build/briefing-eval-$it.md" } ?: "build/briefing-eval.md"
        File(name).writeText(head + out)
        println(head + out)
    }
    /** ⚡ 미리 요약 효과: 하루 메시지의 앞 70%를 미리 요약해 두고, 요약 시각에 나머지 30%만 이어서 — 그냥 요약과 시간·결과 비교 */
    @Test fun 미리_요약_효과() = runBlocking {
        assumeTrue("OLLAMA_MODEL 이 없어 건너뜀", model.isNotEmpty())
        ollama("안녕")
        val all = cases().flatMap { it.items }
        val cutAt = all.map { it.time }.sorted().let { it[(it.size * 0.7).toInt()] }
        val early = all.filter { it.time <= cutAt }
        val since = 1L
        val cache = mutableMapOf<String, BriefingComposer.Cached>()
        var preCalls = 0
        val t0 = System.currentTimeMillis()
        BriefingComposer.compose(early, ask = { preCalls++; ollama(it) }, since = since, onCached = { cache[it.key] = it })
        val preMs = System.currentTimeMillis() - t0
        var calls = 0
        val t1 = System.currentTimeMillis()
        val withCache = BriefingComposer.compose(all, ask = { calls++; ollama(it) }, cache = cache, since = since)
        val withMs = System.currentTimeMillis() - t1
        var plainCalls = 0
        val t2 = System.currentTimeMillis()
        val plain = BriefingComposer.compose(all, ask = { plainCalls++; ollama(it) })
        val plainMs = System.currentTimeMillis() - t2
        val report = "# 미리 요약 효과 ($model, 가짜 메시지 ${all.size}개 중 앞 ${early.size}개를 미리)\n\n" +
            "- 미리 요약(화면 꺼짐·충전 중에): AI ${preCalls}번 ${preMs / 1000.0}초\n" +
            "- **요약 시각 — 미리 요약 있음: AI ${calls}번 ${withMs / 1000.0}초**\n" +
            "- 요약 시각 — 미리 요약 없음: AI ${plainCalls}번 ${plainMs / 1000.0}초\n\n" +
            "## 미리 요약 있음\n```\n${withCache.people}\n```\n## 미리 요약 없음\n```\n${plain.people}\n```\n"
        File("build/briefing-presummary.md").writeText(report)
        println(report)
    }
    /** 2026-11-23 잡음 거르기: 광고(이름 든)·대표번호·봇·매일 오는 알림·가족 묶음이 섞인 하루를 요약 흐름대로 */
    @Test fun 잡음_거르기() = runBlocking {
        assumeTrue("OLLAMA_MODEL 이 없어 건너뜀", model.isNotEmpty())
        ollama("안녕")
        val day = 24L * 3600 * 1000
        val now = t + 7 * day
        fun at(n: NotiItem, time: Long) = n.copy(time = time)
        val weatherWeek = (0 until 6).map { NotiItem(0, "com.weather", "날씨", "오늘 ${it + 2}도 맑음", "미세먼지 보통", now - (6 - it) * day) }
        val today = listOf(
            sms("1588-1234", "홍길동님 이번 주 특별 혜택! 겨울 패딩 최대 70% 할인 쿠폰을 확인하세요"),
            sms("02-345-6789", "[OO병원] 홍길동님 11/25(화) 10:30 진료 예약 안내입니다"),
            sms("01055551234", "선생님 지호 엄마예요. 지호가 내일 현장체험학습에 못 갈 것 같아요. 체험학습 신청서 다시 내야 하나요?"),
            k("", "자비스", "서버 점검: 모두 정상 (빌드 48)"),
            k("", "엄마", "일요일 할머니 생신 점심 12시 한정식집이야"),
            sms("엄마", "너는 꽃 좀 사 와 줄래?"),
            k("우리 가족", "엄마", "늦지 말고 와"),
            k("우리 가족", "동생", "나 케이크 사 갈게"),
            k("", "김민수 선생님", "선생님 내일 3시 학년 협의회 2층 회의실이에요. 학예회 순서 정할 거예요"),
            k("6학년 동학년방", "김민수 선생님", "그리고 학예회 반별 명단은 금요일까지 단톡에 올려 주세요"),
            sms("김민수", "협의회 자료는 메신저로 보냈어요. 미리 한 번 봐 주세요"),
            k("6학년 동학년방", "교감", "홍길동 선생님 내일 8시 30분 교무회의 자료 부탁드려요"),
            NotiItem(0, "com.weather", "날씨", "오늘 5도 맑음", "미세먼지 보통", now),
        ).map { at(it, now + (t++ % 1000) * 1000) }
        val rules = AppFilter.Rules(emptySet(), emptySet(), listOf("홍길동", "체험학습"), adFilter = true, people = listOf("엄마", "동생", "김민수"))
        val kept = today.filter { rules.allows(it) }
        val routine = Routine.detect(weatherWeek + kept)
        val (quiet, loud) = kept.partition { Routine.isQuiet(it, routine, emptySet(), rules.keywords) }
        val book = listOf(PeopleGroup("g", "🏠", "가족", "custom") to listOf("엄마", "동생"), PeopleGroup("t", "👔", "교직원", "custom") to listOf("김민수"))
        val hits = loud.mapNotNull { n -> People.find(book, n, bodyToo = false)?.let { (g, name) -> Triple(g, name, n) } }
        val groups = hits.groupBy { it.first.id }.map { (_, l) -> BriefingComposer.Group(l.first().first.title, l.map { it.second to it.third }) }
        val rest = loud - hits.map { it.third }.toSet()
        var calls = 0
        val r = BriefingComposer.compose(rest, ask = { calls++; ollama(it) }, replyAllowed = { c -> c.items.any { rules.mentionsRoster(it) } }, groups = groups)
        val out = (r.people + "\n\n" + r.groups + Briefings.keywordSection(loud, rules.keywords) + r.apps + "\n" + Routine.line(quiet)).trim()
        val report = "# 잡음 거르기 ($model, 가짜 알림 ${today.size}건 · 광고 거름 ${today.size - kept.size}건 · AI ${calls}번)\n\n```\n$out\n```\n"
        File("build/briefing-noise.md").writeText(report)
        println(report)
    }
    /**
     * 📦 알림 120건(2026-11-23 선생님: 100건이 넘으면 요약이 중간에 끊기거나 다른 사람으로 착각) —
     * 단톡 3곳(사람마다 다른 부탁) + 1:1 여러 곳. 사람 섞임: 한 문장(쉼표·마침표로 끊은 토막)에 부탁 낱말과 '그 부탁을 하지 않은 사람' 이름이 같이 나오면 1.
     */
    @Test fun 대량_알림() = runBlocking {
        assumeTrue("OLLAMA_MODEL 이 없어 건너뜀", model.isNotEmpty())
        applyMode()
        ollama("안녕")
        data class Ask(val who: String, val key: String, val text: String)
        val rooms = mapOf(
            "6학년 동학년방" to listOf(Ask("김부장", "명단", "각 반 학예회 명단 금요일까지 올려 주세요"), Ask("이선생", "버스", "현장학습 버스는 9시 정문에서 출발해요, 인원 확인 부탁드려요"), Ask("박선생", "의상", "학예회 의상은 흰 티셔츠로 통일했어요, 반별로 준비해 주세요")),
            "학년 부장 회의" to listOf(Ask("정교감", "평가", "2학기 평가 계획서 다음 주 월요일까지 내 주세요"), Ask("최부장", "예산", "학년 예산 사용 내역 정리해서 공유 부탁드려요"), Ask("한부장", "연수", "수요일 연수는 3시 시청각실이에요")),
            "2반 학부모회" to listOf(Ask("회장", "앨범", "졸업 앨범 추가 구매 신청 12월 5일까지 받아요"), Ask("총무", "간식", "학예회 날 간식은 학부모회에서 준비할게요"), Ask("서윤맘", "사진", "선생님 학예회 사진 단체방에 올려 주실 수 있나요")),
        )
        val chatter = listOf("네 알겠습니다", "확인했어요", "감사합니다~", "ㅎㅎ 좋네요", "넵 수고하셨어요", "저도 그렇게 할게요", "혹시 시간 바뀌면 알려 주세요")
        val items = mutableListOf<NotiItem>()
        rooms.forEach { (room, asks) ->
            val people = asks.map { it.who } + listOf("오선생", "윤선생")
            for (j in 0 until 34) {
                val a = asks[(j / 6) % asks.size]   // 부탁 줄(6번째마다)이 세 사람에게 돌아가며
                items += if (j % 6 == 0) k(room, a.who, a.text + " (${j / 6 + 1}차 안내)") else k(room, people[j % people.size], chatter[j % chatter.size] + " ${j}")
            }
        }
        val ones = listOf("민준 어머님" to "내일 체험학습 도시락 싸야 하나요?", "도윤 아버님" to "도윤이 내일 2교시 후에 등교해도 될까요?", "지호 어머니" to "상담 시간을 4시로 바꿔도 될까요?",
            "서연 할머니" to "서연이 감기라 체육은 쉬게 해 주세요", "하준 어머니" to "가정통신문 회신서 한 장 더 보내 주실 수 있나요?", "택배기사" to "택배 문 앞에 둘까요?")
        ones.forEach { (who, t) -> items += k("", who, t); items += k("", who, "늘 감사합니다") }
        val askOwner = rooms.values.flatten().associate { it.key to it.who }
        val allPeople = rooms.values.flatten().map { it.who }.toSet()
        var calls = 0
        val t0 = System.currentTimeMillis()
        val raw = StringBuilder()
        val r = BriefingComposer.compose(items, ask = { p -> calls++; ollama(p).also { if ("동학년방" in p) raw.append("---질문 끝부분---\n" + p.takeLast(700) + "\n---답---\n" + it + "\n") } })
        File("build/briefing-bulk-raw.txt").writeText(raw.toString())
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        val text = r.people + "\n" + r.groups
        // '이름: 부탁' 짝만 봄 — 부탁(다음 쉼표·괄호까지)에 든 부탁 낱말의 주인이 그 이름이 아니면 섞임
        val pair = Regex("(" + allPeople.joinToString("|") + ")님?\\s*:\\s*([^,(\\n]+)")
        val wrong = pair.findAll(text).flatMap { m -> askOwner.filter { (key, owner) -> key in m.groupValues[2] && owner != m.groupValues[1] }.map { (key, _) -> "$key↔${m.groupValues[1]}" } }.toList()
        val named = pair.findAll(text).count()
        val missed = askOwner.keys.filter { it !in text }
        val report = "# 알림 ${items.size}건 ($model, ${System.getenv("EVAL_TAG") ?: "지금"})\n\n**AI ${calls}번 · ${sec}초 · 이름 붙은 부탁 ${named}개 · 사람 섞임 ${wrong.size}곳 ${wrong} · 빠진 부탁 ${missed.size}개 ${missed}**\n\n```\n$text\n```\n"
        File("build/briefing-bulk-${System.getenv("EVAL_TAG") ?: "now"}.md").writeText(report)
        println(report)
    }
    /** 프롬프트 토큰 수까지 받는 부르기(폰 작업 공간 4096 안에 드는지 보려고) */
    private fun ollamaT(prompt: String): Pair<String, Int> {
        val body = """{"model":${q(model)},"prompt":${q(prompt)},"stream":false,"think":false,"keep_alive":"1m","options":{"temperature":0.2,"num_ctx":8192,"num_predict":${BriefingComposer.MAX_ANSWER_TOKENS}}}"""
        val c = URI("http://127.0.0.1:11434/api/generate").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("content-type", "application/json")
        c.connectTimeout = 10_000; c.readTimeout = 300_000
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().readText()
        val resp = Regex("\"response\":\"((?:\\\\.|[^\"\\\\])*)\"").find(text)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }.orEmpty()
        val tok = Regex("\"prompt_eval_count\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        return resp to tok
    }

    /**
     * 💬 10분 사이 100개 쏟아지는 단톡(2026-11-23 선생님: 카톡은 10분에 100개도 — 긴 글이 섞이면 빠질 수 있음).
     * 짧은 잡담 사이에 긴 공지 8개(끝에 사람마다 다른 부탁). 설정마다: AI 가 본 부탁 수·요약에 남은 부탁 수·AI 횟수·시간·가장 큰 프롬프트 토큰.
     */
    @Test fun 쏟아지는_단톡_글자수() = runBlocking {
        assumeTrue("OLLAMA_MODEL 이 없어 건너뜀", model.isNotEmpty())
        ollama("안녕")
        val asks = listOf("김부장" to "명단", "이선생" to "버스", "박선생" to "의상", "정교감" to "평가", "최부장" to "예산", "한부장" to "연수", "오선생" to "강당", "윤선생" to "급식")
        val longTail = mapOf("명단" to "그래서 각 반 학예회 참가 명단을 금요일까지 단톡에 올려 주세요.", "버스" to "현장학습 버스 인원은 수요일까지 확인해서 알려 주세요.",
            "의상" to "공연 의상은 흰 티셔츠로 통일했으니 반별로 미리 준비해 주세요.", "평가" to "2학기 평가 계획서는 다음 주 월요일까지 교무실로 내 주세요.",
            "예산" to "학년 예산 사용 내역을 정리해서 목요일까지 공유 부탁드립니다.", "연수" to "수요일 연수는 3시 시청각실이니 꼭 참석해 주세요.",
            "강당" to "리허설 날 강당 의자 배치를 각 반에서 도와주세요.", "급식" to "체험학습 날 급식 취소 인원을 내일까지 행정실에 알려 주세요.")
        val filler = "이번 주는 행사가 많아서 일정이 조금 복잡합니다. 지난번 회의에서 이야기한 내용을 다시 정리하면, 학년 전체가 움직이는 행사라 안전 지도와 이동 동선을 미리 점검해야 하고, 학부모 안내도 함께 나가야 합니다. 세부 사항은 첨부한 계획서를 참고해 주시고 궁금한 점은 언제든 말씀해 주세요. "
        val chat = listOf("네 알겠습니다", "확인했어요", "ㅋㅋㅋ", "감사합니다~", "저도 그렇게 할게요", "혹시 시간 바뀌면 알려 주세요", "넵", "좋아요 그렇게 해요", "저희 반은 조금 늦을 수도 있어요", "회의실 어디였죠?")
        val room = "6학년 동학년방"
        // 1,100자 넘는 공지(끝에 '사진' 부탁)와 영어·숫자가 많은 긴 공지(끝에 '자료' 부탁)를 더함 — 끝 살리기·토큰 안전장치 확인
        val veryLong = "[긴 공지] " + filler + filler + filler + filler + "학예회 당일 사진은 각 반 담임이 찍어서 금요일까지 학년 드라이브에 올려 주세요."
        val english = "[Notice] Please review the following schedule. " + (1..14).joinToString(" ") { "Session $it: 09:${10 + it} - 10:${20 + it} Room B-$it (zoom id 8${it}3-4${it}5-77${it}) https://example.org/s$it" } +
            " 마지막으로 수업 자료는 화요일까지 공유 폴더에 올려 주세요."
        val extraTail = mapOf("사진" to "학년 드라이브에 올려 주세요", "자료" to "공유 폴더에 올려 주세요")
        val items = (0 until 100).map { j ->
            when {
                j % 12 == 5 && j / 12 < asks.size -> { val (who, key) = asks[j / 12]; k(room, who, "[${j / 12 + 1}번째 안내] " + filler + filler.take(120) + longTail[key]) }
                j == 50 -> k(room, "학년부장", veryLong)
                j == 80 -> k(room, "정보부장", english)
                else -> k(room, listOf("오선생", "윤선생", "김부장", "이선생", "박선생")[j % 5], chat[j % chat.size] + " ($j)")
            }
        }
        val longTailAll = longTail + extraTail
        val keys = asks.map { it.second } + listOf("사진", "자료")
        val avgLong = items.filter { it.text.length > 200 }.map { it.text.length }.average().toInt()
        val out = StringBuilder("# 10분 100개 단톡 — 글자 수 비교 ($model)\n\n긴 공지 ${keys.size}개(평균 ${avgLong}자, 부탁은 공지 끝) + 짧은 잡담 ${100 - keys.size}개\n\n| 나누기 | 한 번 넣는 글자 | 메시지 한 개 | AI 본 부탁 | 요약에 남은 부탁 | AI 횟수 | 시간 | 가장 큰 프롬프트 |\n|---|---|---|---|---|---|---|---|\n")
        val detail = StringBuilder()
        data class Cfg(val name: String, val byChars: Boolean, val room: Int, val line: Int, val tail: Boolean, val fact: Boolean = false)
        val configs = listOf(Cfg("지금(30개씩)", false, 1600, 200, false), Cfg("절충안", true, 2400, 600, true), Cfg("사실 먼저", true, 2400, 600, true, fact = true))
        val owner = asks.toMap().entries.associate { (w, kk) -> kk to w } + mapOf("사진" to "학년부장", "자료" to "정보부장")
        val people = owner.values.toSet() + setOf("오선생", "윤선생")
        val pair = Regex("(" + people.joinToString("|") + ")님?\\s*:\\s*([^,(\\n]+)")
        out.setLength(0)
        out.append("# 10분 100개 단톡 — 지금·처음 추천·절충안 ($model)\n\n긴 공지 8개(평균 ${avgLong}자) + 1,100자 공지 1개 + 영어·숫자 공지 1개(${english.length}자), 부탁은 모두 글 끝 · 나머지는 짧은 잡담\n\n")
        out.append("| 설정 | AI 본 부탁 | 요약에 보인 부탁 | 이름 맞음 / 이름 붙은 부탁 | '외 부탁' | AI 횟수 | 시간 | 가장 큰 질문 |\n|---|---|---|---|---|---|---|---|\n")
        for (c in configs) {
            BriefingComposer.chunkByChars = c.byChars; BriefingComposer.roomChars = c.room; BriefingComposer.lineChars = c.line; BriefingComposer.keepTail = c.tail; BriefingComposer.factFirst = c.fact
            val seen = mutableSetOf<String>(); var calls = 0; var maxTok = 0
            val t0 = System.currentTimeMillis()
            val r = BriefingComposer.compose(items, ask = { p -> calls++; keys.filter { longTailAll[it]!!.take(12) in p }.forEach { seen += it }; val (a, t) = ollamaT(p); maxTok = maxOf(maxTok, t); a })
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            val text = r.people
            val kept = keys.count { it in text }
            val extra = Regex("외 부탁 (\\d+)건").find(text)?.groupValues?.get(1) ?: "-"
            val named = pair.findAll(text).toList()
            val right = named.count { m -> owner.filter { (kk, _) -> kk in m.groupValues[2] }.values.let { o -> o.isNotEmpty() && m.groupValues[1] in o } }
            out.append("| ${c.name} | ${seen.size}/${keys.size} | $kept/${keys.size} | $right / ${named.size} | $extra | $calls | ${sec}초 | $maxTok 토큰 |\n")
            detail.append("\n### ${c.name}\n```\n$text\n```\n")
        }
        BriefingComposer.chunkByChars = false; BriefingComposer.roomChars = BriefingComposer.ROOM_CHARS; BriefingComposer.lineChars = BriefingComposer.LINE_CHARS; BriefingComposer.keepTail = false; BriefingComposer.factFirst = false
        File("build/briefing-flood.md").writeText(out.toString() + detail)
        println(out.toString() + detail)
    }

    /** ✅ 할 일 중심을 채점 세트·쏟아지는 단톡에도(꼭 들어갈 말이 할 일·바뀐 것에 들었나) */
    @Test fun 할일중심_다른_세트() = runBlocking {
        assumeTrue("OLLAMA_MODEL 이 없어 건너뜀", model.isNotEmpty())
        ollama("안녕")
        val out = StringBuilder("# 할 일 중심 — 채점 세트 ($model)\n\n")
        var hit = 0; var total = 0; var calls = 0; var ms = 0L
        for (c in cases()) {
            var n = 0; val t0 = System.currentTimeMillis()
            val r = ActionDigest.compose(c.items) { n++; ollama(it) }
            ms += System.currentTimeMillis() - t0; calls += n
            // 마감은 [11/28(금)] 처럼 날짜로 바뀌어 보이므로 '금요일'·'28일'은 날짜 표시로도 인정
            fun shown(w: String): Boolean = w in r.text ||
                Regex("^([월화수목금토일])요일$").find(w)?.let { "(${it.groupValues[1]})]" in r.text } == true ||
                Regex("^(\\d{1,2})일$").find(w)?.let { "/${it.groupValues[1]}(" in r.text } == true
            val got = c.must.filter { m -> m.split('|').any { shown(it) } }
            hit += got.size; total += c.must.size
            out.append("\n### ${c.name} — ${got.size}/${c.must.size}\n```\n${r.text}\n```\n")
        }
        val head = "**꼭 들어갈 말 $hit/$total · AI ${calls}번 · ${ms / 1000.0}초** (지금 방식은 20/21 · AI 10번 · 약 23초)\n"
        File("build/action-eval-cases.md").writeText(out.toString().replaceFirst("\n\n", "\n\n$head\n"))
        println(head)
    }
}
