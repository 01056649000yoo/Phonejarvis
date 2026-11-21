package com.jarvis.phone

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
private const val WHATS_NEW = "👪 학부모 명부: 이름·번호만 등록하면 자동으로 · 정리는 백그라운드에서, 끝나면 알림"

/** 업데이트 내역 큰 창 (버전 올릴 때 같이 고치기) */
private val WHATS_NEW_DETAIL = listOf(
    "👪 학부모 방 고르기 대신 학부모 명부" to "우리 반 → 👪 학부모 명부에서 학생·관계(어머님/아버님)·이름이나 번호만 등록하세요. 문자·카톡·밴드·하이클래스 등 어떤 앱이든 보낸 사람·방 이름·내용에 그 이름·번호가 보이면 자동으로 \"민준 어머님\" 연락으로 모아 답장까지 추천해요.",
    "🙋 한 사람으로 모아요" to "같은 학부모가 문자와 카톡으로 따로 연락해도 오늘 탭 👪 학부모 연락에 한 사람으로 묶여요. 이름·번호·카톡 별명을 여러 개 등록해도 돼요.",
    "🔔 정리는 백그라운드에서" to "지금 정리하기를 누르면 \"완료되면 알림으로 알려드릴게요\" 안내 뒤 앱이 닫히고, 백그라운드에서 정리해요. 다 되면 알림이 오고, 누르면 📄 세부 내역(요약 전체 + 원래 알림)이 바로 열려요.",
    "🧒 명부도 자동으로" to "학부모를 등록할 때 명부에 없는 학생이면 우리 반 명부에 같이 추가돼요.",
    "📋 지난 변화 (v1.0.2)" to "정리(요약)한 알림은 카드에서 사라지고, 오늘 받은 알림 전체는 맨 아래 '🗂 오늘 받은 알림 전체'에서 봐요.",
    "🧭 지난 큰 변화 (v1.0)" to "아래 탭이 📋 오늘 · 🧒 우리 반 · ⚙️ 설정 세 개로 바뀌었고, 설정은 목록에서 세부 화면으로 들어가요.",
)

/**
 * 메인: 아래 탭 세 개
 *  📋 오늘 — 결과만 보기 (브리핑·할 일·학부모 연락)
 *  🧒 우리 반 — 사람 (학생 명부·학부모·중요 키워드)
 *  ⚙️ 설정 — 앱 동작 (알림 앱·시간·AI 모델·백업), 아이폰 '설정'처럼 목록 → 세부 화면
 */
