package eu.kanade.tachiyomi.extension.all.koharu

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.artistList
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.circleList
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.femaleList
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.genreList
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.getFilters
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.maleList
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.mixedList
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.otherList
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.parodyList
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.tagsFetchAttempts
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.tagsFetched
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty

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
    private val preferences: SharedPreferences by lazy { Injekt.get<Application>().getSharedPreferences("source_$id", 0) }
    private val application: Application by injectLazy()

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    override fun headersBuilder() = super.headersBuilder().set("User-Agent", userAgent)

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = replace(shortenTitleRegex, "").trim()
    private fun quality() = preferences.getString(PREF_IMAGERES, "1280")!!
    private fun remadd() = preferences.getBoolean(PREF_REM_ADD, false)
    private fun alwaysExcludeTags() = preferences.getString(PREF_EXCLUDE_TAGS, "")
    private fun autoCrt() = preferences.getBoolean(PREF_AUTO_CRT, false)

    private var _domainUrl: String? = null
    private val domainUrl: String
        get() = _domainUrl ?: run {
            val domain = try {
                val host = network.client.newCall(GET(baseUrl, headers)).execute().request.url.host
                "https://$host"
            } catch (_: Exception) {
                baseUrl
            }
            _domainUrl = domain
            domain
        }

    private fun apiHeaders(): Headers {
        return headersBuilder()
            .set("Referer", "$domainUrl/")
            .set("Origin", domainUrl)
            .build()
    }

    private val webViewCookieJar = WebViewCookieJar()

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .cookieJar(webViewCookieJar)
            .addInterceptor(ClearanceInterceptor())
            .rateLimit(3)
            .build()
    }

    private inner class ClearanceInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()

            val crt = preferences.getString(PREF_CRT_TOKEN, null)
            val requestWithCrt = if (crt != null && originalRequest.url.toString().startsWith(apiUrl)) {
                val newUrl = originalRequest.url.newBuilder()
                    .addQueryParameter("crt", crt)
                    .build()
                originalRequest.newBuilder().url(newUrl).build()
            } else {
                originalRequest
            }

            val response = chain.proceed(requestWithCrt)

            when {
                response.code in listOf(503, 403) && response.header("Server")?.startsWith("cloudflare") == true -> {
                    response.close()
                    throw IOException("Cloudflare challenge required. Please open in WebView, solve the puzzle, and then retry.")
                }
                response.code in listOf(401, 403) && requestWithCrt.url.toString().startsWith(apiUrl) -> {
                    response.close()
                    if (autoCrt()) {
                        val newCrt = fetchCrtTokenViaWebView()
                        val newRequest = originalRequest.newBuilder()
                            .url(originalRequest.url.newBuilder().addQueryParameter("crt", newCrt).build())
                            .build()
                        return chain.proceed(newRequest)
                    } else {
                        throw IOException("Clearance token is invalid or expired. Please open in WebView, wait for the page to load, and then retry.")
                    }
                }
            }

            return response
        }

        @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
        @Synchronized
        private fun fetchCrtTokenViaWebView(): String {
            preferences.edit().remove(PREF_CRT_TOKEN).apply()

            val latch = CountDownLatch(1)
            var token: String? = null

            class JsObject {
                @JavascriptInterface
                fun onToken(receivedToken: String?) {
                    if (receivedToken != null && receivedToken.isNotEmpty() && receivedToken != "null") {
                        token = receivedToken
                        preferences.edit().putString(PREF_CRT_TOKEN, token).apply()
                        latch.countDown()
                    }
                }
            }

            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val webView = WebView(application)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = this@Koharu.userAgent
                }
                webView.addJavascriptInterface(JsObject(), "Android")
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        if (url == "$domainUrl/") {
                            view.evaluateJavascript(
                                """
                                (function() {
                                    const originalSetItem = localStorage.setItem;
                                    localStorage.setItem = function(key, value) {
                                        if (key === 'clearance' && window.Android && typeof window.Android.onToken === 'function') {
                                            window.Android.onToken(value);
                                        }
                                        originalSetItem.apply(this, arguments);
                                    };
                                    const token = localStorage.getItem('clearance');
                                    if (token) {
                                        window.Android.onToken(token);
                                    }
                                })();
                                """.trimIndent(),
                            ) {}
                        }
                    }
                }
                Toast.makeText(application, "Attempting to get clearance token automatically...", Toast.LENGTH_SHORT).show()
                webView.loadUrl(domainUrl)
            }

            latch.await(45, TimeUnit.SECONDS)

            return token ?: throw IOException("Failed to get clearance token automatically. Try solving manually in WebView.")
        }
    }

    private fun getManga(book: Entry) = SManga.create().apply {
        setUrlWithoutDomain("${book.id}/${book.key}")
        title = if (remadd()) book.title.shortenTitle() else book.title
        thumbnail_url = book.thumbnail.path
    }

    private fun getImagesByMangaData(entry: MangaData, entryId: String, entryKey: String): Pair<ImagesInfo, String> {
        val data = entry.data
        fun getIPK(
            ori: DataKey?,
            alt1: DataKey?,
            alt2: DataKey?,
            alt3: DataKey?,
            alt4: DataKey?,
        ): Pair<Int?, String?> {
            return Pair(
                ori?.id ?: alt1?.id ?: alt2?.id ?: alt3?.id ?: alt4?.id,
                ori?.key ?: alt1?.key ?: alt2?.key ?: alt3?.key ?: alt4?.key,
            )
        }
        val (id, public_key) = when (quality()) {
            "1600" -> getIPK(data.`1600`, data.`1280`, data.`0`, data.`980`, data.`780`)
            "1280" -> getIPK(data.`1280`, data.`1600`, data.`0`, data.`980`, data.`780`)
            "980" -> getIPK(data.`980`, data.`1280`, data.`0`, data.`1600`, data.`780`)
            "780" -> getIPK(data.`780`, data.`980`, data.`0`, data.`1280`, data.`1600`)
            else -> getIPK(data.`0`, data.`1600`, data.`1280`, data.`980`, data.`780`)
        }

        if (id == null || public_key == null) {
            throw Exception("No Images Found")
        }

        val realQuality = when (id) {
            data.`1600`?.id -> "1600"
            data.`1280`?.id -> "1280"
            data.`980`?.id -> "980"
            data.`780`?.id -> "780"
            else -> "0"
        }

        val imagesResponse = client.newCall(GET("$apiBooksUrl/data/$entryId/$entryKey/$id/$public_key/$realQuality", apiHeaders())).execute()
        return imagesResponse.parseAs<ImagesInfo>() to realQuality
    }

    override fun latestUpdatesRequest(page: Int) = GET(
        apiBooksUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())

            val terms: MutableList<String> = mutableListOf()
            if (lang != "all") terms += "language:\"^$searchLang$\""
            val alwaysExcludeTags = alwaysExcludeTags()?.split(",")
                ?.map { it.trim() }?.filter(String::isNotBlank) ?: emptyList()
            if (alwaysExcludeTags.isNotEmpty()) {
                terms += "tag:\"${alwaysExcludeTags.joinToString(",") { "-$it" }}\""
            }
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
        }.build(),
        apiHeaders(),
    )

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun popularMangaRequest(page: Int) = GET(
        apiBooksUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "8")
            addQueryParameter("page", page.toString())

            val terms: MutableList<String> = mutableListOf()
            if (lang != "all") terms += "language:\"^$searchLang$\""
            val alwaysExcludeTags = alwaysExcludeTags()?.split(",")
                ?.map { it.trim() }?.filter(String::isNotBlank) ?: emptyList()
            if (alwaysExcludeTags.isNotEmpty()) {
                terms += "tag:\"${alwaysExcludeTags.joinToString(",") { "-$it" }}\""
            }
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
        }.build(),
        apiHeaders(),
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<Books>()
        return MangasPage(data.entries.map(::getManga), data.page * data.limit < data.total)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PREFIX_ID_KEY_SEARCH) -> {
                val ipk = query.removePrefix(PREFIX_ID_KEY_SEARCH)
                client.newCall(GET("$apiBooksUrl/detail/$ipk", apiHeaders()))
                    .asObservableSuccess()
                    .map { response ->
                        MangasPage(listOf(mangaDetailsParse(response)), false)
                    }
            }
            else -> super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = apiBooksUrl.toHttpUrl().newBuilder().apply {
            val terms: MutableList<String> = mutableListOf()
            val includedTags: MutableList<Int> = mutableListOf()
            val excludedTags: MutableList<Int> = mutableListOf()

            if (lang != "all") terms += "language:\"^$searchLang$\""
            val alwaysExcludeTags = alwaysExcludeTags()?.split(",")
                ?.map { it.trim() }?.filter(String::isNotBlank) ?: emptyList()
            if (alwaysExcludeTags.isNotEmpty()) {
                terms += "tag:\"${alwaysExcludeTags.joinToString(",") { "-$it" }}\""
            }

            filters.forEach { filter ->
                when (filter) {
                    is KoharuFilters.SortFilter -> addQueryParameter("sort", filter.getValue())
                    is KoharuFilters.CategoryFilter -> {
                        val activeFilter = filter.state.filter { it.state }
                        if (activeFilter.isNotEmpty()) {
                            addQueryParameter("cat", activeFilter.sumOf { it.value }.toString())
                        }
                    }
                    is KoharuFilters.TagFilter -> {
                        includedTags += filter.state
                            .filter { it.isIncluded() }
                            .map { it.id }
                        excludedTags += filter.state
                            .filter { it.isExcluded() }
                            .map { it.id }
                    }
                    is KoharuFilters.GenreConditionFilter -> {
                        if (filter.state > 0) {
                            addQueryParameter(filter.param, filter.toUriPart())
                        }
                    }
                    is KoharuFilters.TextFilter -> {
                        if (filter.state.isNotEmpty()) {
                            val tags = filter.state.split(",").filter(String::isNotBlank).joinToString(",")
                            if (tags.isNotBlank()) {
                                terms += "${filter.type}:" + if (filter.type == "pages") tags else "\"$tags\""
                            }
                        }
                    }
                    else -> {}
                }
            }

            if (includedTags.isNotEmpty()) {
                addQueryParameter("include", includedTags.joinToString(","))
            }
            if (excludedTags.isNotEmpty()) {
                addQueryParameter("exclude", excludedTags.joinToString(","))
            }

            if (query.isNotEmpty()) terms.add("title:\"$query\"")
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, apiHeaders())
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun getFilterList(): FilterList {
        launchIO { fetchTags() }
        return getFilters()
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private fun launchIO(block: () -> Unit) = scope.launch { block() }

    private fun fetchTags() {
        if (tagsFetchAttempts < 3 && !tagsFetched) {
            try {
                client.newCall(GET("$apiBooksUrl/tags/filters", apiHeaders())).execute().use { response ->
                    if (!response.isSuccessful) return
                    response.parseAs<List<Filter>>()
                        .also { tagsFetched = true }
                        .takeIf { it.isNotEmpty() }
                        ?.map { it.toTag() }
                        ?.also { tags ->
                            genreList = tags.filterIsInstance<KoharuFilters.Genre>()
                            femaleList = tags.filterIsInstance<KoharuFilters.Female>()
                            maleList = tags.filterIsInstance<KoharuFilters.Male>()
                            artistList = tags.filterIsInstance<KoharuFilters.Artist>()
                            circleList = tags.filterIsInstance<KoharuFilters.Circle>()
                            parodyList = tags.filterIsInstance<KoharuFilters.Parody>()
                            mixedList = tags.filterIsInstance<KoharuFilters.Mixed>()
                            otherList = tags.filterIsInstance<KoharuFilters.Other>()
                        }
                }
            } catch (_: Exception) {
            } finally {
                tagsFetchAttempts++
            }
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiBooksUrl/detail/${manga.url}", apiHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaDetail = response.parseAs<MangaDetail>()
        return mangaDetail.toSManga().apply {
            setUrlWithoutDomain("${mangaDetail.id}/${mangaDetail.key}")
            title = if (remadd()) mangaDetail.title.shortenTitle() else mangaDetail.title
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${manga.url}"

    override fun chapterListRequest(manga: SManga): Request = GET("$apiBooksUrl/detail/${manga.url}", apiHeaders())

    override fun chapterListParse(response: Response): List<SChapter> {
        val manga = response.parseAs<MangaDetail>()
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                url = "${manga.id}/${manga.key}"
                date_upload = (manga.updated_at ?: manga.created_at)
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/g/${chapter.url}"

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map(::pageListParse)
    }

    override fun pageListRequest(chapter: SChapter): Request = POST("$apiBooksUrl/detail/${chapter.url}", apiHeaders())

    override fun pageListParse(response: Response): List<Page> {
        val mangaData = response.parseAs<MangaData>()
        val url = response.request.url.toString()
        val matches = Regex("""/detail/(\d+)/([a-z\d]+)""").find(url)
            ?: return emptyList()
        val (entryId, entryKey) = matches.destructured
        val imagesInfo = getImagesByMangaData(mangaData, entryId, entryKey)

        return imagesInfo.first.entries.mapIndexed { index, image ->
            Page(index, imageUrl = "${imagesInfo.first.base}/${image.path}?w=${imagesInfo.second}")
        }
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, apiHeaders())

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

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
            summary = "Remove anything in brackets from manga titles.\n" +
                "Reload manga to apply changes to loaded manga."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_EXCLUDE_TAGS
            title = "Tags to exclude from browse/search"
            summary = "Separate tags with commas (,).\n" +
                "Excluding: ${alwaysExcludeTags()}"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTO_CRT
            title = "Automated clearance"
            summary = "When enabled, the extension will try to get the clearance token automatically in the background. If disabled, you will have to manually use WebView when the token expires."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())

    companion object {
        const val PREFIX_ID_KEY_SEARCH = "id:"
        private const val PREF_IMAGERES = "pref_image_quality"
        private const val PREF_REM_ADD = "pref_remove_additional"
        private const val PREF_EXCLUDE_TAGS = "pref_exclude_tags"
        private const val PREF_AUTO_CRT = "pref_auto_crt"
        private const val PREF_CRT_TOKEN = "pref_clearance_token"
        internal val dateReformat = SimpleDateFormat("EEEE, d MMM yyyy HH:mm (z)", Locale.ENGLISH)
    }
}

class WebViewCookieJar : okhttp3.CookieJar {
    private val cookieManager = CookieManager.getInstance()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()
        cookies.forEach { cookie ->
            cookieManager.setCookie(urlString, cookie.toString())
        }
        cookieManager.flush()
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
        val cookiesString = cookieManager.getCookie(url.toString())
        return if (cookiesString != null && cookiesString.isNotEmpty()) {
            cookiesString.split(";").mapNotNull { cookieString ->
                Cookie.parse(url, cookieString.trim())
            }
        } else {
            emptyList()
        }
    }
}
