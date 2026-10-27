package com.sohelbr.myapp

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader
import com.badlogic.gdx.graphics.g3d.model.Node
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader
import com.badlogic.gdx.graphics.g3d.utils.AnimationController
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.g3d.utils.TextureProvider
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Intersector
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.badlogic.gdx.math.collision.Ray
import com.badlogic.gdx.utils.JsonReader
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Bot(val gender: Int, val outfit: Int, val pos: Vector3, var hp: Float = 100f) {
    val target = Vector3()
    var retarget = 0f
    var yaw = 0f
    var anim = 0f
    var shooting = false
    var moving = false
    var vel = 0f
    var armedT = 0f
    var actor: Actor? = null
    var weapon = 2
    var back = -1
    var deadT = 0f
    var rival: Bot? = null
    var scanT = 0f
}

class Actor(val inst: ModelInstance, val ctrl: AnimationController, val g: Int) {
    var cur = ""
    var hand: Node? = null
    var spine: Node? = null
}

class ActorMeta(val handAimInv: Matrix4, val G: Vector3, val spineInv: Matrix4, val B: Vector3)

class Block(val x: Float, val z: Float, val hw: Float, val hd: Float, val h: Float) {
    val box = BoundingBox(Vector3(x - hw, 0f, z - hd), Vector3(x + hw, h, z + hd))
}

class Part(val inst: ModelInstance, val ox: Float, val oy: Float, val oz: Float, val ry: Float, val rx: Float)
class Prop(val parts: ArrayList<Part>)
class WDef(
    val name: String, val dmg: Float, val cd: Float, val mag: Int, val reload: Float,
    val range: Float, val auto: Boolean, val viewScale: Float, val muzzleY: Float, val muzzleZ: Float
)
class Slot(var type: Int, var mag: Int)
class Loot(var kind: Int, var x: Float, var z: Float, var mag: Int)

class BattleGame : ApplicationAdapter() {
    // ---- tuning ----
    // 1f = real time (20 min match). Set 10f to test the whole zone cycle in 2 minutes.
    private val timeScale = 1f
    private val botCount = 30
    private val startRadius = 700f
    private val zoneRadii = floatArrayOf(420f, 250f, 140f, 70f, 30f, 0f)
    private val holdTimes = floatArrayOf(220f, 150f, 120f, 90f, 70f, 60f)
    private val shrinkTimes = floatArrayOf(120f, 100f, 90f, 70f, 50f, 60f)
    private val zoneDamage = floatArrayOf(1f, 2f, 4f, 6f, 8f, 10f)

    // rifle muzzle of a bot in the aiming pose (bot-local: x left, y up, z forward), male / female

    private val mb = ModelBuilder()
    private val attrs = (Usage.Position or Usage.Normal).toLong()

    private val dark = Color(0.16f, 0.16f, 0.18f, 1f)
    private val gray = Color(0.36f, 0.37f, 0.40f, 1f)
    private val black = Color(0.05f, 0.05f, 0.06f, 1f)
    private val wood = Color(0.45f, 0.28f, 0.14f, 1f)
    private val skin = Color(0.93f, 0.72f, 0.58f, 1f)
    private val sleeve = Color(0.18f, 0.30f, 0.18f, 1f)

    private lateinit var cam: PerspectiveCamera
    private lateinit var gunCam: PerspectiveCamera
    private lateinit var batch: ModelBatch
    private lateinit var env: Environment
    private lateinit var shapes: ShapeRenderer
    private lateinit var sb: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var botModel: Model
    private lateinit var boxInst: ModelInstance
    private lateinit var flashInst: ModelInstance
    private lateinit var zoneInst: ModelInstance
    private lateinit var targetInst: ModelInstance
    private lateinit var flashProp: Prop
    private val layout = GlyphLayout()
    private val models = ArrayList<Model>()
    private val buildings = ArrayList<ModelInstance>()
    private val blocks = ArrayList<Block>()
    private val bots = ArrayList<Bot>()
    private val corpses = ArrayList<Bot>()
    private val pendingKill = ArrayList<Bot>()
    private val weapons = ArrayList<WDef>()
    private val weaponProps = ArrayList<Prop>()
    private val tpProps = ArrayList<Prop>()      // guns without the first-person arms
    private val meta = arrayOfNulls<ActorMeta>(2)
    private val botModels = arrayOfNulls<Model>(2)
    private val holdScale = floatArrayOf(1.0f, 0.9f, 0.85f, 0.75f)
    private var pActor: Actor? = null
    private var pArmedT = 0f
    // grip point (in gun space) and distance between the two hands for each gun
    private val gripY = floatArrayOf(-0.055f, -0.09f, -0.10f, -0.10f)
    private val gripZ = floatArrayOf(0.045f, 0.09f, 0.08f, 0.14f)
    private val holdD = floatArrayOf(0.18f, 0.33f, 0.48f, 0.50f)
    private val itemProps = ArrayList<Prop>()
    private val beams = ArrayList<ModelInstance>()
    private val loot = ArrayList<Loot>()
    private val taps = ArrayList<FloatArray>()
    private val rnd = Random()

    // human characters: [gender][outfit][frame]  frames 0..3 = run cycle, 4 = aiming
    private var loadIdx = 0
    private val loadTotal = 4
    private var humanLoader: G3dModelLoader? = null
    private var humanProvider: TextureProvider? = null
    private var humansOk = false
    private val texCache = HashMap<String, Texture>()

    private val slots = arrayOf(Slot(0, 12), Slot(-1, 0))
    private var cur = 0
    private var reserve = 36
    private var armor = 0f
    private var reloading = false
    private var reloadT = 0f
    private var recoil = 0f
    private var firingLast = false
    private var hitMark = 0f
    private var hitHead = false
    private var gameTime = 0f
    private var walkT = 0f
    private var msg = ""
    private var msgT = 0f
    private var nearLoot: Loot? = null
    private var tpp = true      // third-person camera (like Free Fire); CAM button toggles
    private var pAnim = 0f
    private var medkits = 0
    private var sprint = false
    private var jumpV = 0f
    private var pSpeed = 7f
    private var pYaw = 180f
    private var pMoving = false
    private var pMoveYaw = 0f

    // where the third-person camera sits: behind + over the right shoulder of the player
    private fun tpCamPos(eye: Vector3): Vector3 {
        val back = Vector3(cam.direction).scl(-1f)
        val rgt = Vector3(cam.direction).crs(Vector3.Y).nor()
        val off = Vector3(back).scl(3.4f).add(rgt.scl(0.7f)).add(0f, 0.5f, 0f)
        val len = off.len()
        val dirN = Vector3(off).nor()
        val wd = wallDist(Ray(Vector3(eye), dirN), len)
        val use = minOf(len, wd - 0.4f).coerceAtLeast(0.5f)
        val p = Vector3(eye).add(dirN.scl(use))
        if (p.y < 0.4f) p.y = 0.4f
        return p
    }

    private var yaw = 0f
    private var pitch = 0f
    private var hp = 100f
    private var kills = 0
    private var cooldown = 0f
    private var flash = 0f
    private var hurtFlash = 0f

    private var matchTime = 0f
    private var phase = 0
    private var phaseTime = 0f
    private var shrinking = false
    private var won = false
    private var zoneCx = 0f
    private var zoneCz = 0f
    private var zoneR = startRadius
    private var fromCx = 0f
    private var fromCz = 0f
    private var fromR = startRadius
    private var toCx = 0f
    private var toCz = 0f
    private var toR = 0f