@Composable
fun JarvisScreen(vm: JarvisViewModel) {
    // 폰의 뒤로 버튼: 세부 화면 → 탭 첫 화면 → 오늘 탭
    androidx.activity.compose.BackHandler(enabled = vm.page != null || vm.tab != JarvisViewModel.Tab.BRIEFING) { vm.back() }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(JarvisViewModel.Tab.BRIEFING, "📋", "오늘"),
                    Triple(JarvisViewModel.Tab.CLASS, "🧒", "우리 반"),
                    Triple(JarvisViewModel.Tab.SETTINGS, "⚙️", "설정"),
                ).forEach { (t, icon, label) ->
                    NavigationBarItem(
                        selected = vm.tab == t,
                        onClick = { vm.selectTab(t) },
                        icon = { Text(icon) }, label = { Text(label) },
                    )
                }
            }
        },
    ) { inner ->
        // 화면 위치: (탭, 세부 화면). 세부 화면으로 들어가면 옆에서 밀려 들어오고(iOS처럼), 탭끼리는 살짝 겹쳐 바뀜
        val route = vm.tab to vm.page
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val (fromTab, fromPage) = initialState
                val (toTab, toPage) = targetState
                when {
                    fromTab == toTab && fromPage == null && toPage != null ->      // 들어가기
                        (slideInHorizontally(Motion.offset) { it } + fadeIn(Motion.fade)) togetherWith
                            (slideOutHorizontally(Motion.offset) { -it / 4 } + fadeOut(Motion.fadeFast))
                    fromTab == toTab && fromPage != null && toPage == null ->      // 뒤로
                        (slideInHorizontally(Motion.offset) { -it / 4 } + fadeIn(Motion.fade)) togetherWith
                            (slideOutHorizontally(Motion.offset) { it } + fadeOut(Motion.fadeFast))
                    else ->                                                        // 탭 바꾸기
                        (fadeIn(Motion.fade) + scaleIn(Motion.fade, initialScale = 0.985f)) togetherWith fadeOut(Motion.fadeFast)
                }
            },
            modifier = Modifier.padding(inner),
            label = "route",
        ) { (tab, page) ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                if (page == null) Header(vm, tab) else PageHeader(vm, page)
                if (page != null) PageContent(vm, page)
                else when (tab) {
                    JarvisViewModel.Tab.BRIEFING -> BriefingTab(vm)
                    JarvisViewModel.Tab.CLASS -> ClassTab(vm)
                    JarvisViewModel.Tab.SETTINGS -> SettingsTab(vm)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun Header(vm: JarvisViewModel, tab: JarvisViewModel.Tab) {
    if (tab == JarvisViewModel.Tab.BRIEFING) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsamLogo(44.dp)
            Column(Modifier.weight(1f)) {
                Text("쌤톡스", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text("선생님을 위한 디지털 디톡스 · v${vm.appVersion}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        // 우리 반·설정: 큰 제목 (아이폰 설정 앱처럼)
        Text(if (tab == JarvisViewModel.Tab.CLASS) "우리 반" else "설정",
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    }
}

/** 세부 화면 위: ‹ 뒤로 + 제목 */
@Composable
private fun PageHeader(vm: JarvisViewModel, page: JarvisViewModel.Page) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.back() }, contentPadding = PaddingValues(start = 0.dp, end = 8.dp)) {
            Text("‹ " + when (page.tab) { JarvisViewModel.Tab.CLASS -> "우리 반"; JarvisViewModel.Tab.BRIEFING -> "오늘"; else -> "설정" },
                style = MaterialTheme.typography.titleMedium)
        }
    }
    Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

/** 세부 화면 내용: 예전 설정 칸들을 그대로 씀 */
@Composable
private fun PageContent(vm: JarvisViewModel, page: JarvisViewModel.Page) {
    vm.roomApp?.let { (pkg, name) -> RoomPickerDialog(vm, pkg, name) }
    when (page) {
        JarvisViewModel.Page.APPS -> AppCleanupCard(vm)
        JarvisViewModel.Page.TIMES -> TimesCard(vm)
        JarvisViewModel.Page.QUIET -> QuietCard(vm)
        JarvisViewModel.Page.SYSTEM -> SystemSettings(vm)
        JarvisViewModel.Page.BACKUP -> BackupCard(vm)
        JarvisViewModel.Page.PARENTS -> ParentsPage(vm)
        JarvisViewModel.Page.KEYWORDS -> KeywordCard(vm)
        JarvisViewModel.Page.TODAY_ALL -> TodayAllPage(vm)
    }
}

/** 아이폰 설정 앱 같은 한 줄 메뉴: 아이콘·제목·지금 상태·› */
@Composable
private fun MenuGroup(rows: List<Triple<String, String, () -> Unit>>) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { i, (title, status, onClick) ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, modifier = Modifier.padding(start = 8.dp).widthIn(max = 180.dp))
                    Text("  ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ───────────────────────── 브리핑 탭: 결과만 ─────────────────────────

@Composable
private fun BriefingTab(vm: JarvisViewModel) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val dayTimeFmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREA) }

    // 🆕 업데이트 내역: 업데이트 뒤 처음 한 번만, 8초 뒤 저절로 사라짐. 누르면 큰 창으로
    var whatsNewOpen by rememberSaveable { mutableStateOf(false) }
    if (vm.showWhatsNew) {
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(8_000); if (!whatsNewOpen) vm.closeWhatsNew() }
    }
    AnimatedVisibility(visible = vm.showWhatsNew, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
        exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
        Card(Modifier.fillMaxWidth().clickable { whatsNewOpen = true }) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🆕 v${vm.appVersion} 업데이트 · $WHATS_NEW", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Text("자세히 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (whatsNewOpen) AlertDialog(
        onDismissRequest = { whatsNewOpen = false; vm.closeWhatsNew() },
        title = { Text("🆕 v${vm.appVersion} 바뀐 점") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WHATS_NEW_DETAIL.forEach { (title, body) ->
                    Column {
                        Text(title, fontWeight = FontWeight.Bold)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { whatsNewOpen = false; vm.closeWhatsNew() }) { Text("확인") } },
    )

    vm.briefingDetail?.let { (b, items) -> BriefingDetailDialog(vm, b, items) }
    // 최신 브리핑 카드: 접으면 "언제·몇 건"만, 숨기면 맨 위에서 빠짐 (맨 아래 📚 모음에는 남음)
    val latest = vm.briefings.firstOrNull()
    when {
        latest != null && latest.time.toString() !in vm.hiddenBriefings ->
            BriefingCard(vm, latest, primary = true, onHide = { vm.hideBriefing(latest) })
        latest == null -> Card(Modifier.fillMaxWidth()) {
            Text("아직 브리핑이 없어요. 아래 버튼을 누르거나, 설정에서 정한 시각을 기다리세요.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        else -> {}
    }

    TasksCard(vm)
    ParentCard(vm)

    val ctx = LocalContext.current
    Button(
        onClick = {
            vm.summarizeToday()
            // 🔔 안내 후 앱을 뒤로 보내기 (알림이 꺼져 있으면 결과를 못 받으니 화면에 남음)
            if (androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                android.widget.Toast.makeText(ctx, "정리를 시작했어요. 완료되면 알림으로 알려드릴게요 🔔", android.widget.Toast.LENGTH_LONG).show()
                (ctx as? android.app.Activity)?.moveTaskToBack(true)
            } else {
                android.widget.Toast.makeText(ctx, "알림이 꺼져 있어서 완료 알림을 못 보내요. 이 화면에서 기다려 주세요.", android.widget.Toast.LENGTH_LONG).show()
            }
        },
        enabled = vm.hasModel && !vm.busy && !vm.bgRunning && vm.pendingCount > 0,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(when {
            vm.bgRunning -> "백그라운드에서 정리 중… 다 되면 알림"
            vm.pendingCount > 0 -> "지금 정리하기 (새 알림 ${vm.pendingCount}건)"
            else -> "새 알림이 없어요 · 모두 정리됨"
        }, fontWeight = FontWeight.Bold)
    }
    if (!vm.bgRunning && vm.pendingCount > 0) Text("🔔 누르면 백그라운드에서 정리하고 앱은 닫혀요. 다 되면 알림이 오고, 누르면 세부 내역을 볼 수 있어요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.busy || vm.bgRunning) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick = { vm.stopSummary() }, enabled = !vm.stopping, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.stopping) "멈추는 중…" else "중지 (지금까지 정리한 것만 보기)")
        }
    }
    if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
    if (!vm.hasModel) Text("AI 모델이 없어요. 설정 탭에서 받아 주세요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)

    // 아직 정리(요약) 안 한 알림만: 지금 정리하기를 하면 사라짐. 오늘 전체는 맨 아래 메뉴에서
    if (vm.pendingItems.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val apps = vm.pendingItems.map { it.app }.distinct()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("아직 정리 안 한 알림 ${vm.pendingItems.size}건", fontWeight = FontWeight.Bold)
                        Text(apps.take(3).joinToString(", ") + if (apps.size > 3) " 외 ${apps.size - 3}개 앱" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "원본 보기") }
                }
                AnimatedVisibility(visible = expanded, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
                    exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.pendingItems.takeLast(30).reversed().forEach {
                            Text("${timeFmt.format(it.time)} [${it.app}] ${it.title}: ${it.text}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }

    // 📚 브리핑 모음: 맨 아래, 지난 브리핑을 한 줄씩. 누르면 펼쳐 보기 (최근 7일)
    if (vm.briefings.isNotEmpty()) {
        var open by rememberSaveable { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📚 브리핑 모음 · ${vm.briefings.size}개", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { open = !open }) { Text(if (open) "접기" else "펼치기") }
                }
                AnimatedVisibility(visible = open, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
                    exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.briefings.forEach { b ->
                            var show by rememberSaveable(b.time) { mutableStateOf(false) }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().clickable { show = !show }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${briefingIcon(b)} ${b.label} · ${dayTimeFmt.format(b.time)} · ${b.count}건",
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(if (show) "▾" else "▸")
                            }
                            AnimatedVisibility(visible = show, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
                                exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                                Text(b.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    // 🗂 맨 아래: 오늘 받은 알림 전체 (정리한 것까지 모두)
    MenuGroup(listOf(
        Triple("🗂 오늘 받은 알림 전체", "${vm.todayItems.size}건") { vm.open(JarvisViewModel.Page.TODAY_ALL) },
    ))
}

/** 🗂 오늘 받은 알림 전체: 앱·방별로 묶어 최근 것부터 (정리한 알림 포함) */
@Composable
private fun TodayAllPage(vm: JarvisViewModel) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    if (vm.todayItems.isEmpty()) {
        Text("오늘 모은 알림이 아직 없어요.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("오늘 ${vm.todayItems.size}건 · 정리 안 한 알림 ${vm.pendingItems.size}건", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    vm.todayItems.groupBy { it.app to it.roomOrTitle }.entries
        .sortedByDescending { e -> e.value.maxOf { it.time } }
        .forEach { (key, list) ->
            var open by rememberSaveable(key.first + key.second) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.animateContentSize(Motion.size).clickable { open = !open }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(key.second, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("${key.first} · ${list.size}건 · 마지막 ${timeFmt.format(list.maxOf { it.time })}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (open) "▾" else "▸")
                    }
                    val shown = if (open) list.takeLast(50).reversed() else list.takeLast(1)
                    shown.forEach {
                        val who = if (it.room.isNotEmpty() && it.title != it.room) "${it.title}: " else ""
                        Text("${timeFmt.format(it.time)}  $who${it.text}", style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (open) 6 else 1)
                    }
                }
            }
        }
}

private fun briefingIcon(b: Briefing) = when {
    "아침" in b.label -> "🌅"
    "저녁" in b.label -> "🌙"
    else -> "📋"
}

/** 📄 브리핑 세부 내역: 요약 전체 + 이번에 정리한 원래 알림(앱·방별). 완료 알림을 누르면 바로 열림. */
@Composable
private fun BriefingDetailDialog(vm: JarvisViewModel, b: Briefing, items: List<NotiItem>) {
    val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    var showRaw by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.closeBriefingDetail() },
        title = {
            Column {
                Text("${briefingIcon(b)} ${b.label}", fontWeight = FontWeight.Bold)
                Text("${fmt.format(b.fromTime)} ~ ${timeFmt.format(b.time)} · 알림 ${b.count}건",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(b.text, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "▾ 원래 알림 접기" else "▸ 원래 알림 ${items.size}건 보기")
                }
                AnimatedVisibility(visible = showRaw, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
                    exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.groupBy { it.app to it.roomOrTitle }.forEach { (k, list) ->
                            Column {
                                Text("${k.first} · ${k.second} (${list.size})", fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge)
                                list.sortedBy { it.time }.forEach { n ->
                                    val who = if (n.room.isNotEmpty() && n.title != n.room) "${n.title}: " else ""
                                    Text("${timeFmt.format(n.time)} $who${n.text}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (items.isEmpty()) Text("원래 알림은 7일이 지나 지워졌어요.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { vm.closeBriefingDetail() }) { Text("닫기") } },
    )
}

/**
 * 브리핑 카드: 제목 줄(언제·몇 건)만 보이게 접을 수 있고, 숨기면 맨 위에서 빠짐.
 * 한 번 접으면 다시 열어도 접힌 채로 (보고 나면 접어 두기).
 */
@Composable
private fun BriefingCard(vm: JarvisViewModel, b: Briefing, primary: Boolean, onHide: () -> Unit) {
    val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
    val collapsed = b.time.toString() in vm.collapsedBriefings
    Card(Modifier.fillMaxWidth(), colors = if (primary) CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { vm.toggleBriefing(b) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${briefingIcon(b)} ${b.label}", fontWeight = FontWeight.Bold)
                    Text("${fmt.format(b.time)} · 알림 ${b.count}건", style = MaterialTheme.typography.labelMedium)
                }
                Text(if (collapsed) "▸ 펼치기" else "▾ 접기", style = MaterialTheme.typography.labelLarge)
            }
            // 🎬 접기·펼치기: 아래로 스르륵
            AnimatedVisibility(visible = !collapsed, enter = expandVertically(Motion.size) + fadeIn(Motion.fade),
                exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(b.text, style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.openBriefingDetail(b) }) {
                            Text("📄 세부 내역", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        TextButton(onClick = { vm.toggleBriefing(b) }) {
                            Text("다 봤어요 · 접기", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        TextButton(onClick = onHide) { Text("숨기기", color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 설정 탭: 내 방식대로 ─────────────────────────

@Composable
private fun SettingsTab(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    val rev = vm.quietRev   // 수업·퇴근 설정이 바뀌면 상태 줄도 다시 그림
    val hm = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }
    val fmt = remember { SimpleDateFormat("M/d", Locale.KOREA) }
    val slotsOn = vm.slots.filter { it.on }
    val quietStatus = listOfNotNull(
        if (QuietMode.classOn(ctx)) "수업 중 켜짐" else null,
        if (QuietMode.afterWorkOn(ctx)) "퇴근 ${hm(QuietMode.workEnd(ctx))}" else null,
    ).joinToString(" · ").ifEmpty { if (rev >= 0) "꺼짐" else "" }
    val permsMissing = listOf(vm.listenerEnabled, vm.notiPermission).count { !it }

    MenuGroup(listOf(
        Triple("🌱 알림 앱 정리", "뺀 앱 ${vm.excludedApps.size}개 · 광고 ${vm.adCount}건") { vm.open(JarvisViewModel.Page.APPS) },
        Triple("⏰ 브리핑 시각",
            if (slotsOn.isEmpty()) "꺼짐" else (if (vm.skipWeekend) "평일 " else "") + slotsOn.joinToString(" · ") { hm(it.minute) }) {
            vm.open(JarvisViewModel.Page.TIMES) },
        Triple("🏫 수업·퇴근 모드", quietStatus) { vm.open(JarvisViewModel.Page.QUIET) },
    ))
    MenuGroup(listOf(
        Triple("🤖 AI 모델·권한",
            (vm.installedModel?.title ?: if (vm.hasModel) "모델 있음" else "모델 없음") + if (permsMissing > 0) " · 권한 확인 $permsMissing" else "") {
            vm.open(JarvisViewModel.Page.SYSTEM) },
        Triple("💾 백업·복원",
            (if (vm.lastBackup > 0) "${fmt.format(vm.lastBackup)} 백업" else "백업 없음") + if (vm.hasBackupPin) " · 🔒" else "") {
            vm.open(JarvisViewModel.Page.BACKUP) },
    ))
    Text("쌤톡스 v${vm.appVersion} · 알림은 폰 밖으로 나가지 않아요", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
}

// ───────────────────────── 🧒 우리 반 탭: 사람 ─────────────────────────

@Composable
private fun ClassTab(vm: JarvisViewModel) {
    vm.studentView?.let { (name, entries) -> StudentDialog(vm, name, entries) }
    StudentsCard(vm)
    val linked = vm.parentLinks.size
    MenuGroup(listOf(
        Triple("👪 학부모 명부", if (linked == 0) "이름·번호만 등록하면 자동으로 모아요" else "등록 ${vm.parentLinks.map { it.student to it.relation }.distinct().size}명") { vm.open(JarvisViewModel.Page.PARENTS) },
        Triple("⭐ 중요 키워드",
            if (vm.keywords.isEmpty()) "아직 없음" else vm.keywords.take(2).joinToString(", ") + if (vm.keywords.size > 2) " 외 ${vm.keywords.size - 2}개" else "") {
            vm.open(JarvisViewModel.Page.KEYWORDS) },
    ))
}

/**
 * 👪 학부모 명부: 학생·관계·이름(별명·번호)만 등록하면,
 * 어떤 앱이든 보낸 사람·방 이름·내용에 그 이름·번호가 들어간 알림을 자동으로 그 학부모 연락으로 정리.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentsPage(vm: JarvisViewModel) {
    LaunchedEffect(Unit) { vm.loadRecentSenders() }
    var student by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("어머님") }
    var match by rememberSaveable { mutableStateOf("") }

    Text("학부모 이름이나 번호를 한 번만 등록하면, 문자·카톡·밴드·하이클래스 등 어떤 앱이든 그 이름·번호가 들어간 알림을 " +
         "자동으로 \"민준 어머님\" 연락으로 모아 답장까지 추천해요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // ＋ 등록
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("＋ 학부모 등록", fontWeight = FontWeight.Bold)
            Text("① 학생", style = MaterialTheme.typography.labelLarge)
            if (vm.students.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.students.forEach { s -> FilterChip(selected = student == s, onClick = { student = s }, label = { Text(s) }) }
            }
            OutlinedTextField(value = student, onValueChange = { student = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("학생 이름 (명부에 없으면 새로 추가돼요)") })
            Text("② 관계", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("어머님", "아버님", "보호자").forEach { r -> FilterChip(selected = relation == r, onClick = { relation = r }, label = { Text(r) }) }
            }
            Text("③ 학부모 이름·별명·번호", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(value = match, onValueChange = { match = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("예: 민준맘, 김영희, 010-1234-5678") })
            if (vm.recentSenders.isNotEmpty()) {
                Text("최근 연락한 사람에서 고르기", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.recentSenders.forEach { s -> AssistChip(onClick = { match = s }, label = { Text(s) }) }
                }
            }
            Button(
                onClick = { vm.addParentLink(student, relation, match); match = "" },
                enabled = student.length >= 2 && match.trim().length >= 2,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink),
            ) { Text(if (student.length >= 2) "${if (student.length == 3) student.substring(1) else student} $relation 으로 등록" else "등록", fontWeight = FontWeight.Bold) }
            Text("이름·번호 하나만 넣어도 되고, 같은 학부모를 여러 번(이름, 번호, 카톡 별명) 등록해도 한 사람으로 모여요.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    // 등록된 학부모 (학생별)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("👪 등록된 학부모 ${vm.parentLinks.map { it.student to it.relation }.distinct().size}명", fontWeight = FontWeight.Bold)
            if (vm.parentLinks.isEmpty()) Text("아직 없어요. 위에서 등록해 주세요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.parentLinks.groupBy { it.student to it.relation }.forEach { (k, links) ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { vm.openStudent(k.first) }) {
                        Text("🧒 ${k.first} · ${k.second}", fontWeight = FontWeight.Medium)
                        Text("학생 기록 보기 ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    links.forEach { l -> InputChip(selected = true, onClick = { vm.removeParentLink(l) }, label = { Text("${l.match}  ✕") }) }
                }
            }
        }
    }
    vm.studentView?.let { (name, entries) -> StudentDialog(vm, name, entries) }
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
                else (if (vm.skipWeekend) "평일 " else "매일 ") + on.joinToString(", ") { hm(it.minute) } + "에 새 알림을 정리해 알림으로 보내 드려요.\n" +
                    "📦 정한 시각 ${BriefingScheduler.LEAD_MINUTES}분 전에 미리 정리해 두고, 그 시각엔 결과만 바로 보여 줘요.",
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
private fun SystemSettings(vm: JarvisViewModel) {
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
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            // 🚫 광고 자동 거르기 (문자·카톡의 광고성 메시지)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🚫 광고 자동 거르기", fontWeight = FontWeight.Bold)
                    Text(if (vm.adFilterOn) "오늘 광고 ${vm.adCount}건을 걸렀어요 · (광고)·수신거부·할인·쿠폰 등. 등록한 학부모·키워드는 거르지 않아요"
                         else "꺼짐 · 광고성 문자·카톡도 모아요", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vm.adFilterOn, onCheckedChange = { vm.setAdFilter(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Ssam.Sprout, checkedThumbColor = Ssam.Ink))
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

            // 한 번 적용한 뒤에는 새로 알림을 보낸 앱이 생길 때만 다시 물어봄
            val pending = if (vm.appsApplied) vm.newAppRecs else recs
            if (recs.isNotEmpty() && pending.isNotEmpty()) {
                val drop = pending.count { !it.keep }
                if (vm.appsApplied) Text("🆕 새로 알림을 보낸 앱 ${pending.size}개: " + pending.take(3).joinToString(", ") { it.stat.app },
                    fontWeight = FontWeight.Medium)
                Button(
                    onClick = { vm.applyAppRecs() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink),
                ) { Text(if (drop > 0) "추천대로 적용 (앱 ${drop}개 빼기)" else "추천대로 적용", fontWeight = FontWeight.Bold) }
                Text("또는 아래에서 앱마다 켜고 끄세요. 끈 앱은 모으지도, 요약하지도 않아요.",
                    style = MaterialTheme.typography.bodySmall)
            } else if (recs.isNotEmpty()) {
                // ✅ 이미 정리 완료: 추천 버튼 대신 한 줄. 앱 목록은 아래에 항상 보임 (여기가 알림 앱을 고르는 곳)
                Text("✅ 정리 완료 · 뺀 앱 ${vm.excludedApps.size}개 · 아래에서 앱마다 켜고 끄세요",
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (recs.isNotEmpty()) {

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
            }
            if (recs.isEmpty()) {
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

    if (vm.parentLinks.isEmpty() && vm.parentContacts.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("👪 학부모 이름·번호를 등록하면 어떤 앱으로 와도 학부모 연락만 따로 모아 답장까지 추천해 드려요.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.open(JarvisViewModel.Page.PARENTS) }) { Text("등록하기") }
            }
        }
        return
    }
    val open = vm.parentContacts.filter { it.key !in vm.handledKeys }
    val needReply = open.count { it.category.needsReply }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (open.isEmpty()) "👪 학부모 연락 · 오늘 처리할 연락이 없어요"
                 else "👪 학부모 연락 ${open.size}건 · 답장 필요 $needReply",
                fontWeight = FontWeight.Bold)
            open.forEach { c ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.displayName + if (c.link == null && c.students.isNotEmpty()) " · 🧒 " + c.students.joinToString(", ") else "",
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
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Text("학생 ${vm.students.size}명 · 눌러서 👪 학부모 연결·기록 보기", style = MaterialTheme.typography.labelMedium,
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
        title = { Text("🧒 $name") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ParentLinkSection(vm, name)
                HorizontalDivider()
                Text("📒 기록 ${entries.size}건", fontWeight = FontWeight.Bold)
                if (entries.isEmpty()) Text("아직 기록이 없어요. 이 학생 이름이 들어간 연락이나 연결한 학부모의 연락이 오면 여기에 쌓여요.")
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

/**
 * 👪 학부모 연결: 이름·번호와 관계를 넣거나, 최근 연락한 사람을 눌러 바로 연결.
 * 연결하면 문자·카톡·밴드·하이클래스·학교종이 어디서 온 연락이든 이 학생의 학부모 연락으로 정리됨.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentLinkSection(vm: JarvisViewModel, student: String) {
    var input by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("어머님") }
    val mine = vm.parentLinks.filter { it.student == student }
    Text("👪 학부모 연결", fontWeight = FontWeight.Bold)
    Text("연결하면 문자·카톡·밴드·하이클래스·학교종이 어디서 온 연락이든 \"${if (student.length == 3) student.substring(1) else student} $relation\" 연락으로 정리돼요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (mine.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        mine.forEach { l -> InputChip(selected = true, onClick = { vm.removeParentLink(l) }, label = { Text("${l.relation} · ${l.match}  ✕") }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("어머님", "아버님", "보호자").forEach { r -> FilterChip(selected = relation == r, onClick = { relation = r }, label = { Text(r) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
            placeholder = { Text("이름·번호 (예: 민준맘, 010-1234-5678)") }, modifier = Modifier.weight(1f))
        Button(onClick = { vm.addParentLink(student, relation, input); input = "" }, enabled = input.isNotBlank()) { Text("연결") }
    }
    if (vm.recentSenders.isNotEmpty()) {
        Text("최근 연락한 사람에서 고르기 ($relation)", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.recentSenders.forEach { s -> AssistChip(onClick = { vm.addParentLink(student, relation, s) }, label = { Text("+ $s") }) }
        }
    }
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
            AnimatedVisibility(visible = QuietMode.classOn(ctx), enter = expandVertically(Motion.size) + fadeIn(Motion.fade), exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ranges = QuietMode.ranges(ctx)
                    // ① 시간표 한 번에: 학교급 버튼
                    Text("시간표 한 번에 넣기", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuietMode.PRESETS.forEach { (name, list) ->
                            FilterChip(selected = ranges == list, onClick = { vm.applyPeriodPreset(list) }, label = { Text(name) })
                        }
                    }
                    // ② 교시마다 시작·끝을 따로 눌러 바꾸기
                    Text("교시마다 시각을 눌러 바꿔요 (시작을 바꾸면 끝도 같이 움직여요)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ranges.forEachIndexed { i, (s, e) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${i + 1}교시", Modifier.width(48.dp), fontWeight = FontWeight.Medium)
                            AssistChip(onClick = { pick(s) { vm.setPeriodStart(i, it) } }, label = { Text(hm(s)) })
                            Text("~")
                            AssistChip(onClick = { pick(e) { vm.setPeriodEnd(i, it) } }, label = { Text(hm(e)) })
                            Text("${e - s}분", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { vm.removePeriod(i) }) { Text("✕") }
                        }
                    }
                    OutlinedButton(onClick = { vm.addPeriod() }) { Text("＋ 교시 추가 (같은 길이, 쉬는 시간 10분 뒤)") }
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
            AnimatedVisibility(visible = QuietMode.afterWorkOn(ctx), enter = expandVertically(Motion.size) + fadeIn(Motion.fade), exit = shrinkVertically(Motion.size) + fadeOut(Motion.fadeFast)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 출근·퇴근: 자주 쓰는 시각 버튼 + ⌚ 직접 (브리핑 시각과 같은 방식)
                    QuietTimeRow(vm, "🌅 출근", QuietMode.workStart(ctx), listOf(7 * 60 + 30, 8 * 60, 8 * 60 + 20, 8 * 60 + 30), "work_start")
                    QuietTimeRow(vm, "🏠 퇴근", QuietMode.workEnd(ctx), listOf(16 * 60 + 30, 16 * 60 + 40, 17 * 60, 18 * 60), "work_end")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("주말에도 하루 종일", Modifier.weight(1f))
                        Switch(checked = QuietMode.weekendQuiet(ctx), onCheckedChange = { on -> vm.quiet { putBoolean("weekend", on) } })
                    }
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

/** 💾 백업·복원: 자동 백업 상태 + 지금 백업 / 파일로 내보내기 / 파일에서 불러오기 */
@Composable
private fun BackupCard(vm: JarvisViewModel) {
    val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importer = rememberLauncherForActivityResult(OpenInBackupFolder()) { uri -> if (uri != null) vm.restoreBackup(uri) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("💾 백업·복원", fontWeight = FontWeight.Bold)
            Text("앱을 지웠다 다시 깔아도 명부·학부모 연결·키워드·방·브리핑 시각·시간표가 그대로 돌아와요. " +
                 "메시지 내용과 학생별 기록은 넣지 않아요. 백업 파일도 폰 밖으로 나가지 않아요.",
                style = MaterialTheme.typography.bodySmall)
            Text(
                if (vm.lastBackup > 0) "마지막 백업: ${fmt.format(vm.lastBackup)} · 하루 한 번 다운로드/쌤톡스 폴더에 자동 저장"
                else "아직 백업이 없어요. '모든 파일 접근'이 켜져 있으면 하루 한 번 자동으로 저장해요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
            // 🔒 백업 비밀번호: 백업 파일을 이 비밀번호로 암호화 (학생·학부모 이름·번호 보호)
            var pinInput by rememberSaveable { mutableStateOf("") }
            var changing by rememberSaveable { mutableStateOf(false) }
            if (!vm.hasBackupPin || changing) {
                Text(if (vm.hasBackupPin) "🔒 새 백업 비밀번호" else "🔒 먼저 백업 비밀번호를 정해 주세요 (숫자 4~12자리)",
                    fontWeight = FontWeight.Medium, color = Ssam.Blush)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pinInput, onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(12) },
                        singleLine = true, placeholder = { Text("예: 4자리 이상") }, modifier = Modifier.weight(1f),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                    Button(onClick = { vm.setBackupPin(pinInput); pinInput = ""; changing = false },
                        enabled = pinInput.length >= 4) { Text("정하기") }
                }
                Text("새 폰에서 불러올 때 이 비밀번호가 필요해요. 꼭 기억해 두세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒 백업 비밀번호 설정됨 · 백업 파일은 암호화돼요", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { changing = true }) { Text("바꾸기") }
            }

            // 📤 다른 폰으로 보내기: 카톡 나에게·드라이브·메일 등으로 백업 파일 보내기
            val ctx = LocalContext.current
            Button(onClick = { ctx.startActivity(BackupShare.shareIntent(ctx)) }, modifier = Modifier.fillMaxWidth(),
                enabled = vm.hasBackupPin,
                colors = ButtonDefaults.buttonColors(containerColor = Ssam.Board, contentColor = Ssam.Chalk)) {
                Text("📤 다른 폰으로 보내기", fontWeight = FontWeight.Bold)
            }
            Text("새 폰에서 받은 '쌤톡스_백업.json'을 열고 쌤톡스를 고르면 바로 불러와요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.backupNow() }, enabled = vm.hasBackupPin) { Text("지금 백업") }
                OutlinedButton(onClick = { export.launch("쌤톡스_백업.json") }, enabled = vm.hasBackupPin) { Text("파일로 내보내기") }
                OutlinedButton(onClick = { vm.findBackups() }) { Text("🔎 백업 찾아서 불러오기") }
            }
            BackupFinderPanel(vm, onPickManually = { importer.launch(arrayOf("application/json", "*/*")) })
            if (vm.status.startsWith("💾") || vm.status.startsWith("불러오지") || vm.status.startsWith("백업"))
                Text(vm.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 출근·퇴근 시각 한 줄: 자주 쓰는 시각 버튼 + ⌚ 직접 */
@Composable
private fun QuietTimeRow(vm: JarvisViewModel, label: String, value: Int, presets: List<Int>, key: String) {
    val ctx = LocalContext.current
    val hm = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }
    Text(label, fontWeight = FontWeight.Medium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { p ->
            FilterChip(selected = value == p, onClick = { vm.quiet { putInt(key, p) } }, label = { Text(hm(p)) })
        }
        val custom = value !in presets
        FilterChip(selected = custom, onClick = {
            TimePickerDialog(ctx, { _, h, m -> vm.quiet { putInt(key, h * 60 + m) } }, value / 60, value % 60, true).show()
        }, label = { Text(if (custom) "⌚ ${hm(value)}" else "⌚ 직접") })
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
                Text("방을 누르거나 스위치로 켜고 꺼요. 끈 방은 모으지도, 요약하지도 않아요. 단, 중요 키워드가 들어간 알림은 모아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.rooms.isEmpty()) Text("최근 7일 동안 알림이 온 방이 없어요.", Modifier.padding(vertical = 12.dp))
                else Row {
                    TextButton(onClick = { vm.setAllRooms(pkg, true) }) { Text("모두 켜기") }
                    TextButton(onClick = { vm.setAllRooms(pkg, false) }) { Text("모두 끄기") }
                }
                vm.rooms.forEach { (room, count) ->
                    val on = vm.isRoomOn(pkg, room)
                    // 방 줄을 눌러도 모으기 켜기·끄기
                    Row(Modifier.fillMaxWidth().clickable { vm.setRoom(pkg, room, !on) }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(room, maxLines = 1, fontWeight = FontWeight.Medium)
                            Text("최근 7일 ${count}건",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = on, onCheckedChange = { vm.setRoom(pkg, room, it) })
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

/**
 * 🍎 애플 앱 같은 움직임: 시간 고정(tween) 대신 튕기지 않는 스프링.
 * 손을 떼는 순간처럼 빠르게 시작해 자연스럽게 멈춤. 크기·위치·투명도 모두 같은 느낌으로.
 */
internal object Motion {
    val fade = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val fadeFast = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    val size = spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val offset = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}
