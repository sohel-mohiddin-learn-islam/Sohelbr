package com.sohelbr.myapp

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net
import com.badlogic.gdx.net.HttpStatus
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Fill these two values after you create the game's Firebase project (see server/README.md).
 * While they are empty the game works fully offline and the admin features are simply switched off.
 */
object BackendConfig {
    const val PROJECT_ID = "sohelbr-73c46"
    const val API_KEY = "AIzaSyAatdMvRVTpLwbaWTYVnvvKtTt4Mo2udBU"
    const val DB = "(default)"
}

class RemotePrize(val name: String, val gold: Int, val dia: Int, val weight: Int, val start: Long, val end: Long, val active: Boolean, val itemId: String = "", val ring: String = "")
class RemoteEvent(val id: String, val title: String, val desc: String, val gold: Int, val dia: Int, val start: Long, val end: Long, val active: Boolean, val itemId: String = "")

class PassLevel(val fg: Int, val fd: Int, val fi: String, val pg: Int, val pd: Int, val pi: String)
class PassConfig(val title: String, val end: Long, val price: Int, val levels: List<PassLevel>)
class MissionCfg(val title: String, val kind: String, val goal: Int, val weekly: Boolean, val gold: Int, val dia: Int, val xp: Int, val itemId: String)

/** shop item made in the admin panel: kind = gunskin / bundle / other */
class RemoteItem(val id: String, val name: String, val kind: String, val price: Int, val dia: Boolean, val gun: String, val body: String, val acc: String,
                 val contains: List<String>, val gold: Int, val diaBonus: Int, val start: Long, val end: Long, val active: Boolean, val shop: Boolean)

/** Last ~45 seconds of play (positions, shots, hits, damage taken) - attached to a player report so the admin can replay it. */
class Evidence {
    private val st = ArrayList<Float>()
    private val samples = ArrayList<String>()
    private val et = ArrayList<Float>()
    private val events = ArrayList<String>()

    private fun f(v: Float) = String.format(java.util.Locale.US, "%.1f", v)

    fun clear() { st.clear(); samples.clear(); et.clear(); events.clear() }

    private fun trim(t: Float) {
        while (st.isNotEmpty() && st[0] < t - 45f) { st.removeAt(0); samples.removeAt(0) }
        while (et.isNotEmpty() && et[0] < t - 45f) { et.removeAt(0); events.removeAt(0) }
    }

    /** bots = x,z pairs of nearby enemies */
    fun sample(t: Float, x: Float, y: Float, z: Float, yaw: Float, bots: FloatArray, n: Int) {
        val sb = StringBuilder("[").append(f(t)).append(',').append(f(x)).append(',').append(f(y)).append(',').append(f(z)).append(',').append(f(yaw)).append(",[")
        for (i in 0 until n) {
            if (i > 0) sb.append(',')
            sb.append(f(bots[i * 2])).append(',').append(f(bots[i * 2 + 1]))
        }
        sb.append("]]")
        st.add(t); samples.add(sb.toString())
        trim(t)
    }

    /** kind: shot / hit / hurt / kill */
    fun event(t: Float, kind: String, a: Float, b: Float, c: Float) {
        et.add(t)
        events.add("[" + f(t) + ",\"" + kind + "\"," + f(a) + "," + f(b) + "," + f(c) + "]")
        trim(t)
    }

    fun json(): String = "{\"v\":1,\"samples\":[" + samples.joinToString(",") + "],\"events\":[" + events.joinToString(",") + "]}"
}

/**
 * Talks to Firebase (Firestore REST + anonymous sign-in) with no extra libraries.
 * Downloads: luck royale prizes, events, ban status.   Uploads: player reports with evidence.
 */
class Backend {
    val enabled: Boolean get() = BackendConfig.PROJECT_ID.isNotEmpty() && BackendConfig.API_KEY.isNotEmpty()
    @Volatile var luck: List<RemotePrize> = emptyList()
    @Volatile var events: List<RemoteEvent> = emptyList()
    @Volatile var store: List<RemoteItem> = emptyList()
    @Volatile var pass: PassConfig? = null
    @Volatile var missionCfg: List<MissionCfg> = emptyList()
    @Volatile var banned = false
    @Volatile var banReason = ""
    @Volatile var banUntil = 0L
    @Volatile private var token: String? = null

    private fun base() = "https://firestore.googleapis.com/v1/projects/${BackendConfig.PROJECT_ID}/databases/${BackendConfig.DB}/documents"

    private fun call(method: String, url: String, body: String?, auth: Boolean, done: (Int, String) -> Unit) {
        val rq = Net.HttpRequest(method)
        rq.url = url
        rq.setHeader("Content-Type", "application/json")
        if (auth && token != null) rq.setHeader("Authorization", "Bearer $token")
        if (body != null) rq.content = body
        rq.timeOut = 8000
        Gdx.net.sendHttpRequest(rq, object : Net.HttpResponseListener {
            override fun handleHttpResponse(r: Net.HttpResponse) { done(r.status.statusCode, r.resultAsString) }
            override fun failed(t: Throwable) { done(-1, "") }
            override fun cancelled() { done(-1, "") }
        })
    }

    private fun s(f: JsonValue, k: String): String = f.get(k)?.getString("stringValue", "") ?: ""
    private fun n(f: JsonValue, k: String): Long = (f.get(k)?.getString("integerValue", "0") ?: "0").toLongOrNull() ?: 0L
    private fun b(f: JsonValue, k: String): Boolean = f.get(k)?.getBoolean("booleanValue", false) ?: false

