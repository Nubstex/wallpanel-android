package xyz.wallpanel.app.utils

import android.content.res.Resources
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import timber.log.Timber
import xyz.wallpanel.app.R
import xyz.wallpanel.app.persistence.Configuration
import xyz.wallpanel.app.ui.activities.BaseBrowserActivity
import xyz.wallpanel.app.ui.views.WebClientCallback
import java.util.*

/**
 * GeckoSession-based replacement for the old InternalWebClient/InternalWebChromeClient
 * (android.webkit.WebViewClient/WebChromeClient) pair. Ports the same behavior onto the
 * matching GeckoSession delegate interfaces.
 */
open class InternalGeckoSessionDelegate(
    private val resources: Resources,
    private val anchorView: View,
    private val callback: WebClientCallback,
    private val configuration: Configuration,
    private val dialogUtils: DialogUtils
) : GeckoSession.NavigationDelegate,
    GeckoSession.ProgressDelegate,
    GeckoSession.ContentDelegate,
    GeckoSession.PermissionDelegate,
    GeckoSession.PromptDelegate {

    private var snackbar: Snackbar? = null
    private var currentUrl = ""

    open fun isCurrentUrl(url: String): Boolean {
        return url.lowercase(Locale.getDefault()).contains(currentUrl.lowercase())
    }

    // region NavigationDelegate

    override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>
    ) {
        callback.pageLoadComplete(url ?: currentUrl)
    }

    override fun onLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny> {
        currentUrl = request.uri
        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }

    // TODO load a special file here on disconnect and then reload page on timer
    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: WebRequestError
    ): GeckoResult<String> {
        if (error.category == WebRequestError.ERROR_CATEGORY_SECURITY) {
            // GeckoView has no equivalent of WebView's SslErrorHandler.proceed(): a cert
            // error page can be shown, but there's no supported way to force the load
            // through from this delegate. We only surface the same dialog as before.
            if (!callback.certPermissionsShown() && !callback.isFinishing() && configuration.ignoreSSLErrors) {
                dialogUtils.showAlertDialog(
                    anchorView.context,
                    resources.getString(R.string.dialog_title_ssl_error),
                    resources.getString(R.string.dialog_message_ssl_generic) + resources.getString(R.string.dialog_message_ssl_continue),
                    resources.getString(R.string.button_continue),
                    { _, _ -> },
                    { _, _ -> }
                )
            }
        }
        if (!callback.isFinishing()) {
            callback.isConnected = false
            callback.startReloadDelay()
        }
        return GeckoResult.fromValue("file:///android_asset/error_page.html")
    }

    // endregion

    // region ProgressDelegate

    override fun onPageStart(session: GeckoSession, url: String) {
        if (isCurrentUrl(url)) {
            currentUrl = url
        }
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        if (callback.isConnected) {
            callback.stopReloadDelay()
        }
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        if (progress == 100) {
            snackbar?.dismiss()
            if (currentUrl.isEmpty()) {
                Toast.makeText(
                    anchorView.context,
                    resources.getString(R.string.toast_empty_url),
                    Toast.LENGTH_SHORT
                ).show()
                callback.complete()
            }
            return
        }
        if (callback.displayProgress()) {
            val text = resources.getString(R.string.text_loading_percent, progress.toString(), currentUrl)
            if (snackbar == null) {
                snackbar = Snackbar.make(anchorView, text, Snackbar.LENGTH_INDEFINITE)
            } else {
                snackbar?.setText(text)
            }
            snackbar?.show()
        }
    }

    // endregion

    // region ContentDelegate

    override fun onCrash(session: GeckoSession) {
        // Equivalent of the old onRenderProcessGone: the content process is dead and
        // the session can no longer be used, so just tear it down.
        Timber.d("GeckoSession content process crashed")
        session.close()
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        // GeckoView requires the embedder to react to fullscreen requests (e.g. HTML5
        // video) for them to work at all; the old WebChromeClient never implemented
        // onShowCustomView/onHideCustomView, so there is no prior behavior to preserve
        // beyond making fullscreen playback functional.
        val activity = anchorView.context as? android.app.Activity ?: return
        val decorView = activity.window.decorView
        decorView.systemUiVisibility = if (fullScreen) {
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // endregion

    // region PermissionDelegate

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    ) {
        // Mirrors the old onPermissionRequest: only the media (camera/mic) path below
        // is wired up to the app's own permission flow; anything else is rejected.
        callback.reject()
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback
    ) {
        this.callback.setPendingMediaPermission(callback, video, audio)
        if (video != null) {
            this.callback.askForWebkitPermission(
                android.Manifest.permission.CAMERA,
                BaseBrowserActivity.REQUEST_CODE_PERMISSION_CAMERA
            )
        } else if (audio != null) {
            this.callback.askForWebkitPermission(
                android.Manifest.permission.RECORD_AUDIO,
                BaseBrowserActivity.REQUEST_CODE_PERMISSION_AUDIO
            )
        } else {
            callback.reject()
        }
    }

    // endregion

    // region PromptDelegate

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AlertPrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (!callback.isFinishing()) {
            AlertDialog.Builder(anchorView.context)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        return GeckoResult.fromValue(prompt.dismiss())
    }

    // endregion
}
