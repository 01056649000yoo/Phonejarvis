package com.jarvis.phone

import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
private const val WHATS_NEW = "🏠 홈 화면 위젯 — 앱을 열지 않고 위젯에서 요약을 보고 📋 요약을 눌러요"

/** 업데이트 내역 큰 창 (버전 올릴 때 같이 고치기) */
private val WHATS_NEW_DETAIL = listOf(
    "📱 앱 아이콘이 보여요" to "설정 → 🌱 알림 앱 정리의 목록과 ＋ 앱 추가 창에 앱 아이콘이 같이 나와요. 이름이 같은 앱(캘린더 두 개 등)은 아래에 'Google 제공'·'삼성 제공'처럼 만든 곳을 적어 구분해요. 앱 추가 버튼도 크게 위로 올렸어요.",
    "👪 학부모 명부가 짧아졌어요" to "한 사람씩 줄로 세우던 명부를 이름 칩으로 옆으로 이어 붙였어요. 이름을 누르면 작은 창에서 연결한 이름·번호를 보고 빼거나 지울 수 있어요.",
    "🏠 홈 화면 위젯" to "홈 화면을 길게 누르고 → 위젯 → 쌤톡스 요약을 놓으세요. 가장 최근 요약이 위젯 안에 그대로 보이고(길면 위젯 안에서 올려 보기), 📋 요약을 누르면 지금까지 온 알림을 바로 정리해 위젯이 저절로 바뀌어요. 버튼 옆 숫자는 아직 요약 안 한 알림 수예요. ⚙️를 누르면 앱 설정이 열려요.",
    "🤖 봇·채널 알림은 사람 메시지에서 빼요" to "텔레그램 봇(자비스 등)·카카오톡 채널 알림톡(배송·결제)·카카오톡 자체 안내가 '사람에게 온 메시지'에 섞이던 것을 고쳤어요. 이런 알림은 📦 앱 알림에 '보낸 이: 내용'으로 보여요. 쌤톡스 자기 알림(요약 중·완료)도 더는 모으지 않아요.",
    "👥 사람 묶음을 내 맘대로" to "학생·학부모 묶음이 고정으로 있지 않아요. 설정 → 👥 사람 묶음에서 교직원·가족·학부모·동학년처럼 필요한 목록만 만들고, 이모지 24가지와 이름으로 꾸미세요. 어떤 묶음이든 지울 수 있어요(넣어 둔 이름도 같이 지워지고 모은 알림은 남아요). 예전 학생·학부모 묶음은 비어 있으면 저절로 없어지고, 이름이 들어 있으면 남아 있어요.",
    "🙋 답장할 연락을 맨 위에" to "학부모·동료가 묻거나 부탁한 것은 🙋 답장할 연락에 따로 모아요. 누가 무엇을 원하는지 한 줄로 보여 줘요.",
    "💬 대화방마다 한 줄씩" to "예전에는 알림을 한꺼번에 줄였다가 한 번 더 줄여서 내용이 뭉개졌어요. 이제 대화방마다 따로 '요점·요청·답장'을 정리해요. 날짜·시간·장소는 메시지에 있는 그대로 써요.",
    "🤖 기본 모델은 E4B" to "램 8GB 이상인 요즘 폰은 더 꼼꼼한 Gemma 4 E4B를 추천해요. 쓰던 모델을 바꾸려면 설정 → 🤖 AI 모델·권한 → 바꾸기.",
    "📦 쇼핑·배송·앱 알림은 건수만" to "사람이 보낸 메시지가 아닌 앱 알림은 AI가 요약하지 않고 앱별 건수와 최근 제목만 보여 줘요. 그래서 요약도 더 빨라졌어요.",
    "🧭 탭이 네 개로" to "📋 요약은 가장 최근 요약과 지금 정리하기만, 🔔 알림은 아직 요약 안 한 알림과 오늘 받은 알림 전체, 📚 지난 요약은 요약 모음이에요. 👥 사람 묶음은 설정 안으로 옮겼어요. 안 만들어도 요약은 그대로 돼요.",
    "👥 우리 반 → 사람들" to "아래 탭이 👥 사람들로 바뀌었어요. 교직원·가족처럼 묶음을 직접 만들고 이름만 쭉 넣으면, 그 이름이 보낸 알림이나 이름이 나온 알림을 묶음별로 모아 오늘 탭과 브리핑에 따로 보여 줘요. 학생·학부모 묶음도 이름을 바꾸거나 숨길 수 있어서 누구나 쓸 수 있어요.",
    "💼 업무·일정 앱도 한곳에" to "Gmail·Google Tasks·캘린더·메모 같은 업무·일정 앱은 메시지 앱 바로 아래 💼 묶음에 모이고, 브리핑 맨 위 \"💼 업무·일정 알림\"에 시간순으로 따로 보여요. 그 밖의 앱도 '⭐ 중요 앱으로 올리기'를 누르면 같이 관리돼요.",
    "🔤 글자가 커졌어요" to "앱 전체 글자를 한 단계씩 키우고 줄 간격을 넓혔어요. 오늘·사람들·설정 세 화면의 머리말과 카드 제목도 같은 모양으로 맞췄어요. 폰 설정에서 글자 크기를 키우면 쌤톡스도 같이 커져요.",
    "👪 학부모 명부는 이름만 쭉" to "관계를 따로 고를 필요 없이 이름을 쉼표·줄바꿈 아무렇게나 넣으면 돼요. '민준맘', '이서윤 아버님'처럼 쓰면 관계도 알아서 알아보고, 우리 반 학생 이름이 있으면 그 학생 학부모로 묶어요. 최근 연락한 사람은 누르기만 하면 바로 들어가요.",
    "＋ 앱 추가 목록이 깔끔해졌어요" to "내 파일·보안 폴더 같은 폰 기본 항목은 빼고 설치한 앱만 보여요. ⭐ 메시지·SNS 앱이 맨 위, 그다음 🔔 알림을 보내는 앱, 그 밖의 앱은 접어 둬요.",
    "🙅 안 쓰는 앱은 조용히" to "깔려만 있고 안 쓰는 앱(밴드 등)은 더 이상 \"알림이 안 들어와요 · 설정 열기\"로 보여 주지 않아요. 카톡·기본 문자 앱·직접 추가한 앱만 확인하고, '안 써요'를 누르면 다시 묻지 않아요.",
    "🧹 오래된 기록은 저절로 지워요" to "설정 → 🧹 저장 공간에서 받은 알림(3일~30일)·브리핑 모음(7일~90일)·학생 기록(3개월~1년) 보관 기간을 고를 수 있어요. 기간이 지나면 앱을 열지 않아도 저절로 지워지고, 일주일에 한 번 파일 크기까지 줄여서 앱이 무거워지지 않아요.",
    "＋ 알림 앱 추가" to "설정 → 🌱 알림 앱 정리에서 '＋ 앱 추가'를 누르면 폰에 깔린 앱 중 목록에 없는 앱을 골라 넣을 수 있어요. 한 번 뺀 앱도 이제 목록에서 사라지지 않아 언제든 다시 켤 수 있어요.",
    "💬 방 고르기가 편해졌어요" to "한 번이라도 알림이 온 방은 7일이 지나도 모두 목록에 남아요. 아직 알림이 안 온 방은 방 이름을 직접 넣어 미리 켜고 끌 수 있어요. (카톡 방 목록은 다른 앱이 읽을 수 없어서 이름으로 넣어요)",
    "👪 학부모 방 고르기 대신 학부모 명부" to "우리 반 → 👪 학부모 명부에서 학생·관계(어머님/아버님)·이름이나 번호만 등록하세요. 문자·카톡·밴드·하이클래스 등 어떤 앱이든 보낸 사람·방 이름·내용에 그 이름·번호가 보이면 자동으로 \"민준 어머님\" 연락으로 모아 답장까지 추천해요.",
    "🙋 한 사람으로 모아요" to "같은 학부모가 문자와 카톡으로 따로 연락해도 오늘 탭 👪 학부모 연락에 한 사람으로 묶여요. 이름·번호·카톡 별명을 여러 개 등록해도 돼요.",
    "🔔 정리는 백그라운드에서" to "지금 정리하기를 누르면 \"완료되면 알림으로 알려드릴게요\" 안내 뒤 앱이 닫히고, 백그라운드에서 정리해요. 다 되면 알림이 오고, 누르면 📄 세부 내역(요약 전체 + 원래 알림)이 바로 열려요.",
    "🧒 명부도 자동으로" to "학부모를 등록할 때 명부에 없는 학생이면 우리 반 명부에 같이 추가돼요.",
    "📋 지난 변화 (v1.0.2)" to "정리(요약)한 알림은 카드에서 사라지고, 오늘 받은 알림 전체는 맨 아래 '🗂 오늘 받은 알림 전체'에서 봐요.",
    "🧭 지난 큰 변화 (v1.0)" to "아래 탭이 📋 오늘 · 🧒 우리 반 · ⚙️ 설정 세 개로 바뀌었고, 설정은 목록에서 세부 화면으로 들어가요.",
)

