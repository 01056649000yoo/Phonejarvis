package com.jarvis.phone

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.compose.runtime.remember
import androidx.glance.background
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.Box
import androidx.glance.layout.size
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text as GlanceText
import androidx.glance.text.TextStyle
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 🏠 홈 화면 위젯 (v1.6.0, 2026-11-21 선생님: "위젯만 봐도 되게 — 요약 버튼, 나머지는 설정 버튼으로 앱에서").
 * 가장 최근 요약을 위젯 안에서 그대로 보여 주고(길면 위젯 안에서 스크롤),
 *   [요약] = 지금까지 온 알림을 바로 요약(앱의 '지금 정리하기'와 같은 백그라운드 서비스) → 끝나면 위젯이 저절로 바뀜
 *   [⚙️]     = 앱을 열어 설정 탭으로
 * 위젯은 안드로이드가 30분보다 자주 새로 그리지 않아서, 요약 시작·끝·앱을 열 때 직접 새로 그린다.
 * 2026-11-23 선생님: "위젯에서는 온 알림이 모이게 하지 말고 요약만" — 모인 알림 수(요약 N·새 알림 N건)는 보이지 않고,
 * 요약 칸(🙋·💬·👥 내 묶음)만 그대로, 나머지 칸은 제목 한 줄로.
 */
class SummaryWidget : GlanceAppWidget() {

    private data class State(val briefing: Briefing?, val running: Boolean,
                             val lastAuto: AutoLog.Entry?, val next: SummarySchedule.Run?)

    private fun load(ctx: Context): State {
        val db = NotificationDb.get(ctx)
        // ⚡ 미리 요약 기록은 위젯 ⏰ 줄에 안 보임(정한 시각 요약만)
        return State(db.lastBriefing(), BriefingService.active, AutoLog.all(ctx).firstOrNull { it.label != PreSummary.LABEL },
            runCatching { BriefingScheduler.nextRun(ctx) }.getOrNull())
    }

