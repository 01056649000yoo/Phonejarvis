package com.jarvis.phone

import android.content.Context

/**
 * 🧹 저장 공간: 쌓이는 기록을 보관 기간에 맞춰 자동으로 지워 앱이 무거워지지 않게.
 * - 받은 알림 원문 · 브리핑 모음 · 학생별 기록: 설정에서 기간 선택
 * - 끝낸 할 일 30일, 지난 미완료 할 일 60일, 앱·방 이름 목록 180일 (고정)
 * - 지운 자리는 일주일에 한 번 DB 파일을 줄여서(VACUUM) 실제 용량도 돌려받음
 */
object Storage {
    private fun p(ctx: Context) = ctx.getSharedPreferences("storage", Context.MODE_PRIVATE)

    /** 보관 기간 선택지 (일) */
    val NOTI_CHOICES = listOf(3, 7, 14, 30)
    val BRIEF_CHOICES = listOf(7, 30, 90)
    val LOG_CHOICES = listOf(90, 180, 365)

    fun notiDays(ctx: Context) = p(ctx).getInt("noti_days", 7)
    fun briefDays(ctx: Context) = p(ctx).getInt("brief_days", 30)
    fun logDays(ctx: Context) = p(ctx).getInt("log_days", 365)
    fun setDays(ctx: Context, key: String, days: Int) { p(ctx).edit().putInt(key, days).apply() }

    fun label(days: Int) = when {
        days >= 365 -> "1년"
        days >= 90 && days % 30 == 0 -> "${days / 30}개월"
        else -> "${days}일"
    }

    data class Usage(val bytes: Long, val noti: Int, val briefings: Int, val logs: Int, val tasks: Int)

    fun dbBytes(ctx: Context): Long {
        val f = ctx.getDatabasePath("jarvis.db")
        return listOf(f, java.io.File(f.path + "-wal"), java.io.File(f.path + "-journal")).sumOf { if (it.exists()) it.length() else 0L }
    }

    fun usage(ctx: Context): Usage {
        val c = NotificationDb.get(ctx).counts()
        return Usage(dbBytes(ctx), c["noti"] ?: 0, c["briefing"] ?: 0, c["student_log"] ?: 0, c["task"] ?: 0)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1fGB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024)
        else -> "${(bytes / 1024).coerceAtLeast(1)}KB"
    }

    /** 자동 정리: 앱을 열 때·브리핑이 끝날 때 불림. 6시간에 한 번만 지우고, 일주일에 한 번 파일 줄이기. */
    fun autoClean(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - p(ctx).getLong("last_clean", 0) < 6 * 3600_000L) return
        clean(ctx, vacuum = now - p(ctx).getLong("last_vacuum", 0) > 7 * 24 * 3600_000L)
    }

    /** 지금 정리: 보관 기간이 지난 기록을 지우고 (vacuum이면) 파일을 줄임. 비운 용량(바이트)을 돌려줌. */
    @Synchronized
    fun clean(ctx: Context, vacuum: Boolean = true): Long {
        val before = dbBytes(ctx)
        val db = NotificationDb.get(ctx)
        db.purge(notiDays(ctx), briefDays(ctx), logDays(ctx))
        val now = System.currentTimeMillis()
        if (vacuum) { runCatching { db.vacuum() }; p(ctx).edit().putLong("last_vacuum", now).apply() }
        p(ctx).edit().putLong("last_clean", now).apply()
        return (before - dbBytes(ctx)).coerceAtLeast(0)
    }

    /** 받은 알림 원문 모두 지우기 (브리핑·학생 기록·할 일은 남음) */
    @Synchronized
    fun clearNotis(ctx: Context): Long {
        val before = dbBytes(ctx)
        val db = NotificationDb.get(ctx)
        db.clearNotis()
        runCatching { db.vacuum() }
        return (before - dbBytes(ctx)).coerceAtLeast(0)
    }
}
