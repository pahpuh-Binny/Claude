package io.github.pahpuhbinny.multiview.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A WebView that can keep running while it's off screen.
 *
 * Android tells a WebView its window is hidden when you switch apps or turn
 * the screen off; the page then sees `document.hidden` and most video sites
 * pause. With [keepAlive] on, the WebView is always told its window is
 * visible, so videos keep going in the background, just like music apps.
 */
@SuppressLint("ViewConstructor")
class BrowserWebView(context: Context) : WebView(context) {

    var keepAlive = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepAlive && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}
