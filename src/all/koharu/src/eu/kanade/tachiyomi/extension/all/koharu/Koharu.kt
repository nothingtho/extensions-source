package eu.kanade.tachiyomi.extension.all.koharu

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

// This object is a diagnostic tool to test Cloudflare session stability.
// It creates a WebView with settings designed to mimic a real browser as closely as possible.
object DebugWebViewHelper {
    private const val TAG = "KOHARU_DEBUG_WEBVIEW"
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun openInDebugWebView(source: HttpSource, url: String) {
        handler.post {
            try {
                val context = Injekt.get<Application>()
                val webView = WebView(context)
                val cookieManager = CookieManager.getInstance()

                // Pillar 1: Persistent, Enabled Storage
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT

                    // Pillar 2: Consistent and Believable Identity
                    // We get the User-Agent directly from the client that Tachiyomi will use.
                    val userAgent = source.client.newCall(GET(source.baseUrl, source.headers)).execute().request.header("User-Agent")!!
                    userAgentString = userAgent
                    Log.d(TAG, "WebView User-Agent set to: $userAgent")

                    // Pillar 3: Full Web Functionality
                    javaScriptCanOpenWindowsAutomatically = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }

                // Pillar 3 (cont.): Session Inheritance - Manually sync cookies
                val cookies = source.client.cookieJar.loadForRequest(url.toHttpUrl())
                cookies.forEach { cookie ->
                    cookieManager.setCookie(url, cookie.toString())
                    Log.d(TAG, "Syncing cookie to WebView: ${cookie.name}=${cookie.value}")
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "WebView finished loading: $url")
                        Toast.makeText(context, "Debug WebView loaded. Test browsing now.", Toast.LENGTH_LONG).show()
                    }
                }

                val dialog = android.app.AlertDialog.Builder(context)
                    .setView(webView)
                    .setNegativeButton("Close") { dialog, _ ->
                        webView.stopLoading()
                        webView.destroy()
                        dialog.dismiss()
                    }
                    .create()

                dialog.show()
                webView.loadUrl(url)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create debug WebView", e)
                Toast.makeText(Injekt.get<Application>(), "Error creating WebView: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class Koharu(
    override val lang: String = "all",
    private val searchLang: String = "",
) : HttpSource(), ConfigurableSource {

    override val name = "Niyaniya"
    override val baseUrl = "https://niyaniya.moe"
    override val id = if (lang == "en") 1484902275639232927 else super.id
    private val apiUrl = "https://api.schale.network"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .setRandomUserAgent(
                userAgentType = preferences.getPrefUAType(),
                customUA = preferences.getPrefCustomUA(),
                filterInclude = listOf("chrome"),
            )
            .rateLimit(3)
            .build()
    }

    // --- DIAGNOSTIC MODE ---
    // The following functions are overridden to create a test flow.

    // 1. User opens "Popular" or "Latest". If blocked by CF, this throws an error,
    //    prompting Tachiyomi to show the "Open in WebView" button.
    override fun popularMangaRequest(page: Int): Request = GET(apiUrl, headers) // A dummy request to trigger CF
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun popularMangaParse(response: Response): MangasPage {
        // This will only be reached AFTER the user solves the initial CF challenge.
        // We throw a custom exception to guide the user to the next step.
        throw IOException("Cloudflare solved! Now tap any manga to open the debug WebView.")
    }
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // 2. After solving CF, the user taps on any manga from the (now visible) browse screen.
    //    This function is hijacked to launch our highly-configured test WebView.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        DebugWebViewHelper.openInDebugWebView(this, "$baseUrl${manga.url}")
        return Observable.empty() // We don't return any real manga data.
    }

    // --- All other functions are disabled for this test ---
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException("Disabled for this test")
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Disabled for this test")
    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException("Disabled for this test")
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Disabled for this test")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.error(UnsupportedOperationException("Disabled for this test"))
    override fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException("Disabled for this test")
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Disabled for this test")
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Disabled for this test")
    override fun getFilterList(): FilterList = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Minimal preferences for testing
        addRandomUAPreferenceToScreen(screen)
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = this.body.string()
        return json.decodeFromString(json.serializersModule.serializer(), responseBody)
    }

    companion object {
        // Minimal companion object for now
    }
}
