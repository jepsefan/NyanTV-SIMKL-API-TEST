package com.nyantv.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nyantv.data.Media
import com.nyantv.data.ServiceType
import com.nyantv.data.TrackedMedia
import com.nyantv.ui.SectionRow
import com.nyantv.viewmodel.AppViewModel

@Composable
fun HomeSections(vm: AppViewModel, navController: NavController, onDetailClick: (String) -> Unit) {
    val service         by vm.serviceType.collectAsStateWithLifecycle()
    val animeList       by vm.animeList.collectAsStateWithLifecycle()
    val trending        by vm.trending.collectAsStateWithLifecycle()
    val popular         by vm.popular.collectAsStateWithLifecycle()
    val trendingMovies  by vm.trendingMovies.collectAsStateWithLifecycle()
    val trendingShows   by vm.trendingShows.collectAsStateWithLifecycle()

    val anilistContinue by vm.anilistShowContinue.collectAsStateWithLifecycle()
    val anilistPlanned  by vm.anilistShowPlanned.collectAsStateWithLifecycle()
    val malContinue     by vm.malShowContinue.collectAsStateWithLifecycle()
    val malPlanned      by vm.malShowPlanned.collectAsStateWithLifecycle()
    val simklContMovies by vm.simklShowContMovies.collectAsStateWithLifecycle()
    val simklPlanMovies by vm.simklShowPlanMovies.collectAsStateWithLifecycle()
    val simklContSeries by vm.simklShowContSeries.collectAsStateWithLifecycle()
    val simklPlanSeries by vm.simklShowPlanSeries.collectAsStateWithLifecycle()
    val simklShowAnime by vm.simklShowAnime.collectAsStateWithLifecycle()

    val trackedMap = animeList.associateBy { it.id }

    fun TrackedMedia.toMedia() = Media(
        id           = id,
        title        = title,
        poster       = poster,
        episodes     = totalEpisodes,
        averageScore = averageScore?.takeIf { it > 0f },
        serviceType  = service,
        idMal        = if (service == ServiceType.MAL) id else null,
    )
    fun List<TrackedMedia>.toMedia() = map { it.toMedia() }
    fun navigate(id: String) = onDetailClick(id)

    when (service) {
        ServiceType.ANILIST -> {
            val watching = animeList.filter { it.watchingStatus == "CURRENT" }
            val planned  = animeList.filter { it.watchingStatus == "PLANNING" }
            if (anilistContinue && watching.isNotEmpty()) {
                SectionRow(title = "Continue Watching", items = watching.toMedia(), onItemClick = { navigate(it.id) }, trackedMap = trackedMap)
            }
            if (anilistPlanned && planned.isNotEmpty()) {
                SectionRow(title = "Planned Anime", items = planned.toMedia(), onItemClick = { navigate(it.id) }, trackedMap = trackedMap)
            }
            SectionRow(title = "Trending Now",  items = trending, onItemClick = { navigate(it.id) })
            SectionRow(title = "Popular Anime", items = popular,  onItemClick = { navigate(it.id) })
        }

        ServiceType.MAL -> {
            val watching = animeList.filter { it.watchingStatus == "CURRENT" }
            val planned  = animeList.filter { it.watchingStatus == "PLANNING" }
            if (malContinue && watching.isNotEmpty()) {
                SectionRow(title = "Continue Watching", items = watching.toMedia(), onItemClick = { navigate(it.id) }, trackedMap = trackedMap)
            }
            if (malPlanned && planned.isNotEmpty()) {
                SectionRow(title = "Planned Anime", items = planned.toMedia(), onItemClick = { navigate(it.id) }, trackedMap = trackedMap)
            }
            SectionRow(title = "Trending Now",  items = trending, onItemClick = { navigate(it.id) })
            SectionRow(title = "Popular Anime", items = popular,  onItemClick = { navigate(it.id) })
        }

        ServiceType.SIMKL -> {
            val onlyShows by vm.simklLatestOnlyShows.collectAsStateWithLifecycle()
            val localAnimeFiltering by vm.simklLatestLocalFiltering.collectAsStateWithLifecycle()
            val episodes by vm.simklLatestEpisodes.collectAsStateWithLifecycle()
            val loading by vm.simklLatestLoading.collectAsStateWithLifecycle()
            val diagnostic by vm.simklLatestDiagnostic.collectAsStateWithLifecycle()
            val comparison by vm.simklLatestComparison.collectAsStateWithLifecycle()
            val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()

            LaunchedEffect(loggedIn, service) {
                if (loggedIn) vm.refreshSimklLatest()
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Latest Unwatched Episodes · API test", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Exclude anime (shows only)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = onlyShows,
                        onCheckedChange = { vm.setSimklLatestOnlyShows(it) }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Local anime filtering", modifier = Modifier.weight(1f))
                    Switch(
                        checked = localAnimeFiltering,
                        onCheckedChange = { vm.setSimklLatestLocalFiltering(it) }
                    )
                }
                Text(
                    if (onlyShows) {
                        if (localAnimeFiltering) "Shows only · local filtering" else "Shows only · API filtering"
                    } else "Shows + Anime",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(diagnostic, style = MaterialTheme.typography.bodySmall)
                comparison?.let { result ->
                    Text("API filtering: ${result.apiPath}", style = MaterialTheme.typography.labelSmall)
                    Text("Local filtering: ${result.localPath}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "API: ${result.apiEpisodes.size} episodes · Local: ${result.localEpisodes.size} episodes",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Only in API: ${result.onlyInApi.size} · Only in local: ${result.onlyInLocal.size}" +
                            " · Different next episode: ${result.differentNext.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (result.onlyInApi.isEmpty() && result.onlyInLocal.isEmpty() &&
                        result.differentNext.isEmpty()) {
                        Text("No differences detected between the two shows lists.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        if (result.onlyInApi.isNotEmpty()) {
                            Text("Only in API-filtered results", style = MaterialTheme.typography.titleSmall)
                            result.onlyInApi.forEach { entry ->
                                Text("${entry.media.title} · ${entry.number} · ${entry.airDate}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (result.onlyInLocal.isNotEmpty()) {
                            Text("Only in locally filtered results", style = MaterialTheme.typography.titleSmall)
                            result.onlyInLocal.forEach { entry ->
                                Text("${entry.media.title} · ${entry.number} · ${entry.airDate}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (result.differentNext.isNotEmpty()) {
                            Text("Different next episodes", style = MaterialTheme.typography.titleSmall)
                            result.differentNext.forEach { entry ->
                                Text(
                                    "${entry.api.media.title}: API ${entry.api.number} (${entry.api.airDate})" +
                                        " / Local ${entry.local.number} (${entry.local.airDate})",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                Button(onClick = { vm.refreshSimklLatest() }, enabled = !loading && loggedIn) {
                    Text(if (loading) "Loading..." else "Retry API call")
                }
                if (!loading && episodes.isEmpty()) {
                    Text("No eligible aired unwatched episodes returned.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (episodes.isNotEmpty()) {
                SectionRow(
                    title = "Latest Unwatched Episodes",
                    items = episodes.map { it.media },
                    onItemClick = { navigate(it.id) },
                    trackedMap = trackedMap
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(episodes, key = { it.media.id }) { ep ->
                        Column(modifier = Modifier.width(120.dp)) {
                            Text(
                                ep.number + (if (ep.isAnime) " · Anime" else ""),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(ep.airDate, style = MaterialTheme.typography.labelSmall)
                            if (ep.episodeTitle.isNotBlank()) {
                                Text(ep.episodeTitle, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            val contMovies = animeList.filter { it.watchingStatus == "CURRENT"  && it.isMovie == true }
            val planMovies = animeList.filter { it.watchingStatus == "PLANNING" && it.isMovie == true }
            val contSeries = animeList.filter { it.watchingStatus == "CURRENT"  && it.isMovie != true && !it.isAnime }
            val planSeries = animeList.filter { it.watchingStatus == "PLANNING" && it.isMovie != true }

            if (simklContMovies && contMovies.isNotEmpty()) {
                SectionRow(title = "Continue Watching (Movies)", items = contMovies.toMedia(), onItemClick = { navigate(it.id) }, trackedMap = trackedMap)
            }
            if (simklPlanMovies && planMovies.isNotEmpty()) {
                SectionRow(title = "Planned Movies", items = planMovies.toMedia(), onItemClick = { navigate(it.id) }, trackedMap = trackedMap)
            }
            if (simklContSeries && contSeries.isNotEmpty()) {
                SectionRow(title = "Continue Watching (Series)", items = contSeries.toMedia(), onItemClick = { navigate(it.id) }, trackedMap = trackedMap)
            }
            if (simklPlanSeries && planSeries.isNotEmpty()) {
                SectionRow(title = "Planned Series", items = planSeries.toMedia(), onItemClick = { navigate(it.id) }, trackedMap = trackedMap)
            }
            if (simklShowAnime) {
                val anime = animeList.filter { it.isAnime }
                if (anime.isNotEmpty()) {
                    SectionRow(title = "Simkl Anime", items = anime.toMedia(), onItemClick = { navigate(it.id) }, trackedMap = trackedMap)
                }
            }
            if (trendingMovies.isNotEmpty()) {
                SectionRow(title = "Trending Movies", items = trendingMovies, onItemClick = { navigate(it.id) })
            }
            if (trendingShows.isNotEmpty()) {
                SectionRow(title = "Trending Shows", items = trendingShows, onItemClick = { navigate(it.id) })
            }
        }
    }
}
