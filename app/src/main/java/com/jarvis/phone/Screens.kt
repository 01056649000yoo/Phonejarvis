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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
private const val WHATS_NEW = "자동 브리핑 시각을 버튼으로 쉽게 고르기(직접도 가능)·주말 쉬기 · 🧒 우리 반 · 📌 마감 · 🏫 수업·퇴근 모드"

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

    TasksCard(vm)
    ParentCard(vm)

    // ⭐ 중요 키워드 바로가기: 지금 키워드를 보여 주고, 누르면 설정의 키워드 칸으로
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.keywords.isEmpty()) "⭐ 중요 키워드를 정해 두면 놓치면 안 될 연락을 맨 위에 모아 드려요"
                else "⭐ 중요 키워드: " + vm.keywords.take(5).joinToString(", ") + if (vm.keywords.size > 5) " 외 ${vm.keywords.size - 5}개" else "",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2,
            )
            TextButton(onClick = { vm.openSettings(JarvisViewModel.Section.KEYWORDS) }) {
                Text(if (vm.keywords.isEmpty()) "정하기" else "바꾸기")
            }
        }
    }

    Button(
        onClick = { vm.summarizeToday() },
        enabled = vm.hasModel && !vm.busy && vm.pendingCount > 0,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(if (vm.pendingCount > 0) "지금 정리하기 (새 알림 ${vm.pendingCount}건)" else "새 알림이 없어요 · 모두 정리됨",
            fontWeight = FontWeight.Bold)
    }
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
    vm.roomApp?.let { (pkg, name) -> RoomPickerDialog(vm, pkg, name) }
    vm.studentView?.let { (name, entries) -> StudentDialog(vm, name, entries) }

    // 칸 고르기: 한 번에 한 칸만 보여서 길게 스크롤하지 않음
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        JarvisViewModel.Section.entries.forEach { s ->
            FilterChip(selected = vm.section == s, onClick = { vm.section = s }, label = { Text(s.label) })
        }
    }
    when (vm.section) {
        JarvisViewModel.Section.APPS -> AppCleanupCard(vm)
        JarvisViewModel.Section.STUDENTS -> StudentsCard(vm)
        JarvisViewModel.Section.KEYWORDS -> KeywordCard(vm)
        JarvisViewModel.Section.QUIET -> QuietCard(vm)
        JarvisViewModel.Section.TIMES -> TimesCard(vm)
        JarvisViewModel.Section.BASIC -> BasicSettings(vm)
    }
}

@Composable
private fun TimesCard(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    val hm = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }
    val on = vm.slots.filter { it.on }

    // 지금 설정 한 줄 요약
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("⏰ 자동 브리핑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (on.isEmpty()) "아직 꺼져 있어요. 아래에서 아침·저녁 시각을 눌러 켜 보세요."
                else (if (vm.skipWeekend) "평일 " else "매일 ") + on.joinToString(", ") { hm(it.minute) } + "에 새 알림을 정리해 알림으로 보내 드려요.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    // 아침·저녁: 스위치로 켜고, 시각 버튼을 눌러 고르기 (없으면 ⌚ 직접)
    vm.slots.forEach { s ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.slot.label, fontWeight = FontWeight.Bold)
                        Text(if (s.on) "${hm(s.minute)}에 받아요" else "꺼짐", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = s.on, onCheckedChange = { vm.setSlot(s.slot, it, s.minute) })
                }
                if (s.on) {
                    val custom = s.minute !in s.slot.presets
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.slot.presets.forEach { p ->
                            FilterChip(selected = s.minute == p, onClick = { vm.setSlot(s.slot, true, p) },
                                label = { Text(hm(p), style = MaterialTheme.typography.titleSmall) })
                        }
                        FilterChip(selected = custom, onClick = {
                            TimePickerDialog(ctx, { _, h, m -> vm.setSlot(s.slot, true, h * 60 + m) },
                                s.minute / 60, s.minute % 60, true).show()
                        }, label = { Text(if (custom) "⌚ ${hm(s.minute)}" else "⌚ 직접", style = MaterialTheme.typography.titleSmall) })
                    }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🛌 주말에는 쉬기", fontWeight = FontWeight.Bold)
                    Text("토·일에는 자동 브리핑을 보내지 않아요. 월요일 아침에 한 번에 정리돼요.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vm.skipWeekend, onCheckedChange = { vm.updateSkipWeekend(it) })
            }
            OutlinedButton(onClick = { vm.briefNow() }, enabled = vm.hasModel && !vm.busy,
                modifier = Modifier.fillMaxWidth()) { Text("지금 브리핑 받아보기 (테스트)") }
        }
    }
}