/**
 * 메인: 아래 탭 네 개 — 손대지 않아도 알림을 주기적으로 요약해 보는 앱
 *  📋 요약 — 가장 최근 요약·할 일·연락, 지금 정리하기
 *  🔔 알림 — 아직 요약 안 한 알림, 오늘 받은 알림 전체(앱·방별)
 *  📚 지난 요약 — 요약 모음
 *  ⚙️ 설정 — 앱 동작(알림 앱·시간·AI 모델·백업)과 선택 사항인 👥 사람 묶음, 목록 → 세부 화면
 */
@Composable
fun JarvisScreen(vm: JarvisViewModel) {
    // 폰의 뒤로 버튼: 세부 화면 → 탭 첫 화면 → 요약 탭
    androidx.activity.compose.BackHandler(enabled = vm.page != null || vm.tab != JarvisViewModel.Tab.BRIEFING) { vm.back() }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(JarvisViewModel.Tab.BRIEFING, "📋", "요약"),
                    Triple(JarvisViewModel.Tab.ALERTS, "🔔", "알림"),
                    Triple(JarvisViewModel.Tab.HISTORY, "📚", "지난 요약"),
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
                    JarvisViewModel.Tab.ALERTS -> AlertsTab(vm)
                    JarvisViewModel.Tab.HISTORY -> HistoryTab(vm)
                    JarvisViewModel.Tab.SETTINGS -> SettingsTab(vm)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun Header(vm: JarvisViewModel, tab: JarvisViewModel.Tab) {
    // 네 탭 모두 같은 모양: 작은 로고 + 큰 제목, 아래 한 줄 설명
    val today = remember { SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(System.currentTimeMillis()) }
    val (title, sub) = when (tab) {
        JarvisViewModel.Tab.BRIEFING -> "요약" to today
        JarvisViewModel.Tab.ALERTS -> "알림" to "오늘 ${vm.todayItems.size}건 · 아직 요약 안 한 알림 ${vm.pendingItems.size}건"
        JarvisViewModel.Tab.HISTORY -> "지난 요약" to "최근 요약 ${vm.briefings.size}개"
        JarvisViewModel.Tab.SETTINGS -> "설정" to "쌤톡스 v${vm.appVersion} · 알림은 폰 밖으로 나가지 않아요"
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SsamLogo(36.dp)
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
    Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp))
}

