package eu.kanade.tachiyomi.extension.all.koharu

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private var crtToken: String? = null
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
            val host = noRedirectClient.newCall(GET(baseUrl, headers)).execute()
                .headers["Location"]?.toHttpUrlOrNull()?.host
                ?: return baseUrl
            return "https://$host"
        } catch (_: Exception) {
            return baseUrl
        }
    }

    private val lazyHeaders by lazy {
        headersBuilder()
            .set("Referer", "$domainUrl/")
            .set("Origin", domainUrl)
            .build()
    }

    private val webViewCookieJar = WebViewCookieJar()

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .cookieJar(webViewCookieJar)
            .setRandomUserAgent(
                userAgentType = preferences.getPrefUAType(),
                customUA = preferences.getPrefCustomUA(),
                filterInclude = listOf("chrome"),
            )
            .addInterceptor(CrtInterceptor())
            .addInterceptor(CloudflareInterceptor())
            .rateLimit(3)
            .build()
    }

    private inner class CrtInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = crtToken ?: return chain.proceed(chain.request())

            val originalUrl = chain.request().url
            if (!originalUrl.toString().startsWith(apiUrl)) {
                return chain.proceed(chain.request())
            }

            val newUrl = originalUrl.newBuilder()
                .addQueryParameter("crt", token)
                .build()

            val newRequest = chain.request().newBuilder()
                .url(newUrl)
                .build()

            return chain.proceed(newRequest)
        }
    }

    private inner class CloudflareInterceptor : Interceptor {
        private val handler = Handler(Looper.getMainLooper())

        @SuppressLint("SetJavaScriptEnabled")
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)

            // Check if Cloudflare protection is active
            if (response.code !in listOf(503, 403) || !response.header("Server", "").equals("cloudflare", true)) {
                return response
            }

            response.close()
            try {
                var webView: WebView? = null
                val latch = CountDownLatch(1)

                handler.post {
                    val wv = WebView(Injekt.get<Application>())
                    webView = wv
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                    }
                    CookieManager.getInstance().setAcceptCookie(true)

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val cookies = CookieManager.getInstance().getCookie(url)
                            if (cookies != null && "cf_clearance" in cookies) {
                                val clearanceCookie = cookies.split(';').find { it.trim().startsWith("cf_clearance=") }
                                if (clearanceCookie != null) {
                                    crtToken = clearanceCookie.substringAfter("=").trim()
                                    latch.countDown()
                                }
                            }
                        }
                    }
                    wv.loadUrl(originalRequest.url.toString())
                }

                // Wait for the WebView to solve the challenge
                latch.await(60, TimeUnit.SECONDS)

                handler.post {
                    webView?.stopLoading()
                    webView?.destroy()
                }
            } catch (e: Exception) {
                throw IOException("Failed to solve Cloudflare challenge. ${e.message}")
            }

            // Retry the original request, now with the cookies from the WebView
            return chain.proceed(originalRequest)
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

        val imagesResponse = client.newCall(GET("$apiBooksUrl/data/$entryId/$entryKey/$id/$public_key/$realQuality", lazyHeaders)).execute()
        val images = imagesResponse.parseAs<ImagesInfo>() to realQuality
        return images
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
        lazyHeaders,
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
        lazyHeaders,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<Books>()
        return MangasPage(data.entries.map(::getManga), data.page * data.limit < data.total)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PREFIX_ID_KEY_SEARCH) -> {
                val ipk = query.removePrefix(PREFIX_ID_KEY_SEARCH)
                val response = client.newCall(GET("$apiBooksUrl/detail/$ipk", lazyHeaders)).execute()
                Observable.just(
                    MangasPage(listOf(mangaDetailsParse(response)), false),
                )
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

        return GET(url, lazyHeaders)
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
                client.newCall(
                    GET("$apiBooksUrl/tags/filters", lazyHeaders),
                ).execute()
                    .use { it.parseAs<List<Filter>>() }
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
            } catch (_: Exception) {
            } finally {
                tagsFetchAttempts++
            }
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiBooksUrl/detail/${manga.url}", lazyHeaders)

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaDetail = response.parseAs<MangaDetail>()
        return mangaDetail.toSManga().apply {
            setUrlWithoutDomain("${mangaDetail.id}/${mangaDetail.key}")
            title = if (remadd()) mangaDetail.title.shortenTitle() else mangaDetail.title
        }
    }

    // FIX for 'overrides nothing' compilation error
    override fun fetchSimilarManga(manga: SManga): Observable<MangasPage> {
        // The API doesn't provide a list of related manga.
        // Return an empty page to prevent the app from crashing.
        return Observable.just(MangasPage(emptyList(), false))
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${manga.url}"

    override fun chapterListRequest(manga: SManga): Request = GET("$apiBooksUrl/detail/${manga.url}", lazyHeaders)

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

    override fun pageListRequest(chapter: SChapter): Request = POST("$apiBooksUrl/detail/${chapter.url}", lazyHeaders)

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

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, lazyHeaders)

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

class WebViewCookieJar : okhttp3.CookieJar {
    private val cookieManager = CookieManager.getInstance()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        val urlString = url.toString()
        cookies.forEach { cookie ->
            cookieManager.setCookie(urlString, cookie.toString())
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        val cookies = cookieManager.getCookie(url.toString())
        return if (cookies != null && cookies.isNotEmpty()) {
            cookies.split(";").mapNotNull { okhttp3.Cookie.parse(url, it.trim()) }
        } else {
            emptyList()
        }
    }
}
