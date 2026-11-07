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
class Mission(val title: String, val goal: Int, val progress: Int, val reward: String, val claimed: Boolean)
class PassRow(val level: Int, val free: String, val prem: String, val unlocked: Boolean, val freeDone: Boolean, val premDone: Boolean)
class StoreItem(val id: String, val name: String, val price: Int, val dia: Boolean, val owned: Boolean, val equipped: Boolean)

/**
 * Everything the lobby needs from "the outside world". Today it is answered by [LocalLobbyService]
 * (saved on the phone, no network). When the server exists, write a second class that implements this
 * interface (REST / websocket calls) and give it to Lobby instead - the lobby screens do not change.
 * Every method that changes money / rewards returns a message to show the player.
 */
interface LobbyService {
    val connected: Boolean
    // profile + wallet
    fun myName(): String
    fun myId(): String
    fun setMyName(n: String)
    fun gold(): Int
    fun diamonds(): Int
    fun level(): Int
    fun levelProgress(): Float
    // friends
    fun friends(): List<Friend>
    fun addFriend(idOrName: String): String
    fun removeFriend(id: String)
    fun invite(id: String): String
    // messages
    fun messages(): List<Msg>
    fun unread(): Int
    fun markAllRead()
    // missions
    fun missions(): List<Mission>
    fun claimMission(i: Int): String
    fun missionsReady(): Boolean
    // sohel pass
    fun passLevel(): Int
    fun passProgress(): Float
    fun passPremium(): Boolean
    fun passRows(): List<PassRow>
    fun claimPass(level: Int, premium: Boolean): String
    fun buyPremium(): String
    // store + luck royale + events
    fun storeItems(): List<StoreItem>
    fun buy(id: String): String
    fun equip(id: String): String
    fun spinIsFree(): Boolean
    fun spin(): String
    fun loginReady(): Boolean
    fun claimLogin(): String
    // after each match
    fun recordMatch(kills: Int, won: Boolean): String
}

/** Standalone (offline) implementation: everything is saved in the phone's preferences. */
class LocalLobbyService : LobbyService {
    private val prefs = Gdx.app.getPreferences("sohelbr_lobby")
    private val list = ArrayList<Friend>()
    private val msgs = ArrayList<Msg>()
    override val connected = false

    private val mTitles = arrayOf("Play 1 match", "Get 3 kills", "Play 3 matches", "Win a match")
    private val mGoals = intArrayOf(1, 3, 3, 1)
    private val mGold = intArrayOf(50, 80, 0, 0)
    private val mDia = intArrayOf(0, 0, 10, 25)

    private val items = arrayOf(
        arrayOf("camo", "Jungle Camo", "0", "g"), arrayOf("desert", "Desert Camo", "500", "g"),
        arrayOf("snow", "Snow Ghost", "800", "g"), arrayOf("red", "Red Jumpsuit", "300", "d"),
        arrayOf("goldak", "Golden Gun Skin", "800", "d"), arrayOf("chute", "Sunset Parachute", "400", "g"),
        arrayOf("emote", "Salute Emote", "200", "g"), arrayOf("pack", "Elite Backpack Skin", "150", "d")
    )

