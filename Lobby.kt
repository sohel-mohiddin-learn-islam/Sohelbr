package com.sohelbr.myapp

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.sin

/** status: 0 offline, 1 online, 2 in match */
class Friend(val id: String, var name: String, var status: Int)
class Msg(val time: String, val text: String, var read: Boolean)
class Mission(val title: String, val goal: Int, val progress: Int, val gold: Int, val dia: Int, val xp: Int, val claimed: Boolean)
/** kind: 0 gold, 1 diamonds, 2 item */
class PassRow(val level: Int, val free: String, val prem: String, val freeKind: Int, val premKind: Int, val unlocked: Boolean, val freeDone: Boolean, val premDone: Boolean)
class PassInfo(val title: String, val endMs: Long, val price: Int, val img: String = "")
class GameEvent(val id: String, val title: String, val desc: String, val reward: String, val claimed: Boolean, val img: String = "")
class GunSkin(val gun: String, val body: Int, val acc: Int)
class StoreItem(val id: String, val name: String, val price: Int, val dia: Boolean, val owned: Boolean, val equipped: Boolean,
                val kind: String = "other", val gun: String = "", val body: Int = 0x5a6b4aff, val acc: Int = 0x2a2a2aff, val info: String = "", val img: String = "")
class PrizeInfo(val name: String, val ring: String, val gold: Int, val dia: Int, val weight: Int, val endMs: Long, val item: Boolean, val img: String = "", val ringImg: String = "")

/**
 * Everything the lobby needs from "the outside world". Today it is answered by [LocalLobbyService]
 * (saved on the phone, no network). When the server exists, write a second class that implements this
 * interface and give it to Lobby instead - the lobby screens do not change.
 * Every method that changes money / rewards returns a message to show the player.
 */
interface LobbyService {
    val connected: Boolean
    fun myName(): String
    fun myId(): String
    fun setMyName(n: String)
    fun gold(): Int
    fun diamonds(): Int
    fun level(): Int
    fun levelProgress(): Float
    fun friends(): List<Friend>
    fun addFriend(idOrName: String): String
    fun removeFriend(id: String)
    fun invite(id: String): String
    fun messages(): List<Msg>
    fun unread(): Int
    fun markAllRead()
    // missions (daily / weekly)
    fun missions(weekly: Boolean): List<Mission>
    fun claimMission(weekly: Boolean, i: Int): String
    fun missionsReady(): Boolean
    // sohel pass
    fun passInfo(): PassInfo
    fun passLevel(): Int
    fun passXp(): Int
    fun passProgress(): Float
    fun passPremium(): Boolean
    fun passRows(): List<PassRow>
    fun claimPass(level: Int, premium: Boolean): String
    fun buyPremium(): String
    // store
    fun storeItems(): List<StoreItem>
    fun buy(id: String): String
    fun equip(id: String): String
    fun equippedGunSkins(): List<GunSkin>
    // luck royale
    fun prizes(): List<PrizeInfo>
    fun spinIsFree(): Boolean
    fun spin(ring: String, times: Int): String
    fun lastWon(): List<String>
    // events
    fun events(): List<GameEvent>
    fun claimEvent(id: String): String
    fun loginReady(): Boolean
    fun claimLogin(): String
    fun banned(): String?
    fun recordMatch(kills: Int, won: Boolean): String
}

/** Standalone (offline) implementation: everything is saved in the phone's preferences. */
class LocalLobbyService(private val bk: Backend) : LobbyService {
    private val prefs = Gdx.app.getPreferences("sohelbr_lobby")
    private val list = ArrayList<Friend>()
    private val msgs = ArrayList<Msg>()
    private var won = ArrayList<String>()
    override val connected = false

    private val items = arrayOf(
        arrayOf("camo", "Jungle Camo", "0", "g"), arrayOf("desert", "Desert Camo", "500", "g"),
        arrayOf("snow", "Snow Ghost", "800", "g"), arrayOf("red", "Red Jumpsuit", "300", "d"),
        arrayOf("goldak", "Golden Gun Skin", "800", "d"), arrayOf("chute", "Sunset Parachute", "400", "g"),
        arrayOf("emote", "Salute Emote", "200", "g"), arrayOf("pack", "Elite Backpack Skin", "150", "d")
    )
    private val itemColor = mapOf("camo" to 0x4a6b3aff.toInt(), "desert" to 0xb8945aff.toInt(), "snow" to 0xcfd8e0ff.toInt(), "red" to 0xc0262dff.toInt(),
        "goldak" to 0xe0a800ff.toInt(), "chute" to 0xff7a2fff.toInt(), "emote" to 0x7a4fd6ff.toInt(), "pack" to 0x2f6fb5ff.toInt())

    private val defaultPrizes = listOf(
        RemotePrize("50 Gold", 50, 0, 30, 0, 0, true), RemotePrize("100 Gold", 100, 0, 25, 0, 0, true),
        RemotePrize("250 Gold", 250, 0, 15, 0, 0, true), RemotePrize("500 Gold", 500, 0, 6, 0, 0, true),
        RemotePrize("5 Diamonds", 0, 5, 14, 0, 0, true), RemotePrize("20 Diamonds", 0, 20, 7, 0, 0, true),
        RemotePrize("100 Diamonds", 0, 100, 3, 0, 0, true)
    )

    init {
        if (!prefs.contains("id")) {
            prefs.putString("id", "SB" + (100000 + java.util.Random().nextInt(900000)))
            prefs.putString("name", "Player")
            prefs.putInteger("gold", 300)
            prefs.putInteger("dia", 100)
            prefs.putString("owned", "camo")
            prefs.putString("equipped", "camo")
            prefs.flush()
            addMsg("Welcome to Sohelbr! You got 300 gold and 100 diamonds to start. Check MISSIONS for daily rewards.")
        } else loadMsgs()
        val raw = prefs.getString("friends", "")
        for (e in raw.split(";")) {
            val p = e.split(",")
            if (p.size == 2 && p[0].isNotEmpty()) list.add(Friend(p[0], p[1], 0))
        }
        ensurePeriods()
    }

    private fun gi(k: String) = prefs.getInteger(k, 0)
    private fun pi(k: String, v: Int) { prefs.putInteger(k, v); prefs.flush() }
    private fun now() = System.currentTimeMillis()
    private fun today(): Int = ((now() + java.util.TimeZone.getDefault().getOffset(now())) / 86400000L).toInt()
    private fun week(): Int = (today() + 3) / 7

    private fun ensurePeriods() {
        if (prefs.getInteger("day", -1) != today()) {
            prefs.putInteger("day", today())
            for (k in arrayOf("dm", "dk", "dw")) prefs.putInteger(k, 0)
            prefs.putString("dClaim", "")
        }
        if (prefs.getInteger("wk", -1) != week()) {
            prefs.putInteger("wk", week())
            for (k in arrayOf("wm", "wk2", "ww")) prefs.putInteger(k, 0)
            prefs.putString("wClaim", "")
        }
        prefs.flush()
    }

