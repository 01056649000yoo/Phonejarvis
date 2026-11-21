package com.jarvis.phone

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

/** 이번 버전에서 바뀐 점 한 줄 (버전 올릴 때 같이 고치기). */
private const val WHATS_NEW = "맨 위 ⚙️ 설정 버튼으로 언제든 설정에 갈 수 있어요 · AI 알림 앱 정리 · 요약 중지 버튼"

/** 메인: 아래 탭 두 개 — 브리핑(결과만 보기) / 설정(내 방식대로). */
@Composable
fun JarvisScreen(vm: JarvisViewModel) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = vm.tab == JarvisViewModel.Tab.BRIEFING,
                    onClick = { vm.tab = JarvisViewModel.Tab.BRIEFING },
                    icon = { Text("📋") }, label = { Text("브리핑") },
                )
                NavigationBarItem(
                    selected = vm.tab == JarvisViewModel.Tab.SETTINGS,
                    onClick = { vm.tab = JarvisViewModel.Tab.SETTINGS },
                    icon = { Text("⚙️") }, label = { Text("설정") },
                )
            }
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Header(vm)
            when (vm.tab) {
                JarvisViewModel.Tab.BRIEFING -> BriefingTab(vm)
                JarvisViewModel.Tab.SETTINGS -> SettingsTab(vm)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header(vm: JarvisViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SsamLogo(44.dp)
        Column(Modifier.weight(1f)) {
            Text("쌤톡스", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("선생님을 위한 디지털 디톡스 · v${vm.appVersion}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 아래 탭이 안 보이는 기기에서도 언제든 설정 ↔ 브리핑으로 갈 수 있게 맨 위에도 버튼
        val inSettings = vm.tab == JarvisViewModel.Tab.SETTINGS
        FilledTonalButton(
            onClick = { vm.tab = if (inSettings) JarvisViewModel.Tab.BRIEFING else JarvisViewModel.Tab.SETTINGS },
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) { Text(if (inSettings) "📋 브리핑" else "⚙️ 설정") }
    }
}

// ───────────────────────── 브리핑 탭: 결과만 ─────────────────────────

@Composable
private fun BriefingTab(vm: JarvisViewModel) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val dayTimeFmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREA) }

    if (vm.showWhatsNew) Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🆕 v${vm.appVersion} $WHATS_NEW", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.closeWhatsNew() }) { Text("닫기") }
        }
    }

    // 브리핑 카드: 칠판 그린 바탕
    val briefingColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
    when {
        vm.summary.isNotEmpty() -> Card(Modifier.fillMaxWidth(), colors = briefingColors) {
            Text(vm.summary, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        vm.lastBriefing != null -> {
            val b = vm.lastBriefing!!
            Card(Modifier.fillMaxWidth(), colors = briefingColors) {
                Column(Modifier.padding(16.dp)) {
                    Text("${b.label} · ${dayTimeFmt.format(b.time)} · 알림 ${b.count}건",
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(b.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        else -> Card(Modifier.fillMaxWidth()) {
            Text("아직 브리핑이 없어요. 아래 버튼을 누르거나, 설정에서 정한 시각을 기다리세요.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }

    Button(
        onClick = { vm.summarizeToday() },
        enabled = vm.hasModel && !vm.busy && vm.todayItems.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text("지금 정리하기 (오늘 ${vm.todayItems.size}건)", fontWeight = FontWeight.Bold) }
    if (vm.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick = { vm.stopSummary() }, enabled = !vm.stopping, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.stopping) "멈추는 중…" else "중지 (지금까지 정리한 것만 보기)")
        }
    }
    if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
    if (!vm.hasModel) Text("AI 모델이 없어요. 설정 탭에서 받아 주세요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)

    // 원본은 접어 둠
    if (vm.todayItems.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val apps = vm.todayItems.map { it.app }.distinct()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("오늘 모은 알림 ${vm.todayItems.size}건", fontWeight = FontWeight.Bold)
                        Text(apps.take(3).joinToString(", ") + if (apps.size > 3) " 외 ${apps.size - 3}개 앱" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "원본 보기") }
                }
                if (expanded) vm.todayItems.takeLast(30).reversed().forEach {
                    Text("${timeFmt.format(it.time)} [${it.app}] ${it.title}: ${it.text}",
                        style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}

// ───────────────────────── 설정 탭: 내 방식대로 ─────────────────────────

@Composable
private fun SettingsTab(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    AppCleanupCard(vm)

    // 자동 브리핑 시각
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⏰ 자동 브리핑", fontWeight = FontWeight.Bold)
            Text("정한 시각에 오늘 온 알림(지난 브리핑 다음부터)을 요약해 알림으로 보내줘요.",
                style = MaterialTheme.typography.bodySmall)
            vm.slots.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.slot.label, Modifier.weight(1f))
                    TextButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m -> vm.setSlot(s.slot, true, h * 60 + m) },
                            s.minute / 60, s.minute % 60, true).show()
                    }) { Text("%02d:%02d".format(s.minute / 60, s.minute % 60)) }
                    Switch(checked = s.on, onCheckedChange = { vm.setSlot(s.slot, it, s.minute) })
                }
            }
            OutlinedButton(onClick = { vm.briefNow() }, enabled = vm.hasModel && !vm.busy,
                modifier = Modifier.fillMaxWidth()) { Text("지금 브리핑 받아보기 (테스트)") }
        }
    }

    Text("기본 설정", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SetupCard(
        done = vm.listenerEnabled, title = "알림 접근 권한",
        desc = if (vm.listenerEnabled) "허용됨" else "다른 앱의 알림을 읽으려면 허용이 필요해요",
        button = "설정 열기",
    ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    SetupCard(
        done = vm.hasModel, title = "AI 모델",
        desc = if (vm.hasModel) "${vm.installedModel?.title ?: "직접 고른 모델"} · " +
                   "${vm.summarizer.modelFile.length() / 1_000_000}MB"
               else "폰 진단 후 맞는 모델을 받아요",
        button = if (vm.hasModel) "바꾸기" else "받기", enabled = !vm.busy,
    ) { vm.openModelGuide() }
    val notiPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    SetupCard(
        done = vm.notiPermission, title = "알림 표시 권한",
        desc = if (vm.notiPermission) "허용됨" else "브리핑 결과를 알림으로 받으려면 필요해요",
        button = "허용하기", enabled = !vm.notiPermission,
    ) {
        if (Build.VERSION.SDK_INT >= 33) notiPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
}

/** 알림 앱 정리: AI(또는 규칙)가 모으기/빼기를 추천 → "추천대로 적용" 한 번 또는 스위치로. */
@Composable
private fun AppCleanupCard(vm: JarvisViewModel) {
    val recs = vm.appRecs
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🌱 알림 앱 정리", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                when {
                    recs.isEmpty() -> "아직 모은 알림이 없어요. 알림이 쌓이면 쌤톡스가 앱을 정리해 추천해 드려요."
                    vm.aiBusy -> "최근 7일 동안 알림을 보낸 앱 ${recs.size}개 · AI가 살펴보는 중…"
                    recs.any { it.byAi } -> "최근 7일 동안 알림을 보낸 앱 ${recs.size}개 · AI 추천"
                    else -> "최근 7일 동안 알림을 보낸 앱 ${recs.size}개 · 기본 추천"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (vm.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ssam.Sprout)

            if (recs.isNotEmpty()) {
                val drop = recs.count { !it.keep }
                Button(
                    onClick = { vm.applyAppRecs() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink),
                ) { Text(if (drop > 0) "추천대로 적용 (앱 ${drop}개 빼기)" else "추천대로 적용", fontWeight = FontWeight.Bold) }
                Text("또는 아래에서 앱마다 켜고 끄세요. 끈 앱은 모으지도, 요약하지도 않아요.",
                    style = MaterialTheme.typography.bodySmall)

                recs.forEach { r ->
                    val keepNow = r.stat.pkg !in vm.excludedApps
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.stat.app} · ${r.stat.count}건", fontWeight = FontWeight.Medium)
                            Text((if (r.keep) "추천: 모으기 — " else "추천: 빼기 — ") + r.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (r.keep) Ssam.Sprout else Ssam.Blush)
                        }
                        Switch(
                            checked = keepNow,
                            onCheckedChange = { vm.setAppKeep(r.stat.pkg, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Ssam.Sprout, checkedThumbColor = Ssam.Ink),
                        )
                    }
                }
            } else {
                OutlinedButton(onClick = { vm.skipAppSetup() }) { Text("나중에 할게요", color = Ssam.Chalk) }
            }
        }
    }
}

@Composable
private fun SetupCard(
    done: Boolean, title: String, desc: String, button: String,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (done) "✅ " else "⬜ ") + title, fontWeight = FontWeight.Bold)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(button) }
        }
    }
}
