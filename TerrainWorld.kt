package com.sohelbr.myapp

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.model.MeshPart
import com.badlogic.gdx.graphics.g3d.model.Node
import com.badlogic.gdx.graphics.g3d.model.NodePart
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.badlogic.gdx.utils.JsonReader
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class MapTown(val name: String, val x: Float, val z: Float, val r: Float)

/** a staircase: surface rises from storey level-1 to storey level along its long axis */
class Ramp(val x0: Float, val x1: Float, val z0: Float, val z1: Float, val alongX: Boolean, val inc: Boolean, val level: Int)

/** a walkable multi-storey building */
class Interior(val x0: Float, val x1: Float, val z0: Float, val z1: Float, val f: Float, val nf: Int, val fh: Float) {
    val ramps = ArrayList<Ramp>()
}

private class BData(val x: Float, val z: Float, val hw: Float, val hd: Float, val hh: Float, val y0: Float,
                    val wr: Float, val wg: Float, val wb: Float, val rr: Float, val rg: Float, val rb: Float,
                    val roof: Int, val win: Int, val door: Int) {
    var mask = 0
    var f = 0f
}

private class MapChunk(val inst: ModelInstance, val box: BoundingBox, val cx: Float, val cz: Float, val mesh: Mesh? = null, val count: Int = 0) {
    var detail: ModelInstance? = null
}

private class FBuf {
    var a = FloatArray(8192)
    var n = 0
    fun add(f: Float) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = f
    }
}

/** accumulates flat-shaded, vertex-coloured triangles for one block of the map */
private class VegBuilder {
    val pieces = ArrayList<FloatArray>()
    var buf = FBuf()
    var verts = 0

    fun flush() {
        if (verts > 0) pieces.add(buf.a.copyOf(buf.n))
        buf = FBuf()
        verts = 0
    }

    fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, c: Float) {
        val ux = bx - ax
        val uy = by - ay
        val uz = bz - az
        val vx = cx - ax
        val vy = cy - ay
        val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-6f) return
        nx /= l
        ny /= l
        nz /= l
        if (verts + 3 > 60000) flush()
        put(ax, ay, az, nx, ny, nz, c)
        put(bx, by, bz, nx, ny, nz, c)
        put(cx, cy, cz, nx, ny, nz, c)
    }

    private fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: Float) {
        buf.add(x); buf.add(y); buf.add(z)
        buf.add(nx); buf.add(ny); buf.add(nz)
        buf.add(c)
        verts++
    }

    fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
             cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, c: Float) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, c)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, c)
    }

    /** axis aligned box, outward faces; top / bottom optional */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, wall: Float, top: Float, withTop: Boolean) {
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, wall)
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, wall)
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, wall)
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, wall)
        if (withTop) quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, top)
    }

    /** box turned by rot (radians) about its centre, with top */
    fun rbox(cx: Float, y0: Float, cz: Float, hx: Float, hz: Float, hh: Float, rot: Float, c: Float, ctop: Float) {
        val co = cos(rot)
        val si = sin(rot)
        fun px(lx: Float, lz: Float) = cx + lx * co - lz * si
        fun pz(lx: Float, lz: Float) = cz + lx * si + lz * co
        val y1 = y0 + hh
        // faces as in box(): +x, -x, +z, -z, top
        quad(px(hx, hz), y0, pz(hx, hz), px(hx, -hz), y0, pz(hx, -hz), px(hx, -hz), y1, pz(hx, -hz), px(hx, hz), y1, pz(hx, hz), c)
        quad(px(-hx, -hz), y0, pz(-hx, -hz), px(-hx, hz), y0, pz(-hx, hz), px(-hx, hz), y1, pz(-hx, hz), px(-hx, -hz), y1, pz(-hx, -hz), c)
        quad(px(-hx, hz), y0, pz(-hx, hz), px(hx, hz), y0, pz(hx, hz), px(hx, hz), y1, pz(hx, hz), px(-hx, hz), y1, pz(-hx, hz), c)
        quad(px(hx, -hz), y0, pz(hx, -hz), px(-hx, -hz), y0, pz(-hx, -hz), px(-hx, -hz), y1, pz(-hx, -hz), px(hx, -hz), y1, pz(hx, -hz), c)
        quad(px(-hx, hz), y1, pz(-hx, hz), px(hx, hz), y1, pz(hx, hz), px(hx, -hz), y1, pz(hx, -hz), px(-hx, -hz), y1, pz(-hx, -hz), ctop)
    }

    fun cylinder(cx: Float, y0: Float, cz: Float, r: Float, hh: Float, segs: Int, c: Float, ctop: Float) {
        for (k in 0 until segs) {
            val a0 = k * 6.2831855f / segs
            val a1 = (k + 1) * 6.2831855f / segs
            val x0 = cx + cos(a0) * r
            val z0 = cz + sin(a0) * r
            val x1 = cx + cos(a1) * r
            val z1 = cz + sin(a1) * r
            tri(x1, y0, z1, x0, y0, z0, x0, y0 + hh, z0, c)
            tri(x1, y0, z1, x0, y0 + hh, z0, x1, y0 + hh, z1, c)
            tri(cx, y0 + hh, cz, x1, y0 + hh, z1, x0, y0 + hh, z0, ctop)
        }
    }

    fun cone(cx: Float, cy: Float, cz: Float, r: Float, h: Float, segs: Int, phase: Float, c: Float) {
        for (k in 0 until segs) {
            val a0 = phase + k * 6.2831855f / segs
            val a1 = phase + (k + 1) * 6.2831855f / segs
            tri(cx + cos(a1) * r, cy, cz + sin(a1) * r, cx + cos(a0) * r, cy, cz + sin(a0) * r, cx, cy + h, cz, c)
        }
    }

    /** hexagonal bipyramid, used for rocks and leaf blobs */
    fun blob(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, segs: Int, phase: Float, up: Float, c1: Float, c2: Float) {
        for (k in 0 until segs) {
            val a0 = phase + k * 6.2831855f / segs
            val a1 = phase + (k + 1) * 6.2831855f / segs
            val x0 = cx + cos(a0) * rx
            val z0 = cz + sin(a0) * rz
            val x1 = cx + cos(a1) * rx
            val z1 = cz + sin(a1) * rz
            tri(x1, cy, z1, x0, cy, z0, cx, cy + ry * up, cz, c1)
            tri(x0, cy, z0, x1, cy, z1, cx, cy - ry * 0.6f, cz, c2)
        }
    }
}

class TerrainWorld {
    companion object {
        const val STEP = 2f
        const val CQ = 32                  // quads per terrain chunk
        const val VEG = 64f                // vegetation block size (m)
        const val WATER_BLOCK = -0.9f      // terrain lower than this is deep water (not walkable)
        const val GS = 16f                 // collision grid cell for trees / rocks / props
    }

    var W = 1441
    var H = 751
    var MW = 2880f
    var MH = 1500f
    var X0 = -1440f
    var Z0 = -750f
    val maxX: Float get() = MW / 2f - 5f
    val maxZ: Float get() = MH / 2f - 5f

    var hs = FloatArray(0)
    val towns = ArrayList<MapTown>()
    val blocks = ArrayList<Block>()
    val regionNames = ArrayList<String>()
    lateinit var colorTex: Texture
    private var regionPix = IntArray(0)
    private var rw = 721
    private var rh = 376
    val houses = ArrayList<FloatArray>()   // x, z, hw, hd, floor y of every house you can walk into
    val interiors = ArrayList<Interior>()
    private val roofGrid = HashMap<Int, ArrayList<FloatArray>>()   // x0,z0,x1,z1,top of every roof you can stand on
    private val intGrid = HashMap<Int, ArrayList<Interior>>()
    val lootSpots = ArrayList<FloatArray>()  // x, floor y, z : free floor spots inside buildings
    private val bdata = ArrayList<BData>()
    private val doorZones = ArrayList<FloatArray>()
    private val intChunks = ArrayList<MapChunk>()
    var intDist = 110f
    private var cxs = FloatArray(0)
    private var czs = FloatArray(0)
    private var crs = FloatArray(0)
    private var cellStart = IntArray(0)
    private var cellItems = IntArray(0)
    private var gw = 0
    private var gh = 0
    private val cList = ArrayList<FloatArray>()
    private lateinit var detailTex: Texture
    private lateinit var detailMat: Material
    var detDist = 80f
    private val models = ArrayList<Model>()
    private val hiChunks = ArrayList<MapChunk?>()
    private val loChunks = ArrayList<MapChunk?>()
    private val vegChunks = ArrayList<MapChunk>()
    private var water: ModelInstance? = null
    private var chunksX = 0
    private var chunksZ = 0
    var hiDist = 260f
    var loDist = 900f
    var vegDist = 420f

    // ------------------------------------------------------------------
    // height queries (same triangulation as the rendered mesh)
    // ------------------------------------------------------------------
    private fun hi(i: Int, j: Int): Float = hs[j.coerceIn(0, H - 1) * W + i.coerceIn(0, W - 1)]

    fun h(x: Float, z: Float): Float {
        val fx = ((x - X0) / STEP).coerceIn(0f, (W - 1.001f))
        val fz = ((z - Z0) / STEP).coerceIn(0f, (H - 1.001f))
        val i = fx.toInt()
        val j = fz.toInt()
        val tx = fx - i
        val tz = fz - j
        val a = hs[j * W + i]
        val b = hs[j * W + i + 1]
        val c = hs[(j + 1) * W + i]
        val d = hs[(j + 1) * W + i + 1]
        return if (tx + tz <= 1f) a + (b - a) * tx + (c - a) * tz
        else d + (b - d) * (1f - tz) + (c - d) * (1f - tx)
    }

    fun walkable(x: Float, z: Float): Boolean {
        if (x < X0 + 4f || x > -X0 - 4f || z < Z0 + 4f || z > -Z0 - 4f) return false
        return h(x, z) > WATER_BLOCK
    }

    fun regionAt(x: Float, z: Float): String {
        if (regionPix.isEmpty()) return ""
        val i = ((x - X0) / 4f).toInt().coerceIn(0, rw - 1)
        val j = ((z - Z0) / 4f).toInt().coerceIn(0, rh - 1)
        val id = regionPix[j * rw + i]
        return if (id in 0 until regionNames.size) regionNames[id] else ""
    }

