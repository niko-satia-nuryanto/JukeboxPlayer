package com.jukebox.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import java.util.Calendar

// Technique learned from NouTube: a WebView that always reports its window as
// VISIBLE, so Chromium never treats the page as hidden when the screen locks.
class KeepAliveWebView(context: Context) : WebView(context) {
    override fun dispatchWindowVisibilityChanged(visibility: Int) =
        super.dispatchWindowVisibilityChanged(View.VISIBLE)

    override fun onWindowVisibilityChanged(visibility: Int) =
        super.onWindowVisibilityChanged(View.VISIBLE)
}

class MainActivity : Activity() {
    private lateinit var web: KeepAliveWebView
    @Volatile private var base = ""
    private val handler = Handler(Looper.getMainLooper())
    private var mode = "" // "yt" before the switch time, "jukebox" after

    companion object {
        const val DEFAULT_URL = "http://192-168-110-122.nip.io:3000/player"
        // Daily switch time (phone's local clock). Edit these two numbers to change it.
        const val SWITCH_HOUR = 9
        const val SWITCH_MIN = 20
    }

    private fun afterSwitch(): Boolean {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) >= SWITCH_HOUR * 60 + SWITCH_MIN
    }

    private fun serverUrl(): String =
        getPreferences(MODE_PRIVATE).getString("server_url", DEFAULT_URL) ?: DEFAULT_URL

    private val tick = object : Runnable {
        override fun run() {
            if (mode == "yt" && afterSwitch()) openJukebox()
            handler.postDelayed(this, 15000)
        }
    }

    private fun openYoutube() {
        mode = "yt"
        base = ""
        web.loadUrl("https://m.youtube.com")
    }

    private fun openJukebox() {
        mode = "jukebox"
        load(serverUrl())
    }

    inner class Bridge {
        @JavascriptInterface
        fun state(title: String, paused: Boolean) {
            PlayerService.instance?.setState(base, title, paused)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = KeepAliveWebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = userAgentString + " JukeboxApp"
        }
        // YouTube's embedded player needs third-party cookies, which WebView blocks by default.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(Bridge(), "JukeboxBridge")
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, PlayerService::class.java))

        if (afterSwitch()) openJukebox() else openYoutube()
        handler.postDelayed(tick, 15000)
    }

    private fun load(url: String) {
        base = url.substringBefore("/player").trimEnd('/')
        web.loadUrl(url)
        askBatteryOnce()
    }

    private fun askUrl() {
        val box = EditText(this)
        box.setText(serverUrl())
        AlertDialog.Builder(this)
            .setTitle("Jukebox server address (/player)")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val u = box.text.toString().trim()
                getPreferences(MODE_PRIVATE).edit().putString("server_url", u).apply()
                if (mode == "jukebox") load(u)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Keeps Android from freezing the app while the screen is off.
    private fun askBatteryOnce() {
        val prefs = getPreferences(MODE_PRIVATE)
        if (prefs.getBoolean("batteryAsked", false)) return
        prefs.edit().putBoolean("batteryAsked", true).apply()
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Change server address")
        menu.add(0, 2, 0, "Reload")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> askUrl()
            2 -> web.reload()
        }
        return true
    }

    // Back button hides the app instead of closing it, so music keeps playing.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == "yt" && web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    // Deliberately no web.onPause() / onStop handling: the page must keep running.

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        stopService(Intent(this, PlayerService::class.java))
        web.destroy()
        super.onDestroy()
    }
}
