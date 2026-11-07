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

/**
 * Everything the lobby needs from "the outside world". Today it is answered by [LocalLobbyService]
 * (stored on the phone, no network). When the server exists, write a second class that implements this
 * interface (REST / websocket calls) and hand it to Lobby instead - the lobby screen does not change.
 */
interface LobbyService {
    val connected: Boolean
    fun myName(): String
    fun myId(): String
    fun setMyName(n: String)
    fun friends(): List<Friend>
    fun addFriend(idOrName: String): String      // returns a message to show the player
    fun removeFriend(id: String)
    fun invite(id: String): String               // returns a message to show the player
}

/** Standalone (offline) implementation: profile and friends are saved in the phone's preferences. */
class LocalLobbyService : LobbyService {
    private val prefs = Gdx.app.getPreferences("sohelbr_lobby")
    private val list = ArrayList<Friend>()
    override val connected = false

    init {
        if (!prefs.contains("id")) {
            prefs.putString("id", "SB" + (100000 + java.util.Random().nextInt(900000)))
            prefs.putString("name", "Player")
            prefs.flush()
        }
        val raw = prefs.getString("friends", "")
        for (e in raw.split(";")) {
            val p = e.split(",")
            if (p.size == 2 && p[0].isNotEmpty()) list.add(Friend(p[0], p[1], 0))
        }
    }

    private fun save() {
        prefs.putString("friends", list.joinToString(";") { it.id + "," + it.name })
        prefs.flush()
    }

    private fun clean(s: String) = s.replace(",", " ").replace(";", " ").trim().take(14)

    override fun myName(): String = prefs.getString("name", "Player")
    override fun myId(): String = prefs.getString("id", "SB000000")
    override fun setMyName(n: String) {
        val c = clean(n)
        if (c.isNotEmpty()) {
            prefs.putString("name", c)
            prefs.flush()
        }
    }

    override fun friends(): List<Friend> = list

    override fun addFriend(idOrName: String): String {
        val c = clean(idOrName)
        if (c.isEmpty()) return "Type a friend's ID or name"
        val id = if (c.uppercase().startsWith("SB") && c.length >= 5) c.uppercase().replace(" ", "") else "N-" + c.lowercase().replace(" ", "")
        if (id == myId()) return "That's your own ID"
        if (list.any { it.id == id }) return "$c is already in your list"
        if (list.size >= 50) return "Friend list is full"
        list.add(Friend(id, c, 0))
        save()
        return "$c added"
    }

    override fun removeFriend(id: String) {
        list.removeAll { it.id == id }
        save()
    }

    override fun invite(id: String): String = "Online play is not connected yet - invites will work when the server is ready"
}

/** The main menu shown before a match: character, profile, friends list, mode and PLAY. */
class Lobby(private val font: BitmapFont, private val sb: SpriteBatch, private val shapes: ShapeRenderer, val svc: LobbyService) {
    private class Box(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float, val c: Color)
    private class Txt(val s: String, val x: Float, val y: Float, val scale: Float, val c: Color, val bw: Float, val bh: Float)

    private val layout = GlyphLayout()
    private val boxes = ArrayList<Box>()
    private val texts = ArrayList<Txt>()
    private var toast = ""
    private var toastT = 0f
    private var scroll = 0
    private var mode = 0
    private var t = 0f

    @Volatile private var pendingName: String? = null
    @Volatile private var pendingFriend: String? = null

    private val white = Color(1f, 1f, 1f, 1f)
    private val dim = Color(0.62f, 0.68f, 0.75f, 1f)
    private val panel = Color(0.08f, 0.11f, 0.16f, 0.92f)
    private val row = Color(0.14f, 0.18f, 0.24f, 1f)
    private val btn = Color(0.2f, 0.28f, 0.38f, 1f)
    private val green = Color(0.2f, 0.7f, 0.3f, 1f)
    private val red = Color(0.75f, 0.2f, 0.2f, 1f)
    private val gold = Color(0.95f, 0.65f, 0.15f, 1f)
    private val locked = Color(0.13f, 0.16f, 0.2f, 1f)

    private fun box(id: Int, x: Float, y: Float, w: Float, h: Float, c: Color) { boxes.add(Box(id, x, y, w, h, c)) }
    private fun text(s: String, x: Float, y: Float, scale: Float, c: Color) { texts.add(Txt(s, x, y, scale, c, 0f, 0f)) }
    /** text centred inside a w x h rectangle at (x, y) */
    private fun ctext(s: String, x: Float, y: Float, w: Float, h: Float, scale: Float, c: Color) { texts.add(Txt(s, x, y, scale, c, w, h)) }
    private fun button(id: Int, s: String, x: Float, y: Float, w: Float, h: Float, c: Color, scale: Float, tc: Color = white) {
        box(id, x, y, w, h, c)
        ctext(s, x, y, w, h, scale, tc)
    }

    fun say(s: String) { toast = s; toastT = 3.2f }

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