    private fun saveMsgs() {
        prefs.putString("msgs", msgs.take(30).joinToString("\u0001") { it.time + "\u0002" + it.text + "\u0002" + (if (it.read) "1" else "0") })
        prefs.flush()
    }
    private fun loadMsgs() {
        for (e in prefs.getString("msgs", "").split("\u0001")) {
            val p = e.split("\u0002")
            if (p.size == 3) msgs.add(Msg(p[0], p[1], p[2] == "1"))
        }
    }
    private fun addMsg(text: String) {
        msgs.add(0, Msg(java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date()), text, false))
        saveMsgs()
    }

    private fun addGold(g: Int) { if (g != 0) pi("gold", gi("gold") + g) }
    private fun addDia(d: Int) { if (d != 0) pi("dia", gi("dia") + d) }
    private fun addXp(x: Int) { prefs.putInteger("xp", gi("xp") + x); prefs.putInteger("pxp", gi("pxp") + x); prefs.flush() }
    private fun clean(s: String) = s.replace(",", " ").replace(";", " ").trim().take(14)
    private fun ownedList() = prefs.getString("owned", "camo").split(",")
    private fun grant(id: String) {
        if (id.isBlank() || id in ownedList()) return
        prefs.putString("owned", prefs.getString("owned", "camo") + "," + id)
        prefs.flush()
    }
    private fun itemName(id: String): String = bk.store.firstOrNull { it.id == id }?.name ?: items.firstOrNull { it[0] == id }?.get(1) ?: "Item"

    override fun myName(): String = prefs.getString("name", "Player")
    override fun myId(): String = prefs.getString("id", "SB000000")
    override fun setMyName(n: String) {
        val c = clean(n)
        if (c.isNotEmpty()) { prefs.putString("name", c); prefs.flush() }
    }
    override fun gold() = gi("gold")
    override fun diamonds() = gi("dia")
    override fun level() = 1 + gi("xp") / 300
    override fun levelProgress() = (gi("xp") % 300) / 300f

    override fun friends(): List<Friend> = list
    override fun addFriend(idOrName: String): String {
        val c = clean(idOrName)
        if (c.isEmpty()) return "Type a friend's ID or name"
        val id = if (c.uppercase().startsWith("SB") && c.length >= 5) c.uppercase().replace(" ", "") else "N-" + c.lowercase().replace(" ", "")
        if (id == myId()) return "That's your own ID"
        if (list.any { it.id == id }) return "$c is already in your list"
        if (list.size >= 50) return "Friend list is full"
        list.add(Friend(id, c, 0))
        prefs.putString("friends", list.joinToString(";") { it.id + "," + it.name })
        prefs.flush()
        return "$c added"
    }
    override fun removeFriend(id: String) {
        list.removeAll { it.id == id }
        prefs.putString("friends", list.joinToString(";") { it.id + "," + it.name })
        prefs.flush()
    }
    override fun invite(id: String): String = "Online play is not connected yet - invites will work when the server is ready"

    override fun messages(): List<Msg> = msgs
    override fun unread(): Int = msgs.count { !it.read }
    override fun markAllRead() { msgs.forEach { it.read = true }; saveMsgs() }

    // ---- missions ----
    private fun cfgMissions(weekly: Boolean): List<MissionCfg> {
        val l = bk.missionCfg.filter { it.weekly == weekly }
        if (l.isNotEmpty()) return l
        return if (!weekly) listOf(
            MissionCfg("Play 1 match", "matches", 1, false, 50, 0, 40, ""), MissionCfg("Get 3 kills", "kills", 3, false, 80, 0, 40, ""),
            MissionCfg("Play 3 matches", "matches", 3, false, 0, 10, 40, ""), MissionCfg("Win a match", "wins", 1, false, 0, 25, 40, ""))
        else listOf(
            MissionCfg("Play 15 matches", "matches", 15, true, 300, 0, 100, ""), MissionCfg("Get 25 kills", "kills", 25, true, 500, 0, 100, ""),
            MissionCfg("Win 3 matches", "wins", 3, true, 0, 50, 100, ""))
    }
    private fun counter(kind: String, weekly: Boolean): Int {
        val p = if (weekly) "w" else "d"
        return when (kind) { "kills" -> gi(if (weekly) "wk2" else "dk"); "wins" -> gi(p + "w"); else -> gi(p + "m") }
    }
    private fun claimedSet(weekly: Boolean) = prefs.getString(if (weekly) "wClaim" else "dClaim", "").split(",")
    override fun missions(weekly: Boolean): List<Mission> {
        ensurePeriods()
        val cs = claimedSet(weekly)
        return cfgMissions(weekly).mapIndexed { i, m -> Mission(m.title, m.goal, minOf(m.goal, counter(m.kind, weekly)), m.gold, m.dia, m.xp, i.toString() in cs) }
    }
    override fun missionsReady(): Boolean = missions(false).any { it.progress >= it.goal && !it.claimed } || missions(true).any { it.progress >= it.goal && !it.claimed }
    override fun claimMission(weekly: Boolean, i: Int): String {
        ensurePeriods()
        val cfg = cfgMissions(weekly)
        if (i !in cfg.indices) return ""
        val m = cfg[i]
        if (i.toString() in claimedSet(weekly)) return "Already claimed"
        if (counter(m.kind, weekly) < m.goal) return "Not finished yet"
        val key = if (weekly) "wClaim" else "dClaim"
        prefs.putString(key, prefs.getString(key, "") + "," + i)
        prefs.flush()
        addGold(m.gold); addDia(m.dia); addXp(m.xp); grant(m.itemId)
        return "Mission reward claimed (+${m.xp} XP)"
    }

    // ---- Sohel Pass ----
    private fun levels(): List<PassLevel> = bk.pass?.levels ?: (1..20).map { l ->
        PassLevel(if (l % 5 == 0) 0 else 50 + 10 * l, if (l % 5 == 0) 10 else 0, "", 0, if (l % 5 == 0) 40 else 5, "")
    }
    override fun passInfo(): PassInfo { val p = bk.pass; return PassInfo(p?.title ?: "SOHEL PASS", p?.end ?: 0L, p?.price ?: 150, p?.img ?: "") }
    override fun passLevel() = minOf(levels().size, gi("pxp") / 100)
    override fun passXp() = gi("pxp") % 100
    override fun passProgress() = if (passLevel() >= levels().size) 1f else (gi("pxp") % 100) / 100f
    override fun passPremium() = gi("premium") == 1
    private fun rewardLabel(g: Int, d: Int, item: String): String {
        val p = ArrayList<String>()
        if (g > 0) p.add("${g}G")
        if (d > 0) p.add("${d}D")
        if (item.isNotEmpty()) p.add(itemName(item).take(10))
        return p.joinToString("+")
    }
    private fun kindOf(g: Int, d: Int, item: String) = if (item.isNotEmpty()) 2 else if (d > 0 && g == 0) 1 else 0
    override fun passRows(): List<PassRow> = levels().mapIndexed { idx, lv ->
        val l = idx + 1
        PassRow(l, rewardLabel(lv.fg, lv.fd, lv.fi), rewardLabel(lv.pg, lv.pd, lv.pi), kindOf(lv.fg, lv.fd, lv.fi), kindOf(lv.pg, lv.pd, lv.pi),
            l <= passLevel(), ((gi("pFree") shr l) and 1) == 1, ((gi("pPrem") shr l) and 1) == 1)
    }
    override fun claimPass(level: Int, premium: Boolean): String {
        val lv = levels()
        if (level !in 1..lv.size) return ""
        if (level > passLevel()) return "Reach pass level $level first"
        val key = if (premium) "pPrem" else "pFree"
        if (((gi(key) shr level) and 1) == 1) return "Already claimed"
        if (premium && !passPremium()) return "Unlock the premium pass first"
        pi(key, gi(key) or (1 shl level))
        val r = lv[level - 1]
        if (premium) { addGold(r.pg); addDia(r.pd); grant(r.pi) } else { addGold(r.fg); addDia(r.fd); grant(r.fi) }
        return "Pass level $level reward claimed"
    }
    override fun buyPremium(): String {
        if (passPremium()) return "Premium already unlocked"
        val price = passInfo().price
        if (gi("dia") < price) return "You need $price diamonds"
        addDia(-price); pi("premium", 1)
        return "Premium pass unlocked!"
    }

    // ---- Store ----
    private fun liveStore(): List<RemoteItem> =
        bk.store.filter { it.shop && it.active && now() >= it.start && (it.end == 0L || now() <= it.end) }
    private fun colorInt(hex: String, def: Int): Int = try { Color.rgba8888(Color.valueOf(hex)) } catch (e: Throwable) { def }
    override fun storeItems(): List<StoreItem> {
        val owned = ownedList()
        val eq = prefs.getString("equipped", "camo").split(",")
        val out = ArrayList<StoreItem>()
        for (it in items) out.add(StoreItem(it[0], it[1], it[2].toInt(), it[3] == "d", it[0] in owned, it[0] in eq, "other", "", itemColor[it[0]] ?: 0x5a6b4aff, 0x222222ff, "Cosmetic outfit / item"))
        for (r in liveStore()) {
            val info = when (r.kind) {
                "bundle" -> "Includes: " + (r.contains.map { itemName(it) } + listOf(if (r.gold > 0) "${r.gold} gold" else "", if (r.diaBonus > 0) "${r.diaBonus} diamonds" else "")).filter { it.isNotEmpty() }.joinToString(", ")
                "gunskin" -> "Recolours the ${r.gun}"
                else -> "Cosmetic item"
            }
            out.add(StoreItem(r.id, r.name, r.price, r.dia, r.id in owned, r.id in eq, r.kind, r.gun, colorInt(r.body, 0xc0392bff.toInt()), colorInt(r.acc, 0xf1c40fff.toInt()), info, r.img))
        }
        return out
    }
    override fun buy(id: String): String {
        val it = storeItems().firstOrNull { x -> x.id == id } ?: return ""
        if (it.owned) return "You already own ${it.name}"
        if (it.dia) { if (gi("dia") < it.price) return "Not enough diamonds"; addDia(-it.price) }
        else { if (gi("gold") < it.price) return "Not enough gold"; addGold(-it.price) }
        grant(id)
        val r = bk.store.firstOrNull { x -> x.id == id }
        if (r != null && r.kind == "bundle") {
            for (c in r.contains) grant(c)
            addGold(r.gold); addDia(r.diaBonus)
            return "${r.name} bundle bought - items added to your collection"
        }
        return "${it.name} bought"
    }
    override fun equip(id: String): String {
        val eq = prefs.getString("equipped", "camo").split(",").toMutableList()
        if (id in eq) eq.remove(id)
        else {
            eq.add(id)
            val r = bk.store.firstOrNull { x -> x.id == id }
            if (r != null && r.kind == "gunskin") eq.removeAll { o -> o != id && bk.store.any { y -> y.id == o && y.kind == "gunskin" && y.gun == r.gun } }
        }
        prefs.putString("equipped", eq.joinToString(","))
        prefs.flush()
        return if (id in eq) "Equipped" else "Unequipped"
    }
    override fun equippedGunSkins(): List<GunSkin> {
        val eq = prefs.getString("equipped", "").split(",")
        val owned = ownedList()
        return bk.store.filter { it.kind == "gunskin" && it.id in eq && it.id in owned && it.gun.isNotEmpty() }.mapNotNull { r ->
            try { GunSkin(r.gun, Color.rgba8888(Color.valueOf(r.body)), Color.rgba8888(Color.valueOf(r.acc))) } catch (e: Throwable) { null }
        }
    }

    // ---- Luck Royale ----
    private fun livePrizes(): List<RemotePrize> {
        val l = bk.luck.filter { it.active && now() >= it.start && (it.end == 0L || now() <= it.end) && (it.gold > 0 || it.dia > 0 || it.itemId.isNotEmpty()) }
        return if (l.isEmpty()) defaultPrizes else l
    }
    private fun ringOf(p: RemotePrize) = if (p.ring.isBlank()) "Lucky Ring" else p.ring
    override fun prizes(): List<PrizeInfo> = livePrizes().map { PrizeInfo(it.name, ringOf(it), it.gold, it.dia, it.weight, it.end, it.itemId.isNotEmpty(), it.img, it.ringImg) }
    override fun spinIsFree() = gi("lastSpin") != today() + 1
    override fun lastWon(): List<String> = won
    override fun spin(ring: String, times: Int): String {
        val all = livePrizes()
        val pool = all.filter { ringOf(it) == ring }.ifEmpty { all }
        val n = if (times >= 5) 5 else 1
        if (n == 1) {
            if (spinIsFree()) pi("lastSpin", today() + 1)
            else { if (gi("dia") < 20) return "Not enough diamonds (20 per spin)"; addDia(-20) }
        } else {
            if (gi("dia") < 90) return "Not enough diamonds (90 for 5 spins)"
            addDia(-90)
        }
        won = ArrayList()
        val rnd = java.util.Random()
        for (s in 0 until n) {
            var r = rnd.nextInt(pool.sumOf { it.weight })
            var k = 0
            while (r >= pool[k].weight) { r -= pool[k].weight; k++ }
            addGold(pool[k].gold); addDia(pool[k].dia); grant(pool[k].itemId)
            won.add(pool[k].name)
        }
        return if (n == 1) "You won " + won[0] + "!" else "You won: " + won.joinToString(", ")
    }

    // ---- Events ----
    private fun liveEvents(): List<RemoteEvent> = bk.events.filter { it.active && now() >= it.start && (it.end == 0L || now() <= it.end) }
    override fun events(): List<GameEvent> {
        val done = prefs.getString("evDone", "").split(",")
        return liveEvents().map { e ->
            val rw = (if (e.gold > 0) "${e.gold} Gold " else "") + (if (e.dia > 0) "${e.dia} Diamonds" else "")
            GameEvent(e.id, e.title, e.desc, rw.trim(), e.id in done, e.img)
        }
    }
    override fun claimEvent(id: String): String {
        val e = liveEvents().firstOrNull { it.id == id } ?: return "This event has ended"
        val done = prefs.getString("evDone", "").split(",")
        if (id in done) return "Already claimed"
        prefs.putString("evDone", prefs.getString("evDone", "") + "," + id)
        prefs.flush()
        addGold(e.gold); addDia(e.dia); grant(e.itemId)
        return "Event reward claimed: ${e.title}"
    }
    override fun banned(): String? {
        if (!bk.banActive()) return null
        val u = if (bk.banUntil == 0L) "permanent" else "until " + java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).format(java.util.Date(bk.banUntil))
        return (if (bk.banReason.isEmpty()) "Rule violation" else bk.banReason) + " ($u)"
    }
    override fun loginReady() = gi("lastLogin") != today() + 1
    override fun claimLogin(): String {
        if (!loginReady()) return "Come back tomorrow"
        pi("lastLogin", today() + 1)
        addGold(100); addXp(20)
        return "Daily login: 100 Gold + 20 XP"
    }

    override fun recordMatch(kills: Int, won: Boolean): String {
        ensurePeriods()
        for (p in arrayOf("d", "w")) {
            pi(p + "m", gi(p + "m") + 1)
            pi(if (p == "d") "dk" else "wk2", gi(if (p == "d") "dk" else "wk2") + kills)
            if (won) pi(p + "w", gi(p + "w") + 1)
        }
        val g = 20 + kills * 10 + (if (won) 100 else 0)
        val x = 30 + kills * 10 + (if (won) 100 else 0)
        addGold(g); addXp(x)
        val s = "Match reward: +$g Gold, +$x XP"
        addMsg(s)
        return s
    }
}

