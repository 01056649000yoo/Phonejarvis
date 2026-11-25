package com.jarvis.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 쌤톡스 아이콘 (칠판 그린 바탕 + 눈 감은 말풍선 + 새싹). 로고는 예전 색 그대로. */
@Composable
fun SsamLogo(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(Ssam.LogoBoard),
        contentAlignment = Alignment.Center,
    ) {
        // 적응형 아이콘 전경은 108 기준이라 조금 키워서 꽉 차게
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "쌤톡스 로고",
            modifier = Modifier.requiredSize(size * 1.45f))
    }
}

/** 파일 선택 화면을 '다운로드' 폴더에서 바로 열기. */
private class OpenInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
        )
}

/** 파일 선택 화면을 '다운로드/쌤톡스'(자동 백업 폴더)에서 바로 열기. */
class OpenInBackupFolder : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2F" + Uri.encode("쌤톡스")),
        )
}


private const val STEPS = 4

/**
 * 첫 실행 설치 안내: ① 소개 → ② 폰 진단·모델 추천 → ③ 모델 받기·가져오기 → ④ 권한.
 * 앱은 인터넷 권한이 없으므로 모델은 브라우저로 받고, 받은 파일을 앱 안으로 가져온다.
 */
@Composable
fun OnboardingScreen(vm: JarvisViewModel) {
    BackHandler(enabled = vm.onboardingStep > 0) { vm.onboardingStep-- }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        // 단계 표시
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            repeat(STEPS) { i ->
                Box(Modifier.height(6.dp).weight(1f).clip(CircleShape).background(
                    if (i <= vm.onboardingStep) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 🎬 단계 이동: 앞으로는 오른쪽에서, 뒤로는 왼쪽에서 넘어옴
            AnimatedContent(
                targetState = vm.onboardingStep,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(Motion.offset) { it * dir } + fadeIn(Motion.fade)) togetherWith
                        (slideOutHorizontally(Motion.offset) { -it * dir / 3 } + fadeOut(Motion.fadeFast))
                },
                label = "step",
            ) { step ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (step) {
                        0 -> WelcomeStep(vm)
                        1 -> DiagnoseStep(vm)
                        2 -> DownloadStep(vm)
                        else -> PermissionStep(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(kicker: String, title: String) {
    Text(kicker, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
    Text(title, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp), colors = actionButtonColors()) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
    }
}

// ① 소개
@Composable
private fun WelcomeStep(vm: JarvisViewModel) {
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SsamLogo(112.dp) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("쌤톡스", style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary)
        Text("선생님을 위한 디지털 디톡스", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("톡은 쌤톡스가 지켜볼게요.\n선생님은 잠시 쉬세요.",
                style = MaterialTheme.typography.titleLarge)
            Bullet("아는 사람 연락, 단톡방, 일정 알림을 모아 두고")
            Bullet("정한 시각에 AI가 할 일 중심으로 한 번에 정리해요")
            Bullet("모든 처리는 폰 안에서. 알림 내용이 인터넷으로 나가지 않아요")
        }
    }
    Text("처음 한 번만 설정하면 돼요: 폰 진단 → AI 모델 받기 → 권한 허용 (약 10분)",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // 💾 예전에 쓰던 설정 불러오기 (다시 설치했을 때)
    LaunchedEffect(Unit) { vm.checkBackup() }
    val restorePicker = rememberLauncherForActivityResult(OpenInBackupFolder()) { uri -> if (uri != null) vm.restoreBackup(uri) }
    val found = vm.foundBackup
    if (found != null) {
        val fmt = remember { java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuTitle("💾 지난 설정을 찾았어요", Modifier.asHeading(), style = MaterialTheme.typography.titleSmall, tint = MaterialTheme.colorScheme.primary)
                Text("${fmt.format(found.first)} 저장 · ${found.second}", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.restoreBackup(null) }) { Text("불러오기") }
            }
        }
    }
    if (vm.status.startsWith("💾") || vm.status.startsWith("불러오지")) Text(vm.status, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary)
    PrimaryButton("시작하기") { vm.onboardingStep = 1 }
    TextButton(onClick = { restorePicker.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
        // 선 아이콘 + 두 줄(이름 / 어디서 고르는지)
        Icon(painterResource(R.drawable.ic_line_restore), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
        Text("예전에 쓰던 설정 불러오기\n다운로드/쌤톡스의 백업 파일 고르기", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    // 모델 파일을 이미 다운로드 폴더에 받아 둔 경우: 진단·다운로드를 건너뛰고 바로 가져오기로
    TextButton(onClick = { vm.onboardingStep = 2 }, modifier = Modifier.fillMaxWidth()) {
        Text("모델 파일을 이미 받아 뒀어요 → 바로 가져오기")
    }
    Text("쌤톡스 ${vm.versionLine}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp, end = 12.dp).size(6.dp).clip(CircleShape).background(Ssam.GoldPale))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// ② 폰 진단 + 모델 추천
@Composable
private fun DiagnoseStep(vm: JarvisViewModel) {
    val d = vm.device
    StepTitle("2 / 4 · 폰 진단", "이 폰에 맞는 AI를 골라 드릴게요")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpecRow("기기", d.deviceName)
            SpecRow("칩", d.chip)
            SpecRow("운영체제", d.androidVersion)
            SpecRow("메모리(램)", "${d.ramGb}GB")
            SpecRow("남은 저장공간", "%.1fGB".format(d.freeGb))
        }
    }
    Text("✔ ${d.reason}", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    d.warning?.let {
        Text("⚠ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
    }
    ModelOption.entries.forEach { m ->
        val selected = vm.selectedModel == m
        OutlinedCard(
            Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { vm.chosenModel = m },
            border = BorderStroke(if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(m.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                        Text(m.sizeText, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m == d.recommended) Badge(containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary) { Text("추천", Modifier.padding(horizontal = 4.dp)) }
                    }
                    Text(m.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    ModelCompare()
    Text("나중에 설정 → 🤖 AI 모델·권한 → 바꾸기에서 언제든 바꿀 수 있어요.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    PrimaryButton("${vm.selectedModel.title}(으)로 진행") { vm.onboardingStep = 2 }
}

/** 두 모델 차이 한눈에 (2026-11-22 선생님: 고를 때 차이를 알려 주면 좋겠다) */
@Composable
private fun ModelCompare() {
    val rows = listOf(
        Triple("", "E4B", "E2B"),
        Triple("파일 크기", "3.7GB", "2.6GB"),
        Triple("필요한 램", "8GB 이상", "6GB 이상"),
        Triple("필요한 공간", "약 7.8GB", "약 5.7GB"),
        Triple("요약 꼼꼼함", "더 꼼꼼 — 긴 단톡방에서 부탁·답장할 것을 더 잘 골라요", "핵심은 잡지만 가끔 놓쳐요"),
        Triple("요약 속도", "조금 느려요", "더 빨라요"),
        Triple("폰 발열·배터리", "조금 더 써요", "덜 써요"),
        Triple("이런 분께", "요즘 폰(램 8GB↑), 정확한 요약이 중요", "오래된 폰, 빠른 요약이 좋음"),
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔍 두 모델 차이", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            rows.forEachIndexed { i, (label, a, b) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.Top) {
                    Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val head = i == 0
                    Text(a, Modifier.weight(1f).padding(end = 8.dp), style = if (head) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                        fontWeight = if (head) FontWeight.Bold else null)
                    Text(b, Modifier.weight(1f), style = if (head) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                        fontWeight = if (head) FontWeight.Bold else null)
                }
            }
            Text("두 모델 모두 폰 안에서만 돌고, 알림 내용은 밖으로 나가지 않아요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

// ③ 모델 받기 + 가져오기
@Composable
private fun DownloadStep(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    val m = vm.selectedModel
    val picker = rememberLauncherForActivityResult(OpenInDownloads()) { uri -> if (uri != null) vm.importModel(uri) }

    StepTitle("3 / 4 · AI 모델 받기", "${m.title} (${m.sizeText})")
    Text("쌤톡스는 개인정보 보호를 위해 인터넷 권한이 없어요. 그래서 모델은 브라우저로 받고, 받은 파일을 앱으로 가져와요.\n" +
         "이미 받아 뒀다면 아래 🔎 자동으로 찾기를 누르세요. (E4B·E2B 어느 파일이든 알아서 인식해요)",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // 🔎 받아 둔 모델 자동 찾기(2026-11-25 선생님) — 다운로드 폴더 등에서 .litertlm 을 찾아 바로 가져옴
    if (!vm.hasModel) Button(onClick = { vm.findModels() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("🔎 받아 둔 모델 자동으로 찾기")
    }
    if (!vm.hasModel) Text("처음 한 번 '모든 파일 접근'을 켜 달라고 해요 — 다운로드 폴더의 모델 파일을 읽는 데만 써요. 켜기 싫으면 ② 파일 선택으로 직접 골라도 돼요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.status.startsWith("🔎")) Text(vm.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    if (vm.foundModels.isNotEmpty()) AlertDialog(
        onDismissRequest = { vm.pickFoundModel(null) },
        title = { Text("가져올 모델 고르기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                vm.foundModels.forEach { f ->
                    TextButton(onClick = { vm.pickFoundModel(f) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${f.name}\n${f.length() / 1_000_000}MB · ${f.parentFile?.name ?: ""}", textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.pickFoundModel(null) }) { Text("닫기") } },
    )


    // 데이터 요금 안내 (앱은 네트워크 상태 권한도 없어서 직접 확인하지 않고 사용자에게 물어봄)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("📶 와이파이에서 받으세요", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.asHeading())
            Text("모바일 데이터(LTE·5G)로 받으면 약 ${m.sizeText}가 데이터에서 빠져요. " +
                 "요금제에 따라 데이터 초과 요금이 나올 수 있어요.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }

    var askWifi by remember { mutableStateOf(false) }
    GuideCard(
        number = "1", title = "브라우저로 내려받기",
        desc = "상단 알림창에 '다운로드 완료'가 뜰 때까지 기다리면 돼요 (와이파이에서 보통 5~15분).",
        button = "다운로드 시작",
        done = vm.hasModel,
    ) { askWifi = true }

    if (askWifi) AlertDialog(
        onDismissRequest = { askWifi = false },
        title = { Text("와이파이에 연결돼 있나요?") },
        text = {
            Text("${m.title}은(는) ${m.sizeText}예요.\n\n" +
                 "모바일 데이터로 받으면 데이터가 많이 빠지고, 요금제에 따라 추가 요금이 나올 수 있어요. " +
                 "무제한 요금제가 아니라면 와이파이에 연결한 뒤 받으세요.")
        },
        confirmButton = {
            TextButton(onClick = {
                askWifi = false
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.url)))
            }) { Text("와이파이 연결됨, 받기") }
        },
        dismissButton = {
            TextButton(onClick = {
                askWifi = false
                ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }) { Text("와이파이 설정 열기") }
        },
    )

    GuideCard(
        number = "2", title = "받은 파일 가져오기",
        desc = "'다운로드' 폴더에서 ${m.fileName} 파일을 고르세요. 앱 안으로 복사하는 데 2~3분 걸려요.",
        button = if (vm.hasModel) "다시 선택" else "파일 선택",
        done = vm.hasModel, enabled = !vm.busy,
    ) { picker.launch(arrayOf("*/*")) }

    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodyMedium)
    if (vm.hasModel) Text("복사가 끝나면 다운로드 폴더의 원본은 지워도 돼요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    PrimaryButton("다음", enabled = vm.hasModel && !vm.busy) { vm.onboardingStep = 3 }
}


@Composable
private fun GuideCard(
    number: String, title: String, desc: String, button: String,
    done: Boolean, enabled: Boolean = true, onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(
                if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(if (done) "✓" else number, style = MaterialTheme.typography.titleSmall,
                    color = if (done) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                Text(desc, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onClick, enabled = enabled) { Text(button) }
            }
        }
    }
}

// ④ 권한
@Composable
private fun PermissionStep(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    val notiPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    StepTitle("4 / 4 · 권한", "마지막으로 세 가지만 허용해 주세요")
    GuideCard(
        number = "1", title = "알림 접근",
        desc = "다른 앱(카톡, 학교 앱 등)의 알림을 모으려면 필요해요. 목록에서 쌤톡스를 켜 주세요.\n" +
               "회색으로 잠겨 있으면: 설정 → 애플리케이션 → 쌤톡스 → ⋮ → 제한된 설정 허용",
        button = if (vm.listenerEnabled) "허용됨" else "설정 열기",
        done = vm.listenerEnabled, enabled = !vm.listenerEnabled,
    ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    GuideCard(
        number = "2", title = "알림 표시",
        desc = "정리된 브리핑을 알림으로 받으려면 필요해요.",
        button = if (vm.notiPermission) "허용됨" else "허용하기",
        done = vm.notiPermission, enabled = !vm.notiPermission,
    ) {
        if (Build.VERSION.SDK_INT >= 33) notiPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
    GuideCard(
        number = "3", title = "알람 및 리마인더",
        desc = "정한 시각(예: 8:00 요약·밤 10시 하루 정리)에 딱 맞춰 정리하려면 필요해요. 목록에서 쌤톡스를 켜 주세요. 안 켜면 몇 분 늦을 수 있어요.",
        button = if (vm.exactAlarm) "허용됨" else "설정 열기",
        done = vm.exactAlarm, enabled = !vm.exactAlarm,
    ) { ctx.startActivity(exactAlarmSettings(ctx)) }
    GuideCard(
        number = "＋", title = "홈 화면 위젯 (선택)",
        desc = "홈 화면에 쌤톡스 위젯을 놓으면 앱을 열지 않고 최근 요약을 바로 봐요. 📋 요약을 누르면 그 자리에서 새로 정리해요.",
        button = if (vm.hasWidget) "놓음" else "위젯 놓기",
        done = vm.hasWidget, enabled = !vm.hasWidget,
    ) { vm.pinWidget() }
    Text("권한을 켠 뒤부터 오는 알림이 모여요. 요약 시간(아침·저녁·N시간마다·수업 뒤)과 수업 중 모아 두기는 설정 → 🔕 모아 두기·요약 시간에서 정해요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PrimaryButton(if (vm.listenerEnabled && vm.notiPermission) "쌤톡스 시작하기" else "나중에 하고 시작하기") {
        vm.finishOnboarding()
    }
}

/** ⏰ 알람 및 리마인더(정확한 시각 알람) 허용 화면 — 쌤톡스 항목으로 바로 */
fun exactAlarmSettings(ctx: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${ctx.packageName}"))