    override fun create() {
        val shCfg = DefaultShader.Config()
        shCfg.numBones = 24
        batch = ModelBatch(DefaultShaderProvider(shCfg))
        shapes = ShapeRenderer()
        sb = SpriteBatch()
        font = BitmapFont()
        font.data.setScale(3f)

        env = Environment()
        env.set(ColorAttribute(ColorAttribute.AmbientLight, 0.6f, 0.6f, 0.6f, 1f))
        env.add(DirectionalLight().set(1f, 1f, 1f, -0.5f, -1f, -0.3f))

        cam = PerspectiveCamera(67f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        cam.near = 0.5f
        cam.far = 1500f

        gunCam = PerspectiveCamera(55f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        gunCam.near = 0.05f
        gunCam.far = 20f
        gunCam.update()

        val ground = mb.createBox(1000f, 0.2f, 1000f,
            Material(ColorAttribute.createDiffuse(Color.FOREST)), attrs)
        models.add(ground)
        buildings.add(ModelInstance(ground).also { it.transform.setToTranslation(0f, -0.1f, 0f) })

        val r = Random(1)
        for (i in 0 until 200) {
            val w = 3f + r.nextFloat() * 8f
            val h = 3f + r.nextFloat() * 12f
            val d = 3f + r.nextFloat() * 8f
            val color = Color(0.4f + r.nextFloat() * 0.5f, 0.4f + r.nextFloat() * 0.5f, 0.4f + r.nextFloat() * 0.5f, 1f)
            val m = mb.createBox(w, h, d, Material(ColorAttribute.createDiffuse(color)), attrs)
            models.add(m)
            val x = (r.nextFloat() - 0.5f) * 900f
            val z = (r.nextFloat() - 0.5f) * 900f
            buildings.add(ModelInstance(m).also { it.transform.setToTranslation(x, h / 2f, z) })
            blocks.add(Block(x, z, w / 2f, d / 2f, h))
        }

        // red box = fallback if the character files are missing
        botModel = mb.createBox(1f, 2f, 1f, Material(ColorAttribute.createDiffuse(Color.RED)), attrs)
        models.add(botModel)
        boxInst = ModelInstance(botModel)

        val flashModel = mb.createBox(0.22f, 0.22f, 0.22f,
            Material(ColorAttribute.createDiffuse(1f, 0.9f, 0.3f, 1f), ColorAttribute.createEmissive(1f, 0.8f, 0.2f, 1f)), attrs)
        models.add(flashModel)
        flashInst = ModelInstance(flashModel)

        val zoneModel = openCylinder(mb, 64,
            Material(ColorAttribute.createDiffuse(0.2f, 0.4f, 1f, 1f), BlendingAttribute(0.3f), IntAttribute.createCullFace(0)),
            attrs)
        val targetModel = openCylinder(mb, 64,
            Material(ColorAttribute.createDiffuse(1f, 1f, 1f, 1f), BlendingAttribute(0.12f), IntAttribute.createCullFace(0)),
            attrs)
        models.add(zoneModel)
        models.add(targetModel)
        zoneInst = ModelInstance(zoneModel)
        targetInst = ModelInstance(targetModel)

        buildWeapons()
        buildItems()
        buildBeams()

        Gdx.input.setInputProcessor(object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                taps.add(floatArrayOf(screenX.toFloat(), screenY.toFloat()))
                return false
            }
        })

        restart()
        resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    // ------------------------------------------------------------------
    // Human characters (male / female soldiers from your glTF files)
    // ------------------------------------------------------------------
    private fun getTex(name: String): Texture {
        return texCache.getOrPut(name) {
            val t = Texture(Gdx.files.internal(name), true)
            t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
            t
        }
    }

    // loads one thing per call so the game can show a loading bar (no freezing)
    private fun loadModelFile(file: String): Model {
        val m = humanLoader!!.loadModel(Gdx.files.internal(file), humanProvider)
        models.add(m)
        return m
    }

    private fun loadStep() {
        try {
            when (loadIdx) {
                0 -> {
                    humanLoader = G3dModelLoader(JsonReader())
                    humanProvider = object : TextureProvider {
                        override fun load(fileName: String): Texture = getTex(fileName)
                    }
                    val root = JsonReader().parse(Gdx.files.internal("bot_meta.json"))
                    for (g in 0..1) {
                        val j = root.get(if (g == 0) "m" else "f")
                        val hand = Matrix4(j.get("hand").asFloatArray())
                        val spine = Matrix4(j.get("spine").asFloatArray())
                        val gg = j.get("G").asFloatArray()
                        val bb = j.get("B").asFloatArray()
                        meta[g] = ActorMeta(hand.inv(), Vector3(gg[0], gg[1], gg[2]), spine.inv(), Vector3(bb[0], bb[1], bb[2]))
                    }
                    botModels[0] = loadModelFile("bot_m.g3dj")
                }
                1 -> botModels[1] = loadModelFile("bot_f.g3dj")
                2 -> {
                    for (g in arrayOf("m", "f")) for (o in 0..2) getTex("bot_" + g + "_" + o + ".jpg")
                }
                3 -> {
                    humansOk = true
                    for (b in bots) if (b.actor == null) b.actor = makeActor(b.gender, b.outfit)
                    pActor = makeActor(0, 0)
                }
            }
            loadIdx++
        } catch (e: Throwable) {
            Gdx.app.error("BattleGame", "Could not load character models", e)
            humansOk = false
            loadIdx = loadTotal
        }
    }

    private fun makeActor(g: Int, o: Int): Actor? {
        val m = botModels[g] ?: return null
        val inst = ModelInstance(m)
        inst.getMaterial("skin").set(TextureAttribute.createDiffuse(getTex("bot_" + (if (g == 0) "m" else "f") + "_" + o + ".jpg")))
        val a = Actor(inst, AnimationController(inst), g)
        a.hand = inst.getNode("hand_r", true)
        a.spine = inst.getNode("spine_03", true)
        a.ctrl.animate("Idle", -1, 1f, null, 0f)
        a.cur = "Idle"
        return a
    }

    private fun play(a: Actor, name: String, speed: Float, loops: Int = -1, fade: Float = 0.2f) {
        if (a.cur != name) {
            a.cur = name
            a.ctrl.animate(name, loops, speed, null, fade)
        } else {
            val d = a.ctrl.current
            if (d != null) d.speed = speed
        }
    }

    // pick idle / walk / jog / sprint (with the aiming upper body when armed) from the real speed
    private fun locoAnim(a: Actor, v: Float, armed: Boolean) {
        val pre = if (armed) "Aim" else ""
        if (v < 0.3f) play(a, if (armed) "AimStand" else "Idle", 1f)
        else if (v < 2.3f) play(a, pre + "Walk", (v / 1.5f).coerceIn(0.6f, 1.6f))
        else if (v < 6.3f) play(a, pre + "Jog", (v / 3.5f).coerceIn(0.7f, 1.7f))
        else play(a, pre + "Sprint", (v / 6f).coerceIn(0.8f, 1.8f))
    }

    private fun drawLoading(w: Float, h: Float) {
        Gdx.gl.glClearColor(0.05f, 0.08f, 0.05f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(0.25f, 0.25f, 0.25f, 1f)
        shapes.rect(w * 0.25f, h * 0.45f, w * 0.5f, 24f)
        shapes.setColor(0.3f, 0.9f, 0.3f, 1f)
        shapes.rect(w * 0.25f, h * 0.45f, w * 0.5f * loadIdx / loadTotal, 24f)
        shapes.end()
        sb.begin()
        centered("Loading soldiers...", h * 0.6f, w)
        sb.end()
    }

    // turn angle a toward b by at most step degrees (shortest way)
    private fun turnTo(a: Float, b: Float, step: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return if (abs(d) <= step) b else a + (if (d > 0f) step else -step)
    }

    private val vx = Vector3()
    private val vy = Vector3()
    private val vz = Vector3()

    private fun drawActor(a: Actor, x: Float, y: Float, z: Float, yawDeg: Float, held: Int, back: Int, armed: Boolean, fire: Boolean) {
        a.inst.transform.setToTranslation(x, y, z).rotate(Vector3.Y, yawDeg)
        batch.render(a.inst, env)
        carry(a, held, back, armed, fire)
    }

    // guns are slung on the back (like Free Fire); the current gun comes into the hands while shooting.
    // Everything follows the animated bones (hand_r and spine_03).
    private fun carry(a: Actor, held: Int, back: Int, armed: Boolean, fire: Boolean) {
        val mt = meta[a.g] ?: return
        val base = a.inst.transform
        var s1 = back
        var s2 = -1
        val hn = a.hand
        val sn = a.spine
        if (armed && held in 0..3 && hn != null) {
            val rel = Matrix4(hn.globalTransform).mul(mt.handAimInv)
            vz.set(0f, 0f, -1f)
            vy.set(0f, 1f, 0f)
            vx.set(vy).crs(vz)
            val s = holdScale[held]
            val gm = Matrix4(base).mul(rel).translate(mt.G).mul(Matrix4().set(vx, vy, vz, Vector3.Zero))
            gm.scale(s, s, s).translate(0f, -(gripY[held] + 0.02f), -gripZ[held])
            drawProp(tpProps[held], gm)
            if (fire) {
                val fb = Matrix4(gm).translate(0f, weapons[held].muzzleY, weapons[held].muzzleZ - 0.04f)
                drawProp(flashProp, fb)
            }
        } else {
            s1 = held
            s2 = back
        }
        if (sn != null) {
            val sRel = Matrix4(base).mul(Matrix4(sn.globalTransform).mul(mt.spineInv))
            if (s1 in 0..3) slung(sRel, mt.B, s1, 0)
            if (s2 in 0..3) slung(sRel, mt.B, s2, 1)
        }
    }

    private fun slung(base: Matrix4, B: Vector3, type: Int, slot: Int) {
        val bm = Matrix4(base)
        if (type == 0) {
            // pistol in a holster on the hip
            bm.translate(B.x - 0.22f + 0.44f * slot, B.y - 0.62f, B.z + 0.16f).rotate(Vector3.Y, 180f).scale(1.1f, 1.1f, 1.1f)
        } else {
            // rifle strap over the back: barrel up over the shoulder, side of the gun against the back
            val tilt = if (slot == 0) 10f else -10f
            val sc = if (type == 3) 0.7f else 0.8f
            bm.translate(B.x + (if (slot == 0) -0.03f else 0.03f), B.y - 0.12f, B.z - 0.04f - 0.05f * slot)
            bm.rotate(Vector3.Z, tilt).rotate(Vector3.X, 80f).rotate(Vector3.Z, 90f)
            bm.scale(sc, sc, sc).translate(0f, 0f, 0.2f)
        }
        drawProp(tpProps[type], bm)
    }

    // ------------------------------------------------------------------
    // 3D gun / item building (every gun is made from separate boxes)
    // ------------------------------------------------------------------
    private fun mk(list: ArrayList<Part>, w: Float, h: Float, d: Float, c: Color,
                   x: Float, y: Float, z: Float, ry: Float = 0f, rx: Float = 0f) {
        val m = mb.createBox(w, h, d, Material(ColorAttribute.createDiffuse(c)), attrs)
        models.add(m)
        list.add(Part(ModelInstance(m), x, y, z, ry, rx))
    }

    private fun mkGlow(list: ArrayList<Part>, w: Float, h: Float, d: Float, c: Color,
                       x: Float, y: Float, z: Float) {
        val m = mb.createBox(w, h, d,
            Material(ColorAttribute.createDiffuse(c), ColorAttribute.createEmissive(c)), attrs)
        models.add(m)
        list.add(Part(ModelInstance(m), x, y, z, 0f, 0f))
    }

    // right hand on the grip, forearm going down toward the camera
    private fun addRightArm(p: ArrayList<Part>, gy: Float, gz: Float) {
        mk(p, 0.085f, 0.09f, 0.09f, skin, 0f, gy + 0.02f, gz)
        mk(p, 0.085f, 0.085f, 0.55f, sleeve, 0.08f, gy - 0.06f, gz + 0.25f, 17f, 17f)
    }

    // left hand supporting the gun from below
    private fun addLeftArm(p: ArrayList<Part>, hy: Float, hz: Float) {
        mk(p, 0.085f, 0.085f, 0.12f, skin, 0f, hy, hz)
        mk(p, 0.085f, 0.085f, 0.60f, sleeve, -0.10f, hy - 0.12f, hz + 0.26f, -20f, 22f)
    }

    private fun buildWeapons() {
        // 0: PISTOL
        run {
            val acc = Color(1f, 0.85f, 0.2f, 1f)
            val p = ArrayList<Part>()
            mk(p, 0.05f, 0.04f, 0.24f, dark, 0f, 0f, -0.05f)
            mk(p, 0.055f, 0.06f, 0.26f, gray, 0f, 0.04f, -0.06f)
            mk(p, 0.058f, 0.012f, 0.26f, acc, 0f, 0.076f, -0.06f)
            mk(p, 0.025f, 0.025f, 0.05f, black, 0f, 0.04f, -0.205f)
            mk(p, 0.05f, 0.14f, 0.07f, black, 0f, -0.08f, 0.04f)
            mk(p, 0.04f, 0.02f, 0.07f, dark, 0f, -0.04f, -0.03f)
            mk(p, 0.015f, 0.02f, 0.015f, black, 0f, 0.088f, -0.17f)
            mk(p, 0.025f, 0.02f, 0.015f, black, 0f, 0.088f, 0.06f)
            tpProps.add(Prop(ArrayList(p)))
            addRightArm(p, -0.075f, 0.045f)
            weaponProps.add(Prop(p))
            weapons.add(WDef("PISTOL", 22f, 0.28f, 12, 1.2f, 150f, false, 1.5f, 0.04f, -0.23f))
        }
        // 1: SMG
        run {
            val acc = Color(0.3f, 1f, 0.4f, 1f)
            val p = ArrayList<Part>()
            mk(p, 0.07f, 0.10f, 0.32f, dark, 0f, 0f, -0.02f)
            mk(p, 0.075f, 0.012f, 0.33f, acc, 0f, 0.056f, -0.02f)
            mk(p, 0.05f, 0.06f, 0.16f, gray, 0f, 0.005f, -0.26f)
            mk(p, 0.022f, 0.022f, 0.10f, black, 0f, 0.005f, -0.39f)
            mk(p, 0.04f, 0.26f, 0.06f, dark, 0f, -0.17f, -0.04f)
            mk(p, 0.05f, 0.13f, 0.06f, black, 0f, -0.11f, 0.09f)
            mk(p, 0.035f, 0.05f, 0.18f, gray, 0f, 0.005f, 0.24f)
            mk(p, 0.05f, 0.10f, 0.03f, black, 0f, -0.01f, 0.34f)
            mk(p, 0.03f, 0.035f, 0.10f, black, 0f, 0.075f, -0.04f)
            tpProps.add(Prop(ArrayList(p)))
            addRightArm(p, -0.11f, 0.09f)
            addLeftArm(p, -0.07f, -0.24f)
            weaponProps.add(Prop(p))
            weapons.add(WDef("SMG", 14f, 0.08f, 30, 1.6f, 150f, true, 1.1f, 0.005f, -0.45f))
        }
        // 2: RIFLE
        run {
            val acc = Color(1f, 0.5f, 0.1f, 1f)
            val p = ArrayList<Part>()
            mk(p, 0.07f, 0.10f, 0.45f, dark, 0f, 0f, -0.05f)
            mk(p, 0.075f, 0.012f, 0.46f, acc, 0f, 0.056f, -0.05f)
            mk(p, 0.065f, 0.07f, 0.30f, gray, 0f, -0.005f, -0.40f)
            mk(p, 0.025f, 0.025f, 0.22f, black, 0f, 0.005f, -0.66f)
            mk(p, 0.03f, 0.03f, 0.05f, black, 0f, 0.005f, -0.78f)
            mk(p, 0.014f, 0.045f, 0.014f, black, 0f, 0.045f, -0.60f)
            mk(p, 0.06f, 0.10f, 0.24f, wood, 0f, -0.015f, 0.29f)
            mk(p, 0.05f, 0.14f, 0.06f, black, 0f, -0.12f, 0.08f)
            mk(p, 0.045f, 0.20f, 0.075f, dark, 0f, -0.15f, -0.13f)
            mk(p, 0.045f, 0.04f, 0.10f, black, 0f, 0.075f, -0.02f)
            mkGlow(p, 0.03f, 0.03f, 0.012f, Color(1f, 0.1f, 0.1f, 1f), 0f, 0.08f, -0.075f)
            tpProps.add(Prop(ArrayList(p)))
            addRightArm(p, -0.12f, 0.08f)
            addLeftArm(p, -0.085f, -0.40f)
            weaponProps.add(Prop(p))
            weapons.add(WDef("RIFLE", 25f, 0.13f, 30, 2.0f, 250f, true, 1.0f, 0.005f, -0.81f))
        }
        // 3: SNIPER
        run {
            val acc = Color(1f, 0.8f, 0.1f, 1f)
            val p = ArrayList<Part>()
            mk(p, 0.07f, 0.10f, 0.50f, dark, 0f, 0f, 0f)
            mk(p, 0.074f, 0.012f, 0.50f, acc, 0f, 0.056f, 0f)
            mk(p, 0.03f, 0.03f, 0.70f, black, 0f, 0.01f, -0.60f)
            mk(p, 0.045f, 0.045f, 0.07f, gray, 0f, 0.01f, -0.97f)
            mk(p, 0.05f, 0.05f, 0.30f, black, 0f, 0.105f, -0.08f)
            mk(p, 0.07f, 0.07f, 0.06f, black, 0f, 0.105f, -0.25f)
            mk(p, 0.06f, 0.06f, 0.05f, black, 0f, 0.105f, 0.08f)
            mkGlow(p, 0.05f, 0.05f, 0.01f, Color(0.2f, 0.6f, 1f, 1f), 0f, 0.105f, -0.285f)
            mk(p, 0.03f, 0.04f, 0.03f, gray, 0f, 0.065f, -0.14f)
            mk(p, 0.03f, 0.04f, 0.03f, gray, 0f, 0.065f, 0.02f)
            mk(p, 0.06f, 0.13f, 0.32f, wood, 0f, -0.02f, 0.40f)
            mk(p, 0.05f, 0.04f, 0.14f, wood, 0f, 0.06f, 0.33f)
            mk(p, 0.05f, 0.13f, 0.06f, black, 0f, -0.12f, 0.14f)
            mk(p, 0.04f, 0.10f, 0.07f, dark, 0f, -0.10f, -0.10f)
            tpProps.add(Prop(ArrayList(p)))
            addRightArm(p, -0.12f, 0.14f)
            addLeftArm(p, -0.045f, -0.36f)
            weaponProps.add(Prop(p))
            weapons.add(WDef("SNIPER", 90f, 1.1f, 5, 2.6f, 500f, false, 0.8f, 0.01f, -1.01f))
        }
        // muzzle flash (drawn at the barrel tip)
        run {
            val p = ArrayList<Part>()
            mkGlow(p, 0.14f, 0.14f, 0.02f, Color(1f, 0.95f, 0.3f, 1f), 0f, 0f, 0f)
            mkGlow(p, 0.05f, 0.05f, 0.16f, Color(1f, 0.6f, 0.1f, 1f), 0f, 0f, -0.07f)
            mkGlow(p, 0.22f, 0.03f, 0.02f, Color(1f, 0.8f, 0.2f, 1f), 0f, 0f, -0.01f)
            mkGlow(p, 0.03f, 0.22f, 0.02f, Color(1f, 0.8f, 0.2f, 1f), 0f, 0f, -0.01f)
            flashProp = Prop(p)
        }
    }

    private fun buildItems() {
        // 4: MEDKIT
        run {
            val white = Color(0.95f, 0.95f, 0.95f, 1f)
            val red = Color(0.9f, 0.1f, 0.1f, 1f)
            val p = ArrayList<Part>()
            mk(p, 0.5f, 0.35f, 0.3f, white, 0f, 0f, 0f)
            mk(p, 0.36f, 0.1f, 0.02f, red, 0f, 0f, 0.16f)
            mk(p, 0.1f, 0.3f, 0.02f, red, 0f, 0f, 0.16f)
            mk(p, 0.36f, 0.1f, 0.02f, red, 0f, 0f, -0.16f)
            mk(p, 0.1f, 0.3f, 0.02f, red, 0f, 0f, -0.16f)
            mk(p, 0.2f, 0.06f, 0.06f, gray, 0f, 0.2f, 0f)
            itemProps.add(Prop(p))
        }
        // 5: AMMO BOX
        run {
            val olive = Color(0.35f, 0.38f, 0.2f, 1f)
            val yellow = Color(0.95f, 0.8f, 0.1f, 1f)
            val gold = Color(0.9f, 0.7f, 0.2f, 1f)
            val p = ArrayList<Part>()
            mk(p, 0.55f, 0.3f, 0.32f, olive, 0f, 0f, 0f)
            mk(p, 0.57f, 0.06f, 0.34f, yellow, 0f, 0.04f, 0f)
            for (i in -1..1) {
                mk(p, 0.07f, 0.14f, 0.07f, gold, i * 0.14f, 0.22f, 0f)
            }
            itemProps.add(Prop(p))
        }
        // 6: ARMOR VEST
        run {
            val blue = Color(0.15f, 0.35f, 0.8f, 1f)
            val light = Color(0.4f, 0.6f, 0.95f, 1f)
            val p = ArrayList<Part>()
            mk(p, 0.5f, 0.55f, 0.2f, blue, 0f, 0f, 0f)
            mk(p, 0.3f, 0.3f, 0.05f, light, 0f, 0.02f, 0.12f)
            mk(p, 0.3f, 0.3f, 0.05f, light, 0f, 0.02f, -0.12f)
            mk(p, 0.1f, 0.2f, 0.22f, dark, -0.2f, 0.35f, 0f)
            mk(p, 0.1f, 0.2f, 0.22f, dark, 0.2f, 0.35f, 0f)
            itemProps.add(Prop(p))
        }
    }

    // tall light beams so loot can be spotted from far away
    private fun buildBeams() {
        val cols = arrayOf(
            Color(0.9f, 0.9f, 0.9f, 1f),   // pistol
            Color(0.3f, 1f, 0.3f, 1f),     // smg
            Color(0.3f, 0.5f, 1f, 1f),     // rifle
            Color(1f, 0.8f, 0.1f, 1f),     // sniper
            Color(1f, 0.2f, 0.2f, 1f),     // medkit
            Color(1f, 0.6f, 0.1f, 1f),     // ammo
            Color(0.2f, 0.9f, 1f, 1f)      // armor
        )
        for (c in cols) {
            val m = mb.createBox(0.14f, 9f, 0.14f,
                Material(ColorAttribute.createDiffuse(c), ColorAttribute.createEmissive(c), BlendingAttribute(0.35f)),
                attrs)
            models.add(m)
            beams.add(ModelInstance(m))
        }
    }

    private fun drawProp(p: Prop, base: Matrix4) {
        for (pt in p.parts) {
            pt.inst.transform.set(base).translate(pt.ox, pt.oy, pt.oz)
            if (pt.ry != 0f) pt.inst.transform.rotate(Vector3.Y, pt.ry)
            if (pt.rx != 0f) pt.inst.transform.rotate(Vector3.X, pt.rx)
            batch.render(pt.inst, env)
        }
    }

    // ------------------------------------------------------------------
    // Loot
    // ------------------------------------------------------------------
    private fun randWeapon(): Int {
        val r = rnd.nextInt(100)
        return if (r < 30) 0 else if (r < 60) 1 else if (r < 88) 2 else 3
    }

    private fun randKind(): Int {
        val r = rnd.nextInt(100)
        return if (r < 40) randWeapon() else if (r < 58) 4 else if (r < 82) 5 else 6
    }

    private fun addLoot(kind: Int, x: Float, z: Float) {
        val p = Vector3(x.coerceIn(-490f, 490f), 1f, z.coerceIn(-490f, 490f))
        collide(p, 1.2f)
        val mag = if (kind <= 3) weapons[kind].mag else 0
        loot.add(Loot(kind, p.x, p.z, mag))
    }

    private fun spawnLoot(n: Int) {
        for (i in 0 until n) {
            val b = blocks[rnd.nextInt(blocks.size)]
            val side = if (rnd.nextBoolean()) 1f else -1f
            val x = b.x + side * (b.hw + 1.5f + rnd.nextFloat() * 2f)
            val z = b.z + (rnd.nextFloat() - 0.5f) * b.hd * 2f
            addLoot(randKind(), x, z)
        }
        // some loot right near the start so you can arm up quickly
        for (i in 0 until 6) {
            addLoot(if (i < 3) randWeapon() else randKind(),
                (rnd.nextFloat() - 0.5f) * 30f, (rnd.nextFloat() - 0.5f) * 30f)
        }
    }

    private fun onBotDeath(b: Bot) {
        addLoot(b.weapon, b.pos.x, b.pos.z)
        addLoot(5, b.pos.x + 1.5f, b.pos.z)
    }

    // bot dies: drop loot and leave a body that falls over
    private fun killBot(b: Bot) {
        onBotDeath(b)
        b.deadT = 0f
        b.shooting = false
        val ba = b.actor
        if (ba != null) play(ba, "Death", 1f, 1, 0.1f)
        corpses.add(b)
    }

    private fun applyItem(l: Loot): Boolean {
        when (l.kind) {
            4 -> {
                if (medkits >= 5) return false
                medkits++
                msg = "Medkit +1 (tap the + button)"
            }
            5 -> {
                reserve = minOf(300, reserve + 40)
                msg = "Ammo +40"
            }
            6 -> {
                if (armor >= 100f) return false
                armor = minOf(100f, armor + 50f)
                msg = "Armor +50"
            }
            else -> return false
        }
        msgT = 1.5f
        return true
    }

    private fun pickupWeapon(l: Loot) {
        // same weapon already carried -> just take ammo
        if (slots[0].type == l.kind || slots[1].type == l.kind) {
            reserve = minOf(300, reserve + 30)
            loot.remove(l)
            msg = "Ammo +30"
            msgT = 1.5f
            return
        }
        val empty = if (slots[0].type < 0) 0 else if (slots[1].type < 0) 1 else -1
        if (empty >= 0) {
            slots[empty].type = l.kind
            slots[empty].mag = l.mag
            cur = empty
            loot.remove(l)
        } else {
            // swap the current weapon with the one on the ground
            val s = slots[cur]
            val oldType = s.type
            val oldMag = s.mag
            s.type = l.kind
            s.mag = l.mag
            l.kind = oldType
            l.mag = oldMag
        }
        reloading = false
        msg = "Equipped " + weapons[slots[cur].type].name
        msgT = 1.5f
    }

    private fun selectSlot(i: Int) {
        if (i != cur && slots[i].type >= 0) {
            cur = i
            reloading = false
            msg = weapons[slots[cur].type].name
            msgT = 1.2f
        }
    }

    // simple flat gun silhouette for the weapon boxes
    private fun gunIcon(type: Int, cx: Float, cy: Float) {
        when (type) {
            0 -> {
                shapes.rect(cx - 24f, cy - 4f, 48f, 15f)
                shapes.rect(cx - 8f, cy - 30f, 16f, 28f)
            }
            1 -> {
                shapes.rect(cx - 45f, cy - 6f, 90f, 16f)
                shapes.rect(cx - 2f, cy - 34f, 14f, 30f)
                shapes.rect(cx + 45f, cy - 1f, 20f, 6f)
                shapes.rect(cx - 62f, cy - 2f, 17f, 8f)
            }
            2 -> {
                shapes.rect(cx - 55f, cy - 6f, 100f, 16f)
                shapes.rect(cx + 45f, cy - 1f, 40f, 6f)
                shapes.rect(cx - 82f, cy - 8f, 28f, 20f)
                shapes.rect(cx - 10f, cy - 36f, 14f, 32f)
                shapes.rect(cx - 25f, cy + 8f, 40f, 7f)
            }
            3 -> {
                shapes.rect(cx - 70f, cy - 4f, 140f, 12f)
                shapes.rect(cx + 70f, cy - 1f, 32f, 5f)
                shapes.rect(cx - 92f, cy - 8f, 26f, 18f)
                shapes.rect(cx - 15f, cy + 8f, 44f, 11f)
                shapes.rect(cx - 5f, cy - 26f, 12f, 22f)
            }
        }
    }

    private fun swapWeapon() {
        val o = 1 - cur
        if (slots[o].type >= 0) {
            cur = o
            reloading = false
            msg = weapons[slots[cur].type].name
            msgT = 1.2f
        }
    }

    private fun tryReload() {
        val s = slots[cur]
        val d = weapons[s.type]
        if (!reloading && s.mag < d.mag && reserve > 0) {
            reloading = true
            reloadT = d.reload
        }
    }

    private fun hurt(d: Float) {
        val a = minOf(armor, d * 0.6f)
        armor -= a
        hp -= d - a
    }

    private fun inCircle(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy < r * r
    }

    // ------------------------------------------------------------------
    // Game state
    // ------------------------------------------------------------------
    private fun restart() {
        hp = 100f
        armor = 0f
        kills = 0
        yaw = 0f
        pitch = 0f
        pYaw = 180f
        pMoving = false
        pArmedT = 0f
        val pa0 = pActor
        if (pa0 != null) {
            pa0.cur = ""
            play(pa0, "Idle", 1f, -1, 0f)
        }
        hurtFlash = 0f
        hitMark = 0f
        hitHead = false
        recoil = 0f
        reloading = false
        reloadT = 0f
        firingLast = false
        msg = ""
        msgT = 0f
        walkT = 0f
        nearLoot = null
        reserve = 36
        medkits = 0
        sprint = false
        jumpV = 0f
        slots[0].type = 0
        slots[0].mag = weapons[0].mag
        slots[1].type = -1
        slots[1].mag = 0
        cur = 0
        cam.position.set(0f, 1.7f, 0f)
        collide(cam.position, 0.6f)
        matchTime = 0f
        phase = 0
        phaseTime = 0f
        shrinking = false
        won = false
        zoneCx = 0f
        zoneCz = 0f
        zoneR = startRadius
        pickTarget()
        bots.clear()
        corpses.clear()
        spawnBots(botCount)
        loot.clear()
        spawnLoot(110)
    }

    // push a position out of any building (circle vs box, top-down)
    private fun collide(p: Vector3, rad: Float) {
        for (b in blocks) {
            val nx = p.x.coerceIn(b.x - b.hw, b.x + b.hw)
            val nz = p.z.coerceIn(b.z - b.hd, b.z + b.hd)
            val dx = p.x - nx
            val dz = p.z - nz
            val d2 = dx * dx + dz * dz
            if (d2 < rad * rad) {
                if (d2 > 0.00001f) {
                    val d = sqrt(d2)
                    val push = rad - d
                    p.x += dx / d * push
                    p.z += dz / d * push
                } else {
                    val l = p.x - (b.x - b.hw)
                    val rr = (b.x + b.hw) - p.x
                    val t = p.z - (b.z - b.hd)
                    val bt = (b.z + b.hd) - p.z
                    val m = minOf(minOf(l, rr), minOf(t, bt))
                    when (m) {
                        l -> p.x = b.x - b.hw - rad
                        rr -> p.x = b.x + b.hw + rad
                        t -> p.z = b.z - b.hd - rad
                        else -> p.z = b.z + b.hd + rad
                    }
                }
            }
        }
    }

    // distance along the ray to the nearest building (or maxD)
    private fun wallDist(ray: Ray, maxD: Float): Float {
        var best = maxD
        val tmp = Vector3()
        for (b in blocks) {
            if (Intersector.intersectRayBounds(ray, b.box, tmp)) {
                val d = tmp.dst(ray.origin)
                if (d < best) best = d
            }
        }
        return best
    }

    private fun hasLos(a: Vector3, b: Vector3): Boolean {
        for (bl in blocks) {
            if (segHitsBox(a, b, bl.box)) return false
        }
        return true
    }

    private fun pickTarget() {
        fromCx = zoneCx
        fromCz = zoneCz
        fromR = zoneR
        toR = zoneRadii[phase]
        val maxOff = (fromR - toR).coerceAtLeast(0f)
        val a = rnd.nextFloat() * 6.2831853f
        val d = sqrt(rnd.nextFloat()) * maxOff
        toCx = fromCx + cos(a) * d
        toCz = fromCz + sin(a) * d
    }

    private fun updateZone(dt: Float) {
        matchTime += dt
        phaseTime += dt
        if (!shrinking) {
            if (phaseTime >= holdTimes[phase]) {
                shrinking = true
                phaseTime = 0f
            }
        } else {
            val t = (phaseTime / shrinkTimes[phase]).coerceAtMost(1f)
            zoneCx = fromCx + (toCx - fromCx) * t
            zoneCz = fromCz + (toCz - fromCz) * t
            zoneR = fromR + (toR - fromR) * t
            if (phaseTime >= shrinkTimes[phase]) {
                phase++
                phaseTime = 0f
                shrinking = false
                if (phase >= holdTimes.size) {
                    won = true
                    phase = holdTimes.size - 1
                } else {
                    pickTarget()
                }
            }
        }
    }

    private fun spawnBots(n: Int) {
        for (i in 0 until n) {
            val p = Vector3()
            do {
                p.set((rnd.nextFloat() - 0.5f) * 960f, 0f, (rnd.nextFloat() - 0.5f) * 960f)
            } while (Vector3(p.x - cam.position.x, 0f, p.z - cam.position.z).len() < 70f)
            collide(p, 0.8f)
            val bot = Bot(rnd.nextInt(2), rnd.nextInt(3), p)
            bot.target.set(p)
            if (humansOk) bot.actor = makeActor(bot.gender, bot.outfit)
            bot.yaw = rnd.nextFloat() * 360f
            bot.weapon = randWeapon()
            bot.back = if (rnd.nextInt(100) < 65) randWeapon() else -1
            if (bot.back == bot.weapon) bot.back = -1
            bots.add(bot)
        }
    }

    override fun resize(width: Int, height: Int) {
        cam.viewportWidth = width.toFloat()
        cam.viewportHeight = height.toFloat()
        cam.update()
        gunCam.viewportWidth = width.toFloat()
        gunCam.viewportHeight = height.toFloat()
        gunCam.update()
        shapes.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
        sb.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
    }

    private fun fmt(t: Float): String {
        val s = t.toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun centered(text: String, y: Float, w: Float) {
        layout.setText(font, text)
        font.draw(sb, text, (w - layout.width) / 2f, y)
    }

    private fun label(text: String, cx: Float, cy: Float) {
        layout.setText(font, text)
        font.draw(sb, text, cx - layout.width / 2f, cy + layout.height / 2f)
    }

    // ------------------------------------------------------------------
    // Main loop
    // ------------------------------------------------------------------
    override fun render() {
        val dt = minOf(Gdx.graphics.deltaTime, 0.05f)
        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()
        gameTime += dt
        msgT -= dt
        if (loadIdx < loadTotal) {
            taps.clear()
            loadStep()
            drawLoading(w, h)
            return
        }

        // screen-space (y down) control layout
        val joyX = w * 0.18f
        val joyY = h * 0.70f
        val joyR = h * 0.18f
        val fireX = w * 0.88f
        val fireY = h * 0.72f
        val fireR = h * 0.12f
        val reloadX = w * 0.74f
        val reloadY = h * 0.82f
        val reloadR = h * 0.075f
        val swapX = w * 0.88f
        val swapY = h * 0.43f
        val swapR = h * 0.075f
        val pickX = w * 0.74f
        val pickY = h * 0.62f
        val pickR = h * 0.085f
        val camX = w * 0.74f
        val camY = h * 0.44f
        val camR = h * 0.065f
        // Free Fire style layout: weapon panel top-right, 2 small weapon boxes under it
        val panelW = 440f
        val panelH = 135f
        val panelX = w - panelW - 30f
        val panelY = 30f
        val slotW = 140f
        val slotH = 115f
        val slotY = panelY + panelH + 10f
        fun slotX(i: Int): Float = panelX + i * (slotW + 10f)
        fun slotHit(x: Float, y: Float): Int {
            for (i in 0..1) {
                if (x >= slotX(i) && x <= slotX(i) + slotW && y >= slotY && y <= slotY + slotH) return i
            }
            return -1
        }
        val medX = panelX - 75f
        val medY = 100f
        val medR = 55f
        val runX = w * 0.21f
        val runY = h * 0.40f
        val runR = h * 0.07f
        val jumpX = w * 0.93f
        val jumpY = h * 0.91f
        val jumpR = h * 0.065f

        var mx = 0f
        var my = 0f
        var firing = false
        var knobX = joyX
        var knobY = joyY
        var outside = false
        var damaged = false

        val over = hp <= 0f || won

        if (over) {
            taps.clear()
            if (Gdx.input.justTouched()) restart()
        } else {
            for (i in 0..2) {
                if (!Gdx.input.isTouched(i)) continue
                val x = Gdx.input.getX(i).toFloat()
                val y = Gdx.input.getY(i).toFloat()
                val dfx = x - fireX
                val dfy = y - fireY
                if (dfx * dfx + dfy * dfy < fireR * fireR * 2.2f) {
                    firing = true
                } else if (inCircle(x, y, reloadX, reloadY, reloadR * 1.15f) ||
                    inCircle(x, y, medX, medY, medR * 1.15f) ||
                    inCircle(x, y, runX, runY, runR * 1.15f) ||
                    inCircle(x, y, jumpX, jumpY, jumpR * 1.15f) ||
                    inCircle(x, y, camX, camY, camR * 1.15f) ||
                    slotHit(x, y) >= 0 ||
                    (nearLoot != null && inCircle(x, y, pickX, pickY, pickR * 1.15f))) {
                    // finger is on a button, not on movement or look
                } else if (x < w / 2f) {
                    mx = ((x - joyX) / joyR).coerceIn(-1f, 1f)
                    my = ((joyY - y) / joyR).coerceIn(-1f, 1f)
                    knobX = joyX + mx * joyR
                    knobY = joyY - my * joyR
                } else {
                    yaw -= Gdx.input.getDeltaX(i) * 0.2f
                    pitch = (pitch - Gdx.input.getDeltaY(i) * 0.2f).coerceIn(if (tpp) -55f else -80f, if (tpp) 35f else 80f)
                }
            }

            val fwd = Vector3(0f, 0f, -1f).rotate(Vector3.Y, yaw)
            val right = Vector3(fwd).crs(Vector3.Y)
            val speed = if (sprint) 8.5f else 5.5f
            pSpeed = speed * minOf(1f, sqrt(mx * mx + my * my))
            pMoving = (mx * mx + my * my) > 0.04f
            if (pMoving) {
                val vel = Vector3(fwd).scl(my).add(Vector3(right).scl(mx))
                pMoveYaw = atan2(vel.x, vel.z) * 57.29578f
            }
            cam.position.add(fwd.scl(my * speed * dt)).add(right.scl(mx * speed * dt))
            if (cam.position.y > 1.7f || jumpV != 0f) {
                jumpV -= 18f * dt
                cam.position.y += jumpV * dt
                if (cam.position.y <= 1.7f) {
                    cam.position.y = 1.7f
                    jumpV = 0f
                }
            }
            cam.position.x = cam.position.x.coerceIn(-495f, 495f)
            cam.position.z = cam.position.z.coerceIn(-495f, 495f)
            collide(cam.position, 0.6f)

            updateZone(dt * timeScale)
            val pdx = cam.position.x - zoneCx
            val pdz = cam.position.z - zoneCz
            outside = sqrt(pdx * pdx + pdz * pdz) > zoneR
            if (outside) {
                hp -= zoneDamage[phase] * dt
                damaged = true
            }

            // loot: auto-pickup items, find nearest weapon for the PICK button
            var near: Loot? = null
            var nearD = 3.2f
            var li = loot.size - 1
            while (li >= 0) {
                val l = loot[li]
                val ldx = l.x - cam.position.x
                val ldz = l.z - cam.position.z
                val ld = sqrt(ldx * ldx + ldz * ldz)
                if (l.kind >= 4) {
                    if (ld < 2.2f && applyItem(l)) loot.removeAt(li)
                } else if (ld < nearD) {
                    near = l
                    nearD = ld
                }
                li--
            }
            nearLoot = near

            // button taps
            for (t in taps) {
                val tx = t[0]
                val ty = t[1]
                if (inCircle(tx, ty, reloadX, reloadY, reloadR * 1.15f)) {
                    tryReload()
                } else if (inCircle(tx, ty, medX, medY, medR * 1.15f)) {
                    if (medkits > 0 && hp < 100f) {
                        medkits--
                        hp = minOf(100f, hp + 50f)
                        msg = "Medkit used +50 HP"
                        msgT = 1.5f
                    }
                } else if (inCircle(tx, ty, runX, runY, runR * 1.15f)) {
                    sprint = !sprint
                } else if (inCircle(tx, ty, jumpX, jumpY, jumpR * 1.15f)) {
                    if (cam.position.y <= 1.72f) jumpV = 6.5f
                } else if (slotHit(tx, ty) >= 0) {
                    selectSlot(slotHit(tx, ty))
                } else if (inCircle(tx, ty, camX, camY, camR * 1.15f)) {
                    tpp = !tpp
                    pitch = pitch.coerceIn(if (tpp) -55f else -80f, if (tpp) 35f else 80f)
                } else {
                    val nl = nearLoot
                    if (nl != null && inCircle(tx, ty, pickX, pickY, pickR * 1.15f)) pickupWeapon(nl)
                }
            }
            taps.clear()
        }

        cam.direction.set(0f, 0f, -1f).rotate(Vector3.X, pitch).rotate(Vector3.Y, yaw)
        cam.up.set(Vector3.Y)
        cam.update()

        // ---- weapon logic ----
        val slot = slots[cur]
        val def = weapons[slot.type]
        cooldown -= dt
        flash -= dt
        hitMark -= dt
        recoil = maxOf(0f, recoil - dt * 7f)
        if (reloading) {
            reloadT -= dt
            if (reloadT <= 0f) {
                val take = minOf(def.mag - slot.mag, reserve)
                slot.mag += take
                reserve -= take
                reloading = false
            }
        }
        val wantFire = firing && (def.auto || !firingLast)
        if (wantFire && cooldown <= 0f && !over && !reloading) {
            if (slot.mag > 0) {
                slot.mag -= 1
                cooldown = def.cd
                flash = 0.07f
                recoil = 1f
                val shootDir = Vector3(cam.direction).nor()
                if (tpp) {
                    // aim at whatever is under the crosshair of the third-person camera
                    val tp = tpCamPos(cam.position)
                    shootDir.set(tp.add(Vector3(cam.direction).nor().scl(80f)).sub(cam.position)).nor()
                }
                val ray = Ray(Vector3(cam.position), shootDir)
                val maxD = wallDist(ray, def.range)
                var best: Bot? = null
                var bestDist = maxD
                var bestHead = false
                for (b in bots) {
                    val hc = Vector3(b.pos.x, 1.68f, b.pos.z)
                    val tc = Vector3(b.pos.x, 1.25f, b.pos.z)
                    val lc = Vector3(b.pos.x, 0.65f, b.pos.z)
                    var hit = false
                    var head = false
                    var d = 0f
                    if (Intersector.intersectRaySphere(ray, hc, 0.22f, null)) {
                        hit = true
                        head = true
                        d = hc.dst(cam.position)
                    } else if (Intersector.intersectRaySphere(ray, tc, 0.34f, null)) {
                        hit = true
                        d = tc.dst(cam.position)
                    } else if (Intersector.intersectRaySphere(ray, lc, 0.36f, null)) {
                        hit = true
                        d = lc.dst(cam.position)
                    }
                    if (hit && d < bestDist) {
                        bestDist = d
                        best = b
                        bestHead = head
                    }
                }
                if (best != null) {
                    best.hp -= if (bestHead) def.dmg * 2f else def.dmg
                    hitMark = 0.15f
                    hitHead = bestHead
                    if (bestHead) {
                        msg = "HEADSHOT!"
                        msgT = 0.8f
                    }
                    if (best.hp <= 0f) {
                        killBot(best)
                        bots.remove(best)
                        kills++
                    }
                }
            } else {
                tryReload()
            }
        }
        firingLast = firing

        // ---- bots ----
        if (!over) {
            val eye = Vector3(cam.position)
            val it = bots.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.hp <= 0f) continue
                val zdx = b.pos.x - zoneCx
                val zdz = b.pos.z - zoneCz
                val botOut = sqrt(zdx * zdx + zdz * zdz) > zoneR
                if (botOut) {
                    b.hp -= zoneDamage[phase] * dt
                    if (b.hp <= 0f) {
                        killBot(b)
                        it.remove()
                        continue
                    }
                }

                val toPx = cam.position.x - b.pos.x
                val toPz = cam.position.z - b.pos.z
                val toP = sqrt(toPx * toPx + toPz * toPz)
                val los = toP < 30f && hasLos(Vector3(b.pos.x, 1.5f, b.pos.z), eye)
                var speed = 2.5f
                var move = true
                var facePlayer = false
                var rivalFire = false
                var faceYaw = b.yaw

                if (botOut) {
                    b.target.set(zoneCx, 0f, zoneCz)
                    speed = 4.5f
                } else if (toP < 60f) {
                    b.target.set(cam.position.x, 0f, cam.position.z)
                    speed = 3.5f
                    facePlayer = true
                    if (toP < 12f || los) move = false
                } else {
                    // look for another bot to fight
                    b.scanT -= dt
                    if (b.scanT <= 0f) {
                        b.scanT = 0.4f + rnd.nextFloat() * 0.3f
                        var rv: Bot? = null
                        var rd = 40f
                        for (o in bots) {
                            if (o === b || o.hp <= 0f) continue
                            val ox = o.pos.x - b.pos.x
                            val oz = o.pos.z - b.pos.z
                            val od = sqrt(ox * ox + oz * oz)
                            if (od < rd && hasLos(Vector3(b.pos.x, 1.5f, b.pos.z), Vector3(o.pos.x, 1.5f, o.pos.z))) {
                                rd = od
                                rv = o
                            }
                        }
                        b.rival = rv
                    }
                    val rv = b.rival
                    if (rv != null && rv.hp > 0f && bots.contains(rv)) {
                        val ox = rv.pos.x - b.pos.x
                        val oz = rv.pos.z - b.pos.z
                        if (ox * ox + oz * oz < 45f * 45f) {
                            faceYaw = atan2(ox, oz) * 57.29578f
                            move = false
                            rivalFire = true
                            rv.hp -= 6f * dt
                            if (rv.hp <= 0f && !pendingKill.contains(rv)) pendingKill.add(rv)
                        } else b.rival = null
                    } else b.rival = null
                    if (!rivalFire) {
                        b.retarget -= dt
                        val tdx = b.target.x - b.pos.x
                        val tdz = b.target.z - b.pos.z
                        if (b.retarget <= 0f || tdx * tdx + tdz * tdz < 9f) {
                            val a = rnd.nextFloat() * 6.2831853f
                            val d = sqrt(rnd.nextFloat()) * zoneR * 0.8f
                            b.target.set((zoneCx + cos(a) * d).coerceIn(-480f, 480f), 0f,
                                         (zoneCz + sin(a) * d).coerceIn(-480f, 480f))
                            b.retarget = 6f + rnd.nextFloat() * 6f
                        }
                    }
                }

                var moved = false
                if (move) {
                    val dir = Vector3(b.target.x - b.pos.x, 0f, b.target.z - b.pos.z)
                    if (dir.len() > 0.5f) {
                        dir.nor()
                        faceYaw = atan2(dir.x, dir.z) * 57.29578f
                        dir.scl(speed * dt)
                        b.pos.add(dir)
                        moved = true
                    }
                }
                if (facePlayer && (los || !moved)) faceYaw = atan2(toPx, toPz) * 57.29578f
                b.yaw = turnTo(b.yaw, faceYaw, 480f * dt)
                b.pos.x = b.pos.x.coerceIn(-495f, 495f)
                b.pos.z = b.pos.z.coerceIn(-495f, 495f)
                collide(b.pos, 0.8f)

                b.shooting = los || rivalFire
                b.moving = moved
                b.vel = if (moved) speed else 0f
                if (b.shooting) b.armedT = 2.2f else if (b.armedT > 0f) b.armedT -= dt
                if (los) {
                    hurt(5f * dt)
                    damaged = true
                }
            }
            for (o in pendingKill) {
                if (bots.remove(o)) {
                    killBot(o)
                    msg = "A bot was eliminated"
                    msgT = 1.2f
                }
            }
            pendingKill.clear()
            if (!damaged) hp = minOf(100f, hp + 1.5f * dt)
            if (bots.isEmpty()) won = true
        }
        if (damaged) hurtFlash = 0.15f else hurtFlash -= dt

        // zone walls
        val zr = zoneR.coerceAtLeast(0.5f)
        zoneInst.transform.setToTranslation(zoneCx, 75f, zoneCz).scale(zr, 1f, zr)
        val tr = toR.coerceAtLeast(0.5f)
        targetInst.transform.setToTranslation(toCx, 75f, toCz).scale(tr, 1f, tr)

        // ---- draw world ----
        Gdx.gl.glViewport(0, 0, Gdx.graphics.width, Gdx.graphics.height)
        Gdx.gl.glClearColor(0.5f, 0.8f, 1f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        val eyeP = Vector3(cam.position)
        val pa = pActor
        val showBody = tpp && humansOk && pa != null
        if (tpp) {
            cam.position.set(tpCamPos(eyeP))
            cam.update()
        }
        batch.begin(cam)
        batch.render(buildings, env)
        if (showBody && pa != null) {
            val moving = pMoving
            val shoot = flash > 0f || firing
            if (shoot) pArmedT = 2.5f else pArmedT -= dt
            val armedNow = pArmedT > 0f
            val tgt = if (armedNow) yaw + 180f else if (moving) pMoveYaw else pYaw
            pYaw = turnTo(pYaw, tgt, 720f * dt)
            if (hp <= 0f) play(pa, "Death", 1f, 1, 0.1f)
            else if (eyeP.y > 1.78f) play(pa, "Jump", 1f)
            else locoAnim(pa, if (moving) pSpeed else 0f, armedNow)
            pa.ctrl.update(dt)
            drawActor(pa, eyeP.x, eyeP.y - 1.7f, eyeP.z, pYaw, slots[cur].type, slots[1 - cur].type, armedNow && hp > 0f, flash > 0f)
        }
        for (b in bots) {
            val bdx = b.pos.x - cam.position.x
            val bdz = b.pos.z - cam.position.z
            if (bdx * bdx + bdz * bdz > 250f * 250f) continue
            if (!cam.frustum.sphereInFrustum(b.pos.x, 1f, b.pos.z, 1.8f)) continue
            val ba = b.actor
            if (ba == null) {
                boxInst.transform.setToTranslation(b.pos.x, 1f, b.pos.z)
                batch.render(boxInst, env)
                continue
            }
            val armed = b.armedT > 0f
            locoAnim(ba, b.vel, armed)
            ba.ctrl.update(dt)
            drawActor(ba, b.pos.x, 0f, b.pos.z, b.yaw, b.weapon, b.back, armed,
                      b.shooting && ((gameTime * 18f).toInt() + b.outfit) % 2 == 0)
        }
        val ci = corpses.iterator()
        while (ci.hasNext()) {
            val c = ci.next()
            c.deadT += dt
            if (c.deadT > 6f) {
                ci.remove()
                continue
            }
            val ca = c.actor
            if (ca != null) {
                if (c.deadT < 2.6f) ca.ctrl.update(dt)
                drawActor(ca, c.pos.x, 0f, c.pos.z, c.yaw, -1, -1, false, false)
            }
        }
        for (l in loot) {
            val dx = l.x - cam.position.x
            val dz = l.z - cam.position.z
            if (dx * dx + dz * dz > 120f * 120f) continue
            val bi = beams[l.kind]
            bi.transform.setToTranslation(l.x, 4.5f, l.z)
            batch.render(bi, env)
            val lb = Matrix4()
            lb.translate(l.x, 0.95f + 0.12f * sin(gameTime * 2f + l.x), l.z)
            lb.rotate(Vector3.Y, gameTime * 80f)
            if (l.kind <= 3) {
                lb.scale(1.5f, 1.5f, 1.5f)
                drawProp(weaponProps[l.kind], lb)
            } else {
                lb.scale(1.2f, 1.2f, 1.2f)
                drawProp(itemProps[l.kind - 4], lb)
            }
        }
        batch.render(zoneInst, env)
        if (!shrinking && !won) batch.render(targetInst, env)
        batch.end()
        cam.position.set(eyeP)
        cam.update()

        // ---- draw the gun in your hands (first person only) ----
        if (hp > 0f && !tpp) {
            val moving = (mx * mx + my * my) > 0.04f
            if (moving) walkT += dt * 9f
            val bobX = if (moving) sin(walkT) * 0.012f else 0f
            val bobY = if (moving) abs(cos(walkT)) * 0.012f else sin(gameTime * 1.5f) * 0.003f
            var rk = 0f
            if (reloading) {
                val prog = 1f - reloadT / def.reload
                rk = sin(prog * 3.1415927f)
            }
            val gb = Matrix4()
            gb.translate(0.30f + bobX, -0.27f + bobY - 0.18f * rk, -0.80f + recoil * 0.07f)
            gb.rotate(Vector3.Y, 5f)
            gb.rotate(Vector3.X, recoil * 5f)
            gb.rotate(Vector3.Z, 28f * rk)
            gb.scale(def.viewScale, def.viewScale, def.viewScale)

            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
            batch.begin(gunCam)
            drawProp(weaponProps[slot.type], gb)
            if (flash > 0f) {
                val fb = Matrix4(gb).translate(0f, def.muzzleY, def.muzzleZ - 0.04f)
                drawProp(flashProp, fb)
            }
            batch.end()
        }

        // ---- HUD ----
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        val nl = nearLoot
        val ms = 0.2f
        val mcx = 190f
        val mcy = h - 180f

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        if (outside) {
            shapes.setColor(0.2f, 0.3f, 1f, 0.18f)
            shapes.rect(0f, 0f, w, h)
        }
        if (hurtFlash > 0f && !outside) {
            shapes.setColor(1f, 0f, 0f, 0.15f)
            shapes.rect(0f, 0f, w, h)
        }
        // joystick + fire
        shapes.setColor(1f, 1f, 1f, 0.2f)
        shapes.circle(joyX, h - joyY, joyR)
        shapes.setColor(1f, 1f, 1f, 0.45f)
        shapes.circle(knobX, h - knobY, joyR * 0.4f)
        shapes.setColor(0.1f, 0.1f, 0.12f, 0.55f)
        shapes.circle(fireX, h - fireY, fireR)
        shapes.setColor(1f, 1f, 1f, if (firing) 1f else 0.85f)
        shapes.circle(fireX, h - fireY, fireR * 0.55f)
        shapes.setColor(0.9f, 0.15f, 0.15f, if (firing) 1f else 0.9f)
        shapes.circle(fireX, h - fireY, fireR * 0.42f)

        // round buttons
        shapes.setColor(0.08f, 0.09f, 0.12f, 0.6f)
        shapes.circle(reloadX, h - reloadY, reloadR)
        if (reloading) {
            shapes.setColor(1f, 0.6f, 0.1f, 0.7f)
            shapes.circle(reloadX, h - reloadY, reloadR * 0.8f)
        }
        shapes.setColor(0.08f, 0.09f, 0.12f, 0.6f)
        shapes.circle(camX, h - camY, camR)
        shapes.circle(jumpX, h - jumpY, jumpR)
        if (sprint) shapes.setColor(1f, 0.6f, 0.1f, 0.75f) else shapes.setColor(0.08f, 0.09f, 0.12f, 0.6f)
        shapes.circle(runX, h - runY, runR)
        shapes.setColor(0.08f, 0.09f, 0.12f, 0.6f)
        shapes.circle(medX, h - medY, medR)
        if (medkits > 0 && hp < 100f) shapes.setColor(1f, 0.35f, 0.4f, 1f) else shapes.setColor(0.7f, 0.7f, 0.7f, 0.7f)
        shapes.rect(medX - 24f, h - medY - 7f, 48f, 14f)
        shapes.rect(medX - 7f, h - medY - 24f, 14f, 48f)
        if (nl != null) {
            shapes.setColor(1f, 0.8f, 0.1f, 0.75f)
            shapes.circle(pickX, h - pickY, pickR)
        }

        // crosshair + hit marker
        shapes.setColor(1f, 1f, 1f, 0.9f)
        shapes.rect(w / 2f - 12f, h / 2f - 1.5f, 24f, 3f)
        shapes.rect(w / 2f - 1.5f, h / 2f - 12f, 3f, 24f)
        if (hitMark > 0f) {
            val cx = w / 2f
            val cy = h / 2f
            if (hitHead) shapes.setColor(1f, 0.85f, 0.1f, 1f) else shapes.setColor(1f, 0.15f, 0.15f, 1f)
            shapes.rectLine(cx - 26f, cy - 26f, cx - 12f, cy - 12f, 4f)
            shapes.rectLine(cx + 26f, cy - 26f, cx + 12f, cy - 12f, 4f)
            shapes.rectLine(cx - 26f, cy + 26f, cx - 12f, cy + 12f, 4f)
            shapes.rectLine(cx + 26f, cy + 26f, cx + 12f, cy + 12f, 4f)
        }

        // health + armor (bottom centre)
        val hbx = w / 2f - 260f
        shapes.setColor(0f, 0f, 0f, 0.55f)
        shapes.rect(hbx, 40f, 520f, 26f)
        shapes.setColor(0.95f, 0.95f, 0.95f, 0.95f)
        shapes.rect(hbx, 40f, 520f * (hp.coerceAtLeast(0f) / 100f), 26f)
        shapes.setColor(0f, 0f, 0f, 0.55f)
        shapes.rect(hbx, 70f, 520f, 12f)
        shapes.setColor(0.3f, 0.6f, 1f, 0.95f)
        shapes.rect(hbx, 70f, 520f * (armor / 100f).coerceIn(0f, 1f), 12f)

        // weapon panel (top-right)
        val pby = h - panelY - panelH
        shapes.setColor(0.06f, 0.08f, 0.13f, 0.8f)
        shapes.rect(panelX, pby, panelW, panelH)
        shapes.setColor(0.85f, 0.12f, 0.18f, 0.95f)
        shapes.rect(panelX, pby, 6f, panelH)
        val frac = if (reloading) (1f - reloadT / def.reload).coerceIn(0f, 1f) else slot.mag.toFloat() / def.mag
        shapes.setColor(0.3f, 0.3f, 0.35f, 0.8f)
        shapes.rect(panelX + 16f, pby + 12f, panelW - 32f, 9f)
        shapes.setColor(1f, 1f, 1f, 0.95f)
        shapes.rect(panelX + 16f, pby + 12f, (panelW - 32f) * frac, 9f)
        shapes.setColor(1f, 1f, 1f, 0.95f)
        shapes.identity()
        shapes.translate(panelX + panelW - 130f, pby + panelH * 0.58f, 0f)
        shapes.scale(1.25f, 1.25f, 1f)
        gunIcon(slot.type, 0f, 0f)
        shapes.identity()
        // two small weapon boxes
        for (i in 0..1) {
            val bx = slotX(i)
            val by = h - slotY - slotH
            if (i == cur && slots[i].type >= 0) shapes.setColor(0.85f, 0.12f, 0.18f, 0.85f)
            else if (slots[i].type >= 0) shapes.setColor(0.06f, 0.08f, 0.13f, 0.75f)
            else shapes.setColor(0.06f, 0.08f, 0.13f, 0.4f)
            shapes.rect(bx, by, slotW, slotH)
            if (slots[i].type >= 0) {
                shapes.setColor(1f, 1f, 1f, if (i == cur) 1f else 0.85f)
                shapes.identity()
                shapes.translate(bx + slotW / 2f, by + slotH * 0.52f, 0f)
                shapes.scale(0.72f, 0.72f, 1f)
                gunIcon(slots[i].type, 0f, 0f)
                shapes.identity()
            }
        }

        // minimap (top-left)
        shapes.setColor(0f, 0.15f, 0f, 0.55f)
        shapes.rect(mcx - 150f, mcy - 150f, 300f, 300f)
        shapes.end()

        Gdx.gl.glLineWidth(3f)
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(0.3f, 0.5f, 1f, 1f)
        shapes.circle(mcx + zoneCx * ms, mcy - zoneCz * ms, zoneR * ms, 64)
        if (!shrinking && !won) {
            shapes.setColor(1f, 1f, 1f, 1f)
            shapes.circle(mcx + toCx * ms, mcy - toCz * ms, toR * ms, 64)
        }
        shapes.end()

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(1f, 0.2f, 0.2f, 1f)
        for (b in bots) {
            val ddx = b.pos.x - cam.position.x
            val ddz = b.pos.z - cam.position.z
            if (ddx * ddx + ddz * ddz < 120f * 120f) shapes.circle(mcx + b.pos.x * ms, mcy - b.pos.z * ms, 3f)
        }
        shapes.setColor(1f, 0.9f, 0.2f, 1f)
        for (l in loot) {
            val ddx = l.x - cam.position.x
            val ddz = l.z - cam.position.z
            if (ddx * ddx + ddz * ddz < 120f * 120f) shapes.circle(mcx + l.x * ms, mcy - l.z * ms, 2f)
        }
        shapes.setColor(1f, 1f, 0f, 1f)
        shapes.circle(mcx + cam.position.x * ms, mcy - cam.position.z * ms, 6f)
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(1f, 1f, 0f, 1f)
        val px = mcx + cam.position.x * ms
        val py = mcy - cam.position.z * ms
        shapes.line(px, py, px + cam.direction.x * 22f, py - cam.direction.z * 22f)
        shapes.end()

        sb.begin()
        if (!won) {
            val remaining = if (!shrinking) holdTimes[phase] - phaseTime else shrinkTimes[phase] - phaseTime
            val zlabel = if (!shrinking) "Zone ${phase + 1}/${holdTimes.size} closes in ${fmt(remaining)}"
                         else "ZONE CLOSING ${fmt(remaining)}"
            centered(zlabel, h - 30f, w * 0.76f)
        }
        font.data.setScale(2f)
        centered("ALIVE ${bots.size + (if (hp > 0f) 1 else 0)}    KILLS $kills    ${fmt(1200f - matchTime)}", h - 95f, w * 0.76f)
        font.data.setScale(1.4f)
        font.draw(sb, "FPS ${Gdx.graphics.framesPerSecond}", 40f, h - 20f)
        font.draw(sb, "HP ${hp.coerceAtLeast(0f).toInt()}/100", w / 2f - 60f, 38f)

        // weapon panel texts
        font.data.setScale(3.4f)
        font.draw(sb, "${slot.mag}", panelX + 22f, h - panelY - 14f)
        val ml = layout
        ml.setText(font, "${slot.mag}")
        font.data.setScale(1.7f)
        font.draw(sb, "/ $reserve", panelX + 30f + ml.width, h - panelY - 40f)
        font.data.setScale(1.7f)
        font.draw(sb, if (reloading) "Reload" else def.name, panelX + 22f, h - panelY - 90f)
        // small weapon boxes
        font.data.setScale(1.4f)
        for (i in 0..1) {
            val bx = slotX(i)
            val by = h - slotY - slotH
            if (slots[i].type >= 0) {
                label(weapons[slots[i].type].name, bx + slotW / 2f, by + slotH - 16f)
                label("${slots[i].mag}", bx + slotW / 2f, by + 16f)
            } else {
                label("EMPTY", bx + slotW / 2f, by + slotH / 2f)
            }
        }
        label("x$medkits", medX + 38f, h - medY + 40f)

        if (msgT > 0f) centered(msg, h * 0.64f, w)
        if (nl != null) centered("Weapon: " + weapons[nl.kind].name + " - tap PICK", h * 0.34f, w)

        font.data.setScale(1.8f)
        label("RELOAD", reloadX, h - reloadY)
        label(if (tpp) "1ST" else "3RD", camX, h - camY)
        label("RUN", runX, h - runY)
        label("JUMP", jumpX, h - jumpY)
        if (nl != null) label("PICK", pickX, h - pickY)
        font.data.setScale(3f)

        if (outside) centered("OUTSIDE ZONE! Run to the circle", h * 0.82f, w)
        if (hp <= 0f) centered("YOU DIED - Kills: $kills - tap to restart", h * 0.55f, w)
        else if (won && bots.isEmpty()) centered("WINNER WINNER! Kills: $kills - tap to restart", h * 0.55f, w)
        else if (won) centered("YOU SURVIVED! Kills: $kills - tap to restart", h * 0.55f, w)
        sb.end()

        Gdx.gl.glDisable(GL20.GL_BLEND)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
    }

    override fun dispose() {
        batch.dispose()
        shapes.dispose()
        sb.dispose()
        font.dispose()
        models.forEach { it.dispose() }
        texCache.values.forEach { it.dispose() }
    }
}

// zone wall without top/bottom caps (caps caused flickering)
fun openCylinder(mb: ModelBuilder, divisions: Int, material: Material, attrs: Long): Model {
    mb.begin()
    mb.part("wall", GL20.GL_TRIANGLES, attrs, material)
        .cylinder(2f, 150f, 2f, divisions, 0f, 360f, false)
    return mb.end()
}

// true if the segment a->b passes through the box
fun segHitsBox(a: Vector3, b: Vector3, box: BoundingBox): Boolean {
    val dir = Vector3(b).sub(a)
    val len = dir.len()
    if (len < 0.01f) return false
    val ray = Ray(Vector3(a), dir.nor())
    val hit = Vector3()
    return Intersector.intersectRayBounds(ray, box, hit) && hit.dst(a) < len
}