/** The main menu (Free Fire style): character, wallet, menus, friends, modes, START, and the full-screen Store / Luck Royale / Pass / Missions pages. */
class Lobby(private val font: BitmapFont, private val sb: SpriteBatch, private val shapes: ShapeRenderer, val svc: LobbyService, private val images: ImageLoader) {
    private class Box(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float, val c: Color, val hex: Boolean = false)
    private class Img(val url: String, val x: Float, val y: Float, val w: Float, val h: Float)
    private val imgs = ArrayList<Img>()
    private val bgImgs = ArrayList<Img>()
    private class Txt(val s: String, val x: Float, val y: Float, val scale: Float, val c: Color, val bw: Float, val bh: Float)

    private val layout = GlyphLayout()
    private val boxes = ArrayList<Box>()
    private val texts = ArrayList<Txt>()
    private var toast = ""
    private var toastT = 0f
    private var scroll = 0
    private var fscroll = 0
    private var screen = 0      // 0 lobby, 1 store, 2 luck royale, 3 sohel pass, 4 missions, 5 events, 6 messages
    private var mode = 0
    private var t = 0f
    private var sTab = 0        // store tab
    private var sel = ""        // selected store item id
    private var mTab = 0        // 0 daily, 1 weekly
    private var ringSel = 0
    private var spinT = 0f
    private var spinNames: List<String> = emptyList()
    private var spinMsg = ""