    private val spinNames = arrayOf("50 Gold", "100 Gold", "250 Gold", "500 Gold", "5 Diamonds", "20 Diamonds", "100 Diamonds")
    private val spinGold = intArrayOf(50, 100, 250, 500, 0, 0, 0)
    private val spinDia = intArrayOf(0, 0, 0, 0, 5, 20, 100)
    private val spinW = intArrayOf(30, 25, 15, 6, 14, 7, 3)

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
        ensureDay()
    }

    private fun gi(k: String) = prefs.getInteger(k, 0)
    private fun pi(k: String, v: Int) { prefs.putInteger(k, v); prefs.flush() }
    private fun today() = (System.currentTimeMillis() / 86400000L).toInt()

    private fun ensureDay() {
        if (prefs.getInteger("day", -1) != today()) {
            prefs.putInteger("day", today())
            prefs.putInteger("mMatches", 0)
            prefs.putInteger("mKills", 0)
            prefs.putInteger("mWins", 0)
            prefs.putInteger("mClaim", 0)
            prefs.flush()
        }
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

    private fun addGold(g: Int) { pi("gold", gi("gold") + g) }
    private fun addDia(d: Int) { pi("dia", gi("dia") + d) }
    private fun addXp(x: Int) { prefs.putInteger("xp", gi("xp") + x); prefs.putInteger("pxp", gi("pxp") + x); prefs.flush() }
    private fun clean(s: String) = s.replace(",", " ").replace(";", " ").trim().take(14)

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

    private fun counter(i: Int) = when (i) { 0, 2 -> gi("mMatches"); 1 -> gi("mKills"); else -> gi("mWins") }
    override fun missions(): List<Mission> {
        ensureDay()
        return (0..3).map { i ->
            val rw = (if (mGold[i] > 0) "${mGold[i]} Gold" else "") + (if (mDia[i] > 0) "${mDia[i]} Diamonds" else "")
            Mission(mTitles[i], mGoals[i], minOf(mGoals[i], counter(i)), rw, ((gi("mClaim") shr i) and 1) == 1)
        }
    }
    override fun missionsReady(): Boolean = missions().any { it.progress >= it.goal && !it.claimed }
    override fun claimMission(i: Int): String {
        ensureDay()
        if (i !in 0..3) return ""
        if (((gi("mClaim") shr i) and 1) == 1) return "Already claimed"
        if (counter(i) < mGoals[i]) return "Not finished yet"
        pi("mClaim", gi("mClaim") or (1 shl i))
        addGold(mGold[i]); addDia(mDia[i]); addXp(40)
        return "Mission reward: " + (if (mGold[i] > 0) "${mGold[i]} Gold" else "${mDia[i]} Diamonds") + " + 40 XP"
    }

    // ---- Sohel Pass: 20 levels, 100 pass XP each ----
    override fun passLevel() = minOf(20, gi("pxp") / 100)
    override fun passProgress() = if (passLevel() >= 20) 1f else (gi("pxp") % 100) / 100f
    override fun passPremium() = gi("premium") == 1
    private fun freeGold(l: Int) = if (l % 5 == 0) 0 else 50 + 10 * l
    private fun freeDia(l: Int) = if (l % 5 == 0) 10 else 0
    private fun premDia(l: Int) = if (l % 5 == 0) 40 else 5
    override fun passRows(): List<PassRow> = (1..20).map { l ->
        PassRow(l, if (freeDia(l) > 0) "${freeDia(l)} Diamonds" else "${freeGold(l)} Gold", "${premDia(l)} Diamonds",
            l <= passLevel(), ((gi("pFree") shr l) and 1) == 1, ((gi("pPrem") shr l) and 1) == 1)
    }
    override fun claimPass(level: Int, premium: Boolean): String {
        if (level !in 1..20) return ""
        if (level > passLevel()) return "Reach Sohel Pass level $level first"
        val key = if (premium) "pPrem" else "pFree"
        if (((gi(key) shr level) and 1) == 1) return "Already claimed"
        if (premium && !passPremium()) return "Unlock the premium pass first"
        pi(key, gi(key) or (1 shl level))
        if (premium) addDia(premDia(level)) else { addGold(freeGold(level)); addDia(freeDia(level)) }
        return "Pass level $level reward claimed"
    }
    override fun buyPremium(): String {
        if (passPremium()) return "Premium already unlocked"
        if (gi("dia") < 150) return "You need 150 diamonds"
        addDia(-150); pi("premium", 1)
        return "Premium Sohel Pass unlocked!"
    }

    // ---- Store ----
    override fun storeItems(): List<StoreItem> {
        val owned = prefs.getString("owned", "camo").split(",")
        val eq = prefs.getString("equipped", "camo").split(",")
        return items.map { StoreItem(it[0], it[1], it[2].toInt(), it[3] == "d", it[0] in owned, it[0] in eq) }
    }
    override fun buy(id: String): String {
        val it = storeItems().firstOrNull { x -> x.id == id } ?: return ""
        if (it.owned) return "You already own ${it.name}"
        if (it.dia) { if (gi("dia") < it.price) return "Not enough diamonds"; addDia(-it.price) }
        else { if (gi("gold") < it.price) return "Not enough gold"; addGold(-it.price) }
        prefs.putString("owned", prefs.getString("owned", "camo") + "," + id)
        prefs.flush()
        return "${it.name} bought"
    }
    override fun equip(id: String): String {
        val eq = prefs.getString("equipped", "camo").split(",").toMutableList()
        if (id in eq) eq.remove(id) else eq.add(id)
        prefs.putString("equipped", eq.joinToString(","))
        prefs.flush()
        return if (id in eq) "Equipped" else "Unequipped"
    }

    // ---- Luck Royale ----
    override fun spinIsFree() = gi("lastSpin") != today() + 1   // stored as day+1 so 0 means never
    override fun spin(): String {
        val free = spinIsFree()
        if (!free) {
            if (gi("dia") < 20) return "Not enough diamonds (20 per spin)"
            addDia(-20)
        } else pi("lastSpin", today() + 1)
        var r = java.util.Random().nextInt(spinW.sum())
        var k = 0
        while (r >= spinW[k]) { r -= spinW[k]; k++ }
        addGold(spinGold[k]); addDia(spinDia[k])
        return "You won " + spinNames[k] + "!"
    }

    // ---- Events ----
    override fun loginReady() = gi("lastLogin") != today() + 1
    override fun claimLogin(): String {
        if (!loginReady()) return "Come back tomorrow"
        pi("lastLogin", today() + 1)
        addGold(100); addXp(20)
        return "Daily login: 100 Gold + 20 XP"
    }

    override fun recordMatch(kills: Int, won: Boolean): String {
        ensureDay()
        pi("mMatches", gi("mMatches") + 1)
        pi("mKills", gi("mKills") + kills)
        if (won) pi("mWins", gi("mWins") + 1)
        val g = 20 + kills * 10 + (if (won) 100 else 0)
        val x = 30 + kills * 10 + (if (won) 100 else 0)
        addGold(g); addXp(x)
        val s = "Match reward: +$g Gold, +$x XP"
        addMsg(s)
        return s
    }
}

