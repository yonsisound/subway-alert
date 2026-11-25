package com.hayoon.subwayalert.data

import android.content.Context
import com.hayoon.subwayalert.route.Leg
import com.hayoon.subwayalert.route.Network
import com.hayoon.subwayalert.route.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object DataRepository {
    const val UPDATE_URL = "https://raw.githubusercontent.com/yonsisound/subway-alert/main/data/subway_data.json"
    private const val FILE = "subway_data.json"

    @Volatile private var cached: Network? = null

    fun network(ctx: Context): Network {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val asset = ctx.assets.open(FILE).bufferedReader().use { it.readText() }
            val assetNet = Network.parse(asset)
            val f = File(ctx.filesDir, FILE)
            val net = if (f.exists()) {
                runCatching { Network.parse(f.readText()) }.getOrNull()
                    ?.takeIf { it.version > assetNet.version } ?: assetNet
            } else assetNet
            cached = net
            return net
        }
    }

    sealed class UpdateResult {
        data class Updated(val version: Long, val stations: Int) : UpdateResult()
        data class UpToDate(val version: Long) : UpdateResult()
        data class Failed(val reason: String) : UpdateResult()
    }

    suspend fun update(ctx: Context): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(UPDATE_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 20000; useCaches = false
            }
            if (conn.responseCode != 200) return@withContext UpdateResult.Failed("서버 응답 ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val net = Network.parse(text)
            if (net.stations.size < 100) return@withContext UpdateResult.Failed("데이터가 이상해요 (역 ${net.stations.size}개)")
            val cur = network(ctx)
            if (net.version <= cur.version) return@withContext UpdateResult.UpToDate(cur.version)
            File(ctx.filesDir, FILE).writeText(text)
            cached = net
            UpdateResult.Updated(net.version, net.stations.size)
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- 경로 저장/복원 (탑승 중 앱이 재시작돼도 이어가기) ----
    fun routeToJson(r: Route): String = JSONObject().apply {
        put("legs", JSONArray().apply {
            r.legs.forEach { l ->
                put(JSONObject().put("line", l.line).put("st", JSONArray(l.stations)).put("sec", JSONArray(l.hopSecs)))
            }
        })
        put("tr", JSONArray(r.transferSecs))
    }.toString()

    fun routeFromJson(s: String): Route {
        val o = JSONObject(s)
        val la = o.getJSONArray("legs")
        val legs = (0 until la.length()).map { k ->
            val l = la.getJSONObject(k)
            val st = l.getJSONArray("st"); val sec = l.getJSONArray("sec")
            Leg(l.getString("line"), (0 until st.length()).map { st.getInt(it) }, (0 until sec.length()).map { sec.getInt(it) })
        }
        val tr = o.getJSONArray("tr")
        return Route(legs, (0 until tr.length()).map { tr.getInt(it) })
    }

    // ---- 최근 검색 ----
    fun recent(ctx: Context): List<Pair<Int, Int>> {
        val s = ctx.getSharedPreferences("app", 0).getString("recent", "") ?: ""
        return s.split(";").mapNotNull { p ->
            val a = p.split(","); if (a.size == 2) a[0].toIntOrNull()?.let { x -> a[1].toIntOrNull()?.let { y -> x to y } } else null
        }
    }

    fun addRecent(ctx: Context, from: Int, to: Int) {
        val list = (listOf(from to to) + recent(ctx).filter { it != from to to }).take(5)
        ctx.getSharedPreferences("app", 0).edit().putString("recent", list.joinToString(";") { "${it.first},${it.second}" }).apply()
    }
}
