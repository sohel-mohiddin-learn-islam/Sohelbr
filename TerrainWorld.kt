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
        for (j in 0 until rh) for (i in 0 until rw) regionPix[j * rw + i] = (rp.getPixel(i, j) ushr 24) and 0xff
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
        val dp = Pixmap(Gdx.files.internal("map_detail.png"))
        detailTex = Texture(dp, dp.format, true)
        dp.dispose()
        detailTex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        detailTex.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)
        val ta = TextureAttribute.createDiffuse(detailTex)
        ta.scaleU = MW / 3f
        ta.scaleV = MH / 3f
        detailMat = Material(ta, BlendingAttribute(GL20.GL_DST_COLOR, GL20.GL_SRC_COLOR),
            DepthTestAttribute(GL20.GL_LEQUAL, false))

        buildTerrain()
        buildObjects()
        buildColliderGrid()
        buildWater()
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
        fun bld(x: Float, z: Float): VegBuilder {
            val i = ((x - X0) / VEG).toInt().coerceIn(0, vx - 1)
            val j = ((z - Z0) / VEG).toInt().coerceIn(0, vz - 1)
            var b = builders[j * vx + i]
            if (b == null) {
                b = VegBuilder()
                builders[j * vx + i] = b
            }
            return b
        }
        val rnd = Random(4242)
        val inp = DataInputStream(BufferedInputStream(Gdx.files.internal("map_obj.bin").read(), 1 shl 16))
        val nTrees = inp.readInt()
        for (t in 0 until nTrees) {
            val x = inp.readFloat()
            val z = inp.readFloat()
            val type = inp.readByte().toInt()
            val s = inp.readByte().toInt() / 50f
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
            addRock(bld(x, z), x, h(x, z), z, sz, cls, rnd)
            addCircle(x, z, sz * 0.85f)
        }
        val nB = inp.readInt()
        for (t in 0 until nB) {
            val x = inp.readFloat()
            val z = inp.readFloat()
            val hw = inp.readFloat()
            val hd = inp.readFloat()
            val hh = inp.readFloat()
            val y0 = inp.readFloat()
            val wr = inp.readUnsignedByte() / 255f
            val wg = inp.readUnsignedByte() / 255f
            val wb = inp.readUnsignedByte() / 255f
            val rr = inp.readUnsignedByte() / 255f
            val rg = inp.readUnsignedByte() / 255f
            val rb = inp.readUnsignedByte() / 255f
            val roof = inp.readByte().toInt()
            val win = inp.readByte().toInt()
            val door = inp.readByte().toInt()
            if (door !in 1..4) blocks.add(Block(x, z, hw, hd, hh, y0))
            else houses.add(floatArrayOf(x, z, hw, hd, y0 + 0.6f))
            addBuilding(bld(x, z), x, z, hw, hd, hh, y0, cb(wr, wg, wb), cb(rr, rg, rb), roof, win != 0, door, rnd)
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
            addProp(bld(x, z), x, h(x, z), z, type, rot, sc, rnd)
        }
        pin.close()

        val mat = Material(
            ColorAttribute.createDiffuse(0.72f, 0.72f, 0.72f, 1f),
            IntAttribute.createCullFace(0)
        )
        for (j in 0 until vz) for (i in 0 until vx) {
            val b = builders[j * vx + i] ?: continue
            b.flush()
            val box = BoundingBox()
            var first = true
            for (piece in b.pieces) {
                val nv = piece.size / 7
                val mesh = Mesh(true, nv, 0, VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked())
                mesh.setVertices(piece)
                val inst = ModelInstance(makeModel(mesh, nv, mat))
                val cx = X0 + (i + 0.5f) * VEG
                val cz = Z0 + (j + 0.5f) * VEG
                if (first) {
                    box.set(Vector3(X0 + i * VEG - 3f, -10f, Z0 + j * VEG - 3f), Vector3(X0 + (i + 1) * VEG + 3f, 90f, Z0 + (j + 1) * VEG + 3f))
                    first = false
                }
                vegChunks.add(MapChunk(inst, box, cx, cz))
            }
        }
    }

    private fun addTree(b: VegBuilder, x: Float, y: Float, z: Float, type: Int, s: Float, r: Random) {
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

    private fun addBuilding(b: VegBuilder, x: Float, z: Float, hw: Float, hd: Float, hh: Float, y0: Float,
                            wall: Float, roofC: Float, roof: Int, windows: Boolean, door: Int, r: Random) {
        val x0 = x - hw
        val x1 = x + hw
        val z0 = z - hd
        val z1 = z + hd
        val y1 = y0 + hh
        val base = y0 + 0.6f
        val hollow = door in 1..4
        if (hollow) {
            val t = 0.5f
            val dw = 1.15f
            val dTop = base + 2.4f
            fun piece(ax: Float, az: Float, bx: Float, bz: Float, ya: Float, yb: Float, solid: Boolean) {
                if (bx - ax < 0.01f || bz - az < 0.01f || yb - ya < 0.01f) return
                b.box(ax, ya, az, bx, yb, bz, wall, wall, false)
                if (solid) blocks.add(Block((ax + bx) / 2f, (az + bz) / 2f, (bx - ax) / 2f, (bz - az) / 2f, yb - ya, ya))
            }
            // north (-z) and south (+z) walls run along x
            for (side in 0..1) {
                val zz0 = if (side == 0) z0 else z1 - t
                val zz1 = if (side == 0) z0 + t else z1
                val hasDoor = (side == 0 && door == 2) || (side == 1 && door == 1)
                if (hasDoor) {
                    piece(x0, zz0, x - dw, zz1, y0, y1, true)
                    piece(x + dw, zz0, x1, zz1, y0, y1, true)
                    piece(x - dw, zz0, x + dw, zz1, dTop, y1, false)
                } else piece(x0, zz0, x1, zz1, y0, y1, true)
            }
            // west (-x) and east (+x) walls run along z
            for (side in 0..1) {
                val xx0 = if (side == 0) x0 else x1 - t
                val xx1 = if (side == 0) x0 + t else x1
                val hasDoor = (side == 0 && door == 4) || (side == 1 && door == 3)
                if (hasDoor) {
                    piece(xx0, z0 + t, xx1, z - dw, y0, y1, true)
                    piece(xx0, z + dw, xx1, z1 - t, y0, y1, true)
                    piece(xx0, z - dw, xx1, z + dw, dTop, y1, false)
                } else piece(xx0, z0 + t, xx1, z1 - t, y0, y1, true)
            }
            // wooden floor and a stone step in front of the door
            val fl = cb(0.42f, 0.30f, 0.18f)
            val fy = base + 0.07f
            b.quad(x0 + t, fy, z1 - t, x1 - t, fy, z1 - t, x1 - t, fy, z0 + t, x0 + t, fy, z0 + t, fl)
            val step = cb(0.50f, 0.49f, 0.47f)
            when (door) {
                1 -> b.box(x - 1.5f, base - 0.1f, z1, x + 1.5f, base + 0.14f, z1 + 0.9f, step, step, true)
                2 -> b.box(x - 1.5f, base - 0.1f, z0 - 0.9f, x + 1.5f, base + 0.14f, z0, step, step, true)
                3 -> b.box(x1, base - 0.1f, z - 1.5f, x1 + 0.9f, base + 0.14f, z + 1.5f, step, step, true)
                else -> b.box(x0 - 0.9f, base - 0.1f, z - 1.5f, x0, base + 0.14f, z + 1.5f, step, step, true)
            }
        }
        when (roof) {
            0 -> {
                if (hollow) {
                    b.box(x0, y1, z0, x1, y1 + 0.4f, z1, wall, roofC, true)
                    // ceiling seen from inside
                    b.quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, cb(0.55f, 0.5f, 0.42f))
                } else b.box(x0, y0, z0, x1, y1, z1, wall, roofC, true)
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
                    if (!(door == 1 && abs(u + 0.5f - x) < 2.0f)) b.quad(u, yy, z1 + off, u + 1.0f, yy, z1 + off, u + 1.0f, yy + 1.2f, z1 + off, u, yy + 1.2f, z1 + off, glass)
                    if (!(door == 2 && abs(u + 0.5f - x) < 2.0f)) b.quad(u + 1.0f, yy, z0 - off, u, yy, z0 - off, u, yy + 1.2f, z0 - off, u + 1.0f, yy + 1.2f, z0 - off, glass)
                    yy += 3.3f
                }
                u += 3.2f
            }
            var w = z0 + 1.8f
            while (w < z1 - 1.0f) {
                var yy = base + 1.5f
                while (yy + 1.3f < y1 - 0.4f) {
                    if (!(door == 3 && abs(w + 0.5f - z) < 2.0f)) b.quad(x1 + off, yy, w + 1.0f, x1 + off, yy, w, x1 + off, yy + 1.2f, w, x1 + off, yy + 1.2f, w + 1.0f, glass)
                    if (!(door == 4 && abs(w + 0.5f - z) < 2.0f)) b.quad(x0 - off, yy, w, x0 - off, yy, w + 1.0f, x0 - off, yy + 1.2f, w + 1.0f, x0 - off, yy + 1.2f, w, glass)
                    yy += 3.3f
                }
                w += 3.2f
            }
            if (!hollow) b.quad(x - 0.7f, base, z1 + off * 1.5f, x + 0.7f, base, z1 + off * 1.5f, x + 0.7f, base + 2.2f, z1 + off * 1.5f, x - 0.7f, base + 2.2f, z1 + off * 1.5f, door2)
        }
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
        water?.let { batch.render(it, env) }
    }

    fun dispose() {
        for (m in models) m.dispose()
        models.clear()
        if (::colorTex.isInitialized) colorTex.dispose()
        if (::detailTex.isInitialized) detailTex.dispose()
    }
}
