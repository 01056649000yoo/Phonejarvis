package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/** 🔎 받아 둔 모델 자동 찾기(2026-11-25) — 받는 중·작은 파일은 빼고, 고른 모델 이름 먼저 */
class ModelFinderTest {
    private fun sparse(f: File, size: Long) { f.parentFile.mkdirs(); RandomAccessFile(f, "rw").use { it.setLength(size) } }

    @Test fun 다운로드_폴더에서_모델을_찾음() {
        val root = Files.createTempDirectory("dl").toFile()
        try {
            sparse(File(root, "gemma-4-E2B-it.litertlm"), 2_600_000_000L)
            sparse(File(root, "모델/gemma-4-E4B-it.litertlm"), 3_700_000_000L)       // 한 단계 안 폴더
            sparse(File(root, "gemma-4-E4B-it.litertlm.crdownload"), 1_000_000_000L)  // 받는 중
            sparse(File(root, "small.litertlm"), 10_000_000L)                         // 너무 작음
            sparse(File(root, ".숨김/x.litertlm"), 3_000_000_000L)                    // 숨긴 폴더
            sparse(File(root, "a/b/c/d/deep.litertlm"), 3_000_000_000L)               // 너무 깊음
            val names = ModelFinder.scan(listOf(root, root), preferName = "gemma-4-E2B-it.litertlm").map { it.name }
            assertEquals(listOf("gemma-4-E2B-it.litertlm", "gemma-4-E4B-it.litertlm"), names)
            assertEquals("gemma-4-E4B-it.litertlm", ModelFinder.scan(listOf(root)).first().name)   // 고른 이름이 없으면 큰 것부터
        } finally { root.deleteRecursively() }
    }
}
