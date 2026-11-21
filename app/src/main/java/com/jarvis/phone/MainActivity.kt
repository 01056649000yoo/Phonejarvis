package com.jarvis.phone

import android.Manifest
import android.app.Application
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class JarvisViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NotificationDb.get(app)
    val summarizer = Summarizer.get(app)

    var listenerEnabled by mutableStateOf(false)
        private set
    var notiPermission by mutableStateOf(true)
        private set
    var lastBriefing by mutableStateOf<Briefing?>(null)
        private set
    var slots by mutableStateOf(readSlots())
        private set

    data class SlotState(val slot: BriefingSlot, val on: Boolean, val minute: Int)

    private fun readSlots(): List<SlotState> {
        val ctx = getApplication<Application>()
        return BriefingSlot.entries.map { SlotState(it, it.isEnabled(ctx), it.minute(ctx)) }
    }

    fun setSlot(slot: BriefingSlot, on: Boolean, minute: Int) {
        slot.save(getApplication<Application>(), on, minute)
        slots = readSlots()
    }

    /** "지금 브리핑 받아보기": 예약 브리핑과 똑같은 경로(포그라운드 서비스)로 바로 실행. */
    fun briefNow() {
        BriefingScheduler.startNow(getApplication<Application>(), "지금 브리핑")
        status = "브리핑을 시작했어요. 진행 상황은 알림창에서 볼 수 있어요."
    }

    // ── 첫 실행 설치 안내 ──
    private val prefs = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)

    /** 설치 안내를 끝냈는지. 모델이 없으면 안내를 끝냈어도 다시 보여줌. */
    var onboardingDone by mutableStateOf(prefs.getBoolean("onboarding_done", false))
        private set
    var onboardingStep by mutableIntStateOf(0)
    val device: DeviceReport by lazy { DeviceCheck.run(app) }
    /** 사용자가 고른 모델. 기본값은 진단 결과의 추천 모델. */
    var chosenModel by mutableStateOf<ModelOption?>(null)
    val selectedModel get() = chosenModel ?: device.recommended
    /** 지금 설치된 모델 (파일 크기로 알아냄). 모르는 파일이면 null. */
    var installedModel by mutableStateOf(
        prefs.getString("model_id", null)?.let { runCatching { ModelOption.valueOf(it) }.getOrNull() })
        private set

    /** 메인 화면의 "모델 바꾸기/받기": 설치 안내의 폰 진단 단계부터 다시 보여줌. */
    fun openModelGuide() {
        onboardingStep = 1
        onboardingDone = false
    }

    fun finishOnboarding() {
        prefs.edit().putBoolean("onboarding_done", true).apply()
        onboardingDone = true
    }

    var hasModel by mutableStateOf(false)
        private set
    var todayItems by mutableStateOf<List<NotiItem>>(emptyList())
        private set
    var status by mutableStateOf("")
        private set
    var summary by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    fun refresh() {
        val ctx = getApplication<Application>()
        listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(ctx)
            .contains(ctx.packageName)
        hasModel = summarizer.hasModel()
        notiPermission = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        slots = readSlots()
        viewModelScope.launch(Dispatchers.IO) {
            db.purgeOld()
            val items = db.today()
            val last = db.lastBriefing()
            withContext(Dispatchers.Main) { todayItems = items; lastBriefing = last }
        }
    }

    /**
     * 사용자가 고른 .litertlm 파일을 앱 내부 저장소로 복사 (E4B 약 3.7GB, 2~3분).
     * 화면을 나가도 취소되지 않도록 앱 전역 스코프에서 실행.
     */
    fun importModel(uri: Uri) {
        val ctx = getApplication<Application>()
        busy = true
        AppScope.io.launch {
            fun ui(block: () -> Unit) { AppScope.main.launch { block() } }
            val tmp = java.io.File(ctx.filesDir, "model.tmp")
            try {
                // 1) 파일 정보 확인
                var name = "?"; var total = -1L
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni) ?: "?"
                        if (si >= 0 && !c.isNull(si)) total = c.getLong(si)
                    }
                }
                if (total <= 0) total = runCatching {
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                }.getOrDefault(-1L)
                ui { status = "선택한 파일: $name (${if (total > 0) "${total / 1_000_000}MB" else "크기 모름"})" }

                if (name.endsWith(".crdownload") || name.endsWith(".part") || name.endsWith(".tmp"))
                    error("아직 다운로드 중인 파일이에요. 다운로드가 끝난 뒤 다시 선택하세요.")
                if (total in 1 until 500_000_000L)
                    error("파일이 너무 작아요(${total / 1_000_000}MB). 모델 파일이 아니거나 다운로드가 덜 됐어요. " +
                          "HuggingFace에서 파일 옆 다운로드(↓) 버튼으로 받은 .litertlm 파일을 선택하세요.")

                // 2) 저장공간 확인 (복사본 + 여유 500MB)
                val free = ctx.filesDir.usableSpace
                if (total > 0 && free < total + 500_000_000L)
                    error("저장공간 부족: 필요 ${(total + 500_000_000L) / 1_000_000}MB, 남은 공간 ${free / 1_000_000}MB")

                // 3) 복사
                tmp.delete()
                val input = ctx.contentResolver.openInputStream(uri)
                    ?: error("파일을 열 수 없어요. '내 파일' 앱 → 다운로드 폴더에서 다시 선택해 보세요.")
                var copied = 0L
                input.use { inp ->
                    java.io.FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(4 shl 20)
                        var lastPct = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); copied += n
                            val pct = if (total > 0) (copied * 100 / total).toInt() else -1
                            if (pct != lastPct) { lastPct = pct
                                val mb = copied / 1_000_000
                                ui { status = if (pct >= 0) "모델 복사 중… $pct%" else "모델 복사 중… ${mb}MB" } }
                        }
                        out.fd.sync()
                    }
                }
                if (total > 0 && copied != total)
                    error("복사가 중간에 끊겼어요 (${copied / 1_000_000}/${total / 1_000_000}MB). 다시 시도하세요.")

                // 4) 기존 모델 교체
                summarizer.release()   // 예약 브리핑이 모델을 쓰는 중이면 끝날 때까지 기다림
                java.nio.file.Files.move(tmp.toPath(), summarizer.modelFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                val detected = ModelOption.bySize(copied)
                prefs.edit().putString("model_id", detected?.name).apply()
                ui {
                    installedModel = detected
                    status = "모델 준비 완료 · ${detected?.title ?: "직접 고른 모델"} (${copied / 1_000_000}MB)"
                }
            } catch (t: Throwable) {
                tmp.delete()
                val raw = t.message.orEmpty()
                val msg = when {
                    t is IllegalStateException -> raw
                    "pending" in raw || "trashed" in raw ->
                        "다운로드가 아직 끝나지 않았거나(또는 멈췄거나) 휴지통에 있는 파일이에요. " +
                        "파일 선택 화면 왼쪽 메뉴(☰)에서 'Galaxy Z Fold4 → Download' 폴더로 들어가 직접 선택해 보세요."
                    else -> "${t.javaClass.simpleName}: $raw"
                }
                ui { status = "모델 복사 실패 — $msg" }
            } finally {
                ui { busy = false; refresh() }
            }
        }
    }

    fun summarizeToday() {
        busy = true; summary = ""
        viewModelScope.launch {
            try {
                val out = Briefings.run(getApplication<Application>(), "오늘 요약", NotificationDb.startOfToday()) { s ->
                    viewModelScope.launch { status = s }
                }
                summary = out.body
                lastBriefing = withContext(Dispatchers.IO) { db.lastBriefing() }
            } catch (t: Throwable) {
                status = "요약 실패: ${t.message}"
            } finally { busy = false }
        }
    }

    // 엔진은 예약 브리핑과 공유하므로 화면이 닫힐 때 메모리만 돌려줌
    override fun onCleared() { AppScope.io.launch { summarizer.release() } }
}

