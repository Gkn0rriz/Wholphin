package com.github.damontecres.wholphin.ui.detail.movie

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.ItemPlaybackRepository
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.ExtrasService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.MediaReportService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PeopleFavorites
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.services.StreamChoiceService
import com.github.damontecres.wholphin.services.ThemeSongPlayer
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.letNotEmpty
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import timber.log.Timber
import java.util.UUID

@HiltViewModel(assistedFactory = MovieViewModel.Factory::class)
class MovieViewModel
    @AssistedInject
    constructor(
        private val api: ApiClient,
        private val seerrService: SeerrService,
        @param:ApplicationContext private val context: Context,
        private val navigationManager: NavigationManager,
        val serverRepository: ServerRepository,
        val itemPlaybackRepository: ItemPlaybackRepository,
        val streamChoiceService: StreamChoiceService,
        val mediaReportService: MediaReportService,
        private val themeSongPlayer: ThemeSongPlayer,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val peopleFavorites: PeopleFavorites,
        private val trailerService: TrailerService,
        private val extrasService: ExtrasService,
        private val userPreferencesService: UserPreferencesService,
        private val backdropService: BackdropService,
        private val mediaManagementService: MediaManagementService,
        @Assisted val itemId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(itemId: UUID): MovieViewModel
        }

        private val _state = MutableStateFlow(MovieState())
        val state: StateFlow<MovieState> = _state

        init {
            init()
            viewModelScope.launchDefault {
                mediaManagementService.collectCanDelete(state.map { it.movie }) { canDelete ->
                    _state.update {
                        it.copy(canDelete = canDelete)
                    }
                }
            }
        }

        private suspend fun getMovie(): BaseItem {
            val item =
                api.userLibraryApi.getItem(itemId).content.let {
                    BaseItem(it)
                }
            return item
        }

        fun init(): Job =
            viewModelScope.launchDefault {
                val movie =
                    try {
                        getMovie()
                    } catch (ex: Exception) {
                        Timber.e(ex, "Failed to fetch movie %s", itemId)
                        _state.update { it.copy(loading = DataLoadingState.Error(ex)) }
                        return@launchDefault
                    }
                val chosenStreams =
                    itemPlaybackRepository.getSelectedTracks(
                        itemId,
                        movie,
                        userPreferencesService.getCurrent(),
                    )
                val remoteTrailers = trailerService.getRemoteTrailers(movie)
                val chapters = Chapter.fromDto(movie.data, api)
                _state.update {
                    it.copy(
                        loading = DataLoadingState.Success(movie),
                        chosenStreams = chosenStreams,
                        trailers = remoteTrailers,
                        chapters = chapters,
                    )
                }
                backdropService.submit(movie)
                viewModelScope.launchIO {
                    trailerService.getLocalTrailers(movie).letNotEmpty { localTrailers ->
                        _state.update {
                            it.copy(
                                trailers = localTrailers + remoteTrailers,
                            )
                        }
                    }
                }
                viewModelScope.launchIO {
                    val people = peopleFavorites.getPeopleFor(movie)
                    _state.update {
                        it.copy(
                            people = people,
                        )
                    }
                }
                viewModelScope.launchIO {
                    val extras = extrasService.getExtras(itemId)
                    _state.update {
                        it.copy(
                            extras = extras,
                        )
                    }
                }
                viewModelScope.launchIO {
                    val results = seerrService.similar(movie).orEmpty()
                    _state.update {
                        it.copy(
                            discovered = results,
                        )
                    }
                }

                /* The Movie Collection fetch logic*/

                viewModelScope.launchIO {
                    try {
                        Timber.d("WholphinLog: Starting Fast Parallel Scan...")

                        // 1. Get the list of all Box Sets
                        val allBoxSets = api.itemsApi.getItems(
                            GetItemsRequest(
                                userId = serverRepository.currentUser?.id,
                                includeItemTypes = listOf(BaseItemKind.BOX_SET),
                                recursive = true
                            )
                        ).content.items

                        var foundBoxSetId: UUID? = null

                        // 2. Parallel Search: Check 10 boxsets at a time to find the movie
                        // We use chunked to avoid overwhelming the server with 205 simultaneous calls
                        allBoxSets.chunked(10).forEach { chunk ->
                            if (foundBoxSetId != null) return@forEach // Stop if we already found it

                            val results = chunk.map { boxSet ->
                                async {
                                    val containsItem = api.itemsApi.getItems(
                                        GetItemsRequest(
                                            userId = serverRepository.currentUser?.id,
                                            parentId = boxSet.id,
                                            includeItemTypes = listOf(BaseItemKind.MOVIE)
                                        )
                                    ).content.items.any { it.id == itemId }
                                    if (containsItem) boxSet.id else null
                                }
                            }.awaitAll()

                            foundBoxSetId = results.filterNotNull().firstOrNull()
                        }

                        // 3. THE MISSING PIECE: Fetch siblings and Update UI State
                        if (foundBoxSetId != null) {
                            Timber.d("WholphinLog: Match found: $foundBoxSetId. Fetching siblings now...")

                            val siblings = api.itemsApi.getItems(
                                org.jellyfin.sdk.model.api.request.GetItemsRequest(
                                    userId = serverRepository.currentUser?.id,
                                    parentId = foundBoxSetId,
                                    fields = SlimItemFields,
                                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                                    sortBy = listOf(ItemSortBy.PREMIERE_DATE),
                                    sortOrder = listOf(SortOrder.ASCENDING)
                                )
                            ).content.items
                                .map { BaseItem(it) }
                                .filter { it.id != itemId } // Exclude current movie

                            _state.update {
                                it.copy(collections = siblings)
                            }
                            Timber.d("WholphinLog: State updated with ${siblings.size} siblings.")
                        } else {
                            Timber.d("WholphinLog: No collection found after full scan.")
                        }

                    } catch (ex: Exception) {
                        Timber.e(ex, "WholphinLog: Error during parallel scan")
                    }
                }

                if (state.value.similar.isEmpty()) {
                    val similar =
                        api.libraryApi
                            .getSimilarItems(
                                GetSimilarItemsRequest(
                                    userId = serverRepository.currentUser?.id,
                                    itemId = itemId,
                                    fields = SlimItemFields,
                                    limit = 25,
                                ),
                            ).content.items
                            .map { BaseItem(it) }

                    _state.update { it.copy(similar = similar) }
                }
            }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
        ) = viewModelScope.launchDefault {
            try {
                favoriteWatchManager.setWatched(itemId, played)
                getMovie().let { movie ->
                    _state.update {
                        it.copy(loading = DataLoadingState.Success(movie))
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error updating watch status for movie %s", itemId)
                showToast(context, "Something went wrong...")
            }
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ) = viewModelScope.launchDefault {
            try {
                favoriteWatchManager.setFavorite(itemId, favorite)
                val movie = getMovie()
                _state.update {
                    it.copy(loading = DataLoadingState.Success(movie))
                }
                if (itemId != movie.id) {
                    viewModelScope.launchIO {
                        val people = peopleFavorites.getPeopleFor(movie)
                        _state.update { it.copy(people = people) }
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error updating favorite  %s", itemId)
                showToast(context, "Something went wrong...")
            }
        }

        fun savePlayVersion(
            item: BaseItem,
            sourceId: UUID,
        ) {
            viewModelScope.launchIO {
                val prefs = userPreferencesService.getCurrent()
                val plc = streamChoiceService.getPlaybackLanguageChoice(item.data)
                val result = itemPlaybackRepository.savePlayVersion(item.id, sourceId)
                val chosen =
                    result?.let {
                        itemPlaybackRepository.getChosenItemFromPlayback(item, result, plc, prefs)
                    }
                _state.update { it.copy(chosenStreams = chosen) }
            }
        }

        fun saveTrackSelection(
            item: BaseItem,
            itemPlayback: ItemPlayback?,
            trackIndex: Int,
            type: MediaStreamType,
        ) {
            viewModelScope.launchIO {
                val prefs = userPreferencesService.getCurrent()
                val plc = streamChoiceService.getPlaybackLanguageChoice(item.data)
                val result =
                    itemPlaybackRepository.saveTrackSelection(
                        item = item,
                        itemPlayback = itemPlayback,
                        trackIndex = trackIndex,
                        type = type,
                    )
                val chosen =
                    result?.let {
                        itemPlaybackRepository.getChosenItemFromPlayback(item, result, plc, prefs)
                    }
                _state.update { it.copy(chosenStreams = chosen) }
            }
        }

        fun maybePlayThemeSong(itemId: UUID) {
            viewModelScope.launchIO {
                themeSongPlayer.playThemeFor(itemId)
                addCloseable {
                    themeSongPlayer.stop()
                }
            }
        }

        fun release() {
            themeSongPlayer.stop()
        }

        fun navigateTo(destination: Destination) {
            release()
            navigationManager.navigateTo(destination)
        }

        fun clearChosenStreams(chosenStreams: ChosenStreams?) {
            viewModelScope.launchIO {
                itemPlaybackRepository.deleteChosenStreams(chosenStreams)
                state.value.movie?.let { item ->
                    val result =
                        itemPlaybackRepository.getSelectedTracks(
                            itemId,
                            item,
                            userPreferencesService.getCurrent(),
                        )
                    _state.update { it.copy(chosenStreams = result) }
                }
            }
        }

        fun deleteItem(item: BaseItem) {
            deleteItem(context, mediaManagementService, item) {
                navigationManager.goBack()
            }
        }
    }

data class MovieState(
    val loading: DataLoadingState<BaseItem> = DataLoadingState.Pending,
    val trailers: List<Trailer> = emptyList(),
    val people: List<Person> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val extras: List<ExtrasItem> = emptyList(),
    val similar: List<BaseItem> = emptyList(),
    val collections: List<BaseItem> = emptyList(),
    val discovered: List<DiscoverItem> = emptyList(),
    val chosenStreams: ChosenStreams? = null,
    val canDelete: Boolean = false,
) {
    val movie: BaseItem? = (loading as? DataLoadingState.Success<BaseItem>)?.data
}