/** 세부 화면 위: ‹ 뒤로 + 제목 */
@Composable
private fun PageHeader(vm: JarvisViewModel, page: JarvisViewModel.Page) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.back() }, contentPadding = PaddingValues(start = 0.dp, end = 8.dp)) {
            Text("‹ " + (page.parent?.title ?: when (page.tab) {
                JarvisViewModel.Tab.BRIEFING -> "요약"; JarvisViewModel.Tab.ALERTS -> "알림"
                JarvisViewModel.Tab.HISTORY -> "지난 요약"; else -> "설정" }),
                style = MaterialTheme.typography.titleMedium)
        }
    }
    Text(if (page == JarvisViewModel.Page.GROUP) vm.currentGroup?.title ?: page.title else page.title,
        style = MaterialTheme.typography.headlineSmall)
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
        JarvisViewModel.Page.STORAGE -> StoragePage(vm)
        JarvisViewModel.Page.PEOPLE -> PeoplePage(vm)
        JarvisViewModel.Page.GROUP -> GroupPage(vm)
        JarvisViewModel.Page.KEYWORDS -> KeywordCard(vm)
    }
}

/** 아이폰 설정 앱 같은 한 줄 메뉴: 아이콘·제목·지금 상태·› */
@Composable
private fun MenuGroup(rows: List<Triple<String, String, () -> Unit>>) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { i, (title, status, onClick) ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("  ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ───────────────────────── 브리핑 탭: 결과만 ─────────────────────────

@Composable
private fun BriefingTab(vm: JarvisViewModel) {

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
    if (vm.groups.any { it.id == "parents" && !it.hidden }) ParentCard(vm)
    PeopleCard(vm)

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
}

// ───────────────────────── 🔔 알림 탭: 모은 알림 원본 ─────────────────────────

@Composable
private fun AlertsTab(vm: JarvisViewModel) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    // 아직 요약 안 한 알림: 요약 탭에서 '지금 정리하기'를 하면 사라짐
    if (vm.pendingItems.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(true) }
        val apps = vm.pendingItems.map { it.app }.distinct()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("아직 요약 안 한 알림 ${vm.pendingItems.size}건", style = MaterialTheme.typography.titleMedium)
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
    Text("오늘 받은 알림 전체 · 앱·대화방별", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    TodayAllPage(vm)
}

// ───────────────────────── 📚 지난 요약 탭 ─────────────────────────

@Composable
private fun HistoryTab(vm: JarvisViewModel) {
    val dayTimeFmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREA) }
    vm.briefingDetail?.let { (b, items) -> BriefingDetailDialog(vm, b, items) }
    if (vm.briefings.isEmpty()) Card(Modifier.fillMaxWidth()) {
        Text("아직 요약이 없어요. 설정에서 정한 시각이 되면 저절로 쌓여요.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
    // 지난 요약을 한 줄씩. 누르면 펼쳐 보기
    if (vm.briefings.isNotEmpty()) {
        var open by rememberSaveable { mutableStateOf(true) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📚 요약 모음 · ${vm.briefings.size}개", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                Text("${briefingIcon(b)} ${b.label}", style = MaterialTheme.typography.titleMedium)
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
                        if (items.isEmpty()) Text("원래 알림은 보관 기간이 지나 지워졌어요. (설정 → 🧹 저장 공간)", style = MaterialTheme.typography.bodySmall)
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
                    Text("${briefingIcon(b)} ${b.label}", style = MaterialTheme.typography.titleMedium)
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
    // 👥 사람 묶음은 선택 사항: 안 만들어도 요약은 그대로 됨
    MenuGroup(listOf(
        Triple("👥 사람 묶음", "묶음 ${vm.groups.count { !it.hidden }}개 · 선택") { vm.open(JarvisViewModel.Page.PEOPLE) },
    ))
    MenuGroup(listOf(
        Triple("🤖 AI 모델·권한",
            (vm.installedModel?.title ?: if (vm.hasModel) "모델 있음" else "모델 없음") + if (permsMissing > 0) " · 권한 확인 $permsMissing" else "") {
            vm.open(JarvisViewModel.Page.SYSTEM) },
        Triple("💾 백업·복원",
            (if (vm.lastBackup > 0) "${fmt.format(vm.lastBackup)} 백업" else "백업 없음") + if (vm.hasBackupPin) " · 🔒" else "") {
            vm.open(JarvisViewModel.Page.BACKUP) },
        Triple("🧹 저장 공간",
            (vm.storageUsage?.let { Storage.size(it.bytes) + " · " } ?: "") + "알림 ${Storage.label(vm.notiDays)} 보관 · 자동 정리") {
            vm.open(JarvisViewModel.Page.STORAGE) },
    ))
}

// ───────────────────────── 👥 사람 묶음 (설정 → 세부 화면) ─────────────────────────

@Composable
private fun PeoplePage(vm: JarvisViewModel) {
    vm.studentView?.let { (name, entries) -> StudentDialog(vm, name, entries) }
    var making by rememberSaveable { mutableStateOf(false) }
    val shown = vm.groups.filterNot { it.hidden }
    Text("교직원·가족·학부모처럼 내 맘대로 목록을 만들고 이모지·이름으로 꾸미세요. 이름만 쭉 넣으면 그 사람이 보낸 알림이나 이름이 나온 알림을 목록별로 모아요. 안 만들어도 요약은 그대로 돼요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (shown.isNotEmpty()) MenuGroup(shown.map { g ->
        val n = when (g.kind) {
            "students" -> vm.students.size
            "parents" -> vm.parentLinks.map { it.label }.distinct().size
            else -> vm.groupMembers[g.id]?.size ?: 0
        }
        Triple(g.title, if (n == 0) "비어 있음" else "${n}명") { vm.openGroup(g.id) }
    })
    Button(onClick = { making = true }, modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink)) {
        Text("＋ 묶음 만들기", fontWeight = FontWeight.Bold)
    }
    val hidden = vm.groups.filter { it.hidden }
    if (hidden.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("숨긴 묶음:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        hidden.forEach { g -> TextButton(onClick = { vm.unhideGroup(g.id) }) { Text("${g.title} 다시 보기") } }
    }
    MenuGroup(listOf(
        Triple("⭐ 중요 키워드",
            if (vm.keywords.isEmpty()) "아직 없음" else vm.keywords.take(2).joinToString(", ") + if (vm.keywords.size > 2) " 외 ${vm.keywords.size - 2}개" else "") {
            vm.open(JarvisViewModel.Page.KEYWORDS) },
    ))
    if (making) GroupNameDialog(title = "＋ 묶음 만들기", initEmoji = "👔", initName = "", suggest = true,
        onDone = { emoji, name -> making = false; vm.addGroup(emoji, name) }, onDismiss = { making = false })
}

/** 묶음 이름·아이콘 정하기 (만들기·이름 바꾸기 공용) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupNameDialog(title: String, initEmoji: String, initName: String, suggest: Boolean,
                            onDone: (String, String) -> Unit, onDismiss: () -> Unit) {
    var emoji by rememberSaveable { mutableStateOf(initEmoji) }
    var name by rememberSaveable { mutableStateOf(initName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (suggest) {
                    Text("눌러서 바로 고르기", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        People.SUGGESTIONS.forEach { (e, n) -> AssistChip(onClick = { emoji = e; name = n }, label = { Text("$e $n") }) }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("묶음 이름 (예: 교직원)") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    People.EMOJIS.forEach { e -> FilterChip(selected = emoji == e, onClick = { emoji = e }, label = { Text(e) }) }
                }
            }
        },
        confirmButton = { Button(onClick = { onDone(emoji, name) }, enabled = name.isNotBlank()) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 묶음 한 개: 학생·학부모 묶음은 원래 화면, 직접 만든 묶음은 이름 쭉 넣기 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupPage(vm: JarvisViewModel) {
    val g = vm.currentGroup ?: return
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { renaming = true }) { Text("✏️ 이름 바꾸기") }
        OutlinedButton(onClick = { removing = true }) { Text("🗑 묶음 지우기") }
    }
    when (g.kind) {
        "students" -> { vm.studentView?.let { (name, entries) -> StudentDialog(vm, name, entries) }; StudentsCard(vm) }
        "parents" -> ParentsPage(vm)
        else -> CustomGroupCard(vm, g)
    }
    if (renaming) GroupNameDialog(title = "✏️ 이름 바꾸기", initEmoji = g.emoji, initName = g.name, suggest = false,
        onDone = { e, n -> renaming = false; vm.renameGroup(g.id, e, n) }, onDismiss = { renaming = false })
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("${g.title} 묶음을 지울까요?") },
        text = { Text(when (g.kind) {
            "students" -> "넣어 둔 학생 이름 ${vm.students.size}명도 같이 지워져요. 학생별 기록과 모은 알림은 그대로 남아요."
            "parents" -> "연결해 둔 학부모도 같이 지워져요. 모은 알림은 그대로 남아요."
            else -> "넣어 둔 이름 ${vm.groupMembers[g.id]?.size ?: 0}개도 같이 지워져요. 모은 알림은 그대로 남아요."
        }) },
        confirmButton = { Button(onClick = { removing = false; vm.removeGroup(g.id) }) { Text("지우기") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("취소") } },
    )
}

/** 직접 만든 묶음: 이름만 쭉 넣기 + 최근 연락한 사람에서 고르기 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomGroupCard(vm: JarvisViewModel, g: PeopleGroup) {
    LaunchedEffect(g.id) { vm.loadRecentSenders() }
    var raw by rememberSaveable(g.id) { mutableStateOf("") }
    val members = vm.groupMembers[g.id].orEmpty()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("＋ 이름 넣기", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = raw, onValueChange = { raw = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("예) 김교감, 박부장, 이선생 … 쉼표·줄바꿈 아무렇게나") })
            Button(onClick = { if (vm.addMembers(g.id, raw) > 0) raw = "" }, enabled = raw.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink)) { Text("넣기", fontWeight = FontWeight.Bold) }
            Text("알림에 뜨는 이름 그대로 넣으세요. 그 이름이 보낸 알림이나, 내용에 그 이름이 나온 알림을 이 묶음으로 모아요.",
                style = MaterialTheme.typography.bodySmall)
            val recent = vm.recentSenders.filter { it !in members }
            if (recent.isNotEmpty()) {
                Text("최근 연락한 사람에서 고르기 · 누르면 바로 들어가요", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEach { s -> AssistChip(onClick = { vm.addMembers(g.id, s) }, label = { Text("+ $s") }) }
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${g.title} ${members.size}명", style = MaterialTheme.typography.titleMedium)
            if (members.isEmpty()) Text("아직 없어요. 위에 이름을 넣어 주세요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                members.forEach { n -> InputChip(selected = true, onClick = { vm.removeMember(g.id, n) }, label = { Text("$n  ✕") }) }
            }
        }
    }
}

/** 요약 탭: 👥 묶음별 오늘 연락 (직접 만든 묶음). 없으면 안 보임 */
@Composable
private fun PeopleCard(vm: JarvisViewModel) {
    if (vm.peopleToday.isEmpty()) return
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.peopleToday.forEachIndexed { i, (g, list) ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable { vm.openGroup(g.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${g.title} 연락 ${list.size}건", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                list.sortedByDescending { it.second.time }.take(3).forEach { (name, n) ->
                    Text("${timeFmt.format(n.time)} $name · ${n.text.replace('\n', ' ').take(50)}",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
            }
        }
    }
}

/**
 * 👪 학부모 명부: 이름만 쭉 넣으면 끝. 쉼표·줄바꿈·띄어쓰기, 관계·번호 있든 없든 알아서 나눔.
 * 어떤 앱이든 보낸 사람·방 이름·내용에 그 이름·번호가 들어간 알림을 그 학부모 연락으로 자동 정리.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentsPage(vm: JarvisViewModel) {
    LaunchedEffect(Unit) { vm.loadRecentSenders() }
    var raw by rememberSaveable { mutableStateOf("") }

    Text("학부모 이름을 쭉 넣기만 하세요. 쉼표·줄바꿈 아무렇게나 써도 돼요. 문자·카톡·밴드 등 어떤 앱이든 그 이름이 들어간 알림을 학부모 연락으로 모아 답장까지 추천해요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("＋ 이름 넣기", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = raw, onValueChange = { raw = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("예) 민준맘, 이서윤 아버님, 박지호 어머니, 김영희") })
            Button(
                onClick = { if (vm.addParentsRaw(raw) > 0) raw = "" },
                enabled = raw.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink),
            ) { Text("넣기", fontWeight = FontWeight.Bold) }
            Text("알림에 뜨는 이름 그대로 넣으면 돼요. '맘·아빠' 같은 관계는 있으면 같이 알아보고, 우리 반 학생 이름이 들어 있으면 그 학생 학부모로 묶어요.",
                style = MaterialTheme.typography.bodySmall)
            if (vm.recentSenders.isNotEmpty()) {
                Text("최근 연락한 사람에서 고르기 · 누르면 바로 들어가요", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.recentSenders.forEach { s -> AssistChip(onClick = { vm.addParentsRaw(s) }, label = { Text("+ $s") }) }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(Motion.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val groups = vm.parentLinks.groupBy { it.label }
            Text("👪 명부 ${groups.size}명", style = MaterialTheme.typography.titleMedium)
            if (groups.isEmpty()) Text("아직 없어요. 위에 이름을 넣어 주세요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 2026-11-21 선생님: 한 사람씩 줄로 세우면 13명이 너무 길다 → 이름 칩을 옆으로 이어 붙이고, 누르면 작은 창에서 자세히·지우기
            var picked by rememberSaveable { mutableStateOf<String?>(null) }
            if (groups.isNotEmpty()) Text("이름을 누르면 연결한 이름·번호를 보고 지울 수 있어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (label, links) ->
                    val student = links.first().student.takeIf { it in vm.students }
                    AssistChip(onClick = { picked = label },
                        label = { Text((if (student != null) "🧒 " else "") + label + if (links.size > 1) " · ${links.size}" else "") })
                }
            }
            picked?.let { label ->
                val links = groups[label].orEmpty()
                if (links.isEmpty()) { picked = null; return@let }
                val student = links.first().student.takeIf { it in vm.students }
                AlertDialog(
                    onDismissRequest = { picked = null },
                    title = { Text("👪 $label") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("이 이름·번호가 들어간 알림을 이 학부모 연락으로 모아요. ✕ 를 누르면 그것만 빠져요.", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                links.forEach { l -> InputChip(selected = true, onClick = { vm.removeParentLink(l) }, label = { Text("${l.match}  ✕") }) }
                            }
                            if (student != null) TextButton(onClick = { picked = null; vm.openStudent(student) }) { Text("🧒 $student 학생 기록 ›") }
                        }
                    },
                    confirmButton = { Button(onClick = { picked = null }) { Text("닫기") } },
                    dismissButton = { TextButton(onClick = { links.forEach { vm.removeParentLink(it) }; picked = null }) { Text("🗑 이 학부모 지우기") } },
                )
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
                    Text("🛌 주말에는 쉬기", style = MaterialTheme.typography.titleMedium)
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
            // ＋ 앱 추가: 기본은 모든 앱을 모으고, 뺄 앱만 끔. 목록에 없는 앱은 여기서 추가(2026-11-21 선생님: 눈에 안 띈다 → 꽉 찬 버튼)
            Button(onClick = { vm.openAddApps() }, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink)) {
                Text("＋ 목록에 없는 앱 추가하기", fontWeight = FontWeight.Bold)
            }
            Text("모든 앱 알림을 기본으로 모아요. 아래 목록에서 뺄 앱만 끄고, 목록에 없는 앱은 위 버튼으로 추가하세요.",
                style = MaterialTheme.typography.bodySmall)
            vm.addableApps?.let { list -> AddAppDialog(vm, list) }
            // 🚫 광고 자동 거르기 (문자·카톡의 광고성 메시지)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🚫 광고 자동 거르기", style = MaterialTheme.typography.titleMedium)
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
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
                            }) { Text("알림 설정 열기", color = Ssam.Chalk) }
                            TextButton(onClick = { vm.dismissSilent(pkg) }) { Text("안 써요 · 숨기기", color = Ssam.Chalk) }
                        }
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
                val imp = vm.importantApps
                val others = recs.filter { AppAdvisor.group(it.stat, imp) == 2 }
                recs.forEachIndexed { i, r ->
                    // 묶음 제목: 💬 메시지·SNS → 💼 업무·일정·중요 앱 → 📱 그 밖의 앱(접힘)
                    val g = AppAdvisor.group(r.stat, imp)
                    val msg = g == 0
                    if (i == 0 || g != AppAdvisor.group(recs[i - 1].stat, imp)) {
                        if (g < 2) Text(if (g == 0) "💬 메시지·SNS 앱" else "💼 업무·일정 · ⭐ 중요 앱", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        else TextButton(onClick = { showOthers = !showOthers }, contentPadding = PaddingValues(0.dp)) {
                            Text("📱 그 밖의 앱 ${others.size}개 (빼기 추천 ${others.count { !it.keep }}) " +
                                if (showOthers) "▾ 접기" else "▸ 펼치기",
                                color = Ssam.Chalk, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (g == 2 && !showOthers) return@forEachIndexed
                    val keepNow = r.stat.pkg !in vm.excludedApps
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppIcon(r.stat.pkg, 32.dp)
                        Column(Modifier.weight(1f)) {
                            Text("${r.stat.app} · " + if (r.stat.count > 0) "${r.stat.count}건" else "최근 7일 0건",
                                fontWeight = FontWeight.Medium)
                            Text((if (r.keep) "추천: 모으기 — " else "추천: 빼기 — ") + r.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (r.keep) Ssam.Sprout else Ssam.Blush)
                            // ⭐ 중요 앱으로 올리기 / 내리기 (업무·일정으로 자동 분류된 앱은 원래 위에 있음)
                            if (g == 2) TextButton(onClick = { vm.setImportant(r.stat.pkg, true) }, contentPadding = PaddingValues(0.dp)) {
                                Text("⭐ 중요 앱으로 올리기", color = Ssam.Chalk, style = MaterialTheme.typography.labelLarge)
                            } else if (r.stat.pkg in imp && !AppAdvisor.isWork(r.stat.app, r.stat.pkg)) TextButton(
                                onClick = { vm.setImportant(r.stat.pkg, false) }, contentPadding = PaddingValues(0.dp)) {
                                Text("⭐ 중요 앱에서 내리기", color = Ssam.Chalk, style = MaterialTheme.typography.labelLarge)
                            }
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

/** 🧹 저장 공간: 보관 기간 고르기 + 지금 정리하기. 기간이 지난 기록은 자동으로 지워짐. */
@Composable
private fun StoragePage(vm: JarvisViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    Text("쌓인 기록은 아래 보관 기간이 지나면 저절로 지워져요. 앱을 열지 않아도 브리핑이 끝날 때마다 정리하고, " +
         "일주일에 한 번은 지운 자리까지 줄여 용량을 돌려받아요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val u = vm.storageUsage
            Text("💾 지금 쓰는 공간 " + (u?.let { Storage.size(it.bytes) } ?: "…"), fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
            if (u != null) Text("받은 알림 ${u.noti}건 · 브리핑 ${u.briefings}개 · 학생 기록 ${u.logs}건 · 할 일 ${u.tasks}개",
                style = MaterialTheme.typography.bodySmall)
            Text("AI 모델 파일(수 GB)은 요약에 꼭 필요해서 여기서 지우지 않아요.", style = MaterialTheme.typography.labelSmall)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("⏳ 보관 기간", style = MaterialTheme.typography.titleMedium)
            RetentionRow("📨 받은 알림 원문", "브리핑 세부 내역의 '원래 알림'도 이 기간만큼 볼 수 있어요", Storage.NOTI_CHOICES, vm.notiDays) {
                vm.setRetention("noti_days", it) }
            RetentionRow("📚 브리핑 모음", "요약 결과라 작아요", Storage.BRIEF_CHOICES, vm.briefDays) { vm.setRetention("brief_days", it) }
            RetentionRow("🧒 학생별 기록", "상담·생활기록에 쓰려면 길게 두세요", Storage.LOG_CHOICES, vm.logDays) { vm.setRetention("log_days", it) }
            Text("📌 끝낸 할 일은 30일, 마감이 60일 지난 할 일은 저절로 지워요. 앱·방 이름 목록은 180일 동안 안 오면 지워요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Button(onClick = { vm.cleanNow() }, enabled = !vm.cleaning, modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink)) {
        Text(if (vm.cleaning) "정리하는 중…" else "🧹 지금 정리하기", fontWeight = FontWeight.Bold)
    }
    OutlinedButton(onClick = { confirmClear = true }, enabled = !vm.cleaning, modifier = Modifier.fillMaxWidth()) {
        Text("받은 알림 원문 모두 지우기")
    }
    if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("받은 알림 원문을 모두 지울까요?") },
        text = { Text("모은 알림 ${vm.storageUsage?.noti ?: 0}건이 지워지고 되돌릴 수 없어요. 브리핑 모음·학생 기록·할 일·설정은 그대로 남아요. 아직 정리 안 한 알림도 지워져요.") },
        confirmButton = { Button(onClick = { confirmClear = false; vm.clearNotis() }) { Text("모두 지우기") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetentionRow(title: String, hint: String, choices: List<Int>, value: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { d -> FilterChip(selected = value == d, onClick = { onPick(d) }, label = { Text(Storage.label(d)) }) }
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** ＋ 앱 추가: 폰에 설치된 앱 중 아직 목록에 없는 앱 (메시지 앱 먼저) */
@Composable
private fun AddAppDialog(vm: JarvisViewModel, apps: List<JarvisViewModel.AddableApp>) {
    var q by rememberSaveable { mutableStateOf("") }
    var showOthers by rememberSaveable { mutableStateOf(false) }
    val sameName = remember(apps) { apps.groupingBy { it.name }.eachCount() }
    AlertDialog(
        onDismissRequest = { vm.closeAddApps() },
        title = { Text("＋ 앱 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("추가한 앱은 알림이 오면 바로 모아요. 그 앱의 알림이 켜져 있어야 해요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("앱 이름 찾기") })
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    // 찾는 중이면 전부에서, 아니면 추천(메시지·SNS → 알림 보내는 앱) 먼저, 그 밖의 앱은 접어 둠
                    val searching = q.isNotBlank()
                    val shown = apps.filter { !searching || q.trim() in it.name }
                    if (apps.isEmpty()) Text("앱 목록을 불러오는 중이에요…", Modifier.padding(vertical = 12.dp))
                    else if (shown.isEmpty()) Text("찾는 앱이 없어요.", Modifier.padding(vertical = 12.dp))
                    val titles = listOf("⭐ 추천 · 💬 메시지·SNS", "⭐ 추천 · 💼 업무·일정", "🔔 알림을 보내는 앱", "📱 그 밖의 앱")
                    shown.groupBy { it.group }.toSortedMap().forEach { (g, list) ->
                        if (g == 3 && !searching) {
                            TextButton(onClick = { showOthers = !showOthers }, contentPadding = PaddingValues(0.dp)) {
                                Text("${titles[g]} ${list.size}개 " + if (showOthers) "▾ 접기" else "▸ 펼치기",
                                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            }
                            if (!showOthers) return@forEach
                        } else Text(titles[g], fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        list.forEach { a ->
                            Row(Modifier.fillMaxWidth().clickable { vm.addApp(a.pkg, a.name) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AppIcon(a.pkg, 36.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(a.name)
                                    // 이름이 같은 앱(캘린더 둘 등)은 만든 곳으로 가른다
                                    if (sameName.getOrDefault(a.name, 0) > 1) Text(appMaker(a.pkg), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("＋ 추가", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeAddApps() }) { Text("닫기") } },
    )
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
                Text("👪 학부모 이름을 넣어 두면 어떤 앱으로 와도 학부모 연락만 따로 모아 답장까지 추천해 드려요.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.openGroup("parents") }) { Text("등록하기") }
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
            Text(vm.groups.firstOrNull { it.id == "students" }?.title ?: "🧒 우리 반 학생", style = MaterialTheme.typography.titleMedium)
            Text("학생 이름을 넣어 두면 연락에 \"민준이\"처럼 이름만 나와도 알아보고, 학생별 기록에 계속 쌓아요 (보관 기간은 설정 → 🧹 저장 공간). " +
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
                Text("📒 기록 ${entries.size}건", style = MaterialTheme.typography.titleMedium)
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
    Text("👪 학부모 연결", style = MaterialTheme.typography.titleMedium)
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
            Text("🏫 수업 중 모드", style = MaterialTheme.typography.titleMedium)
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
            Text("🌙 퇴근 모드", style = MaterialTheme.typography.titleMedium)
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
            Text("💾 백업·복원", style = MaterialTheme.typography.titleMedium)
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
                Text("📤 다른 폰으로 보내기", style = MaterialTheme.typography.titleMedium)
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

private fun roomLast(vm: JarvisViewModel, room: String) = vm.rooms.firstOrNull { it.name == room }?.last ?: 0L

/** 방 고르기: 이 앱에서 알림이 온 적 있는 모든 방(최근 많은 순) + 직접 추가한 방, 스위치로 켜고 끄기. */
@Composable
private fun RoomPickerDialog(vm: JarvisViewModel, pkg: String, name: String) {
    AlertDialog(
        onDismissRequest = { vm.closeRooms() },
        title = { Text("$name · 방 고르기") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("방을 누르거나 스위치로 켜고 꺼요. 끈 방은 모으지도, 요약하지도 않아요. 단, 중요 키워드·등록한 학부모는 모아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // ＋ 방 이름 직접 추가: 다른 앱은 카톡 방 목록을 읽을 수 없어서, 아직 알림이 안 온 방은 이름으로 미리 넣음
                var newRoom by rememberSaveable { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = newRoom, onValueChange = { newRoom = it }, singleLine = true,
                        modifier = Modifier.weight(1f), placeholder = { Text("방 이름 직접 추가") })
                    OutlinedButton(onClick = { vm.addRoom(pkg, newRoom); newRoom = "" }, enabled = newRoom.isNotBlank()) { Text("추가") }
                }
                Text("한 번이라도 알림이 온 방은 모두 여기 남아요. 아직 알림이 안 온 방은 이름을 그대로 넣으면 미리 켜고 끌 수 있어요.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.rooms.isEmpty()) Text("아직 알림이 온 방이 없어요. 위에서 방 이름을 넣어 추가할 수 있어요.", Modifier.padding(vertical = 12.dp))
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
                            Text(when {
                                    count > 0 -> "최근 7일 ${count}건"
                                    roomLast(vm, room) > 0 -> "마지막 알림 " + SimpleDateFormat("M/d", Locale.KOREA).format(roomLast(vm, room))
                                    else -> "직접 추가한 방"
                                },
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
            Text("⭐ 중요 키워드", style = MaterialTheme.typography.titleMedium)
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

/** 폰에 깔린 앱의 실제 아이콘(없으면 📱). 한 번 그린 것은 다시 읽지 않음 */
@Composable
fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (bmp != null) androidx.compose.foundation.Image(bmp, contentDescription = null, modifier = Modifier.size(size))
    else Box(Modifier.size(size), contentAlignment = Alignment.Center) { Text("📱") }
}

/** 이름이 같은 앱을 가르는 '만든 곳' (com.google.android.calendar → Google · 캘린더) */
fun appMaker(pkg: String): String {
    val maker = when {
        pkg.startsWith("com.google.") -> "Google"
        pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.") -> "삼성"
        pkg.startsWith("com.microsoft.") -> "Microsoft"
        pkg.startsWith("com.naver.") || pkg.startsWith("com.nhn.") -> "네이버"
        pkg.startsWith("com.kakao.") -> "카카오"
        pkg.startsWith("com.lge.") -> "LG"
        else -> pkg.split('.').getOrNull(1)?.replaceFirstChar { it.uppercase() } ?: pkg
    }
    return "$maker 제공 · $pkg"
}

