package com.jarvis.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * 다운로드 폴더에 이미 받아 둔 Gemma 모델 파일(.litertlm)을 자동으로 찾는다.
 * 안드로이드는 다른 앱(브라우저)이 받은 파일을 보려면 '모든 파일 접근' 권한이 필요하다.
 * 쌤톡스는 인터넷 권한이 없어서, 이 권한을 켜도 파일이 폰 밖으로 나가지 않는다.
 */
object ModelFinder {
    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /** '모든 파일 접근' 설정 화면 (쌤톡스 항목으로 바로). */
    fun accessSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    data class Found(val file: File, val model: ModelOption?) {
        val label get() = "${file.name} (${"%.1f".format(file.length() / 1e9)}GB)"
    }

    /**
     * 다운로드 폴더(하위 폴더 한 단계 포함)에서 .litertlm 파일을 찾는다.
     * 다운로드가 덜 된 파일(.crdownload 등, 크기 불일치)은 제외. 원하는 모델 → 알려진 모델 → 큰 파일 순.
     */
    fun find(prefer: ModelOption?): List<Found> {
        if (!hasAccess()) return emptyList()
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidates = buildList {
            root.listFiles()?.forEach { f ->
                if (f.isDirectory) f.listFiles()?.let { addAll(it) } else add(f)
            }
        }
        return candidates
            .filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) && it.length() > 500_000_000L }
            .map { Found(it, ModelOption.bySize(it.length())) }
            // 이름은 공식 모델인데 크기가 다르면 다운로드가 덜 된 파일
            .filterNot { f -> f.model == null && ModelOption.entries.any { it.fileName.equals(f.file.name, true) } }
            .sortedWith(compareByDescending<Found> { it.model == prefer && prefer != null }
                .thenByDescending { it.model != null }
                .thenByDescending { it.file.length() })
    }
}
