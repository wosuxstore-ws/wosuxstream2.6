package com.freetv.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load

/**
 * The ad banner on top of Home: shows the ads one after the other, in a loop.
 * A picture stays for its `seconds`, a video (always muted) plays to the end. With one ad only, a picture just stays and a video repeats.
 *
 * The view is invisible until it has at least one ad (setItems), so without ads Home looks as before.
 * Click / touch: an ad with a `link` opens that website (OK on the remote, or a tap). The banner can take the remote focus only while
 * the list has at least one ad with a link (a cyan ring shows it). A device without a web browser (many TVs) shows a short message.
 * The owner calls resume() / pause() when the screen is shown / hidden (HomeFragment). pause() also frees the video player, so
 * the banner never holds a hardware decoder while a channel is playing. The same ad list again (setItems) is ignored: no restart.
 *
 * Shape: a fixed ratio (8 : 1, e.g. 1920 x 240 px) between 72dp and 120dp high; the picture / video fills it (the edges may be cropped).
 */
class AdBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val image: ImageView
    private val video: PlayerView

    private var player: ExoPlayer? = null
    private var items: List<AdItem> = emptyList()
    private var index = -1
    private var running = false
    private val ui = Handler(Looper.getMainLooper())
    private val nextAd = Runnable { showNext() }

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        descendantFocusability = View.FOCUS_BLOCK_DESCENDANTS
        setOnClickListener { openLink() }
        setTappable(false)
        LayoutInflater.from(context).inflate(R.layout.view_ad_banner, this, true)
        image = findViewById(R.id.adImage)
        video = findViewById(R.id.adVideo)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val d = resources.displayMetrics.density
        val height = (width / ASPECT).toInt().coerceIn((MIN_DP * d).toInt(), (MAX_DP * d).toInt())
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
    }

    /** Sets the ads. An empty list hides the banner. The same list as before changes nothing. */
    fun setItems(list: List<AdItem>) {
        if (list == items) return
        items = list
        index = -1
        ui.removeCallbacks(nextAd)
        setTappable(list.any { it.link != null })
        if (list.isEmpty()) {
            releasePlayer()
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        if (running) show(0)
    }

    /** The screen is shown: start (again) with the current ad. */
    fun resume() {
        if (running) return
        running = true
        if (items.isNotEmpty()) show(if (index in items.indices) index else 0)
    }

    /** The screen is hidden or stopped: stop the timer and free the video player. */
    fun pause() {
        running = false
        ui.removeCallbacks(nextAd)
        releasePlayer()
    }

    // Focusable / clickable only when some ad has a link, so a banner without links never catches the remote focus
    private fun setTappable(on: Boolean) {
        isFocusable = on
        isClickable = on
    }

    // Opens the website of the ad that is on screen. No browser (or any other failure) -> a short message, never a crash.
    private fun openLink() {
        val link = items.getOrNull(index)?.link ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: Exception) {
            Toast.makeText(context, R.string.ad_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun showNext() {
        if (items.isEmpty()) return
        show((index + 1) % items.size)
    }

    private fun show(i: Int) {
        ui.removeCallbacks(nextAd)
        val item = items.getOrNull(i) ?: return
        index = i
        if (item.video) {
            image.visibility = View.GONE
            video.visibility = View.VISIBLE
            val p = ensurePlayer()
            p.repeatMode = if (items.size == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(item.file)))
            p.prepare()
            p.playWhenReady = true
        } else {
            player?.stop()
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.load(item.file)
            if (items.size > 1) ui.postDelayed(nextAd, item.seconds * 1000L)
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context).build()
        p.volume = 0f // banner videos are silent
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && running && items.size > 1) showNext()
            }

            override fun onPlayerError(error: PlaybackException) {
                // A video that cannot be played is skipped (after a short wait, so a list of broken files never spins)
                if (running && items.size > 1) {
                    ui.removeCallbacks(nextAd)
                    ui.postDelayed(nextAd, ERROR_SKIP_MS)
                }
            }
        })
        video.player = p
        player = p
        return p
    }

    private fun releasePlayer() {
        video.player = null
        player?.release()
        player = null
    }

    private companion object {
        const val ASPECT = 8f
        const val MIN_DP = 72
        const val MAX_DP = 120
        const val ERROR_SKIP_MS = 3_000L
    }
}
