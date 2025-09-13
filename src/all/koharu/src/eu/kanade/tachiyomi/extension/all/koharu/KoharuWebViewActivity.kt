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

        // Create a bridge for JavaScript to call our Kotlin code
        webView.addJavascriptInterface(JSInterface(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                // This JavaScript code will run after the page loads.
                // It sets up an interval to check localStorage every half-second.
                // When it finds the 'clearance' token, it passes it to our Android app and stops.
                val js = """
                    (function() {
                        const interval = setInterval(function() {
                            const token = localStorage.getItem('clearance');
                            if (token) {
                                clearInterval(interval);
                                // The token is found! Pass it to our JSInterface.
                                Android.passToken(token);
                            }
                        }, 500);
                    })();
                """
                view.evaluateJavascript(js, null)
            }
        }

        // Load the website. The user will solve the Turnstile CAPTCHA here.
        webView.loadUrl(source.baseUrl)
    }

    // This is the Kotlin class that the JavaScript code will call.
    inner class JSInterface {
        @JavascriptInterface
        fun passToken(token: String) {
            val intent = Intent()
            // The token from JS has extra quotes, so we remove them.
            intent.putExtra("token", token.removeSurrounding("\""))
            setResult(Activity.RESULT_OK, intent)
            finish() // Close the WebView activity
        }
    }
}
