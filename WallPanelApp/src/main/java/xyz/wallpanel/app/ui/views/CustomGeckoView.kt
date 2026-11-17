package xyz.wallpanel.app.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import org.mozilla.geckoview.GeckoView

/**
 * GeckoView replacement for the old CustomWebView. Preserves the
 * requestDisallowInterceptTouchEvent passthrough behavior so swipe-to-refresh and
 * other parent gesture handling keep working while a page requests scroll events.
 */
class CustomGeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GeckoView(context, attrs) {

    private var requestDisallow = false

    fun requestScrollEvents() {
        requestDisallow = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (requestDisallow) {
            requestDisallowInterceptTouchEvent(true)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> requestDisallow = false
        }
        return super.onTouchEvent(event)
    }
}