    @Volatile private var pendingName: String? = null
    @Volatile private var pendingFriend: String? = null

    private val white = Color(1f, 1f, 1f, 1f)
    private val dim = Color(0.66f, 0.7f, 0.78f, 1f)
    private val dark = Color(0.05f, 0.06f, 0.1f, 0.85f)
    private val row = Color(0.17f, 0.15f, 0.29f, 1f)
    private val btn = Color(0.24f, 0.22f, 0.42f, 1f)
    private val green = Color(0.2f, 0.7f, 0.3f, 1f)
    private val red = Color(0.8f, 0.2f, 0.25f, 1f)
    private val gold = Color(1f, 0.74f, 0.1f, 1f)
    private val cyan = Color(0.25f, 0.8f, 1f, 1f)
    private val locked = Color(0.14f, 0.13f, 0.22f, 1f)
    private val black = Color(0.05f, 0.05f, 0.05f, 1f)
    private val pageBg = Color(0.07f, 0.04f, 0.13f, 1f)

    fun say(s: String) { toast = s; toastT = 3.2f }

    private fun bid(id: Int) = if (screen == 0) id else -1
    private fun box(id: Int, x: Float, y: Float, w: Float, h: Float, c: Color) { boxes.add(Box(id, x, y, w, h, c)) }
    private fun hex(id: Int, x: Float, y: Float, d: Float, c: Color) { boxes.add(Box(id, x, y, d, d, c, true)) }
    private fun text(s: String, x: Float, y: Float, scale: Float, c: Color) { texts.add(Txt(s, x, y, scale, c, 0f, 0f)) }
    private fun ctext(s: String, x: Float, y: Float, w: Float, h: Float, scale: Float, c: Color) { texts.add(Txt(s, x, y, scale, c, w, h)) }
    private fun button(id: Int, s: String, x: Float, y: Float, w: Float, h: Float, c: Color, scale: Float, tc: Color = white) {
        box(id, x, y, w, h, c)
        ctext(s, x, y, w, h, scale, tc)
    }
    private fun rgba(v: Int) = Color(v)
    private fun img(url: String, x: Float, y: Float, w: Float, h: Float) { if (url.isNotBlank()) imgs.add(Img(url, x, y, w, h)) }
    private fun bgImg(url: String, x: Float, y: Float, w: Float, h: Float) { if (url.isNotBlank()) bgImgs.add(Img(url, x, y, w, h)) }
    private fun left(ms: Long): String {
        if (ms <= 0L) return ""
        val d = (ms - System.currentTimeMillis()) / 1000
        if (d <= 0) return "ended"
        return if (d >= 86400) "${d / 86400}d ${(d % 86400) / 3600}h" else "${d / 3600}h ${(d % 3600) / 60}m"
    }

    private fun askName() {
        Gdx.input.getTextInput(object : Input.TextInputListener {
            override fun input(text: String?) { pendingName = text }
            override fun canceled() {}
        }, "Your name", svc.myName(), "Name")
    }
    private fun askFriend() {
        Gdx.input.getTextInput(object : Input.TextInputListener {
            override fun input(text: String?) { pendingFriend = text }
            override fun canceled() {}
        }, "Add friend", "", "Friend ID (SB123456) or name")
    }

    fun drawBackground(w: Float, h: Float) {
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.rect(0f, 0f, w, h, Color(0.16f, 0.07f, 0.3f, 1f), Color(0.16f, 0.07f, 0.3f, 1f), Color(0.04f, 0.04f, 0.16f, 1f), Color(0.04f, 0.04f, 0.16f, 1f))
        shapes.setColor(0.45f, 0.3f, 0.9f, 0.22f)
        shapes.triangle(w * 0.5f, h * 0.95f, w * 0.22f, h * 0.18f, w * 0.78f, h * 0.18f)
        shapes.setColor(0.65f, 0.45f, 1f, 0.35f)
        shapes.triangle(w * 0.5f, h * 0.8f, w * 0.34f, h * 0.18f, w * 0.66f, h * 0.18f)
        shapes.setColor(0.12f, 0.08f, 0.24f, 1f)
        shapes.rect(0f, 0f, w, h * 0.18f)
        shapes.setColor(0.5f, 0.35f, 1f, 0.6f)
        shapes.rect(0f, h * 0.18f, w, 3f)
        shapes.end()
    }

    /** draws the lobby over the 3D character; returns true on the frame START is pressed */
    fun draw(w: Float, h: Float, dt: Float): Boolean {
        t += dt
        if (toastT > 0f) toastT -= dt
        pendingName?.let { svc.setMyName(it); pendingName = null }
        pendingFriend?.let { say(svc.addFriend(it)); pendingFriend = null }
        if (spinT > 0f) {
            spinT -= dt
            if (spinT <= 0f) say(spinMsg)
        }

        boxes.clear()
        texts.clear()
        imgs.clear()
        bgImgs.clear()
        val m = w * 0.02f
        val friends = svc.friends()
        val unread = svc.unread()

        if (screen == 0) buildLobby(w, h, m, friends, unread)
        else buildTopBar(w, h, m)
        if (screen != 0) buildScreen(w, h)

        if (toastT > 0f) {
            box(-1, w * 0.2f, h * 0.93f - 4f, w * 0.6f, h * 0.06f, Color(0f, 0f, 0f, 0.85f))
            ctext(toast, w * 0.2f, h * 0.93f - 4f, w * 0.6f, h * 0.06f, 1.1f, white)
        }

        // ---- render ----
        if (bgImgs.isNotEmpty()) {
            sb.begin()
            sb.setColor(1f, 1f, 1f, 1f)
            for (im in bgImgs) { val tx0 = images.get(im.url); if (tx0 != null) sb.draw(tx0, im.x, h - im.y - im.h, im.w, im.h) }
            sb.end()
        }
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        for (b in boxes) {
            shapes.setColor(b.c)
            if (b.hex) shapes.circle(b.x + b.w / 2f, h - b.y - b.h / 2f, b.w / 2f, 6)
            else shapes.rect(b.x, h - b.y - b.h, b.w, b.h)
        }
        shapes.end()
        sb.begin()
        sb.setColor(1f, 1f, 1f, 1f)
        for (im in imgs) { val tx1 = images.get(im.url); if (tx1 != null) sb.draw(tx1, im.x, h - im.y - im.h, im.w, im.h) }
        for (tx in texts) {
            font.data.setScale(tx.scale)
            font.setColor(tx.c)
            layout.setText(font, tx.s)
            val x = if (tx.bw > 0f) tx.x + (tx.bw - layout.width) / 2f else tx.x
            val y = if (tx.bh > 0f) tx.y + (tx.bh - layout.height) / 2f else tx.y
            font.draw(sb, tx.s, x, h - y)
        }
        font.setColor(Color.WHITE)
        font.data.setScale(3f)
        sb.end()

        // ---- input ----
        if (!Gdx.input.justTouched()) return false
        val tx = Gdx.input.getX().toFloat()
        val ty = Gdx.input.getY().toFloat()
        var hit = -1
        for (b in boxes) if (b.id >= 0 && tx >= b.x && tx <= b.x + b.w && ty >= b.y && ty <= b.y + b.h) hit = b.id
        return onHit(hit, friends)
    }

