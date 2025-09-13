// FILE: src/all/koharu/KoharuWebViewActivity.kt

package eu.kanade.tachiyomi.extension.all.koharu

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.source.online.HttpSource
import uy.kohesive.injekt.injectLazy

class KoharuWebViewActivity : Activity() {

    private val source: HttpSource by injectLazy()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = source.headers["User-Agent"] // Use the source's user agent

        // Create a bridge for JavaScript to call our Kotlin code
        webView.addJavascriptInterface(JSInterface(this), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                // This script waits for the clearance token and then passes it AND the user agent back.
                val js = """
                    (function() {
                        const interval = setInterval(function() {
                            const token = localStorage.getItem('clearance');
                            if (token) {
                                clearInterval(interval);
                                const userAgent = navigator.userAgent;
                                Android.passClearanceData(token, userAgent);
                            }
                        }, 500);
                    })();
                """
                view.evaluateJavascript(js, null)
            }
        }

        webView.loadUrl(source.baseUrl)
    }

    inner class JSInterface(private val activity: Activity) {
        @JavascriptInterface
        fun passClearanceData(token: String, userAgent: String) {
            val intent = Intent()
            intent.putExtra("token", token.removeSurrounding("\""))
            intent.putExtra("userAgent", userAgent)
            activity.setResult(Activity.RESULT_OK, intent)
            activity.finish()
        }
    }
}
