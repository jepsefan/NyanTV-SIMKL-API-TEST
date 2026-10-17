package com.nyantv.data

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import com.nyantv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.launch


// Read-only data for the experimental home feed.
// This is NOT the full Simkl website's Latest Unwatched Episodes algorithm:
// the API supplies one next episode for each item in Watching.
data class SimklLatestEpisode(
    val media: Media,
    val number: String,
    val episodeTitle: String,
    val airDate: String,
    val isAnime: Boolean
)

data class SimklLatestEpisodeMismatch(
    val api: SimklLatestEpisode,
    val local: SimklLatestEpisode
)

data class SimklLatestUnwatchedResult(
    val episodes: List<SimklLatestEpisode>,
    val path: String,
    val returnedShows: Int,
    val returnedAnime: Int,
    val excludedAnime: Int,
    val apiPath: String,
    val localPath: String,
    val apiReturnedShows: Int,
    val localReturnedShows: Int,
    val apiEpisodes: List<SimklLatestEpisode>,
    val localEpisodes: List<SimklLatestEpisode>,
    val onlyInApi: List<SimklLatestEpisode>,
    val onlyInLocal: List<SimklLatestEpisode>,
    val differentNext: List<SimklLatestEpisodeMismatch>
)

private const val SIMKL_API  = "https://api.simkl.com"
private const val SIMKL_PREFS = "simkl_prefs"
private const val POSTER_BASE = "https://wsrv.nl/?url=https://simkl.in/posters/"

class SimklService(context: Context) : MediaService {

    override val serviceType = ServiceType.SIMKL

    private val http  = OkHttpClient()
    private val json  = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val prefs = context.getSharedPreferences(SIMKL_PREFS, Context.MODE_PRIVATE)

    private var token: String? = prefs.getString("access_token", null)

    private val _isLoggedIn      = MutableStateFlow(token != null)
    private val _profile         = MutableStateFlow<Profile?>(null)
    private val _animeList       = MutableStateFlow<List<TrackedMedia>>(emptyList())
    private val _currentMedia    = MutableStateFlow<TrackedMedia?>(null)
    private val _trending        = MutableStateFlow<List<Media>>(emptyList())
    private val _trendingMovies = MutableStateFlow<List<Media>>(emptyList())
    private val _trendingShows  = MutableStateFlow<List<Media>>(emptyList())
    private val _popular         = MutableStateFlow<List<Media>>(emptyList())
    private val _upcoming        = MutableStateFlow<List<Media>>(emptyList())
    private val _recentlyUpdated = MutableStateFlow<List<Media>>(emptyList())

    override val isLoggedIn:      StateFlow<Boolean>            = _isLoggedIn.asStateFlow()
    override val profile:         StateFlow<Profile?>           = _profile.asStateFlow()
    override val animeList:       StateFlow<List<TrackedMedia>> = _animeList.asStateFlow()
    override val currentMedia:    StateFlow<TrackedMedia?>      = _currentMedia.asStateFlow()
    override val trending:        StateFlow<List<Media>>        = _trending.asStateFlow()
    val trendingMovies:           StateFlow<List<Media>>        = _trendingMovies.asStateFlow()
    val trendingShows:            StateFlow<List<Media>>        = _trendingShows.asStateFlow()
    override val popular:         StateFlow<List<Media>>        = _popular.asStateFlow()
    override val upcoming:        StateFlow<List<Media>>        = _upcoming.asStateFlow()
    override val recentlyUpdated: StateFlow<List<Media>>        = _recentlyUpdated.asStateFlow()

    private val _deviceSession = MutableStateFlow<SimklDeviceSession?>(null)
    val deviceSession: StateFlow<SimklDeviceSession?> = _deviceSession.asStateFlow()
    private val _deviceStatus = MutableStateFlow("")
    val deviceStatus: StateFlow<String> = _deviceStatus.asStateFlow()
    private var deviceJob: kotlinx.coroutines.Job? = null