    /** call when the lobby opens: refreshes prizes / events / ban status */
    fun refresh(playerId: String) {
        if (!enabled) return
        call("GET", base() + "/luck_items?pageSize=100", null, false) { code, txt ->
            if (code == 200) try {
                val out = ArrayList<RemotePrize>()
                val docs = JsonReader().parse(txt).get("documents")
                if (docs != null) for (d in docs) {
                    val f = d.get("fields") ?: continue
                    out.add(RemotePrize(s(f, "name"), n(f, "gold").toInt(), n(f, "dia").toInt(), maxOf(1, n(f, "weight").toInt()), n(f, "start"), n(f, "end"), b(f, "active"), s(f, "itemId"), s(f, "ring")))
                }
                luck = out
            } catch (e: Throwable) { }
        }
        call("GET", base() + "/events?pageSize=100", null, false) { code, txt ->
            if (code == 200) try {
                val out = ArrayList<RemoteEvent>()
                val docs = JsonReader().parse(txt).get("documents")
                if (docs != null) for (d in docs) {
                    val f = d.get("fields") ?: continue
                    val id = d.getString("name", "").substringAfterLast('/')
                    out.add(RemoteEvent(id, s(f, "title"), s(f, "desc"), n(f, "gold").toInt(), n(f, "dia").toInt(), n(f, "start"), n(f, "end"), b(f, "active"), s(f, "itemId")))
                }
                events = out
            } catch (e: Throwable) { }
        }
        call("GET", base() + "/store_items?pageSize=200", null, false) { code, txt ->
            if (code == 200) try {
                val out = ArrayList<RemoteItem>()
                val docs = JsonReader().parse(txt).get("documents")
                if (docs != null) for (d in docs) {
                    val f = d.get("fields") ?: continue
                    val id = d.getString("name", "").substringAfterLast('/')
                    out.add(RemoteItem(id, s(f, "name"), s(f, "kind"), n(f, "price").toInt(), s(f, "currency") == "d", s(f, "gun"), s(f, "body"), s(f, "acc"),
                        s(f, "contains").split(",").filter { it.isNotBlank() }, n(f, "gold").toInt(), n(f, "diaBonus").toInt(), n(f, "start"), n(f, "end"), b(f, "active"), f.get("shop") == null || b(f, "shop")))
                }
                store = out
            } catch (e: Throwable) { }
        }
        call("GET", base() + "/config/pass", null, false) { code, txt ->
            if (code == 200) try {
                val f = JsonReader().parse(txt).get("fields")
                if (f != null) {
                    val lv = ArrayList<PassLevel>()
                    val arr = JsonReader().parse(s(f, "levels"))
                    for (c in arr) lv.add(PassLevel(c.getInt("fg", 0), c.getInt("fd", 0), c.getString("fi", ""), c.getInt("pg", 0), c.getInt("pd", 0), c.getString("pi", "")))
                    if (lv.isNotEmpty()) pass = PassConfig(s(f, "title").ifEmpty { "SOHEL PASS" }, n(f, "end"), n(f, "price").toInt().let { if (it <= 0) 150 else it }, lv.take(30))
                }
            } catch (e: Throwable) { }
        }
        call("GET", base() + "/config/missions", null, false) { code, txt ->
            if (code == 200) try {
                val f = JsonReader().parse(txt).get("fields")
                if (f != null) {
                    val out = ArrayList<MissionCfg>()
                    for (c in JsonReader().parse(s(f, "list"))) out.add(MissionCfg(c.getString("t", "Mission"), c.getString("k", "matches"), maxOf(1, c.getInt("n", 1)), c.getString("p", "daily") == "weekly", c.getInt("g", 0), c.getInt("d", 0), c.getInt("x", 40), c.getString("i", "")))
                    missionCfg = out
                }
            } catch (e: Throwable) { }
        }
        call("GET", base() + "/bans/" + playerId, null, false) { code, txt ->
            if (code == 200) try {
                val f = JsonReader().parse(txt).get("fields")
                if (f != null && b(f, "active")) { banReason = s(f, "reason"); banUntil = n(f, "until"); banned = true } else banned = false
            } catch (e: Throwable) { }
            else if (code == 404) banned = false
        }
    }

    /** true while the ban is in force */
    fun banActive(): Boolean = banned && (banUntil == 0L || System.currentTimeMillis() < banUntil)

    private fun esc(x: String) = x.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    /** sends a report; onResult(true/false) is called from a background thread */
    fun submitReport(reporter: String, reported: String, reason: String, match: String, evidence: String, onResult: (Boolean) -> Unit) {
        if (!enabled) { onResult(false); return }
        val send = {
            val body = "{\"fields\":{" +
                "\"reporter\":{\"stringValue\":\"${esc(reporter)}\"}," +
                "\"reported\":{\"stringValue\":\"${esc(reported)}\"}," +
                "\"reason\":{\"stringValue\":\"${esc(reason)}\"}," +
                "\"match\":{\"stringValue\":\"${esc(match)}\"}," +
                "\"at\":{\"integerValue\":\"${System.currentTimeMillis()}\"}," +
                "\"status\":{\"stringValue\":\"open\"}," +
                "\"evidence\":{\"stringValue\":\"${esc(evidence)}\"}}}"
            call("POST", base() + "/reports", body, true) { code, _ -> onResult(code == 200) }
        }
        if (token != null) { send(); return }
        call("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${BackendConfig.API_KEY}", "{\"returnSecureToken\":true}", false) { code, txt ->
            if (code == 200) {
                try { token = JsonReader().parse(txt).getString("idToken", "") } catch (e: Throwable) { }
                if (!token.isNullOrEmpty()) send() else onResult(false)
            } else onResult(false)
        }
    }
}