class MainActivity : ComponentActivity() {
    private val vm: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()   // 상태바까지 앱 배경색, 다크 모드에 맞춰 아이콘 색 자동
        Notifications.ensureChannels(this)
        BriefingScheduler.scheduleAll(this)   // 강제 종료 등으로 알람이 지워졌을 때를 대비해 다시 등록
        setContent {
            SsamTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    // 첫 실행이면 설치 안내(폰 진단 → 모델 받기 → 권한), 끝났으면 메인 화면
                    if (vm.onboardingDone) JarvisScreen(vm) else OnboardingScreen(vm)
                }
            }
        }
    }

    override fun onResume() { super.onResume(); vm.refresh() }
}

@Composable
fun JarvisScreen(vm: JarvisViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val dayTimeFmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREA) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SsamLogo(48.dp)
            Column {
                Text("쌤톡스", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text("선생님을 위한 디지털 디톡스", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 1. 알림 접근 권한
        SetupCard(
            done = vm.listenerEnabled,
            title = "① 알림 접근 권한",
            desc = if (vm.listenerEnabled) "허용됨" else "다른 앱의 알림을 읽으려면 허용이 필요해요",
            button = "설정 열기",
        ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        // 2. 모델 파일
        SetupCard(
            done = vm.hasModel,
            title = "② AI 모델",
            desc = if (vm.hasModel) "${vm.installedModel?.title ?: "직접 고른 모델"} · " +
                       "${vm.summarizer.modelFile.length() / 1_000_000}MB"
                   else "폰 진단 후 맞는 모델을 받아요",
            button = if (vm.hasModel) "바꾸기" else "받기",
            enabled = !vm.busy,
        ) { vm.openModelGuide() }

        // 3. 자동 브리핑 결과를 알림으로 받기 위한 권한 (Android 13+)
        val notiPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()) { vm.refresh() }
        SetupCard(
            done = vm.notiPermission,
            title = "③ 알림 표시 권한",
            desc = if (vm.notiPermission) "허용됨" else "자동 브리핑 결과를 알림으로 받으려면 필요해요",
            button = "허용하기",
            enabled = !vm.notiPermission,
        ) {
            if (Build.VERSION.SDK_INT >= 33) notiPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }

        // 4. 자동 브리핑 시간
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⏰ 자동 브리핑", fontWeight = FontWeight.Bold)
                Text("정한 시각에 지난 브리핑 이후(최대 24시간) 알림을 요약해 알림으로 보내줘요.",
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
                OutlinedButton(
                    onClick = { vm.briefNow() },
                    enabled = vm.hasModel && !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("지금 브리핑 받아보기 (테스트)") }
            }
        }

        // 5. 요약
        Button(
            onClick = { vm.summarizeToday() },
            enabled = vm.hasModel && !vm.busy && vm.todayItems.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("오늘 알림 ${vm.todayItems.size}건 요약하기") }

        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall)

        // 브리핑 카드: 로고처럼 칠판 그린 바탕에 분필 크림 글씨
        val briefingColors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        if (vm.summary.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = briefingColors) {
                Text(vm.summary, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        } else vm.lastBriefing?.let { b ->
            // 마지막 브리핑 (예약 브리핑 포함)
            Card(Modifier.fillMaxWidth(), colors = briefingColors) {
                Column(Modifier.padding(16.dp)) {
                    Text("${b.label} · ${dayTimeFmt.format(b.time)} · 알림 ${b.count}건",
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(b.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        // 수집된 원본 (최근 30건)
        if (vm.todayItems.isNotEmpty()) {
            Text("오늘 수집된 알림 (최근 30건)", fontWeight = FontWeight.Bold)
            vm.todayItems.takeLast(30).reversed().forEach {
                Text("${timeFmt.format(it.time)} [${it.app}] ${it.title}: ${it.text}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 2)
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
        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (done) "✅ " else "⬜ ") + title, fontWeight = FontWeight.Bold)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(button) }
        }
    }
}