    fun cancelDeviceLogin() {
        deviceJob?.cancel()
        deviceJob = null
        _deviceSession.value = null
        _deviceStatus.value = ""
    }

    fun startDeviceLogin(scope: kotlinx.coroutines.CoroutineScope) {
        cancelDeviceLogin()
        deviceJob = scope.launch {
            _deviceStatus.value = "Requesting a Simkl login code…"
            try {
                val session = SimklDeviceAuth.request()
                _deviceSession.value = session
                _deviceStatus.value = "Scan the QR code or enter the code on your phone"
                val credentials = SimklDeviceAuth.poll(session) { _deviceStatus.value = it }
                token = credentials.accessToken
                prefs.edit {
                    putString("access_token", credentials.accessToken)
                    credentials.refreshToken?.let { putString("refresh_token", it) }
                    putLong("token_expires_at", System.currentTimeMillis() + credentials.expiresIn * 1000L)
                }
                _isLoggedIn.value = true
                _deviceStatus.value = "Connected to Simkl"
                _deviceSession.value = null
                fetchUserProfile()
                refreshUserLists()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _deviceStatus.value = e.message ?: "Simkl login failed"
            }
        }
    }

    // ── Auth ───────────────────────────────────────────────────────────────────

    override suspend fun login(context: Context) {
        val clientId = BuildConfig.SIMKL_CLIENT_ID
        val redirect = BuildConfig.REDIRECT_URI
        val url = "https://simkl.com/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=$redirect"
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    }

    suspend fun handleAuthCallback(code: String) = withContext(Dispatchers.IO) {
        android.util.Log.d("SimklService", "handleAuthCallback called, code=${code.take(10)}...")
        val body = buildJsonObject {
            put("code",          code)
            put("client_id",     BuildConfig.SIMKL_CLIENT_ID)
            put("client_secret", BuildConfig.SIMKL_CLIENT_SECRET)
            put("redirect_uri",  BuildConfig.REDIRECT_URI)
            put("grant_type",    "authorization_code")
        }
        android.util.Log.d("SimklService", "posting to $SIMKL_API/oauth/token")
        val resp = postJson("$SIMKL_API/oauth/token", body) ?: return@withContext
        // Never log OAuth tokens.
        token = resp["access_token"]!!.jsonPrimitive.content
        prefs.edit { putString("access_token", token) }
        _isLoggedIn.value = true
        fetchUserProfile()
        refreshUserLists()
    }

    override suspend fun logout() {
        cancelDeviceLogin()
        token = null
        prefs.edit { remove("access_token"); remove("refresh_token"); remove("token_expires_at") }
        _isLoggedIn.value = false
        _profile.value   = null
        _animeList.value = emptyList()
    }

    private suspend fun refreshIfNeeded() {
        val refresh = prefs.getString("refresh_token", null) ?: return
        if (System.currentTimeMillis() < prefs.getLong("token_expires_at", 0L) - 60000L) return
        val updated = SimklDeviceAuth.refresh(refresh)
        token = updated.accessToken
        prefs.edit {
            putString("access_token", updated.accessToken)
            updated.refreshToken?.let { putString("refresh_token", it) }
            putLong("token_expires_at", System.currentTimeMillis() + updated.expiresIn * 1000L)
        }
    }

    override suspend fun autoLogin() {
        if (token == null) return
        withContext(Dispatchers.IO) {
            runCatching {
                refreshIfNeeded()
                coroutineScope {
                    launch { fetchUserProfile() }
                    launch { refreshUserLists() }
                }
            }.onFailure {
                android.util.Log.e("SimklService", "autoLogin failed", it)
            }
        }
    }

    // ── Profile ────────────────────────────────────────────────────────────────

    private suspend fun fetchUserProfile() = withContext(Dispatchers.IO) {
        val data = postJsonAuthed("$SIMKL_API/users/settings", buildJsonObject {}) ?: return@withContext
        val account = data["account"]?.jsonObject
        val user    = data["user"]?.jsonObject
        _profile.value = Profile(
            id     = account?.get("id")?.jsonPrimitive?.contentOrNull,
            name   = user?.get("name")?.jsonPrimitive?.contentOrNull,
            avatar = user?.get("avatar")?.jsonPrimitive?.contentOrNull
        )
    }

