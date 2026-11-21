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
import androidx.glance.background
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
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 🏠 홈 화면 위젯 (v1.6.0, 2026-11-21 선생님: "위젯만 봐도 되게 — 요약 버튼, 나머지는 설정 버튼으로 앱에서").
 * 가장 최근 요약을 위젯 안에서 그대로 보여 주고(길면 위젯 안에서 스크롤),
 *   [📋 요약] = 지금까지 온 알림을 바로 요약(앱의 '지금 정리하기'와 같은 백그라운드 서비스) → 끝나면 위젯이 저절로 바뀜
 *   [⚙️]     = 앱을 열어 설정 탭으로
 * 위젯은 안드로이드가 30분보다 자주 새로 그리지 않아서, 요약 시작·끝·앱을 열 때·새 알림(10분에 한 번까지) 직접 새로 그린다.
 */
class SummaryWidget : GlanceAppWidget() {

    private data class State(val briefing: Briefing?, val pending: Int, val running: Boolean)

    private fun load(ctx: Context): State {
        val db = NotificationDb.get(ctx)
        val rules = AppFilter.rules(ctx)
        val pending = runCatching { db.since(Briefings.pendingFrom(db)).count { rules.allows(it) } }.getOrDefault(0)
        return State(db.lastBriefing(), pending, BriefingService.active)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = load(context)
        provideContent { GlanceTheme { Content(context, state) } }
    }

    @Composable
    private fun Content(ctx: Context, s: State) {
        val c = GlanceTheme.colors
        val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
        val openApp = actionStartActivity(Intent(ctx, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        val openSettings = actionStartActivity(Intent(ctx, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_TAB, "settings"))
        val summarize = actionStartService(
            Intent(ctx, BriefingService::class.java).putExtra(BriefingService.EXTRA_LABEL, "위젯 요약"),
            isForegroundService = true,
        )
        Column(GlanceModifier.fillMaxSize().background(c.surface).cornerRadius(20.dp).padding(12.dp)) {
            // 맨 위: 이름·언제 요약했는지, 오른쪽에 [요약] [⚙️]
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight().clickable(openApp)) {
                    Text("쌤톡스", style = TextStyle(color = c.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp))
                    Text(
                        s.briefing?.let { "${it.label} · ${fmt.format(it.time)} · 알림 ${it.count}건" } ?: "아직 요약이 없어요",
                        style = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp), maxLines = 1,
                    )
                }
                Text(
                    when {
                        s.running -> "요약 중…"
                        s.pending > 0 -> "📋 요약 ${s.pending}"
                        else -> "📋 요약"
                    },
                    modifier = GlanceModifier.background(c.primaryContainer).cornerRadius(14.dp)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .let { if (s.running) it else it.clickable(summarize) },
                    style = TextStyle(color = c.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text("⚙️", modifier = GlanceModifier.background(c.secondaryContainer).cornerRadius(14.dp)
                    .padding(horizontal = 10.dp, vertical = 7.dp).clickable(openSettings),
                    style = TextStyle(fontSize = 13.sp))
            }
            Spacer(GlanceModifier.height(8.dp))
            // 요약 본문: 줄마다, 칸 제목(🙋 💬 📦 📌 …)은 굵게. 누르면 앱의 요약 탭
            val lines = s.briefing?.text?.lines()?.map { it.trimEnd() }?.filter { it.isNotEmpty() }.orEmpty()
            if (lines.isEmpty()) {
                Text(
                    if (s.pending > 0) "새 알림 ${s.pending}건이 모였어요. 📋 요약을 누르면 여기에 정리돼요." else "알림이 모이면 📋 요약을 눌러 보세요. 정한 시각에는 저절로 요약해요.",
                    modifier = GlanceModifier.clickable(openApp),
                    style = TextStyle(color = c.onSurface, fontSize = 13.sp),
                )
            } else {
                LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(lines) { line ->
                        val head = !line.startsWith("-") && !line.startsWith(" ") && !line.startsWith("(")
                        Text(line, modifier = GlanceModifier.fillMaxWidth().padding(top = if (head) 6.dp else 1.dp).clickable(openApp),
                            style = TextStyle(color = if (head) c.primary else c.onSurface,
                                fontWeight = if (head) FontWeight.Bold else FontWeight.Normal, fontSize = if (head) 13.sp else 12.sp))
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        @Volatile private var lastNotiRefresh = 0L

        /** 위젯 다시 그리기(요약 시작·끝, 앱 열 때) */
        suspend fun refresh(ctx: Context) { runCatching { SummaryWidget().updateAll(ctx.applicationContext) } }

        /** 새 알림이 올 때: 위젯 '요약 N' 숫자를 10분에 한 번까지만 */
        suspend fun refreshForNewNoti(ctx: Context) {
            val now = System.currentTimeMillis()
            if (now - lastNotiRefresh < 10 * 60_000L) return
            lastNotiRefresh = now
            refresh(ctx)
        }
    }
}

class SummaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SummaryWidget()
}
