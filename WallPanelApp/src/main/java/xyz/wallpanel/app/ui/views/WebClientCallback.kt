package xyz.wallpanel.app.ui.views

import org.mozilla.geckoview.GeckoSession

interface WebClientCallback {
    fun askForWebkitPermission(permission: String, requestCode: Int)

    fun complete()

    fun pageLoadComplete(url: String)

    fun setPendingMediaPermission(
        callback: GeckoSession.PermissionDelegate.MediaCallback?,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?
    )

    var isConnected: Boolean

    fun isFinishing(): Boolean

    fun displayProgress(): Boolean

    fun startReloadDelay()

    fun stopReloadDelay()

    fun certPermissionsShown(): Boolean

}