@Composable
private fun BasicSettings(vm: JarvisViewModel) {
    val ctx = LocalContext.current
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
            // 늘 보이는 안내: 목록은 실제로 알림이 온 앱만 나옴
            Surface(color = Ssam.Chalk.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium) {
                Text(
                    "ℹ️ 이 목록에는 폰에 실제로 알림이 온 앱만 나와요. 카톡·문자·밴드 같은 메시지·SNS 앱은 " +
                    "그 앱의 알림을 켜 두어야 쌤톡스가 모을 수 있고, 여기 목록에도 떠요.",
                    Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
            if (vm.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ssam.Sprout)

            // 설치돼 있는데 알림이 0건인 메시지 앱 (카톡 등): 원인 안내 + 그 앱의 알림 설정 바로 열기
            if (vm.silentApps.isNotEmpty()) {
                val ctx = LocalContext.current
                Text("⚠️ 알림이 안 들어오는 메시지 앱", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, color = Ssam.Blush)
                vm.silentApps.forEach { (pkg, name) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$name · 최근 7일 0건", fontWeight = FontWeight.Medium)
                        Text(
                            if (pkg == "com.kakao.talk")
                                "카톡 알림이 꺼져 있거나, PC 카톡을 쓰는 중이라 폰 알림이 멈춰 있을 수 있어요. " +
                                "카톡 → 설정 → 알림 켜기, PC 카톡 → 설정 → 알림에서 'PC 사용 중 모바일 알림 끄기'를 해제하세요."
                            else "이 앱의 알림이 꺼져 있으면 쌤톡스도 모을 수 없어요.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
                        }) { Text("$name 알림 설정 열기", color = Ssam.Chalk) }
                    }
                }
                if (!vm.listenerEnabled) Text("쌤톡스의 '알림 접근' 권한도 꺼져 있어요. 아래 기본 설정에서 켜 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = Ssam.Blush)
            }

            if (recs.isNotEmpty()) {
                val drop = recs.count { !it.keep }
                Button(
                    onClick = { vm.applyAppRecs() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink),
                ) { Text(if (drop > 0) "추천대로 적용 (앱 ${drop}개 빼기)" else "추천대로 적용", fontWeight = FontWeight.Bold) }
                Text("또는 아래에서 앱마다 켜고 끄세요. 끈 앱은 모으지도, 요약하지도 않아요.",
                    style = MaterialTheme.typography.bodySmall)

                // 그 밖의 앱은 접어 둠 (목록이 길어지지 않게). 눌러서 펼치기
                var showOthers by rememberSaveable { mutableStateOf(false) }
                val others = recs.filterNot { AppAdvisor.isMessaging(it.stat) }
                recs.forEachIndexed { i, r ->
                    // 묶음 제목: 메시지·SNS 앱이 위, 그 밖의 앱은 아래
                    val msg = AppAdvisor.isMessaging(r.stat)
                    if (i == 0 || msg != AppAdvisor.isMessaging(recs[i - 1].stat)) {
                        if (msg) Text("💬 메시지·SNS 앱", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        else TextButton(onClick = { showOthers = !showOthers }, contentPadding = PaddingValues(0.dp)) {
                            Text("📱 그 밖의 앱 ${others.size}개 (빼기 추천 ${others.count { !it.keep }}) " +
                                if (showOthers) "▾ 접기" else "▸ 펼치기",
                                color = Ssam.Chalk, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (!msg && !showOthers) return@forEachIndexed
                    val keepNow = r.stat.pkg !in vm.excludedApps
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.stat.app} · ${r.stat.count}건", fontWeight = FontWeight.Medium)
                            Text((if (r.keep) "추천: 모으기 — " else "추천: 빼기 — ") + r.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (r.keep) Ssam.Sprout else Ssam.Blush)
                            // 메시지·SNS 앱은 방 단위로 고르기
                            if (msg && keepNow) {
                                val off = vm.excludedRoomCount(r.stat.pkg)
                                TextButton(onClick = { vm.openRooms(r.stat.pkg, r.stat.app) },
                                    contentPadding = PaddingValues(0.dp)) {
                                    Text(if (off > 0) "💬 방 고르기 (뺀 방 ${off}개) ›" else "💬 방 고르기 ›",
                                        color = Ssam.Chalk, style = MaterialTheme.typography.labelLarge)
                                }
                            }
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

/**
 * 👪 학부모 연락: 지정한 학부모 방의 오늘 연락을 분류해 보여 주고, 답장 추천·복사·카톡 열기·답장함 표시.
 * 학부모 방을 아직 지정하지 않았으면 설정으로 안내.
 */
@Composable
private fun ParentCard(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }

    if (vm.parentRooms.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("👪 학부모 방을 지정하면 학부모 연락만 따로 모아 답장까지 추천해 드려요.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.openSettings(JarvisViewModel.Section.APPS) }) { Text("지정하기") }
            }
        }
        return
    }
    val open = vm.parentContacts.filter { it.key !in vm.handledKeys }
    val needReply = open.count { it.category.needsReply }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (open.isEmpty()) "👪 학부모 연락 · 오늘 처리할 연락이 없어요"
                 else "👪 학부모 연락 ${open.size}건 · 답장 필요 $needReply",
                fontWeight = FontWeight.Bold)
            open.forEach { c ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.room + if (c.students.isNotEmpty()) " · 🧒 " + c.students.joinToString(", ") else "",
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    Surface(shape = MaterialTheme.shapes.small,
                        color = if (c.category.needsReply) Ssam.Blush.copy(alpha = 0.25f) else Ssam.Sprout.copy(alpha = 0.3f)) {
                        Text(c.category.label + if (c.category.needsReply) " · 답장 필요" else "",
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(timeFmt.format(c.last.time), style = MaterialTheme.typography.bodySmall)
                }
                c.messages.takeLast(2).forEach {
                    Text(it.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.draftReply(c) }, enabled = vm.draftingKey == null) {
                        Text(if (vm.draftingKey == c.key) "만드는 중…" else "💬 답장 추천")
                    }
                    TextButton(onClick = { vm.markHandled(c) }) { Text("답장함 ✓") }
                }
                vm.drafts[c.key]?.forEach { d ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(d, style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(d))
                                    android.widget.Toast.makeText(ctx, "복사했어요. 카톡에 붙여 넣으세요.", android.widget.Toast.LENGTH_SHORT).show()
                                }) { Text("복사") }
                                TextButton(onClick = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(d))
                                    ctx.packageManager.getLaunchIntentForPackage(c.pkg)?.let { ctx.startActivity(it) }
                                }) { Text("복사하고 ${c.app} 열기") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 📌 할 일·마감 (브리핑 탭) ─────────────────────────

/** 알림에서 뽑은 할 일·마감: 오늘 / D-1 / 지남 표시, 체크로 끝내기, 직접 추가. */
@Composable
private fun TasksCard(vm: JarvisViewModel) {
    val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
    val dayFmt = remember { SimpleDateFormat("M/d(E)", Locale.KOREA) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val open = vm.tasks.filter { !it.done }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val today = open.count { it.dLabel() == "오늘" || it.dLabel() == "지남" }
                Text(if (open.isEmpty()) "📌 할 일·마감 · 없어요" else "📌 할 일·마감 ${open.size}건" + if (today > 0) " · 오늘 $today" else "",
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { adding = !adding }) { Text(if (adding) "닫기" else "＋ 추가") }
            }
            if (open.isEmpty() && !adding) Text("\"내일까지 제출\", \"금요일 회의 14:30\" 같은 연락이 오면 여기에 자동으로 모여요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (adding) {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    placeholder = { Text("할 일 (예: 체험학습 동의서 걷기)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("오늘" to 0, "내일" to 1, "모레" to 2).forEach { (label, d) ->
                        Button(onClick = { vm.addTask(input, d); input = ""; adding = false }, enabled = input.isNotBlank()) { Text(label) }
                    }
                }
            }
            (if (showAll) open else open.take(5)).forEach { t ->
                val label = t.dLabel()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = false, onCheckedChange = { vm.setTaskDone(t, true) })
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text((if (t.hasTime) fmt.format(t.due) else dayFmt.format(t.due)) + " · " + t.room,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Surface(shape = MaterialTheme.shapes.small, color = when (label) {
                        "지남", "오늘" -> Ssam.Blush.copy(alpha = 0.3f)
                        "D-1" -> Ssam.Sprout.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }) { Text(label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (open.size > 5) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "접기" else "${open.size - 5}건 더 보기") }
        }
    }
}

// ───────────────────────── 🧒 우리 반 (설정) ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudentsCard(vm: JarvisViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🧒 우리 반 명부", fontWeight = FontWeight.Bold)
            Text("학생 이름을 넣어 두면 연락에 \"민준이\"처럼 이름만 나와도 알아보고, 학생별 기록에 계속 쌓아요 (1년 보관). " +
                 "학생 이름은 중요 키워드처럼 브리핑 맨 위에 모여요.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it },
                    placeholder = { Text("김민준, 이서윤 … 한 번에 붙여 넣기") }, modifier = Modifier.weight(1f), maxLines = 3)
                Button(onClick = { vm.addStudents(input); input = "" }, enabled = input.isNotBlank()) { Text("추가") }
            }
            if (vm.students.isEmpty()) Text("아직 명부가 비어 있어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text("학생 ${vm.students.size}명 · 눌러서 기록 보기", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.students.forEach { name ->
                        val n = vm.studentCounts[name] ?: 0
                        InputChip(selected = n > 0, onClick = { vm.openStudent(name) },
                            label = { Text(if (n > 0) "$name · $n" else name) },
                            trailingIcon = { Text("✕", Modifier.clickable { vm.removeStudent(name) }.padding(4.dp)) })
                    }
                }
            }
        }
    }
}