/** The main menu shown before a match (Free Fire style): character, wallet, menus, friends, modes and START. */
class Lobby(private val font: BitmapFont, private val sb: SpriteBatch, private val shapes: ShapeRenderer, val svc: LobbyService) {
    private class Box(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float, val c: Color)
    private class Txt(val s: String, val x: Float, val y: Float, val scale: Float, val c: Color, val bw: Float, val bh: Float)

    private val layout = GlyphLayout()
    private val boxes = ArrayList<Box>()
    private val texts = ArrayList<Txt>()
    private var toast = ""
    private var toastT = 0f
    private var scroll = 0
    private var fscroll = 0
    private var screen = 0      // 0 none, 1 store, 2 luck royale, 3 sohel pass, 4 missions, 5 events, 6 messages
    private var mode = 0
    private var t = 0f
    private var spinT = 0f
    private var spinResult = ""
    private var spinPending = ""

    @Volatile private var pendingName: String? = null
    @Volatile private var pendingFriend: String? = null

    private val white = Color(1f, 1f, 1f, 1f)
    private val dim = Color(0.66f, 0.7f, 0.78f, 1f)
    private val dark = Color(0.05f, 0.06f, 0.1f, 0.85f)
    private val panel = Color(0.09f, 0.08f, 0.17f, 0.96f)
    private val row = Color(0.17f, 0.15f, 0.29f, 1f)
    private val btn = Color(0.24f, 0.22f, 0.42f, 1f)
    private val green = Color(0.2f, 0.7f, 0.3f, 1f)
    private val red = Color(0.8f, 0.2f, 0.25f, 1f)
    private val gold = Color(1f, 0.74f, 0.1f, 1f)
    private val cyan = Color(0.25f, 0.8f, 1f, 1f)
    private val locked = Color(0.14f, 0.13f, 0.22f, 1f)
    private val black = Color(0.05f, 0.05f, 0.05f, 1f)

    fun say(s: String) { toast = s; toastT = 3.2f }

    private fun bid(id: Int) = if (screen == 0) id else -1
    private fun box(id: Int, x: Float, y: Float, w: Float, h: Float, c: Color) { boxes.add(Box(id, x, y, w, h, c)) }
    private fun text(s: String, x: Float, y: Float, scale: Float, c: Color) { texts.add(Txt(s, x, y, scale, c, 0f, 0f)) }
    private fun ctext(s: String, x: Float, y: Float, w: Float, h: Float, scale: Float, c: Color) { texts.add(Txt(s, x, y, scale, c, w, h)) }
    private fun button(id: Int, s: String, x: Float, y: Float, w: Float, h: Float, c: Color, scale: Float, tc: Color = white) {
        box(id, x, y, w, h, c)
        ctext(s, x, y, w, h, scale, tc)
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
            if (spinT <= 0f) { spinResult = spinPending; say(spinPending) }
        }

