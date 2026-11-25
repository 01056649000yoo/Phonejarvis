package com.jarvis.phone

import java.io.File

/**
 * 🔎 받아 둔 AI 모델 자동 찾기(2026-11-25 선생님: "이미 다운로드된 모델이 있는데 찾으러 들어가면 찾기가 힘들어 — 자동으로").
 * 다운로드·문서 폴더 등에서 .litertlm 파일을 찾는다(폴더 안 3단계까지). 받는 중인 파일(.crdownload·.part)과 너무 작은 파일은 뺌.
 * 다른 앱이 받은 파일을 읽으려면 '모든 파일 접근'이 필요하다 — Play 스토어에 올리지 않기로 해서(APK 배포) 다시 씀.
 * 순수 규칙이라 시험할 수 있다(폴더를 넘겨 받음).
 */
object ModelFinder {
    const val MIN_BYTES = 500_000_000L
    const val MAX_DEPTH = 3

    /** 찾은 모델 파일들: [preferName](고른 모델 파일 이름)과 같은 것 먼저, 그다음 큰 것부터 */
    fun scan(roots: List<File>, preferName: String = ""): List<File> {
        val out = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in kids) {
                if (f.isDirectory) { if (depth < MAX_DEPTH && !f.name.startsWith(".")) walk(f, depth + 1) }
                else if (f.name.endsWith(".litertlm", ignoreCase = true) && f.length() >= MIN_BYTES) out += f
            }
        }
        roots.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }.filter { it.isDirectory }.forEach { walk(it, 0) }
        return out.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
            .sortedWith(compareByDescending<File> { preferName.isNotEmpty() && it.name.equals(preferName, ignoreCase = true) }.thenByDescending { it.length() })
    }
}
