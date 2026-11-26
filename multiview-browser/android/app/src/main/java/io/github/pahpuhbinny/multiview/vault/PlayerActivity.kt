package io.github.pahpuhbinny.multiview.vault

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.ui.toast

/** Plays a video from the vault, decrypting it as it plays. Nothing is written to disk. */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.vaultSecureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this)
        setContentView(view)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())

        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val item = Vault.item(id) ?: return finish()
        val p = ExoPlayer.Builder(this).build()
        player = p
        view.player = p
        val source = ProgressiveMediaSource.Factory(VaultDataSource.Factory(this))
            .createMediaSource(MediaItem.fromUri(Uri.parse("mvvault://$id")))
        p.setMediaSource(source)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast("Can't play this video: ${error.errorCodeName}")
            }
        })
        title = item.title
        p.prepare()
        p.playWhenReady = true
        val start = savedInstanceState?.getLong("pos") ?: intent.getLongExtra(EXTRA_POSITION, 0L)
        if (start > 0) p.seekTo(start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong("pos", it.currentPosition) }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_POSITION = "position"
    }
}