        boxes.clear()
        texts.clear()
        val m = w * 0.02f
        val friends = svc.friends()
        val unread = svc.unread()

        // ---- top bar ----
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
        text(if (svc.connected) "ONLINE" else "OFFLINE", w * 0.9f, h * 0.025f, 1.1f, if (svc.connected) green else dim)

        // ---- left menu ----
        val names = arrayOf("STORE", "LUCK ROYALE", "SOHEL PASS", "MISSIONS", "EVENTS")
        for (i in 0..4) {
            val by = h * 0.15f + i * h * 0.105f
            button(bid(30 + i), names[i], m, by, w * 0.19f, h * 0.088f, dark, 1.4f, gold)
            box(-1, m, by, 5f, h * 0.088f, gold)
            val dot = (i == 3 && svc.missionsReady()) || (i == 4 && svc.loginReady()) || (i == 1 && svc.spinIsFree())
            if (dot) box(-1, m + w * 0.19f - 18f, by + 6f, 12f, 12f, red)
        }
        button(bid(40), if (unread > 0) "MESSAGES ($unread)" else "MESSAGES", m, h * 0.84f, w * 0.19f, h * 0.088f, dark, 1.2f, white)

        // ---- name card ----
        button(bid(3), svc.myName() + "   " + svc.myId(), w * 0.36f, h * 0.90f, w * 0.28f, h * 0.075f, dark, 1.1f, dim)

        // ---- friends column (right) ----
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

        // ---- modes + START ----
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

        if (screen != 0) buildScreen(w, h)

        if (toastT > 0f) {
            box(-1, w * 0.2f, h * 0.93f - 4f, w * 0.6f, h * 0.06f, Color(0f, 0f, 0f, 0.85f))
            ctext(toast, w * 0.2f, h * 0.93f - 4f, w * 0.6f, h * 0.06f, 1.1f, white)
        }

