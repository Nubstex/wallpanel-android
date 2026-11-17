/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.app.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.view.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleObserver
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import xyz.wallpanel.app.WallPanel
import xyz.wallpanel.app.databinding.ActivityBrowserBinding
import xyz.wallpanel.app.network.ConnectionLiveData
import xyz.wallpanel.app.ui.fragments.CodeBottomSheetFragment
import xyz.wallpanel.app.ui.views.CustomGeckoView
import xyz.wallpanel.app.ui.views.WebClientCallback
import xyz.wallpanel.app.utils.InternalGeckoSessionDelegate
import xyz.wallpanel.app.BuildConfig
import xyz.wallpanel.app.R
import timber.log.Timber
import java.net.URISyntaxException
import java.util.*
import java.util.concurrent.TimeUnit


class BrowserActivityNative : BaseBrowserActivity(), LifecycleObserver, WebClientCallback {

    private lateinit var geckoView: CustomGeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var binding: ActivityBrowserBinding
    private var certPermissionsShown = false
    private var playlistHandler: Handler? = null
    private var codeBottomSheet: CodeBottomSheetFragment? = null
    private val calendar: Calendar = Calendar.getInstance()
    private val reconnectionHandler = Handler(Looper.getMainLooper())
    private var connectionLiveData: ConnectionLiveData? = null
    override var isConnected = true
    private var pendingMediaCallback: GeckoSession.PermissionDelegate.MediaCallback? = null
    private var pendingVideoSources: Array<GeckoSession.PermissionDelegate.MediaSource>? = null
    private var pendingAudioSources: Array<GeckoSession.PermissionDelegate.MediaSource>? = null
    private var awaitingReconnect = false

    private val reloadPageRunnable = Runnable {
        initWebPageLoad()
    }

    // To save current index
    private var playlistIndex = 0