    // 위젯은 한 번 뜬 동안(세션) provideGlance 를 다시 부르지 않는다 — 처음 읽은 요약을 계속 그리던 것(2026-11-21 선생님:
    // "요약을 눌러도 위젯에 최신 요약이 안 됨"). 새로 그리라고 할 때마다 위젯 상태의 '판 번호'를 올리고, 그 번호가 바뀌면 다시 읽는다.
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val rev = currentState<Preferences>()[REV] ?: 0L
            val state = remember(rev) { load(context) }
            // 위젯도 앱과 같은 쌤톡스 색(칠판 그린·크림) — 기본 보라색 대신(2026-11-23 디자인 정리)
            GlanceTheme(colors = androidx.glance.material3.ColorProviders(light = SsamLight, dark = SsamDark)) { Content(context, state) }
        }
    }

    @Composable
    private fun Content(ctx: Context, s: State) {
        val c = GlanceTheme.colors
        val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
        // 본문을 누르면 늘 📋 요약 탭의 최신 요약으로(2026-11-23 선생님: 앱에서 마지막에 보던 화면이 열림)
        val openApp = actionStartActivity(Intent(ctx, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_TAB, "summary"))
        val openSettings = actionStartActivity(Intent(ctx, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_TAB, "settings"))
        val summarize = actionStartService(
            Intent(ctx, BriefingService::class.java).putExtra(BriefingService.EXTRA_LABEL, "위젯 요약"),
            isForegroundService = true,
        )
        // 🎨 2026-11-25 디자인: 앱과 같게 — 크림 바탕·로고, 초록 '요약' 단추(로고 색), 세이지 동그란 설정 단추,
        //    요약은 회갈색 카드 안에 칸 제목은 금색 선 아이콘, 답할 것은 답장 화살표
        val gold = ColorProvider(Ssam.GoldPale)
        val cream = c.onPrimaryContainer
        val creamSoft = ColorProvider(Ssam.Canvas.copy(alpha = 0.72f))
        val white = ColorProvider(Color.White)
        Column(GlanceModifier.fillMaxSize().background(c.background).cornerRadius(24.dp).padding(14.dp)) {
            // 맨 위: 로고·이름·언제 요약했는지, 오른쪽에 [요약] [설정]
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 앱 아이콘 그대로(적응형 아이콘 — 바탕 칠판 그린 + 말풍선)
                Image(ImageProvider(R.mipmap.ic_launcher), contentDescription = "쌤톡스",
                    modifier = GlanceModifier.size(36.dp).cornerRadius(10.dp).clickable(openApp))
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight().clickable(openApp)) {
                    Text("쌤톡스", style = TextStyle(color = c.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp))
                    Text(
                        s.briefing?.let { "${it.label} · ${fmt.format(it.time)} · 알림 ${it.count}건" } ?: "아직 요약이 없어요",
                        style = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp), maxLines = 1,
                    )
                    // 자동 요약이 돌았는지(새 알림이 없어 조용히 넘어간 것도)·다음 요약
                    val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
                    val auto = s.lastAuto?.takeIf { a -> s.briefing == null || a.at > s.briefing.time + 60_000 || !a.result.startsWith("요약") }
                    val line = listOfNotNull(
                        auto?.let { "${hm.format(it.at)} 자동 · ${it.result}" },
                        s.next?.let { n -> "다음 " + SimpleDateFormat(if (SummarySchedule.startOfDay(n.at) == SummarySchedule.startOfDay(System.currentTimeMillis())) "HH:mm" else "M/d HH:mm", Locale.KOREA).format(n.at) },
                    ).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
                }
                Spacer(GlanceModifier.width(6.dp))
                // 초록 '요약' 단추 — 위젯에서 가장 중요한 단추 하나(Ssam.Action = 로고 칠판 그린)
                Row(GlanceModifier.background(ColorProvider(Ssam.Action)).cornerRadius(18.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .let { if (s.running) it else it.clickable(summarize) },
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!s.running) {
                        Image(ImageProvider(R.drawable.ic_line_summary), contentDescription = null,
                            modifier = GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(white))
                        Spacer(GlanceModifier.width(4.dp))
                    }
                    Text(if (s.running) "요약 중…" else "요약", style = TextStyle(color = white, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                }
                Spacer(GlanceModifier.width(6.dp))
                Box(GlanceModifier.size(36.dp).background(c.secondaryContainer).cornerRadius(18.dp).clickable(openSettings),
                    contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.ic_line_settings), contentDescription = "설정",
                        modifier = GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(c.onSecondaryContainer))
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            // 요약 본문: 앱의 진한 요약 카드처럼 회갈색 카드 안에. 누르면 앱의 요약 탭
            // 위젯은 요약만(2026-11-22 선생님: 온 메시지가 다 보일 필요 없음) — 요약 칸은 그대로, 나머지 칸은 제목만 한 줄로
            val lines = s.briefing?.text?.let { summaryOnly(it) }.orEmpty()
            Column(GlanceModifier.fillMaxSize().background(c.primaryContainer).cornerRadius(20.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (lines.isEmpty()) {
                    Text(
                        "아직 요약이 없어요. 요약을 누르면 여기에 정리돼요. 정한 시각에는 저절로 요약해요.",
                        modifier = GlanceModifier.clickable(openApp),
                        style = TextStyle(color = cream, fontSize = 13.sp),
                    )
                } else LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(lines) { line ->
                        // 앱의 요약 화면(SummaryText)과 같은 꼴: 칸 제목은 금색 아이콘·글 · 사람 이름 굵게 + 시각·건수 옅게 / 내용은 다음 줄
                        when {
                            line.startsWith("-") -> {
                                val (ask, clean) = splitAsk(line)
                                val l = splitLine(clean)
                                Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp).clickable(openApp)) {
                                    if (ask) Image(ImageProvider(R.drawable.ic_line_reply), contentDescription = "답할 것",
                                        modifier = GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(gold))
                                    else Box(GlanceModifier.size(14.dp), contentAlignment = Alignment.Center) {
                                        Box(GlanceModifier.size(5.dp).background(gold).cornerRadius(3.dp)) {}
                                    }
                                    Spacer(GlanceModifier.width(6.dp))
                                    Column(GlanceModifier.defaultWeight()) {
                                        // 보낸 사람은 배지(앱 요약 화면과 같게)
                                        if (l.who.isNotEmpty() || l.meta.isNotEmpty()) Row(GlanceModifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (l.who.isNotEmpty()) Text(l.who, modifier = GlanceModifier.background(ColorProvider(Ssam.Canvas.copy(alpha = 0.16f)))
                                                    .cornerRadius(6.dp).padding(horizontal = 6.dp, vertical = 1.dp),
                                                style = TextStyle(color = cream, fontWeight = FontWeight.Bold, fontSize = 12.sp), maxLines = 1)
                                            if (l.meta.isNotEmpty()) Text(l.meta, modifier = if (l.who.isEmpty()) GlanceModifier else GlanceModifier.padding(start = 6.dp),
                                                style = TextStyle(color = creamSoft, fontSize = 11.sp), maxLines = 1)
                                        }
                                        Text(l.body, style = TextStyle(color = cream, fontSize = 12.sp))
                                    }
                                }
                            }
                            line.startsWith("(") -> Text(line, modifier = GlanceModifier.fillMaxWidth().padding(top = 10.dp).clickable(openApp),
                                style = TextStyle(color = creamSoft, fontSize = 11.sp))
                            else -> {
                                val (icon, title) = Emoji.lead(line)
                                Row(GlanceModifier.fillMaxWidth().padding(top = 10.dp).clickable(openApp), verticalAlignment = Alignment.CenterVertically) {
                                    if (icon != null) {
                                        Image(ImageProvider(icon), contentDescription = null,
                                            modifier = GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(gold))
                                        Spacer(GlanceModifier.width(6.dp))
                                    }
                                    Text(title, style = TextStyle(color = gold, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"

        /**
         * 요약 글에서 위젯에 보일 줄: AI 요약 칸(👥 사람 묶음 'OO 연락'·💼 업무·일정·💬 대화 요약, 예전 판의 🙋)은 전부,
         * 원문 칸(키워드·앱 알림·정기 알림, 예전 판의 마감·학부모)은 제목만 모아 한 줄
         */
        fun summaryOnly(text: String): List<String> {
            val blocks = text.trim().split(Regex("\\n\\s*\\n")).map { b -> b.lines().map { it.trimEnd() }.filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
            fun summarized(h: String) = h.startsWith("🙋") || h.startsWith("💬") || h.startsWith(BriefingComposer.WORK_TITLE) ||
                (" 연락 (" in h && !h.startsWith("👪 학부모 연락 ("))
            val keep = blocks.filter { summarized(it[0]) }.flatten()
            // 🌡️·🔋 'AI 없이 정리했어요' 안내 문단은 칸이 아니라 빼기
            val heads = blocks.filterNot { summarized(it[0]) || it[0].startsWith("(") || it[0].startsWith("🌡") || it[0].startsWith("🔋") }
                .map { it[0].substringBefore(" — ") }
            return keep + (if (heads.isNotEmpty()) listOf("(" + heads.joinToString(" · ") + " — 눌러서 보기)") else emptyList())
        }

        private val REV = longPreferencesKey("rev")

        /** 위젯 다시 그리기(요약 시작·끝, 앱 열 때) — 판 번호를 올려 최신 요약을 다시 읽게 */
        suspend fun refresh(ctx: Context) {
            runCatching {
                val app = ctx.applicationContext
                val now = System.currentTimeMillis()
                GlanceAppWidgetManager(app).getGlanceIds(SummaryWidget::class.java).forEach { id ->
                    updateAppWidgetState(app, id) { it[REV] = now }
                }
                SummaryWidget().updateAll(app)
            }
        }

    }
}

class SummaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SummaryWidget()
}


/** 위젯 글자: 앱 이모지는 지우고 글만(칸 제목 아이콘은 위에서 Image 로 따로 그림) */
@androidx.compose.runtime.Composable
private fun Text(text: String, modifier: GlanceModifier = GlanceModifier, style: TextStyle? = null, maxLines: Int = Int.MAX_VALUE) =
    if (style == null) GlanceText(Emoji.strip(text), modifier, maxLines = maxLines) else GlanceText(Emoji.strip(text), modifier, style, maxLines)
