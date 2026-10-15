package com.delish.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import com.delish.app.model.ModelManager
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import android.view.ViewGroup.LayoutParams as ViewLayoutParams

/**
 * Hosts the Delish UI (WebView) and bridges to the native engines:
 * Vosk STT, MediaPipe LLM, TextToSpeech.
 *
 * UI assets:  assets/index.html, assets/settings.html, assets/onboarding.html
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var bridge: WebAppInterface

    /** Status-bar / notch height in CSS px, mirrored into the page as --sat. */
    private var safeTop = 0
    private var safeBottom = 0

    /** Hands the real window insets to the page as CSS custom properties. */
    private fun pushInsets() {
        if (!::web.isInitialized) return
        val js = "(function(){var r=document.documentElement;" +
            "if(!r)return;" +
            "r.style.setProperty('--sat','${safeTop}px');" +
            "r.style.setProperty('--sab','${safeBottom}px');})();"
        web.evaluateJavascript(js, null)
    }

    companion object {
        /** Name of the JS interface object exposed to the page. */
        const val BRIDGE_NAME = "Delish"
        private const val REQ_MIC = 4401
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        root = FrameLayout(this).apply {
            layoutParams = ViewLayoutParams(
                ViewLayoutParams.MATCH_PARENT,
                ViewLayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF080A0F.toInt())
        }
        setContentView(root)

        /* The window is edge-to-edge, so the WebView extends up behind the
           status bar. `env(safe-area-inset-*)` reports 0 in Android WebView, so
           the real values have to be pushed into the page as CSS variables —
           otherwise the header sits under the notification bar. */
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val d = resources.displayMetrics.density
            safeTop = (bars.top / d).toInt()
            safeBottom = (bars.bottom / d).toInt()
            pushInsets()
            insets
        }

        bridge = WebAppInterface(this) { this }

        web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF080A0F.toInt())
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false

            // debug builds: inspect the UI at chrome://inspect from desktop Chrome
            if (0 != (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)) {
                WebView.setWebContentsDebuggingEnabled(true)
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                loadsImagesAutomatically = true
            }

            addJavascriptInterface(bridge, BRIDGE_NAME)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // keep in-app navigation inside the WebView
                    return if (url.startsWith("file://")) {
                        view?.loadUrl(url); true
                    } else false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // the document is new — re-apply the insets to it
                    pushInsets()
                    bridge.onPageReady(url.orEmpty())
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    LogBus.log("JS", "net error code=${error?.errorCode} desc=${error?.description} url=${request?.url}")
                }

                @Deprecated("deprecated in API 23")
                override fun onReceivedError(
                    view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                ) {
                    LogBus.log("JS", "net error(legacy) $errorCode $description $failingUrl")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    LogBus.log("JS", "http ${errorResponse?.statusCode} ${request?.url}")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    msg?.let {
                        LogBus.log("JS", "[${it.messageLevel()}] ${it.message()} (line ${it.lineNumber()})")
                    }
                    return true
                }
            }
        }
        root.addView(web)

        web.loadUrl("file:///android_asset/${entryPage()}")
    }

    /**
     * First launch -> onboarding (splash + model download).
     * Once the model is on disk -> straight into chat.
     */
    private fun entryPage(): String {
        val prefs = getSharedPreferences("delish_state", Context.MODE_PRIVATE)
        val installed = prefs.getBoolean("installed", false)
        // Verify the actual file, not just the flag — a stale pref would drop
        // the user into chat with no model behind it and no way to download.
        val tier = prefs.getString("tier", "mini") ?: "mini"
        val rel = ModelManager(this).pathFor(tier)
        val file = File(filesDir, rel)
        val ok = installed && (file.exists() && file.length() > 1024)
        return if (ok) "index.html" else "onboarding.html"
    }

    /** Emitted from native code back into the page. */
    fun sendToPage(event: String, payload: String) {
        runOnUiThread {
            val js = "window.DelishNative && window.DelishNative.onNative('$event', $payload);"
            web.evaluateJavascript(js, null)
        }
    }

    fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    fun requestMicPermission() {
        runOnUiThread {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            bridge.onPermissionResult(granted)
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        bridge.shutdown()
        web.removeJavascriptInterface(BRIDGE_NAME)
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        bridge.onHostPause()
    }
}