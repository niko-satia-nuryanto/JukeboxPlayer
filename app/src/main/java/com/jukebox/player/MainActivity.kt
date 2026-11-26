package com.jukebox.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

        val saved = getPreferences(MODE_PRIVATE).getString("url", null)
        if (saved == null) askUrl() else load(saved)
    }

    private fun load(url: String) {
        base = url.substringBefore("/player").trimEnd('/')
        web.loadUrl(url)
        askBatteryOnce()
    }

    private fun askUrl() {
        val box = EditText(this)
        box.setText(
            getPreferences(MODE_PRIVATE).getString("url", "http://192-168-110-122.nip.io:3000/player")
        )
        AlertDialog.Builder(this)
            .setTitle("Jukebox server address (/player)")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val u = box.text.toString().trim()
                getPreferences(MODE_PRIVATE).edit().putString("url", u).apply()
                load(u)
            }
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
        moveTaskToBack(true)
    }

    // Deliberately no web.onPause() / onStop handling: the page must keep running.

    override fun onDestroy() {
        stopService(Intent(this, PlayerService::class.java))
        web.destroy()
        super.onDestroy()
    }
}
