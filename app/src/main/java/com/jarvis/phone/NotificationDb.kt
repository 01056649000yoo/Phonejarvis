package com.jarvis.phone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class NotiItem(
    val id: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
)

/** 한 번의 브리핑 결과. fromTime~time 사이 알림 count건을 요약한 것. */
data class Briefing(
    val time: Long,
    val fromTime: Long,
    val label: String,
    val count: Int,
    val text: String,
)

/** 수집한 알림을 휴대폰 안에만 저장하는 SQLite DB (7일 보관). */
class NotificationDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "jarvis.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE noti(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                title TEXT NOT NULL,
                text TEXT NOT NULL,
                time INTEGER NOT NULL,
                dedupe TEXT NOT NULL UNIQUE)"""
        )
        db.execSQL("CREATE INDEX idx_noti_time ON noti(time)")
        createBriefingTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) createBriefingTable(db)
    }

    /** v2: 자동·수동 브리핑 결과 기록. */
    private fun createBriefingTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS briefing(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                from_time INTEGER NOT NULL,
                label TEXT NOT NULL,
                count INTEGER NOT NULL,
                text TEXT NOT NULL)"""
        )
    }

    fun insertBriefing(b: Briefing) {
        val v = ContentValues().apply {
            put("time", b.time); put("from_time", b.fromTime); put("label", b.label)
            put("count", b.count); put("text", b.text)
        }
        writableDatabase.insert("briefing", null, v)
    }

    fun lastBriefing(): Briefing? =
        readableDatabase.rawQuery(
            "SELECT time,from_time,label,count,text FROM briefing ORDER BY time DESC LIMIT 1", null
        ).use { c ->
            if (c.moveToFirst()) Briefing(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getString(4))
            else null
        }

    /** 같은 알림이 업데이트되며 여러 번 들어오는 경우(카톡 등)를 막기 위해 dedupe 키 사용. */
    fun insert(pkg: String, app: String, title: String, text: String, time: Long) {
        val v = ContentValues().apply {
            put("pkg", pkg); put("app", app); put("title", title)
            put("text", text); put("time", time)
            put("dedupe", "$pkg|$title|$text")
        }
        writableDatabase.insertWithOnConflict("noti", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun since(fromMillis: Long): List<NotiItem> {
        val out = mutableListOf<NotiItem>()
        readableDatabase.rawQuery(
            "SELECT id,pkg,app,title,text,time FROM noti WHERE time>=? ORDER BY time ASC",
            arrayOf(fromMillis.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += NotiItem(c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getString(4), c.getLong(5))
            }
        }
        return out
    }

    fun today(): List<NotiItem> = since(startOfToday())

    fun purgeOld(days: Int = 7) {
        val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
        writableDatabase.delete("noti", "time<?", arrayOf(cutoff.toString()))
        writableDatabase.delete("briefing", "time<?", arrayOf(cutoff.toString()))
    }

    companion object {
        @Volatile private var inst: NotificationDb? = null
        fun get(ctx: Context): NotificationDb =
            inst ?: synchronized(this) {
                inst ?: NotificationDb(ctx.applicationContext).also { inst = it }
            }

        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