    private val playlistRunnable = object : Runnable {
        override fun run() {
            // TODO: allow users to set their own value in settings
            val offset = 60L - calendar.get(Calendar.SECOND)
            val urls: List<String> = configuration.appLaunchUrl.lines()
            // Avoid IndexOutOfBound
            playlistIndex = (playlistIndex + 1) % urls.size
            if (urls.isNotEmpty() && urls.size >= playlistIndex) {
                loadWebViewUrl(urls[playlistIndex])
                playlistHandler?.postDelayed(this, TimeUnit.SECONDS.toMillis(offset))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            configuration.mqttBroker = BuildConfig.BROKER
            configuration.mqttUsername = BuildConfig.BROKER_USERNAME
            configuration.mqttPassword = BuildConfig.BROKER_PASS
            configuration.appLaunchUrl = BuildConfig.HASS_URL
            configuration.isFirstTime = false
            configuration.settingsCode = BuildConfig.CODE.toString()
            configuration.hasClockScreenSaver = true
        }

        binding = ActivityBrowserBinding.inflate(layoutInflater)
        try {
            setContentView(binding.root)
        } catch (e: Exception) {
            Timber.e(e.message)
            AlertDialog.Builder(this@BrowserActivityNative)
                .setMessage(getString(R.string.dialog_missing_webview_warning))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        binding.launchSettingsFab.setOnClickListener {
            if (configuration.isFirstTime) {
                openSettings()
            } else {
                showCodeBottomSheet()
            }
        }

        configureConnection()
        configureGeckoView()
        initWebPageLoad()
    }

    override fun onStart() {
        super.onStart()
        if (configuration.useDarkTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            setLightTheme()
        }

        if (configuration.browserRefresh) {
            binding.swipeContainer.setOnRefreshListener {
                clearCache()
                initWebPageLoad()
            }
            // GeckoView scrolls its content inside the compositor rather than as an
            // Android View, so View.scrollY is always 0 here; swipe-to-refresh is left
            // enabled at all scroll positions as a result.
            mOnScrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
                binding.swipeContainer?.isEnabled = geckoView.scrollY == 0
            }
            binding.swipeContainer.viewTreeObserver.addOnScrollChangedListener(mOnScrollChangedListener)
        } else {
            binding.swipeContainer.isEnabled = false
        }

        setupSettingsButton()

        if (configuration.hasSettingsUpdates()) {
            initWebPageLoad()
        }
    }

    override fun onStop() {
        super.onStop()
        if (mOnScrollChangedListener != null && configuration.browserRefresh) {
            binding.swipeContainer.viewTreeObserver.removeOnScrollChangedListener(mOnScrollChangedListener)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        codeBottomSheet?.dismiss()
        geckoSession.close()
    }

    override fun openSettings() {
        hideScreenSaver()
        // Stop our service for performance reasons and to pick up changes
        stopService(wallPanelService)
        val intent = SettingsActivity.createStartIntent(this)
        startActivity(intent)
    }

    override fun loadWebViewUrl(url: String) {
        Timber.d("loadUrl $url")
        if (url.startsWith("intent:")) {
            val launchIntent: Intent
            try {
                launchIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } catch (ex: URISyntaxException) {
                Timber.e("Bad URI $url: $ex.message")
                dialogUtils.showAlertDialog(geckoView.context, resources.getString(R.string.dialog_message_invalid_intent))
                return
            }
            val selector = launchIntent.selector
            if (selector != null) {
                selector.addCategory(Intent.CATEGORY_BROWSABLE)
                selector.setComponent(null)
            }
            launchIntent.putExtra(Browser.EXTRA_APPLICATION_ID, geckoView.context.packageName);
            geckoView.context.startActivity(launchIntent)
        } else {
            geckoSession.loadUri(url)
        }
    }

    override fun evaluateJavascript(js: String) {
        // GeckoSession has no WebView.evaluateJavascript equivalent: running arbitrary
        // JS against a loaded page requires a WebExtension content script. The
        // BROADCAST_ACTION_JS_EXEC remote-exec feature (triggered via MQTT) is
        // therefore a known gap until that port is done.
        Timber.w("evaluateJavascript is not implemented for GeckoView; dropping JS exec request")
    }

    override fun clearCache() {
        WallPanel.getGeckoRuntime(applicationContext).storageController
            .clearData(StorageController.ClearFlags.ALL.toLong())
    }

    override fun reload() {
        geckoSession.reload()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun configureWebSettings(userAgent: String) {
        // GeckoSessionSettings has no equivalent for: allowFileAccess/
        // allowFileAccessFromFileURLs/allowContentAccess (no file:// embedding model in
        // GeckoView), saveFormData, domStorageEnabled/databaseEnabled (always on),
        // pluginState (NPAPI plugins don't exist in Gecko), setRenderPriority, or
        // cacheMode (LOAD_NO_CACHE has no session-level toggle). Those are dropped.
        if (userAgent.isNotEmpty()) {
            geckoSession.settings.userAgentOverride = userAgent
        }
        geckoSession.settings.useTrackingProtection = false
    }

    override fun complete() {
        if (binding.swipeContainer != null && binding.swipeContainer.isRefreshing && configuration.browserRefresh) {
            binding.swipeContainer.isRefreshing = false
        }
    }

    override fun setPendingMediaPermission(
        callback: GeckoSession.PermissionDelegate.MediaCallback?,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?
    ) {
        pendingMediaCallback = callback
        pendingVideoSources = video
        pendingAudioSources = audio
    }

    override fun displayProgress(): Boolean {
        return displayProgress
    }

    override fun startReloadDelay() {
        awaitingReconnect = true
        playlistHandler?.removeCallbacksAndMessages(null)
        reconnectionHandler.postDelayed(reloadPageRunnable, 30000)
    }

    override fun certPermissionsShown(): Boolean {
        return certPermissionsShown
    }

    override fun stopReloadDelay() {
        awaitingReconnect = false
        reconnectionHandler.removeCallbacks(reloadPageRunnable)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun askForWebkitPermission(permission: String, requestCode: Int) {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
            }
        } else {
            pendingMediaCallback?.grant(pendingVideoSources?.firstOrNull(), pendingAudioSources?.firstOrNull())
            pendingMediaCallback = null
        }
    }

    private fun configureConnection() {
        connectionLiveData = ConnectionLiveData(this)
        connectionLiveData?.observe(this) { connected ->
            if (connected && isConnected.not()) {
                isConnected = true
                if (awaitingReconnect) { // reload the page if there was error initially loading page due to network disconnect
                    stopReloadDelay()
                    initWebPageLoad()
                } else if (configuration.browserRefreshDisconnect) { // reload page on network reconnect
                    initWebPageLoad()
                }
            } else if (connected.not()) {
                isConnected = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureGeckoView() {
        geckoView = binding.activityBrowserWebviewNative
        geckoSession = GeckoSession(GeckoSessionSettings.Builder().build())
        geckoSession.open(WallPanel.getGeckoRuntime(applicationContext))

        val delegate = InternalGeckoSessionDelegate(
            resources = resources,
            anchorView = geckoView,
            callback = this,
            configuration = configuration,
            dialogUtils = dialogUtils
        )
        geckoSession.navigationDelegate = delegate
        geckoSession.progressDelegate = delegate
        geckoSession.contentDelegate = delegate
        geckoSession.permissionDelegate = delegate
        geckoSession.promptDelegate = delegate

        geckoView.setSession(geckoSession)

        geckoView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetScreen()
                    if (!v.hasFocus()) {
                        v.requestFocus()
                    }
                }
                MotionEvent.ACTION_UP -> if (!v.hasFocus()) {
                    v.requestFocus()
                }
            }
            false
        }
    }

    private fun initWebPageLoad() {
        binding.progressView.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
        // set user agent
        configureWebSettings(configuration.browserUserAgent)
        // set zoom level: GeckoSessionSettings/GeckoView has no equivalent of
        // WebView.setInitialScale, so configuration.testZoomLevel ("zoomLevel") is not
        // applied here.
        // check if we are using playlist
        if (configuration.appLaunchUrl.lines().size == 1) {
            loadWebViewUrl(configuration.appLaunchUrl)
        } else {
            startPlaylist()
        }
    }

    private fun startPlaylist() {
        playlistHandler = Handler(Looper.getMainLooper())
        playlistHandler?.postDelayed(playlistRunnable, 10)
    }



    private fun showCodeBottomSheet() {
        codeBottomSheet = CodeBottomSheetFragment.newInstance(configuration.settingsCode,
            object : CodeBottomSheetFragment.OnAlarmCodeFragmentListener {
                override fun onComplete(code: String) {
                    codeBottomSheet?.dismiss()
                    openSettings()
                }

                override fun onCodeError() {
                    Toast.makeText(
                        this@BrowserActivityNative,
                        R.string.toast_code_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onCancel() {
                    codeBottomSheet?.dismiss()
                }
            })
        codeBottomSheet?.show(supportFragmentManager, codeBottomSheet?.tag)
    }

    private fun setupSettingsButton() {
        // Set the location and transparency of the fab button
        val params: CoordinatorLayout.LayoutParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = 16
        params.leftMargin = 16
        params.rightMargin = 16
        params.bottomMargin = 16
        when (configuration.settingsLocation) {
            0 -> {
                params.gravity = Gravity.BOTTOM or Gravity.END
            }
            1 -> {
                params.gravity = Gravity.BOTTOM or Gravity.START
            }
            2 -> {
                params.gravity = Gravity.TOP or Gravity.END
            }
            3 -> {
                params.gravity = Gravity.TOP or Gravity.START
            }
        }
        binding.launchSettingsFab.layoutParams = params
        when {
            configuration.settingsDisabled -> {
                binding.launchSettingsFab.visibility = View.GONE
            }
            configuration.settingsTransparent -> {
                binding.launchSettingsFab.visibility = View.VISIBLE
                binding.launchSettingsFab.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.transparent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    binding.launchSettingsFab.compatElevation = 0f
                }
                binding.launchSettingsFab.imageAlpha = 0
            }
            else -> {
                binding.launchSettingsFab.visibility = View.VISIBLE
                binding.launchSettingsFab.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.colorAccent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    binding.launchSettingsFab.compatElevation = 4f
                }
                binding.launchSettingsFab.imageAlpha = 180
            }
        }
    }

}
