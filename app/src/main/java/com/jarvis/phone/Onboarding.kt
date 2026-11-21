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

/** 쌤톡스 아이콘 (칠판 그린 바탕 + 눈 감은 말풍선 + 새싹). */
@Composable
fun SsamLogo(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(Ssam.Board),
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

/**
 * 🔎 찾은 백업 목록 (설치 안내 첫 화면·설정 백업 칸 공용).
 * 권한이 없으면 '모든 파일 접근' 안내 → 켜고 돌아오면 자동으로 찾음. 직접 고르기도 항상 가능.
 */
@Composable
fun BackupFinderPanel(vm: JarvisViewModel, onPickManually: () -> Unit) {
    val ctx = LocalContext.current
    val list = vm.backupCandidates
    if (!vm.backupNeedsAccess && !vm.searchingBackup && list == null) return
    val fmt = remember { java.text.SimpleDateFormat("M/d(E) HH:mm", java.util.Locale.KOREA) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔎 백업 파일 찾기", fontWeight = FontWeight.Bold)
            when {
                vm.backupNeedsAccess -> {
                    Text("다운로드 폴더(카톡에서 저장한 파일 포함)에서 백업을 자동으로 찾으려면 '모든 파일 접근'을 한 번 켜 주세요. " +
                         "켜고 돌아오면 바로 찾아요. 파일은 폰 밖으로 나가지 않아요.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { ctx.startActivity(ModelFinder.accessSettingsIntent(ctx)) }) { Text("권한 켜고 자동으로 찾기") }
                }
                vm.searchingBackup -> {
                    Text("다운로드 폴더를 찾는 중…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                list.isNullOrEmpty() ->
                    Text("백업 파일을 못 찾았어요. 카톡으로 받은 파일이라면 카톡에서 '저장'을 누른 뒤 다시 찾거나, 직접 골라 주세요.",
                        style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text("${list.size}개 찾았어요 · 가장 최근 것이 맨 위예요", style = MaterialTheme.typography.bodySmall)
                    list.take(5).forEachIndexed { i, c ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text((if (i == 0) "⭐ " else "") + fmt.format(c.created) + " 저장" + if (c.encrypted) " · 🔒" else "",
                                        fontWeight = FontWeight.Bold)
                                    Text(c.summary.ifEmpty { "쌤톡스 설정" }, style = MaterialTheme.typography.bodySmall)
                                    Text(c.where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Button(onClick = { vm.restoreCandidate(c) }) { Text("불러오기") }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onPickManually) { Text("직접 파일 고르기") }
        }
    }
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            repeat(STEPS) { i ->
                Box(Modifier.height(6.dp).weight(1f).clip(CircleShape).background(
                    if (i <= vm.onboardingStep) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

// ① 소개
@Composable
private fun WelcomeStep(vm: JarvisViewModel) {
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SsamLogo(112.dp) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("쌤톡스", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text("선생님을 위한 디지털 디톡스", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("톡은 쌤톡스가 지켜볼게요.\n선생님은 잠시 쉬세요.",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Bullet("학부모 연락, 학급 단톡, 일정 알림을 모아 두고")
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("💾 지난 설정을 찾았어요", fontWeight = FontWeight.Bold)
                Text("${fmt.format(found.first)} 저장 · ${found.second}", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.restoreBackup(null) }) { Text("불러오기") }
            }
        }
    }
    if (vm.status.startsWith("💾") || vm.status.startsWith("불러오지")) Text(vm.status, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary)
    PrimaryButton("시작하기") { vm.onboardingStep = 1 }
    TextButton(onClick = { vm.findBackups() }, modifier = Modifier.fillMaxWidth()) {
        Text("💾 예전에 쓰던 설정 불러오기 (백업 파일 자동 찾기)")
    }
    BackupFinderPanel(vm, onPickManually = { restorePicker.launch(arrayOf("application/json", "*/*")) })
    // 모델 파일을 이미 다운로드 폴더에 받아 둔 경우: 진단·다운로드를 건너뛰고 바로 가져오기로
    TextButton(onClick = { vm.onboardingStep = 2 }, modifier = Modifier.fillMaxWidth()) {
        Text("모델 파일을 이미 받아 뒀어요 → 바로 가져오기")
    }
    Text("쌤톡스 v${vm.appVersion}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp, end = 10.dp).size(6.dp).clip(CircleShape).background(Ssam.Sprout))
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
    Text("✔ ${d.reason}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
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
                        Text(m.title, fontWeight = FontWeight.Bold)
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
    Text("나중에 설정 → 🤖 AI 모델·권한 → 바꾸기에서 언제든 바꿀 수 있어요.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    PrimaryButton("${vm.selectedModel.title}(으)로 진행") { vm.onboardingStep = 2 }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
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
         "이미 다운로드 폴더에 받아 뒀다면 ①은 건너뛰고 ② 파일 선택만 하면 돼요. (E4B·E2B 어느 파일이든 알아서 인식해요)",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // 이미 받아 둔 파일 자동 찾기
    LaunchedEffect(Unit) { vm.scanDownloads() }
    AutoFindCard(vm)

    // 데이터 요금 안내 (앱은 네트워크 상태 권한도 없어서 직접 확인하지 않고 사용자에게 물어봄)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("📶 와이파이에서 받으세요", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
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

/** 다운로드 폴더에 이미 받아 둔 모델을 찾아 한 번에 가져오는 카드. */
@Composable
private fun AutoFindCard(vm: JarvisViewModel) {
    val ctx = LocalContext.current
    if (vm.hasModel) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔎 이미 받아 둔 모델 찾기", fontWeight = FontWeight.Bold)
            when {
                !vm.fileAccess -> {
                    Text("다운로드 폴더에 받아 둔 파일을 쌤톡스가 직접 찾아요. '모든 파일 접근'을 한 번 켜 주세요. " +
                         "쌤톡스는 인터넷 권한이 없어서 파일이 폰 밖으로 나가지 않아요. 다 가져온 뒤 꺼도 돼요.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { ctx.startActivity(ModelFinder.accessSettingsIntent(ctx)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink)) {
                        Text("권한 켜고 자동으로 찾기")
                    }
                }
                !vm.scanned -> Text("다운로드 폴더를 찾는 중…", style = MaterialTheme.typography.bodyMedium)
                vm.found.isEmpty() -> {
                    Text("다운로드 폴더에서 모델 파일을 못 찾았어요. 아래 ①로 받거나, 다른 폴더에 있다면 ② 파일 선택을 쓰세요.",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { vm.scanDownloads() }) { Text("다시 찾기", color = Ssam.Chalk) }
                }
                else -> vm.found.take(3).forEach { f ->
                    Text("찾았어요: ${f.model?.title ?: "모르는 모델"} · ${f.label}", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { vm.importFound(f) }, enabled = !vm.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Ssam.Sprout, contentColor = Ssam.Ink)) {
                        Text("이 파일 가져오기")
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideCard(
    number: String, title: String, desc: String, button: String,
    done: Boolean, enabled: Boolean = true, onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(
                if (done) Ssam.Sprout else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(if (done) "✓" else number, fontWeight = FontWeight.Bold,
                    color = if (done) Ssam.Ink else MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
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

    StepTitle("4 / 4 · 권한", "마지막으로 두 가지만 허용해 주세요")
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
    Text("권한을 켠 뒤부터 오는 알림이 모여요. 요약 시간(아침·저녁·N시간마다·수업 뒤)과 수업 중 모아 두기는 설정 → 🔕 모아 두기·요약 시간에서 정해요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PrimaryButton(if (vm.listenerEnabled && vm.notiPermission) "쌤톡스 시작하기" else "나중에 하고 시작하기") {
        vm.finishOnboarding()
    }
}
