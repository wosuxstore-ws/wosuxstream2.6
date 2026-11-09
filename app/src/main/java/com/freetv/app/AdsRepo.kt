package com.freetv.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One ad of the top banner on Home: a picture or a video, already saved on the device. `seconds` is how long a picture stays.
 * `link` is the website that opens when the ad is clicked / touched (null = the ad is not clickable).
 */
data class AdItem(val video: Boolean, val file: File, val seconds: Int, val link: String? = null)

/**
 * Ads for the banner on top of Home. Everything comes straight from your own web space (cPanel), no third-party service.
 *
 * 1. The app downloads the ad list from [ADS_URL] (a small JSON file, made by cpanel-ads/ads.php or written by hand):
 *      {"items":[{"type":"image","url":"https://.../a.jpg","v":1731000000,"sec":6,"link":"https://your-shop.com/sale"},{"type":"video","url":"https://.../b.mp4","v":1731000000}]}
 *    type = "image" or "video" (guessed from the file ending when missing), v = any number that you change when you replace
 *    the file under the same name, sec = seconds a picture stays (2..60, default 6), link = website to open on click / touch (optional,
 *    only http:// and https:// are accepted).
 * 2. Every picture / video in the list is downloaded ONCE into the app's own storage (files/ads), so it also plays offline and
 *    your web space is not asked again and again. Files that are no longer in the list are deleted from the device.
 * 3. The last list is kept on the device too, so the banner shows at once when the app starts, before the internet answered.
 *
 * Limits: 10 ads, 30 MB per file. A failed download is simply tried again at the next refresh.
 */
object AdsRepo {

    /**
     * The address of the ad list on your cPanel, for example "https://your-domain.com/ads/ads.php" (see cpanel-ads/README.txt).
     * Empty = no banner at all, the Home screen looks as it did before.
     */
    const val ADS_URL = "https://wosux.com//ads/ads.php"

    private const val MAX_ITEMS = 10
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val MAX_LIST_BYTES = 512L * 1024
    private const val DEFAULT_SECONDS = 6
    private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov")

    // One refresh at a time, so two calls never write the same files together
    private val lock = Mutex()

    private class Entry(val video: Boolean, val url: String, val seconds: Int, val link: String?)

    /** The ads that are already on the device (from the last list). Fast, never touches the internet. Empty when there are none. */
    suspend fun cached(ctx: Context): List<AdItem> = withContext(Dispatchers.IO) {
        if (ADS_URL.isEmpty()) return@withContext emptyList<AdItem>()
        try {
            val f = manifestFile(ctx)
            if (f.exists()) resolve(ctx, parse(f.readText())) else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Asks the server for the list, downloads what is new, deletes what is gone and returns the ads that are on the device now.
     * Returns null when nothing is configured, the server did not answer or the list was not valid: then keep what is shown.
     */
    suspend fun refresh(ctx: Context): List<AdItem>? {
        if (ADS_URL.isEmpty()) return null
        return lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val text = fetchList() ?: return@withContext null
                    val entries = parse(text)
                    val dir = mediaDir(ctx)
                    dir.mkdirs()
                    for (e in entries) {
                        ensureActive()
                        val f = localFile(dir, e)
                        if (f.length() == 0L) {
                            try {
                                download(e.url, f)
                            } catch (x: CancellationException) {
                                throw x
                            } catch (x: Exception) {
                                // this ad is skipped for now and tried again at the next refresh
                            }
                        }
                    }
                    // Keep only what the list names (this also removes half-finished downloads)
                    val keep = HashSet<String>()
                    for (e in entries) keep.add(localFile(dir, e).name)
                    dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
                    writeText(manifestFile(ctx), text)
                    resolve(ctx, entries)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    // ---------- list ----------

    // Throws (JSONException) when the text is not a valid list: the caller keeps the old ads then
    private fun parse(text: String): List<Entry> {
        val arr = JSONObject(text).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_ITEMS) break
            val o = arr.optJSONObject(i) ?: continue
            val raw = o.optString("url", "").trim()
            if (!raw.startsWith("https://") && !raw.startsWith("http://")) continue
            val type = o.optString("type", "").trim().lowercase()
            val video = when (type) {
                "video" -> true
                "image" -> false
                else -> extOf(raw) in VIDEO_EXT
            }
            // "v" changes the address, so a replaced file is a new download (and the old copy is deleted)
            val v = o.optLong("v", 0L)
            val url = if (v > 0L) raw + (if (raw.contains('?')) "&" else "?") + "v=" + v else raw
            val sec = o.optInt("sec", DEFAULT_SECONDS).coerceIn(2, 60)
            val link = o.optString("link", "").trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
            out.add(Entry(video, url, sec, link))
        }
        return out
    }

    private fun fetchList(): String? {
        val request = Request.Builder().url(ADS_URL).header("Cache-Control", "no-cache").build()
        return Net.client.newCall(request).execute().use { response ->
            val body = response.body
            if (response.code != 200 || body == null || body.contentLength() > MAX_LIST_BYTES) null else body.string()
        }
    }

    // ---------- files ----------

    private fun manifestFile(ctx: Context) = File(ctx.filesDir, "ads.json")

    private fun mediaDir(ctx: Context) = File(ctx.filesDir, "ads")

    private fun localFile(dir: File, e: Entry) = File(dir, "ad_" + Integer.toHexString(e.url.hashCode()) + "." + extOf(e.url))

    // File ending without the query part; "bin" when the address has none that looks like one
    private fun extOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else "bin"
    }

    // The entries whose file is on the device, in the order of the list
    private fun resolve(ctx: Context, entries: List<Entry>): List<AdItem> {
        val dir = mediaDir(ctx)
        val out = ArrayList<AdItem>(entries.size)
        for (e in entries) {
            val f = localFile(dir, e)
            if (f.length() > 0L) out.add(AdItem(e.video, f, e.seconds, e.link))
        }
        return out
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            Net.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code != 200) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty file")
                if (body.contentLength() > MAX_BYTES) throw IOException("File too big")
                var total = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("File too big")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            if (tmp.length() == 0L) throw IOException("Empty file")
            if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
        } finally {
            tmp.delete()
        }
    }

    private fun writeText(dest: File, text: String) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
