// (Only the class body replaced — use as a full file replacing your previous Koharu.kt)
package eu.kanade.tachiyomi.extension.all.koharu

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.koharu.KoharuFilters.*
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
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    // Default browsing client (no crt).
    override val client: OkHttpClient by lazy {
        val builder = when (preferences.getString(PREF_CLOUDFLARE_SOLVER, "tachiyomi")) {
            "custom" -> {
                toast("Using Custom WebView Solver")
                network.client.newBuilder()
                    .addInterceptor(CloudflareInterceptor())
            }
            else -> {
                toast("Using Tachiyomi's Solver")
                network.cloudflareClient.newBuilder()
            }
        }

        builder
            .setRandomUserAgent(
                userAgentType = preferences.getPrefUAType(),
                customUA = preferences.getPrefCustomUA(),
                filterInclude = listOf("chrome"),
            )
            .rateLimit(3)
            .build()
    }

    // Reading client: appends 'crt' query parameter via interceptor
    private val pageClient: OkHttpClient by lazy {
        client.newBuilder()
            .addInterceptor(::schaleTokenInterceptor)
            .build()
    }

    // Use in-memory + persisted prefs (mirrors localStorage in main.js)
    private val clearanceTokenRef = AtomicReference<String?>(null)
    private val tokenLock = Any()

    @Serializable
    private data class SchaleToken(val token: String)

    private fun schaleTokenInterceptor(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val alreadyHasCrt = originalRequest.url.queryParameter("crt") != null

        val token = try {
            getOrFetchToken()
        } catch (e: IOException) {
            // propagate so callers can handle
            throw e
        }

        val requestWithCrt = if (!alreadyHasCrt && token.isNotBlank()) {
            originalRequest.newBuilder()
                .url(originalRequest.url.newBuilder().addQueryParameter("crt", token).build())
                .build()
        } else originalRequest

        var response = chain.proceed(requestWithCrt)

        if (response.code in listOf(400, 403)) {
            response.close()
            invalidateClearance() // clear persisted + memory (mirrors clear in main.js)
            val newToken = try { getOrFetchToken() } catch (e: IOException) { throw e }
            val retryRequest = if (!alreadyHasCrt && newToken.isNotBlank()) {
                originalRequest.newBuilder()
                    .url(originalRequest.url.newBuilder().addQueryParameter("crt", newToken).build())
                    .build()
            } else originalRequest
            response = chain.proceed(retryRequest)
        }

        return response
    }

    /**
     * This mirrors mr.Clearance.get/must/set/invalidate from the JS bundle:
     * - We persist the token in SharedPreferences (so it survives restarts).
     * - getOrFetchToken checks prefs first, uses in-memory fast path, otherwise fetches from auth/clearance and persists.
     */
    @Throws(IOException::class)
    private fun getOrFetchToken(): String {
        clearanceTokenRef.get()?.let { return it }

        synchronized(tokenLock) {
            clearanceTokenRef.get()?.let { return it }

            // Check persisted token (like localStorage.getItem("clearance"))
            val persisted = preferences.getString(PREF_SCHALE_CLEARANCE, null)
            if (!persisted.isNullOrBlank()) {
                clearanceTokenRef.set(persisted)
                return persisted
            }

            // Not found: fetch from auth endpoint (browsing client)
            val call = try {
                client.newCall(GET("$authUrl/clearance", headers)).execute()
            } catch (e: Exception) {
                throw IOException("Failed to fetch clearance token: ${e.message}", e)
            }

            call.use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Failed to fetch clearance token (HTTP ${resp.code})")
                }

                val bodyStr = resp.body?.string() ?: throw IOException("clearance response empty")
                val parsed = try {
                    json.decodeFromString<SchaleToken>(bodyStr)
                } catch (e: Exception) {
                    throw IOException("Failed to parse clearance token response", e)
                }

                val token = parsed.token
                // persist (mirrors localStorage.setItem("clearance", token))
                preferences.edit().putString(PREF_SCHALE_CLEARANCE, token).apply()
                clearanceTokenRef.set(token)
                return token
            }
        }
    }

    // Clear in-memory + persisted token (mirrors Clearance.invalidate())
    private fun invalidateClearance() {
        clearanceTokenRef.set(null)
        preferences.edit().remove(PREF_SCHALE_CLEARANCE).apply()
    }

    private fun toast(message: String) {
        handler.post {
            Toast.makeText(Injekt.get<Application>(), message, Toast.LENGTH_LONG).show()
        }
    }

    private inner class CloudflareInterceptor : Interceptor {
        @SuppressLint("SetJavaScriptEnabled", "ApplySharedPref")
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)

            if (response.code !in listOf(503, 403) || response.header("Server", "")?.startsWith("cloudflare", true) != true) {
                return response
            }

            val body = try { response.peekBody(Long.MAX_VALUE).string() } catch (e: Exception) { "" }
            if (!body.contains("cf-challenge-running", true)) {
                return response
            }

            response.close()

            try {
                toast("Cloudflare challenge detected. Opening WebView...")
                var webView: WebView? = null
                var newCookie: Cookie? = null

                val userAgent = originalRequest.header("User-Agent")
                    ?: preferences.getPrefCustomUA()?.takeIf { it.isNotBlank() }
                    ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

                val latch = CountDownLatch(1)
                handler.post {
                    val wv = WebView(Injekt.get<Application>())
                    webView = wv
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = userAgent
                    }
                    wv.webViewClient = object : WebViewClient() {}
                    wv.loadUrl(originalRequest.url.toString())
                }

                // Poll cookies for cf_clearance (similar to the JS behavior)
                for (i in 1..20) {
                    Thread.sleep(1000)
                    val cookies = CookieManager.getInstance().getCookie(originalRequest.url.toString())
                    if (cookies != null && "cf_clearance" in cookies) {
                        toast("cf_clearance cookie found!")
                        val clearanceCookieValue = cookies.split(';')
                            .find { it.trim().startsWith("cf_clearance=") }
                            ?.substringAfter("=")?.trim()

                        if (!clearanceCookieValue.isNullOrBlank()) {
                            newCookie = Cookie.Builder()
                                .name("cf_clearance")
                                .value(clearanceCookieValue)
                                .domain(originalRequest.url.host)
                                .path("/")
                                .build()
                            break
                        }
                    }
                }

                handler.post {
                    webView?.stopLoading()
                    webView?.destroy()
                }

                latch.countDown()
                latch.await(5, TimeUnit.SECONDS)

                if (newCookie != null) {
                    toast("Successfully solved challenge. Retrying request...")
                    // Save cookie into the client's cookie jar
                    val clientToUpdate = if (preferences.getString(PREF_CLOUDFLARE_SOLVER, "tachiyomi") == "custom") {
                        client
                    } else {
                        network.cloudflareClient
                    }
                    clientToUpdate.cookieJar.saveFromResponse(originalRequest.url, listOf(newCookie!!))

                    // Now that we solved CF, fetch and persist a fresh clearance token (mirrors main.js flow)
                    invalidateClearance()
                    try {
                        getOrFetchToken()
                    } catch (_: Exception) {
                        // ignore here; subsequent requests will attempt again
                    }

                    return chain.proceed(originalRequest.newBuilder().build())
                } else {
                    toast("Failed to solve Cloudflare challenge: cf_clearance cookie not found.")
                    throw IOException("Failed to solve Cloudflare challenge: cf_clearance cookie not found.")
                }
            } catch (e: Exception) {
                toast("Cloudflare challenge failed: ${e.message}")
                throw IOException("Failed to solve Cloudflare challenge. ${e.message}")
            }
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

        // MUST use pageClient so 'crt' is appended (mirrors Read/Download in main.js)
        val imagesResponse = pageClient.newCall(GET("$apiBooksUrl/data/$entryId/$entryKey/$id/$public_key/$realQuality", headers)).execute()
        return imagesResponse.parseAs<ImagesInfo>() to realQuality
    }

    // browsing requests
    override fun latestUpdatesRequest(page: Int) = GET(
        apiBooksUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            val terms: MutableList<String> = mutableListOf()
            if (lang != "all") terms += "language:\"^$searchLang$\""
            alwaysExcludeTags()?.takeIf { it.isNotBlank() }?.let {
                terms += "tag:\"${it.split(",").joinToString(",") { "-${it.trim()}" }}\""
            }
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
        }.build(),
        headers,
    )

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun popularMangaRequest(page: Int) = GET(
        apiBooksUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "8")
            addQueryParameter("page", page.toString())
            val terms: MutableList<String> = mutableListOf()
            if (lang != "all") terms += "language:\"^$searchLang$\""
            alwaysExcludeTags()?.takeIf { it.isNotBlank() }?.let {
                terms += "tag:\"${it.split(",").joinToString(",") { "-${it.trim()}" }}\""
            }
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
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
            if (lang != "all") terms += "language:\"^$searchLang$\""
            alwaysExcludeTags()?.takeIf { it.isNotBlank() }?.let {
                terms += "tag:\"${it.split(",").joinToString(",") { "-${it.trim()}" }}\""
            }
            filters.forEach { filter ->
                when (filter) {
                    is KoharuFilters.SortFilter -> addQueryParameter("sort", filter.getValue())
                    is KoharuFilters.CategoryFilter -> filter.state.filter { it.state }.let {
                        if (it.isNotEmpty()) addQueryParameter("cat", it.sumOf { tag -> tag.value }.toString())
                    }
                    is KoharuFilters.TagFilter -> {
                        includedTags += filter.state.filter { it.isIncluded() }.map { it.id }
                        excludedTags += filter.state.filter { it.isExcluded() }.map { it.id }
                    }
                    is KoharuFilters.GenreConditionFilter -> if (filter.state > 0) addQueryParameter(filter.param, filter.toUriPart())
                    is KoharuFilters.TextFilter -> filter.state.takeIf { it.isNotEmpty() }?.let {
                        val tags = it.split(",").filter(String::isNotBlank).joinToString(",")
                        if (tags.isNotBlank()) terms += "${filter.type}:${if (filter.type == "pages") tags else "\"$tags\""}"
                    }
                    else -> {}
                }
            }
            if (includedTags.isNotEmpty()) addQueryParameter("include", includedTags.joinToString(","))
            if (excludedTags.isNotEmpty()) addQueryParameter("exclude", excludedTags.joinToString(","))
            if (query.isNotEmpty()) terms.add("title:\"$query\"")
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
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
            title = if (remadd()) mangaDetail.title.shortenTitle() else mangaDetail.title
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

    // Entry point for reading: MUST use pageClient so 'crt' is provided as in main.js
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

    // imageRequest: if the image's host is API/auth domain, pageClient must be used to execute call
    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl ?: throw IOException("Missing image URL")
        // Tachiyomi expects a Request object; the actual network client that executes the request
        // is chosen by Tachiyomi runtime. We return GET(url, headers) here; pageClient handles crt when calling API endpoints earlier.
        return GET(url, headers)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_CLOUDFLARE_SOLVER
            title = "Cloudflare Solver"
            entries = arrayOf("Tachiyomi's Solver (Recommended)", "Custom WebView Solver")
            entryValues = arrayOf("tachiyomi", "custom")
            summary = "%s\n\nTachiyomi's solver is stable and handles Cloudflare automatically. The custom solver is for debugging and may be unstable."
            setDefaultValue("tachiyomi")
        }.also(screen::addPreference)

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
        private const val PREF_CLOUDFLARE_SOLVER = "pref_cloudflare_solver"
        private const val PREF_IMAGERES = "pref_image_quality"
        private const val PREF_REM_ADD = "pref_remove_additional"
        private const val PREF_EXCLUDE_TAGS = "pref_exclude_tags"
        private const val PREF_SCHALE_CLEARANCE = "pref_schale_clearance" // persisted clearance token (mirrors localStorage)
        internal val dateReformat = SimpleDateFormat("EEEE, d MMM yyyy HH:mm (z)", Locale.ENGLISH)
    }
}