/** 학생별 기록: 날짜순으로 연락 (분류 함께). */
@Composable
private fun StudentDialog(vm: JarvisViewModel, name: String, entries: List<StudentEntry>) {
    val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
    AlertDialog(
        onDismissRequest = { vm.closeStudent() },
        title = { Text("🧒 $name 기록 · ${entries.size}건") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entries.isEmpty()) Text("아직 기록이 없어요. 이 학생 이름이 들어간 연락이 오면 여기에 쌓여요.")
                entries.forEach { e ->
                    val cat = ParentAdvisor.classify(listOf(NotiItem(0, "", e.app, e.sender, e.text, e.time, e.room)))
                    Column {
                        Text("${fmt.format(e.time)} · ${e.room} · ${cat.label}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text((if (e.sender != e.room) "${e.sender}: " else "") + e.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeStudent() }) { Text("닫기") } },
    )
}

// ───────────────────────── 🏫 수업 중 · 🌙 퇴근 모드 (설정) ─────────────────────────

@Composable
private fun QuietCard(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    val rev = vm.quietRev   // 설정이 바뀌면 다시 그림 (이 값을 읽어 두면 바뀔 때 화면이 새로 그려짐)
    if (rev < 0) return
    val hm = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }
    fun pick(start: Int, onPick: (Int) -> Unit) =
        TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, start / 60, start % 60, true).show()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🏫 수업 중 모드", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("수업 시간에는 메시지 알림을 숨기고, 수업이 끝나면 \"수업 중 알림 N건\"을 한 번 알려 줘요 (평일).",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = QuietMode.classOn(ctx), onCheckedChange = { on -> vm.quiet { putBoolean("class_on", on) } })
            }
            if (QuietMode.classOn(ctx)) {
                val len = QuietMode.periodLen(ctx)
                QuietMode.periods(ctx).forEachIndexed { i, start ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}교시", Modifier.width(52.dp))
                        TextButton(onClick = { pick(start) { vm.setPeriod(i, it) } }) { Text("${hm(start)} ~ ${hm(start + len)}") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.removePeriod(i) }) { Text("빼기") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pick(QuietMode.periods(ctx).maxOrNull()?.plus(len + 10) ?: 540) { vm.setPeriod(99, it) } }) { Text("＋ 교시 추가") }
                    Text("수업 길이", style = MaterialTheme.typography.bodySmall)
                    listOf(40, 45, 50).forEach { l ->
                        FilterChip(selected = len == l, onClick = { vm.quiet { putInt("period_len", l) } }, label = { Text("${l}분") })
                    }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🌙 퇴근 모드", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("퇴근 후·출근 전에 온 메시지 알림을 숨겼다가 아침 브리핑으로 전해 줘요. 퇴근 후 학부모 연락에는 " +
                     "\"근무시간 이후라 내일 확인드리겠습니다\" 답장을 먼저 추천해요.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = QuietMode.afterWorkOn(ctx), onCheckedChange = { on -> vm.quiet { putBoolean("after_on", on) } })
            }
            if (QuietMode.afterWorkOn(ctx)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("출근", Modifier.width(52.dp))
                    TextButton(onClick = { pick(QuietMode.workStart(ctx)) { m -> vm.quiet { putInt("work_start", m) } } }) { Text(hm(QuietMode.workStart(ctx))) }
                    Text("퇴근", Modifier.padding(start = 12.dp).width(40.dp))
                    TextButton(onClick = { pick(QuietMode.workEnd(ctx)) { m -> vm.quiet { putInt("work_end", m) } } }) { Text(hm(QuietMode.workEnd(ctx))) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("주말에도 하루 종일", Modifier.weight(1f))
                    Switch(checked = QuietMode.weekendQuiet(ctx), onCheckedChange = { on -> vm.quiet { putBoolean("weekend", on) } })
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("상태바에서 알림 숨기기", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Switch(checked = QuietMode.hideInShade(ctx), onCheckedChange = { on -> vm.quiet { putBoolean("hide", on) } })
            }
            val dnd = vm.dndGranted()
            Text(if (dnd) "✅ 방해 금지 권한 있음 · 수업 중·퇴근 후에는 소리·진동도 꺼져요 (선생님이 직접 켠 방해 금지는 건드리지 않아요)"
                 else "🔈 지금은 알림을 숨기기만 하고 소리는 그대로예요. 소리까지 끄려면 방해 금지 권한을 허용해 주세요.",
                style = MaterialTheme.typography.bodySmall)
            if (!dnd) OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }) { Text("방해 금지 권한 허용하기") }
        }
    }
}

