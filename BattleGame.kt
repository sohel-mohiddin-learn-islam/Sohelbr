package com.sohelbr.myapp

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
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
import com.badlogic.gdx.graphics.g3d.model.MeshPart
import com.badlogic.gdx.graphics.g3d.model.Node
import com.badlogic.gdx.graphics.g3d.model.NodePart
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
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Bot(val gender: Int, val outfit: Int, val pos: Vector3, var hp: Float = 200f) {
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
    var downT = 0f
    var vehCd = 0f
}

class Actor(val inst: ModelInstance, val ctrl: AnimationController, val g: Int) {
    var cur = ""
    var hand: Node? = null
    var spine: Node? = null
}

class ActorMeta(val handAimInv: Matrix4, val G: Vector3, val spineInv: Matrix4, val B: Vector3)

class Block(val x: Float, val z: Float, val hw: Float, val hd: Float, val h: Float, val y0: Float = 0f) {
    val box = BoundingBox(Vector3(x - hw, y0, z - hd), Vector3(x + hw, y0 + h, z + hd))
    var mark = 0
}

class Part(val inst: ModelInstance, val ox: Float, val oy: Float, val oz: Float, val ry: Float, val rx: Float)
class Prop(val parts: ArrayList<Part>)
class WDef(
    val name: String, val dmg: Float, val cd: Float, val mag: Int, val reload: Float,
    val range: Float, val auto: Boolean, val viewScale: Float, val muzzleY: Float, val muzzleZ: Float,
    val cat: Int = -1
)
class Slot(var type: Int, var mag: Int)
class Loot(var kind: Int, var x: Float, var z: Float, var mag: Int, var y: Float = 0f)

class BattleGame : ApplicationAdapter() {
    // ---- tuning ----
    // 1f = real time (20 min match). Set 10f to test the whole zone cycle in 2 minutes.
    private val timeScale = 1f
    private val botCount = 49
    private val startRadius = 1700f
    private val zoneRadii = floatArrayOf(1000f, 600f, 330f, 170f, 70f, 0f)
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
    private val holdScale = floatArrayOf(1.0f, 0.9f, 0.85f, 0.75f, 1f, 1f, 1f, 0.7f).copyOf(40)
    private var pActor: Actor? = null
    private var pArmedT = 0f
    // plane drop: 0 = in the plane, 1 = free fall, 2 = parachute, 3 = on the ground
    private var dropState = 3
    private var planeX = 0f
    private var planeZ = 0f
    private val planeY = 350f
    private var mapOpen = false
    private var mapZoom = 1f
    private var mapCx = 0f
    private var mapCz = 0f
    private var pinchPrev = 0f
    private var miniLvl = 1
    private val miniRanges = floatArrayOf(160f, 320f, 640f)
    private val bGrid = HashMap<Int, ArrayList<Block>>()
    private val tmpB = ArrayList<Block>()
    private var markStamp = 0
    private lateinit var world: TerrainWorld
    private var planeDX = 1f
    private var planeDZ = 0f
    private var planeYaw = 0f
    private var planeActive = false
    private var dropVy = 0f
    private lateinit var planeProp: Prop
    private lateinit var canopy: ModelInstance
    private var planeInst: ModelInstance? = null
    private var tpLen = 3.2f
    // grip point (in gun space) and distance between the two hands for each gun
    private val gripY = floatArrayOf(-0.055f, -0.09f, -0.10f, -0.10f, 0f, 0f, 0f, -0.12f).copyOf(40)
    private val gripZ = floatArrayOf(0.045f, 0.09f, 0.08f, 0.14f, 0f, 0f, 0f, 0.12f).copyOf(40)
    private val holdD = floatArrayOf(0.18f, 0.33f, 0.48f, 0.50f, 0f, 0f, 0f, 0.45f)
    private val itemProps = ArrayList<Prop>()
    private val beams = ArrayList<ModelInstance>()
    private val loot = ArrayList<Loot>()
    private val taps = ArrayList<FloatArray>()
    private val rnd = Random()

    // human characters: [gender][outfit][frame]  frames 0..3 = run cycle, 4 = aiming
    private var loadIdx = 0
    private val loadTotal = 5
    private var humanLoader: G3dModelLoader? = null
    private var humanProvider: TextureProvider? = null
    private var humansOk = false
    private val texCache = HashMap<String, Texture>()

    private val slots = arrayOf(Slot(0, 12), Slot(-1, 0), Slot(-1, 0))
    private var bpLevel = 0
    private val bpInst = arrayOfNulls<ModelInstance>(3)
    private fun isGun(k: Int) = k in 0..3 || k == 7 || k in 11..37
    private fun gunCat(t: Int): Int = if (t >= 11) weapons[t].cat else if (t == 0) 0 else if (t == 1) 1 else if (t == 3) 3 else 2
    private fun ammoCap() = intArrayOf(90, 150, 220, 300)[bpLevel]
    private fun medCap() = intArrayOf(2, 3, 4, 6)[bpLevel]
    private fun otherType(): Int {
        for (d in 1..2) { val i = (cur + d) % 3; if (slots[i].type >= 0) return slots[i].type }
        return -1
    }
    private fun lootName(k: Int): String = if (isGun(k)) weapons[k].name else if (k in 8..10) "BACKPACK LV" + (k - 7) else "ITEM"
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
    private var carInst: ModelInstance? = null
    private var bikeInst: ModelInstance? = null
    private var sbikeInst: ModelInstance? = null
    private var mtbInst: ModelInstance? = null
    private class WheelDef(val file: String, val x: Float, val y: Float, val z: Float, val r: Float, val steer: Boolean)
    private val wheelDefs = arrayOf(
        arrayOf(WheelDef("car_w0.vmesh", -0.718f, 0.295f, 1.113f, 0.295f, true), WheelDef("car_w1.vmesh", 0.674f, 0.295f, 1.18f, 0.295f, true),
                WheelDef("car_w2.vmesh", -0.685f, 0.295f, -1.05f, 0.295f, false), WheelDef("car_w3.vmesh", 0.658f, 0.295f, -1.05f, 0.295f, false)),
        arrayOf(WheelDef("bike_w0.vmesh", -0.034f, 0.362f, -0.566f, 0.362f, false), WheelDef("bike_w1.vmesh", -0.013f, 0.364f, 0.606f, 0.362f, true)),
        arrayOf(WheelDef("sbike_w0.vmesh", 0.024f, 0.297f, 0.755f, 0.297f, true), WheelDef("sbike_w1.vmesh", -0.003f, 0.317f, -0.718f, 0.317f, false)),
        arrayOf(WheelDef("bike2_w0.vmesh", 0.002f, 0.352f, 0.544f, 0.352f, true), WheelDef("bike2_w1.vmesh", -0.001f, 0.364f, -0.533f, 0.352f, false)))
    private val wheelInst = arrayOfNulls<Array<ModelInstance?>>(4)
    private val vTmp = Matrix4()
    private val wTmp = Matrix4()
    private fun vehMatrix(vh: Vehicle, out: Matrix4): Matrix4 =
        out.setToTranslation(vh.x, vh.y, vh.z).rotate(Vector3.Y, vh.yaw + 180f).rotate(Vector3.X, -vh.pitch)
    private fun drawVehicle(vh: Vehicle, m: Matrix4) {
        val inst = vehInst(vh.type) ?: return
        val tv = if (vh.wrecked) 0.16f else 0.9f
        tint(inst, tv)
        inst.transform.set(m)
        batch.render(inst, env)
        val ws = wheelInst[vh.type] ?: return
        val defs = wheelDefs[vh.type]
        for (i in defs.indices) {
            val wi = ws[i] ?: continue
            val d = defs[i]
            wTmp.set(m).translate(d.x, d.y, d.z)
            if (d.steer) wTmp.rotate(Vector3.Y, vh.steer)
            wTmp.rotate(Vector3.X, vh.dist / d.r * 57.29578f)
            tint(wi, tv)
            wi.transform.set(wTmp)
            batch.render(wi, env)
        }
    }
    private fun tint(inst: ModelInstance, v: Float) {
        for (mt in inst.materials) {
            val ca = mt.get(ColorAttribute.Diffuse) as? ColorAttribute
            if (ca != null) ca.color.set(v, v, v, 1f)
        }
    }
    private fun vehInst(t: Int): ModelInstance? = when (t) { 0 -> carInst; 1 -> bikeInst; 2 -> sbikeInst; else -> mtbInst }
    private var paraInst: ModelInstance? = null
    private val vehicles = ArrayList<Vehicle>()
    private var driving: Vehicle? = null
    private var nearVeh: Vehicle? = null
    private var tppBeforeDrive = true
    private var lookT = 0f
    private var chuteT = 0f
    private var stance = 0          // 0 standing, 1 sitting/crouched, 2 prone
    private var eyeH = 1.7f
    private var jumpV = 0f
    private var pSpeed = 7f
    private var pYaw = 180f
    private var pMoving = false
    private var pMoveYaw = 0f

    private fun tpOffset(): Vector3 {
        val back = Vector3(cam.direction).scl(-1f)
        val rgt = Vector3(cam.direction).crs(Vector3.Y).nor()
        val dv = driving
        return if (dv == null) Vector3(back).scl(3.1f).add(rgt.scl(0.55f)).add(0f, 0.45f, 0f)
        else if (dv.type == 0) Vector3(back).scl(6.8f).add(0f, 1.6f, 0f)
        else if (dv.type == 2) Vector3(back).scl(4.8f).add(0f, 1.1f, 0f)
        else Vector3(back).scl(4.2f).add(0f, 1.0f, 0f)
    }

    // Free Fire style camera: orbits a pivot at the player's shoulders, a bit to the right, pulled in by walls
    private fun tpWanted(eye: Vector3): Float {
        val pv = Vector3(eye).add(0f, -0.15f, 0f)
        val off = tpOffset()
        val len = off.len()
        val wd = wallDist(Ray(pv, Vector3(off).nor()), len)
        return minOf(len, wd - 0.4f).coerceAtLeast(0.5f)
    }

    private fun tpCamPos(eye: Vector3): Vector3 {
        val pv = Vector3(eye).add(0f, -0.15f, 0f)
        val off = tpOffset()
        val full = off.len()
        val p = pv.add(off.nor().scl(minOf(tpLen, full)))
        val gmin = world.groundY(p.x, p.z, pv.y - 1.0f) + 0.5f
        if (p.y < gmin) p.y = gmin
        return p
    }

    private var yaw = 0f
    private var pitch = 0f
    private var hp = 200f
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
        cam.far = 1000f

