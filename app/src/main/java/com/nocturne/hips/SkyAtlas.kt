package com.nocturne.hips

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Offline sky atlas for the Plan tab's Framing card: the DSS2 colour HiPS survey, orders
 * 0..[MAX_ORDER]. Orders 0..4 ship inside the APK (assets `hips/DSSColor/`, packed at build time
 * from the laptop mirror, see app/build.gradle.kts); order 5 lives in app storage, loaded by
 * `tools/push_sky_atlas.sh` over USB or copied from the rig ([copyFromRig], reboot daemon
 * `/hips/`). A tile in app storage wins over a packaged one. Unlike a per-target cutout, the
 * tiles don't depend on the scope or camera: any field of view, any target, is rendered from
 * them ([render]), so framing works with no internet at all.
 */
class SkyAtlas(context: Context) {

    companion object {
        /** Order 5 = 12.9″/tile pixel: sharp for the 600 px framing image up to ~600 mm focal
         *  length. 16,380 tiles, ~970 MB (measured 2026-10-24: tiles grow from ~44 KB at order 3 to ~62 KB at order 5). */
        const val MAX_ORDER = 5
        val TOTAL_TILES: Int = (0..MAX_ORDER).sumOf { Healpix.tileCount(it) }
        private const val PARALLEL = 4
        private const val ASSET_ROOT = "hips/DSSColor"
    }

    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "hips/DSSColor")
    private val assets = context.assets

    /** Packaged tiles per order, counted once (asset listing is slow-ish; the APK can't change). */
    private val packaged: Map<Int, Set<String>> by lazy {
        (0..MAX_ORDER).associateWith { order ->
            val base = "$ASSET_ROOT/Norder$order"
            (runCatching { assets.list(base) }.getOrNull() ?: emptyArray()).flatMap { dir ->
                (runCatching { assets.list("$base/$dir") }.getOrNull() ?: emptyArray())
                    .filter { it.startsWith("Npix") && it.endsWith(".jpg") }
                    .map { "Norder$order/$dir/$it" }
            }.toSet()
        }
    }

    private fun isPackaged(order: Int, npix: Int) = Healpix.tilePath(order, npix) in packaged[order].orEmpty()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    fun tileFile(order: Int, npix: Int) = File(root, Healpix.tilePath(order, npix))

    data class Local(val tiles: Int, val completeOrder: Int)

    /** Tiles on the phone (packaged + app storage), and the deepest order with every tile present (-1: none). */
    suspend fun scanLocal(): Local = withContext(Dispatchers.IO) {
        var tiles = 0
        var complete = -1
        for (order in 0..MAX_ORDER) {
            val stored = File(root, "Norder$order").listFiles()?.flatMap { dir ->
                dir.list()?.filter { it.startsWith("Npix") && it.endsWith(".jpg") }?.map { "Norder$order/${dir.name}/$it" }.orEmpty()
            }.orEmpty().toSet()
            val n = (stored + packaged[order].orEmpty()).size
            tiles += n
            if (n >= Healpix.tileCount(order) && complete == order - 1) complete = order
        }
        Local(tiles, complete)
    }

    /** Tiles the rig can serve for orders 0..[MAX_ORDER], from the daemon's `/hips/status`. */
    suspend fun rigTiles(host: String, port: Int): Int = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("http://$host:$port/hips/status").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("rig replied HTTP ${resp.code}")
            val orders = Json.parseToJsonElement(resp.body!!.string()).jsonObject["orders"]?.jsonObject
                ?: throw IOException("rig has no sky atlas")
            (0..MAX_ORDER).sumOf { orders[it.toString()]?.jsonPrimitive?.int ?: 0 }
        }
    }

    /**
     * Copies every tile the phone doesn't have yet. Resumable (present tiles are skipped,
     * downloads land in `.part` first) and cancellable. Tiles the rig doesn't have yet (its own
     * mirror still running) are counted in the result's `missing`, not treated as errors.
     */
    suspend fun copyFromRig(host: String, port: Int, onProgress: (done: Int, total: Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val todo = (0..MAX_ORDER).flatMap { order ->
                (0 until Healpix.tileCount(order)).map { order to it }
            }.filterNot { (o, n) -> isPackaged(o, n) || tileFile(o, n).let { it.exists() && it.length() > 0 } }
            val done = AtomicInteger(TOTAL_TILES - todo.size)
            val missing = AtomicInteger(0)
            onProgress(done.get(), TOTAL_TILES)
            if (todo.isEmpty()) return@withContext 0
            coroutineScope {
                todo.chunked(maxOf(1, (todo.size + PARALLEL - 1) / PARALLEL)).map { part ->
                    async {
                        for ((order, npix) in part) {
                            coroutineContext.ensureActive()
                            if (!fetchTile(host, port, order, npix)) missing.incrementAndGet()
                            val d = done.incrementAndGet()
                            if (d % 50 == 0 || d == TOTAL_TILES) onProgress(d, TOTAL_TILES)
                        }
                    }
                }.awaitAll()
            }
            missing.get()
        }

    private fun fetchTile(host: String, port: Int, order: Int, npix: Int): Boolean {
        val rel = Healpix.tilePath(order, npix)
        val req = Request.Builder().url("http://$host:$port/hips/$rel").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return false
            if (!resp.isSuccessful) throw IOException("rig replied HTTP ${resp.code} for $rel")
            val out = File(root, rel)
            out.parentFile?.mkdirs()
            val part = File(out.path + ".part")
            part.writeBytes(resp.body!!.bytes())
            if (!part.renameTo(out)) throw IOException("couldn't write $rel")
            return true
        }
    }

    /** Deletes the tiles in app storage; the packaged orders stay (they're part of the APK). */
    fun clear() {
        root.deleteRecursively()
    }

    /**
     * Square [sizePx] JPEG of [fovDeg] around ([raDeg], [decDeg]), same framing as the
     * `hips2fits` cutout it replaces (TAN, north up, east left). Uses the coarsest order whose
     * pixels are at least as fine as the output's, capped at [maxOrder] (the deepest order
     * fully on the phone). Null if a needed tile is missing.
     */
    suspend fun render(raDeg: Double, decDeg: Double, fovDeg: Double, maxOrder: Int, sizePx: Int = 600): ByteArray? =
        withContext(Dispatchers.Default) {
            if (maxOrder < 0) return@withContext null
            val outPixelDeg = fovDeg / sizePx
            val order = (0..maxOrder).firstOrNull { Healpix.tilePixelDeg(it) <= outPixelDeg } ?: maxOrder
            val tiles = HashMap<Int, IntArray?>()
            val out = IntArray(sizePx * sizePx)
            for (row in 0 until sizePx) {
                coroutineContext.ensureActive()
                for (col in 0 until sizePx) {
                    val (ra, dec) = Healpix.tanToSky(raDeg, decDeg, fovDeg, sizePx, col, row)
                    val s = Healpix.tileSample(order, ra, dec)
                    val px = tiles.getOrPut(s.npix) { loadTile(order, s.npix) } ?: return@withContext null
                    out[row * sizePx + col] = px[s.row * Healpix.TILE_WIDTH + s.col]
                }
            }
            val bmp = Bitmap.createBitmap(out, sizePx, sizePx, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                bmp.recycle()
                bos.toByteArray()
            }
        }

    private fun loadTile(order: Int, npix: Int): IntArray? {
        val f = tileFile(order, npix)
        val bmp = when {
            f.exists() -> BitmapFactory.decodeFile(f.path)
            isPackaged(order, npix) -> assets.open("$ASSET_ROOT/${Healpix.tilePath(order, npix)}").use { BitmapFactory.decodeStream(it) }
            else -> null
        } ?: return null
        if (bmp.width != Healpix.TILE_WIDTH || bmp.height != Healpix.TILE_WIDTH) {
            bmp.recycle()
            return null
        }
        val px = IntArray(Healpix.TILE_WIDTH * Healpix.TILE_WIDTH)
        bmp.getPixels(px, 0, Healpix.TILE_WIDTH, 0, 0, Healpix.TILE_WIDTH, Healpix.TILE_WIDTH)
        bmp.recycle()
        return px
    }
}
