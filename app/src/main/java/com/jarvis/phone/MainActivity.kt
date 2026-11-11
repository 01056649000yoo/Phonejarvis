package com.jarvis.phone

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val summarizer = Summarizer(app)

    var listenerEnabled by mutableStateOf(false)
        private set
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
        viewModelScope.launch(Dispatchers.IO) {
            db.purgeOld()
            val items = db.today()
            withContext(Dispatchers.Main) { todayItems = items }
        }
    }

    /** 사용자가 고른 .litertlm 파일을 앱 내부 저장소로 복사 (약 2.6GB, 1~2분). */
    fun importModel(uri: Uri) {
        val ctx = getApplication<Application>()
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val total = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                val tmp = java.io.File(ctx.filesDir, "model.tmp")
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L; var lastPct = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); copied += n
                            if (total > 0) {
                                val pct = (copied * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct
                                    withContext(Dispatchers.Main) { status = "모델 복사 중… $pct%" } }
                            }
                        }
                    }
                }
                summarizer.close()
                tmp.renameTo(summarizer.modelFile)
                withContext(Dispatchers.Main) { status = "모델 준비 완료" }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { status = "모델 복사 실패: ${t.message}" }
            } finally {
                withContext(Dispatchers.Main) { busy = false; refresh() }
            }
        }
    }

    fun summarizeToday() {
        busy = true; summary = ""
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { db.today() }
                val start = System.currentTimeMillis()
                summary = summarizer.summarize(items) { s -> viewModelScope.launch { status = s } }
                status = "완료 (${(System.currentTimeMillis() - start) / 1000}초)"
            } catch (t: Throwable) {
                status = "요약 실패: ${t.message}"
            } finally { busy = false }
        }
    }

    override fun onCleared() { summarizer.close() }
}

class MainActivity : ComponentActivity() {
    private val vm: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { JarvisScreen(vm) } } }
    }

    override fun onResume() { super.onResume(); vm.refresh() }
}

@Composable
fun JarvisScreen(vm: JarvisViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("휴대폰 자비스", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

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
            title = "② AI 모델 (Gemma)",
            desc = if (vm.hasModel) "준비됨 (${vm.summarizer.modelFile.length() / 1_000_000}MB)"
                   else "다운로드한 .litertlm 파일을 선택하세요",
            button = if (vm.hasModel) "다시 선택" else "파일 선택",
            enabled = !vm.busy,
        ) { picker.launch(arrayOf("*/*")) }

        // 3. 요약
        Button(
            onClick = { vm.summarizeToday() },
            enabled = vm.hasModel && !vm.busy && vm.todayItems.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("오늘 알림 ${vm.todayItems.size}건 요약하기") }

        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall)

        if (vm.summary.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(vm.summary, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
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