        // ---- render ----
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        for (b in boxes) {
            shapes.setColor(b.c)
            shapes.rect(b.x, h - b.y - b.h, b.w, b.h)
        }
        shapes.end()
        sb.begin()
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
        when {
            hit == 1 -> return true
            hit == 2 -> askFriend()
            hit == 3 -> askName()
            hit == 4 -> fscroll = maxOf(0, fscroll - 1)
            hit == 5 -> fscroll = minOf(maxOf(0, friends.size - maxF), fscroll + 1)
            hit == 10 -> mode = 0
            hit == 11 || hit == 12 -> say("Duo and Squad need the online server - coming soon")
            hit in 30..35 -> { screen = hit - 29; scroll = 0 }
            hit == 40 -> { screen = 6; scroll = 0; svc.markAllRead() }
            hit == 90 -> screen = 0
            hit == 91 -> scroll = maxOf(0, scroll - 1)
            hit == 92 -> scroll += 1
            hit in 100..199 -> if (hit - 100 < friends.size) say(svc.invite(friends[hit - 100].id))
            hit in 200..299 -> if (hit - 200 < friends.size) {
                val f = friends[hit - 200]
                svc.removeFriend(f.id)
                say("${f.name} removed")
            }
            hit in 300..399 -> {
                val it = svc.storeItems()[hit - 300]
                say(if (it.owned) svc.equip(it.id) else svc.buy(it.id))
            }
            hit == 400 -> if (spinT <= 0f) {
                val r = svc.spin()
                if (r.startsWith("You won")) { spinPending = r; spinT = 1.4f; spinResult = "" } else say(r)
            }
            hit == 510 -> say(svc.buyPremium())
            hit in 520..539 -> say(svc.claimPass(hit - 519, false))
            hit in 540..559 -> say(svc.claimPass(hit - 539, true))
            hit in 700..703 -> say(svc.claimMission(hit - 700))
            hit == 800 -> say(svc.claimLogin())
        }
        return false
    }

    private fun buildScreen(w: Float, h: Float) {
        val px = w * 0.22f
        val py = h * 0.12f
        val pw = w * 0.56f
        val ph = h * 0.76f
        box(-1, 0f, 0f, w, h, Color(0f, 0f, 0f, 0.5f))
        box(-1, px, py, pw, ph, panel)
        val titles = arrayOf("", "STORE", "LUCK ROYALE", "SOHEL PASS", "MISSIONS", "EVENTS", "MESSAGES")
        text(titles[screen], px + 20f, py + 14f, 2f, gold)
        button(90, "X", px + pw - h * 0.08f - 10f, py + 8f, h * 0.08f, h * 0.07f, red, 1.4f)
        val cy = py + h * 0.1f
        val rowH = h * 0.1f
        val rx = px + 12f
        val rw = pw - 24f
        when (screen) {
            1 -> {
                val its = svc.storeItems()
                scroll = minOf(scroll, maxOf(0, its.size - 5))
                for (k in 0 until 5) {
                    val i = scroll + k
                    if (i >= its.size) break
                    val it = its[i]
                    val ry = cy + k * rowH
                    box(-1, rx, ry, rw, rowH - 6f, row)
                    text(it.name, rx + 14f, ry + 8f, 1.4f, white)
                    text(if (it.price == 0) "FREE" else "${it.price} " + (if (it.dia) "Diamonds" else "Gold"), rx + 14f, ry + rowH * 0.5f, 1.0f, if (it.dia) cyan else gold)
                    val lab = if (!it.owned) "BUY" else if (it.equipped) "EQUIPPED" else "EQUIP"
                    button(300 + i, lab, rx + rw - w * 0.14f - 8f, ry + 6f, w * 0.14f, rowH - 18f, if (!it.owned) green else if (it.equipped) locked else btn, 1.2f)
                }
                scrollButtons(px, py, pw, ph, h)
                text("Skins are saved to your profile; showing them in match needs the skin models.", px + 20f, py + ph - h * 0.05f, 0.8f, dim)
            }
            2 -> {
                box(-1, rx, cy, rw, h * 0.34f, row)
                val shown = if (spinT > 0f) (arrayOf("50 Gold", "250 Gold", "5 Diamonds", "100 Gold", "20 Diamonds", "500 Gold", "100 Diamonds")[((t * 14f).toInt()) % 7]) else if (spinResult.isEmpty()) "Spin to win!" else spinResult.removePrefix("You won ")
                ctext(shown, rx, cy, rw, h * 0.34f, 2.6f, if (spinT > 0f) dim else gold)
                val free = svc.spinIsFree()
                button(400, if (free) "FREE SPIN" else "SPIN - 20 DIAMONDS", px + pw * 0.2f, cy + h * 0.38f, pw * 0.6f, h * 0.11f, if (free) green else btn, 1.8f)
                ctext("Prizes: 50 / 100 / 250 / 500 Gold  -  5 / 20 / 100 Diamonds.  One free spin every day.", px, py + ph - h * 0.1f, pw, h * 0.06f, 0.9f, dim)
            }
            3 -> {
                val rows = svc.passRows()
                text("Level ${svc.passLevel()} / 20", px + 20f, cy - 2f, 1.3f, white)
                box(-1, px + 20f + w * 0.1f, cy + 6f, w * 0.2f, 12f, locked)
                box(-1, px + 20f + w * 0.1f, cy + 6f, w * 0.2f * svc.passProgress(), 12f, green)
                if (svc.passPremium()) text("PREMIUM ACTIVE", px + pw - w * 0.2f, cy - 2f, 1.1f, gold)
                else button(510, "UNLOCK PREMIUM - 150 DIA", px + pw - w * 0.25f - 70f, cy - 6f, w * 0.25f, h * 0.06f, gold, 1.0f, black)
                val top = cy + h * 0.07f
                scroll = minOf(scroll, maxOf(0, rows.size - 4))
                for (k in 0 until 4) {
                    val i = scroll + k
                    if (i >= rows.size) break
                    val r = rows[i]
                    val ry = top + k * rowH
                    box(-1, rx, ry, rw, rowH - 6f, row)
                    text("LV ${r.level}", rx + 14f, ry + 12f, 1.5f, if (r.unlocked) gold else dim)
                    text(r.free, rx + w * 0.07f, ry + rowH * 0.12f, 1.0f, white)
                    button(520 + r.level - 1, if (r.freeDone) "CLAIMED" else "CLAIM", rx + w * 0.07f, ry + rowH * 0.42f, w * 0.12f, rowH * 0.45f, if (r.unlocked && !r.freeDone) green else locked, 0.9f)
                    text("PREMIUM: " + r.prem, rx + w * 0.26f, ry + rowH * 0.12f, 1.0f, cyan)
                    button(540 + r.level - 1, if (r.premDone) "CLAIMED" else "CLAIM", rx + w * 0.26f, ry + rowH * 0.42f, w * 0.12f, rowH * 0.45f, if (r.unlocked && !r.premDone && svc.passPremium()) green else locked, 0.9f)
                }
                scrollButtons(px, py, pw, ph, h)
                text("Earn pass XP by playing matches and finishing missions.", px + 20f, py + ph - h * 0.05f, 0.8f, dim)
            }
            4 -> {
                val ms = svc.missions()
                for ((i, mi) in ms.withIndex()) {
                    val ry = cy + i * rowH
                    box(-1, rx, ry, rw, rowH - 6f, row)
                    text(mi.title, rx + 14f, ry + 8f, 1.4f, white)
                    text("${mi.progress}/${mi.goal}   Reward: ${mi.reward} + 40 XP", rx + 14f, ry + rowH * 0.5f, 1.0f, gold)
                    val done = mi.progress >= mi.goal
                    button(700 + i, if (mi.claimed) "CLAIMED" else if (done) "CLAIM" else "IN PROGRESS", rx + rw - w * 0.15f - 8f, ry + 6f, w * 0.15f, rowH - 18f, if (done && !mi.claimed) green else locked, 1.0f)
                }
                text("Daily missions reset every day. Finish a match to count progress.", px + 20f, py + ph - h * 0.05f, 0.8f, dim)
            }
            5 -> {
                box(-1, rx, cy, rw, rowH * 1.3f, row)
                text("DAILY LOGIN", rx + 14f, cy + 8f, 1.6f, gold)
                text("Open the game every day: 100 Gold + 20 XP", rx + 14f, cy + rowH * 0.6f, 1.0f, white)
                button(800, if (svc.loginReady()) "CLAIM" else "CLAIMED", rx + rw - w * 0.15f - 8f, cy + 10f, w * 0.15f, rowH - 10f, if (svc.loginReady()) green else locked, 1.2f)
                box(-1, rx, cy + rowH * 1.4f, rw, rowH, row)
                text("WINTER FESTIVAL", rx + 14f, cy + rowH * 1.4f + 8f, 1.5f, white)
                text("Coming soon - limited-time skins and double XP weekend.", rx + 14f, cy + rowH * 1.4f + rowH * 0.5f, 1.0f, dim)
                box(-1, rx, cy + rowH * 2.5f, rw, rowH, row)
                text("RANKED SEASON 1", rx + 14f, cy + rowH * 2.5f + 8f, 1.5f, white)
                text("Coming soon - needs the online server.", rx + 14f, cy + rowH * 2.5f + rowH * 0.5f, 1.0f, dim)
            }
            6 -> {
                val ms = svc.messages()
                scroll = minOf(scroll, maxOf(0, ms.size - 5))
                if (ms.isEmpty()) ctext("No messages yet", px, cy, pw, h * 0.2f, 1.2f, dim)
                for (k in 0 until 5) {
                    val i = scroll + k
                    if (i >= ms.size) break
                    val ry = cy + k * rowH
                    box(-1, rx, ry, rw, rowH - 6f, row)
                    text(ms[i].time, rx + 14f, ry + 6f, 0.9f, dim)
                    text(ms[i].text.take(80), rx + 14f, ry + rowH * 0.4f, 1.0f, white)
                }
                scrollButtons(px, py, pw, ph, h)
                text("Friend chat arrives with the online server.", px + 20f, py + ph - h * 0.05f, 0.8f, dim)
            }
        }
    }

    private fun scrollButtons(px: Float, py: Float, pw: Float, ph: Float, h: Float) {
        button(91, "^", px + pw - h * 0.17f - 20f, py + 8f, h * 0.07f, h * 0.07f, btn, 1.2f)
        button(92, "v", px + pw - h * 0.17f - 20f - h * 0.08f, py + 8f, h * 0.07f, h * 0.07f, btn, 1.2f)
    }
}