    // ── Homepage ───────────────────────────────────────────────────────────────

    override suspend fun fetchHomePage() = withContext(Dispatchers.IO) {
        coroutineScope {
            val movies = async { fetchTrending("movies") }
            val shows  = async { fetchTrending("tv") }
            _trendingMovies.value = movies.await().take(15)
            _trendingShows.value  = shows.await().take(15)
            val combined = (_trendingMovies.value + _trendingShows.value)
                .sortedByDescending { it.popularity }
            _trending.value = combined.take(15)
            _popular.value  = combined.drop(15).take(15)
        }
    }

    private suspend fun fetchTrending(type: String): List<Media> = withContext(Dispatchers.IO) {
        runCatching {
            val simklType = if (type == "movies") "movies" else "tv"
            val req = Request.Builder()
                .url("https://data.simkl.in/discover/trending/$simklType/today_100.json?client_id=${BuildConfig.SIMKL_CLIENT_ID}")
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                android.util.Log.e("SimklService", "fetchTrending $type HTTP ${resp.code}")
                return@withContext emptyList()
            }
            val body = resp.body.string()
            android.util.Log.d("SimklService", "fetchTrending $type sample: ${body.take(500)}")
            json.parseToJsonElement(body).jsonArray
                .mapNotNull { it.jsonObject.toSimklMedia(isMovie = type == "movies") }
        }.getOrElse {
            android.util.Log.e("SimklService", "fetchTrending $type failed", it)
            emptyList()
        }
    }

    // ── Details ────────────────────────────────────────────────────────────────

    override suspend fun fetchDetails(id: String): Media = withContext(Dispatchers.IO) {
        val simklId = id.substringBefore("*")
        val isMovie = id.substringAfter("*") == "MOVIE"
        val type    = if (isMovie) "movies" else if (id.endsWith("*ANIME")) "anime" else "tv"
        runCatching {
            val data = getRaw("$SIMKL_API/$type/$simklId?extended=full&client_id=${BuildConfig.SIMKL_CLIENT_ID}")
                ?: return@withContext Media(id = id, title = "?")
            data.toSimklMediaFull(isMovie = isMovie)?.copy(id = id, format = if (id.endsWith("*ANIME")) "ANIME" else if (isMovie) "MOVIE" else "TV") ?: Media(id = id, title = "?")
        }.getOrElse { Media(id = id, title = "?") }
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<Media> = withContext(Dispatchers.IO) {
        coroutineScope {
            val movies = async {
                getRawArray("$SIMKL_API/search/movie?q=$query&extended=full&client_id=${BuildConfig.SIMKL_CLIENT_ID}")
                    ?.mapNotNull { it.jsonObject.toSimklMedia(isMovie = true) } ?: emptyList()
            }
            val series = async {
                getRawArray("$SIMKL_API/search/tv?q=$query&extended=full&client_id=${BuildConfig.SIMKL_CLIENT_ID}")
                    ?.mapNotNull { it.jsonObject.toSimklMedia(isMovie = false) } ?: emptyList()
            }
            movies.await() + series.await()
        }
    }

    // ── User lists – movies + shows merged into animeList ─────────────────────

    override suspend fun refreshUserLists() = withContext(Dispatchers.IO) {
        coroutineScope {
            val movies = async { fetchUserMovies() }
            val shows  = async { fetchUserShows() }
            val anime = async { fetchUserAnime() }
            _animeList.value = movies.await() + shows.await() + anime.await()
        }
    }

    private suspend fun fetchUserMovies(): List<TrackedMedia> = withContext(Dispatchers.IO) {
        coroutineScope {
            val listReq   = async { getAuthed("$SIMKL_API/sync/all-items/movies") }
            val ratingsReq = async {
                getRawArray("$SIMKL_API/ratings/ratings/movies?user_watchlist=all&fields=simkl,ext&client_id=${BuildConfig.SIMKL_CLIENT_ID}", authHeaders())
            }
            val data    = listReq.await() ?: return@coroutineScope emptyList()
            val ratings = ratingsReq.await()

            val ratingMap = mutableMapOf<String, JsonObject>()
            ratings?.forEach { r ->
                val id = r.jsonObject["id"]?.jsonPrimitive?.intOrNull?.toString()
                    ?: return@forEach
                ratingMap[id] = r.jsonObject
            }

            data["movies"]?.jsonArray?.mapNotNull { entry ->
                val movie   = entry.jsonObject["movie"]?.jsonObject ?: return@mapNotNull null
                val ids     = movie["ids"]?.jsonObject ?: return@mapNotNull null
                val simklId = ids["simkl"]?.jsonPrimitive?.intOrNull?.toString()
                    ?: return@mapNotNull null
                if (simklId.isBlank()) return@mapNotNull null

                val r = ratingMap[simklId]
                val simklRating = r?.get("simkl")?.jsonObject?.get("rating")?.jsonPrimitive?.floatOrNull
                val imdbRating  = r?.get("imdb")?.jsonObject?.get("rating")?.jsonPrimitive?.floatOrNull
                val score = (simklRating ?: imdbRating)?.let { (it * 10).toInt() }

                TrackedMedia(
                    id             = "${simklId}*MOVIE",
                    title          = movie["title"]?.jsonPrimitive?.contentOrNull ?: "?",
                    poster         = movie["poster"]?.jsonPrimitive?.contentOrNull
                        ?.let { "${POSTER_BASE}${it}_m.jpg" },
                    watchingStatus = simklStatusToAL(entry.jsonObject["status"]?.jsonPrimitive?.contentOrNull),
                    episodeCount   = if (simklStatusToAL(entry.jsonObject["status"]?.jsonPrimitive?.contentOrNull) == "COMPLETED") 1 else 0,
                    totalEpisodes  = 1,
                    averageScore   = score,
                    isMovie        = true
                )
            } ?: emptyList()
        }
    }

    private suspend fun fetchUserShows(): List<TrackedMedia> = withContext(Dispatchers.IO) {
        coroutineScope {
            val listReq    = async { getAuthed("$SIMKL_API/sync/all-items/shows") }
            val ratingsReq = async {
                getRawArray("$SIMKL_API/ratings/ratings/tv?user_watchlist=all&fields=simkl,ext&client_id=${BuildConfig.SIMKL_CLIENT_ID}", authHeaders())
            }
            val data    = listReq.await() ?: return@coroutineScope emptyList()
            val ratings = ratingsReq.await()

            val ratingMap = mutableMapOf<String, JsonObject>()
            ratings?.forEach { r ->
                val id = r.jsonObject["id"]?.jsonPrimitive?.intOrNull?.toString()
                    ?: return@forEach
                ratingMap[id] = r.jsonObject
            }

            data["shows"]?.jsonArray?.mapNotNull { entry ->
                val show    = entry.jsonObject["show"]?.jsonObject ?: return@mapNotNull null
                val ids     = show["ids"]?.jsonObject ?: return@mapNotNull null
                val simklId = ids["simkl"]?.jsonPrimitive?.intOrNull?.toString()
                    ?: return@mapNotNull null
                if (simklId.isBlank()) return@mapNotNull null

                val r = ratingMap[simklId]
                val simklRating = r?.get("simkl")?.jsonObject?.get("rating")?.jsonPrimitive?.floatOrNull
                val imdbRating  = r?.get("imdb")?.jsonObject?.get("rating")?.jsonPrimitive?.floatOrNull
                val score = (simklRating ?: imdbRating)?.let { (it * 10).toInt() }

                TrackedMedia(
                    id             = "${simklId}*SERIES",
                    title          = show["title"]?.jsonPrimitive?.contentOrNull ?: "?",
                    poster         = show["poster"]?.jsonPrimitive?.contentOrNull
                        ?.let { "${POSTER_BASE}${it}_m.jpg" },
                    watchingStatus = simklStatusToAL(entry.jsonObject["status"]?.jsonPrimitive?.contentOrNull),
                    episodeCount   = entry.jsonObject["watched_episodes_count"]?.jsonPrimitive?.intOrNull,
                    totalEpisodes  = entry.jsonObject["total_episodes_count"]?.jsonPrimitive?.intOrNull,
                    averageScore   = score,
                    isMovie        = false
                )
            } ?: emptyList()
        }
    }


    private suspend fun fetchUserAnime(): List<TrackedMedia> {
        val data = getAuthed("$SIMKL_API/sync/all-items/anime") ?: return emptyList()
        val entries = data["anime"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val show = item["show"] as? JsonObject ?: return@mapNotNull null
            val ids = show["ids"] as? JsonObject ?: return@mapNotNull null
            val id = (ids["simkl"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            TrackedMedia(
                id = "${id}*ANIME",
                title = (show["title"] as? JsonPrimitive)?.contentOrNull ?: "?",
                poster = (show["poster"] as? JsonPrimitive)?.contentOrNull
                    ?.let { "${POSTER_BASE}${it}_m.jpg" },
                watchingStatus = simklStatusToAL((item["status"] as? JsonPrimitive)?.contentOrNull),
                episodeCount = (item["watched_episodes_count"] as? JsonPrimitive)?.intOrNull,
                totalEpisodes = (item["total_episodes_count"] as? JsonPrimitive)?.intOrNull,
                isMovie = false,
                isAnime = true
            )
        }
    }

    override fun setCurrentMedia(id: String) {
        _currentMedia.value = _animeList.value.firstOrNull { it.id == id }
    }

    override suspend fun updateEntry(id: String, status: String?, progress: Int?, score: Float?) =
        withContext(Dispatchers.IO) {
            val isMovie = id.substringAfter("*") == "MOVIE"
            val category = if (id.endsWith("*ANIME")) "anime" else "shows"
            val simklId = id.substringBefore("*")
            val newStatus = alStatusToSimkl(status ?: "CURRENT")
            val existingEntry = _animeList.value.firstOrNull { it.id == id }
            val alreadyExists = existingEntry != null
            val currentProgress = existingEntry?.episodeCount ?: 0
            val isDecreasing = progress != null && progress < currentProgress
            val statusChanged = alreadyExists && existingEntry.watchingStatus != (status ?: existingEntry.watchingStatus)

            if (isMovie) {
                val body = buildJsonObject {
                    put("movies", buildJsonArray {
                        add(buildJsonObject {
                            if (!alreadyExists) put("to", newStatus)
                            put("ids", buildJsonObject { put("simkl", simklId) })
                        })
                    })
                }
                val url = if (alreadyExists) "$SIMKL_API/sync/history" else "$SIMKL_API/sync/add-to-list"
                postJsonAuthed(url, body)
            } else {
                if (!alreadyExists || statusChanged) {
                    val statusBody = buildJsonObject {
                        put(category, buildJsonArray {
                            add(buildJsonObject {
                                put("to", newStatus)
                                put("ids", buildJsonObject { put("simkl", simklId) })
                            })
                        })
                    }
                    postJsonAuthed("$SIMKL_API/sync/add-to-list", statusBody)
                }

                if (isDecreasing) {
                    val removeBody = buildJsonObject {
                        put(category, buildJsonArray {
                            add(buildJsonObject {
                                put("ids", buildJsonObject { put("simkl", simklId) })
                                put("seasons", buildJsonArray {
                                    add(buildJsonObject {
                                        put("number", 1)
                                        put("episodes", buildJsonArray {
                                            for (i in 1..currentProgress) {
                                                add(buildJsonObject { put("number", i) })
                                            }
                                        })
                                    })
                                })
                            })
                        })
                    }
                    postJsonAuthed("$SIMKL_API/sync/history/remove", removeBody)
                }

                if (progress != null && progress > 0) {
                    val addBody = buildJsonObject {
                        put(category, buildJsonArray {
                            add(buildJsonObject {
                                put("ids", buildJsonObject { put("simkl", simklId) })
                                put("episodes", buildJsonArray {
                                    for (i in 1..progress) {
                                        add(buildJsonObject { put("number", i) })
                                    }
                                })
                            })
                        })
                    }
                    postJsonAuthed("$SIMKL_API/sync/history", addBody)
                }
            }

            refreshUserLists()
            setCurrentMedia(id)
        }

    override suspend fun deleteEntry(id: String) = withContext(Dispatchers.IO) {
        val isMovie = id.substringAfter("*") == "MOVIE"
        val simklId = id.substringBefore("*")
        val body = if (isMovie) buildJsonObject {
            put("movies", buildJsonArray {
                add(buildJsonObject { put("ids", buildJsonObject { put("simkl", simklId) }) })
            })
        } else buildJsonObject {
            put(if (id.endsWith("*ANIME")) "anime" else "shows", buildJsonArray {
                add(buildJsonObject { put("ids", buildJsonObject { put("simkl", simklId) }) })
            })
        }
        postJsonAuthed("$SIMKL_API/sync/history/remove", body)
        refreshUserLists()
        setCurrentMedia(id)
    }


    // ── Diagnostic home feed: Simkl next unwatched episodes ────────────────────
    // This read-only experiment does not change the existing watchlists.
    suspend fun fetchLatestUnwatchedEpisodes(
        onlyShows: Boolean,
        localAnimeFiltering: Boolean
    ): SimklLatestUnwatchedResult {
        // Always retrieve both snapshots for an actual side-by-side comparison.
        // Both endpoints are read-only. The same eligibility rules below apply to each.
        val apiPath = "/sync/all-items/shows/watching?next_watch_info=yes"
        val localPath = "/sync/all-items?next_watch_info=yes"
        if (token.isNullOrBlank()) error("Sign in to Simkl to test the API")
        val apiData = getAuthed("$SIMKL_API$apiPath")
            ?: error("API-filtered request failed: $apiPath")
        val localData = getAuthed("$SIMKL_API$localPath")
            ?: error("Local-filtered request failed: $localPath")
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()

        fun parse(data: JsonObject, type: String): List<SimklLatestEpisode> {
            val entries = data[type] as? JsonArray ?: return emptyList()
            return entries.mapNotNull { raw ->
                val item = raw as? JsonObject ?: return@mapNotNull null
                if ((item["status"] as? JsonPrimitive)?.contentOrNull != "watching") {
                    return@mapNotNull null
                }
                val show = item["show"] as? JsonObject ?: return@mapNotNull null
                val ids = show["ids"] as? JsonObject ?: return@mapNotNull null
                val simklId = (ids["simkl"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val next = item["next_to_watch_info"] as? JsonObject
                    ?: return@mapNotNull null
                val airedAt = (next["date"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val day = airedAt.take(10)
                if (!day.matches(Regex("""\d{4}-\d{2}-\d{2}""")) || day > today) {
                    return@mapNotNull null
                }
                val season = (next["season"] as? JsonPrimitive)?.intOrNull
                val episode = (next["episode"] as? JsonPrimitive)?.intOrNull
                val number = if (type == "anime") {
                    episode?.let { "E$it" }
                        ?: (item["next_to_watch"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                } else {
                    if (season != null && episode != null) {
                        "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
                    } else (item["next_to_watch"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
                val title = (show["title"] as? JsonPrimitive)?.contentOrNull ?: "Unknown"
                val poster = (show["poster"] as? JsonPrimitive)?.contentOrNull
                SimklLatestEpisode(
                    media = Media(
                        id = "$simklId*${if (type == "anime") "ANIME" else "SERIES"}",
                        title = title,
                        poster = poster?.let { "${POSTER_BASE}${it}_m.jpg" },
                        serviceType = ServiceType.SIMKL
                    ),
                    number = number,
                    episodeTitle = (next["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    airDate = day,
                    isAnime = type == "anime"
                )
            }.distinctBy { it.media.id }
        }

        val byAirDate = compareByDescending<SimklLatestEpisode> { it.airDate }
            .thenBy { it.media.title }
        val apiShows = parse(apiData, "shows").sortedWith(byAirDate)
        val localShows = parse(localData, "shows").sortedWith(byAirDate)
        val localAnime = parse(localData, "anime").sortedWith(byAirDate)
        val apiById = apiShows.associateBy { it.media.id }
        val localById = localShows.associateBy { it.media.id }
        val onlyInApi = apiShows.filter { it.media.id !in localById }
        val onlyInLocal = localShows.filter { it.media.id !in apiById }
        val differentNext = apiShows.mapNotNull { api ->
            val local = localById[api.media.id] ?: return@mapNotNull null
            if (api.number != local.number || api.airDate != local.airDate) {
                SimklLatestEpisodeMismatch(api, local)
            } else null
        }

        // The switch selects which shows list is primary; it does not affect
        // the comparison. Anime always comes from the full response if enabled.
        val selectedShows = if (localAnimeFiltering) localShows else apiShows
        val episodes = (selectedShows + if (onlyShows) emptyList() else localAnime)
            .distinctBy { it.media.id }.sortedWith(byAirDate)
        return SimklLatestUnwatchedResult(
            episodes = episodes,
            path = if (localAnimeFiltering) localPath else apiPath,
            returnedShows = if (localAnimeFiltering) {
                (localData["shows"] as? JsonArray)?.size ?: 0
            } else {
                (apiData["shows"] as? JsonArray)?.size ?: 0
            },
            returnedAnime = (localData["anime"] as? JsonArray)?.size ?: 0,
            excludedAnime = if (onlyShows) localAnime.size else 0,
            apiPath = apiPath,
            localPath = localPath,
            apiReturnedShows = (apiData["shows"] as? JsonArray)?.size ?: 0,
            localReturnedShows = (localData["shows"] as? JsonArray)?.size ?: 0,
            apiEpisodes = apiShows,
            localEpisodes = localShows,
            onlyInApi = onlyInApi,
            onlyInLocal = onlyInLocal,
            differentNext = differentNext
        )
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────────

    private fun authHeaders() = mapOf(
        "Authorization"  to "Bearer $token",
        "simkl-api-key"  to BuildConfig.SIMKL_CLIENT_ID,
        "Content-Type"   to "application/json"
    )

    private suspend fun get(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val req  = Request.Builder().url(url).build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext null
        runCatching { json.parseToJsonElement(resp.body.string()).jsonObject }.getOrNull()
    }

    private suspend fun getRaw(url: String): JsonObject? = get(url)

    private suspend fun getRawArray(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): List<JsonElement>? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext null
        runCatching { json.parseToJsonElement(resp.body.string()).jsonArray.toList() }.getOrNull()
    }

    private suspend fun getAuthed(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .apply { authHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext null
        runCatching { json.parseToJsonElement(resp.body.string()).jsonObject }.getOrNull()
    }

    private suspend fun postJson(url: String, body: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            android.util.Log.e("SimklService", "postJson $url failed: ${resp.code} – ${resp.body.string()}")
            return@withContext null
        }
        runCatching { json.parseToJsonElement(resp.body.string()).jsonObject }.getOrNull()
    }

    private suspend fun postJsonAuthed(url: String, body: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { authHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            android.util.Log.e("SimklService", "postJsonAuthed $url failed: ${resp.code} – ${resp.body.string()}")
            return@withContext null
        }
        runCatching { json.parseToJsonElement(resp.body.string()).jsonObject }.getOrNull()
    }

    // ── Status mapping ─────────────────────────────────────────────────────────

    private fun simklStatusToAL(s: String?) = when (s) {
        "watching"    -> "CURRENT"
        "completed"   -> "COMPLETED"
        "hold"        -> "PAUSED"
        "dropped"     -> "DROPPED"
        "plantowatch" -> "PLANNING"
        else          -> "UNKNOWN"
    }

    private fun alStatusToSimkl(s: String) = when (s) {
        "CURRENT"   -> "watching"
        "COMPLETED" -> "completed"
        "PAUSED"    -> "hold"
        "DROPPED"   -> "dropped"
        "PLANNING"  -> "plantowatch"
        else        -> "watching"
    }
}

// ── Simkl JSON → Media ────────────────────────────────────────────────────────

private fun JsonObject.toSimklMedia(isMovie: Boolean): Media? {
    val ids     = this["ids"]?.jsonObject
    val simklId = ids?.get("simkl_id")?.jsonPrimitive?.contentOrNull
        ?: ids?.get("simkl")?.jsonPrimitive?.contentOrNull
    if (simklId.isNullOrBlank()) return null
    val tmdbId = ids?.get("tmdb_id")?.jsonPrimitive?.contentOrNull
        ?: ids?.get("tmdb")?.jsonPrimitive?.contentOrNull

    val poster = this["poster"]?.jsonPrimitive?.contentOrNull
        ?.let { "${POSTER_BASE}${it}_m.jpg" }

    val ratingsObj   = this["ratings"]?.jsonObject
    val simklRating  = ratingsObj?.get("simkl")?.jsonObject?.get("rating")?.jsonPrimitive?.floatOrNull
    val imdbRating   = ratingsObj?.get("imdb")?.jsonObject?.get("rating")?.jsonPrimitive?.floatOrNull
    val rating       = simklRating ?: imdbRating
    val averageScore = rating?.let { (it * 10).toInt() }

    val popularity = ratingsObj?.get("simkl")?.jsonObject?.get("votes")?.jsonPrimitive?.intOrNull

    return Media(
        id           = if (isMovie) "${simklId}*MOVIE" else "${simklId}*SERIES",
        title        = this["title"]?.jsonPrimitive?.contentOrNull ?: "?",
        poster       = poster,
        cover        = this["fanart"]?.jsonPrimitive?.contentOrNull
            ?.let { "https://wsrv.nl/?url=https://simkl.in/fanart/${it}_w.jpg" },
        description  = this["overview"]?.jsonPrimitive?.contentOrNull,
        status       = this["status"]?.jsonPrimitive?.contentOrNull.normalizeStatus(),
        averageScore = averageScore,
        format       = if (isMovie) "MOVIE" else "TV",
        seasonYear   = this["year"]?.jsonPrimitive?.intOrNull,
        popularity   = popularity,
        tmdbId       = tmdbId,
        serviceType  = ServiceType.SIMKL
    )
}

private fun JsonObject.toSimklMediaFull(isMovie: Boolean): Media? {
    val base = toSimklMedia(isMovie) ?: return null

    val recommendations = this["users_recommendations"]?.jsonArray?.mapNotNull { rec ->
        runCatching {
            val obj     = rec.jsonObject
            val ids     = obj["ids"]?.jsonObject
            val recId   = ids?.get("simkl")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val poster  = obj["poster"]?.jsonPrimitive?.contentOrNull
                ?.let { "https://simkl.in/posters/${it}_m.jpg" }
            Media(
                id          = if (isMovie) "${recId}*MOVIE" else "${recId}*SERIES",
                title       = obj["title"]?.jsonPrimitive?.contentOrNull ?: "?",
                poster      = poster,
                serviceType = ServiceType.SIMKL
            )
        }.getOrNull()
    } ?: emptyList()

    return base.copy(
        recommendations = recommendations,
        episodes        = this["total_episodes"]?.jsonPrimitive?.intOrNull,
        genres          = this["genres"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        description     = this["overview"]?.jsonPrimitive?.contentOrNull
    )
}
