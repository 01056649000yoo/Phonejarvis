package com.jarvis.phone

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.roundToInt

/** 받을 수 있는 온디바이스 AI 모델(SLM). 둘 다 Apache-2.0, 로그인 없이 받을 수 있음. */
enum class ModelOption(
    val title: String,
    val fileName: String,
    val bytes: Long,
    val summary: String,
) {
    E4B(
        "Gemma 4 E4B", "gemma-4-E4B-it.litertlm", 3_659_530_240L,
        "요약이 더 꼼꼼하고 자연스러워요. 램 8GB 이상인 요즘 폰에 맞아요(기본).",
    ),
    E2B(
        "Gemma 4 E2B", "gemma-4-E2B-it.litertlm", 2_588_147_712L,
        "가볍고 빨라요. 폰이 덜 뜨겁고 배터리를 덜 써요.",
    );

    val url get() = "https://huggingface.co/litert-community/${fileName.removeSuffix(".litertlm")}-litert-lm/resolve/main/$fileName?download=true"
    val sizeText get() = "%.1fGB".format(bytes / 1e9)

    /** 브라우저 다운로드본 + 앱 안 복사본이 잠깐 함께 있으므로 두 배 + 여유 0.5GB. */
    val neededBytes get() = bytes * 2 + 500_000_000L

    companion object {
        /** 진단을 못 하거나 애매할 때 쓰는 기본값(2026-11-21 선생님: 요즘 폰이면 E4B 가 돈다). */
        val DEFAULT = E4B

        /** 복사된 파일 크기로 어떤 모델인지 알아냄 (다른 이름으로 받아도 인식). */
        fun bySize(size: Long): ModelOption? = entries.firstOrNull { it.bytes == size }
    }
}

/** 폰 사양 진단 결과와 추천 모델. */
data class DeviceReport(
    val deviceName: String,
    val chip: String,
    val androidVersion: String,
    val ramGb: Int,
    val freeGb: Double,
    val recommended: ModelOption,
    val reason: String,
    val warning: String?,
)

object DeviceCheck {
    // 8GB 폰은 시스템 예약 때문에 실제로 약 7.3GB로 잡힘(2026-11-21: 12GB → 8GB, 요즘 폰 기본이 E4B)
    private const val RAM_FOR_E4B = 7_000_000_000L
    private const val RAM_MIN = 5_500_000_000L

    fun run(ctx: Context): DeviceReport {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val ram = mi.totalMem
        val free = ctx.filesDir.usableSpace

        val chip = buildString {
            if (Build.VERSION.SDK_INT >= 31) {
                append(Build.SOC_MANUFACTURER.takeUnless { it == Build.UNKNOWN }.orEmpty())
                val model = Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN }.orEmpty()
                if (model.isNotEmpty()) { if (isNotEmpty()) append(" "); append(model) }
            }
            if (isEmpty()) append(Build.HARDWARE)
        }

        val (rec, reason, warning) = when {
            ram >= RAM_FOR_E4B && free >= ModelOption.E4B.neededBytes -> Triple(
                ModelOption.E4B,
                "램이 넉넉해서 더 똑똑한 E4B를 돌릴 수 있어요.",
                null,
            )
            ram >= RAM_FOR_E4B -> Triple(
                ModelOption.E2B,
                "램은 E4B도 되지만, 저장공간이 부족해서 가벼운 E2B를 추천해요.",
                if (free < ModelOption.E2B.neededBytes) storageWarning(ModelOption.E2B, free) else null,
            )
            ram >= RAM_MIN -> Triple(
                ModelOption.E2B,
                "이 폰에는 가볍고 빠른 E2B가 잘 맞아요.",
                if (free < ModelOption.E2B.neededBytes) storageWarning(ModelOption.E2B, free) else null,
            )
            else -> Triple(
                ModelOption.DEFAULT,
                "램이 적은 편이라 가장 가벼운 E2B로 시작해요.",
                "권장 사양(램 6GB)보다 낮아서 요약이 느리거나 중간에 멈출 수 있어요.",
            )
        }

        return DeviceReport(
            deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            chip = chip,
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            ramGb = (ram / 1e9).roundToInt().let { roundUpToMarketing(it) },
            freeGb = free / 1e9,
            recommended = rec,
            reason = reason,
            warning = warning,
        )
    }

    private fun storageWarning(m: ModelOption, free: Long) =
        "저장공간이 ${"%.1f".format(free / 1e9)}GB 남았어요. ${m.title} 모델을 받으려면 " +
        "약 ${"%.1f".format(m.neededBytes / 1e9)}GB가 필요해요. 사진·동영상을 정리한 뒤 진행하세요."

    /** 11GB → 12GB처럼, 폰 광고에 쓰는 램 크기로 보여줌. */
    private fun roundUpToMarketing(gb: Int) =
        listOf(2, 3, 4, 6, 8, 12, 16, 24).firstOrNull { it >= gb } ?: gb
}