    fun placeName(x: Float, z: Float): String {
        var best: MapTown? = null
        var bd = 1e9f
        for (t in towns) {
            val dx = x - t.x
            val dz = z - t.z
            val d = sqrt(dx * dx + dz * dz)
            if (d < t.r * 1.1f && d < bd) {
                bd = d
                best = t
            }
        }
        return best?.name ?: regionAt(x, z)
    }

    /** true if the terrain is in the way between two points */
    fun terrainBlocks(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1f) return false
        val n = (len / 3f).toInt().coerceAtLeast(2)
        for (k in 1 until n) {
            val t = k.toFloat() / n
            val x = ax + dx * t
            val y = ay + dy * t
            val z = az + dz * t
            if (y < h(x, z) - 0.05f) return true
        }
        return false
    }

    /** distance along a ray until it dips into the terrain (or maxD) */
    fun terrainRay(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxD: Float): Float {
        var t = 2f
        while (t < maxD) {
            val x = ox + dx * t
            val y = oy + dy * t
            val z = oz + dz * t
            if (y < h(x, z)) return t
            t += if (t < 60f) 2f else 5f
        }
        return maxD
    }

    // ------------------------------------------------------------------
    // loading
    // ------------------------------------------------------------------
    fun load() {
        val info = JsonReader().parse(Gdx.files.internal("map_info.json"))
        W = info.getInt("w"); H = info.getInt("h")
        MW = info.getFloat("mw"); MH = info.getFloat("mh")
        X0 = -MW / 2f; Z0 = -MH / 2f
        rw = info.getInt("rw"); rh = info.getInt("rh")
        hs = FloatArray(W * H)
        // heights
        val pm = Pixmap(Gdx.files.internal("map_height.png"))
        for (j in 0 until H) for (i in 0 until W) {
            val p = pm.getPixel(i, j)
            val r = (p ushr 24) and 0xff
            val g = (p ushr 16) and 0xff
            hs[j * W + i] = ((r shl 8) or g) / 500f - 10f
        }
        pm.dispose()
        // regions
        val rp = Pixmap(Gdx.files.internal("map_region.png"))
        regionPix = IntArray(rw * rh)
        val rAlpha = rp.format == Pixmap.Format.Alpha
        for (j in 0 until rh) for (i in 0 until rw) { val q = rp.getPixel(i, j); regionPix[j * rw + i] = if (rAlpha) q and 0xff else (q ushr 24) and 0xff }
        rp.dispose()
        for (n in info.get("regions")) regionNames.add(n.asString())
        for (t in info.get("towns")) towns.add(MapTown(t.getString("name"), t.getFloat("x"), t.getFloat("z"), t.getFloat("r")))
        // colour texture (4096 x 2048)
        val cp = Pixmap(Gdx.files.internal("map_color.jpg"))
        colorTex = Texture(cp, cp.format, true)
        cp.dispose()
        colorTex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        colorTex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
        try { colorTex.setAnisotropicFilter(4f) } catch (e: Throwable) { }
        // tiled detail texture for the close-up detail pass
        val dsrc = Pixmap(Gdx.files.internal("map_detail.png"))
        val dp = Pixmap(dsrc.width, dsrc.height, Pixmap.Format.RGBA8888)
        val dAlpha = dsrc.format == Pixmap.Format.Alpha
        for (j in 0 until dsrc.height) for (i in 0 until dsrc.width) {
            val q = dsrc.getPixel(i, j)
            val v = if (dAlpha) q and 0xff else (q ushr 24) and 0xff
            dp.drawPixel(i, j, (v shl 24) or (v shl 16) or (v shl 8) or 0xff)
        }
        dsrc.dispose()
        detailTex = Texture(dp, dp.format, true)
        dp.dispose()
        detailTex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        detailTex.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)
        val ta = TextureAttribute.createDiffuse(detailTex)
        ta.scaleU = MW / 3f
        ta.scaleV = MH / 3f
        detailMat = Material(ta, BlendingAttribute(GL20.GL_DST_COLOR, GL20.GL_SRC_COLOR),
            DepthTestAttribute(GL20.GL_LEQUAL, false))

        readBuildings()
        flattenBuildings()
        buildTerrain()
        buildObjects()
        buildColliderGrid()
        buildWater()
    }

    private fun skipFully(inp: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = inp.skip(left)
            if (k <= 0) { inp.readByte(); left-- } else left -= k
        }
    }

    private fun readBuildings() {
        val inp = DataInputStream(BufferedInputStream(Gdx.files.internal("map_obj.bin").read(), 1 shl 16))
        val nT = inp.readInt()
        skipFully(inp, nT * 10L)
        val nR = inp.readInt()
        skipFully(inp, nR * 13L)
        val nB = inp.readInt()
        for (t in 0 until nB) {
            val x = inp.readFloat(); val z = inp.readFloat()
            val hw = inp.readFloat(); val hd = inp.readFloat(); val hh = inp.readFloat(); val y0 = inp.readFloat()
            val wr = inp.readUnsignedByte() / 255f; val wg = inp.readUnsignedByte() / 255f; val wb = inp.readUnsignedByte() / 255f
            val rr = inp.readUnsignedByte() / 255f; val rg = inp.readUnsignedByte() / 255f; val rb = inp.readUnsignedByte() / 255f
            val roof = inp.readByte().toInt(); val win = inp.readByte().toInt(); val door = inp.readByte().toInt()
            val bd = BData(x, z, hw, hd, hh, y0, wr, wg, wb, rr, rg, rb, roof, win, door)
            if (door in 1..4) {
                val opp = when (door) { 1 -> 2; 2 -> 1; 3 -> 4; else -> 3 }
                bd.mask = (1 shl (door - 1)) or (if (maxOf(hw, hd) >= 8f) (1 shl (opp - 1)) else 0)
            }
            bdata.add(bd)
        }
        inp.close()
    }

    /** level the ground under every walkable building so the floor sits exactly on the terrain */
    private fun flattenBuildings() {
        val locked = BooleanArray(W * H)
        val m1 = 3.0f
        val m2 = 5.0f
        for (bd in bdata) {
            if (bd.door !in 1..4) continue
            var sum = 0f
            var n = 0
            var zz = bd.z - bd.hd
            while (zz <= bd.z + bd.hd + 0.01f) {
                var xx = bd.x - bd.hw
                while (xx <= bd.x + bd.hw + 0.01f) { sum += h(xx, zz); n++; xx += 1.5f }
                zz += 1.5f
            }
            bd.f = maxOf(if (n > 0) sum / n else h(bd.x, bd.z), bd.y0 + 0.6f)
            val i0 = ((bd.x - bd.hw - m1 - m2 - X0) / STEP).toInt().coerceIn(0, W - 1)
            val i1 = (((bd.x + bd.hw + m1 + m2 - X0) / STEP).toInt() + 1).coerceIn(0, W - 1)
            val j0 = ((bd.z - bd.hd - m1 - m2 - Z0) / STEP).toInt().coerceIn(0, H - 1)
            val j1 = (((bd.z + bd.hd + m1 + m2 - Z0) / STEP).toInt() + 1).coerceIn(0, H - 1)
            for (j in j0..j1) for (i in i0..i1) {
                val idx = j * W + i
                if (locked[idx]) continue
                val px = X0 + i * STEP
                val pz = Z0 + j * STEP
                val dx = maxOf(abs(px - bd.x) - bd.hw, 0f)
                val dz = maxOf(abs(pz - bd.z) - bd.hd, 0f)
                val d = sqrt(dx * dx + dz * dz)
                if (d <= m1) {
                    hs[idx] = bd.f
                    locked[idx] = true
                } else if (d < m1 + m2) {
                    val u = (d - m1) / m2
                    val w = 1f - u * u * (3f - 2f * u)
                    hs[idx] = hs[idx] * (1f - w) + bd.f * w
                }
            }
            // keep trees, rocks and props away from every doorway
            for (d in 1..4) {
                if ((bd.mask and (1 shl (d - 1))) == 0) continue
                when (d) {
                    1 -> doorZones.add(floatArrayOf(bd.x - 2.6f, bd.z + bd.hd, bd.x + 2.6f, bd.z + bd.hd + 5f))
                    2 -> doorZones.add(floatArrayOf(bd.x - 2.6f, bd.z - bd.hd - 5f, bd.x + 2.6f, bd.z - bd.hd))
                    3 -> doorZones.add(floatArrayOf(bd.x + bd.hw, bd.z - 2.6f, bd.x + bd.hw + 5f, bd.z + 2.6f))
                    else -> doorZones.add(floatArrayOf(bd.x - bd.hw - 5f, bd.z - 2.6f, bd.x - bd.hw, bd.z + 2.6f))
                }
            }
        }
    }

    private fun nearDoor(x: Float, z: Float): Boolean {
        for (q in doorZones) if (x >= q[0] - 0.8f && x <= q[2] + 0.8f && z >= q[1] - 0.8f && z <= q[3] + 0.8f) return true
        return false
    }

    private fun rampSteps(fh: Float): Int = maxOf(4, Math.round(fh / 0.2f))

    private fun registerInterior(iv: Interior) {
        interiors.add(iv)
        val i0 = ((iv.x0 - X0) / 32f).toInt().coerceAtLeast(0)
        val i1 = ((iv.x1 - X0) / 32f).toInt().coerceAtLeast(0)
        val j0 = ((iv.z0 - Z0) / 32f).toInt().coerceAtLeast(0)
        val j1 = ((iv.z1 - Z0) / 32f).toInt().coerceAtLeast(0)
        for (j in j0..j1) for (i in i0..i1) intGrid.getOrPut(j * 1024 + i) { ArrayList() }.add(iv)
    }

    /** height of the floor you would stand on at (x,z); feet = current feet height (picks the right storey) */
    fun groundY(x: Float, z: Float, feet: Float): Float {
        var best = h(x, z)
        val rl = roofGrid[((z - Z0) / 32f).toInt().coerceAtLeast(0) * 1024 + ((x - X0) / 32f).toInt().coerceAtLeast(0)]
        if (rl != null) for (rf in rl) {
            if (x < rf[0] || x > rf[2] || z < rf[1] || z > rf[3]) continue
            val k = rf[5].toInt()
            val top = when (k) {
                0 -> rf[4] + 0.4f
                3 -> rf[4] + 0.02f
                1 -> {
                    val alongX = rf[9] >= rf[10]
                    val w = if (alongX) abs(z - rf[8]) / rf[10] else abs(x - rf[7]) / rf[9]
                    rf[4] + rf[6] * (1f - w).coerceAtLeast(0f)
                }
                else -> rf[4] + rf[6] * (1f - maxOf(abs(x - rf[7]) / rf[9], abs(z - rf[8]) / rf[10])).coerceAtLeast(0f)
            }
            if (top <= feet + 0.85f && top > best) best = top
        }
        if (interiors.isEmpty()) return best
        val lst = intGrid[((z - Z0) / 32f).toInt().coerceAtLeast(0) * 1024 + ((x - X0) / 32f).toInt().coerceAtLeast(0)] ?: return best
        for (iv in lst) {
            if (x < iv.x0 || x > iv.x1 || z < iv.z0 || z > iv.z1) continue
            for (r in iv.ramps) {
                if (x < r.x0 || x > r.x1 || z < r.z0 || z > r.z1) continue
                val t0 = if (r.alongX) (x - r.x0) / (r.x1 - r.x0) else (z - r.z0) / (r.z1 - r.z0)
                val t = if (r.inc) t0 else 1f - t0
                val n = rampSteps(iv.fh)
                val s = minOf(floor(t * n) + 1f, n.toFloat()) / n
                val y = iv.f + (r.level - 1) * iv.fh + s * iv.fh
                if (y <= feet + 0.85f && y > best) best = y
            }
            for (k in 1 until iv.nf) {
                var hole = false
                for (r in iv.ramps) if (r.level == k && x >= r.x0 && x <= r.x1 && z >= r.z0 && z <= r.z1) hole = true
                if (hole) continue
                val y = iv.f + k * iv.fh
                if (y <= feet + 0.85f && y > best) best = y
            }
        }
        return best
    }

    private fun addCircle(x: Float, z: Float, r: Float) {
        cList.add(floatArrayOf(x, z, r))
    }

    private fun buildColliderGrid() {
        val n = cList.size
        cxs = FloatArray(n); czs = FloatArray(n); crs = FloatArray(n)
        gw = (MW / GS).toInt() + 2
        gh = (MH / GS).toInt() + 2
        val counts = IntArray(gw * gh + 1)
        val cellOf = IntArray(n)
        for (k in 0 until n) {
            val c = cList[k]
            cxs[k] = c[0]; czs[k] = c[1]; crs[k] = c[2]
            val i = ((c[0] - X0) / GS).toInt().coerceIn(0, gw - 1)
            val j = ((c[1] - Z0) / GS).toInt().coerceIn(0, gh - 1)
            cellOf[k] = j * gw + i
            counts[cellOf[k] + 1]++
        }
        for (c in 0 until gw * gh) counts[c + 1] += counts[c]
        cellStart = counts
        cellItems = IntArray(n)
        val fill = IntArray(gw * gh)
        for (k in 0 until n) {
            val c = cellOf[k]
            cellItems[cellStart[c] + fill[c]] = k
            fill[c]++
        }
        cList.clear()
    }

    /** push a position out of trees, rocks and props (circle colliders) */
    fun collideCircles(p: Vector3, rad: Float) {
        if (cellItems.isEmpty()) return
        val ci = ((p.x - X0) / GS).toInt()
        val cj = ((p.z - Z0) / GS).toInt()
        for (dj in -1..1) for (di in -1..1) {
            val i = ci + di
            val j = cj + dj
            if (i < 0 || j < 0 || i >= gw || j >= gh) continue
            val c = j * gw + i
            for (q in cellStart[c] until cellStart[c + 1]) {
                val k = cellItems[q]
                val dx = p.x - cxs[k]
                val dz = p.z - czs[k]
                val rr = crs[k] + rad
                val d2 = dx * dx + dz * dz
                if (d2 < rr * rr) {
                    if (d2 > 0.00001f) {
                        val d = sqrt(d2)
                        p.x += dx / d * (rr - d)
                        p.z += dz / d * (rr - d)
                    } else {
                        p.x += rr
                    }
                }
            }
        }
    }

    private fun makeModel(mesh: Mesh, count: Int, mat: Material): Model {
        val model = Model()
        val node = Node()
        node.id = "n"
        node.parts.add(NodePart(MeshPart("p", mesh, 0, count, GL20.GL_TRIANGLES), mat))
        model.nodes.add(node)
        model.meshes.add(mesh)
        model.materials.add(mat)
        model.manageDisposable(mesh)
        models.add(model)
        return model
    }

    private fun buildTerrain() {
        val mat = Material(
            TextureAttribute.createDiffuse(colorTex),
            ColorAttribute.createDiffuse(0.70f, 0.70f, 0.70f, 1f)
        )
        chunksX = (W - 1) / CQ
        chunksZ = ((H - 1) + CQ - 1) / CQ
        for (cj in 0 until chunksZ) for (ci in 0 until chunksX) {
            val i0 = ci * CQ
            val j0 = cj * CQ
            val i1 = minOf(i0 + CQ, W - 1)
            val j1 = minOf(j0 + CQ, H - 1)
            val box = BoundingBox()
            val hiM = gridMesh(i0, j0, i1, j1, 1, box)
            val loM = gridMesh(i0, j0, i1, j1, 4, null)
            val cx = X0 + (i0 + i1) * 0.5f * STEP
            val cz = Z0 + (j0 + j1) * 0.5f * STEP
            val hiInst = ModelInstance(makeModel(hiM.first, hiM.second, mat))
            val loInst = ModelInstance(makeModel(loM.first, loM.second, mat))
            hiChunks.add(MapChunk(hiInst, box, cx, cz, hiM.first, hiM.second))
            loChunks.add(MapChunk(loInst, box, cx, cz))
        }
    }

    /** terrain grid with skirts; returns mesh and index count */
    private fun gridMesh(i0: Int, j0: Int, i1: Int, j1: Int, s: Int, bounds: BoundingBox?): Pair<Mesh, Int> {
        val nx = (i1 - i0) / s + 1
        val nz = (j1 - j0) / s + 1
        val skirtN = 2 * (nx + nz)
        val nv = nx * nz + skirtN
        val v = FloatArray(nv * 8)
        var minY = 1e9f
        var maxY = -1e9f
        var k = 0
        fun put(i: Int, j: Int, dropY: Float) {
            val hh = hi(i, j)
            val hx = (hi(i + s, j) - hi(i - s, j)) / (2f * s * STEP)
            val hz = (hi(i, j + s) - hi(i, j - s)) / (2f * s * STEP)
            var ux = -hx
            var uy = 1f
            var uz = -hz
            val l = sqrt(ux * ux + uy * uy + uz * uz)
            ux /= l; uy /= l; uz /= l
            val x = X0 + i * STEP
            val z = Z0 + j * STEP
            v[k++] = x; v[k++] = hh - dropY; v[k++] = z
            v[k++] = ux; v[k++] = uy; v[k++] = uz
            v[k++] = (x - X0) / MW
            v[k++] = (z - Z0) / MH
            if (hh - dropY < minY) minY = hh - dropY
            if (hh > maxY) maxY = hh
        }
        for (b in 0 until nz) for (a in 0 until nx) put(i0 + a * s, j0 + b * s, 0f)
        // skirt vertices: top edge, bottom edge, left edge, right edge
        val skirtStart = nx * nz
        for (a in 0 until nx) put(i0 + a * s, j0, 2.5f)
        for (a in 0 until nx) put(i0 + a * s, j1, 2.5f)
        for (b in 0 until nz) put(i0, j0 + b * s, 2.5f)
        for (b in 0 until nz) put(i1, j0 + b * s, 2.5f)
        val idx = ShortArray((nx - 1) * (nz - 1) * 6 + (nx - 1) * 24 + (nz - 1) * 24)
        var q = 0
        for (b in 0 until nz - 1) for (a in 0 until nx - 1) {
            val va = b * nx + a
            val vb = va + 1
            val vc = va + nx
            val vd = vc + 1
            idx[q++] = va.toShort(); idx[q++] = vc.toShort(); idx[q++] = vb.toShort()
            idx[q++] = vb.toShort(); idx[q++] = vc.toShort(); idx[q++] = vd.toShort()
        }
        // skirts (double sided so they are visible from either side)
        for (a in 0 until nx - 1) {
            val t0 = a
            val t1 = a + 1
            val s0 = skirtStart + a
            val s1 = skirtStart + a + 1
            idx[q++] = t0.toShort(); idx[q++] = s0.toShort(); idx[q++] = t1.toShort()
            idx[q++] = t1.toShort(); idx[q++] = s0.toShort(); idx[q++] = s1.toShort()
            idx[q++] = t0.toShort(); idx[q++] = t1.toShort(); idx[q++] = s0.toShort()
            idx[q++] = t1.toShort(); idx[q++] = s1.toShort(); idx[q++] = s0.toShort()
            val bt0 = (nz - 1) * nx + a
            val bt1 = bt0 + 1
            val bs0 = skirtStart + nx + a
            val bs1 = bs0 + 1
            idx[q++] = bt0.toShort(); idx[q++] = bs0.toShort(); idx[q++] = bt1.toShort()
            idx[q++] = bt1.toShort(); idx[q++] = bs0.toShort(); idx[q++] = bs1.toShort()
            idx[q++] = bt0.toShort(); idx[q++] = bt1.toShort(); idx[q++] = bs0.toShort()
            idx[q++] = bt1.toShort(); idx[q++] = bs1.toShort(); idx[q++] = bs0.toShort()
        }
        for (b in 0 until nz - 1) {
            val l0 = b * nx
            val l1 = (b + 1) * nx
            val sl0 = skirtStart + 2 * nx + b
            val sl1 = sl0 + 1
            idx[q++] = l0.toShort(); idx[q++] = sl0.toShort(); idx[q++] = l1.toShort()
            idx[q++] = l1.toShort(); idx[q++] = sl0.toShort(); idx[q++] = sl1.toShort()
            idx[q++] = l0.toShort(); idx[q++] = l1.toShort(); idx[q++] = sl0.toShort()
            idx[q++] = l1.toShort(); idx[q++] = sl1.toShort(); idx[q++] = sl0.toShort()
            val r0 = b * nx + nx - 1
            val r1 = r0 + nx
            val sr0 = skirtStart + 2 * nx + nz + b
            val sr1 = sr0 + 1
            idx[q++] = r0.toShort(); idx[q++] = sr0.toShort(); idx[q++] = r1.toShort()
            idx[q++] = r1.toShort(); idx[q++] = sr0.toShort(); idx[q++] = sr1.toShort()
            idx[q++] = r0.toShort(); idx[q++] = r1.toShort(); idx[q++] = sr0.toShort()
            idx[q++] = r1.toShort(); idx[q++] = sr1.toShort(); idx[q++] = sr0.toShort()
        }
        val mesh = Mesh(true, nv, q, VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.TexCoords(0))
        mesh.setVertices(v)
        mesh.setIndices(idx, 0, q)
        bounds?.set(
            Vector3(X0 + i0 * STEP, minY, Z0 + j0 * STEP),
            Vector3(X0 + i1 * STEP, maxY + 0.5f, Z0 + j1 * STEP)
        )
        return Pair(mesh, q)
    }

    private fun buildWater() {
        val mat = Material(
            ColorAttribute.createDiffuse(0.22f, 0.55f, 0.72f, 1f),
            BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 0.5f),
            IntAttribute.createCullFace(0)
        )
        val v = floatArrayOf(
            -3000f, 0f, -3000f, 0f, 1f, 0f,
            3000f, 0f, -3000f, 0f, 1f, 0f,
            -3000f, 0f, 3000f, 0f, 1f, 0f,
            3000f, 0f, 3000f, 0f, 1f, 0f
        )
        val mesh = Mesh(true, 4, 6, VertexAttribute.Position(), VertexAttribute.Normal())
        mesh.setVertices(v)
        mesh.setIndices(shortArrayOf(0, 2, 1, 1, 2, 3))
        water = ModelInstance(makeModel(mesh, 6, mat))
    }

    // ------------------------------------------------------------------
    // trees, rocks and buildings (merged into 64 m blocks)
    // ------------------------------------------------------------------
    private fun cb(r: Float, g: Float, b: Float): Float =
        Color.toFloatBits(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), 1f)

    private fun buildObjects() {
        val vx = ((MW / VEG).toInt() + 1)
        val vz = ((MH / VEG).toInt() + 1)
        val builders = arrayOfNulls<VegBuilder>(vx * vz)
        val ibuilders = arrayOfNulls<VegBuilder>(vx * vz)
        fun ibld(x: Float, z: Float): VegBuilder {
            val i = ((x - X0) / VEG).toInt().coerceIn(0, vx - 1)
            val j = ((z - Z0) / VEG).toInt().coerceIn(0, vz - 1)
            val k = j * vx + i
            val ex = ibuilders[k]
            if (ex != null) return ex
            val nb = VegBuilder()
            ibuilders[k] = nb
            return nb
        }
        fun bld(x: Float, z: Float): VegBuilder {
            val i = ((x - X0) / VEG).toInt().coerceIn(0, vx - 1)
            val j = ((z - Z0) / VEG).toInt().coerceIn(0, vz - 1)
            val k = j * vx + i
            val ex = builders[k]
            if (ex != null) return ex
            val nb = VegBuilder()
            builders[k] = nb
            return nb
        }
        loadTreeModel()
        val rnd = Random(4242)
        val inp = DataInputStream(BufferedInputStream(Gdx.files.internal("map_obj.bin").read(), 1 shl 16))
        val nTrees = inp.readInt()
        for (t in 0 until nTrees) {
            val x = inp.readFloat()
            val z = inp.readFloat()
            val type = inp.readByte().toInt()
            val s = inp.readByte().toInt() / 50f
            if (nearDoor(x, z)) continue
            addTree(bld(x, z), x, h(x, z) - 0.15f, z, type, s, rnd)
            val tr = when (type) { 0, 1 -> 0.45f * s; 2 -> 0.5f * s; 3 -> 0.6f * s; 4 -> 0.3f * s; 5 -> 0.55f * s; else -> 0.35f * s }
            addCircle(x, z, tr)
        }
        val nRocks = inp.readInt()
        for (t in 0 until nRocks) {
            val x = inp.readFloat()
            val z = inp.readFloat()
            val sz = inp.readFloat()
            val cls = inp.readByte().toInt()
            if (nearDoor(x, z)) continue
            addRock(bld(x, z), x, h(x, z), z, sz, cls, rnd)
            addCircle(x, z, sz * 0.85f)
        }
        for (bd in bdata) {
            if (bd.door !in 1..4) blocks.add(Block(bd.x, bd.z, bd.hw, bd.hd, bd.hh, bd.y0))
            else houses.add(floatArrayOf(bd.x, bd.z, bd.hw, bd.hd, bd.f))
            addBuilding(bld(bd.x, bd.z), ibld(bd.x, bd.z), bd, cb(bd.wr, bd.wg, bd.wb), cb(bd.rr, bd.rg, bd.rb), rnd)
        }
        inp.close()
        // props: crates, barrels, lamps, haystacks, wells, bushes
        val pin = DataInputStream(BufferedInputStream(Gdx.files.internal("map_prop.bin").read(), 1 shl 16))
        val nP = pin.readInt()
        for (t in 0 until nP) {
            val x = pin.readFloat()
            val z = pin.readFloat()
            val type = pin.readUnsignedByte()
            val rot = pin.readUnsignedByte() * 6.2831855f / 255f
            val sc = pin.readUnsignedByte() / 40f
            if (nearDoor(x, z)) continue
            addProp(bld(x, z), x, h(x, z), z, type, rot, sc, rnd)
        }
        pin.close()

        val mat = Material(
            ColorAttribute.createDiffuse(0.72f, 0.72f, 0.72f, 1f),
            IntAttribute.createCullFace(0)
        )
        fun emit(arr: Array<VegBuilder?>, out: ArrayList<MapChunk>) {
            for (j in 0 until vz) for (i in 0 until vx) {
                val b = arr[j * vx + i] ?: continue
                b.flush()
                val box = BoundingBox()
                box.set(Vector3(X0 + i * VEG - 3f, -10f, Z0 + j * VEG - 3f), Vector3(X0 + (i + 1) * VEG + 3f, 90f, Z0 + (j + 1) * VEG + 3f))
                for (piece in b.pieces) {
                    val nv = piece.size / 7
                    val mesh = Mesh(true, nv, 0, VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked())
                    mesh.setVertices(piece)
                    val inst = ModelInstance(makeModel(mesh, nv, mat))
                    out.add(MapChunk(inst, box, X0 + (i + 0.5f) * VEG, Z0 + (j + 0.5f) * VEG))
                }
            }
        }
        emit(builders, vegChunks)
        emit(ibuilders, intChunks)
    }

    // low-poly tree model (tree.bin): per triangle 9 floats (x,y,z of 3 corners) + packed rgba
    private val treeTpls = ArrayList<FloatArray>()
    private val treeCols = ArrayList<IntArray>()
    private fun loadTreeModel() {
        for (name in arrayOf("tree.bin", "tree_elm.bin", "tree_lg.bin")) {
            try {
                val inp = DataInputStream(BufferedInputStream(Gdx.files.internal(name).read(), 1 shl 14))
                val n = inp.readInt()
                val tp = FloatArray(n * 9)
                val tc = IntArray(n)
                for (t in 0 until n) {
                    for (k in 0 until 9) tp[t * 9 + k] = inp.readFloat()
                    tc[t] = inp.readInt()
                }
                inp.close()
                treeTpls.add(tp)
                treeCols.add(tc)
            } catch (e: Throwable) {
            }
        }
    }

    private fun addTreeModel(b: VegBuilder, x: Float, y: Float, z: Float, type: Int, s: Float, r: Random): Boolean {
        if (treeTpls.isEmpty()) return false
        val mi = if (type == 2 && treeTpls.size > 1) 1 + r.nextInt(treeTpls.size - 1) else r.nextInt(treeTpls.size)
        val tp = treeTpls[mi]
        val tc = treeCols[mi]
        val v = 0.85f + r.nextFloat() * 0.3f
        val ang = r.nextFloat() * 6.2831855f
        val ca = cos(ang)
        val sa = sin(ang)
        val k = (when (type) { 0, 1 -> 4.0f; 2 -> 3.2f; else -> 3.6f }) * s * (0.9f + r.nextFloat() * 0.2f)
        val snow = type == 1
        val dry = type == 2 && r.nextInt(8) == 0
        for (t in 0 until tc.size) {
            val o = t * 9
            val c = tc[t]
            var cr = ((c ushr 24) and 255) / 255f
            var cg = ((c ushr 16) and 255) / 255f
            var cbl = ((c ushr 8) and 255) / 255f
            val leaf = cg > cr
            if (leaf) {
                if (snow) { cr = 0.72f; cg = 0.84f; cbl = 0.78f }
                else if (dry) { cr = 0.70f; cg = 0.45f; cbl = 0.12f }
                else if (type == 0 || type == 3) { cr *= 0.65f; cg *= 0.85f; cbl *= 0.7f }
            }
            val col = cb(cr * v, cg * v, cbl * v)
            val px = FloatArray(3)
            val py = FloatArray(3)
            val pz = FloatArray(3)
            for (q in 0 until 3) {
                val lx = tp[o + q * 3]
                val ly = tp[o + q * 3 + 1]
                val lz = tp[o + q * 3 + 2]
                px[q] = x + (lx * ca - lz * sa) * k
                py[q] = y + ly * k
                pz[q] = z + (lx * sa + lz * ca) * k
            }
            b.tri(px[0], py[0], pz[0], px[1], py[1], pz[1], px[2], py[2], pz[2], col)
        }
        return true
    }

    private fun addTree(b: VegBuilder, x: Float, y: Float, z: Float, type: Int, s: Float, r: Random) {
        if (type in 0..3 && addTreeModel(b, x, y, z, type, s, r)) return
        val v = 0.85f + r.nextFloat() * 0.3f
        val ph = r.nextFloat() * 6.28f
        val trunk = cb(0.36f * v, 0.25f * v, 0.15f * v)
        when (type) {
            0, 1 -> {
                val hgt = 9f * s
                val tw = 0.22f * s
                b.box(x - tw, y, z - tw, x + tw, y + hgt * 0.35f, z + tw, trunk, trunk, false)
                val snow = type == 1
                val g1 = if (snow) cb(0.55f * v, 0.70f * v, 0.62f * v) else cb(0.10f * v, 0.30f * v, 0.13f * v)
                val g2 = if (snow) cb(0.72f * v, 0.82f * v, 0.76f * v) else cb(0.13f * v, 0.36f * v, 0.15f * v)
                val g3 = if (snow) cb(0.90f * v, 0.95f * v, 0.95f * v) else cb(0.17f * v, 0.42f * v, 0.17f * v)
                b.cone(x, y + hgt * 0.18f, z, 2.1f * s, hgt * 0.40f, 6, ph, g1)
                b.cone(x, y + hgt * 0.38f, z, 1.6f * s, hgt * 0.38f, 6, ph + 0.4f, g2)
                b.cone(x, y + hgt * 0.60f, z, 1.05f * s, hgt * 0.40f, 6, ph + 0.8f, g3)
            }
            2 -> {
                val tw = 0.3f * s
                b.box(x - tw, y, z - tw, x + tw, y + 3.4f * s, z + tw, trunk, trunk, false)
                val autumn = r.nextInt(8) == 0
                val c1 = if (autumn) cb(0.70f * v, 0.42f * v, 0.10f * v) else cb(0.22f * v, 0.46f * v, 0.14f * v)
                val c2 = if (autumn) cb(0.80f * v, 0.55f * v, 0.12f * v) else cb(0.32f * v, 0.55f * v, 0.18f * v)
                b.blob(x, y + 4.6f * s, z, 2.7f * s, 2.2f * s, 2.7f * s, 6, ph, 1f, c2, c1)
                b.blob(x + 0.9f * s, y + 5.6f * s, z + 0.5f * s, 1.8f * s, 1.5f * s, 1.8f * s, 5, ph, 1f, c2, c1)
            }
            3 -> {
                val tw = 0.35f * s
                b.box(x - tw, y, z - tw, x + tw, y + 7f * s, z + tw, trunk, trunk, false)
                val c1 = cb(0.07f * v, 0.27f * v, 0.09f * v)
                val c2 = cb(0.10f * v, 0.34f * v, 0.11f * v)
                b.cone(x, y + 5.2f * s, z, 3.4f * s, 2.0f * s, 6, ph, c1)
                b.cone(x, y + 6.6f * s, z, 4.4f * s, 2.4f * s, 7, ph + 0.5f, c2)
            }
            4 -> {
                val tw = 0.18f * s
                val hgt = 6.2f * s
                b.box(x - tw, y, z - tw, x + tw, y + hgt, z + tw, cb(0.5f * v, 0.38f * v, 0.22f * v), trunk, false)
                val leaf = cb(0.18f * v, 0.50f * v, 0.14f * v)
                for (k in 0 until 6) {
                    val a = ph + k * 1.0472f
                    val ca = cos(a)
                    val sa = sin(a)
                    val tx = x + ca * 3.2f * s
                    val tz = z + sa * 3.2f * s
                    val ty = y + hgt - 1.2f * s
                    val px = -sa * 0.55f * s
                    val pz = ca * 0.55f * s
                    b.tri(x + px, y + hgt, z + pz, x - px, y + hgt, z - pz, tx, ty, tz, leaf)
                    b.tri(x - px, y + hgt, z - pz, x + px, y + hgt, z + pz, tx, ty, tz, leaf)
                }
            }
            5 -> {
                val g = cb(0.20f * v, 0.46f * v, 0.22f * v)
                b.box(x - 0.35f * s, y, z - 0.35f * s, x + 0.35f * s, y + 3.2f * s, z + 0.35f * s, g, g, true)
                b.box(x + 0.35f * s, y + 1.4f * s, z - 0.2f * s, x + 1.1f * s, y + 1.9f * s, z + 0.2f * s, g, g, true)
                b.box(x + 0.75f * s, y + 1.9f * s, z - 0.2f * s, x + 1.15f * s, y + 2.9f * s, z + 0.2f * s, g, g, true)
                b.box(x - 1.1f * s, y + 1.0f * s, z - 0.2f * s, x - 0.35f * s, y + 1.5f * s, z + 0.2f * s, g, g, true)
            }
            else -> {
                val d = cb(0.30f * v, 0.23f * v, 0.17f * v)
                b.box(x - 0.2f * s, y, z - 0.2f * s, x + 0.2f * s, y + 4.2f * s, z + 0.2f * s, d, d, true)
                b.box(x + 0.2f * s, y + 2.4f * s, z - 0.12f * s, x + 1.5f * s, y + 2.8f * s, z + 0.12f * s, d, d, true)
                b.box(x - 1.3f * s, y + 3.0f * s, z - 0.12f * s, x - 0.2f * s, y + 3.4f * s, z + 0.12f * s, d, d, true)
            }
        }
    }

    private fun addRock(b: VegBuilder, x: Float, y: Float, z: Float, s: Float, cls: Int, r: Random) {
        val v = 0.8f + r.nextFloat() * 0.35f
        val c1: Float
        val c2: Float
        when (cls) {
            1 -> { c1 = cb(0.80f * v, 0.66f * v, 0.45f * v); c2 = cb(0.62f * v, 0.50f * v, 0.34f * v) }
            2 -> { c1 = cb(0.66f * v, 0.32f * v, 0.20f * v); c2 = cb(0.48f * v, 0.22f * v, 0.14f * v) }
            else -> { c1 = cb(0.52f * v, 0.51f * v, 0.49f * v); c2 = cb(0.36f * v, 0.35f * v, 0.34f * v) }
        }
        b.blob(x, y + s * 0.15f, z, s * (0.9f + r.nextFloat() * 0.3f), s * 0.85f, s * (0.9f + r.nextFloat() * 0.3f),
            6, r.nextFloat() * 6.28f, 1f, c1, c2)
    }

    private fun addProp(b: VegBuilder, x: Float, y: Float, z: Float, type: Int, rot: Float, sc: Float, r: Random) {
        val v = 0.85f + r.nextFloat() * 0.3f
        when (type) {
            0 -> {
                val c = cb(0.55f * v, 0.40f * v, 0.22f * v)
                val ct = cb(0.62f * v, 0.46f * v, 0.26f * v)
                b.rbox(x, y - 0.05f, z, 0.45f * sc, 0.45f * sc, 0.95f * sc, rot, c, ct)
                if (r.nextInt(3) == 0) b.rbox(x + 0.1f, y + 0.9f * sc, z, 0.35f * sc, 0.35f * sc, 0.7f * sc, rot + 0.5f, c, ct)
                addCircle(x, z, 0.75f * sc)
            }
            1 -> {
                val c = cb(0.40f * v, 0.27f * v, 0.15f * v)
                b.cylinder(x, y - 0.05f, z, 0.45f * sc, 1.05f * sc, 8, c, cb(0.30f, 0.21f, 0.12f))
                addCircle(x, z, 0.5f * sc)
            }
            2 -> {
                val pole = cb(0.18f, 0.18f, 0.2f)
                b.box(x - 0.08f, y - 0.1f, z - 0.08f, x + 0.08f, y + 4.0f, z + 0.08f, pole, pole, true)
                val lamp = cb(1.0f, 0.88f, 0.55f)
                b.box(x - 0.25f, y + 4.0f, z - 0.25f, x + 0.25f, y + 4.45f, z + 0.25f, lamp, lamp, true)
                b.box(x - 0.32f, y + 4.45f, z - 0.32f, x + 0.32f, y + 4.55f, z + 0.32f, pole, pole, true)
                addCircle(x, z, 0.22f)
            }
            3 -> {
                val c = cb(0.82f * v, 0.68f * v, 0.28f * v)
                b.cylinder(x, y - 0.05f, z, 1.5f * sc, 0.9f * sc, 8, c, c)
                b.cone(x, y + 0.85f * sc, z, 1.55f * sc, 1.7f * sc, 8, rot, cb(0.88f * v, 0.74f * v, 0.32f * v))
                addCircle(x, z, 1.5f * sc)
            }
            4 -> {
                val st = cb(0.55f, 0.54f, 0.52f)
                b.cylinder(x, y - 0.1f, z, 1.0f, 1.0f, 8, st, cb(0.12f, 0.25f, 0.35f))
                val wd = cb(0.38f, 0.26f, 0.15f)
                b.box(x - 1.0f, y, z - 0.08f, x - 0.85f, y + 2.3f, z + 0.08f, wd, wd, true)
                b.box(x + 0.85f, y, z - 0.08f, x + 1.0f, y + 2.3f, z + 0.08f, wd, wd, true)
                b.cone(x, y + 2.25f, z, 1.5f, 0.9f, 4, 0.78f, cb(0.62f, 0.2f, 0.15f))
                addCircle(x, z, 1.15f)
            }
            else -> {
                val g1 = cb(0.20f * v, 0.42f * v, 0.14f * v)
                val g2 = cb(0.13f * v, 0.30f * v, 0.10f * v)
                b.blob(x, y + 0.15f * sc, z, 0.95f * sc, 0.8f * sc, 0.95f * sc, 5, rot, 1f, g1, g2)
            }
        }
    }

    private fun addBuilding(b: VegBuilder, ib: VegBuilder, bd: BData, wall: Float, roofC: Float, r: Random) {
        val x = bd.x
        val z = bd.z
        val hw = bd.hw
        val hd = bd.hd
        val hh = bd.hh
        val y0 = bd.y0
        val roof = bd.roof
        val windows = bd.win != 0
        val x0 = x - hw
        val x1 = x + hw
        val z0 = z - hd
        val z1 = z + hd
        val y1 = y0 + hh
        val hollow = bd.door in 1..4
        run {
            val kind = if (roof == 0) 0 else if (roof == 3) 3 else if (roof == 1) 1 else 2
            val ov = if (kind == 1) 0.45f else if (kind == 2) 0.35f else 0f
            val span = if (hw >= hd) hd else hw
            val rh = if (kind == 1) span * 0.85f + 0.6f else maxOf(hw, hd) * 0.9f + 1.2f
            val rf = floatArrayOf(x0 - ov, z0 - ov, x1 + ov, z1 + ov, y1, kind.toFloat(), rh, x, z, hw + ov, hd + ov)
            val ci0 = ((x0 - ov - X0) / 32f).toInt().coerceAtLeast(0)
            val ci1 = ((x1 + ov - X0) / 32f).toInt().coerceAtLeast(0)
            val cj0 = ((z0 - ov - Z0) / 32f).toInt().coerceAtLeast(0)
            val cj1 = ((z1 + ov - Z0) / 32f).toInt().coerceAtLeast(0)
            for (cj in cj0..cj1) for (ci in ci0..ci1) roofGrid.getOrPut(cj * 1024 + ci) { ArrayList() }.add(rf)
        }
        val base = if (hollow) bd.f else y0 + 0.6f
        val mask = bd.mask
        val dS = hollow && (mask and 1) != 0   // door in the +z wall
        val dN = hollow && (mask and 2) != 0   // -z wall
        val dE = hollow && (mask and 4) != 0   // +x wall
        val dW = hollow && (mask and 8) != 0   // -x wall
        if (hollow) {
            val t = 0.5f
            val dTop = base + 2.55f
            fun piece(ax: Float, az: Float, bx: Float, bz: Float, ya: Float, yb: Float, solid: Boolean) {
                if (bx - ax < 0.01f || bz - az < 0.01f || yb - ya < 0.01f) return
                b.box(ax, ya, az, bx, yb, bz, wall, wall, false)
                if (solid) blocks.add(Block((ax + bx) / 2f, (az + bz) / 2f, (bx - ax) / 2f, (bz - az) / 2f, yb - ya, ya))
            }
            val dwX = doorHalf(hw)
            val dwZ = doorHalf(hd)
            // north (-z) and south (+z) walls run along x
            for (side in 0..1) {
                val zz0 = if (side == 0) z0 else z1 - t
                val zz1 = if (side == 0) z0 + t else z1
                val hasDoor = if (side == 0) dN else dS
                if (hasDoor) {
                    piece(x0, zz0, x - dwX, zz1, y0, y1, true)
                    piece(x + dwX, zz0, x1, zz1, y0, y1, true)
                    piece(x - dwX, zz0, x + dwX, zz1, dTop, y1, false)
                } else piece(x0, zz0, x1, zz1, y0, y1, true)
            }
            // west (-x) and east (+x) walls run along z
            for (side in 0..1) {
                val xx0 = if (side == 0) x0 else x1 - t
                val xx1 = if (side == 0) x0 + t else x1
                val hasDoor = if (side == 0) dW else dE
                if (hasDoor) {
                    piece(xx0, z0 + t, xx1, z - dwZ, y0, y1, true)
                    piece(xx0, z + dwZ, xx1, z1 - t, y0, y1, true)
                    piece(xx0, z - dwZ, xx1, z + dwZ, dTop, y1, false)
                } else piece(xx0, z0 + t, xx1, z1 - t, y0, y1, true)
            }
            buildInterior(ib, bd, r)
        }
        when (roof) {
            0 -> {
                if (hollow) b.box(x0, y1, z0, x1, y1 + 0.4f, z1, wall, roofC, true)
                else b.box(x0, y0, z0, x1, y1 + 0.4f, z1, wall, roofC, true)
                b.box(x0, y1 + 0.4f, z0, x1, y1 + 0.9f, z0 + 0.3f, wall, wall, true)
                b.box(x0, y1 + 0.4f, z1 - 0.3f, x1, y1 + 0.9f, z1, wall, wall, true)
            }
            3 -> b.box(x0, y0, z0, x1, y1, z1, wall, wall, true)
            1 -> {
                if (!hollow) b.box(x0, y0, z0, x1, y1, z1, wall, wall, false)
                val ov = 0.45f
                val alongX = hw >= hd
                val half = if (alongX) hw else hd      // half length of the ridge
                val span = if (alongX) hd else hw      // half width
                val rh = span * 0.85f + 0.6f
                fun p(u: Float, yy: Float, w: Float): FloatArray =
                    if (alongX) floatArrayOf(x + u, yy, z + w) else floatArrayOf(x + w, yy, z - u)
                val ul = -half - ov
                val ur = half + ov
                val ws = span + ov
                val a = p(ul, y1, ws); val bb = p(ur, y1, ws); val c = p(ur, y1 + rh, 0f); val d = p(ul, y1 + rh, 0f)
                b.quad(a[0], a[1], a[2], bb[0], bb[1], bb[2], c[0], c[1], c[2], d[0], d[1], d[2], roofC)
                val a2 = p(ur, y1, -ws); val b2 = p(ul, y1, -ws); val c2 = p(ul, y1 + rh, 0f); val d2 = p(ur, y1 + rh, 0f)
                b.quad(a2[0], a2[1], a2[2], b2[0], b2[1], b2[2], c2[0], c2[1], c2[2], d2[0], d2[1], d2[2], roofC)
                val g1 = p(half, y1, span); val g2 = p(half, y1, -span); val g3 = p(half, y1 + rh, 0f)
                b.tri(g1[0], g1[1], g1[2], g2[0], g2[1], g2[2], g3[0], g3[1], g3[2], wall)
                val h1 = p(-half, y1, -span); val h2 = p(-half, y1, span); val h3 = p(-half, y1 + rh, 0f)
                b.tri(h1[0], h1[1], h1[2], h2[0], h2[1], h2[2], h3[0], h3[1], h3[2], wall)
                if (r.nextInt(100) < 55) {
                    val sgn = if (r.nextBoolean()) 1f else -1f
                    val cp = p(sgn * half * 0.55f, y1, 0f)
                    val brick = cb(0.50f, 0.26f, 0.20f)
                    b.box(cp[0] - 0.4f, y1 + 0.2f, cp[2] - 0.4f, cp[0] + 0.4f, y1 + rh + 1.4f, cp[2] + 0.4f, brick, cb(0.2f, 0.2f, 0.2f), true)
                }
            }
            else -> {
                if (!hollow) b.box(x0, y0, z0, x1, y1, z1, wall, wall, false)
                val ov = 0.35f
                val rh = maxOf(hw, hd) * 0.9f + 1.2f
                val ax = x0 - ov; val bx = x1 + ov; val az = z0 - ov; val bz = z1 + ov
                b.tri(ax, y1, bz, bx, y1, bz, x, y1 + rh, z, roofC)
                b.tri(bx, y1, bz, bx, y1, az, x, y1 + rh, z, roofC)
                b.tri(bx, y1, az, ax, y1, az, x, y1 + rh, z, roofC)
                b.tri(ax, y1, az, ax, y1, bz, x, y1 + rh, z, roofC)
            }
        }
        if (windows && hh > 3.3f) {
            val glass = cb(0.10f, 0.14f, 0.20f)
            val door2 = cb(0.22f, 0.14f, 0.08f)
            val off = 0.04f
            var u = x0 + 1.8f
            while (u < x1 - 1.0f) {
                var yy = base + 1.5f
                while (yy + 1.3f < y1 - 0.4f) {
                    if (!(dS && abs(u + 0.5f - x) < 2.4f)) b.quad(u, yy, z1 + off, u + 1.0f, yy, z1 + off, u + 1.0f, yy + 1.2f, z1 + off, u, yy + 1.2f, z1 + off, glass)
                    if (!(dN && abs(u + 0.5f - x) < 2.4f)) b.quad(u + 1.0f, yy, z0 - off, u, yy, z0 - off, u, yy + 1.2f, z0 - off, u + 1.0f, yy + 1.2f, z0 - off, glass)
                    yy += 3.3f
                }
                u += 3.2f
            }
            var w = z0 + 1.8f
            while (w < z1 - 1.0f) {
                var yy = base + 1.5f
                while (yy + 1.3f < y1 - 0.4f) {
                    if (!(dE && abs(w + 0.5f - z) < 2.4f)) b.quad(x1 + off, yy, w + 1.0f, x1 + off, yy, w, x1 + off, yy + 1.2f, w, x1 + off, yy + 1.2f, w + 1.0f, glass)
                    if (!(dW && abs(w + 0.5f - z) < 2.4f)) b.quad(x0 - off, yy, w, x0 - off, yy, w + 1.0f, x0 - off, yy + 1.2f, w + 1.0f, x0 - off, yy + 1.2f, w, glass)
                    yy += 3.3f
                }
                w += 3.2f
            }
            if (!hollow) b.quad(x - 0.7f, base, z1 + off * 1.5f, x + 0.7f, base, z1 + off * 1.5f, x + 0.7f, base + 2.2f, z1 + off * 1.5f, x - 0.7f, base + 2.2f, z1 + off * 1.5f, door2)
        }
    }

    private fun doorHalf(len: Float): Float = minOf(1.3f, len * 0.36f)

    /** rooms, stairs, upper floors, furniture and loot spots of a walkable building */
    private fun buildInterior(ib: VegBuilder, bd: BData, r: Random) {
        val t = 0.5f
        val ix0 = bd.x - bd.hw + t
        val ix1 = bd.x + bd.hw - t
        val iz0 = bd.z - bd.hd + t
        val iz1 = bd.z + bd.hd - t
        val alongX = (ix1 - ix0) >= (iz1 - iz0)
        val a0 = if (alongX) ix0 else iz0
        val a1 = if (alongX) ix1 else iz1
        val b0 = if (alongX) iz0 else ix0
        val b1 = if (alongX) iz1 else ix1
        val F = bd.f
        val y1 = bd.y0 + bd.hh
        val mask = bd.mask
        val rs = Random((bd.x * 131f + bd.z * 17f).toLong() xor 0x5DEECE66DL)

        fun abBox(aLo: Float, aHi: Float, bLo: Float, bHi: Float, yLo: Float, yHi: Float, wc: Float, tc: Float, top: Boolean) {
            if (aHi - aLo < 0.01f || bHi - bLo < 0.01f || yHi - yLo < 0.005f) return
            if (alongX) ib.box(aLo, yLo, bLo, aHi, yHi, bHi, wc, tc, top) else ib.box(bLo, yLo, aLo, bHi, yHi, aHi, wc, tc, top)
        }
        fun abBlock(aLo: Float, aHi: Float, bLo: Float, bHi: Float, yLo: Float, hgt: Float) {
            if (aHi - aLo < 0.01f || bHi - bLo < 0.01f) return
            if (alongX) blocks.add(Block((aLo + aHi) / 2f, (bLo + bHi) / 2f, (aHi - aLo) / 2f, (bHi - bLo) / 2f, hgt, yLo))
            else blocks.add(Block((bLo + bHi) / 2f, (aLo + aHi) / 2f, (bHi - bLo) / 2f, (aHi - aLo) / 2f, hgt, yLo))
        }
        fun abFlat(aLo: Float, aHi: Float, bLo: Float, bHi: Float, y: Float, c: Float, up: Boolean) {
            val xl = if (alongX) aLo else bLo
            val xh = if (alongX) aHi else bHi
            val zl = if (alongX) bLo else aLo
            val zh = if (alongX) bHi else aHi
            if (up) ib.quad(xl, y, zh, xh, y, zh, xh, y, zl, xl, y, zl, c)
            else ib.quad(xl, y, zl, xh, y, zl, xh, y, zh, xl, y, zh, c)
        }
        fun wX(a: Float, bb: Float) = if (alongX) a else bb
        fun wZ(a: Float, bb: Float) = if (alongX) bb else a

        // doors in (A,B) space: clear zones
        val clears = ArrayList<FloatArray>()    // aLo, aHi, bLo, bHi
        var endDoorB = Float.NaN
        var sideDoorA = Float.NaN
        var sideDoorAtB0 = false
        var sideDoorAtB1 = false
        for (d in 1..4) {
            if ((mask and (1 shl (d - 1))) == 0) continue
            val onZWall = d <= 2
            val aIsAlongWall = (alongX && onZWall) || (!alongX && !onZWall)   // wall runs along A => side wall
            val centre = if (onZWall) bd.x else bd.z
            val dw = if (onZWall) doorHalf(bd.hw) else doorHalf(bd.hd)
            val plus = (d == 1 || d == 3)
            if (aIsAlongWall) {
                sideDoorA = centre
                if (plus) sideDoorAtB1 = true else sideDoorAtB0 = true
                clears.add(floatArrayOf(centre - dw - 0.4f, centre + dw + 0.4f, if (plus) b1 - 2.6f else b0, if (plus) b1 else b0 + 2.6f))
            } else {
                endDoorB = centre
                clears.add(floatArrayOf(if (plus) a1 - 2.6f else a0, if (plus) a1 else a0 + 2.6f, centre - dw - 0.4f, centre + dw + 0.4f))
            }
        }

        // storeys
        var nf = 1
        val avail = y1 - F
        if (bd.hh >= 6.4f && a1 - a0 >= 8.5f && b1 - b0 >= 3.4f) nf = floor(avail / 3.4f).toInt().coerceIn(1, 3)
        var fh = avail / nf
        var rl = fh / 0.58f
        if (nf >= 2 && rl > (a1 - a0) - 2.5f) nf = 1
        if (nf == 3 && (b1 - b0 < 4.4f || (a1 - a0) < rl + 4f)) nf = 2
        fh = avail / nf
        rl = fh / 0.58f
        val rampW = 1.6f

        val wood = cb(0.46f, 0.33f, 0.20f)
        val woodDark = cb(0.34f, 0.23f, 0.13f)
        val plaster = cb(0.84f, 0.80f, 0.72f)
        val ceilC = cb(0.62f, 0.58f, 0.50f)
        val stepC = cb(0.52f, 0.40f, 0.26f)

        val iv = Interior(ix0, ix1, iz0, iz1, F, nf, fh)
        val aMinP: Float
        var aMaxP = a1 - 2.0f
        var aMinPart = a0 + 2.0f
        if (nf >= 2) {
            // stairs: ramp 1 from the near end, ramp 2 (3 storeys) from the far end, on opposite sides
            val r1b0 = if (sideDoorAtB0) b1 - rampW else b0
            val r2b0 = if (sideDoorAtB0) b0 else b1 - rampW
            for (k in 1 until nf) {
                val up = (k == 1)
                val aLo = if (up) a0 + 0.05f else a1 - 0.05f - rl
                val aHi = aLo + rl
                val bLo = if (up) r1b0 else r2b0
                val bHi = bLo + rampW
                val ramp = if (alongX) Ramp(aLo, aHi, bLo, bHi, true, up, k) else Ramp(bLo, bHi, aLo, aHi, false, up, k)
                iv.ramps.add(ramp)
                clears.add(floatArrayOf(aLo - 1.5f, aHi + 1.5f, bLo - 0.4f, bHi + 0.4f))
                // visible steps
                val n = rampSteps(fh)
                val dl = rl / n
                val baseY = F + (k - 1) * fh
                for (i in 0 until n) {
                    val sa = if (up) aLo + i * dl else aHi - (i + 1) * dl
                    abBox(sa, sa + dl, bLo, bHi, baseY, baseY + (i + 1) * fh / n, stepC, woodDark, true)
                }
                if (up) aMinPart = aHi + 0.7f else aMaxP = aLo - 0.7f
            }
            if (nf == 2) aMaxP = a1 - 2.0f
        }
        aMinP = aMinPart

        // floor of the ground storey, ceiling under the roof
        abFlat(a0, a1, b0, b1, F + 0.05f, wood, true)
        abFlat(a0, a1, b0, b1, y1, ceilC, false)

        // upper storeys: slabs with the stair holes cut out
        for (k in 1 until nf) {
            val y = F + k * fh
            val hole = iv.ramps.first { it.level == k }
            val hA0 = if (alongX) hole.x0 else hole.z0
            val hA1 = if (alongX) hole.x1 else hole.z1
            val hB0 = if (alongX) hole.z0 else hole.x0
            val hB1 = if (alongX) hole.z1 else hole.x1
            val holeAtLow = hB0 <= b0 + 0.5f
            val bLow = if (holeAtLow) hB1 else b0
            val bHigh = if (holeAtLow) b1 else hB0
            val pieces = ArrayList<FloatArray>()
            pieces.add(floatArrayOf(a0, a1, bLow, bHigh))
            val sb0 = if (holeAtLow) b0 else hB0
            val sb1 = if (holeAtLow) hB1 else b1
            pieces.add(floatArrayOf(a0, hA0, sb0, sb1))
            pieces.add(floatArrayOf(hA1, a1, sb0, sb1))
            for (p in pieces) {
                if (p[1] - p[0] < 0.02f || p[3] - p[2] < 0.02f) continue
                abBox(p[0], p[1], p[2], p[3], y - 0.25f, y, plaster, wood, true)
                abFlat(p[0], p[1], p[2], p[3], y - 0.25f, ceilC, false)
            }
        }

        // partitions
        val roomsA = ArrayList<Float>()   // partition positions along A
        run {
            val span = aMaxP - aMinP
            val len = a1 - a0
            val rooms = Math.round(len / 5.0f).coerceIn(1, 5)
            if (nf == 1) {
                val n = rooms - 1
                for (i in 1..n) {
                    val p = a0 + len * i / rooms
                    if (!sideDoorA.isNaN() && abs(p - sideDoorA) < 2.2f) continue
                    roomsA.add(p)
                }
            } else if (span > 3.5f) {
                val n = Math.round(span / 5.0f).coerceIn(1, 4) - 1 + 1
                for (i in 1..n) {
                    val p = aMinP + span * i / (n + 1)
                    if (!sideDoorA.isNaN() && abs(p - sideDoorA) < 2.2f) continue
                    roomsA.add(p)
                }
            }
        }
        var pb = Float.NaN
        if (b1 - b0 >= 9f && a1 - a0 >= 10f) {
            var c = b0 + (b1 - b0) * 0.32f
            if (!endDoorB.isNaN() && abs(c - endDoorB) < 2.4f) c = b0 + (b1 - b0) * 0.68f
            if (endDoorB.isNaN() || abs(c - endDoorB) >= 2.4f) pb = c
        }
        val pw = 0.15f
        val bBounds = ArrayList<Float>()
        bBounds.add(b0)
        if (!pb.isNaN()) { bBounds.add(pb - pw); bBounds.add(pb + pw) }
        bBounds.add(b1)
        val aBounds = ArrayList<Float>()
        aBounds.add(a0)
        for (p in roomsA) { aBounds.add(p - pw); aBounds.add(p + pw) }
        aBounds.add(a1)

        for (k in 0 until nf) {
            val ly = if (k == 0) F else F + k * fh
            val ph = if (k < nf - 1) fh - 0.3f else y1 - ly
            // doorway helper: wall piece with a gap in the middle
            fun wallSeg(isAPart: Boolean, pos: Float, lo: Float, hi: Float) {
                val len = hi - lo
                if (len < 0.3f) return
                fun seg(l: Float, h2: Float, solid: Boolean, yl: Float, yh: Float) {
                    if (h2 - l < 0.01f) return
                    if (isAPart) abBox(pos - pw, pos + pw, l, h2, yl, yh, plaster, plaster, false)
                    else abBox(l, h2, pos - pw, pos + pw, yl, yh, plaster, plaster, false)
                    if (solid) {
                        if (isAPart) abBlock(pos - pw, pos + pw, l, h2, yl, yh - yl) else abBlock(l, h2, pos - pw, pos + pw, yl, yh - yl)
                    }
                }
                if (len >= 2.7f) {
                    val c = (lo + hi) / 2f + (rs.nextFloat() - 0.5f) * (len - 2.7f) * 0.5f
                    seg(lo, c - 0.85f, true, ly, ly + ph)
                    seg(c + 0.85f, hi, true, ly, ly + ph)
                    seg(c - 0.85f, c + 0.85f, false, ly + 2.4f, ly + ph)
                    if (k == 0) {
                        if (isAPart) clears.add(floatArrayOf(pos - 1.2f, pos + 1.2f, c - 1.2f, c + 1.2f))
                        else clears.add(floatArrayOf(c - 1.2f, c + 1.2f, pos - 1.2f, pos + 1.2f))
                    }
                } else seg(lo, hi, true, ly, ly + ph)
            }
            for (p in roomsA) {
                var i = 0
                while (i < bBounds.size) { wallSeg(true, p, bBounds[i], bBounds[i + 1]); i += 2 }
            }
            if (!pb.isNaN()) {
                var i = 0
                while (i < aBounds.size) { wallSeg(false, pb, aBounds[i], aBounds[i + 1]); i += 2 }
            }
        }

        // rooms: furniture and loot spots per storey
        for (k in 0 until nf) {
            val ly = F + k * fh
            val fy = if (k == 0) F + 0.05f else ly
            var ai = 0
            while (ai < aBounds.size) {
                val ra0 = aBounds[ai] + (if (ai > 0) pw + 0.05f else 0.05f)
                val ra1 = aBounds[ai + 1] - (if (ai + 1 < aBounds.size - 1) pw + 0.05f else 0.05f)
                ai += 2
                var bi = 0
                while (bi < bBounds.size) {
                    val rb0 = bBounds[bi] + (if (bi > 0) pw + 0.05f else 0.05f)
                    val rb1 = bBounds[bi + 1] - (if (bi + 1 < bBounds.size - 1) pw + 0.05f else 0.05f)
                    bi += 2
                    if (ra1 - ra0 < 2.2f || rb1 - rb0 < 2.2f) continue
                    val placed = ArrayList<FloatArray>()
                    fun free(aLo: Float, aHi: Float, bLo: Float, bHi: Float, m: Float): Boolean {
                        if (aLo < ra0 || aHi > ra1 || bLo < rb0 || bHi > rb1) return false
                        for (c in clears) if (aLo < c[1] && aHi > c[0] && bLo < c[3] && bHi > c[2]) return false
                        for (f in placed) if (aLo - m < f[1] && aHi + m > f[0] && bLo - m < f[3] && bHi + m > f[2]) return false
                        return true
                    }
                    // rug
                    if ((ra1 - ra0) > 3.4f && (rb1 - rb0) > 3.4f && rs.nextInt(100) < 55) {
                        val cx = (ra0 + ra1) / 2f
                        val cy = (rb0 + rb1) / 2f
                        val rc = when (rs.nextInt(3)) { 0 -> cb(0.60f, 0.18f, 0.16f); 1 -> cb(0.16f, 0.30f, 0.50f); else -> cb(0.28f, 0.45f, 0.28f) }
                        abFlat(cx - 1.2f, cx + 1.2f, cy - 0.8f, cy + 0.8f, fy + 0.02f, rc, true)
                    }
                    val area = (ra1 - ra0) * (rb1 - rb0)
                    val want = minOf(6, (area / 5.5f).toInt())
                    var made = 0
                    var tries = 0
                    while (made < want && tries < 24) {
                        tries++
                        val ty = rs.nextInt(100)
                        var sa: Float
                        var sb: Float
                        var hgt: Float
                        var c1: Float
                        var c2: Float
                        var solid = true
                        when {
                            ty < 14 -> { sa = 2.0f; sb = 1.1f; hgt = 0.5f; c1 = cb(0.55f, 0.25f, 0.22f); c2 = cb(0.85f, 0.82f, 0.78f) }
                            ty < 30 -> { sa = 1.5f; sb = 0.9f; hgt = 0.8f; c1 = wood; c2 = cb(0.58f, 0.42f, 0.26f) }
                            ty < 40 -> { sa = 0.5f; sb = 0.5f; hgt = 0.5f; c1 = woodDark; c2 = wood; solid = false }
                            ty < 52 -> { sa = 1.2f; sb = 0.6f; hgt = 2.0f; c1 = woodDark; c2 = wood }
                            ty < 64 -> { sa = 1.6f; sb = 0.4f; hgt = 1.9f; c1 = cb(0.40f, 0.30f, 0.20f); c2 = wood }
                            ty < 76 -> { sa = 2.0f; sb = 0.9f; hgt = 0.85f; c1 = cb(0.25f, 0.32f, 0.45f); c2 = cb(0.30f, 0.38f, 0.52f) }
                            ty < 88 -> { sa = 0.9f; sb = 0.9f; hgt = 0.9f; c1 = cb(0.55f, 0.40f, 0.22f); c2 = cb(0.62f, 0.46f, 0.26f) }
                            else -> { sa = 2.2f; sb = 0.65f; hgt = 0.95f; c1 = cb(0.78f, 0.76f, 0.72f); c2 = cb(0.35f, 0.33f, 0.31f) }
                        }
                        if (rs.nextBoolean()) { val tmp = sa; sa = sb; sb = tmp }
                        val mode = rs.nextInt(4)
                        var aLo: Float
                        var bLo: Float
                        when (mode) {
                            0 -> { aLo = ra0 + 0.1f; bLo = rb0 + 0.1f + rs.nextFloat() * maxOf(0f, rb1 - rb0 - sb - 0.2f) }
                            1 -> { aLo = ra1 - sa - 0.1f; bLo = rb0 + 0.1f + rs.nextFloat() * maxOf(0f, rb1 - rb0 - sb - 0.2f) }
                            2 -> { bLo = rb0 + 0.1f; aLo = ra0 + 0.1f + rs.nextFloat() * maxOf(0f, ra1 - ra0 - sa - 0.2f) }
                            else -> { bLo = rb1 - sb - 0.1f; aLo = ra0 + 0.1f + rs.nextFloat() * maxOf(0f, ra1 - ra0 - sa - 0.2f) }
                        }
                        if (!free(aLo, aLo + sa, bLo, bLo + sb, 0.5f)) continue
                        placed.add(floatArrayOf(aLo, aLo + sa, bLo, bLo + sb))
                        made++
                        abBox(aLo, aLo + sa, bLo, bLo + sb, fy, fy + hgt, c1, c2, true)
                        if (ty < 14) abBox(aLo + 0.05f, aLo + 0.45f, bLo + 0.1f, bLo + sb - 0.1f, fy + hgt, fy + hgt + 0.12f, cb(0.92f, 0.92f, 0.9f), cb(0.95f, 0.95f, 0.93f), true)
                        if (ty in 52..63) abBox(aLo + 0.1f, aLo + sa - 0.1f, bLo + 0.05f, bLo + sb - 0.05f, fy + 0.5f, fy + 0.8f, cb(0.7f, 0.3f, 0.25f), cb(0.7f, 0.3f, 0.25f), true)
                        if (solid) abBlock(aLo, aLo + sa, bLo, bLo + sb, fy, hgt)
                    }
                    // loot spots on the floor
                    val wantL = if (area > 40f) 2 else 1
                    var gotL = 0
                    var lt = 0
                    while (gotL < wantL && lt < 14) {
                        lt++
                        val pa = ra0 + 0.8f + rs.nextFloat() * (ra1 - ra0 - 1.6f)
                        val pbb = rb0 + 0.8f + rs.nextFloat() * (rb1 - rb0 - 1.6f)
                        if (!free(pa - 0.5f, pa + 0.5f, pbb - 0.5f, pbb + 0.5f, 0.2f)) continue
                        lootSpots.add(floatArrayOf(wX(pa, pbb), fy, wZ(pa, pbb)))
                        gotL++
                    }
                }
            }
        }
        if (nf >= 2) registerInterior(iv)
    }

    // ------------------------------------------------------------------
    // drawing
    // ------------------------------------------------------------------
    fun render(batch: ModelBatch, cam: Camera, env: Environment) {
        val px = cam.position.x
        val pz = cam.position.z
        val hd2 = (hiDist + 46f) * (hiDist + 46f)
        val ld2 = (loDist + 46f) * (loDist + 46f)
        for (n in hiChunks.indices) {
            val c = hiChunks[n] ?: continue
            val dx = c.cx - px
            val dz = c.cz - pz
            val d2 = dx * dx + dz * dz
            if (d2 > ld2) continue
            if (!cam.frustum.boundsInFrustum(c.box)) continue
            if (d2 < hd2) {
                batch.render(c.inst, env)
                if (d2 < (detDist + 46f) * (detDist + 46f) && c.mesh != null) {
                    var di = c.detail
                    if (di == null) {
                        val m = Model()
                        val nd = Node()
                        nd.id = "d"
                        nd.parts.add(NodePart(MeshPart("p", c.mesh, 0, c.count, GL20.GL_TRIANGLES), detailMat))
                        m.nodes.add(nd)
                        m.meshes.add(c.mesh)
                        m.materials.add(detailMat)
                        di = ModelInstance(m)
                        c.detail = di
                    }
                    batch.render(di)
                }
            } else batch.render(loChunks[n]!!.inst, env)
        }
        val vd2 = (vegDist + 46f) * (vegDist + 46f)
        for (c in vegChunks) {
            val dx = c.cx - px
            val dz = c.cz - pz
            if (dx * dx + dz * dz > vd2) continue
            if (!cam.frustum.boundsInFrustum(c.box)) continue
            batch.render(c.inst, env)
        }
        val id2 = (intDist + 46f) * (intDist + 46f)
        for (c in intChunks) {
            val dx = c.cx - px
            val dz = c.cz - pz
            if (dx * dx + dz * dz > id2) continue
            if (!cam.frustum.boundsInFrustum(c.box)) continue
            batch.render(c.inst, env)
        }
        water?.let { batch.render(it, env) }
    }

    fun dispose() {
        for (m in models) m.dispose()
        models.clear()
        if (::colorTex.isInitialized) colorTex.dispose()
        if (::detailTex.isInitialized) detailTex.dispose()
    }
}