    private fun onHit(hit: Int, friends: List<Friend>): Boolean {
        val maxF = 4
        when {
            hit == 1 -> { val bn = svc.banned(); if (bn != null) say("ACCOUNT BANNED: $bn") else return true }
            hit == 2 -> askFriend()
            hit == 3 -> askName()
            hit == 4 -> fscroll = maxOf(0, fscroll - 1)
            hit == 5 -> fscroll = minOf(maxOf(0, friends.size - maxF), fscroll + 1)
            hit == 10 -> mode = 0
            hit == 11 || hit == 12 -> say("Duo and Squad need the online server - coming soon")
            hit in 30..34 -> { screen = hit - 29; scroll = 0; sel = ""; spinT = 0f }
            hit == 40 -> { screen = 6; scroll = 0; svc.markAllRead() }
            hit == 90 -> screen = 0
            hit == 91 -> scroll = maxOf(0, scroll - 1)
            hit == 92 -> scroll += 1
            hit in 60..63 -> { sTab = hit - 60; scroll = 0; sel = "" }
            hit == 64 || hit == 65 -> mTab = hit - 64
            hit in 70..79 -> { ringSel = hit - 70; spinNames = emptyList() }
            hit in 100..199 -> if (hit - 100 < friends.size) say(svc.invite(friends[hit - 100].id))
            hit in 200..299 -> if (hit - 200 < friends.size) {
                val f = friends[hit - 200]
                svc.removeFriend(f.id)
                say("${f.name} removed")
            }
            hit in 300..399 -> sel = storeFilter().getOrNull(hit - 300)?.id ?: sel
            hit == 450 -> {
                val it = svc.storeItems().firstOrNull { x -> x.id == sel }
                if (it != null) say(if (it.owned) (if (it.kind == "bundle") "Bundle already collected" else svc.equip(it.id)) else svc.buy(it.id))
            }
            hit == 400 || hit == 401 -> if (spinT <= 0f) {
                val rings = ringNames()
                val ring = rings.getOrElse(ringSel) { rings.firstOrNull() ?: "" }
                val r = svc.spin(ring, if (hit == 401) 5 else 1)
                if (r.startsWith("You won")) { spinMsg = r; spinNames = svc.lastWon(); spinT = 1.6f } else say(r)
            }
            hit == 510 -> say(svc.buyPremium())
            hit in 520..559 -> say(svc.claimPass(hit - 519, false))
            hit in 560..599 -> say(svc.claimPass(hit - 559, true))
            hit in 700..709 -> say(svc.claimMission(false, hit - 700))
            hit in 710..719 -> say(svc.claimMission(true, hit - 710))
            hit == 720 -> say("Play a match to make progress")
            hit == 800 -> say(svc.claimLogin())
            hit in 810..812 -> { val evs = svc.events(); if (hit - 810 < evs.size) say(svc.claimEvent(evs[hit - 810].id)) }
        }
        return false
    }

    // ================= lobby (home) =================
    private fun buildTopBar(w: Float, h: Float, m: Float) {
        box(-1, 0f, 0f, w, h * 0.085f, dark)
        box(-1, m, h * 0.01f, h * 0.065f, h * 0.065f, Color(0.4f, 0.25f, 0.8f, 1f))
        text("Lv ${svc.level()}", m + 6f, h * 0.03f, 1.1f, white)
        text(svc.myName(), m + h * 0.065f + 12f, h * 0.008f, 1.3f, white)
        box(-1, m + h * 0.065f + 12f, h * 0.058f, w * 0.14f, 8f, Color(0.25f, 0.25f, 0.35f, 1f))
        box(-1, m + h * 0.065f + 12f, h * 0.058f, w * 0.14f * svc.levelProgress(), 8f, green)
        val gx = w * 0.27f
        box(-1, gx, h * 0.022f, 30f, 30f, gold)
        text("" + svc.gold(), gx + 40f, h * 0.02f, 1.5f, white)
        val dx = w * 0.40f
        box(-1, dx, h * 0.022f, 26f, 30f, cyan)
        text("" + svc.diamonds(), dx + 38f, h * 0.02f, 1.5f, white)
    }

    private fun buildLobby(w: Float, h: Float, m: Float, friends: List<Friend>, unread: Int) {
        buildTopBar(w, h, m)
        val bnr = svc.banned()
        if (bnr != null) text("BANNED: $bnr", w * 0.5f, h * 0.1f, 1.1f, red)
        text(if (svc.connected) "ONLINE" else "OFFLINE", w * 0.9f, h * 0.025f, 1.1f, if (svc.connected) green else dim)

        val names = arrayOf("STORE", "LUCK ROYALE", "SOHEL PASS", "MISSIONS", "EVENTS")
        for (i in 0..4) {
            val by = h * 0.15f + i * h * 0.105f
            button(bid(30 + i), names[i], m, by, w * 0.19f, h * 0.088f, dark, 1.4f, gold)
            box(-1, m, by, 5f, h * 0.088f, gold)
            val dot = (i == 3 && svc.missionsReady()) || (i == 4 && svc.loginReady()) || (i == 1 && svc.spinIsFree())
            if (dot) box(-1, m + w * 0.19f - 18f, by + 6f, 12f, 12f, red)
        }
        button(bid(40), if (unread > 0) "MESSAGES ($unread)" else "MESSAGES", m, h * 0.84f, w * 0.19f, h * 0.088f, dark, 1.2f, white)
        button(bid(3), svc.myName() + "   " + svc.myId(), w * 0.36f, h * 0.90f, w * 0.28f, h * 0.075f, dark, 1.1f, dim)

        val fx = w * 0.80f
        val fw = w * 0.18f
        box(-1, fx, h * 0.12f, fw, h * 0.54f, dark)
        text("FRIENDS (${friends.size})", fx + 10f, h * 0.13f, 1.1f, white)
        button(bid(2), "+", fx + fw - h * 0.065f - 4f, h * 0.122f, h * 0.065f, h * 0.05f, green, 1.2f)
        val maxF = 4
        if (fscroll > maxOf(0, friends.size - maxF)) fscroll = maxOf(0, friends.size - maxF)
        if (friends.isEmpty()) ctext("Tap + and type a friend's ID", fx, h * 0.25f, fw, h * 0.2f, 0.9f, dim)
        for (k in 0 until maxF) {
            val i = fscroll + k
            if (i >= friends.size) break
            val f = friends[i]
            val ry = h * 0.19f + k * h * 0.1f
            box(bid(100 + i), fx + 6f, ry, fw - 12f, h * 0.09f, row)
            box(-1, fx + 14f, ry + h * 0.04f, 10f, 10f, if (f.status == 1) green else if (f.status == 2) gold else Color.GRAY)
            text(f.name, fx + 32f, ry + 6f, 1.0f, white)
            text(if (f.status == 1) "Online" else if (f.status == 2) "In match" else "Offline", fx + 32f, ry + h * 0.045f, 0.8f, dim)
            button(bid(200 + i), "X", fx + fw - 6f - h * 0.05f, ry + 4f, h * 0.05f, h * 0.05f, red, 0.9f)
        }
        button(bid(4), "^", fx + 6f, h * 0.60f, fw / 2f - 8f, h * 0.05f, btn, 1.0f)
        button(bid(5), "v", fx + fw / 2f + 2f, h * 0.60f, fw / 2f - 8f, h * 0.05f, btn, 1.0f)

        val mx = w * 0.62f
        val mw = w * 0.36f
        val tw = (mw - 16f) / 3f
        val mnames = arrayOf("SOLO", "DUO", "SQUAD")
        for (i in 0..2) {
            button(bid(10 + i), mnames[i], mx + i * (tw + 8f), h * 0.69f, tw, h * 0.07f, if (i == mode) gold else locked, 1.2f, if (i == mode) black else dim)
        }
        box(-1, mx, h * 0.77f, mw, h * 0.07f, dark)
        ctext("BATTLE ROYALE - Random Map", mx, h * 0.77f, mw, h * 0.07f, 1.1f, white)
        val pulse = 0.9f + 0.1f * sin(t * 3f)
        button(bid(1), "START", mx, h * 0.855f, mw, h * 0.125f, Color(1f * pulse, 0.78f * pulse, 0.05f, 1f), 3f, black)
    }