    /** draws the lobby over the 3D character; returns true on the frame PLAY is pressed */
    fun draw(w: Float, h: Float, dt: Float): Boolean {
        t += dt
        if (toastT > 0f) toastT -= dt
        pendingName?.let { svc.setMyName(it); pendingName = null }
        pendingFriend?.let { say(svc.addFriend(it)); pendingFriend = null }

        boxes.clear()
        texts.clear()
        val m = w * 0.03f
        val friends = svc.friends()

        // title + connection pill
        text("SOHELBR", m, h * 0.04f, 3f, white)
        text("BATTLE ROYALE", m, h * 0.04f + 62f, 1.2f, gold)
        box(-1, w * 0.56f, h * 0.035f, w * 0.41f, h * 0.075f, panel)
        box(-1, w * 0.56f + 16f, h * 0.035f + h * 0.0375f - 7f, 14f, 14f, if (svc.connected) green else red)
        ctext(if (svc.connected) "ONLINE" else "OFFLINE - STANDALONE", w * 0.56f + 30f, h * 0.035f, w * 0.41f - 30f, h * 0.075f, 1.3f, white)

        // profile card (tap to rename)
        val cx = m
        val cy = h * 0.79f
        val cw = w * 0.46f
        val ch = h * 0.18f
        box(3, cx, cy, cw, ch, panel)
        text(svc.myName(), cx + 18f, cy + 12f, 1.9f, white)
        text("ID: " + svc.myId(), cx + 18f, cy + ch * 0.55f, 1.2f, gold)
        text("tap to change name", cx + 18f, cy + ch * 0.55f + 34f, 0.9f, dim)

        // team slots
        text("TEAM", w * 0.43f, h * 0.15f, 1.2f, dim)
        for (i in 0..2) {
            button(20 + i, "+ INVITE", w * 0.43f, h * 0.20f + i * h * 0.17f, w * 0.115f, h * 0.14f, btn, 1.0f, dim)
        }

        // friends panel
        val px = w * 0.56f
        val py = h * 0.14f
        val pw = w * 0.41f
        val ph = h * 0.56f
        box(-1, px, py, pw, ph, panel)
        text("FRIENDS (${friends.size})", px + 16f, py + 14f, 1.5f, white)
        val hb = h * 0.07f
        button(2, "+ ADD", px + pw - w * 0.105f - 8f, py + 6f, w * 0.105f, hb, green, 1.2f)
        button(5, "v", px + pw - w * 0.105f - 16f - hb, py + 6f, hb, hb, btn, 1.2f)
        button(4, "^", px + pw - w * 0.105f - 24f - hb * 2f, py + 6f, hb, hb, btn, 1.2f)
        val rowH = h * 0.085f
        val maxRows = 5
        if (scroll > maxOf(0, friends.size - maxRows)) scroll = maxOf(0, friends.size - maxRows)
        if (friends.isEmpty()) {
            ctext("No friends yet. Tap + ADD and type a friend's ID.", px, py + h * 0.15f, pw, h * 0.2f, 1.1f, dim)
        }
        for (k in 0 until maxRows) {
            val i = scroll + k
            if (i >= friends.size) break
            val f = friends[i]
            val ry = py + h * 0.10f + k * rowH
            box(-1, px + 8f, ry, pw - 16f, rowH - 6f, row)
            box(-1, px + 20f, ry + (rowH - 6f) / 2f - 6f, 12f, 12f, if (f.status == 1) green else if (f.status == 2) gold else Color.GRAY)
            text(f.name, px + 44f, ry + 6f, 1.3f, white)
            text(f.id + "  " + (if (f.status == 1) "Online" else if (f.status == 2) "In match" else "Offline"), px + 44f, ry + (rowH - 6f) * 0.55f, 0.85f, dim)
            button(100 + i, "INVITE", px + pw - 16f - w * 0.1f - hb - 8f, ry + 4f, w * 0.1f, rowH - 14f, btn, 1.0f)
            button(200 + i, "X", px + pw - 16f - hb, ry + 4f, hb, rowH - 14f, red, 1.1f)
        }

        // modes
        val tw = (pw - 32f) / 3f
        val names = arrayOf("SOLO", "DUO", "SQUAD")
        for (i in 0..2) {
            val sel = i == mode
            button(10 + i, names[i], px + 8f + i * (tw + 8f), h * 0.725f, tw, h * 0.08f, if (sel) gold else locked, 1.4f, if (i == 0 || sel) white else dim)
        }

        // play
        val pulse = 0.85f + 0.15f * sin(t * 3f)
        button(1, "PLAY", px, h * 0.835f, pw, h * 0.135f, Color(0.15f * pulse, 0.75f * pulse, 0.3f * pulse, 1f), 3f)

        if (toastT > 0f) {
            box(-1, w * 0.2f, h * 0.93f - 4f, w * 0.6f, h * 0.06f, Color(0f, 0f, 0f, 0.8f))
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
            hit == 4 -> scroll = maxOf(0, scroll - 1)
            hit == 5 -> scroll = minOf(maxOf(0, friends.size - maxRows), scroll + 1)
            hit == 10 -> mode = 0
            hit == 11 || hit == 12 -> say("Duo and Squad need the online server - coming soon")
            hit in 20..22 -> say("Team invites need the online server - coming soon")
            hit in 100..199 -> if (hit - 100 < friends.size) say(svc.invite(friends[hit - 100].id))
            hit in 200..299 -> if (hit - 200 < friends.size) {
                val f = friends[hit - 200]
                svc.removeFriend(f.id)
                say("${f.name} removed")
            }
        }
        return false
    }
}
