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
    /** 단톡방 이름. 1:1 대화나 일반 앱은 빈 값이라 제목(보낸 사람·제목)으로 대신함. */
    val room: String = "",
) {
    val roomOrTitle: String get() = room.ifEmpty { title }
}

/** 방 고르기 목록의 방 하나: 이름, 최근 7일 건수, 마지막 알림 시각(0이면 직접 추가한 방) */
data class KnownRoom(val name: String, val recent: Int, val last: Long)

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
    SQLiteOpenHelper(ctx, "jarvis.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE noti(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                title TEXT NOT NULL,
                text TEXT NOT NULL,
                time INTEGER NOT NULL,
                dedupe TEXT NOT NULL UNIQUE,
                room TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX idx_noti_time ON noti(time)")
        createBriefingTable(db)
        createTeacherTables(db)
        createSeenTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) createBriefingTable(db)
        // v3: 단톡방 이름 (방 고르기)
        if (oldV < 3) db.execSQL("ALTER TABLE noti ADD COLUMN room TEXT NOT NULL DEFAULT ''")
        // v4: 학생별 기록, 할 일·마감
        if (oldV < 4) createTeacherTables(db)
        // v5: 알림이 온 앱·방 기억 (뺀 앱·끈 방도 목록에서 사라지지 않게)
        if (oldV < 5) createSeenTable(db)
    }

    /**
     * v5: 알림을 보낸 앱(room='')과 메시지 앱의 방 이름만 기억 (내용은 저장 안 함, 180일 보관).
     * 뺀 앱·끈 방은 알림을 저장하지 않아 7일 뒤 목록에서 사라지던 문제를 막음.
     */
    private fun createSeenTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS seen(
                pkg TEXT NOT NULL,
                room TEXT NOT NULL,
                app TEXT NOT NULL,
                last INTEGER NOT NULL,
                PRIMARY KEY(pkg, room))"""
        )
    }

    /** 알림이 올 때마다 (뺀 앱·끈 방이어도) 앱·방 이름과 마지막 시각만 기록 */
    fun touchSeen(pkg: String, app: String, room: String, time: Long) {
        val sql = "INSERT INTO seen(pkg,room,app,last) VALUES(?,?,?,?) " +
            "ON CONFLICT(pkg,room) DO UPDATE SET app=excluded.app, last=MAX(last, excluded.last)"
        writableDatabase.execSQL(sql, arrayOf<Any>(pkg, "", app, time))
        if (room.isNotBlank()) writableDatabase.execSQL(sql, arrayOf<Any>(pkg, room.take(100), app, time))
    }

    /** 지금까지 알림을 보낸 적 있는 앱 (패키지, 이름, 마지막 시각) */
    fun knownApps(): List<Triple<String, String, Long>> =
        readableDatabase.rawQuery("SELECT pkg, app, last FROM seen WHERE room='' ORDER BY last DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) } }

    /** 방 고르기 목록: 지금까지 알림이 온 모든 방 (최근 7일 건수가 많은 순, 그다음 최근에 온 순) */
    fun knownRooms(pkg: String): List<KnownRoom> {
        val recent = roomStats(pkg, 7).toMap()
        val seen = readableDatabase.rawQuery("SELECT room, last FROM seen WHERE pkg=? AND room<>''", arrayOf(pkg))
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }
        return (recent.keys + seen.keys).distinct()
            .map { KnownRoom(it, recent[it] ?: 0, seen[it] ?: 0L) }
            .sortedWith(compareByDescending<KnownRoom> { it.recent }.thenByDescending { it.last })
    }

    /** v4: 학생별 기록(1년 보관)과 할 일·마감. 원래 알림(7일)이 지워져도 남음. */
    private fun createTeacherTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS student_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                student TEXT NOT NULL,
                time INTEGER NOT NULL,
                app TEXT NOT NULL,
                room TEXT NOT NULL,
                sender TEXT NOT NULL,
                text TEXT NOT NULL,
                UNIQUE(student, time, text))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS task(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                room TEXT NOT NULL,
                due INTEGER NOT NULL,
                has_time INTEGER NOT NULL,
                source_time INTEGER NOT NULL,
                done INTEGER NOT NULL DEFAULT 0,
                dedupe TEXT NOT NULL UNIQUE)"""
        )
    }

    /** 브리핑 모음: 최근 브리핑들 (새것부터, 7일 보관) */
    fun briefings(limit: Int = 30): List<Briefing> =
        readableDatabase.rawQuery(
            "SELECT time,from_time,label,count,text FROM briefing ORDER BY time DESC LIMIT $limit", null
        ).use { c -> buildList { while (c.moveToNext()) add(Briefing(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getString(4))) } }

    // ── 학생별 기록 ──
    fun logStudent(student: String, n: NotiItem) {
        val v = ContentValues().apply {
            put("student", student); put("time", n.time); put("app", n.app)
            put("room", n.roomOrTitle); put("sender", n.title); put("text", n.text.take(500))
        }
        writableDatabase.insertWithOnConflict("student_log", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun studentLog(student: String): List<StudentEntry> =
        readableDatabase.rawQuery(
            "SELECT time,app,room,sender,text FROM student_log WHERE student=? ORDER BY time DESC LIMIT 200", arrayOf(student)
        ).use { c -> buildList { while (c.moveToNext()) add(StudentEntry(student, c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))) } }
    fun studentCounts(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT student, COUNT(*) FROM student_log GROUP BY student", null)
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    // ── 할 일·마감 ──
    fun insertTask(t: Task) {
        val v = ContentValues().apply {
            put("title", t.title); put("room", t.room); put("due", t.due); put("has_time", if (t.hasTime) 1 else 0)
            put("source_time", t.sourceTime); put("done", if (t.done) 1 else 0); put("dedupe", t.dedupe)
        }
        writableDatabase.insertWithOnConflict("task", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }
    /** 안 끝낸 할 일 + 최근 3일 안에 끝낸 할 일 (마감 빠른 순) */
    fun tasks(): List<Task> {
        val recent = (System.currentTimeMillis() - 3 * 24L * 3600 * 1000).toString()
        return readableDatabase.rawQuery(
            "SELECT id,title,room,due,has_time,source_time,done,dedupe FROM task WHERE done=0 OR due>=? ORDER BY done ASC, due ASC LIMIT 100",
            arrayOf(recent)
        ).use { c -> buildList { while (c.moveToNext()) add(Task(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
            c.getInt(4) == 1, c.getLong(5), c.getInt(6) == 1, c.getString(7))) } }
    }
    fun setTaskDone(id: Long, done: Boolean) {
        writableDatabase.update("task", ContentValues().apply { put("done", if (done) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }
    fun deleteTask(id: Long) { writableDatabase.delete("task", "id=?", arrayOf(id.toString())) }

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
    fun insert(pkg: String, app: String, title: String, text: String, time: Long, room: String = "") {
        val v = ContentValues().apply {
            put("pkg", pkg); put("app", app); put("title", title)
            put("text", text); put("time", time); put("room", room)
            put("dedupe", "$pkg|$room|$title|$text")
        }
        writableDatabase.insertWithOnConflict("noti", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun since(fromMillis: Long): List<NotiItem> {
        val out = mutableListOf<NotiItem>()
        readableDatabase.rawQuery(
            "SELECT id,pkg,app,title,text,time,room FROM noti WHERE time>=? ORDER BY time ASC",
            arrayOf(fromMillis.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += NotiItem(c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getString(4), c.getLong(5), c.getString(6))
            }
        }
        return out
    }

    fun today(): List<NotiItem> = since(startOfToday())

    /** 최근 days일 동안 알림을 보낸 앱별 건수와 제목 예시 (많은 순). 알림 앱 정리 추천에 씀. */
    fun appStats(days: Int = 7): List<AppStat> {
        val from = (System.currentTimeMillis() - days * 24L * 3600 * 1000).toString()
        val out = mutableListOf<AppStat>()
        readableDatabase.rawQuery(
            "SELECT pkg, MAX(app), COUNT(*) FROM noti WHERE time>=? GROUP BY pkg ORDER BY 3 DESC", arrayOf(from)
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(0)
                val samples = readableDatabase.rawQuery(
                    "SELECT title FROM noti WHERE pkg=? AND time>=? AND title<>'' GROUP BY title ORDER BY MAX(time) DESC LIMIT 3",
                    arrayOf(pkg, from)
                ).use { s -> buildList { while (s.moveToNext()) add(s.getString(0)) } }
                out += AppStat(pkg, c.getString(1), c.getInt(2), samples)
            }
        }
        return out
    }

    /** 한 앱에서 최근 days일 동안 알림이 온 방(단톡방 이름, 없으면 보낸 사람·제목)과 건수 (많은 순). */
    fun roomStats(pkg: String, days: Int = 7): List<Pair<String, Int>> {
        val from = (System.currentTimeMillis() - days * 24L * 3600 * 1000).toString()
        return readableDatabase.rawQuery(
            "SELECT CASE WHEN room='' THEN title ELSE room END AS r, COUNT(*) FROM noti " +
            "WHERE pkg=? AND time>=? GROUP BY r ORDER BY 2 DESC LIMIT 60",
            arrayOf(pkg, from)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
    }

    /** 보관 기간이 지난 기록 지우기 (🧹 Storage가 설정값으로 부름) */
    fun purge(notiDays: Int, briefDays: Int, logDays: Int) {
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        val w = writableDatabase
        w.delete("noti", "time<?", arrayOf((now - notiDays * day).toString()))
        w.delete("briefing", "time<?", arrayOf((now - briefDays * day).toString()))
        w.delete("student_log", "time<?", arrayOf((now - logDays * day).toString()))
        w.delete("seen", "last<?", arrayOf((now - 180 * day).toString()))
        // 끝낸 할 일은 30일, 마감이 지난 지 60일 된 할 일은 끝내지 않았어도 지움
        w.delete("task", "done=1 AND due<?", arrayOf((now - 30 * day).toString()))
        w.delete("task", "due<?", arrayOf((now - 60 * day).toString()))
    }

    /** 표마다 몇 줄인지 (저장 공간 화면) */
    fun counts(): Map<String, Int> = listOf("noti", "briefing", "student_log", "task").associateWith { t ->
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $t", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 받은 알림 원문 모두 지우기 */
    fun clearNotis() { writableDatabase.delete("noti", null, null) }

    /** 지운 자리를 정리해 파일 크기를 실제로 줄임 */
    fun vacuum() { writableDatabase.execSQL("VACUUM") }

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
