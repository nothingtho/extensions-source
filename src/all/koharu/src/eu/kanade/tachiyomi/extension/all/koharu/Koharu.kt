package eu.kanade.tachiyomi.extension.all.koharu

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
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
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.concurrent.atomic.AtomicReference

class Koharu(
    override val lang: String = "all",
    private val searchLang: String = "",
) : HttpSource(), ConfigurableSource {

    override val name = "Niyaniya"
    override val baseUrl = "https://niyaniya.moe"
    override val id = if (lang == "en") 1484902275639232927 else super.id
    private val apiUrl = "https://api.schale.network"
    private val authUrl = "https://auth.schale.network"
    private val apiBooksUrl = "$apiUrl/books"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by getPreferencesLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

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

    private val pageClient: OkHttpClient by lazy {
        client.newBuilder()
            .addInterceptor(::schaleTokenInterceptor)
            .build()
    }

    private val clearanceTokenRef = AtomicReference<String?>(null)
    private val tokenLock = Any()

    @Serializable private data class SchaleToken(val token: String)
    @Serializable private data class SchaleError(val error: String)

    private fun schaleTokenInterceptor(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        if (originalRequest.url.queryParameter("crt") != null) {
            return chain.proceed(originalRequest)
        }

        val token = try {
            getOrFetchToken()
        } catch (e: Exception) {
            handler.post { Toast.makeText(Injekt.get<Application>(), "Clearance Error: ${e.message}", Toast.LENGTH_LONG).show() }
            throw e
        }

        val requestWithCrt = originalRequest.newBuilder()
            .url(originalRequest.url.newBuilder().addQueryParameter("crt", token).build())
            .build()

        var response = chain.proceed(requestWithCrt)

        if (response.code in listOf(401, 403)) {
            response.close()
            clearanceTokenRef.set(null)

            val newToken = try {
                getOrFetchToken()
            } catch (e: IOException) {
                throw e
            }

            val retryRequest = originalRequest.newBuilder()
                .url(originalRequest.url.newBuilder().addQueryParameter("crt", newToken).build())
                .build()

            response = chain.proceed(retryRequest)
        }

        return response
    }

    @Throws(IOException::class)
    private fun getOrFetchToken(): String {
        clearanceTokenRef.get()?.let { return it }

        synchronized(tokenLock) {
            clearanceTokenRef.get()?.let { return it }

            toast("Schale requires clearance. Solving challenge...")
            try {
                val turnstileToken = ClearanceWebView().solve()
                val clearanceRequest = POST(
                    "$authUrl/clearance",
                    headers,
                    body = "".toRequestBody(null),
                ).newBuilder()
                    .header("Authorization", "Bearer $turnstileToken")
                    .build()

                // Use the main client, which has the cf_clearance cookie from manual solving
                val clearanceResponse = client.newCall(clearanceRequest).execute()
                if (!clearanceResponse.isSuccessful) {
                    val errorBody = clearanceResponse.body.string().orEmpty()
                    val errorMsg = try { json.decodeFromString<SchaleError>(errorBody).error } catch (_: Exception) { errorBody }
                    throw IOException("Failed to exchange Turnstile token. Server said: $errorMsg (HTTP ${clearanceResponse.code})")
                }

                val body = clearanceResponse.body.string()
                val schaleToken = json.decodeFromString<SchaleToken>(body).token
                clearanceTokenRef.set(schaleToken)
                toast("Clearance obtained!")
                return schaleToken
            } catch (e: Exception) {
                throw IOException("Failed to solve clearance challenge: ${e.message}", e)
            }
        }
    }

    private fun toast(message: String) {
        handler.post {
            Toast.makeText(Injekt.get<Application>(), message, Toast.LENGTH_LONG).show()
        }
    }

    private inner class ClearanceWebView {
        private val latch = CountDownLatch(1)
        private var turnstileToken: String? = null

        private val jsToInject = """
            (function() {
                if (window.turnstileCallbackInjected) return;
                window.turnstileCallbackInjected = true;
                const originalRender = window.turnstile.render;
                window.turnstile.render = function(container, params) {
                    const originalCallback = params.callback;
                    params.callback = function(token) {
                        JSInterface.receiveToken(token);
                        if (originalCallback) originalCallback(token);
                    };
                    return originalRender(container, params);
                };
            })();
        """.trimIndent()

        private val checkJs = "setInterval(() => { if(window.turnstile) { $jsToInject } }, 250);"

        @SuppressLint("SetJavaScriptEnabled")
        fun solve(): String {
            var webView: WebView? = null
            val userAgent = client.newCall(GET(baseUrl, headers)).execute().request.header("User-Agent")!!

            handler.post {
                val wv = WebView(Injekt.get<Application>())
                webView = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = userAgent
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                wv.addJavascriptInterface(JsCallback(), "JSInterface")
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(checkJs, null)
                    }
                }
                wv.loadUrl("$authUrl/login")
            }

            latch.await(60, TimeUnit.SECONDS)

            handler.post {
                webView?.stopLoading()
                webView?.destroy()
            }

            return turnstileToken ?: throw IOException("Could not solve Turnstile challenge (timed out).")
        }

        inner class JsCallback {
            @JavascriptInterface
            fun receiveToken(token: String) {
                if (turnstileToken == null) {
                    turnstileToken = token
                    latch.countDown()
                }
            }
        }
    }

    private fun getManga(book: Entry) = SManga.create().apply {
        setUrlWithoutDomain("${book.id}/${book.key}")
        title = if (remadd()) {
            book.title.shortenTitle()
        } else {
            book.title
        }
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

        val imagesResponse = pageClient.newCall(GET("$apiBooksUrl/data/$entryId/$entryKey/$id/$public_key/$realQuality", headers)).execute()
        return imagesResponse.parseAs<ImagesInfo>() to realQuality
    }

    override fun latestUpdatesRequest(page: Int) = GET(
        apiBooksUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            val terms: MutableList<String> = mutableListOf()
            if (lang != "all") {
                terms += "language:\"^$searchLang$\""
            }
            alwaysExcludeTags()?.takeIf { it.isNotBlank() }?.let {
                terms += "tag:\"${it.split(",").joinToString(",") { "-${it.trim()}" }}\""
            }
            if (terms.isNotEmpty()) {
                addQueryParameter("s", terms.joinToString(" "))
            }
        }.build(),
        headers,
    )

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun popularMangaRequest(page: Int) = GET(
        apiBooksUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "8")
            addQueryParameter("page", page.toString())
            val terms: MutableList<String> = mutableListOf()
            if (lang != "all") {
                terms += "language:\"^$searchLang$\""
            }
            alwaysExcludeTags()?.takeIf { it.isNotBlank() }?.let {
                terms += "tag:\"${it.split(",").joinToString(",") { "-${it.trim()}" }}\""
            }
            if (terms.isNotEmpty()) {
                addQueryParameter("s", terms.joinToString(" "))
            }
        }.build(),
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<Books>()
        return MangasPage(data.entries.map(::getManga), data.page * data.limit < data.total)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID_KEY_SEARCH)) {
            val ipk = query.removePrefix(PREFIX_ID_KEY_SEARCH)
            client.newCall(GET("$apiBooksUrl/detail/$ipk", headers))
                .asObservableSuccess()
                .map { MangasPage(listOf(mangaDetailsParse(it)), false) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = apiBooksUrl.toHttpUrl().newBuilder().apply {
            val terms: MutableList<String> = mutableListOf()
            val includedTags: MutableList<Int> = mutableListOf()
            val excludedTags: MutableList<Int> = mutableListOf()
            if (lang != "all") {
                terms += "language:\"^$searchLang$\""
            }
            alwaysExcludeTags()?.takeIf { it.isNotBlank() }?.let {
                terms += "tag:\"${it.split(",").joinToString(",") { "-${it.trim()}" }}\""
            }
            filters.forEach { filter ->
                when (filter) {
                    is KoharuFilters.SortFilter -> addQueryParameter("sort", filter.getValue())
                    is KoharuFilters.CategoryFilter -> filter.state.filter { it.state }.let {
                        if (it.isNotEmpty()) {
                            addQueryParameter("cat", it.sumOf { tag -> tag.value }.toString())
                        }
                    }
                    is KoharuFilters.TagFilter -> {
                        includedTags += filter.state.filter { it.isIncluded() }.map { it.id }
                        excludedTags += filter.state.filter { it.isExcluded() }.map { it.id }
                    }
                    is KoharuFilters.GenreConditionFilter -> if (filter.state > 0) {
                        addQueryParameter(filter.param, filter.toUriPart())
                    }
                    is KoharuFilters.TextFilter -> filter.state.takeIf { it.isNotEmpty() }?.let {
                        val tags = it.split(",").filter(String::isNotBlank).joinToString(",")
                        if (tags.isNotBlank()) {
                            terms += "${filter.type}:${if (filter.type == "pages") tags else "\"$tags\""}"
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
            if (query.isNotEmpty()) {
                terms.add("title:\"$query\"")
            }
            if (terms.isNotEmpty()) {
                addQueryParameter("s", terms.joinToString(" "))
            }
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
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
                client.newCall(GET("$apiBooksUrl/tags/filters", headers))
                    .execute()
                    .use { it.parseAs<List<Filter>>() }
                    .also { tagsFetched = true }
                    .takeIf { it.isNotEmpty() }
                    ?.map(Filter::toTag)
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
            } catch (_: Exception) {
            } finally {
                tagsFetchAttempts++
            }
        }
    }

    override fun mangaDetailsRequest(manga: SManga) = GET("$apiBooksUrl/detail/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaDetail = response.parseAs<MangaDetail>()
        return mangaDetail.toSManga().apply {
            setUrlWithoutDomain("${mangaDetail.id}/${mangaDetail.key}")
            title = if (remadd()) {
                mangaDetail.title.shortenTitle()
            } else {
                mangaDetail.title
            }
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${manga.url}"

    override fun chapterListRequest(manga: SManga): Request = GET("$apiBooksUrl/detail/${manga.url}", headers)

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
        return pageClient.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map(::pageListParse)
    }

    override fun pageListRequest(chapter: SChapter): Request = POST("$apiBooksUrl/detail/${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val mangaData = response.parseAs<MangaData>()
        val urlString = response.request.url.toString()
        val matches = Regex("""/detail/(\d+)/([a-z\d]+)""").find(urlString)
            ?: throw IOException("Failed to parse URL: $urlString")
        val (entryId, entryKey) = matches.destructured
        val imagesInfo = getImagesByMangaData(mangaData, entryId, entryKey)

        return imagesInfo.first.entries.mapIndexed { index, image ->
            Page(index, imageUrl = "${imagesInfo.first.base}/${image.path}?w=${imagesInfo.second}")
        }
    }

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
