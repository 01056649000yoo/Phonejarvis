package com.jarvis.phone

import android.content.Context

/** 최근 며칠 동안 알림을 보낸 앱 하나의 통계. */
data class AppStat(val pkg: String, val app: String, val count: Int, val samples: List<String>)

/** 앱 하나에 대한 추천: 모으기(keep) / 빼기. */
data class AppRec(val stat: AppStat, val keep: Boolean, val reason: String, val byAi: Boolean)

/** 사용자가 뺀 앱(패키지) 목록과, 이미 살펴본 앱 목록. 수집·요약 모두 이 목록을 따른다. */
object AppFilter {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun excluded(ctx: Context): Set<String> = prefs(ctx).getStringSet("excluded", emptySet())!!.toSet()
    fun reviewed(ctx: Context): Set<String> = prefs(ctx).getStringSet("reviewed", emptySet())!!.toSet()

    fun setKeep(ctx: Context, pkg: String, keep: Boolean) {
        val ex = excluded(ctx).toMutableSet()
        if (keep) ex.remove(pkg) else ex.add(pkg)
        prefs(ctx).edit().putStringSet("excluded", ex).apply()
        markReviewed(ctx, listOf(pkg))
    }

    /** 추천 한꺼번에 적용: 빼기 추천 앱은 제외 목록에, 모으기 추천 앱은 제외 목록에서 뺌. */
    fun apply(ctx: Context, recs: List<AppRec>) {
        val ex = excluded(ctx).toMutableSet()
        recs.forEach { if (it.keep) ex.remove(it.stat.pkg) else ex.add(it.stat.pkg) }
        prefs(ctx).edit().putStringSet("excluded", ex).apply()
        markReviewed(ctx, recs.map { it.stat.pkg })
    }

    fun markReviewed(ctx: Context, pkgs: Collection<String>) {
        prefs(ctx).edit().putStringSet("reviewed", reviewed(ctx) + pkgs).apply()
    }
}

/**
 * 알림 앱 정리 추천.
 * 1) 규칙: 앱 이름으로 학교·연락 앱은 모으기, 쇼핑·광고·게임·금융·시스템 앱은 빼기 (즉시)
 * 2) AI: 모델이 있으면 앱 이름 + 알림 제목 예시를 보고 다시 판단 (폰 안에서, 한 번 호출)
 */
object AppAdvisor {
    private val KEEP = listOf(
        "카카오톡", "kakao.talk", "클래스", "class", "알리미", "학교", "school", "밴드", "band",
        "메신저", "messenger", "메일", "mail", "문자", "메시지", "messag", "sms", "캘린더", "calendar",
        "teams", "zoom", "줌", "업무", "나이스", "neis", "교육", "edu", "학급", "e알리미",
    )
    private val DROP = listOf(
        "쿠팡", "coupang", "11번가", "g마켓", "gmarket", "옥션", "무신사", "배민", "배달", "요기요",
        "당근", "쇼핑", "shopping", "스토어", "store", "유튜브", "youtube", "넷플릭스", "netflix",
        "게임", "game", "뉴스", "news", "날씨", "weather", "은행", "bank", "카드", "card", "페이", "pay",
        "증권", "토스", "toss", "인스타", "instagram", "틱톡", "tiktok", "스포티파이", "spotify", "멜론",
        "melon", "갤러리", "gallery", "클라우드", "cloud", "업데이트", "update", "배터리", "디바이스",
        "보안", "security", "백신", "광고",
    )

    fun byRules(stats: List<AppStat>): List<AppRec> = stats.map { s ->
        val key = (s.app + " " + s.pkg).lowercase()
        when {
            DROP.any { it in key } -> AppRec(s, false, "쇼핑·광고·금융·시스템 알림", false)
            KEEP.any { it in key } -> AppRec(s, true, "학교·연락 관련", false)
            else -> AppRec(s, true, "판단이 어려워 일단 모아 둬요", false)
        }
    }

    /** AI 추천. 실패하거나 답 형식이 틀린 앱은 규칙 추천을 그대로 씀. */
    suspend fun byAi(ctx: Context, rules: List<AppRec>): List<AppRec> {
        val summarizer = Summarizer.get(ctx)
        if (!summarizer.hasModel() || rules.isEmpty()) return rules
        val list = rules.take(MAX_APPS)
        val prompt = buildString {
            append(PROMPT)
            list.forEachIndexed { i, r ->
                append("${i + 1}. ${r.stat.app} (7일 ${r.stat.count}건)")
                if (r.stat.samples.isNotEmpty()) append(" 예: ${r.stat.samples.joinToString(" / ") { it.take(20) }}")
                append("\n")
            }
        }
        val answer = runCatching { summarizer.complete(prompt) }.getOrNull() ?: return rules
        val parsed = mutableMapOf<Int, Pair<Boolean, String>>()
        LINE.findAll(answer).forEach { m ->
            val idx = m.groupValues[1].toInt() - 1
            parsed[idx] = (m.groupValues[2] == "모으기") to m.groupValues[3].trim().take(30)
        }
        return rules.mapIndexed { i, r ->
            parsed[i]?.let { (keep, why) -> r.copy(keep = keep, reason = why.ifEmpty { r.reason }, byAi = true) } ?: r
        }
    }

    private const val MAX_APPS = 25
    private val LINE = Regex("""(?m)^\s*(\d+)\s*[|｜]\s*(모으기|빼기)\s*[|｜]\s*(.*)$""")
    private val PROMPT = """
        너는 교사의 알림 정리 도우미야. 아래는 최근 7일 동안 휴대폰에 알림을 보낸 앱 목록이야.
        학교 업무, 학부모·학생 연락, 학교 일정과 관련 있을 수 있는 앱은 '모으기',
        쇼핑·광고·게임·뉴스·금융·시스템처럼 관련 없는 앱은 '빼기'로 골라.
        앱마다 한 줄씩, 정확히 이 형식으로만 답해. 다른 말은 쓰지 마.
        번호|모으기 또는 빼기|이유(15자 이내)

        앱 목록:

    """.trimIndent()
}