    // ================= full-screen pages =================
    private val titles = arrayOf("", "STORE", "LUCK ROYALE", "SOHEL PASS", "MISSIONS", "EVENTS", "MESSAGES")

    private fun buildScreen(w: Float, h: Float) {
        val top = h * 0.085f
        val passBg = if (screen == 3) svc.passInfo().img else ""
        if (passBg.isEmpty()) {
            box(-1, 0f, top, w, h - top, pageBg)
            box(-1, w * 0.58f, top, w * 0.42f, h - top, Color(0.45f, 0.18f, 0.05f, 0.3f))
        } else bgImg(passBg, 0f, top, w, h - top)
        text(titles[screen], w * 0.66f, h * 0.02f, 1.8f, white)
        button(90, "X", w - h * 0.1f, 0f, h * 0.1f, h * 0.085f, red, 1.6f)
        when (screen) {
            1 -> storePage(w, h)
            2 -> luckPage(w, h)
            3 -> passPage(w, h)
            4 -> missionPage(w, h)
            5 -> eventsPage(w, h)
            6 -> messagesPage(w, h)
        }
    }

    private fun tabButtons(id0: Int, names: Array<String>, cur: Int, w: Float, h: Float) {
        for (i in names.indices) {
            val y = h * 0.12f + i * h * 0.12f
            button(id0 + i, names[i], w * 0.015f, y, w * 0.16f, h * 0.1f, if (i == cur) gold else Color(0.1f, 0.08f, 0.16f, 1f), 1.4f, if (i == cur) black else white)
        }
    }

    // ---------- STORE ----------
    private fun storeFilter(): List<StoreItem> {
        val all = svc.storeItems()
        return when (sTab) { 1 -> all.filter { it.kind == "gunskin" }; 2 -> all.filter { it.kind == "bundle" }; 3 -> all.filter { it.kind != "gunskin" && it.kind != "bundle" }; else -> all }
    }

    private fun storePage(w: Float, h: Float) {
        tabButtons(60, arrayOf("HIGHLIGHT", "GUN SKINS", "BUNDLES", "OTHER"), sTab, w, h)
        val fl = storeFilter()
        if (fl.none { it.id == sel }) sel = fl.firstOrNull()?.id ?: ""
        val rowsVis = 3
        val maxScroll = maxOf(0, (fl.size + 1) / 2 - rowsVis)
        scroll = minOf(scroll, maxScroll)
        val cw = w * 0.185f
        val ch = h * 0.265f
        val gx = w * 0.19f
        for (k in 0 until rowsVis * 2) {
            val i = scroll * 2 + k
            if (i >= fl.size) break
            val it = fl[i]
            val cx = gx + (k % 2) * (cw + w * 0.012f)
            val cy = h * 0.11f + (k / 2) * (ch + h * 0.02f)
            if (it.id == sel) box(-1, cx - 4f, cy - 4f, cw + 8f, ch + 8f, gold)
            box(300 + i, cx, cy, cw, ch, rgba(it.body))
            box(-1, cx, cy + ch * 0.62f, cw, ch * 0.38f, Color(0f, 0f, 0f, 0.6f))
            img(it.img, cx, cy, cw, ch * 0.62f)
            if (it.kind == "gunskin" && it.img.isEmpty()) { box(-1, cx + cw * 0.12f, cy + ch * 0.28f, cw * 0.76f, ch * 0.1f, rgba(it.acc)); box(-1, cx + cw * 0.15f, cy + ch * 0.38f, cw * 0.14f, ch * 0.12f, black) }
            if (it.kind == "bundle" && it.img.isEmpty()) ctext("BUNDLE", cx, cy + ch * 0.1f, cw, ch * 0.3f, 1.6f, white)
            text(it.name.take(18), cx + 10f, cy + ch * 0.66f, 1.0f, white)
            text(if (it.price == 0) "FREE" else "${it.price} " + (if (it.dia) "DIA" else "GOLD"), cx + 10f, cy + ch * 0.82f, 1.0f, if (it.dia) cyan else gold)
            if (it.owned) { box(-1, cx, cy, cw * 0.4f, ch * 0.12f, green); ctext("OWNED", cx, cy, cw * 0.4f, ch * 0.12f, 0.8f, white) }
        }
        button(91, "^", w * 0.015f, h * 0.62f, w * 0.075f, h * 0.08f, btn, 1.2f)
        button(92, "v", w * 0.1f, h * 0.62f, w * 0.075f, h * 0.08f, btn, 1.2f)

        // preview
        val it = fl.firstOrNull { x -> x.id == sel }
        if (it == null) {
            ctext("Nothing here yet", w * 0.58f, h * 0.3f, w * 0.4f, h * 0.2f, 1.4f, dim)
            return
        }
        val px = w * 0.60f
        val pw = w * 0.37f
        text(it.name.take(22), px, h * 0.11f, 2.3f, gold)
        text(when (it.kind) { "gunskin" -> "GUN SKIN - " + it.gun; "bundle" -> "BUNDLE"; else -> "COLLECTION" }, px, h * 0.11f + 52f, 1.2f, dim)
        val body = rgba(it.body)
        val acc = rgba(it.acc)
        if (it.img.isNotEmpty()) {
            img(it.img, px, h * 0.2f, pw, h * 0.42f)
        } else if (it.kind == "gunskin") {
            box(-1, px + pw * 0.04f, h * 0.36f, pw * 0.1f, h * 0.07f, body)
            box(-1, px + pw * 0.12f, h * 0.35f, pw * 0.62f, h * 0.1f, body)
            box(-1, px + pw * 0.12f, h * 0.35f, pw * 0.62f, h * 0.025f, acc)
            box(-1, px + pw * 0.74f, h * 0.375f, pw * 0.22f, h * 0.03f, Color(0.12f, 0.12f, 0.14f, 1f))
            box(-1, px + pw * 0.2f, h * 0.45f, pw * 0.07f, h * 0.13f, Color(0.12f, 0.12f, 0.14f, 1f))
            box(-1, px + pw * 0.4f, h * 0.45f, pw * 0.07f, h * 0.1f, acc)
        } else {
            box(-1, px + pw * 0.3f, h * 0.3f, pw * 0.4f, h * 0.3f, body)
            ctext(if (it.kind == "bundle") "BUNDLE" else "ITEM", px + pw * 0.3f, h * 0.3f, pw * 0.4f, h * 0.3f, 1.8f, white)
        }
        text(it.info.take(90), px, h * 0.65f, 0.95f, white)
        val lab = if (!it.owned) "OBTAIN  " + (if (it.price == 0) "FREE" else "${it.price} " + (if (it.dia) "DIA" else "GOLD")) else if (it.kind == "bundle") "COLLECTED" else if (it.equipped) "EQUIPPED" else "EQUIP"
        button(450, lab, px, h * 0.78f, pw, h * 0.13f, if (it.owned && (it.equipped || it.kind == "bundle")) locked else gold, 2.0f, if (it.owned && (it.equipped || it.kind == "bundle")) dim else black)
    }

    // ---------- LUCK ROYALE ----------
    private fun ringNames(): List<String> = svc.prizes().map { it.ring }.distinct()

