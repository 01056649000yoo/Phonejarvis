package com.jarvis.phone

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 🌱 설치 첫날에도 알림 앱 정리 추천이 나오게 (2026-11-22 선생님: "처음 설치하면 알림 패턴을 보고 정리할 앱을 추천").
 * 안드로이드는 알림 접근을 켠 뒤에 온 알림만 넘겨주므로, 그 전에는 추천할 재료가 없었다. 두 가지로 메움:
 *  1) 알림창 훑기 — 알림 접근이 연결되는 순간 알림창에 남아 있는 알림을 앱별로 세어 둔다(앱·건수·제목 예시 3개, 7일 보관).
 *     요약 재료(noti 표)에는 넣지 않는다 — 이미 본 알림이 첫 요약에 섞이지 않게.
 *  2) 설치된 앱 — 처음 정리하기 전에는 폰에 설치된 앱 중 메시지·업무 앱과 알림을 보내는 앱을 목록에 미리 올려
 *     앱 이름으로 규칙 추천(쇼핑·게임·금융 등은 빼기)을 바로 보여 준다.
 */
object ShadeSeed {
    private const val KEEP_MS = 7 * 24L * 3600 * 1000
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shade_seed", Context.MODE_PRIVATE)

    data class Seen(val pkg: String, val app: String, val title: String)

    /** 알림창에서 읽은 알림들을 앱별 통계로 합침. 같은 앱은 건수를 더 큰 쪽으로(다시 연결될 때 두 번 세지 않게), 예시는 새것 먼저 3개 */
    fun merge(old: Map<String, AppStat>, seen: List<Seen>): Map<String, AppStat> {
        val now = seen.groupBy { it.pkg }.mapValues { (pkg, l) ->
            AppStat(pkg, l.first().app, l.size, l.map { it.title.take(40) }.filter { it.isNotBlank() }.distinct().take(3))
        }
        return (old.keys + now.keys).associateWith { pkg ->
            val o = old[pkg]; val n = now[pkg]
            when {
                o == null -> n!!
                n == null -> o
                else -> AppStat(pkg, n.app, maxOf(o.count, n.count), (n.samples + o.samples).distinct().take(3))
            }
        }
    }

    fun save(ctx: Context, seen: List<Seen>) {
        if (seen.isEmpty()) return
        val merged = merge(load(ctx), seen)
        val arr = JSONArray()
        merged.values.forEach { s ->
            arr.put(JSONObject().put("pkg", s.pkg).put("app", s.app).put("count", s.count).put("samples", JSONArray(s.samples)))
        }
        val first = prefs(ctx).getLong("at", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        prefs(ctx).edit().putString("list", arr.toString()).putLong("at", first).apply()
    }

    /** 저장해 둔 알림창 통계 (처음 훑은 지 7일이 지나면 버림 — 그때부턴 실제로 모은 알림으로 충분) */
    fun load(ctx: Context): Map<String, AppStat> {
        val p = prefs(ctx)
        val at = p.getLong("at", 0L)
        if (at == 0L) return emptyMap()
        if (System.currentTimeMillis() - at > KEEP_MS) { p.edit().clear().apply(); return emptyMap() }
        return runCatching {
            val arr = JSONArray(p.getString("list", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val sa = o.optJSONArray("samples") ?: JSONArray()
                AppStat(o.getString("pkg"), o.getString("app"), o.getInt("count"), (0 until sa.length()).map { sa.getString(it) })
            }.associateBy { it.pkg }
        }.getOrDefault(emptyMap())
    }

    /** 모은 알림 통계에 알림창 통계를 더함: 건수는 큰 쪽, 예시가 없으면 알림창 예시. 모은 적 없는 앱은 뒤에 붙임 */
    fun combine(recent: List<AppStat>, shade: Map<String, AppStat>): List<AppStat> {
        val have = recent.map { it.pkg }.toSet()
        return recent.map { r ->
            shade[r.pkg]?.let { s -> r.copy(count = maxOf(r.count, s.count), samples = r.samples.ifEmpty { s.samples }) } ?: r
        } + shade.values.filter { it.pkg !in have }.sortedByDescending { it.count }
    }
}

/** 폰에 설치된(실행할 수 있는) 앱. group: 0 메시지·SNS · 1 업무·일정 · 2 알림을 보내는 앱 · 3 그 밖의 앱 */
data class InstalledApp(val pkg: String, val name: String, val group: Int)

object InstalledApps {
    /** 기본 시스템 항목(내 파일·설정 등)은 빼고 설치한 앱만. 단, 기본 문자 같은 메시지·업무 앱은 남김. 느리므로 IO 에서 */
    fun list(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it != ctx.packageName && it !in NotificationCollectorService.IGNORED_PACKAGES }
            .mapNotNull { pkg ->
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                val name = pm.getApplicationLabel(info).toString()
                val msg = AppAdvisor.isMessaging(name, pkg)
                val work = AppAdvisor.isWork(name, pkg)
                val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                if (system && !msg && !work) return@mapNotNull null
                // 알림을 보내는 앱인지 (알림 권한을 요청하는 앱)
                val notifies = runCatching {
                    pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions?.contains("android.permission.POST_NOTIFICATIONS") == true
                }.getOrDefault(false)
                InstalledApp(pkg, name, when { msg -> 0; work -> 1; notifies -> 2; else -> 3 })
            }
            .sortedWith(compareBy<InstalledApp> { it.group }.thenBy { it.name })
    }
}
