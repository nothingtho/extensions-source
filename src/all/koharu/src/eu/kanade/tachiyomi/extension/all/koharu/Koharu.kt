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
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
object DebugWebView {
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

                // Sync cookies from the app's client to the WebView
                source.client.cookieJar.loadForRequest(url.toHttpUrl()).forEach {
                    cookieManager.setCookie(url, it.toString())
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
                    .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }
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
    private val apiBooksUrl = "$apiUrl/books"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by getPreferencesLazy()

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = replace(shortenTitleRegex, "").trim()
    private fun quality() = preferences.getString(PREF_IMAGERES, "1280")!!
    private fun remadd() = preferences.getBoolean(PREF_REM_ADD, false)
    private fun alwaysExcludeTags() = preferences.getString(PREF_EXCLUDE_TAGS, "")

    private var _domainUrl: String? = null
    private val domainUrl: String
        get() = _domainUrl ?: run {
            val domain = getDomain()
            _domainUrl = domain
            domain
        }

    private fun getDomain(): String {
        try {
            val noRedirectClient = network.client.newBuilder().followRedirects(false).build()
            val host = noRedirectClient.newCall(GET(baseUrl, Headers.Builder().build())).execute()
                .headers["Location"]?.toHttpUrlOrNull()?.host
                ?: return baseUrl
            return "https://$host"
        } catch (_: Exception) {
            return baseUrl
        }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$domainUrl/")
        .set("Origin", domainUrl)

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

    // --- Start of Stripped-Down Code ---

    // LATEST UPDATES & POPULAR: These will now serve as our trigger for the debug WebView.
    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        // We throw an exception here on purpose. This will show the "Open in WebView" button.
        // After solving in WebView, refreshing again will trigger fetchMangaDetails.
        throw IOException("Please solve Cloudflare in WebView first, then refresh again to open the debug WebView.")
    }
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // DETAILS: This is now the entry point for our test.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        // Instead of fetching details, we launch our debug WebView.
        DebugWebView.openInDebugWebView(this, "$baseUrl${manga.url}")
        // Return an empty observable because we are not actually fetching details.
        return Observable.empty()
    }

    // --- All other functions are disabled for this test ---

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException("Disabled for debug")
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Disabled for debug")
    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException("Disabled for debug")
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Disabled for debug")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.error(UnsupportedOperationException("Disabled for debug"))
    override fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException("Disabled for debug")
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Disabled for debug")
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Disabled for debug")

    // --- Boilerplate and Settings (unchanged) ---

    override fun getFilterList(): FilterList = FilterList() // Disabled for now
    private val scope = CoroutineScope(Dispatchers.IO)
    private fun launchIO(block: () -> Unit) = scope.launch { block() }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_IMAGERES
            title = "Image Resolution"
            entries = arrayOf("780x", "980x", "1280x", "1600x", "Original")
            entryValues = arrayOf("780", "980", "1280", "1600", "0")
            summary = "%s"
            setDefaultValue("1280")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_REM_ADD
            title = "Remove additional information in title"
            summary = "Remove anything in brackets from manga titles."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_EXCLUDE_TAGS
            title = "Tags to exclude from browse/search"
            summary = "Separate tags with commas (,)."
        }.also(screen::addPreference)

        addRandomUAPreferenceToScreen(screen)
    }

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())

    companion object {
        const val PREFIX_ID_KEY_SEARCH = "id:"
        private const val PREF_IMAGERES = "pref_image_quality"
        private const val PREF_REM_ADD = "pref_remove_additional"
        private const val PREF_EXCLUDE_TAGS = "pref_exclude_tags"
        internal val dateReformat = SimpleDateFormat("EEEE, d MMM yyyy HH:mm (z)", Locale.ENGLISH)
    }
}
