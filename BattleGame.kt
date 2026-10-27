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
    var deadT = 0f
}

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
    private val muz = arrayOf(floatArrayOf(-0.179f, 1.607f, 1.072f), floatArrayOf(-0.179f, 1.571f, 1.073f))

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
    private val weapons = ArrayList<WDef>()
    private val weaponProps = ArrayList<Prop>()
    private val itemProps = ArrayList<Prop>()
    private val beams = ArrayList<ModelInstance>()
    private val loot = ArrayList<Loot>()
    private val taps = ArrayList<FloatArray>()
    private val rnd = Random()

    // human characters: [gender][outfit][frame]  frames 0..3 = run cycle, 4 = aiming
    private val humans = Array(2) { Array(3) { arrayOfNulls<ModelInstance>(5) } }
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
        batch = ModelBatch()
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
        loadHumans()

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

    private fun loadHumans() {
        try {
            val loader = G3dModelLoader(JsonReader())
            val provider = object : TextureProvider {
                override fun load(fileName: String): Texture = getTex(fileName)
            }
            val tag = arrayOf("m", "f")
            for (g in 0..1) {
                for (f in 0..4) {
                    val file = "bot_" + tag[g] + "_" + (if (f < 4) "run" + f else "aim") + ".g3dj"
                    val m = loader.loadModel(Gdx.files.internal(file), provider)
                    models.add(m)
                    for (o in 0..2) {
                        val inst = ModelInstance(m)
                        inst.getMaterial("skin").set(TextureAttribute.createDiffuse(getTex("bot_" + tag[g] + "_" + o + ".jpg")))
                        humans[g][o][f] = inst
                    }
                }
            }
            humansOk = true
        } catch (e: Throwable) {
            Gdx.app.error("BattleGame", "Could not load character models", e)
            humansOk = false
        }
    }

    private fun drawBot(b: Bot, frame: Int, fall: Float) {
        val ins = if (humansOk) humans[b.gender][b.outfit][frame] else null
        if (ins == null) {
            boxInst.transform.setToTranslation(b.pos.x, 1f, b.pos.z)
            batch.render(boxInst, env)
            return
        }
        ins.transform.setToTranslation(b.pos.x, 0f, b.pos.z).rotate(Vector3.Y, b.yaw)
        if (fall > 0f) ins.transform.rotate(Vector3.X, -90f * fall)
        batch.render(ins, env)
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
        addLoot(randWeapon(), b.pos.x, b.pos.z)
        addLoot(5, b.pos.x + 1.5f, b.pos.z)
    }

    // bot dies: drop loot and leave a body that falls over
    private fun killBot(b: Bot) {
        onBotDeath(b)
        b.deadT = 0f
        b.shooting = false
        corpses.add(b)
    }

    private fun applyItem(l: Loot): Boolean {
        when (l.kind) {
            4 -> {
                if (hp >= 100f) return false
                hp = minOf(100f, hp + 50f)
                msg = "Medkit +50 HP"
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
            bot.yaw = rnd.nextFloat() * 360f
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
                    inCircle(x, y, swapX, swapY, swapR * 1.15f) ||
                    (nearLoot != null && inCircle(x, y, pickX, pickY, pickR * 1.15f))) {
                    // finger is on a button, not on movement or look
                } else if (x < w / 2f) {
                    mx = ((x - joyX) / joyR).coerceIn(-1f, 1f)
                    my = ((joyY - y) / joyR).coerceIn(-1f, 1f)
                    knobX = joyX + mx * joyR
                    knobY = joyY - my * joyR
                } else {
                    yaw -= Gdx.input.getDeltaX(i) * 0.2f
                    pitch = (pitch - Gdx.input.getDeltaY(i) * 0.2f).coerceIn(-80f, 80f)
                }
            }

            val fwd = Vector3(0f, 0f, -1f).rotate(Vector3.Y, yaw)
            val right = Vector3(fwd).crs(Vector3.Y)
            val speed = 8f
            cam.position.add(fwd.scl(my * speed * dt)).add(right.scl(mx * speed * dt))
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
                } else if (inCircle(tx, ty, swapX, swapY, swapR * 1.15f)) {
                    swapWeapon()
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
                val ray = Ray(Vector3(cam.position), Vector3(cam.direction).nor())
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

                if (botOut) {
                    b.target.set(zoneCx, 0f, zoneCz)
                    speed = 4.5f
                } else if (toP < 60f) {
                    b.target.set(cam.position.x, 0f, cam.position.z)
                    speed = 3.5f
                    facePlayer = true
                    if (toP < 12f || los) move = false
                } else {
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

                var moved = false
                if (move) {
                    val dir = Vector3(b.target.x - b.pos.x, 0f, b.target.z - b.pos.z)
                    if (dir.len() > 0.5f) {
                        dir.nor()
                        if (!facePlayer) b.yaw = atan2(dir.x, dir.z) * 57.29578f
                        dir.scl(speed * dt)
                        b.pos.add(dir)
                        moved = true
                    }
                }
                if (facePlayer) b.yaw = atan2(toPx, toPz) * 57.29578f
                b.pos.x = b.pos.x.coerceIn(-495f, 495f)
                b.pos.z = b.pos.z.coerceIn(-495f, 495f)
                collide(b.pos, 0.8f)

                b.shooting = los
                if (los) {
                    hurt(5f * dt)
                    damaged = true
                } else if (moved) {
                    b.anim += dt * speed * 1.7f
                }
            }
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
        batch.begin(cam)
        batch.render(buildings, env)
        for (b in bots) {
            val bdx = b.pos.x - cam.position.x
            val bdz = b.pos.z - cam.position.z
            if (bdx * bdx + bdz * bdz > 250f * 250f) continue
            if (!cam.frustum.sphereInFrustum(b.pos.x, 1f, b.pos.z, 1.8f)) continue
            drawBot(b, if (b.shooting) 4 else (b.anim.toInt() and 3), 0f)
            if (b.shooting && ((gameTime * 18f).toInt() + b.outfit) % 2 == 0) {
                val mz = muz[b.gender]
                flashInst.transform.setToTranslation(b.pos.x, 0f, b.pos.z).rotate(Vector3.Y, b.yaw).translate(mz[0], mz[1], mz[2])
                batch.render(flashInst, env)
            }
        }
        val ci = corpses.iterator()
        while (ci.hasNext()) {
            val c = ci.next()
            c.deadT += dt
            if (c.deadT > 6f) {
                ci.remove()
                continue
            }
            drawBot(c, 4, minOf(c.deadT / 0.45f, 1f))
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

        // ---- draw the gun in your hands (own camera, never clips into walls) ----
        if (hp > 0f) {
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
        shapes.setColor(1f, 1f, 1f, 0.25f)
        shapes.circle(joyX, h - joyY, joyR)
        shapes.setColor(1f, 1f, 1f, 0.5f)
        shapes.circle(knobX, h - knobY, joyR * 0.4f)
        shapes.setColor(1f, 0.2f, 0.2f, if (firing) 0.8f else 0.45f)
        shapes.circle(fireX, h - fireY, fireR)

        // buttons
        if (reloading) shapes.setColor(1f, 0.6f, 0.1f, 0.6f) else shapes.setColor(1f, 1f, 1f, 0.25f)
        shapes.circle(reloadX, h - reloadY, reloadR)
        shapes.setColor(1f, 1f, 1f, 0.25f)
        shapes.circle(swapX, h - swapY, swapR)
        if (nl != null) {
            shapes.setColor(1f, 0.8f, 0.1f, 0.65f)
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

        // health + armor
        shapes.setColor(0f, 0f, 0f, 0.5f)
        shapes.rect(40f, h - 80f, 400f, 30f)
        shapes.setColor(0.2f, 0.9f, 0.2f, 0.9f)
        shapes.rect(40f, h - 80f, 400f * (hp.coerceAtLeast(0f) / 100f), 30f)
        shapes.setColor(0f, 0f, 0f, 0.5f)
        shapes.rect(40f, h - 108f, 400f, 16f)
        shapes.setColor(0.3f, 0.6f, 1f, 0.95f)
        shapes.rect(40f, h - 108f, 400f * (armor / 100f).coerceIn(0f, 1f), 16f)

        // minimap
        val ms = 0.2f
        val mcx = w - 180f
        val mcy = h - 180f
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
        font.draw(sb, "Kills: $kills", 40f, h - 130f)
        font.draw(sb, "Alive: ${bots.size + (if (hp > 0f) 1 else 0)}", 40f, h - 180f)
        if (!won) {
            val remaining = if (!shrinking) holdTimes[phase] - phaseTime else shrinkTimes[phase] - phaseTime
            val zlabel = if (!shrinking) "Zone ${phase + 1}/${holdTimes.size} closes in ${fmt(remaining)}"
                         else "ZONE CLOSING ${fmt(remaining)}"
            centered(zlabel, h - 30f, w * 0.78f)
            centered("Match time left ${fmt(1200f - matchTime)}", h - 90f, w * 0.78f)
        }

        // weapon + ammo
        val other = slots[1 - cur]
        var wname = def.name
        if (other.type >= 0) wname += "   [swap: " + weapons[other.type].name + "]"
        centered(wname, 150f, w)
        centered(if (reloading) "RELOADING..." else "${slot.mag} / $reserve", 100f, w)

        if (msgT > 0f) centered(msg, h * 0.64f, w)
        if (nl != null) centered("Weapon: " + weapons[nl.kind].name + " - tap PICK", h * 0.34f, w)

        // button labels
        font.data.setScale(2f)
        label("RELOAD", reloadX, h - reloadY)
        label("SWAP", swapX, h - swapY)
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
