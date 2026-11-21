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
import androidx.compose.runtime.saveable.rememberSaveable
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
    /**
     * 설치 안내의 현재 단계. 저장해 둬서, 권한 설정 화면에 다녀오는 사이 앱이 다시 시작돼도
     * 처음(모델 받기 전)으로 돌아가지 않고 하던 단계에서 이어짐.
     */
    private var stepState by mutableIntStateOf(prefs.getInt("onboarding_step", 0))
    var onboardingStep: Int
        get() = stepState
        set(v) { stepState = v; prefs.edit().putInt("onboarding_step", v).apply() }
    val appVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"
    val device: DeviceReport by lazy { DeviceCheck.run(app) }
    /** 사용자가 고른 모델. 기본값은 진단 결과의 추천 모델. */
    var chosenModel by mutableStateOf<ModelOption?>(null)
    val selectedModel get() = chosenModel ?: device.recommended
    /** 지금 설치된 모델 (파일 크기로 알아냄). 모르는 파일이면 null. */
    var installedModel by mutableStateOf(
        prefs.getString("model_id", null)?.let { runCatching { ModelOption.valueOf(it) }.getOrNull() })
        private set

    init {
        // 이미 모델이 설치된 폰(예전 버전에서 업데이트)은 설치 안내를 건너뛰고 바로 메인 화면으로.
        // 앱을 켤 때 한 번만 확인 — 메인의 "바꾸기"로 안내를 다시 연 경우에는 건너뛰지 않음.
        // 단, 마지막 권한 단계(3) 중이었다면 그 단계에서 이어서 보여줌.
        if (!onboardingDone && summarizer.hasModel() && onboardingStep != 3) {
            if (installedModel == null) {
                installedModel = ModelOption.bySize(summarizer.modelFile.length())
                prefs.edit().putString("model_id", installedModel?.name).apply()
            }
            finishOnboarding()
        }
    }

    // ── 다운로드 폴더에서 모델 자동 찾기 ──
    var fileAccess by mutableStateOf(ModelFinder.hasAccess())
        private set
    var found by mutableStateOf<List<ModelFinder.Found>>(emptyList())
        private set
    var scanned by mutableStateOf(false)
        private set

    fun scanDownloads() {
        fileAccess = ModelFinder.hasAccess()
        if (!fileAccess) return
        viewModelScope.launch(Dispatchers.IO) {
            val list = ModelFinder.find(selectedModel)
            withContext(Dispatchers.Main) { found = list; scanned = true }
        }
    }

    fun importFound(f: ModelFinder.Found) = importModel(Uri.fromFile(f.file))

    // ── 탭: 브리핑(결과만) / 설정 ──
    enum class Tab { BRIEFING, SETTINGS }
    /** 처음 들어오면 설정 탭부터. 알림 앱 정리를 한 번 적용한 뒤에는 브리핑 탭부터. */
    var tab by mutableStateOf(if (prefs.getBoolean("apps_setup_done", false)) Tab.BRIEFING else Tab.SETTINGS)

    // ── 알림 앱 정리 (AI 추천) ──
    var appRecs by mutableStateOf<List<AppRec>>(emptyList())
        private set
    var excludedApps by mutableStateOf(AppFilter.excluded(app))
        private set
    var aiBusy by mutableStateOf(false)
        private set
    private var aiDoneFor: Set<String> = emptySet()

    /** 앱 목록과 규칙 추천을 바로 보여 주고, 모델이 있으면 AI 추천으로 다시 다듬음 (앱 목록이 바뀌었을 때만). */
    fun loadApps() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { db.appStats(7) }
            val pkgs = stats.map { it.pkg }.toSet()
            if (pkgs == aiDoneFor && appRecs.isNotEmpty()) return@launch
            val rules = AppAdvisor.sortForDisplay(AppAdvisor.byRules(stats))
            appRecs = rules
            excludedApps = AppFilter.excluded(ctx)
            if (!summarizer.hasModel() || stats.isEmpty() || aiBusy) return@launch
            aiBusy = true
            try {
                appRecs = AppAdvisor.sortForDisplay(AppAdvisor.byAi(ctx, rules))
                aiDoneFor = pkgs
            } finally { aiBusy = false }
        }
    }

    fun setAppKeep(pkg: String, keep: Boolean) {
        val ctx = getApplication<Application>()
        AppFilter.setKeep(ctx, pkg, keep)
        excludedApps = AppFilter.excluded(ctx)
        refresh()
    }

    /** "추천대로 적용": 한 번에 반영하고 브리핑 탭으로. */
    fun applyAppRecs() {
        val ctx = getApplication<Application>()
        AppFilter.apply(ctx, appRecs)
        excludedApps = AppFilter.excluded(ctx)
        prefs.edit().putBoolean("apps_setup_done", true).apply()
        val dropped = appRecs.count { !it.keep }
        status = if (dropped > 0) "앱 ${dropped}개를 뺐어요. 이제 학교·연락 알림만 모아요." else "추천대로 적용했어요."
        tab = Tab.BRIEFING
        refresh()
    }

    /** 버전마다 "바뀐 점" 한 줄. 닫으면 다음 버전까지 다시 안 보임. */
    var showWhatsNew by mutableStateOf(prefs.getString("whats_new_seen", null) != appVersion)
        private set
    fun closeWhatsNew() {
        prefs.edit().putString("whats_new_seen", appVersion).apply()
        showWhatsNew = false
    }

    /** 아직 알림이 없을 때 "나중에 할게요": 브리핑 탭으로. */
    fun skipAppSetup() {
        prefs.edit().putBoolean("apps_setup_done", true).apply()
        tab = Tab.BRIEFING
    }

    /** 메인 화면의 "모델 바꾸기/받기": 설치 안내의 폰 진단 단계부터 다시 보여줌. */
    fun openModelGuide() {
        onboardingStep = 1
        onboardingDone = false
    }

    fun finishOnboarding() {
        prefs.edit().putBoolean("onboarding_done", true).apply()
        onboardingDone = true
        onboardingStep = 0
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
        // 모델 받기 단계에 있으면(권한 설정에서 돌아온 경우 포함) 다운로드 폴더를 다시 찾아봄
        if (!onboardingDone && onboardingStep == 2) scanDownloads()
        if (onboardingDone) loadApps()
        viewModelScope.launch(Dispatchers.IO) {
            db.purgeOld()
            val excluded = AppFilter.excluded(ctx)
            val items = db.today().filterNot { it.pkg in excluded }
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
                if (name == "?") name = uri.lastPathSegment ?: "?"   // 자동 찾기(file://)로 고른 경우
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
            } catch (s: Summarizer.StoppedException) {
                status = s.message ?: "중지했어요."
            } catch (t: Throwable) {
                status = "요약 실패: ${t.message}"
            } finally { busy = false; stopping = false }
        }
    }

    /** 요약이 너무 오래 걸릴 때 "중지". 지금 하던 부분이 끝나면 멈추고 그때까지 정리한 것을 보여 줌. */
    var stopping by mutableStateOf(false)
        private set
    fun stopSummary() {
        summarizer.requestStop()
        stopping = true
        status = "멈추는 중… 지금 하던 부분이 끝나면 멈춰요 (최대 1분)"
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

