package eu.kanade.tachiyomi.extension.all.koharu

import android.content.SharedPreferences
import android.util.Log
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
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

// This is a temporary helper object for debugging Cloudflare clearance.
// Its only purpose is to verify if the cf_clearance cookie is being
// correctly saved to the client's cookie jar after the user solves
// the challenge in WebView.
object CloudflareHelper {
    private const val TAG = "KOHARU_CF_DEBUG"

    // A flag to prevent this check from running on every single request.
    private var isClearanceChecked = false

    fun checkAndLogClearance(source: HttpSource) {
        if (isClearanceChecked) return

        Log.d(TAG, "======================================================================")
        Log.d(TAG, "  STARTING CLOUDFLARE SESSION CHECK")
        Log.d(TAG, "======================================================================")

        val client = source.client
        // Correctly access public headers property, not the protected builder method
        val headers = source.headers

        try {
            Log.d(TAG, "Attempting to connect to baseUrl to check for Cloudflare block...")
            val response = client.newCall(GET(source.baseUrl, headers)).execute()

            if (response.isSuccessful) {
                Log.d(TAG, "Request was successful (HTTP ${response.code}). We are NOT blocked by Cloudflare.")
                Log.d(TAG, "Now, let's inspect the cookies...")

                val cookies = client.cookieJar.loadForRequest(source.baseUrl.toHttpUrl())
                if (cookies.isEmpty()) {
                    Log.w(TAG, "!!! WARNING: Request was successful, but NO cookies were found in the jar for this domain.")
                } else {
                    Log.d(TAG, "Cookies found in jar for ${source.baseUrl.toHttpUrl().host}:")
                    var foundClearance = false
                    cookies.forEach { cookie ->
                        Log.d(TAG, "  - ${cookie.name} = ${cookie.value}")
                        if (cookie.name == "cf_clearance") {
                            Log.d(TAG, "  >>>>>>>>>> SUCCESS! cf_clearance FOUND: ${cookie.value} <<<<<<<<<<")
                            foundClearance = true
                        }
                    }
                    if (!foundClearance) {
                        Log.e(TAG, "  !!!!!!!!!! FAILURE: Request was successful, but cf_clearance cookie was NOT FOUND. !!!!!!!!!!!")
                    } else {
                        isClearanceChecked = true // Mark as checked only on full success
                    }
                }
            } else {
                Log.e(TAG, "Request FAILED with HTTP ${response.code}. This means Cloudflare is likely blocking us.")
                Log.e(TAG, "Please solve the challenge in the WebView. This error is expected.")
                throw IOException("Cloudflare challenge required.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "An exception occurred during the check. This is expected if Cloudflare is active.", e)
            throw e // Re-throw to trigger Tachiyomi's WebView prompt
        } finally {
            Log.d(TAG, "======================================================================")
            Log.d(TAG, "  CLOUDFLARE SESSION CHECK FINISHED")
            Log.d(TAG, "======================================================================")
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
    private val authUrl = "https://auth.schale.network"
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

    private fun getManga(book: Entry) = SManga.create().apply {
        setUrlWithoutDomain("${book.id}/${book.key}")
        title = if (remadd()) book.title.shortenTitle() else book.title
        thumbnail_url = book.thumbnail.path
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        CloudflareHelper.checkAndLogClearance(this)
        return super.fetchPopularManga(page)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        CloudflareHelper.checkAndLogClearance(this)
        return super.fetchLatestUpdates(page)
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
        return Observable.error(UnsupportedOperationException("Page loading is disabled in this debug build."))
    }

    override fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException()
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()
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

    // Corrected parseAs function
    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())

    companion object {
        const val PREFIX_ID_KEY_SEARCH = "id:"
        private const val PREF_IMAGERES = "pref_image_quality"
        private const val PREF_REM_ADD = "pref_remove_additional"
        private const val PREF_EXCLUDE_TAGS = "pref_exclude_tags"
        // Added missing imports
        internal val dateReformat = SimpleDateFormat("EEEE, d MMM yyyy HH:mm (z)", Locale.ENGLISH)
    }
}