        gunCam = PerspectiveCamera(55f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        gunCam.near = 0.05f
        gunCam.far = 20f
        gunCam.update()

        world = TerrainWorld()
        world.load()
        blocks.addAll(world.blocks)
        indexBlocks()
        env.set(ColorAttribute(ColorAttribute.Fog, 0.5f, 0.8f, 1f, 1f))

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
        buildPlane()

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
                    try {
                        val pmod = loadModelFile("plane.g3dj")
                        for (mt in pmod.materials) mt.set(IntAttribute.createCullFace(0))
                        planeInst = ModelInstance(pmod)
                    } catch (e: Throwable) {
                        Gdx.app.error("BattleGame", "plane model missing, using simple plane", e)
                    }
                }
                4 -> loadVehicles()
            }
            loadIdx++
        } catch (e: Throwable) {
            Gdx.app.error("BattleGame", "Could not load character models", e)
            humansOk = false
            loadIdx = loadTotal
        }
    }

    private fun loadVehicles() {
        try { carInst = ModelInstance(loadVMesh("car.vmesh", null).also { models.add(it) }) } catch (e: Throwable) { Gdx.app.error("BattleGame", "car missing", e) }
        try { bikeInst = ModelInstance(loadVMesh("bike.vmesh", null).also { models.add(it) }) } catch (e: Throwable) { Gdx.app.error("BattleGame", "bike missing", e) }
        try { sbikeInst = ModelInstance(loadVMesh("sbike.vmesh", null).also { models.add(it) }) } catch (e: Throwable) { Gdx.app.error("BattleGame", "sbike missing", e) }
        try { mtbInst = ModelInstance(loadVMesh("bike2.vmesh", null).also { models.add(it) }) } catch (e: Throwable) { Gdx.app.error("BattleGame", "mtb missing", e) }
        for (t in 0..3) {
            val defs = wheelDefs[t]
            wheelInst[t] = Array(defs.size) { i ->
                try { ModelInstance(loadVMesh(defs[i].file, null).also { models.add(it) }) } catch (e: Throwable) { Gdx.app.error("BattleGame", "wheel missing", e); null }
            }
        }
        for (k in 0..2) {
            try { bpInst[k] = ModelInstance(loadVMesh("bp" + (k + 1) + ".vmesh", null).also { models.add(it) }) } catch (e: Throwable) { Gdx.app.error("BattleGame", "backpack missing", e) }
        }
        try { paraInst = ModelInstance(loadVMesh("para.vmesh", getTex("para.jpg")).also { models.add(it) }) } catch (e: Throwable) { Gdx.app.error("BattleGame", "parachute missing", e) }
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

    private fun drawActor(a: Actor, x: Float, y: Float, z: Float, yawDeg: Float, held: Int, back: Int, armed: Boolean, fire: Boolean, pk: Int = 0) {
        a.inst.transform.setToTranslation(x, y, z).rotate(Vector3.Y, yawDeg)
        batch.render(a.inst, env)
        carry(a, held, back, armed, fire, pk)
    }

    // guns are slung on the back (like Free Fire); the current gun comes into the hands while shooting.
    // Everything follows the animated bones (hand_r and spine_03).
    private fun carry(a: Actor, held: Int, back: Int, armed: Boolean, fire: Boolean, pk: Int = 0) {
        val mt = meta[a.g] ?: return
        val base = a.inst.transform
        var s1 = back
        var s2 = -1
        val hn = a.hand
        val sn = a.spine
        if (armed && isGun(held) && hn != null) {
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
            if (isGun(s1)) slung(sRel, mt.B, s1, 0)
            if (isGun(s2)) slung(sRel, mt.B, s2, 1)
            if (pk > 0) {
                val pi = bpInst[pk - 1]
                if (pi != null) {
                    pi.transform.set(sRel).translate(mt.B.x, mt.B.y - 0.08f, mt.B.z + 0.04f)
                    batch.render(pi, env)
                }
            }
        }
    }

    private fun slung(base: Matrix4, B: Vector3, type: Int, slot: Int) {
        val bm = Matrix4(base)
        if (gunCat(type) == 0 || gunCat(type) == 4) {
            // pistol in a holster on the hip
            bm.translate(B.x - 0.22f + 0.44f * slot, B.y - 0.62f, B.z + 0.16f).rotate(Vector3.Y, 180f).scale(1.1f, 1.1f, 1.1f)
        } else {
            // rifle strap over the back: barrel up over the shoulder, side of the gun against the back
            val tilt = if (slot == 0) 10f else -10f
            val sc = if (type == 7) 0.5f else if (gunCat(type) == 3) 0.7f else 0.8f
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

    // ------------------------------------------------------------------
    // 25 real-world guns (kinds 11..35), built from boxes by category
    // ------------------------------------------------------------------
    private class GS(val name: String, val cat: Int, val dmg: Float, val cd: Float, val mag: Int, val reload: Float,
                     val range: Float, val auto: Boolean, val len: Float, val body: Color, val acc: Color, val fl: Int)

    private fun gc(r: Float, g: Float, b: Float) = Color(r, g, b, 1f)

    private fun gunSpecs(): List<GS> {
        val SC = 1; val BP = 2; val CH = 4; val DR = 8; val NS = 16; val BI = 32; val SU = 64; val WD = 128; val CV = 256; val BR = 512
        return listOf(
            // pistols (cat 0)
            GS("USP", 0, 24f, 0.27f, 15, 1.3f, 140f, false, 1.05f, gc(0.2f, 0.2f, 0.22f), gc(0.8f, 0.8f, 0.85f), 0),
            GS("GLOCK 19", 0, 20f, 0.24f, 15, 1.2f, 130f, false, 0.95f, gc(0.12f, 0.12f, 0.12f), gc(1f, 0.3f, 0.1f), 0),
            GS("P320", 0, 21f, 0.25f, 17, 1.25f, 135f, false, 1.0f, gc(0.55f, 0.45f, 0.3f), gc(0.2f, 0.2f, 0.2f), 0),
            GS("M92FS", 0, 22f, 0.27f, 15, 1.3f, 140f, false, 1.1f, gc(0.18f, 0.18f, 0.2f), gc(0.75f, 0.75f, 0.7f), 0),
            GS("M1911", 0, 34f, 0.35f, 7, 1.4f, 120f, false, 1.0f, gc(0.25f, 0.25f, 0.25f), gc(0.85f, 0.75f, 0.4f), WD),
            // submachine guns (cat 1)
            GS("MP5", 1, 15f, 0.075f, 30, 1.6f, 170f, true, 1.0f, gc(0.12f, 0.12f, 0.13f), gc(0.5f, 0.5f, 0.55f), 0),
            GS("P90", 1, 14f, 0.065f, 50, 2.0f, 190f, true, 1.0f, gc(0.2f, 0.22f, 0.2f), gc(1f, 0.6f, 0.1f), BP or NS),
            GS("VECTOR", 1, 17f, 0.055f, 25, 1.5f, 150f, true, 0.9f, gc(0.15f, 0.15f, 0.17f), gc(0.2f, 0.9f, 0.4f), SC or NS),
            GS("SCORPION", 1, 15f, 0.08f, 30, 1.6f, 160f, true, 1.0f, gc(0.1f, 0.1f, 0.1f), gc(0.9f, 0.9f, 0.9f), 0),
            GS("MP7", 1, 13f, 0.07f, 40, 1.7f, 190f, true, 0.9f, gc(0.25f, 0.27f, 0.22f), gc(0.3f, 0.3f, 0.3f), SC or SU),
            // assault rifles (cat 2)
            GS("M4A1", 2, 24f, 0.09f, 30, 2.0f, 280f, true, 1.0f, gc(0.15f, 0.15f, 0.15f), gc(0.5f, 0.5f, 0.55f), SC),
            GS("AK-47", 2, 30f, 0.1f, 30, 2.1f, 250f, true, 1.0f, gc(0.2f, 0.18f, 0.15f), gc(0.5f, 0.3f, 0.1f), WD or CV),
            GS("SCAR-L", 2, 25f, 0.095f, 30, 2.0f, 290f, true, 1.05f, gc(0.55f, 0.45f, 0.3f), gc(0.2f, 0.2f, 0.2f), SC),
            GS("HK416", 2, 25f, 0.09f, 30, 2.0f, 300f, true, 1.05f, gc(0.12f, 0.12f, 0.12f), gc(0.3f, 0.6f, 1f), SC),
            GS("FAMAS", 2, 22f, 0.067f, 25, 2.2f, 250f, true, 0.9f, gc(0.25f, 0.3f, 0.2f), gc(0.6f, 0.6f, 0.6f), BP or CH),
            GS("AUG A3", 2, 23f, 0.09f, 30, 2.0f, 280f, true, 0.9f, gc(0.2f, 0.3f, 0.15f), gc(0.2f, 0.2f, 0.2f), BP or SC),
            GS("QBZ-95", 2, 24f, 0.092f, 30, 2.1f, 260f, true, 0.9f, gc(0.3f, 0.3f, 0.25f), gc(0.2f, 0.2f, 0.2f), BP or CH),
            // sniper rifles (cat 3)
            GS("AWM", 3, 120f, 1.5f, 5, 3.0f, 650f, false, 1.1f, gc(0.2f, 0.25f, 0.15f), gc(0.9f, 0.8f, 0.2f), SC or BI),
            GS("BARRETT", 3, 115f, 0.9f, 10, 3.2f, 700f, false, 1.1f, gc(0.15f, 0.15f, 0.15f), gc(0.8f, 0.2f, 0.2f), SC or DR or BR or BI),
            GS("M200", 3, 125f, 1.4f, 7, 3.0f, 750f, false, 1.2f, gc(0.25f, 0.2f, 0.15f), gc(0.9f, 0.8f, 0.2f), SC or BI),
            GS("SVD", 3, 75f, 0.5f, 10, 2.5f, 500f, false, 1.0f, gc(0.3f, 0.22f, 0.12f), gc(0.5f, 0.3f, 0.1f), SC or WD or DR),
            GS("R700", 3, 100f, 1.2f, 5, 2.8f, 550f, false, 1.0f, gc(0.15f, 0.15f, 0.15f), gc(0.8f, 0.8f, 0.8f), SC or WD),
            // machine pistols (cat 4)
            GS("G18C", 4, 14f, 0.055f, 33, 1.5f, 120f, true, 0.95f, gc(0.1f, 0.1f, 0.1f), gc(1f, 0.3f, 0.1f), 0),
            GS("VZ.61", 4, 11f, 0.07f, 20, 1.4f, 110f, true, 1.0f, gc(0.3f, 0.3f, 0.32f), gc(0.8f, 0.8f, 0.8f), 0),
            GS("MAC-10", 4, 13f, 0.048f, 30, 1.5f, 100f, true, 0.85f, gc(0.22f, 0.22f, 0.24f), gc(0.6f, 0.6f, 0.6f), 0),
            GS("DESERT EAGLE", 0, 52f, 0.4f, 7, 1.6f, 150f, false, 1.25f, gc(0.7f, 0.7f, 0.72f), gc(0.2f, 0.2f, 0.2f), 0),
            GS("M500", 0, 85f, 0.75f, 5, 2.4f, 170f, false, 1.5f, gc(0.6f, 0.6f, 0.62f), gc(0.15f, 0.15f, 0.15f), WD)
        )
    }

    private fun addGun(sp: GS) {
        val k = weapons.size
        val p = ArrayList<Part>()
        val F = { f: Int -> (sp.fl and f) != 0 }
        val furn = if (F(128)) wood else black
        var muzzle = 0f
        var my = 0.005f
        when (sp.cat) {
            0, 4 -> {
                val sl = 0.26f * sp.len
                val front = -0.06f - sl / 2f
                mk(p, 0.05f, 0.04f, sl - 0.02f, dark, 0f, 0f, -0.05f)
                mk(p, 0.055f, 0.06f, sl, sp.body, 0f, 0.04f, -0.06f)
                mk(p, 0.058f, 0.012f, sl, sp.acc, 0f, 0.076f, -0.06f)
                mk(p, 0.025f, 0.025f, 0.05f, black, 0f, 0.04f, front - 0.015f)
                mk(p, 0.05f, 0.14f, 0.07f, if (F(128)) wood else black, 0f, -0.08f, 0.04f)
                mk(p, 0.04f, 0.02f, 0.07f, dark, 0f, -0.04f, -0.03f)
                mk(p, 0.015f, 0.02f, 0.015f, black, 0f, 0.088f, front + 0.02f)
                mk(p, 0.025f, 0.02f, 0.015f, black, 0f, 0.088f, 0.06f)
                muzzle = front - 0.04f
                my = 0.04f
                if (sp.cat == 4) {
                    mk(p, 0.04f, 0.18f, 0.05f, dark, 0f, -0.21f, 0.045f)
                    mk(p, 0.04f, 0.04f, 0.08f, black, 0f, 0.0f, front - 0.04f)
                }
                addRightArm(p, -0.075f, 0.045f)
            }
            1 -> {
                val bl = 0.32f * sp.len
                val front = -0.02f - bl / 2f
                val bl2 = if (F(64)) 0.22f else 0.10f
                mk(p, 0.07f, 0.10f, bl, sp.body, 0f, 0f, -0.02f)
                mk(p, 0.075f, 0.012f, bl + 0.01f, sp.acc, 0f, 0.056f, -0.02f)
                mk(p, 0.05f, 0.06f, 0.16f, gray, 0f, 0.005f, front - 0.08f)
                mk(p, 0.022f, 0.022f, bl2, black, 0f, 0.005f, front - 0.16f - bl2 / 2f)
                if (F(2)) mk(p, 0.06f, 0.035f, bl * 0.9f, dark, 0f, 0.075f, -0.04f)
                else {
                    mk(p, 0.04f, 0.26f, 0.06f, dark, 0f, -0.17f, -0.04f)
                    mk(p, 0.03f, 0.035f, 0.10f, black, 0f, 0.075f, -0.04f)
                }
                mk(p, 0.05f, 0.13f, 0.06f, black, 0f, -0.11f, 0.09f)
                if (!F(16)) {
                    mk(p, 0.035f, 0.05f, 0.18f, gray, 0f, 0.005f, 0.24f)
                    mk(p, 0.05f, 0.10f, 0.03f, black, 0f, -0.01f, 0.34f)
                }
                if (F(1)) mk(p, 0.04f, 0.05f, 0.10f, black, 0f, 0.10f, -0.04f)
                muzzle = front - 0.16f - bl2 - 0.01f
                addRightArm(p, -0.11f, 0.09f)
                addLeftArm(p, -0.07f, front - 0.06f)
            }
            2 -> {
                val rl = 0.45f * sp.len
                val front = -0.05f - rl / 2f
                val bl2 = if (F(64)) 0.34f else 0.22f
                mk(p, 0.07f, 0.10f, rl, sp.body, 0f, 0f, -0.05f)
                mk(p, 0.075f, 0.012f, rl + 0.01f, sp.acc, 0f, 0.056f, -0.05f)
                mk(p, 0.065f, 0.07f, 0.30f, if (F(128)) wood else gray, 0f, -0.005f, front - 0.125f)
                mk(p, 0.025f, 0.025f, bl2, black, 0f, 0.005f, front - 0.25f - bl2 / 2f)
                mk(p, 0.03f, 0.03f, 0.05f, black, 0f, 0.005f, front - 0.25f - bl2 - 0.02f)
                mk(p, 0.014f, 0.045f, 0.014f, black, 0f, 0.045f, front - 0.2f)
                if (F(2)) {
                    mk(p, 0.07f, 0.11f, 0.26f, sp.body, 0f, -0.005f, 0.17f)
                    mk(p, 0.045f, 0.20f, 0.075f, dark, 0f, -0.15f, 0.02f)
                } else {
                    mk(p, 0.06f, 0.10f, 0.24f, furn, 0f, -0.015f, -0.05f + rl / 2f + 0.115f)
                    if (F(256)) mk(p, 0.045f, 0.22f, 0.075f, dark, 0f, -0.15f, -0.13f, 0f, 14f)
                    else mk(p, 0.045f, 0.20f, 0.075f, dark, 0f, -0.15f, -0.13f)
                }
                mk(p, 0.05f, 0.14f, 0.06f, black, 0f, -0.12f, 0.08f)
                if (F(4)) mk(p, 0.04f, 0.05f, 0.28f, black, 0f, 0.09f, -0.05f)
                else mk(p, 0.045f, 0.04f, 0.10f, black, 0f, 0.075f, -0.02f)
                if (F(1)) {
                    mk(p, 0.05f, 0.05f, 0.12f, black, 0f, 0.11f, -0.06f)
                    mkGlow(p, 0.03f, 0.03f, 0.012f, Color(1f, 0.1f, 0.1f, 1f), 0f, 0.11f, -0.125f)
                }
                muzzle = front - 0.25f - bl2 - 0.045f
                addRightArm(p, -0.12f, 0.08f)
                addLeftArm(p, -0.085f, front - 0.125f)
            }
            3 -> {
                val bar = 0.70f * sp.len
                val tip = -0.25f - bar
                val big = F(512)
                mk(p, 0.07f, 0.10f, 0.50f, sp.body, 0f, 0f, 0f)
                mk(p, 0.074f, 0.012f, 0.50f, sp.acc, 0f, 0.056f, 0f)
                mk(p, 0.03f, 0.03f, bar, black, 0f, 0.01f, -0.25f - bar / 2f)
                mk(p, if (big) 0.07f else 0.045f, if (big) 0.07f else 0.045f, if (big) 0.12f else 0.07f, gray, 0f, 0.01f, tip - 0.02f)
                if (F(1)) {
                    mk(p, 0.05f, 0.05f, 0.30f, black, 0f, 0.105f, -0.08f)
                    mk(p, 0.07f, 0.07f, 0.06f, black, 0f, 0.105f, -0.25f)
                    mk(p, 0.06f, 0.06f, 0.05f, black, 0f, 0.105f, 0.08f)
                    mkGlow(p, 0.05f, 0.05f, 0.01f, Color(0.2f, 0.6f, 1f, 1f), 0f, 0.105f, -0.285f)
                    mk(p, 0.03f, 0.04f, 0.03f, gray, 0f, 0.065f, -0.14f)
                    mk(p, 0.03f, 0.04f, 0.03f, gray, 0f, 0.065f, 0.02f)
                }
                if (F(32)) {
                    mk(p, 0.02f, 0.14f, 0.02f, gray, 0.06f, -0.08f, tip + 0.25f, 0f, 20f)
                    mk(p, 0.02f, 0.14f, 0.02f, gray, -0.06f, -0.08f, tip + 0.25f, 0f, 20f)
                }
                mk(p, 0.06f, 0.13f, 0.32f, furn, 0f, -0.02f, 0.40f)
                mk(p, 0.05f, 0.04f, 0.14f, furn, 0f, 0.06f, 0.33f)
                mk(p, 0.05f, 0.13f, 0.06f, black, 0f, -0.12f, 0.14f)
                if (F(8)) mk(p, 0.045f, 0.20f, 0.08f, dark, 0f, -0.15f, -0.10f)
                else mk(p, 0.04f, 0.10f, 0.07f, dark, 0f, -0.10f, -0.10f)
                muzzle = tip - 0.04f - (if (big) 0.06f else 0f)
                my = 0.01f
                addRightArm(p, -0.12f, 0.14f)
                addLeftArm(p, -0.045f, -0.36f)
            }
        }
        // the third-person gun has no first-person arms
        tpProps.add(Prop(ArrayList(p.subList(0, p.size - armParts(sp.cat)))))
        weaponProps.add(Prop(p))
        val vs = when (sp.cat) { 0 -> 1.5f; 4 -> 1.4f; 1 -> 1.1f; 2 -> 1.0f; else -> 0.8f }
        weapons.add(WDef(sp.name, sp.dmg, sp.cd, sp.mag, sp.reload, sp.range, sp.auto, vs, my, muzzle, sp.cat))
        holdScale[k] = when (sp.cat) { 0 -> 1.0f; 4 -> 0.95f; 1 -> 0.9f; 2 -> 0.85f; else -> 0.75f }
        gripY[k] = when (sp.cat) { 0 -> -0.055f; 4 -> -0.055f; 1 -> -0.09f; 2 -> -0.10f; else -> -0.10f }
        gripZ[k] = when (sp.cat) { 0 -> 0.045f; 4 -> 0.045f; 1 -> 0.09f; 2 -> 0.08f; else -> 0.14f }
    }

    private fun armParts(cat: Int): Int = when (cat) { 0 -> 2; 4 -> 2; else -> 4 }

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
        // dummy entries for the item kinds 4..6 so the minigun can be kind 7
        for (k in 0 until 3) {
            weapons.add(WDef("-", 0f, 1f, 1, 1f, 1f, false, 1f, 0f, 0f))
            tpProps.add(Prop(ArrayList()))
            weaponProps.add(Prop(ArrayList()))
        }
        // 7: MINIGUN (six barrels, heavy weapon box)
        run {
            val olive = Color(0.35f, 0.38f, 0.2f, 1f)
            val p = ArrayList<Part>()
            mk(p, 0.16f, 0.16f, 0.5f, dark, 0f, 0f, 0f)
            mk(p, 0.10f, 0.10f, 0.3f, gray, 0f, 0.12f, 0.05f)
            for (k in 0 until 6) {
                val a = k * 1.0472f
                mk(p, 0.03f, 0.03f, 0.7f, black, cos(a) * 0.055f, sin(a) * 0.055f, -0.6f)
            }
            mk(p, 0.16f, 0.16f, 0.04f, gray, 0f, 0f, -0.42f)
            mk(p, 0.16f, 0.16f, 0.04f, gray, 0f, 0f, -0.80f)
            mk(p, 0.06f, 0.15f, 0.08f, black, 0f, -0.15f, 0.12f)
            mk(p, 0.22f, 0.24f, 0.30f, olive, 0.2f, -0.08f, 0.18f)
            tpProps.add(Prop(ArrayList(p)))
            addRightArm(p, -0.12f, 0.12f)
            addLeftArm(p, -0.05f, -0.28f)
            weaponProps.add(Prop(p))
            weapons.add(WDef("MINIGUN", 9f, 0.055f, 150, 4.5f, 220f, true, 0.7f, 0f, -0.95f))
        }
        for (k in 0 until 3) {
            weapons.add(WDef("-", 0f, 1f, 1, 1f, 1f, false, 1f, 0f, 0f))
            tpProps.add(Prop(ArrayList()))
            weaponProps.add(Prop(ArrayList()))
        }
        for (sp in gunSpecs()) addGun(sp)
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
            Color(0.2f, 0.9f, 1f, 1f),     // armor
            Color(1f, 0.25f, 0.1f, 1f),    // minigun
            Color(0.85f, 0.55f, 0.2f, 1f), // backpack lv1
            Color(0.3f, 0.5f, 1f, 1f),     // backpack lv2
            Color(0.85f, 0.3f, 1f, 1f)     // backpack lv3
        )
        val allCols = ArrayList<Color>()
        for (c in cols) allCols.add(c)
        for (sp in gunSpecs()) allCols.add(when (sp.cat) { 0, 4 -> cols[0]; 1 -> cols[1]; 2 -> cols[2]; else -> cols[3] })
        for (c in allCols) {
            val m = mb.createBox(0.14f, 9f, 0.14f,
                Material(ColorAttribute.createDiffuse(c), ColorAttribute.createEmissive(c), BlendingAttribute(0.35f)),
                attrs)
            models.add(m)
            beams.add(ModelInstance(m))
        }
    }

    private fun buildPlane() {
        val body = Color(0.78f, 0.80f, 0.84f, 1f)
        val dkr = Color(0.30f, 0.33f, 0.40f, 1f)
        val red = Color(0.8f, 0.15f, 0.15f, 1f)
        val p = ArrayList<Part>()
        mk(p, 3.2f, 3.2f, 20f, body, 0f, 0f, 0f)             // fuselage (nose toward -Z)
        mk(p, 2.4f, 2.4f, 3f, dkr, 0f, 0f, -11f)             // nose
        mk(p, 24f, 0.5f, 4.5f, body, 0f, -0.4f, -1f)          // wings
        mk(p, 24.2f, 0.52f, 0.8f, red, 0f, -0.4f, 1.1f)       // wing stripe
        mk(p, 8f, 0.4f, 2.6f, body, 0f, 0.3f, 9.5f)           // tail wing
        mk(p, 0.4f, 3.4f, 2.8f, red, 0f, 2.2f, 9.3f)          // tail fin
        mk(p, 2.6f, 1.2f, 3f, Color(0.2f, 0.5f, 0.9f, 1f), 0f, 1.5f, -6f)  // cockpit glass
        planeProp = Prop(p)
        val cm = mb.createSphere(7f, 3.6f, 7f, 18, 12,
            Material(ColorAttribute.createDiffuse(1f, 0.45f, 0.1f, 1f)), attrs)
        models.add(cm)
        canopy = ModelInstance(cm)
    }

    private fun startFreefall() {
        if (dropState != 0) return
        dropState = 1
        dropVy = 0f
        msg = "FREEFALL - tap CHUTE to open the parachute"
        msgT = 3f
    }

    private fun openChute() {
        if (dropState != 1) return
        dropState = 2
        chuteT = 0f
        msg = "Parachute open - steer to your landing spot"
        msgT = 3f
    }

    // flying in the plane / falling / gliding down
    private fun updateDrop(dt: Float, mx: Float, my: Float, fwd: Vector3, right: Vector3) {
        if (dropState == 0) {
            cam.position.set(planeX, planeY - 1.7f, planeZ)
            if (planeX * planeDX + planeZ * planeDZ > world.MW / 2f + 160f) startFreefall()
            return
        }
        val hs = if (dropState == 1) 30f else 13f
        cam.position.add(Vector3(fwd).scl(my * hs * dt)).add(Vector3(right).scl(mx * hs * dt))
        if (dropState == 1) {
            dropVy = maxOf(dropVy - 25f * dt, -40f)
            if (cam.position.y - world.h(cam.position.x, cam.position.z) < 75f) openChute()
        } else {
            dropVy += (-7f - dropVy) * minOf(1f, 3f * dt)
        }
        cam.position.y += dropVy * dt
        cam.position.x = cam.position.x.coerceIn(-world.maxX, world.maxX)
        cam.position.z = cam.position.z.coerceIn(-world.maxZ, world.maxZ)
        if (cam.position.y <= world.h(cam.position.x, cam.position.z) + 1.7f) {
            snapToLand(cam.position)
            stance = 0
            eyeH = 1.7f
            cam.position.y = world.h(cam.position.x, cam.position.z) + 1.7f
            dropState = 3
            dropVy = 0f
            collide(cam.position, 0.6f)
            msg = "Landed! Find a weapon."
            msgT = 2.5f
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
        if (r < 40) return if (r < 12) 0 else if (r < 24) 1 else if (r < 36) 2 else 3
        // the 25 real-world guns: pistols 11-15, SMGs 16-20, rifles 21-27, snipers 28-32, machine pistols 33-35
        val c = rnd.nextInt(100)
        return if (c < 22) 11 + rnd.nextInt(5) else if (c < 50) 16 + rnd.nextInt(5) else if (c < 80) 21 + rnd.nextInt(7)
        else if (c < 86) 28 + rnd.nextInt(5) else if (c < 93) 33 + rnd.nextInt(3) else 36 + rnd.nextInt(2)
    }

    private fun randKind(): Int {
        val r = rnd.nextInt(100)
        return if (r < 34) randWeapon() else if (r < 50) 4 else if (r < 72) 5 else if (r < 84) 6
        else if (r < 88) 7 else if (r < 94) 8 else if (r < 98) 9 else 10
    }

    private fun addLoot(kind: Int, x: Float, z: Float) {
        val p = Vector3(x.coerceIn(-world.maxX, world.maxX), 1f, z.coerceIn(-world.maxZ, world.maxZ))
        collide(p, 1.2f, world.h(p.x, p.z))
        if (!world.walkable(p.x, p.z) || world.h(p.x, p.z) < 0.4f) return
        val mag = if (isGun(kind)) weapons[kind].mag else 0
        loot.add(Loot(kind, p.x, p.z, mag, world.h(p.x, p.z)))
    }

    // loot lying on the floor of a building (any storey)
    private fun addLootAt(kind: Int, x: Float, y: Float, z: Float) {
        val mag = if (isGun(kind)) weapons[kind].mag else 0
        loot.add(Loot(kind, x, z, mag, y))
    }

    private fun spawnLoot(n: Int) {
        for (i in 0 until n) {
            if (world.lootSpots.isNotEmpty() && rnd.nextInt(100) < 72) {
                // on the floor inside a building (rooms and upper storeys)
                val sp = world.lootSpots[rnd.nextInt(world.lootSpots.size)]
                addLootAt(randKind(), sp[0], sp[1], sp[2])
                continue
            }
            val b = blocks[rnd.nextInt(blocks.size)]
            val side = if (rnd.nextBoolean()) 1f else -1f
            val x = b.x + side * (b.hw + 1.5f + rnd.nextFloat() * 2f)
            val z = b.z + (rnd.nextFloat() - 0.5f) * b.hd * 2f
            addLoot(randKind(), x, z)
        }
        // heavy weapons and backpacks of every level lying around the map
        for (i in 0 until 40) {
            addLoot(if (i < 6) 7 else if (i < 22) 8 else if (i < 34) 9 else 10,
                (rnd.nextFloat() - 0.5f) * (world.MW - 100f), (rnd.nextFloat() - 0.5f) * (world.MH - 100f))
        }
        // extra guns lying around in the open
        for (i in 0 until 14) {
            addLoot(if (i < 8) randWeapon() else randKind(),
                (rnd.nextFloat() - 0.5f) * (world.MW - 100f), (rnd.nextFloat() - 0.5f) * (world.MH - 100f))
        }
    }

    private fun onBotDeath(b: Bot) {
        addLoot(b.weapon, b.pos.x, b.pos.z)
        addLoot(5, b.pos.x + 1.5f, b.pos.z)
        if (rnd.nextInt(100) < 14) addLoot(8 + rnd.nextInt(2), b.pos.x - 1.5f, b.pos.z)
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
                if (medkits >= medCap()) return false
                medkits++
                msg = "Medkit +1 (tap the + button)"
            }
            5 -> {
                if (reserve >= ammoCap()) return false
                reserve = minOf(ammoCap(), reserve + 40)
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

    private fun pickupPack(l: Loot) {
        val lv = l.kind - 7
        if (lv > bpLevel) {
            if (bpLevel > 0) loot.add(Loot(7 + bpLevel, l.x + 1.3f, l.z, 0, l.y))
            bpLevel = lv
            loot.remove(l)
            msg = "Level $lv backpack: ammo ${ammoCap()}, medkits ${medCap()}"
        } else msg = "You already carry a level $bpLevel backpack"
        msgT = 2f
    }

    private fun pickupWeapon(l: Loot) {
        if (l.kind in 8..10) { pickupPack(l); return }
        if (l.kind == 7) {
            // the minigun has its own box; a second one swaps and the old one falls to the ground
            val s = slots[2]
            if (s.type < 0) {
                s.type = 7
                s.mag = l.mag
                loot.remove(l)
                msg = "Equipped MINIGUN"
            } else if (s.type == 7) {
                val om = s.mag
                s.mag = l.mag
                l.mag = om
                msg = "Swapped MINIGUN - the old one fell on the ground"
            } else {
                // a normal gun sits in box 3: move it to a free box or drop it
                val fr = if (slots[0].type < 0) 0 else if (slots[1].type < 0) 1 else -1
                if (fr >= 0) {
                    slots[fr].type = s.type
                    slots[fr].mag = s.mag
                    loot.remove(l)
                    msg = "Equipped MINIGUN"
                } else {
                    val ot = s.type
                    val om = s.mag
                    l.kind = ot
                    l.mag = om
                    msg = "Equipped MINIGUN - your " + weapons[ot].name + " fell on the ground"
                }
                s.type = 7
                s.mag = weapons[7].mag
            }
            cur = 2
            reloading = false
            msgT = 1.8f
            return
        }
        val empty = if (slots[0].type < 0) 0 else if (slots[1].type < 0) 1 else if (slots[2].type < 0) 2 else -1
        if (empty >= 0) {
            slots[empty].type = l.kind
            slots[empty].mag = l.mag
            cur = empty
            loot.remove(l)
        } else {
            // both gun boxes full: swap the gun in your hands (or box 1) with the one on the ground
            val i = if (slots[cur].type == 7) 0 else cur
            val s = slots[i]
            val oldType = s.type
            val oldMag = s.mag
            s.type = l.kind
            s.mag = l.mag
            l.kind = oldType
            l.mag = oldMag
            cur = i
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
        val ic = if (type >= 11) (when (weapons[type].cat) { 0, 4 -> 0; 1 -> 1; 2 -> 2; else -> 3 }) else type
        when (ic) {
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
            7 -> {
                shapes.rect(cx - 40f, cy - 8f, 70f, 22f)
                for (k in 0..2) shapes.rect(cx + 30f, cy - 6f + k * 7f, 60f, 4f)
                shapes.rect(cx - 8f, cy - 36f, 14f, 30f)
                shapes.rect(cx - 30f, cy + 14f, 40f, 8f)
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
        for (d in 1..2) {
            val o = (cur + d) % 3
            if (slots[o].type >= 0) {
                cur = o
                reloading = false
                msg = weapons[slots[cur].type].name
                msgT = 1.2f
                break
            }
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

    // ------------------------------------------------------------------
    // vehicles: Porsche (type 0) and retro bike (type 1)
    // ------------------------------------------------------------------
    private fun vehHalf(vh: Vehicle): Float = when (vh.type) { 0 -> 1.9f; 2 -> 1.0f; else -> 0.8f }

    private fun placeVehicle(vh: Vehicle, snap: Boolean, dt: Float) {
        val fwd = Vector3(0f, 0f, -1f).rotate(Vector3.Y, vh.yaw)
        val l = vehHalf(vh)
        val hf = world.h(vh.x + fwd.x * l, vh.z + fwd.z * l)
        val hb = world.h(vh.x - fwd.x * l, vh.z - fwd.z * l)
        val ty = (hf + hb) / 2f
        val tp = atan2(hf - hb, 2f * l) * 57.29578f
        if (snap) {
            vh.y = ty
            vh.pitch = tp
        } else {
            val k = minOf(1f, dt * 10f)
            vh.y += (ty - vh.y) * k
            vh.pitch += (tp - vh.pitch) * k
        }
    }

    private fun spawnVehicles() {
        vehicles.clear()
        driving = null
        nearVeh = null
        val ts = world.towns
        if (ts.isEmpty()) return
        for (i in 0 until 44) {
            val t = ts[rnd.nextInt(ts.size)]
            var tries = 0
            while (tries < 40) {
                tries++
                val a = rnd.nextFloat() * 6.2831855f
                val r = 10f + rnd.nextFloat() * (t.r * 0.9f + 20f)
                val x = t.x + cos(a) * r
                val z = t.z + sin(a) * r
                if (!world.walkable(x, z) || world.h(x, z) < 1.2f) continue
                val p = Vector3(x, world.h(x, z) + 1f, z)
                collide(p, 2.4f, world.h(x, z))
                if (abs(p.x - x) + abs(p.z - z) > 0.05f) continue
                if (abs(world.h(x + 3f, z) - world.h(x - 3f, z)) > 1.0f || abs(world.h(x, z + 3f) - world.h(x, z - 3f)) > 1.0f) continue
                val vh = Vehicle(when (i % 4) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 3 }, x, z, rnd.nextFloat() * 360f)
                placeVehicle(vh, true, 0f)
                vehicles.add(vh)
                break
            }
        }
    }

    private fun enterVehicle(vh: Vehicle) {
        driving = vh
        tppBeforeDrive = tpp
        tpp = true
        stance = 0
        sprint = false
        jumpV = 0f
        vh.v = 0f
        eyeH = when (vh.type) { 0 -> 1.2f; 2 -> 1.6f; else -> 1.55f }
        yaw = vh.yaw
        msg = when (vh.type) { 0 -> "Driving the Porsche - left stick: gas and steer"; 2 -> "Sports bike - left stick: throttle and steer"; 3 -> "Mountain bike - left stick: pedal and steer"; else -> "Riding the bike - left stick: pedal and steer" }
        msgT = 3f
    }

    private fun exitVehicle() {
        val vh = driving ?: return
        driving = null
        vh.v = 0f
        val right = Vector3(0f, 0f, -1f).rotate(Vector3.Y, vh.yaw).crs(Vector3.Y).nor()
        val d = if (vh.type == 0) 2.4f else 1.3f
        var px = vh.x + right.x * d
        var pz = vh.z + right.z * d
        if (!world.walkable(px, pz)) {
            px = vh.x - right.x * d
            pz = vh.z - right.z * d
        }
        val p = Vector3(px, world.h(px, pz) + 1.7f, pz)
        collide(p, 0.6f, world.h(px, pz))
        cam.position.set(p.x, world.groundY(p.x, p.z, p.y - 1.7f) + 1.7f, p.z)
        eyeH = 1.7f
        tpp = tppBeforeDrive
    }

    private fun updateDriving(vh: Vehicle, dt: Float, mx: Float, my: Float) {
        val car = vh.type == 0
        val sport = vh.type == 2
        val maxF = if (car) 26f else if (sport) 32f else 9f
        val maxR = if (car) -7f else -2.5f
        val acc = if (car) 11f else if (sport) 14f else 5f
        if (my > 0.05f) vh.v += acc * my * dt * (if (vh.v < 0f) 2.5f else 1f)
        else if (my < -0.05f) vh.v += acc * my * dt * (if (vh.v > 0f) 1.8f else 0.8f)
        else {
            val c = (if (car) 4f else 3f) * dt
            vh.v = if (abs(vh.v) <= c) 0f else vh.v - (if (vh.v > 0f) c else -c)
        }
        vh.v = vh.v.coerceIn(maxR, maxF)
        val sp = abs(vh.v)
        val steer = (if (car) 80f else if (sport) 90f else 105f) * minOf(1f, sp / 4f) * (if (vh.v >= 0f) 1f else -1f)
        vh.yaw -= mx * steer * dt
        val fwd = Vector3(0f, 0f, -1f).rotate(Vector3.Y, vh.yaw)
        val nx = vh.x + fwd.x * vh.v * dt
        val nz = vh.z + fwd.z * vh.v * dt
        if (stepOk(vh.x, vh.z, nx, nz)) {
            vh.x = nx
            vh.z = nz
        } else vh.v = 0f
        vh.x = vh.x.coerceIn(-world.maxX, world.maxX)
        vh.z = vh.z.coerceIn(-world.maxZ, world.maxZ)
        val p = Vector3(vh.x, vh.y + 1f, vh.z)
        collide(p, if (car) 1.5f else 0.7f, vh.y)
        val moved = abs(p.x - vh.x) + abs(p.z - vh.z)
        if (moved > 0.02f) {
            vh.v *= 0.45f
            vh.x = p.x
            vh.z = p.z
        }
        vehicleHits(vh)
        vh.dist += vh.v * dt
        vh.steer += (-mx * 28f * (if (vh.v >= 0f) 1f else -1f) - vh.steer) * minOf(1f, dt * 8f)
        placeVehicle(vh, false, dt)
        cam.position.set(vh.x, vh.y + eyeH, vh.z)
        // camera swings behind the vehicle unless the player is looking around
        if (lookT <= 0f) {
            val dd = (((vh.yaw - yaw) % 360f) + 540f) % 360f - 180f
            yaw += dd * minOf(1f, dt * 2.2f)
        }
        pSpeed = sp
        pMoving = sp > 0.5f
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
        hp = 200f
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
        stance = 0
        eyeH = 1.7f
        jumpV = 0f
        slots[0].type = 0
        slots[0].mag = weapons[0].mag
        slots[1].type = -1
        slots[1].mag = 0
        slots[2].type = -1
        slots[2].mag = 0
        bpLevel = 0
        cur = 0
        cam.position.set(0f, 1.7f, 0f)
        collide(cam.position, 0.6f)
        run {
            val a = (if (rnd.nextBoolean()) 0f else 3.1415927f) + (rnd.nextFloat() - 0.5f) * 1.3f
            planeDX = cos(a)
            planeDZ = sin(a)
            val off = (rnd.nextFloat() - 0.5f) * 700f
            planeX = -planeDX * (world.MW / 2f + 200f) - planeDZ * off
            planeZ = -planeDZ * (world.MW / 2f + 200f) + planeDX * off
            planeYaw = atan2(-planeDX, -planeDZ) * 57.29578f
            yaw = planeYaw
            pitch = -15f
            planeActive = true
            dropState = 0
            dropVy = 0f
            cam.position.set(planeX, planeY - 1.7f, planeZ)
            msg = "Tap DROP to jump out of the plane"
            msgT = 5f
        }
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
        spawnLoot(760)
        spawnVehicles()
    }

    // push a position out of any building (circle vs box, top-down)
    private fun indexBlocks() {
        bGrid.clear()
        for (b in blocks) {
            val i0 = ((b.x - b.hw - world.X0) / 24f).toInt().coerceAtLeast(0)
            val i1 = ((b.x + b.hw - world.X0) / 24f).toInt().coerceAtLeast(0)
            val j0 = ((b.z - b.hd - world.Z0) / 24f).toInt().coerceAtLeast(0)
            val j1 = ((b.z + b.hd - world.Z0) / 24f).toInt().coerceAtLeast(0)
            for (j in j0..j1) for (i in i0..i1) bGrid.getOrPut(j * 512 + i) { ArrayList() }.add(b)
        }
    }

    /** blocks whose cells touch the given rectangle (each block once) */
    private fun nearBlocks(xa: Float, za: Float, xb: Float, zb: Float): ArrayList<Block> {
        tmpB.clear()
        markStamp++
        val i0 = ((minOf(xa, xb) - world.X0) / 24f).toInt().coerceAtLeast(0)
        val i1 = ((maxOf(xa, xb) - world.X0) / 24f).toInt().coerceAtLeast(0)
        val j0 = ((minOf(za, zb) - world.Z0) / 24f).toInt().coerceAtLeast(0)
        val j1 = ((maxOf(za, zb) - world.Z0) / 24f).toInt().coerceAtLeast(0)
        for (j in j0..j1) for (i in i0..i1) {
            val l = bGrid[j * 512 + i] ?: continue
            for (b in l) {
                if (b.mark != markStamp) {
                    b.mark = markStamp
                    tmpB.add(b)
                }
            }
        }
        return tmpB
    }

    // feet = height of the feet, so furniture and walls of other storeys are ignored
    private var ghostWalls = false
    private val vcTmp = FloatArray(9)

    // solid vehicle bodies as chains of circles: returns the number of circles (x,z,r triples in vcTmp)
    private fun vehCircles(vh: Vehicle): Int {
        val fwd = Vector3(0f, 0f, -1f).rotate(Vector3.Y, vh.yaw)
        if (vh.type == 0) {
            for (k in 0..2) {
                vcTmp[k * 3] = vh.x + fwd.x * (k - 1) * 1.2f
                vcTmp[k * 3 + 1] = vh.z + fwd.z * (k - 1) * 1.2f
                vcTmp[k * 3 + 2] = 1.0f
            }
            return 3
        }
        for (k in 0..1) {
            vcTmp[k * 3] = vh.x + fwd.x * (k - 0.5f) * 1.0f
            vcTmp[k * 3 + 1] = vh.z + fwd.z * (k - 0.5f) * 1.0f
            vcTmp[k * 3 + 2] = 0.5f
        }
        return 2
    }

    private fun collideVehicles(p: Vector3, rad: Float, feet: Float) {
        for (vh in vehicles) {
            if (vh === driving) continue
            if (feet > vh.y + (if (vh.type == 0) 1.5f else 1.1f)) continue
            val ddx = vh.x - p.x
            val ddz = vh.z - p.z
            if (ddx * ddx + ddz * ddz > 25f) continue
            val n = vehCircles(vh)
            for (k in 0 until n) {
                val cx = vcTmp[k * 3]
                val cz = vcTmp[k * 3 + 1]
                val rr = vcTmp[k * 3 + 2] + rad
                val dx = p.x - cx
                val dz = p.z - cz
                val d2 = dx * dx + dz * dz
                if (d2 < rr * rr) {
                    if (d2 > 0.00001f) {
                        val d = sqrt(d2)
                        p.x += dx / d * (rr - d)
                        p.z += dz / d * (rr - d)
                    } else p.x += rr
                }
            }
        }
    }

    // nearest vehicle hit by the ray (distance in vdHit)
    private var vdHit = 0f
    private fun vehRay(ray: Ray, maxD: Float): Vehicle? {
        var best: Vehicle? = null
        var bd = maxD
        val c = Vector3()
        for (vh in vehicles) {
            if (vh === driving) continue
            val ddx = vh.x - ray.origin.x
            val ddz = vh.z - ray.origin.z
            if (ddx * ddx + ddz * ddz > (maxD + 4f) * (maxD + 4f)) continue
            val n = vehCircles(vh)
            for (k in 0 until n) {
                c.set(vcTmp[k * 3], vh.y + 0.75f, vcTmp[k * 3 + 1])
                if (Intersector.intersectRaySphere(ray, c, vcTmp[k * 3 + 2] + 0.15f, null)) {
                    val d = c.dst(ray.origin)
                    if (d < bd) { bd = d; best = vh }
                }
            }
        }
        vdHit = bd
        return best
    }

    private fun knockBot(b: Bot, dmg: Float, dx: Float, dz: Float) {
        b.hp -= dmg
        b.vehCd = 0.5f
        if (b.hp <= 0f) {
            if (!pendingKill.contains(b)) {
                pendingKill.add(b)
                kills++
            }
            return
        }
        if (b.downT <= 0f) {
            val ba = b.actor
            if (ba != null) play(ba, "Death", 1f, 1, 0.1f)
        }
        b.downT = 3f
        b.shooting = false
        b.pos.x += dx
        b.pos.z += dz
    }

    private fun explodeVehicle(vh: Vehicle) {
        vh.wrecked = true
        vh.hp = 0f
        vh.v = 0f
        msg = "Vehicle destroyed!"
        msgT = 1.5f
        val R2 = 7f
        for (b in bots) {
            if (b.hp <= 0f) continue
            val dx = b.pos.x - vh.x
            val dz = b.pos.z - vh.z
            val d = sqrt(dx * dx + dz * dz)
            if (d < R2) {
                val f = 1f - d / R2
                val l = d + 0.01f
                knockBot(b, 150f * f, dx / l * 2f * f, dz / l * 2f * f)
            }
        }
        val pdx = cam.position.x - vh.x
        val pdz = cam.position.z - vh.z
        val pd = sqrt(pdx * pdx + pdz * pdz)
        if (pd < R2) { hurt(120f * (1f - pd / R2)); hurtFlash = 0.3f }
    }

    private fun damageVehicle(vh: Vehicle, d: Float) {
        if (vh.wrecked) return
        vh.hp -= d
        hitMark = 0.15f
        msg = "VEHICLE HP " + vh.hp.coerceAtLeast(0f).toInt()
        msgT = 0.8f
        if (vh.hp <= 0f) explodeVehicle(vh)
    }

    // run over enemies
    private fun vehicleHits(vh: Vehicle) {
        val sp = abs(vh.v)
        if (sp < 3f || vh.wrecked) return
        val fwd = Vector3(0f, 0f, -1f).rotate(Vector3.Y, vh.yaw)
        val dir = if (vh.v >= 0f) 1f else -1f
        val n = vehCircles(vh)
        for (b in bots) {
            if (b.hp <= 0f || b.vehCd > 0f) continue
            if (b.pos.y > vh.y + 1.4f) continue
            for (k in 0 until n) {
                val dx = b.pos.x - vcTmp[k * 3]
                val dz = b.pos.z - vcTmp[k * 3 + 1]
                val rr = vcTmp[k * 3 + 2] + 0.9f
                if (dx * dx + dz * dz < rr * rr) {
                    val per = when (vh.type) { 0 -> 9f; 2 -> 6f; else -> 3.5f }
                    knockBot(b, sp * per, fwd.x * dir * 1.6f, fwd.z * dir * 1.6f)
                    hitMark = 0.2f
                    break
                }
            }
        }
    }

    private fun collide(p: Vector3, rad: Float, feet: Float = p.y - 1.7f) {
        world.collideCircles(p, rad)
        collideVehicles(p, rad, feet)
        if (!ghostWalls) for (b in nearBlocks(p.x - rad, p.z - rad, p.x + rad, p.z + rad)) {
            if (feet + 1.5f < b.y0 + 0.1f || feet > b.y0 + b.h - 0.35f) continue
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
        val ex = ray.origin.x + ray.direction.x * maxD
        val ez = ray.origin.z + ray.direction.z * maxD
        for (b in nearBlocks(ray.origin.x, ray.origin.z, ex, ez)) {
            if (Intersector.intersectRayBounds(ray, b.box, tmp)) {
                val d = tmp.dst(ray.origin)
                if (d < best) best = d
            }
        }
        val tr = world.terrainRay(ray.origin.x, ray.origin.y, ray.origin.z, ray.direction.x, ray.direction.y, ray.direction.z, best)
        if (tr < best) best = tr
        return best
    }

    private fun stepOk(fx: Float, fz: Float, tx: Float, tz: Float): Boolean {
        if (!world.walkable(tx, tz)) return false
        val dx = tx - fx
        val dz = tz - fz
        val dist = sqrt(dx * dx + dz * dz)
        if (dist < 0.0001f) return true
        return (world.h(tx, tz) - world.h(fx, fz)) / dist < 0.9f
    }

    // walk over the terrain: blocked by deep water and cliffs, slides along them
    private fun moveOnLand(p: Vector3, dx: Float, dz: Float) {
        if (stepOk(p.x, p.z, p.x + dx, p.z + dz)) {
            p.x += dx
            p.z += dz
        } else if (stepOk(p.x, p.z, p.x + dx, p.z)) {
            p.x += dx
        } else if (stepOk(p.x, p.z, p.x, p.z + dz)) {
            p.z += dz
        }
    }

    // find dry land near a point (used when the parachute lands in the sea)
    private fun snapToLand(p: Vector3) {
        if (world.walkable(p.x, p.z)) return
        var r = 6f
        while (r < 400f) {
            for (k in 0 until 24) {
                val a = k * 0.2618f
                val x = p.x + cos(a) * r
                val z = p.z + sin(a) * r
                if (world.walkable(x, z) && world.h(x, z) > 0.6f) {
                    p.x = x
                    p.z = z
                    return
                }
            }
            r += 6f
        }
    }

    private fun hasLos(a: Vector3, b: Vector3): Boolean {
        if (world.terrainBlocks(a.x, a.y, a.z, b.x, b.y, b.z)) return false
        for (bl in nearBlocks(a.x, a.z, b.x, b.z)) {
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
        var tries = 0
        while (tries < 40 && (world.h(toCx, toCz) < 1f || abs(toCx) > world.MW / 2f - 80f || abs(toCz) > world.MH / 2f - 80f)) {
            val a2 = rnd.nextFloat() * 6.2831853f
            val d2 = sqrt(rnd.nextFloat()) * maxOff
            toCx = fromCx + cos(a2) * d2
            toCz = fromCz + sin(a2) * d2
            tries++
        }
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
                p.set((rnd.nextFloat() - 0.5f) * (world.MW - 80f), 0f, (rnd.nextFloat() - 0.5f) * (world.MH - 80f))
            } while (!world.walkable(p.x, p.z) || world.h(p.x, p.z) < 1.2f)
            collide(p, 0.8f, world.h(p.x, p.z))
            p.y = world.h(p.x, p.z)
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
        val fire2X = w * 0.085f
        val fire2Y = h * 0.52f
        val fire2R = h * 0.075f
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
        val mapX = 170f          // round minimap (screen coordinates, y down)
        val mapY = 170f
        val mapR = 150f
        val zbX = 352f           // minimap zoom buttons
        val zbY1 = 92f
        val zbY2 = 152f
        val zbR = 24f
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
            for (i in 0..2) {
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
        val crouchX = w * 0.835f
        val crouchY = h * 0.935f
        val crouchR = h * 0.058f
        val proneX = w * 0.665f
        val proneY = h * 0.915f
        val proneR = h * 0.058f
        val driveX = w * 0.60f
        val driveY = h * 0.66f
        val driveR = h * 0.075f

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
            if (mapOpen) handleMapInput(w, h)
            for (i in 0..2) {
                if (!Gdx.input.isTouched(i) || mapOpen) continue
                val x = Gdx.input.getX(i).toFloat()
                val y = Gdx.input.getY(i).toFloat()
                val dfx = x - fireX
                val dfy = y - fireY
                if (dfx * dfx + dfy * dfy < fireR * fireR * 2.2f || inCircle(x, y, fire2X, fire2Y, fire2R * 1.25f)) {
                    firing = true
                } else if (inCircle(x, y, reloadX, reloadY, reloadR * 1.15f) ||
                    inCircle(x, y, medX, medY, medR * 1.15f) ||
                    inCircle(x, y, runX, runY, runR * 1.15f) ||
                    inCircle(x, y, jumpX, jumpY, jumpR * 1.15f) ||
                    inCircle(x, y, crouchX, crouchY, crouchR * 1.15f) || inCircle(x, y, proneX, proneY, proneR * 1.15f) ||
                    inCircle(x, y, camX, camY, camR * 1.15f) ||
                    inCircle(x, y, mapX, mapY, mapR) ||
                    inCircle(x, y, zbX, zbY1, zbR * 1.2f) || inCircle(x, y, zbX, zbY2, zbR * 1.2f) ||
                    slotHit(x, y) >= 0 ||
                    ((nearVeh != null || driving != null) && inCircle(x, y, driveX, driveY, driveR * 1.15f)) ||
                    (nearLoot != null && inCircle(x, y, pickX, pickY, pickR * 1.15f))) {
                    // finger is on a button, not on movement or look
                } else if (x < w / 2f) {
                    mx = ((x - joyX) / joyR).coerceIn(-1f, 1f)
                    my = ((joyY - y) / joyR).coerceIn(-1f, 1f)
                    knobX = joyX + mx * joyR
                    knobY = joyY - my * joyR
                } else {
                    yaw -= Gdx.input.getDeltaX(i) * 0.2f
                    lookT = 1.6f
                    pitch = (pitch - Gdx.input.getDeltaY(i) * 0.2f).coerceIn(if (tpp) -55f else -80f, if (tpp) 35f else 80f)
                }
            }

            if (driving != null) firing = false
            lookT -= dt
            val fwd = Vector3(0f, 0f, -1f).rotate(Vector3.Y, yaw)
            val right = Vector3(fwd).crs(Vector3.Y)
            val speed = (if (sprint && stance == 0) 8.5f else 5.5f) * (if (stance == 1) 0.55f else if (stance == 2) 0.28f else 1f)
            pSpeed = speed * minOf(1f, sqrt(mx * mx + my * my))
            pMoving = (mx * mx + my * my) > 0.04f
            if (pMoving) {
                val vel = Vector3(fwd).scl(my).add(Vector3(right).scl(mx))
                pMoveYaw = atan2(vel.x, vel.z) * 57.29578f
            }
            if (dropState < 3) {
                updateDrop(dt, mx, my, fwd, right)
            } else {
                val dvh = driving
                if (dvh != null) updateDriving(dvh, dt, mx, my) else {
                val stepV = Vector3(fwd).scl(my * speed * dt).add(Vector3(right).scl(mx * speed * dt))
                moveOnLand(cam.position, stepV.x, stepV.z)
                val eyeT = if (stance == 1) 1.15f else if (stance == 2) 0.55f else 1.7f
                eyeH += (eyeT - eyeH) * minOf(1f, dt * 10f)
                val ge = world.groundY(cam.position.x, cam.position.z, cam.position.y - eyeH) + eyeH
                if (jumpV != 0f || cam.position.y > ge + 0.6f) {
                    jumpV -= 18f * dt
                    cam.position.y += jumpV * dt
                    if (cam.position.y <= ge) {
                        cam.position.y = ge
                        jumpV = 0f
                    }
                } else {
                    cam.position.y += (ge - cam.position.y) * minOf(1f, dt * 18f)
                }
                cam.position.x = cam.position.x.coerceIn(-world.maxX, world.maxX)
                cam.position.z = cam.position.z.coerceIn(-world.maxZ, world.maxZ)
                val gfeet = cam.position.y - eyeH
                ghostWalls = (jumpV != 0f || gfeet > ge - eyeH + 0.6f) && gfeet - world.h(cam.position.x, cam.position.z) > 1.8f
                collide(cam.position, 0.6f, gfeet)
                ghostWalls = false
                }
            }
            if (planeActive) {
                planeX += planeDX * 75f * dt
                planeZ += planeDZ * 75f * dt
                if (dropState > 0 && (abs(planeX) > world.MW || abs(planeZ) > world.MW)) planeActive = false
            }

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
                if (l.kind in 4..6) {
                    if (dropState == 3 && ld < 2.2f && abs(l.y - (cam.position.y - eyeH)) < 2.2f && applyItem(l)) loot.removeAt(li)
                } else if (dropState == 3 && ld < nearD && abs(l.y - (cam.position.y - eyeH)) < 2.2f) {
                    near = l
                    nearD = ld
                }
                li--
            }
            nearLoot = near
            var nv: Vehicle? = null
            if (driving == null && dropState == 3 && stance == 0 && hp > 0f) {
                var nd = 4.2f
                for (vh in vehicles) {
                    if (vh.wrecked) continue
                    val vdx = vh.x - cam.position.x
                    val vdz = vh.z - cam.position.z
                    val vd = sqrt(vdx * vdx + vdz * vdz)
                    if (vd < nd) {
                        nd = vd
                        nv = vh
                    }
                }
            }
            nearVeh = nv

            // button taps
            for (t in taps) {
                val tx = t[0]
                val ty = t[1]
                if (mapOpen) {
                    // handled by the map screen
                } else if (inCircle(tx, ty, zbX, zbY1, zbR * 1.2f)) {
                    miniLvl = maxOf(0, miniLvl - 1)
                } else if (inCircle(tx, ty, zbX, zbY2, zbR * 1.2f)) {
                    miniLvl = minOf(miniRanges.size - 1, miniLvl + 1)
                } else if (inCircle(tx, ty, mapX, mapY, mapR)) {
                    mapOpen = true
                    mapZoom = 1f
                    mapCx = 0f
                    mapCz = 0f
                    pinchPrev = 0f
                } else if ((nearVeh != null || driving != null) && inCircle(tx, ty, driveX, driveY, driveR * 1.15f)) {
                    val nvv = nearVeh
                    if (driving != null) exitVehicle() else if (nvv != null) enterVehicle(nvv)
                } else if (inCircle(tx, ty, reloadX, reloadY, reloadR * 1.15f)) {
                    tryReload()
                } else if (inCircle(tx, ty, medX, medY, medR * 1.15f)) {
                    if (medkits > 0 && hp < 200f) {
                        medkits--
                        hp = minOf(200f, hp + 100f)
                        msg = "Medkit used +50 HP"
                        msgT = 1.5f
                    }
                } else if (inCircle(tx, ty, runX, runY, runR * 1.15f)) {
                    sprint = !sprint
                } else if (inCircle(tx, ty, jumpX, jumpY, jumpR * 1.15f)) {
                    if (dropState == 0) startFreefall()
                    else if (dropState == 1) openChute()
                    else if (driving != null) { }
                    else if (dropState == 3 && stance != 0) stance = 0
                    else if (dropState == 3 && cam.position.y - eyeH <= world.groundY(cam.position.x, cam.position.z, cam.position.y - eyeH) + 0.25f && jumpV == 0f) jumpV = 6.5f
                } else if (inCircle(tx, ty, crouchX, crouchY, crouchR * 1.15f)) {
                    if (dropState == 3 && hp > 0f && driving == null) stance = if (stance == 1) 0 else 1
                } else if (inCircle(tx, ty, proneX, proneY, proneR * 1.15f)) {
                    if (dropState == 3 && hp > 0f && driving == null) stance = if (stance == 2) 0 else 2
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
        val wantFire = firing && (def.auto || !firingLast) && dropState == 3
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
                var maxD = wallDist(ray, def.range)
                val vHitV = vehRay(ray, maxD)
                if (vHitV != null) maxD = vdHit
                var best: Bot? = null
                var bestDist = maxD
                var bestHead = false
                for (b in bots) {
                    val hc = Vector3(b.pos.x, b.pos.y + 1.68f, b.pos.z)
                    val tc = Vector3(b.pos.x, b.pos.y + 1.25f, b.pos.z)
                    val lc = Vector3(b.pos.x, b.pos.y + 0.65f, b.pos.z)
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
                if (best == null && vHitV != null) damageVehicle(vHitV, def.dmg)
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
                if (b.vehCd > 0f) b.vehCd -= dt
                if (b.downT > 0f) {
                    b.downT -= dt
                    b.shooting = false
                    b.moving = false
                    b.vel = 0f
                    collide(b.pos, 0.8f, b.pos.y)
                    b.pos.y = world.groundY(b.pos.x, b.pos.z, b.pos.y)
                    continue
                }
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
                val los = toP < 30f && dropState == 3 && hasLos(Vector3(b.pos.x, b.pos.y + 1.5f, b.pos.z), eye)
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
                            if (od < rd && hasLos(Vector3(b.pos.x, b.pos.y + 1.5f, b.pos.z), Vector3(o.pos.x, o.pos.y + 1.5f, o.pos.z))) {
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
                            var tt = 0
                            do {
                                val a = rnd.nextFloat() * 6.2831853f
                                val d = sqrt(rnd.nextFloat()) * zoneR * 0.8f
                                b.target.set((zoneCx + cos(a) * d).coerceIn(-world.maxX, world.maxX), 0f,
                                             (zoneCz + sin(a) * d).coerceIn(-world.maxZ, world.maxZ))
                                tt++
                            } while (tt < 12 && (!world.walkable(b.target.x, b.target.z) || world.h(b.target.x, b.target.z) < 1f))
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
                        val bx0 = b.pos.x
                        val bz0 = b.pos.z
                        moveOnLand(b.pos, dir.x, dir.z)
                        val mdx = b.pos.x - bx0
                        val mdz = b.pos.z - bz0
                        if (mdx * mdx + mdz * mdz < 0.25f * dir.x * dir.x + 0.25f * dir.z * dir.z) b.retarget = 0f
                        moved = true
                    }
                }
                if (facePlayer && (los || !moved)) faceYaw = atan2(toPx, toPz) * 57.29578f
                b.yaw = turnTo(b.yaw, faceYaw, 480f * dt)
                b.pos.x = b.pos.x.coerceIn(-world.maxX, world.maxX)
                b.pos.z = b.pos.z.coerceIn(-world.maxZ, world.maxZ)
                collide(b.pos, 0.8f, b.pos.y)
                b.pos.y = world.groundY(b.pos.x, b.pos.z, b.pos.y)

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
            if (!damaged) hp = minOf(200f, hp + 1.5f * dt)
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
        val showBody = tpp && humansOk && pa != null && dropState > 0 && driving == null
        if (dropState == 0) {
            // Free Fire style: camera floats behind and above the plane and can orbit around it
            cam.position.set(planeX, planeY + 1.5f, planeZ).add(Vector3(cam.direction).scl(-46f))
            cam.fieldOfView = 67f
            cam.update()
        } else if (tpp) {
            val want = tpWanted(eyeP)
            tpLen = if (want < tpLen) want else tpLen + (want - tpLen) * minOf(1f, dt * 5f)
            cam.position.set(tpCamPos(eyeP))
            val wantFov = if (sprint && pMoving) 74f else 67f
            cam.fieldOfView += (wantFov - cam.fieldOfView) * minOf(1f, dt * 4f)
            cam.update()
        } else if (cam.fieldOfView != 67f) {
            cam.fieldOfView = 67f
            cam.update()
        }
        batch.begin(cam)
        world.render(batch, cam, env)
        batch.render(buildings, env)
        if (planeActive) {
            val pm = Matrix4().translate(planeX, planeY, planeZ).rotate(Vector3.Y, planeYaw)
            val pi = planeInst
            if (pi != null) {
                pi.transform.set(pm)
                batch.render(pi, env)
            } else drawProp(planeProp, pm)
        }
        if (dropState == 2) {
            val pc = paraInst
            if (pc != null) {
                chuteT += dt
                val so = minOf(1f, chuteT / 0.9f)
                val sx = 0.2f + 0.8f * so
                val sy = 0.35f + 0.65f * so
                pc.transform.setToTranslation(eyeP.x, eyeP.y - 0.35f, eyeP.z).rotate(Vector3.Y, yaw).scale(sx, sy, sx)
                batch.render(pc, env)
            } else {
                canopy.transform.setToTranslation(eyeP.x, eyeP.y + 3.4f, eyeP.z)
                batch.render(canopy, env)
            }
        }
        for (vh in vehicles) {
            val vdx = vh.x - cam.position.x
            val vdz = vh.z - cam.position.z
            if (vdx * vdx + vdz * vdz > 260f * 260f) continue
            if (vh !== driving && !cam.frustum.sphereInFrustum(vh.x, vh.y + 1f, vh.z, 3.5f)) continue
            drawVehicle(vh, vehMatrix(vh, vTmp))
        }
        val dvh = driving
        if (dvh != null && pa != null && humansOk && tpp) {
            val bm = vehMatrix(dvh, Matrix4())
            val clip = when (dvh.type) { 0 -> "DriveCar"; 1 -> "RideRetro"; 2 -> "RideMoto"; else -> "RideMtb" }
            val pedal = if (dvh.type == 1 || dvh.type == 3) (abs(dvh.v) / 3.2f).coerceIn(0f, 3f) else 1f
            play(pa, clip, pedal)
            pa.ctrl.update(dt)
            pa.inst.transform.set(bm)
            batch.render(pa.inst, env)
            carry(pa, slots[cur].type, otherType(), false, false, bpLevel)
        }
        if (showBody && pa != null) {
            val moving = pMoving
            val shoot = flash > 0f || firing
            if (shoot) pArmedT = 2.5f else pArmedT -= dt
            val armedNow = pArmedT > 0f
            val tgt = if (armedNow || dropState in 1..2) yaw + 180f else if (moving) pMoveYaw else pYaw
            pYaw = turnTo(pYaw, tgt, 720f * dt)
            if (hp <= 0f) play(pa, "Death", 1f, 1, 0.1f)
            else if (dropState in 1..2 || (dropState == 3 && (jumpV != 0f || eyeP.y - eyeH > world.groundY(eyeP.x, eyeP.z, eyeP.y - eyeH) + 0.6f))) play(pa, "Jump", 1f)
            else if (dropState == 3 && stance == 2) play(pa, if (moving) "Crawl" else "Prone", if (moving) (pSpeed / 1.5f).coerceIn(0.6f, 1.5f) else 1f)
            else if (dropState == 3 && stance == 1) play(pa, if (moving) "CrouchWalk" else "Crouch", if (moving) (pSpeed / 2f).coerceIn(0.6f, 1.5f) else 1f)
            else locoAnim(pa, if (moving) pSpeed else 0f, armedNow)
            pa.ctrl.update(dt)
            drawActor(pa, eyeP.x, eyeP.y - (if (dropState == 3) eyeH else 1.7f), eyeP.z, pYaw, slots[cur].type, otherType(), armedNow && hp > 0f, flash > 0f, bpLevel)
        }
        for (b in bots) {
            val bdx = b.pos.x - cam.position.x
            val bdz = b.pos.z - cam.position.z
            if (bdx * bdx + bdz * bdz > 250f * 250f) continue
            if (!cam.frustum.sphereInFrustum(b.pos.x, b.pos.y + 1f, b.pos.z, 1.8f)) continue
            val ba = b.actor
            if (ba == null) {
                boxInst.transform.setToTranslation(b.pos.x, b.pos.y + 1f, b.pos.z)
                batch.render(boxInst, env)
                continue
            }
            val armed = b.armedT > 0f
            if (b.downT <= 0f) locoAnim(ba, b.vel, armed)
            ba.ctrl.update(dt)
            drawActor(ba, b.pos.x, b.pos.y, b.pos.z, b.yaw, b.weapon, b.back, armed,
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
                drawActor(ca, c.pos.x, c.pos.y, c.pos.z, c.yaw, -1, -1, false, false)
            }
        }
        for (l in loot) {
            val dx = l.x - cam.position.x
            val dz = l.z - cam.position.z
            if (dx * dx + dz * dz > 120f * 120f) continue
            val bi = beams[l.kind]
            val lgh = l.y
            bi.transform.setToTranslation(l.x, lgh + 4.5f, l.z)
            batch.render(bi, env)
            val lb = Matrix4()
            lb.translate(l.x, lgh + 0.95f + 0.12f * sin(gameTime * 2f + l.x), l.z)
            lb.rotate(Vector3.Y, gameTime * 80f)
            if (isGun(l.kind)) {
                lb.scale(1.5f, 1.5f, 1.5f)
                drawProp(weaponProps[l.kind], lb)
            } else if (l.kind in 8..10) {
                val pi = bpInst[l.kind - 8]
                if (pi != null) {
                    pi.transform.set(lb).scale(2.2f, 2.2f, 2.2f).translate(0f, 0f, 0.12f)
                    batch.render(pi, env)
                }
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
        shapes.setColor(0.1f, 0.1f, 0.12f, 0.55f)
        shapes.circle(fire2X, h - fire2Y, fire2R)
        shapes.setColor(0.9f, 0.15f, 0.15f, if (firing) 1f else 0.85f)
        shapes.circle(fire2X, h - fire2Y, fire2R * 0.6f)

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
        if (stance == 1) shapes.setColor(1f, 0.6f, 0.1f, 0.75f) else shapes.setColor(0.08f, 0.09f, 0.12f, 0.6f)
        shapes.circle(crouchX, h - crouchY, crouchR)
        if (stance == 2) shapes.setColor(1f, 0.6f, 0.1f, 0.75f) else shapes.setColor(0.08f, 0.09f, 0.12f, 0.6f)
        shapes.circle(proneX, h - proneY, proneR)
        shapes.setColor(0.08f, 0.09f, 0.12f, 0.6f)
        if (sprint) shapes.setColor(1f, 0.6f, 0.1f, 0.75f) else shapes.setColor(0.08f, 0.09f, 0.12f, 0.6f)
        shapes.circle(runX, h - runY, runR)
        shapes.setColor(0.08f, 0.09f, 0.12f, 0.6f)
        shapes.circle(medX, h - medY, medR)
        if (medkits > 0 && hp < 200f) shapes.setColor(1f, 0.35f, 0.4f, 1f) else shapes.setColor(0.7f, 0.7f, 0.7f, 0.7f)
        shapes.rect(medX - 24f, h - medY - 7f, 48f, 14f)
        shapes.rect(medX - 7f, h - medY - 24f, 14f, 48f)
        if (nearVeh != null || driving != null) {
            shapes.setColor(0.1f, 0.45f, 0.75f, 0.75f)
            shapes.circle(driveX, h - driveY, driveR)
        }
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
        shapes.rect(hbx, 40f, 520f * (hp.coerceAtLeast(0f) / 200f), 26f)
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
        // three small weapon boxes (2 guns + 1 heavy)
        for (i in 0..2) {
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

        // round minimap (top-left), centred on you, north is up
        val mcx = mapX
        val mcy = h - mapY
        val mrw = miniRanges[miniLvl]                       // metres shown from the centre to the rim
        val msc = mapR / mrw
        shapes.setColor(0f, 0f, 0f, 0.65f)
        shapes.circle(mcx, mcy, mapR + 5f, 64)
        shapes.end()
        sb.begin()
        run {
            val segs = 56
            val vtx = FloatArray(segs * 20)
            val col = Color(1f, 1f, 1f, 0.97f).toFloatBits()
            val pxw = cam.position.x
            val pzw = cam.position.z
            var o = 0
            for (k in 0 until segs) {
                val a0 = k * 6.2831855f / segs
                val a1 = (k + 1) * 6.2831855f / segs
                val ox = floatArrayOf(0f, cos(a0) * mapR, cos(a1) * mapR, cos(a1) * mapR)
                val oy = floatArrayOf(0f, sin(a0) * mapR, sin(a1) * mapR, sin(a1) * mapR)
                for (q in 0..3) {
                    vtx[o++] = mcx + ox[q]
                    vtx[o++] = mcy + oy[q]
                    vtx[o++] = col
                    vtx[o++] = (pxw + ox[q] / msc - world.X0) / world.MW
                    vtx[o++] = (pzw - oy[q] / msc - world.Z0) / world.MH
                }
            }
            sb.draw(world.colorTex, vtx, 0, vtx.size)
        }
        sb.end()

        Gdx.gl.glLineWidth(3f)
        shapes.begin(ShapeRenderer.ShapeType.Line)
        // zone circles, only the part inside the round map
        for (pass in 0..1) {
            if (pass == 1 && (shrinking || won)) break
            val zx = if (pass == 0) zoneCx else toCx
            val zz = if (pass == 0) zoneCz else toCz
            val zr = if (pass == 0) zoneR else toR
            if (pass == 0) shapes.setColor(0.3f, 0.5f, 1f, 1f) else shapes.setColor(1f, 1f, 1f, 1f)
            var lx = 0f
            var ly = 0f
            var lin = false
            for (k in 0..96) {
                val an = k * 6.2831855f / 96f
                val sx = mcx + (zx + cos(an) * zr - cam.position.x) * msc
                val sy = mcy - (zz + sin(an) * zr - cam.position.z) * msc
                val dxm = sx - mcx
                val dym = sy - mcy
                val inside = dxm * dxm + dym * dym < (mapR - 2f) * (mapR - 2f)
                if (k > 0 && inside && lin) shapes.line(lx, ly, sx, sy)
                lx = sx
                ly = sy
                lin = inside
            }
        }
        shapes.setColor(1f, 1f, 1f, 0.9f)
        shapes.circle(mcx, mcy, mapR, 64)
        shapes.end()

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        // towns
        shapes.setColor(1f, 1f, 1f, 1f)
        for (tn in world.towns) {
            val sx = mcx + (tn.x - cam.position.x) * msc
            val sy = mcy - (tn.z - cam.position.z) * msc
            val dxm = sx - mcx
            val dym = sy - mcy
            if (dxm * dxm + dym * dym < (mapR - 6f) * (mapR - 6f)) shapes.circle(sx, sy, 3.5f)
        }
        shapes.setColor(1f, 0.2f, 0.2f, 1f)
        for (b in bots) {
            val ddx = b.pos.x - cam.position.x
            val ddz = b.pos.z - cam.position.z
            if (ddx * ddx + ddz * ddz < 120f * 120f) shapes.circle(mcx + ddx * msc, mcy - ddz * msc, 3.5f)
        }
        shapes.setColor(1f, 0.9f, 0.2f, 1f)
        for (l in loot) {
            val ddx = l.x - cam.position.x
            val ddz = l.z - cam.position.z
            if (ddx * ddx + ddz * ddz < 120f * 120f) shapes.circle(mcx + ddx * msc, mcy - ddz * msc, 2.2f)
        }
        shapes.setColor(0.3f, 0.9f, 1f, 1f)
        for (vh in vehicles) {
            if (vh.wrecked) continue
            val ddx = vh.x - cam.position.x
            val ddz = vh.z - cam.position.z
            if (ddx * ddx + ddz * ddz < 120f * 120f) shapes.circle(mcx + ddx * msc, mcy - ddz * msc, 3f)
        }
        if (planeActive) {
            val ddx = (planeX - cam.position.x) * msc
            val ddz = (planeZ - cam.position.z) * msc
            if (ddx * ddx + ddz * ddz < (mapR - 8f) * (mapR - 8f)) {
                shapes.setColor(1f, 1f, 1f, 1f)
                shapes.circle(mcx + ddx, mcy - ddz, 7f)
            }
        }
        shapes.setColor(1f, 1f, 0f, 1f)
        shapes.circle(mcx, mcy, 6f)
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(1f, 1f, 0f, 1f)
        shapes.line(mcx, mcy, mcx + cam.direction.x * 28f, mcy - cam.direction.z * 28f)
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(0f, 0f, 0f, 0.6f)
        shapes.circle(zbX, h - zbY1, zbR, 24)
        shapes.circle(zbX, h - zbY2, zbR, 24)
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
        font.draw(sb, "FPS ${Gdx.graphics.framesPerSecond}", 410f, h - 20f)
        font.data.setScale(2.2f)
        font.setColor(1f, 1f, 1f, 1f)
        font.draw(sb, "+", zbX - 9f, h - zbY1 + 14f)
        font.draw(sb, "-", zbX - 6f, h - zbY2 + 12f)
        font.data.setScale(1.5f)
        font.draw(sb, world.placeName(cam.position.x, cam.position.z), 20f, h - 345f)
        font.data.setScale(0.9f)
        for (tn in world.towns) {
            val sx = mcx + (tn.x - cam.position.x) * msc
            val sy = mcy - (tn.z - cam.position.z) * msc
            val dxm = sx - mcx
            val dym = sy - mcy
            if (dxm * dxm + dym * dym < (mapR - 30f) * (mapR - 30f)) {
                layout.setText(font, tn.name)
                font.setColor(0f, 0f, 0f, 0.9f)
                font.draw(sb, tn.name, sx - layout.width / 2f + 1.2f, sy - 6f - 1.2f)
                font.setColor(1f, 1f, 1f, 1f)
                font.draw(sb, tn.name, sx - layout.width / 2f, sy - 6f)
            }
        }
        font.data.setScale(1.5f)
        font.draw(sb, "HP ${hp.coerceAtLeast(0f).toInt()}/200", w / 2f - 60f, 38f)

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
        for (i in 0..2) {
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
        font.data.setScale(1.3f)
        font.draw(sb, "BAG LV$bpLevel   AMMO $reserve/${ammoCap()}   MEDKIT $medkits/${medCap()}", w / 2f - 190f, 118f)

        if (dropState < 3) centered("ALTITUDE ${(if (dropState == 0) planeY else cam.position.y - world.h(cam.position.x, cam.position.z)).toInt()} m", h * 0.28f, w)
        if (dropState == 0) centered("Still in cabin: ${bots.size + 1}", h * 0.22f, w)
        if (msgT > 0f) centered(msg, h * 0.64f, w)
        if (nl != null) centered(lootName(nl.kind) + " - tap PICK", h * 0.34f, w)

        font.data.setScale(1.8f)
        label("RELOAD", reloadX, h - reloadY)
        label(if (tpp) "1ST" else "3RD", camX, h - camY)
        label("RUN", runX, h - runY)
        label("SIT", crouchX, h - crouchY)
        label("PRONE", proneX, h - proneY)
        label(if (dropState == 0) "EJECT" else if (dropState == 1) "CHUTE" else "JUMP", jumpX, h - jumpY)
        if (nl != null) label("PICK", pickX, h - pickY)
        if (nearVeh != null || driving != null) label(if (driving != null) "EXIT" else "DRIVE", driveX, h - driveY)
        font.data.setScale(3f)

        if (outside) centered("OUTSIDE ZONE! Run to the circle", h * 0.82f, w)
        if (hp <= 0f) centered("YOU DIED - Kills: $kills - tap to restart", h * 0.55f, w)
        else if (won && bots.isEmpty()) centered("WINNER WINNER! Kills: $kills - tap to restart", h * 0.55f, w)
        else if (won) centered("YOU SURVIVED! Kills: $kills - tap to restart", h * 0.55f, w)
        sb.end()

        if (mapOpen) drawFullMap(w, h)

        Gdx.gl.glDisable(GL20.GL_BLEND)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
    }

    // ---- full screen map: pinch / drag / +,- buttons --------------------------
    private fun fullMapRect(w: Float, h: Float): FloatArray {
        val mw = minOf(w * 0.86f, h * 0.84f * world.MW / world.MH)
        val mh = mw * world.MH / world.MW
        return floatArrayOf((w - mw) / 2f, (h - mh) / 2f - 14f, mw, mh)
    }

    private fun mapBtnX(w: Float) = w - 80f

    private fun handleMapInput(w: Float, h: Float) {
        val rc = fullMapRect(w, h)
        val bx = mapBtnX(w)
        val byClose = 70f
        val byIn = h * 0.42f
        val byOut = h * 0.42f + 100f
        val fs0 = rc[2] / world.MW
        for (t in taps) {
            val tx = t[0]
            val ty = t[1]
            if (inCircle(tx, ty, bx, byClose, 44f)) mapOpen = false
            else if (inCircle(tx, ty, bx, byIn, 44f)) mapZoom = minOf(8f, mapZoom * 1.6f)
            else if (inCircle(tx, ty, bx, byOut, 44f)) mapZoom = maxOf(1f, mapZoom / 1.6f)
        }
        taps.clear()
        val t0 = Gdx.input.isTouched(0)
        val t1 = Gdx.input.isTouched(1)
        val fs = fs0 * mapZoom
        if (t0 && t1) {
            val dx = (Gdx.input.getX(0) - Gdx.input.getX(1)).toFloat()
            val dy = (Gdx.input.getY(0) - Gdx.input.getY(1)).toFloat()
            val d = sqrt(dx * dx + dy * dy)
            if (pinchPrev > 10f && d > 10f) mapZoom = (mapZoom * d / pinchPrev).coerceIn(1f, 8f)
            pinchPrev = d
        } else {
            pinchPrev = 0f
            if (t0) {
                val x = Gdx.input.getX(0).toFloat()
                val y = Gdx.input.getY(0).toFloat()
                val onBtn = inCircle(x, y, bx, byClose, 50f) || inCircle(x, y, bx, byIn, 50f) || inCircle(x, y, bx, byOut, 50f)
                if (!onBtn) {
                    mapCx -= Gdx.input.getDeltaX(0) / fs
                    mapCz -= Gdx.input.getDeltaY(0) / fs
                }
            }
        }
        val ww = rc[2] / (fs0 * mapZoom) / 2f
        val hh = rc[3] / (fs0 * mapZoom) / 2f
        mapCx = if (ww >= world.MW / 2f) 0f else mapCx.coerceIn(world.X0 + ww, world.X0 + world.MW - ww)
        mapCz = if (hh >= world.MH / 2f) 0f else mapCz.coerceIn(world.Z0 + hh, world.Z0 + world.MH - hh)
    }

    // full screen map with every town name
    private fun drawFullMap(w: Float, h: Float) {
        val rc = fullMapRect(w, h)
        val mx0 = rc[0]
        val my0 = rc[1]
        val mw = rc[2]
        val mh = rc[3]
        val fs = mw / world.MW * mapZoom
        val ww = mw / fs / 2f
        val hh = mh / fs / 2f
        fun sx(wx: Float) = mx0 + mw / 2f + (wx - mapCx) * fs
        fun sy(wz: Float) = my0 + mh / 2f - (wz - mapCz) * fs
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(0f, 0f, 0f, 0.86f)
        shapes.rect(0f, 0f, w, h)
        shapes.end()
        // everything below is clipped to the map panel
        sb.begin()
        sb.flush()
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
        Gdx.gl.glScissor(mx0.toInt(), my0.toInt(), mw.toInt(), mh.toInt())
        sb.setColor(1f, 1f, 1f, 1f)
        val u0 = (mapCx - ww - world.X0) / world.MW
        val u1 = (mapCx + ww - world.X0) / world.MW
        val vTop = (mapCz - hh - world.Z0) / world.MH
        val vBot = (mapCz + hh - world.Z0) / world.MH
        sb.draw(world.colorTex, mx0, my0, mw, mh, u0, vBot, u1, vTop)
        sb.end()
        // danger area (outside the safe zone) tinted, safe zone rim in blue, next zone in white
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(0.15f, 0.3f, 1f, 0.26f)
        run {
            val segs = 120
            val zr = zoneR
            val big = zr + 6000f
            for (k in 0 until segs) {
                val a = k * 6.2831855f / segs
                val b = (k + 1) * 6.2831855f / segs
                val ix0 = sx(zoneCx + cos(a) * zr); val iy0 = sy(zoneCz + sin(a) * zr)
                val ix1 = sx(zoneCx + cos(b) * zr); val iy1 = sy(zoneCz + sin(b) * zr)
                val ox0 = sx(zoneCx + cos(a) * big); val oy0 = sy(zoneCz + sin(a) * big)
                val ox1 = sx(zoneCx + cos(b) * big); val oy1 = sy(zoneCz + sin(b) * big)
                shapes.triangle(ix0, iy0, ox0, oy0, ox1, oy1)
                shapes.triangle(ix0, iy0, ox1, oy1, ix1, iy1)
            }
        }
        shapes.setColor(1f, 1f, 1f, 1f)
        for (tn in world.towns) shapes.circle(sx(tn.x), sy(tn.z), 4f)
        shapes.end()
        Gdx.gl.glLineWidth(3f)
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(0.35f, 0.55f, 1f, 1f)
        shapes.circle(sx(zoneCx), sy(zoneCz), zoneR * fs, 128)
        if (!shrinking && !won) {
            shapes.setColor(1f, 1f, 1f, 1f)
            shapes.circle(sx(toCx), sy(toCz), toR * fs, 128)
        }
        shapes.end()
        val ppx = sx(cam.position.x)
        val ppy = sy(cam.position.z)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(1f, 1f, 0f, 1f)
        shapes.circle(ppx, ppy, 9f)
        if (planeActive) {
            shapes.setColor(1f, 1f, 1f, 1f)
            shapes.circle(sx(planeX), sy(planeZ), 8f)
        }
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(1f, 1f, 0f, 1f)
        shapes.line(ppx, ppy, ppx + cam.direction.x * 34f, ppy - cam.direction.z * 34f)
        shapes.end()
        sb.begin()
        font.data.setScale(if (mapZoom > 2.5f) 1.4f else 1.1f)
        for (tn in world.towns) {
            val tx = sx(tn.x)
            val ty = sy(tn.z)
            if (tx < mx0 || tx > mx0 + mw || ty < my0 || ty > my0 + mh) continue
            layout.setText(font, tn.name)
            font.setColor(0f, 0f, 0f, 0.95f)
            font.draw(sb, tn.name, tx - layout.width / 2f + 1.5f, ty + 24f - 1.5f)
            font.setColor(1f, 1f, 1f, 1f)
            font.draw(sb, tn.name, tx - layout.width / 2f, ty + 24f)
        }
        sb.end()
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        // frame, title and buttons
        Gdx.gl.glLineWidth(3f)
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(1f, 1f, 1f, 0.85f)
        shapes.rect(mx0, my0, mw, mh)
        shapes.end()
        val bx = mapBtnX(w)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(0.75f, 0.15f, 0.15f, 0.95f)
        shapes.circle(bx, h - 70f, 40f, 32)
        shapes.setColor(0.2f, 0.25f, 0.35f, 0.95f)
        shapes.circle(bx, h - h * 0.42f, 40f, 32)
        shapes.circle(bx, h - (h * 0.42f + 100f), 40f, 32)
        shapes.end()
        sb.begin()
        font.setColor(1f, 1f, 1f, 1f)
        font.data.setScale(2.6f)
        font.draw(sb, "X", bx - 14f, h - 70f + 18f)
        font.draw(sb, "+", bx - 13f, h - h * 0.42f + 18f)
        font.draw(sb, "-", bx - 9f, h - (h * 0.42f + 100f) + 16f)
        font.data.setScale(1.8f)
        centered("REALM OF ALMARAKAH   -   pinch or drag to zoom", my0 + mh + 46f, w)
        font.data.setScale(1.0f)
        centered("Credits (Sketchfab): Porsche 911 Carrera 4S by Karol Miklas, CC-BY-SA 4.0 | Retro bike by annzep, CC-BY 4.0 | PUBG Green Parachute by Skin-Tracker, CC-BY 4.0 | Sports Bike by Futurealiti, CC-BY 4.0 | Bike by T-FLEX CAD ST, CC-BY 4.0 | Backpacks by Thomas Lean, Bart (CC-BY 4.0) and kam (Hiking Backpack, CC-BY-SA 4.0)", 26f, w)
        font.data.setScale(3f)
        sb.end()
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


class Vehicle(val type: Int, var x: Float, var z: Float, var yaw: Float) {
    var y = 0f
    var v = 0f
    var pitch = 0f
    var dist = 0f
    var steer = 0f
    var hp = when (type) { 0 -> 300f; 2 -> 150f; else -> 90f }
    var wrecked = false
    var wreckT = 0f
}

// compact mesh files made from the 3D models: positions, normals and either vertex colours or UVs
fun loadVMesh(file: String, tex: Texture?): Model {
    val inp = DataInputStream(BufferedInputStream(Gdx.files.internal(file).read(), 1 shl 16))
    val flags = inp.readUnsignedByte()
    val nv = inp.readInt()
    val ni = inp.readInt()
    val stride = if (flags == 1) 8 else 7
    val verts = FloatArray(nv * stride)
    for (k in 0 until nv) {
        val o = k * stride
        for (q in 0..5) verts[o + q] = inp.readFloat()
        if (flags == 1) {
            verts[o + 6] = inp.readFloat()
            verts[o + 7] = inp.readFloat()
        } else {
            val c = inp.readInt()
            verts[o + 6] = Color.toFloatBits((c ushr 24) and 255, (c ushr 16) and 255, (c ushr 8) and 255, 255)
        }
    }
    val idx = ShortArray(ni)
    for (k in 0 until ni) idx[k] = inp.readShort()
    inp.close()
    val mesh = if (flags == 1) Mesh(true, nv, ni, VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.TexCoords(0))
    else Mesh(true, nv, ni, VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked())
    mesh.setVertices(verts)
    mesh.setIndices(idx)
    val mat = Material(ColorAttribute.createDiffuse(0.9f, 0.9f, 0.9f, 1f), IntAttribute.createCullFace(0))
    if (tex != null) mat.set(TextureAttribute.createDiffuse(tex))
    val model = Model()
    val node = Node()
    node.id = "n"
    node.parts.add(NodePart(MeshPart("p", mesh, 0, ni, GL20.GL_TRIANGLES), mat))
    model.nodes.add(node)
    model.meshes.add(mesh)
    model.materials.add(mat)
    model.manageDisposable(mesh)
    return model
}
