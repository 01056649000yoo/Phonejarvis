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
import kotlinx.coroutines.delay
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
    // ── 🔕 모아 두기·요약 시간 (v1.7.0) ──
    var schedule by mutableStateOf(SummarySchedule.config(app))
        private set
    var restAdded by mutableStateOf(RestDays.added(app))
        private set
    private fun reloadSchedule() {
        val ctx = getApplication<Application>()
        schedule = SummarySchedule.config(ctx); restAdded = RestDays.added(ctx); quietRev++
    }
    private fun editSchedule(block: android.content.SharedPreferences.Editor.() -> Unit) {
        SummarySchedule.update(getApplication<Application>(), block); reloadSchedule()
    }
    fun addTime(m: Int) { SummarySchedule.setTimes(getApplication<Application>(), schedule.times + m); reloadSchedule() }
    fun removeTime(m: Int) { SummarySchedule.setTimes(getApplication<Application>(), schedule.times - m); reloadSchedule() }
    fun setInterval(h: Int) = editSchedule { putInt("interval_h", h) }
    fun setIntervalFrom(m: Int) = editSchedule { putInt("interval_from", m) }
    fun setIntervalTo(m: Int) = editSchedule { putInt("interval_to", m) }
    fun setDigest(on: Boolean) {
        // 알릴 시간이 하나도 없이 켜면 모아 두기만 하고 알리지 않으니 아침 8:00·저녁 17:00 을 넣어 둠
        val empty = schedule.times.isEmpty() && schedule.intervalHours == 0
        editSchedule { putBoolean("digest", on); if (on && empty) putString("times", "${8 * 60},${17 * 60}") }
        // 켜자마자 지금 상태바에 남은 모으는 앱 알림도 정리
        if (on) NotificationCollectorService.instance?.sweep()
    }
    fun setRestSkip(on: Boolean) = editSchedule { putBoolean("rest_skip", on) }
    fun setDaily(on: Boolean) = editSchedule { putBoolean("daily_on", on) }
    fun setDailyTime(m: Int) = editSchedule { putInt("daily_min", m) }
    /** 🎯 중요한 대화만 요약 (기본 켬) */
    var focusTalk by mutableStateOf(Briefings.focusOn(getApplication<Application>()))
        private set
    fun changeFocusTalk(on: Boolean) { Briefings.setFocus(getApplication<Application>(), on); focusTalk = on }
    fun setDndAuto(on: Boolean) { Dnd.setAuto(getApplication<Application>(), on); quietRev++ }
    fun setHideWhen(h: QuietMode.Hide) {
        quiet { putString("hide_when", h.name) }
        // 지금 숨길 때가 되었으면 이미 와 있던 알림도 바로 정리
        if (NotificationCollectorService.shouldHide(getApplication<Application>())) NotificationCollectorService.instance?.sweep()
    }
    /** 다음 요약 시각 한 줄(설정 위 상태 줄) */
    fun nextRunText(): String {
        val run = BriefingScheduler.nextRun(getApplication<Application>()) ?: return "요약 시간이 없어요"
        val fmt = java.text.SimpleDateFormat("M/d(E) HH:mm", java.util.Locale.KOREA)
        return "다음 요약 ${fmt.format(run.at)} · ${BriefingScheduler.labelOf(run.kind, run.minute)}"
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
    /** 이 버전이 나온(빌드된) 시각 — 직접 빌드해 시각이 없으면 이 폰에 설치·업데이트한 시각 */
    val appUpdatedAt: Long = BuildConfig.BUILD_TIME.takeIf { it > 0 }
        ?: runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    /** 'v1.14.0 · 11월 25일(수) 22:53 업데이트' (2026-11-25 선생님: 버전을 잘 보이게, 업데이트 일시도) */
    val versionLine: String get() = "v$appVersion" + if (appUpdatedAt > 0)
        " · " + java.text.SimpleDateFormat("M월 d일(E) HH:mm", java.util.Locale.KOREA).format(appUpdatedAt) + " 업데이트" else ""
    val device: DeviceReport by lazy { DeviceCheck.run(app) }
    /** 사용자가 고른 모델. 기본값은 진단 결과의 추천 모델. */
    var chosenModel by mutableStateOf<ModelOption?>(null)
    val selectedModel get() = chosenModel ?: device.recommended
    /** 지금 설치된 모델 (파일 크기로 알아냄). 모르는 파일이면 null. */
    var installedModel by mutableStateOf(
        prefs.getString("model_id", null)?.let { runCatching { ModelOption.valueOf(it) }.getOrNull() })
        private set

    init {
        // 예전 판의 학생·학부모 묶음이 비어 있으면 없앤다(2026-11-21 고정 묶음 없앰)
        People.dropEmptyBuiltIns(app)   // 예전 👪 학부모 명부는 보통 묶음(🤝 지인)으로
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


    // ── 탭: 브리핑(결과만) / 설정 ──
    enum class Tab { BRIEFING, ALERTS, HISTORY, SETTINGS }
    /** 처음 들어오면 설정 탭부터. 알림 앱 정리를 한 번 적용한 뒤에는 브리핑 탭부터. */
    var tab by mutableStateOf(if (prefs.getBoolean("apps_setup_done", false)) Tab.BRIEFING else Tab.SETTINGS)

    // ── 알림 앱 정리 (AI 추천) ──
    var appRecs by mutableStateOf<List<AppRec>>(emptyList())
        private set
    var excludedApps by mutableStateOf(AppFilter.excluded(app))
        private set
    var aiBusy by mutableStateOf(false)
        private set
    /** 설치돼 있지만 최근 7일 알림이 0건인 메시지 앱 (패키지, 이름). 예: PC 카톡 사용 중이라 폰 알림이 꺼진 경우 */
    var silentApps by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    private var aiDoneFor: Set<String> = emptySet()

    /**
     * 앱 목록과 규칙 추천을 바로 보여 주고, ai=true 이고 모델이 있으면 AI 추천으로 다시 다듬음 (앱 목록이 바뀌었을 때만).
     * AI(3.7GB 모델을 GPU에 올림)는 🌱 알림 앱 정리 화면을 열었을 때만 — 예전엔 앱을 열 때마다 돌아
     * 화면과 GPU를 다퉈 하단 메뉴가 멈춘 듯 보였다(2026-11-22).
     */
    fun loadApps(ai: Boolean = false) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            // 최근 7일 알림을 보낸 앱 + 예전에 알림을 보낸 앱(뺀 앱 포함) + 직접 추가한 앱 → 한 번 빼도 목록에서 사라지지 않음
            // 🌱 첫날 보완: 알림 접근을 켤 때 알림창에 있던 알림(ShadeSeed) + 처음 정리 전에는 폰에 설치된 앱도
            val stats = withContext(Dispatchers.IO) {
                val recent = ShadeSeed.combine(db.appStats(7), ShadeSeed.load(ctx))
                val have = recent.map { it.pkg }.toMutableSet()
                val older = db.knownApps().filter { have.add(it.first) }.map { AppStat(it.first, it.second, 0, emptyList()) }
                val added = AppFilter.addedApps(ctx).filter { have.add(it) }.map { AppStat(it, labelOf(it), 0, emptyList()) }
                // 설치된 앱: 처음 정리 전에는 메시지·업무·알림 보내는 앱을 모두, 그 뒤에는 메시지·업무 앱(Gmail·캘린더 등)과 뺀 앱
                // (정리 뒤 알림이 아직 없던 Gmail 이 목록에서 사라지던 것 — 2026-11-23)
                val excluded = AppFilter.excluded(ctx)
                val installed = installedApps(ctx)
                    .filter { it.group <= 2 && (!appsApplied || it.group <= 1 || it.pkg in excluded) && have.add(it.pkg) }
                    .map { AppStat(it.pkg, it.name, 0, emptyList()) }
                recent + older + added + installed
            }
            val pkgs = stats.map { it.pkg }.toSet()
            // AI 가 이미 본 목록 그대로면 AI 추천(모으기·빼기·이유)은 지키고, 건수·경고·묶음은 새로 읽은 것으로.
            // 예전엔 여기서 그냥 돌아가서, AI 추천을 한 번 받은 뒤로는 앱을 다시 켤 때까지 건수와 '알림이 안 와요' 경고가 옛것 그대로였다
            val aiKept = if (pkgs == aiDoneFor) appRecs.filter { it.byAi }.associateBy { it.stat.pkg } else emptyMap()
            silentApps = withContext(Dispatchers.IO) {
                // 최근 7일에 알림이 온 적 있는 앱(뺀 방·광고라 모으지 않은 것 포함)은 '알림이 안 와요'가 아님
                val weekAgo = System.currentTimeMillis() - 7 * 24L * 3600 * 1000
                val arrived = db.knownApps().filter { it.third >= weekAgo }.map { it.first }.toSet()
                AppAdvisor.silentMessagingApps(ctx, stats, arrived)
            }
            workApps = AppFilter.workApps(ctx, stats)
            val rules = AppAdvisor.sortForDisplay(AppAdvisor.byRules(stats).map { r ->
                aiKept[r.stat.pkg]?.let { r.copy(keep = it.keep, reason = it.reason, byAi = true) } ?: r
            }, workApps)
            appRecs = rules
            excludedApps = AppFilter.excluded(ctx)
            reviewedApps = AppFilter.reviewed(ctx)
            if (pkgs == aiDoneFor && rules.isNotEmpty()) return@launch
            // 키워드 추천: 최근 7일 모은 알림(뺀 앱·방 제외)에서 규칙으로 바로
            val recent = withContext(Dispatchers.IO) {
                val r = AppFilter.rules(ctx)
                db.since(System.currentTimeMillis() - 7 * 24L * 3600 * 1000).filter { r.allows(it) }
            }
            keywordRecs = KeywordAdvisor.byRules(recent)
            if (!ai || !summarizer.hasModel() || stats.isEmpty() || aiBusy || BriefingService.active) return@launch
            aiBusy = true
            try {
                appRecs = AppAdvisor.sortForDisplay(AppAdvisor.byAi(ctx, rules), workApps)
                keywordRecs = KeywordAdvisor.byAi(ctx, recent, keywordRecs)   // AI가 더 골라 줌
                aiDoneFor = pkgs
            } finally { aiBusy = false }
        }
    }

    private fun labelOf(pkg: String): String = runCatching {
        val pm = getApplication<Application>().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ── ＋ 앱 추가: 폰에 설치된 앱 중 목록에 없는 앱 ──
    /** 앱 추가 창의 앱 하나. group: 0 메시지·SNS · 1 업무·일정 (둘 다 추천) · 2 알림을 보내는 앱 · 3 그 밖의 앱 */
    data class AddableApp(val pkg: String, val name: String, val group: Int)
    /** 앱 추가 창. null이면 닫힘 */
    var addableApps by mutableStateOf<List<AddableApp>?>(null)
        private set
    fun openAddApps() {
        val ctx = getApplication<Application>()
        addableApps = emptyList()
        viewModelScope.launch {
            addableApps = withContext(Dispatchers.IO) {
                val listed = appRecs.map { it.stat.pkg }.toSet()
                installedApps(ctx, fresh = true).filter { it.pkg !in listed }.map { AddableApp(it.pkg, it.name, it.group) }
            }
        }
    }
    /** 설치된 앱 목록은 느려서(앱마다 권한 조회) 한 번 읽어 두고 씀. ＋ 앱 추가 창을 열 때는 새로 읽음 */
    @Volatile private var installedCache: List<InstalledApp>? = null
    private fun installedApps(ctx: Application, fresh: Boolean = false): List<InstalledApp> =
        (if (fresh) null else installedCache) ?: runCatching { InstalledApps.list(ctx) }.getOrDefault(emptyList()).also { installedCache = it }
    fun closeAddApps() { addableApps = null }
    fun addApp(pkg: String, name: String) {
        val ctx = getApplication<Application>()
        AppFilter.addApp(ctx, pkg)
        excludedApps = AppFilter.excluded(ctx)
        reviewedApps = AppFilter.reviewed(ctx)
        addableApps = addableApps?.filterNot { it.pkg == pkg }
        status = "＋ $name 추가! 이제 이 앱 알림이 오면 모아요."
        aiDoneFor = emptySet()
        loadApps(ai = true)
    }

    /** "안 써요": 이 앱은 알림이 안 들어와도 다시 경고하지 않음 */
    fun dismissSilent(pkg: String) {
        AppFilter.dismissSilent(getApplication<Application>(), pkg)
        silentApps = silentApps.filterNot { it.first == pkg }
    }

    /** 💼 업무·일정 앱 (지정한 앱만 요약의 업무·일정 칸에) */
    var workApps by mutableStateOf<Set<String>>(emptySet())
        private set
    fun setWork(pkg: String, on: Boolean) {
        val ctx = getApplication<Application>()
        AppFilter.setWork(ctx, pkg, on, workApps)
        workApps = AppFilter.workApps(ctx, appRecs.map { it.stat })
        excludedApps = AppFilter.excluded(ctx)
        appRecs = AppAdvisor.sortForDisplay(appRecs, workApps)
    }

    fun setAppKeep(pkg: String, keep: Boolean) {
        val ctx = getApplication<Application>()
        AppFilter.setKeep(ctx, pkg, keep)
        excludedApps = AppFilter.excluded(ctx)
        reviewedApps = AppFilter.reviewed(ctx)
        refresh()
    }

    /** "추천대로 적용": 한 번에 반영하고 브리핑 탭으로. */
    /** 이미 살펴본(적용했거나 직접 켜고 끈) 앱. 여기 없는 앱만 다시 추천해 물어봄 */
    var reviewedApps by mutableStateOf(AppFilter.reviewed(app))
        private set
    /** 추천을 한 번이라도 적용했는지 */
    var appsApplied by mutableStateOf(prefs.getBoolean("apps_setup_done", false))
        private set
    /** 아직 묻지 않은 새 앱의 추천 */
    val newAppRecs: List<AppRec> get() = appRecs.filter { it.stat.pkg !in reviewedApps }

    fun applyAppRecs() {
        val ctx = getApplication<Application>()
        // 아직 살펴보지 않은 앱만 — 처음 적용할 때도 직접 켜고 끈 앱·직접 추가한 앱은 건드리지 않음
        // (예전엔 처음엔 전체에 적용해서, 미리 꺼 둔 앱이 '모으기' 추천으로 다시 켜지고 켜 둔 앱이 꺼졌다)
        val target = newAppRecs
        AppFilter.apply(ctx, target)
        excludedApps = AppFilter.excluded(ctx)
        reviewedApps = AppFilter.reviewed(ctx)
        prefs.edit().putBoolean("apps_setup_done", true).apply()
        appsApplied = true
        val dropped = target.count { !it.keep }
        status = if (dropped > 0) "앱 ${dropped}개를 뺐어요. 이제 학교·연락 알림만 모아요." else "추천대로 적용했어요."
        tab = Tab.BRIEFING; page = null
        refresh()
    }

    // ── 방 고르기 (메시지·SNS 앱 안의 단톡방) ──
    /** 방 고르기 창을 연 앱 (패키지, 이름). null이면 닫힘. */
    var roomApp by mutableStateOf<Pair<String, String>?>(null)
        private set
    var rooms by mutableStateOf<List<KnownRoom>>(emptyList())
        private set
    var excludedRooms by mutableStateOf(AppFilter.excludedRooms(app))
        private set

    fun openRooms(pkg: String, name: String) {
        roomApp = pkg to name
        rooms = emptyList()
        loadRooms(pkg)
    }
    /** 지금까지 알림이 온 모든 방 + 직접 추가한 방 */
    private fun loadRooms(pkg: String) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            rooms = withContext(Dispatchers.IO) {
                val known = db.knownRooms(pkg)
                val names = known.map { it.name }.toMutableSet()
                // 설정에만 남은 방(백업을 불러온 새 폰, 180일 넘게 조용했던 방): 뺀 방·학부모 방·보일 모양을 정한 방.
                // 예전엔 '뺀 방 3개'라고 나오는데 목록에는 없어 다시 켤 수가 없었다
                val fromSettings = (AppFilter.excludedRooms(ctx) + ParentAdvisor.parentRooms(ctx) + RoomTier.all(ctx).keys)
                    .filter { it.startsWith("$pkg|") }.map { it.substringAfter('|') }
                known + (AppFilter.manualRooms(ctx, pkg) + fromSettings).filter { it.isNotBlank() && names.add(it) }.map { KnownRoom(it, 0, 0L) }
            }
        }
    }
    /** ＋ 방 이름 직접 추가 (아직 알림이 안 온 방도 미리 켜고 끄기) */
    fun addRoom(pkg: String, name: String) {
        AppFilter.addManualRoom(getApplication<Application>(), pkg, name)
        loadRooms(pkg)
    }
    fun closeRooms() { roomApp = null; refresh() }
    fun isRoomOn(pkg: String, room: String) = !AppFilter.isRoomExcluded(excludedRooms, pkg, room)
    fun setRoom(pkg: String, room: String, keep: Boolean) {
        val ctx = getApplication<Application>()
        AppFilter.setRoomKeep(ctx, pkg, room, keep)
        excludedRooms = AppFilter.excludedRooms(ctx)
    }
    fun setAllRooms(pkg: String, keep: Boolean) {
        val ctx = getApplication<Application>()
        AppFilter.setAllRooms(ctx, pkg, rooms.map { it.name }, keep)
        excludedRooms = AppFilter.excludedRooms(ctx)
    }
    // ── 🙋 나를 부르는 말 (2026-11-25: 여러 선생님이 쓰는 앱 — 나에게 온 대화를 알아보는 기준) ──
    var myNames by mutableStateOf(MyNames.all(app))
        private set
    fun myNameSuggestions() = MyNames.suggestions(getApplication<Application>(), AppFilter.keywords(getApplication<Application>()))
    fun addMyName(name: String) { MyNames.add(getApplication<Application>(), name); myNames = MyNames.all(getApplication<Application>()) }
    fun removeMyName(name: String) { MyNames.remove(getApplication<Application>(), name); myNames = MyNames.all(getApplication<Application>()) }
    var myNamesTipClosed by mutableStateOf(prefs.getBoolean("my_names_tip_closed", false))
        private set
    fun closeMyNamesTip() { prefs.edit().putBoolean("my_names_tip_closed", true).apply(); myNamesTipClosed = true }

    /** 📖 설치 방법 페이지(docs/install.html) — 브라우저로 열기·공유(앱은 인터넷 권한이 없어 직접 열지 않음) */
    private val INSTALL_GUIDE = "https://ssamtox.xn--9y2br3k43n.kr/install.html"
    fun openInstallGuide() {
        runCatching { getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(INSTALL_GUIDE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun shareInstallGuide() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "쌤톡스 — 단톡방 알림을 폰 안 AI가 한 장으로 요약해 주는 앱이에요. 설치 방법은 여기 차례대로 따라 하면 돼요: $INSTALL_GUIDE")
        runCatching { getApplication<Application>().startActivity(Intent.createChooser(send, "설치 링크 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** 📦 잠깐 나갔던 com.ssamtox 판(v1.15.0)이 아직 설치돼 있나 — 두 앱이 함께 있으면 알림을 두 번 모음(2026-11-25 패키지 이름 되돌림) */
    val oldAppInstalled: Boolean get() = getApplication<Application>().let { a -> a.packageName != OLD_PACKAGE &&
        runCatching { a.packageManager.getPackageInfo(OLD_PACKAGE, 0); true }.getOrDefault(false) }
    private val OLD_PACKAGE = "com.ssamtox"
    fun openOldAppSettings() {
        runCatching { getApplication<Application>().startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$OLD_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** 🎯 방마다 직접 정한 거리(늘 중요 1 · 한 줄 2 · 건수만 3, 없으면 저절로) */
    var roomTiers by mutableStateOf(RoomTier.all(app))
        private set
    fun roomTier(pkg: String, room: String) = roomTiers[RoomTier.key(pkg, room)] ?: Relevance.AUTO
    fun setRoomTier(pkg: String, room: String, tier: Int) {
        val ctx = getApplication<Application>()
        RoomTier.set(ctx, pkg, room, tier)
        roomTiers = RoomTier.all(ctx)
    }
    /** 앱 목록에 보여 줄 "뺀 방 N개" */
    fun excludedRoomCount(pkg: String) = excludedRooms.count { it.startsWith("$pkg|") }

    // ── 중요 키워드 ──
    var keywords by mutableStateOf(AppFilter.keywords(app))
        private set
    fun addKeyword(word: String) {
        val w = word.trim()
        if (w.isEmpty() || w in keywords) return
        AppFilter.setKeywords(getApplication<Application>(), keywords + w)
        keywords = AppFilter.keywords(getApplication<Application>())
    }
    /** 지난 요약 이후 새로 온(아직 요약 안 한) 오늘 알림 수 */
    var pendingCount by mutableIntStateOf(0)
        private set
    /** 아직 요약 안 한 오늘 알림 (요약하면 사라짐) */
    var pendingItems by mutableStateOf<List<NotiItem>>(emptyList())
        private set

    // ── 탭 안의 세부 화면 (목록 → 누르면 옆에서 밀려 들어오는 화면) ──
    /** 세부 화면. parent 가 있으면 뒤로 가기가 그 화면으로 (사람 묶음 → 묶음 하나) */
    enum class Page(val title: String, val tab: Tab, val parent: Page? = null) {
        APPS("🌱 알림 앱 정리", Tab.SETTINGS), SCHEDULE("🔕 모아서 알림", Tab.SETTINGS),
        SYSTEM("🤖 AI 모델·권한", Tab.SETTINGS), BACKUP("💾 백업·복원", Tab.SETTINGS), STORAGE("🧹 저장 공간", Tab.SETTINGS),
        PEOPLE("👥 사람 묶음", Tab.SETTINGS),
        GROUP("👥 묶음", Tab.SETTINGS, PEOPLE), KEYWORDS("⭐ 중요 키워드", Tab.SETTINGS, PEOPLE),
        MY_NAMES("🙋 나를 부르는 말", Tab.SETTINGS, PEOPLE),
    }
    /** 지금 열린 세부 화면 (null이면 탭의 첫 화면) */
    var page by mutableStateOf<Page?>(if (prefs.getBoolean("apps_setup_done", false)) null else Page.APPS)
    fun open(p: Page) { tab = p.tab; page = p; if (p == Page.APPS) loadApps(ai = true) }

    // ── 👥 사람들: 쓰는 사람이 만드는 묶음 ──
    var groups by mutableStateOf(People.groups(app))
        private set
    /** 지금 열어 본 묶음 (Page.GROUP) */
    var groupId by mutableStateOf("")
        private set
    val currentGroup: PeopleGroup? get() = groups.firstOrNull { it.id == groupId }
    var groupMembers by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    private fun reloadGroups() {
        val ctx = getApplication<Application>()
        groups = People.groups(ctx)
        groupMembers = groups.associate { it.id to People.members(ctx, it.id) }
    }
    fun openGroup(id: String) { reloadGroups(); groupId = id; open(Page.GROUP) }
    fun addGroup(emoji: String, name: String) {
        val g = People.addGroup(getApplication<Application>(), emoji, name) ?: return
        reloadGroups(); status = "${g.title} 묶음을 만들었어요. 이름을 쭉 넣어 주세요."
        openGroup(g.id)
    }
    fun renameGroup(id: String, emoji: String, name: String) { People.rename(getApplication<Application>(), id, emoji, name); reloadGroups() }
    fun removeGroup(id: String) {
        People.remove(getApplication<Application>(), id); reloadGroups()
        if (groupId == id) page = null
        refresh()
    }
    fun unhideGroup(id: String) { People.unhide(getApplication<Application>(), id); reloadGroups(); refresh() }
    fun addMembers(id: String, raw: String): Int {
        val added = People.addMembers(getApplication<Application>(), id, raw)
        reloadGroups()
        if (added.isNotEmpty()) { status = "👥 ${added.size}명 넣었어요. 이 이름이 보이는 알림을 이 묶음으로 모아요."; refresh() }
        return added.size
    }
    fun removeMember(id: String, name: String) { People.removeMember(getApplication<Application>(), id, name); reloadGroups(); refresh() }
    /** 오늘 탭: 묶음별 오늘 연락 (묶음, [(이름, 알림)]) */
    var peopleToday by mutableStateOf<List<Pair<PeopleGroup, List<Pair<String, NotiItem>>>>>(emptyList())
        private set
    /** 📝 최근 자동 요약 기록 (🔕 모아서 알림 화면) */
    var autoLog by mutableStateOf<List<AutoLog.Entry>>(emptyList())
        private set
    fun selectTab(t: Tab) { tab = t; page = null }
    /** 뒤로: 세부 화면 → (윗 세부 화면) → 탭 첫 화면 → 요약 탭. 더 갈 곳이 없으면 false */
    fun back(): Boolean = when {
        page != null -> { page = page?.parent; true }
        tab != Tab.BRIEFING -> { tab = Tab.BRIEFING; true }
        else -> false
    }

    // ── 💾 백업·복원 ──
    var lastBackup by mutableStateOf(Backup.lastBackupTime(app))
        private set
    /** 다운로드/쌤톡스에서 찾은 백업 (저장 시각, 요약) */
    var foundBackup by mutableStateOf<Pair<Long, String>?>(null)
        private set
    fun checkBackup() {
        viewModelScope.launch { foundBackup = withContext(Dispatchers.IO) { Backup.found(getApplication()) } }
    }
    fun backupNow() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Backup.saveToDownloads(ctx) }
            lastBackup = Backup.lastBackupTime(ctx)
            status = if (ok) "💾 다운로드/쌤톡스 폴더에 백업했어요." else "백업하지 못했어요. '파일로 내보내기'로 저장해 보세요."
        }
    }
    fun exportBackup(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Backup.exportTo(ctx, uri) }
            lastBackup = Backup.lastBackupTime(ctx)
            status = if (ok) "💾 백업 파일을 저장했어요." else "백업 파일을 저장하지 못했어요."
        }
    }

    /** 📥 받은 백업 파일: (uri, (저장 시각, 요약)). null이면 창 닫힘 */
    var incomingBackup by mutableStateOf<Pair<Uri, Pair<Long, String>>?>(null)
        private set
    fun offerRestore(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { BackupShare.peek(ctx, uri) }
            r.onSuccess { incomingBackup = uri to it }
                .onFailure { status = "불러오지 못했어요: ${it.message}" }
        }
    }
    fun acceptIncoming() { incomingBackup?.let { restoreBackup(it.first) }; incomingBackup = null }
    fun dismissIncoming() { incomingBackup = null }

    /** uri가 null이면 다운로드/쌤톡스에서 찾은 백업을 불러옴 */
    fun restoreBackup(uri: Uri?, pin: String? = null) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (uri == null) Backup.restoreFromDownloads(ctx, pin) else Backup.restoreFrom(ctx, uri, pin)
            }
            val err = r.exceptionOrNull()
            when {
                // 🔒 암호화된 백업: 비밀번호 창 띄우기 (틀리면 다시)
                err is Backup.NeedPin -> { pinAsk = PinAsk(uri); pinError = "" }
                err is IllegalArgumentException && pin != null -> { pinAsk = PinAsk(uri); pinError = err.message ?: "" }
                else -> {
                    pinAsk = null
                    status = r.fold({ "💾 설정을 불러왔어요 · $it" }, { "불러오지 못했어요: ${it.message}" })
                    if (r.isSuccess) { hasBackupPin = BackupCrypto.hasPin(ctx); reloadAll() }
                }
            }
        }
    }

    // ── 🔒 백업 비밀번호 ──
    /** 불러오기에 비밀번호가 필요할 때 (uri가 null이면 다운로드/쌤톡스의 백업) */
    data class PinAsk(val uri: Uri?)
    var pinAsk by mutableStateOf<PinAsk?>(null)
        private set
    var pinError by mutableStateOf("")
        private set
    fun cancelPin() { pinAsk = null; pinError = "" }
    var hasBackupPin by mutableStateOf(BackupCrypto.hasPin(app))
        private set
    /** 예전에 정한 짧은 비밀번호(4~5자리) — 바꾸라고 안내 */
    var weakPin by mutableStateOf((BackupCrypto.pin(app)?.length ?: 99) < BackupCrypto.MIN_LEN)
        private set
    fun setBackupPin(pin: String) {
        if (pin.length !in BackupCrypto.MIN_LEN..20) { status = "백업 비밀번호는 ${BackupCrypto.MIN_LEN}~20자로 정해 주세요."; return }
        val ctx = getApplication<Application>()
        BackupCrypto.setPin(ctx, pin); hasBackupPin = true; weakPin = false
        status = "💾 백업 비밀번호를 정했어요. 새 폰에서 불러올 때 이 비밀번호가 필요해요."
        // 바로 새 비밀번호로 백업 — 예전엔 다음 자동 백업(길면 20시간 뒤)까지 백업이 없거나, 바꾸기 전 비밀번호로 잠긴 파일이 남아
        // 그 사이에 다시 설치하면 새 비밀번호로는 열리지 않았다
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Backup.saveToDownloads(ctx) }
            lastBackup = Backup.lastBackupTime(ctx)
        }
    }
    /** 복원 뒤 화면 상태를 저장된 설정으로 다시 읽기 */
    private fun reloadAll() {
        val ctx = getApplication<Application>()
        students = Students.names(ctx); keywords = AppFilter.keywords(ctx)
        excludedApps = AppFilter.excluded(ctx); excludedRooms = AppFilter.excludedRooms(ctx)
        parentRooms = ParentAdvisor.parentRooms(ctx); parentLinks = ParentBook.links(ctx)
        adFilterOn = AdFilter.enabled(ctx)
        // 예전엔 아래 값들을 다시 읽지 않아, 불러온 뒤에도 화면은 불러오기 전 상태였다. 특히 '추천 적용 여부'가 옛값이면
        // 알림 앱 정리의 "추천대로 적용"이 방금 불러온 뺀 앱 목록을 추천으로 덮어썼다
        appsApplied = prefs.getBoolean("apps_setup_done", false)
        reviewedApps = AppFilter.reviewed(ctx)
        aiDoneFor = emptySet()
        myNames = MyNames.all(ctx); roomTiers = RoomTier.all(ctx)
        focusTalk = Briefings.focusOn(ctx)
        notiDays = Storage.notiDays(ctx); briefDays = Storage.briefDays(ctx); logDays = Storage.logDays(ctx)
        reloadGroups(); reloadTasks(); refreshMuted()
        reloadSchedule()
        foundBackup = null
        refresh()
    }

    // ── 📋 브리핑 카드 접기·숨기기, 📚 브리핑 모음 ──
    var briefings by mutableStateOf<List<Briefing>>(emptyList())
        private set
    /** 접어 둔 브리핑(시각 키). 한 번 접으면 다시 열어도 접힌 채로 */
    var collapsedBriefings by mutableStateOf(prefs.getStringSet("collapsed_briefings", emptySet())!!.toSet())
        private set
    /** 맨 위에서 숨긴 브리핑(시각 키). 모음에는 그대로 남음 */
    var hiddenBriefings by mutableStateOf(prefs.getStringSet("hidden_briefings", emptySet())!!.toSet())
        private set
    private fun saveSet(key: String, s: Set<String>) =
        prefs.edit().putStringSet(key, s.toList().takeLast(100).toSet()).apply()
    fun toggleBriefing(b: Briefing) {
        val k = b.time.toString()
        collapsedBriefings = if (k in collapsedBriefings) collapsedBriefings - k else collapsedBriefings + k
        saveSet("collapsed_briefings", collapsedBriefings)
    }
    /** 📄 브리핑 세부 내역: (브리핑, 그 브리핑이 정리한 원래 알림들). null이면 닫힘 */
    var briefingDetail by mutableStateOf<Pair<Briefing, List<NotiItem>>?>(null)
        private set
    fun openBriefingDetail(b: Briefing) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val items = withContext(Dispatchers.IO) {
                val rules = AppFilter.rules(ctx)
                db.since(b.fromTime).filter { it.time <= b.time && rules.allows(it) }
            }
            briefingDetail = b to items
        }
    }
    /** 완료 알림을 눌러 들어왔을 때: 그 시각의 브리핑을 찾아 세부 내역 열기 */
    fun openBriefingDetail(time: Long) {
        viewModelScope.launch {
            val b = withContext(Dispatchers.IO) { db.briefingAt(time) } ?: return@launch
            tab = Tab.BRIEFING; page = null
            if (b.time.toString() in hiddenBriefings) { hiddenBriefings = hiddenBriefings - b.time.toString(); saveSet("hidden_briefings", hiddenBriefings) }
            openBriefingDetail(b)
        }
    }
    fun closeBriefingDetail() { briefingDetail = null }

    /** 맨 위로 올리라는 신호(바뀔 때마다 지금 화면을 맨 위로) */
    var scrollTopSignal by mutableStateOf(0)
        private set
    /**
     * 🏠 위젯 본문을 눌렀을 때(2026-11-23 선생님: 마지막에 보던 화면이 열림 → 바로 요약 내용이 보이게):
     * 📋 요약 탭 첫 화면으로, 떠 있던 창은 닫고, 최신 요약은 숨김·접힘을 풀어 맨 위에 펼쳐 보임
     */
    fun showLatestSummary() {
        tab = Tab.BRIEFING; page = null
        briefingDetail = null; roomApp = null; addableApps = null; studentView = null
        scrollTopSignal++
        viewModelScope.launch {
            val b = withContext(Dispatchers.IO) { db.lastBriefing() } ?: return@launch
            val k = b.time.toString()
            if (k in hiddenBriefings) { hiddenBriefings = hiddenBriefings - k; saveSet("hidden_briefings", hiddenBriefings) }
            if (k in collapsedBriefings) { collapsedBriefings = collapsedBriefings - k; saveSet("collapsed_briefings", collapsedBriefings) }
            refresh()
        }
    }

    // ── ✉️ 문자 답장 초안 ──
    var replyDrafts by mutableStateOf<List<ReplyDraft>>(emptyList())
        private set
    /** 지금 초안을 쓰는 중인 보낸 사람 (없으면 null) */
    var draftingFor by mutableStateOf<String?>(null)
        private set
    fun canReplyDirectly(d: ReplyDraft) = ReplyHandles.canReplyDirectly(d.sender)
    private suspend fun reloadReplies() { val l = withContext(Dispatchers.IO) { db.openReplies() }; replyDrafts = l }
    /** ✏️ 초안 만들기·다시 쓰기 (누를 때 모델을 불러와 한 건만) */
    fun makeDraft(d: ReplyDraft) {
        if (draftingFor != null || bgRunning) return
        draftingFor = d.sender
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val items = db.since(d.lastTime - 3 * 24 * 3600_000L).filter { it.pkg == d.pkg && it.room.isEmpty() && it.title == d.sender }
                        .ifEmpty { listOf(NotiItem(0, d.pkg, "문자", d.sender, d.question, d.lastTime)) }
                    ReplyDrafter.clean(summarizer.complete(ReplyDrafter.prompt(d.sender, items), maxTokens = ReplyDrafter.MAX_TOKENS))
                }
                if (text.isNotEmpty()) withContext(Dispatchers.IO) { db.setReplyDraft(d.pkg, d.sender, text) }
                else status = "초안을 만들지 못했어요. 다시 눌러 주세요."
            } catch (t: Throwable) {
                status = "초안 만들기 실패 · ${t.javaClass.simpleName}"
            } finally {
                draftingFor = null
                AppScope.io.launch { summarizer.release() }   // 화면에서 쓴 모델도 바로 내려 폰을 가볍게
                reloadReplies()
            }
        }
    }
    /** 💬 보내기: 고친 글로. 바로 답장이 되면 끝난 것으로, 아니면 문자 앱을 열고 '끝' 표시는 선생님이 */
    fun sendReply(d: ReplyDraft, text: String): String {
        val ctx = getApplication<Application>()
        val direct = ReplyHandles.canReplyDirectly(d.sender)
        val msg = ReplyHandles.send(ctx, d.pkg, d.sender, text)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { if (direct && msg.endsWith("보냈어요")) db.doneReply(d.pkg, d.sender) else db.setReplyDraft(d.pkg, d.sender, text) }
            reloadReplies()
        }
        return msg
    }
    fun doneReply(d: ReplyDraft) {
        viewModelScope.launch { withContext(Dispatchers.IO) { db.doneReply(d.pkg, d.sender) }; reloadReplies() }
    }

    // ── 🧹 저장 공간 ──
    var storageUsage by mutableStateOf<Storage.Usage?>(null)
        private set
    var notiDays by mutableStateOf(Storage.notiDays(app))
        private set
    var briefDays by mutableStateOf(Storage.briefDays(app))
        private set
    var logDays by mutableStateOf(Storage.logDays(app))
        private set
    var cleaning by mutableStateOf(false)
        private set
    fun setRetention(key: String, days: Int) {
        val ctx = getApplication<Application>()
        Storage.setDays(ctx, key, days)
        notiDays = Storage.notiDays(ctx); briefDays = Storage.briefDays(ctx); logDays = Storage.logDays(ctx)
    }
    private fun runClean(block: (Application) -> Long) {
        if (cleaning) return
        cleaning = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val (freed, use) = withContext(Dispatchers.IO) { block(ctx) to Storage.usage(ctx) }
            storageUsage = use; cleaning = false
            status = if (freed > 0) "🧹 ${Storage.size(freed)} 비웠어요." else "🧹 정리했어요. 더 지울 기록이 없어요."
            refresh()
        }
    }
    /** 지금 정리하기: 보관 기간이 지난 기록 지우고 파일 줄이기 */
    fun cleanNow() = runClean { Storage.clean(it, vacuum = true) }
    /** 받은 알림 원문 모두 지우기 (브리핑·학생 기록·할 일은 남음) */
    fun clearNotis() = runClean { Storage.clearNotis(it) }
    fun hideBriefing(b: Briefing) {
        hiddenBriefings = hiddenBriefings + b.time.toString(); saveSet("hidden_briefings", hiddenBriefings)
    }
    fun unhideBriefing(b: Briefing) {
        hiddenBriefings = hiddenBriefings - b.time.toString(); saveSet("hidden_briefings", hiddenBriefings)
    }

    // ── 🚫 광고 자동 거르기 ──
    var adFilterOn by mutableStateOf(AdFilter.enabled(app))
        private set
    var adCount by mutableIntStateOf(AdFilter.countToday(app))
        private set
    fun setAdFilter(on: Boolean) {
        AdFilter.setEnabled(getApplication<Application>(), on); adFilterOn = on; refresh()
    }

    // ── 🧒 우리 반 명부 + 학생별 기록 ──
    var students by mutableStateOf<List<String>>(emptyList())
        private set
    var studentCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** 학생별 기록 창: (이름, 기록들). null이면 닫힘 */
    var studentView by mutableStateOf<Pair<String, List<StudentEntry>>?>(null)
        private set

    fun addStudents(raw: String) {
        val ctx = getApplication<Application>()
        val added = Students.add(ctx, raw)
        students = Students.names(ctx)
        status = if (added.isNotEmpty()) "${added.size}명을 명부에 넣었어요. 최근 알림에서 기록을 찾는 중이에요." else status
        // 최근 7일 알림에서 바로 기록 채우기
        viewModelScope.launch(Dispatchers.IO) {
            val names = Students.names(ctx)
            db.since(System.currentTimeMillis() - 7 * 24L * 3600 * 1000).forEach { Students.log(ctx, names, it) }
            val counts = db.studentCounts()
            withContext(Dispatchers.Main) { studentCounts = counts }
        }
    }
    fun removeStudent(name: String) {
        Students.remove(getApplication<Application>(), name)
        students = Students.names(getApplication<Application>())
    }
    fun openStudent(name: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val (log, senders) = withContext(Dispatchers.IO) {
                db.studentLog(name) to ParentBook.recentSenders(
                    db.since(System.currentTimeMillis() - 7 * 24L * 3600 * 1000), ParentBook.links(ctx))
            }
            parentLinks = ParentBook.links(ctx)
            recentSenders = senders
            studentView = name to log
        }
    }
    fun closeStudent() { studentView = null; refresh() }

    // ── 👪 학부모 명부 (학생 ↔ 학부모 이름·번호) ──
    var parentLinks by mutableStateOf(ParentBook.links(app))
        private set
    /** "최근 연락에서 고르기": 문자·1:1 대화의 최근 보낸 사람 */
    var recentSenders by mutableStateOf<List<String>>(emptyList())
        private set
    /** 학부모 명부 화면을 열 때: 최근 7일 보낸 사람(아직 등록 안 한) 불러오기 */
    fun loadRecentSenders() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            recentSenders = withContext(Dispatchers.IO) {
                ParentBook.recentSenders(db.since(System.currentTimeMillis() - 7 * 24L * 3600 * 1000), ParentBook.links(ctx))
            }
            parentLinks = ParentBook.links(ctx)
        }
    }
    /** 👪 이름 쭉 넣기: 아무 형식(쉼표·줄바꿈·띄어쓰기)이든 알아서 나눠 명부에 넣음. 넣은 사람 수를 돌려줌 */
    fun addParentsRaw(raw: String): Int {
        val ctx = getApplication<Application>()
        val links = ParentBook.parse(raw, Students.names(ctx))
        if (links.isEmpty()) return 0
        // "이서윤 아버님"처럼 관계를 같이 쓴 학생은 우리 반 명부에도 추가
        val newStudents = links.filter { it.relation.isNotBlank() && it.student !in Students.names(ctx) &&
            it.student.length in 2..4 && it.student.all { c -> c in '가'..'힣' } }.map { it.student }.distinct()
        if (newStudents.isNotEmpty()) { Students.add(ctx, newStudents.joinToString(",")); students = Students.names(ctx) }
        links.forEach { ParentBook.add(ctx, it) }
        parentLinks = ParentBook.links(ctx)
        val people = links.map { it.student to it.relation }.distinct().size
        recentSenders = recentSenders.filter { s -> links.none { it.match == s } }
        status = "👪 ${people}명 넣었어요. 이제 어떤 앱이든 이 이름·번호가 보이면 자동으로 모아요."
        backfillParents()
        return people
    }
    private fun backfillParents() {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val names = Students.names(ctx)
            db.since(System.currentTimeMillis() - 7 * 24L * 3600 * 1000).forEach { Students.log(ctx, names, it) }
            val counts = db.studentCounts()
            withContext(Dispatchers.Main) { studentCounts = counts; refresh() }
        }
    }

    fun addParentLink(student: String, relation: String, match: String) {
        val ctx = getApplication<Application>()
        val s = student.trim(); val mm = match.trim()
        if (s.length < 2 || mm.length < 2) return
        // 명부에 없는 학생이면 함께 추가
        if (s !in Students.names(ctx)) { Students.add(ctx, s); students = Students.names(ctx) }
        ParentBook.add(ctx, ParentLink(s, relation, mm))
        parentLinks = ParentBook.links(ctx)
        recentSenders = recentSenders - match
        status = "👪 $s $relation(${mm}) 등록! 이제 어떤 앱이든 이 이름·번호가 보이면 자동으로 모아요."
        // 최근 7일 알림에서 이 학부모 연락을 학생 기록에 바로 채움
        viewModelScope.launch(Dispatchers.IO) {
            val names = Students.names(ctx)
            db.since(System.currentTimeMillis() - 7 * 24L * 3600 * 1000).forEach { Students.log(ctx, names, it) }
            val log = db.studentLog(s)
            val counts = db.studentCounts()
            withContext(Dispatchers.Main) {
                studentCounts = counts
                if (studentView?.first == s) studentView = s to log
                refresh()
            }
        }
    }
    fun removeParentLink(l: ParentLink) {
        ParentBook.remove(getApplication<Application>(), l)
        parentLinks = ParentBook.links(getApplication<Application>())
    }

    // ── 📌 마감·할 일 ──
    var tasks by mutableStateOf<List<Task>>(emptyList())
        private set
    private fun reloadTasks() { viewModelScope.launch { tasks = withContext(Dispatchers.IO) { db.tasks() } } }
    fun setTaskDone(t: Task, done: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { db.setTaskDone(t.id, done) }.invokeOnCompletion { reloadTasks() }
    }
    fun deleteTask(t: Task) {
        viewModelScope.launch(Dispatchers.IO) { db.deleteTask(t.id) }.invokeOnCompletion { reloadTasks() }
    }
    /** 직접 추가: 오늘/내일/모레 (0/1/2일 뒤) */
    fun addTask(title: String, daysLater: Int) {
        if (title.isBlank()) return
        val due = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, daysLater)
            set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val t = Task(title = title.trim().take(60), room = "직접 추가", due = due, hasTime = false, sourceTime = now, dedupe = "manual|$now")
        viewModelScope.launch(Dispatchers.IO) { db.insertTask(t) }.invokeOnCompletion { reloadTasks() }
    }

    // ── 🏫 수업 중 · 🌙 퇴근 모드 ──
    /** 설정이 바뀔 때마다 올려서 화면을 다시 그림 (값은 QuietMode가 저장) */
    var quietRev by mutableIntStateOf(0)
        private set
    fun quiet(block: android.content.SharedPreferences.Editor.() -> Unit) {
        QuietMode.update(getApplication<Application>(), block); quietRev++
    }
    /** 교시 시작·끝 바꾸기. 시작을 바꾸면 수업 길이는 그대로 유지(끝도 같이 이동) */
    fun setPeriodStart(index: Int, start: Int) = editRanges { list ->
        val (s, e) = list[index]; list[index] = start to (start + (e - s))
    }
    fun setPeriodEnd(index: Int, end: Int) = editRanges { list ->
        val (s, _) = list[index]; if (end > s) list[index] = s to end
    }
    /** 마지막 교시 뒤에 같은 길이로 하나 더 (쉬는 시간 10분) */
    fun addPeriod() = editRanges { list ->
        val last = list.lastOrNull() ?: (9 * 60 - 50 to 9 * 60 - 10)
        val len = last.second - last.first
        list.add(last.second + 10 to last.second + 10 + len)
    }
    fun removePeriod(index: Int) = editRanges { it.removeAt(index) }
    fun applyPeriodPreset(list: List<Pair<Int, Int>>) {
        QuietMode.setRanges(getApplication<Application>(), list); quietRev++
    }
    private fun editRanges(block: (MutableList<Pair<Int, Int>>) -> Unit) {
        val ctx = getApplication<Application>()
        val list = QuietMode.ranges(ctx).toMutableList()
        runCatching { block(list) }
        QuietMode.setRanges(ctx, list); quietRev++
    }
    fun dndGranted(): Boolean =
        getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted

    // ── 👪 학부모 연락 ──
    var parentRooms by mutableStateOf(ParentAdvisor.parentRooms(app))
        private set
    var parentContacts by mutableStateOf<List<ParentContact>>(emptyList())
        private set
    var handledKeys by mutableStateOf<Set<String>>(emptySet())
        private set
    /** 답장 추천 결과 (연락 키 → 답장들) 와 지금 만들고 있는 연락 키 */
    var drafts by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    var draftingKey by mutableStateOf<String?>(null)
        private set

    fun isParentRoom(pkg: String, room: String) = ParentAdvisor.isParent(parentRooms, pkg, room)
    fun setParentRoom(pkg: String, room: String, on: Boolean) {
        ParentAdvisor.setParent(getApplication<Application>(), pkg, room, on)
        parentRooms = ParentAdvisor.parentRooms(getApplication<Application>())
    }
    /** 방 고르기 창: 이름으로 학부모 같아 보이는데 아직 지정 안 한 방 */
    fun suggestedParentRooms(pkg: String) =
        rooms.map { it.name }.filter { ParentAdvisor.looksParent(it) && !isParentRoom(pkg, it) }
    fun setAllSuggestedParents(pkg: String) = suggestedParentRooms(pkg).forEach { setParentRoom(pkg, it, true) }

    fun markHandled(c: ParentContact) {
        ParentAdvisor.markHandled(getApplication<Application>(), c.key)
        handledKeys = ParentAdvisor.handled(getApplication<Application>())
    }
    fun draftReply(c: ParentContact) {
        if (draftingKey != null) return
        draftingKey = c.key
        viewModelScope.launch {
            try {
                val list = ParentAdvisor.drafts(getApplication<Application>(), c)
                drafts = drafts + (c.key to list)
            } finally { draftingKey = null }
        }
    }

    /** 최근 알림에서 찾은 추천 키워드 (단어, 나온 횟수). */
    var keywordRecs by mutableStateOf<List<Pair<String, Int>>>(emptyList())
        private set
    fun addAllKeywordRecs() {
        AppFilter.setKeywords(getApplication<Application>(), keywords + keywordRecs.map { it.first })
        keywords = AppFilter.keywords(getApplication<Application>())
    }
    fun removeKeyword(word: String) {
        AppFilter.setKeywords(getApplication<Application>(), keywords - word)
        keywords = AppFilter.keywords(getApplication<Application>())
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
        tab = Tab.BRIEFING; page = null
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
    /** ⏰ 정한 시각 정확히 알림(알람·리마인더 권한) — 없으면 몇 분 늦을 수 있음 */
    var exactAlarm by mutableStateOf(true)
        private set

    // 🏠 홈 화면 위젯(2026-11-23 선생님: 설치하면 위젯도 바로 쓰게 안내)
    var hasWidget by mutableStateOf(true)
        private set
    var widgetTipClosed by mutableStateOf(prefs.getBoolean("widget_tip_closed", false))
        private set
    fun closeWidgetTip() { prefs.edit().putBoolean("widget_tip_closed", true).apply(); widgetTipClosed = true }
    private fun widgetCount(ctx: android.content.Context) = runCatching {
        android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetIds(android.content.ComponentName(ctx, SummaryWidgetReceiver::class.java)).size
    }.getOrDefault(0)
    /** 위젯을 홈 화면에 놓기: 폰이 지원하면 '홈 화면에 추가' 창, 아니면 직접 놓는 방법 안내 */
    fun pinWidget() {
        val ctx = getApplication<Application>()
        val am = android.appwidget.AppWidgetManager.getInstance(ctx)
        val ok = am.isRequestPinAppWidgetSupported &&
            runCatching { am.requestPinAppWidget(android.content.ComponentName(ctx, SummaryWidgetReceiver::class.java), null, null) }.getOrDefault(false)
        status = if (ok) "🏠 '홈 화면에 추가'를 누르면 위젯이 놓여요. 크기는 위젯을 길게 눌러 늘이고 줄일 수 있어요."
            else "🏠 홈 화면 빈 곳을 길게 누르기 → 위젯 → 쌤톡스 '요약'을 끌어다 놓으세요."
    }
    var todayItems by mutableStateOf<List<NotiItem>>(emptyList())
        private set
    /** 🔎 알림 검색: 낱말과 결과(null = 검색 안 함) */
    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<NotiItem>?>(null)
        private set
    private var searchJob: kotlinx.coroutines.Job? = null
    fun search(q: String) {
        searchQuery = q
        searchJob?.cancel()
        if (q.isBlank()) { searchResults = null; return }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250)   // 치는 동안 매번 찾지 않게
            val found = runCatching { db.search(q) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { if (searchQuery == q) searchResults = found }
        }
    }
    var status by mutableStateOf("")
        private set

    // 🔕 요약에서 뺀 알림 꼴(2026-11-23 선생님: 서버·시간 되면 오는 알림) — 알림 탭에서 길게 눌러 빼고, 아래 목록에서 되돌림
    var mutedShapes by mutableStateOf<Set<String>>(emptySet())
        private set
    fun refreshMuted() { mutedShapes = Routine.muted(getApplication()) }
    fun muteLike(n: NotiItem) {
        Routine.mute(getApplication(), Routine.key(n)); refreshMuted()
        status = "🔕 ${n.app} '${Routine.shape(n.title).ifBlank { "제목 없음" }}' 알림은 이제 요약에서 빼요. 알림 탭·검색에는 남아요."
    }
    fun unmute(key: String) { Routine.unmute(getApplication(), key); refreshMuted() }
    fun mutedLabel(key: String) = Routine.label(key, labelOf(key.substringBefore('|')))

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
        adCount = AdFilter.countToday(ctx)
        refreshMuted()
        // 앱을 닫았다 다시 열었을 때 백그라운드 정리가 아직 돌고 있으면 "정리 중" 표시
        if (BriefingService.active && !bgRunning) { bgRunning = true; watchBackground() }
        exactAlarm = BriefingScheduler.canExact(ctx.getSystemService(android.app.AlarmManager::class.java))
        hasWidget = widgetCount(ctx) > 0
        lastBackup = Backup.lastBackupTime(ctx)
        reloadSchedule()
        autoLog = AutoLog.all(ctx)
        if (onboardingDone) loadApps(ai = page == Page.APPS)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Storage.autoClean(ctx) }   // 🧹 보관 기간이 지난 기록 자동 정리 (6시간에 한 번)
            val use = runCatching { Storage.usage(ctx) }.getOrNull()
            withContext(Dispatchers.Main) { if (use != null) storageUsage = use }
            runCatching { Backup.autoBackup(ctx) }   // 💾 하루 한 번 자동 백업
            val rules = AppFilter.rules(ctx)
            val items = db.today().filter { rules.allows(it) }
            val names = Students.names(ctx)
            val parents = if (People.isVisible(ctx, "parents")) ParentAdvisor.contacts(ctx, items) else emptyList()
            val book = People.customBook(ctx)
            val people = items.filter(BriefingComposer::isPerson).filter { it.room.isEmpty() }   // 메시지 앱의 사람 연락만(카카오페이 송금 알림 등은 빼고), 단톡방 말은 그 방에(2026-11-24)
                .mapNotNull { n -> People.find(book, n, bodyToo = false)?.let { (g, name) -> Triple(g, name, n) } }   // 그 사람이 보낸 것만(내용에 이름만 나온 건 빼고)
                .groupBy { it.first.id }.map { (_, l) -> l.first().first to l.map { it.second to it.third } }
            // 학생별 기록 채우기 (📌 할 일 자동 등록은 2026-11-23 에 없앰 — 직접 넣은 할 일만)
            items.forEach { runCatching { Students.log(ctx, names, it) } }
            val taskList = db.tasks()
            val counts = db.studentCounts()
            val done = ParentAdvisor.handled(ctx)
            // 아직 요약하지 않은 새 알림 수 ("지금 정리하기" 버튼)
            val from = Briefings.pendingFrom(db)
            val pendingList = Briefings.pendingItems(db).filter { rules.allows(it) }   // 어제 저녁 요약 뒤에 온 것도(오늘 0시로 자르지 않음)
            val pending = pendingList.size
            withContext(Dispatchers.Main) {
                parentContacts = parents; handledKeys = done; pendingCount = pending; pendingItems = pendingList
                peopleToday = people; reloadGroups()
                tasks = taskList; students = names; studentCounts = counts
            }
            val last = db.lastBriefing()
            val all = db.briefings()
            val replies = db.openReplies()
            withContext(Dispatchers.Main) { todayItems = items; lastBriefing = last; briefings = all; replyDrafts = replies }
        }
    }

    // ── 🔎 받아 둔 모델 자동 찾기(2026-11-25 선생님: 파일 고르기 창에서 찾기 힘듦) ──
    /** 여러 개 찾았을 때 고를 목록(하나면 바로 가져옴) */
    var foundModels by mutableStateOf<List<java.io.File>>(emptyList())
        private set
    /** '모든 파일 접근'을 켜러 설정에 다녀오는 중 — 돌아오면(onResume) 바로 찾기 */
    private var findAfterGrant = false
    private fun hasAllFiles() = android.os.Environment.isExternalStorageManager()
    fun findModels() {
        val ctx = getApplication<Application>()
        if (!hasAllFiles()) {
            findAfterGrant = true
            status = "🔎 '모든 파일 접근'에서 쌤톡스를 켜고 돌아오면 바로 찾아요. (다운로드 폴더의 모델 파일을 읽는 데만 써요)"
            val pkg = android.net.Uri.parse("package:${ctx.packageName}")
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .recoverCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        status = "🔎 받아 둔 모델을 찾는 중…"
        viewModelScope.launch {
            val ext = android.os.Environment.getExternalStorageDirectory()
            val roots = listOf(android.os.Environment.DIRECTORY_DOWNLOADS, android.os.Environment.DIRECTORY_DOCUMENTS)
                .map { android.os.Environment.getExternalStoragePublicDirectory(it) } + ext
            val found = withContext(Dispatchers.IO) { ModelFinder.scan(roots, selectedModel.fileName) }
            when {
                found.isEmpty() -> status = "🔎 다운로드 폴더에서 모델 파일(.litertlm)을 찾지 못했어요. ① 다운로드 시작으로 받거나, 다운로드가 끝났는지 확인해 주세요."
                found.size == 1 -> { status = "🔎 찾았어요: ${found[0].name}"; importModel(android.net.Uri.fromFile(found[0])) }
                else -> { status = "🔎 모델 파일을 ${found.size}개 찾았어요. 가져올 것을 고르세요."; foundModels = found }
            }
        }
    }
    fun pickFoundModel(f: java.io.File?) { foundModels = emptyList(); if (f != null) importModel(android.net.Uri.fromFile(f)) }
    /** 설정에서 '모든 파일 접근'을 켜고 돌아왔을 때 */
    fun onResumeFind() { if (findAfterGrant && hasAllFiles()) { findAfterGrant = false; findModels() } }

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
                summarizer.clearEngineCache()   // 옛 모델에 맞춘 캐시는 지움(새 모델은 첫 요약 때 다시 만듦)
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

    /** 백그라운드에서 정리 중인지 */
    var bgRunning by mutableStateOf(false)
        private set
    private var watching = false

    private fun startBackgroundSummary() {
        // 이름은 '📋 00:25 지금 정리' — 예전 '정리 완료'는 지난 요약·알림 제목에서 무슨 요약인지 알 수 없었다(2026-11-24)
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(System.currentTimeMillis())
        BriefingScheduler.startNow(getApplication<Application>(), "📋 $hm 지금 정리")
        bgRunning = true; stopping = false
        status = "🔔 새 알림 ${pendingCount}건을 백그라운드에서 정리하고 있어요. 다 되면 알림으로 알려 드릴게요."
        watchBackground()
    }
    /** 서비스가 끝날 때까지 지켜보다가 끝나면 화면 새로고침 */
    private fun watchBackground() {
        if (watching) return
        watching = true
        viewModelScope.launch {
            var waited = 0
            while (!BriefingService.active && waited < 8000) { delay(250); waited += 250 }   // 서비스가 뜰 때까지
            while (BriefingService.active) delay(2000)
            watching = false
            if (bgRunning) status = "✅ 정리가 끝났어요. 알림창과 📚 지난 요약에서 볼 수 있어요."
            bgRunning = false; stopping = false
            lastBriefing = withContext(Dispatchers.IO) { db.lastBriefing() }
            refresh()
        }
    }

    /** "지금 정리하기": 건수와 상관없이 백그라운드에서 정리하고 끝나면 알림 (누르면 세부 내역) */
    fun summarizeToday() { startBackgroundSummary() }

    /** 요약이 너무 오래 걸릴 때 "중지". 지금 하던 부분이 끝나면 멈추고 그때까지 정리한 것을 보여 줌. */
    var stopping by mutableStateOf(false)
        private set
    fun stopSummary() {
        if (bgRunning) {
            val ctx = getApplication<Application>()
            runCatching { ctx.startService(Intent(ctx, BriefingService::class.java).setAction(BriefingService.ACTION_STOP)) }
        }
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
        QuietMode.tick(this)                  // 수업 중·퇴근 모드 상태 반영과 다음 경계 예약
        BackupShare.uriFrom(intent)?.let { vm.offerRestore(it) }   // 📥 받은 백업 파일로 열렸으면 불러오기 제안
        if (savedInstanceState == null) { openFromNotification(intent); openFromWidget(intent) }   // 폴드 접기·화면 회전으로 다시 그릴 때는 또 열지 않음
        setContent {
            SsamTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    // 첫 실행이면 설치 안내(폰 진단 → 모델 받기 → 권한), 끝났으면 메인 화면
                    // 🎬 설치 안내 ↔ 메인 화면: 부드럽게 겹쳐 바뀜
                    androidx.compose.animation.Crossfade(targetState = vm.onboardingDone, label = "main") { done ->
                        if (done) JarvisScreen(vm) else OnboardingScreen(vm)
                    }
                    // 🔒 암호화된 백업 불러오기: 비밀번호 입력
                    vm.pinAsk?.let { ask ->
                        var pin by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { vm.cancelPin() },
                            title = { Text("🔒 백업 비밀번호") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("이 백업은 비밀번호로 잠겨 있어요. 백업할 때 정한 비밀번호를 입력해 주세요.")
                                    OutlinedTextField(value = pin, onValueChange = { pin = it }, singleLine = true,
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                                        isError = vm.pinError.isNotEmpty(),
                                        supportingText = { if (vm.pinError.isNotEmpty()) Text(vm.pinError) })
                                }
                            },
                            confirmButton = { Button(onClick = { vm.restoreBackup(ask.uri, pin) }, enabled = pin.isNotEmpty()) { Text("불러오기") } },
                            dismissButton = { TextButton(onClick = { vm.cancelPin() }) { Text("취소") } },
                        )
                    }
                    // 📥 받은 백업 파일 → 불러오기 확인 (설치 안내 중이어도)
                    vm.incomingBackup?.let { (_, info) ->
                        val fmt = remember { SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA) }
                        AlertDialog(
                            onDismissRequest = { vm.dismissIncoming() },
                            title = { Text("💾 백업 파일을 받았어요") },
                            text = { Text("${fmt.format(info.first)} 저장 · ${info.second}\n\n이 설정으로 바꿀까요? 지금 설정은 이 파일 내용으로 덮어써져요.") },
                            confirmButton = { Button(onClick = { vm.acceptIncoming() }) { Text("불러오기") } },
                            dismissButton = { TextButton(onClick = { vm.dismissIncoming() }) { Text("취소") } },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume(); vm.refresh()
        vm.onResumeFind()   // 🔎 '모든 파일 접근'을 켜고 돌아왔으면 받아 둔 모델 바로 찾기
        AppScope.main.launch { SummaryWidget.refresh(this@MainActivity) }   // 🏠 앱에서 바꾼 것이 위젯에도
    }

    /** 🏠 위젯 [⚙️] 로 열었으면 설정 탭, 본문을 눌렀으면 📋 요약 탭의 최신 요약 */
    private fun openFromWidget(intent: Intent?) {
        when (intent?.getStringExtra(SummaryWidget.EXTRA_OPEN_TAB)) {
            "settings" -> vm.selectTab(JarvisViewModel.Tab.SETTINGS)
            "summary" -> vm.showLatestSummary()
            else -> return
        }
        intent.removeExtra(SummaryWidget.EXTRA_OPEN_TAB)
    }

    /** 🔔 정리 완료 알림을 눌러 열었으면 그 브리핑의 세부 내역 보여 주기 */
    private fun openFromNotification(intent: Intent?) {
        val t = intent?.getLongExtra(Notifications.EXTRA_BRIEFING, 0L) ?: 0L
        if (t > 0) { vm.openBriefingDetail(t); intent?.removeExtra(Notifications.EXTRA_BRIEFING) }
    }

    // 📥 카톡·드라이브에서 받은 백업 파일로 열렸을 때 (앱이 이미 떠 있으면 onNewIntent)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
        openFromWidget(intent)
        BackupShare.uriFrom(intent)?.let { vm.offerRestore(it) }
    }
}