    private val slotX = floatArrayOf(0.09f, 0.30f, 0.50f, 0.71f, 0.91f, 0.09f, 0.30f, 0.50f, 0.71f, 0.91f, 0.25f, 0.75f, 0.50f, 0.50f)
    private val slotY = floatArrayOf(0.28f, 0.22f, 0.09f, 0.22f, 0.28f, 0.77f, 0.80f, 0.97f, 0.80f, 0.77f, 0.50f, 0.50f, 0.34f, 0.65f)
    private val slotD = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.19f, 0.19f, 0.135f, 0.135f)

    private fun luckPage(w: Float, h: Float) {
        val rings = ringNames()
        if (ringSel >= rings.size) ringSel = 0
        val ring = rings.getOrElse(ringSel) { "" }
        val all = svc.prizes()
        for ((i, r) in rings.withIndex().take(6)) {
            val y = h * 0.11f + i * h * 0.13f
            box(70 + i, w * 0.015f, y, w * 0.2f, h * 0.11f, if (i == ringSel) gold else Color(0.1f, 0.08f, 0.16f, 1f))
            text(r.take(14), w * 0.025f, y + 8f, 1.3f, if (i == ringSel) black else white)
            val lt = left(all.filter { it.ring == r && it.endMs > 0 }.minOfOrNull { it.endMs } ?: 0L)
            if (lt.isNotEmpty()) text(lt, w * 0.025f, y + h * 0.065f, 0.9f, if (i == ringSel) black else dim)
        }
        val banner = all.firstOrNull { it.ring == ring && it.ringImg.isNotEmpty() }?.ringImg ?: ""
        if (banner.isNotEmpty()) img(banner, w * 0.24f, h * 0.09f, w * 0.3f, h * 0.1f) else text(ring.uppercase().take(20), w * 0.24f, h * 0.1f, 2.6f, gold)
        val prizes = all.filter { it.ring == ring }.sortedBy { it.weight }.take(14)
        val ax = w * 0.24f
        val ay = h * 0.2f
        val aw = w * 0.36f
        val ah = h * 0.58f
        val hiIdx = if (spinT > 0f) ((t * 14f).toInt()) % maxOf(1, prizes.size) else -1
        for ((i, p) in prizes.withIndex()) {
            val d = slotD[i] * w * 0.62f
            val cx = ax + slotX[i] * aw
            val cy = ay + slotY[i] * ah
            val rare = i < 4
            val win = spinT <= 0f && spinNames.isNotEmpty() && p.name == spinNames.last()
            val c = if (i == hiIdx || win) Color(1f, 1f, 1f, 1f) else if (rare) Color(0.85f, 0.6f, 0.1f, 1f) else Color(0.5f, 0.52f, 0.58f, 1f)
            hex(-1, cx - d / 2f, cy - d / 2f, d, c)
            if (p.img.isNotEmpty()) img(p.img, cx - d * 0.36f, cy - d * 0.36f, d * 0.72f, d * 0.72f)
            else ctext(p.name.take(12), cx - d / 2f, cy - d / 2f, d, d, if (rare) 0.9f else 0.75f, if (i == hiIdx || win) black else white)
        }
        val free = svc.spinIsFree()
        button(400, if (free) "FREE SPIN" else "1 SPIN  20 DIA", w * 0.24f, h * 0.84f, w * 0.17f, h * 0.12f, Color(0.15f, 0.55f, 0.9f, 1f), 1.5f)
        button(401, "5 SPINS  90 DIA", w * 0.43f, h * 0.84f, w * 0.17f, h * 0.12f, gold, 1.5f, black)
        text("LAST RESULT", w * 0.64f, h * 0.15f, 1.4f, dim)
        val won = svc.lastWon()
        if (won.isEmpty()) text("Spin to win prizes!", w * 0.64f, h * 0.22f, 1.3f, white)
        for ((i, n) in won.withIndex()) if (spinT <= 0f) text("- $n", w * 0.64f, h * 0.22f + i * 46f, 1.5f, gold)
        if (spinT > 0f) text("Spinning...", w * 0.64f, h * 0.22f, 1.6f, white)
        text("Rare prizes are the big gold hexagons.", w * 0.64f, h * 0.72f, 0.9f, dim)
    }

    // ---------- SOHEL PASS ----------
    private fun passPage(w: Float, h: Float) {
        val info = svc.passInfo()
        val rows = svc.passRows()
        text(info.title.uppercase().take(18), w * 0.04f, h * 0.1f, 3.4f, Color(0.85f, 0.75f, 1f, 1f))
        val lt = left(info.endMs)
        if (lt.isNotEmpty()) text("ENDS IN $lt   LIMITED EDITION", w * 0.04f, h * 0.1f + 74f, 1.0f, dim)
        text("Lv.${svc.passLevel()}", w * 0.04f, h * 0.2f, 2.0f, white)
        box(-1, w * 0.14f, h * 0.215f, w * 0.34f, 16f, locked)
        box(-1, w * 0.14f, h * 0.215f, w * 0.34f * svc.passProgress(), 16f, gold)
        text("PASS EXP ${svc.passXp()} / 100", w * 0.14f, h * 0.215f + 24f, 0.9f, dim)

        val cw = w * 0.095f
        val tx0 = w * 0.14f
        val vis = 5
        scroll = minOf(scroll, maxOf(0, rows.size - vis))
        box(-1, w * 0.03f, h * 0.36f, w * 0.1f, h * 0.2f, Color(0.2f, 0.25f, 0.45f, 1f))
        ctext("FREE", w * 0.03f, h * 0.36f, w * 0.1f, h * 0.2f, 1.4f, white)
        box(-1, w * 0.03f, h * 0.58f, w * 0.1f, h * 0.2f, Color(0.16f, 0.2f, 0.55f, 1f))
        ctext("PREMIUM", w * 0.03f, h * 0.58f, w * 0.1f, h * 0.2f, 1.2f, gold)
        for (k in 0 until vis) {
            val i = scroll + k
            if (i >= rows.size) break
            val r = rows[i]
            val x = tx0 + k * (cw + 4f)
            box(-1, x, h * 0.30f, cw, h * 0.055f, if (r.unlocked) gold else Color(0.25f, 0.25f, 0.4f, 1f))
            ctext("Lv.${r.level}", x, h * 0.30f, cw, h * 0.055f, 1.1f, if (r.unlocked) black else white)
            val fc = if (r.freeKind == 2) Color(0.5f, 0.3f, 0.8f, 1f) else if (r.freeKind == 1) Color(0.15f, 0.5f, 0.7f, 1f) else Color(0.6f, 0.45f, 0.1f, 1f)
            val pc = if (r.premKind == 2) Color(0.5f, 0.3f, 0.8f, 1f) else if (r.premKind == 1) Color(0.15f, 0.5f, 0.7f, 1f) else Color(0.6f, 0.45f, 0.1f, 1f)
            box(519 + r.level, x, h * 0.36f, cw, h * 0.2f, fc)
            ctext(r.free, x, h * 0.36f, cw, h * 0.14f, 1.0f, white)
            ctext(if (r.freeDone) "CLAIMED" else if (r.unlocked) "TAP" else "LOCK", x, h * 0.48f, cw, h * 0.06f, 0.8f, if (r.unlocked && !r.freeDone) gold else dim)
            box(559 + r.level, x, h * 0.58f, cw, h * 0.2f, pc)
            ctext(r.prem, x, h * 0.58f, cw, h * 0.14f, 1.0f, white)
            ctext(if (r.premDone) "CLAIMED" else if (!svc.passPremium()) "LOCKED" else if (r.unlocked) "TAP" else "LOCK", x, h * 0.70f, cw, h * 0.06f, 0.8f, if (svc.passPremium() && r.unlocked && !r.premDone) gold else dim)
            if (r.freeDone) box(-1, x, h * 0.36f, cw, h * 0.2f, Color(0f, 0f, 0f, 0.55f))
            if (r.premDone || !svc.passPremium()) box(-1, x, h * 0.58f, cw, h * 0.2f, Color(0f, 0f, 0f, 0.45f))
        }
        if (rows.isNotEmpty()) {
            val g = rows.last()
            val gx = tx0 + vis * (cw + 4f) + 10f
            box(-1, gx, h * 0.30f, w * 0.15f, h * 0.48f, Color(0.3f, 0.35f, 0.75f, 1f))
            ctext("Lv.${g.level}", gx, h * 0.31f, w * 0.15f, h * 0.06f, 1.3f, white)
            ctext("GRAND REWARD", gx, h * 0.45f, w * 0.15f, h * 0.06f, 1.0f, gold)
            ctext(g.prem, gx, h * 0.52f, w * 0.15f, h * 0.08f, 1.3f, white)
        }
        button(91, "<", tx0, h * 0.81f, w * 0.06f, h * 0.08f, btn, 1.4f)
        button(92, ">", tx0 + w * 0.07f, h * 0.81f, w * 0.06f, h * 0.08f, btn, 1.4f)
        if (svc.passPremium()) button(-1, "PREMIUM ACTIVE", w * 0.4f, h * 0.84f, w * 0.3f, h * 0.11f, locked, 1.6f, gold)
        else button(510, "UNLOCK  ${info.price} DIA", w * 0.4f, h * 0.84f, w * 0.3f, h * 0.11f, gold, 1.8f, black)
        text("Earn pass XP by playing matches and finishing missions.", w * 0.04f, h * 0.93f, 0.85f, dim)
    }

    // ---------- MISSIONS ----------
    private fun missionPage(w: Float, h: Float) {
        val names = arrayOf("DAILY", "WEEKLY")
        for (i in 0..1) button(64 + i, names[i], w * 0.02f, h * 0.12f + i * h * 0.12f, w * 0.2f, h * 0.1f, if (i == mTab) gold else Color(0.1f, 0.08f, 0.16f, 1f), 1.5f, if (i == mTab) black else white)
        val weekly = mTab == 1
        val ms = svc.missions(weekly)
        text(if (weekly) "WEEKLY MISSION" else "DAILY MISSION", w * 0.26f, h * 0.1f, 2.4f, Color(0.85f, 0.75f, 1f, 1f))
        val now = System.currentTimeMillis()
        val off = java.util.TimeZone.getDefault().getOffset(now)
        val rem = 86400000L - ((now + off) % 86400000L)
        val sec = rem / 1000
        text(if (weekly) "Resets every Monday" else String.format("Resets in %02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60), w * 0.26f, h * 0.1f + 60f, 1.0f, dim)
        val done = ms.count { it.claimed }
        text("Missions completed: $done / ${ms.size}", w * 0.04f, h * 0.7f, 1.1f, white)
        val rh = h * 0.115f
        scroll = minOf(scroll, maxOf(0, ms.size - 6))
        for (k in 0 until 6) {
            val i = scroll + k
            if (i >= ms.size) break
            val m = ms[i]
            val y = h * 0.2f + k * (rh + 6f)
            val x = w * 0.26f
            val rw = w * 0.72f
            box(-1, x, y, rw, rh, Color(0.12f, 0.09f, 0.22f, 1f))
            text(m.title.take(34), x + 14f, y + rh * 0.3f, 1.2f, white)
            var bx = x + rw * 0.45f
            if (m.gold > 0) { box(-1, bx, y + 8f, rw * 0.1f, rh - 16f, Color(0.6f, 0.45f, 0.1f, 1f)); ctext("x${m.gold}", bx, y + 8f, rw * 0.1f, rh - 16f, 1.0f, white); bx += rw * 0.11f }
            if (m.dia > 0) { box(-1, bx, y + 8f, rw * 0.1f, rh - 16f, Color(0.15f, 0.5f, 0.7f, 1f)); ctext("x${m.dia}", bx, y + 8f, rw * 0.1f, rh - 16f, 1.0f, white); bx += rw * 0.11f }
            box(-1, bx, y + 8f, rw * 0.1f, rh - 16f, Color(0.3f, 0.35f, 0.8f, 1f))
            ctext("XP ${m.xp}", bx, y + 8f, rw * 0.1f, rh - 16f, 0.9f, white)
            ctext("${m.progress}/${m.goal}", x + rw * 0.76f, y, rw * 0.1f, rh * 0.45f, 1.0f, white)
            val ready = m.progress >= m.goal && !m.claimed
            val bw = rw * 0.17f
            if (m.claimed) button(-1, "CLAIMED", x + rw - bw - 10f, y + rh * 0.4f, bw, rh * 0.5f, locked, 1.0f, green)
            else if (ready) button((if (weekly) 710 else 700) + i, "CLAIM", x + rw - bw - 10f, y + rh * 0.4f, bw, rh * 0.5f, green, 1.1f)
            else button(720, "GO", x + rw - bw - 10f, y + rh * 0.4f, bw, rh * 0.5f, Color(0.8f, 0.8f, 0.85f, 1f), 1.1f, black)
        }
        if (ms.size > 6) { button(91, "^", w * 0.02f, h * 0.45f, w * 0.095f, h * 0.08f, btn, 1.2f); button(92, "v", w * 0.125f, h * 0.45f, w * 0.095f, h * 0.08f, btn, 1.2f) }
    }

    // ---------- EVENTS ----------
    private fun eventsPage(w: Float, h: Float) {
        val rx = w * 0.04f
        val rw = w * 0.88f
        val rowH = h * 0.14f
        box(-1, rx, h * 0.12f, rw, rowH, row)
        text("DAILY LOGIN", rx + 14f, h * 0.12f + 10f, 1.8f, gold)
        text("Open the game every day: 100 Gold + 20 XP", rx + 14f, h * 0.12f + rowH * 0.6f, 1.1f, white)
        button(800, if (svc.loginReady()) "CLAIM" else "CLAIMED", rx + rw - w * 0.16f - 10f, h * 0.12f + 12f, w * 0.16f, rowH - 24f, if (svc.loginReady()) green else locked, 1.3f)
        val evs = svc.events()
        if (evs.isEmpty()) ctext("No other events right now - check back soon", rx, h * 0.32f, rw, rowH, 1.2f, dim)
        for (k in 0 until minOf(3, evs.size)) {
            val e = evs[k]
            val ry = h * 0.12f + (k + 1) * (rowH + 10f)
            box(-1, rx, ry, rw, rowH, row)
            img(e.img, rx + rw - w * 0.16f - 20f - rowH * 1.7f, ry + 6f, rowH * 1.7f, rowH - 12f)
            text(e.title.take(28), rx + 14f, ry + 10f, 1.6f, white)
            text(e.desc.take(70) + "   Reward: " + e.reward, rx + 14f, ry + rowH * 0.6f, 1.0f, gold)
            button(810 + k, if (e.claimed) "CLAIMED" else "CLAIM", rx + rw - w * 0.16f - 10f, ry + 12f, w * 0.16f, rowH - 24f, if (e.claimed) locked else green, 1.3f)
        }
    }

    // ---------- MESSAGES ----------
    private fun messagesPage(w: Float, h: Float) {
        val ms = svc.messages()
        val rx = w * 0.04f
        val rw = w * 0.88f
        val rowH = h * 0.14f
        scroll = minOf(scroll, maxOf(0, ms.size - 5))
        if (ms.isEmpty()) ctext("No messages yet", rx, h * 0.3f, rw, h * 0.2f, 1.3f, dim)
        for (k in 0 until 5) {
            val i = scroll + k
            if (i >= ms.size) break
            val ry = h * 0.11f + k * (rowH + 6f)
            box(-1, rx, ry, rw, rowH, row)
            text(ms[i].time, rx + 14f, ry + 8f, 0.9f, dim)
            text(ms[i].text.take(90), rx + 14f, ry + rowH * 0.45f, 1.1f, white)
        }
        if (ms.size > 5) { button(91, "^", w * 0.7f, h * 0.9f, w * 0.1f, h * 0.07f, btn, 1.2f); button(92, "v", w * 0.82f, h * 0.9f, w * 0.1f, h * 0.07f, btn, 1.2f) }
        text("Friend chat arrives with the online server.", rx, h * 0.93f, 0.85f, dim)
    }
}