/** 방 고르기: 이 앱에서 최근 7일 알림이 온 방을 많은 순으로, 스위치로 켜고 끄기. */
@Composable
private fun RoomPickerDialog(vm: JarvisViewModel, pkg: String, name: String) {
    AlertDialog(
        onDismissRequest = { vm.closeRooms() },
        title = { Text("$name · 방 고르기") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("끈 방은 모으지도, 요약하지도 않아요. 단, 중요 키워드가 들어간 알림은 모아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.rooms.isEmpty()) Text("최근 7일 동안 알림이 온 방이 없어요.", Modifier.padding(vertical = 12.dp))
                else Row {
                    TextButton(onClick = { vm.setAllRooms(pkg, true) }) { Text("모두 켜기") }
                    TextButton(onClick = { vm.setAllRooms(pkg, false) }) { Text("모두 끄기") }
                }
                // 학부모 방 추천: 이름에 어머님·아버님·학부모 등이 있는 방
                val suggested = vm.suggestedParentRooms(pkg)
                if (suggested.isNotEmpty()) {
                    Button(onClick = { vm.setAllSuggestedParents(pkg) }, modifier = Modifier.fillMaxWidth()) {
                        Text("👪 학부모 방 추천 모두 지정 (${suggested.size})")
                    }
                }
                Text("👪를 눌러 학부모 방으로 지정하면, 그 연락을 따로 모아 답장까지 추천해 드려요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.rooms.forEach { (room, count) ->
                    val parent = vm.isParentRoom(pkg, room)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(room, maxLines = 1, fontWeight = FontWeight.Medium)
                            Text("최근 7일 ${count}건" + if (!parent && ParentAdvisor.looksParent(room)) " · 학부모 추천" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilterChip(selected = parent, onClick = { vm.setParentRoom(pkg, room, !parent) },
                            label = { Text("👪") }, modifier = Modifier.padding(end = 6.dp))
                        Switch(checked = vm.isRoomOn(pkg, room), onCheckedChange = { vm.setRoom(pkg, room, it) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeRooms() }) { Text("완료") } },
    )
}

/** 중요 키워드: 추천 칩을 누르거나 직접 입력. 들어간 알림은 브리핑 맨 위 ⭐에 따로 모임. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun KeywordCard(vm: JarvisViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⭐ 중요 키워드", fontWeight = FontWeight.Bold)
            Text("이 단어가 들어간 알림은 브리핑 맨 위에 따로 모아 보여 줘요. 끈 방에서 와도 놓치지 않아요.",
                style = MaterialTheme.typography.bodySmall)
            if (vm.keywords.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.keywords.forEach { w ->
                    InputChip(selected = true, onClick = { vm.removeKeyword(w) }, label = { Text("$w  ✕") })
                }
            }
            // 최근 알림에서 찾은 추천 (규칙 + AI). 없으면 기본 추천 단어
            val recs = vm.keywordRecs.filter { it.first !in vm.keywords }
            if (recs.isNotEmpty()) {
                Text(if (vm.aiBusy) "🌱 최근 알림에서 찾은 추천 · AI가 더 살펴보는 중…" else "🌱 최근 알림에서 찾은 추천",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recs.forEach { (w, n) -> AssistChip(onClick = { vm.addKeyword(w) }, label = { Text("+ $w · ${n}번") }) }
                }
                OutlinedButton(onClick = { vm.addAllKeywordRecs() }) { Text("추천 모두 추가") }
            }
            val suggestions = AppFilter.SUGGESTED_KEYWORDS.filter { it !in vm.keywords && recs.none { r -> r.first == it } }
            if (recs.isEmpty() && suggestions.isNotEmpty()) {
                Text("눌러서 추가", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { w -> AssistChip(onClick = { vm.addKeyword(w) }, label = { Text("+ $w") }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    placeholder = { Text("직접 입력 (예: 학생 이름)") }, modifier = Modifier.weight(1f))
                Button(onClick = { vm.addKeyword(input); input = "" }, enabled = input.isNotBlank()) { Text("추가") }
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
