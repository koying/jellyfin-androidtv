package org.jellyfin.androidtv.integration

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.*
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.TvApp
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.ImageUtils
import org.jellyfin.androidtv.util.apiclient.getItems
import org.jellyfin.androidtv.util.apiclient.getLatestItems
import org.jellyfin.androidtv.util.apiclient.getNextUpEpisodes
import org.jellyfin.androidtv.util.apiclient.getUserViews
import org.jellyfin.androidtv.util.dp
import org.jellyfin.apiclient.interaction.ApiClient
import org.jellyfin.apiclient.model.drawing.ImageFormat
import org.jellyfin.apiclient.model.dto.BaseItemDto
import org.jellyfin.apiclient.model.dto.ImageOptions
import org.jellyfin.apiclient.model.entities.ImageType
import org.jellyfin.apiclient.model.entities.LocationType
import org.jellyfin.apiclient.model.entities.MediaType
import org.jellyfin.apiclient.model.querying.*
import org.koin.core.component.KoinApiExtension
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Manages channels on the android tv home screen
 *
 * More info: https://developer.android.com/training/tv/discovery/recommendations-channel
 */
@KoinApiExtension
class LeanbackChannelWorker(
	private val context: Context,
	workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams), KoinComponent {
	companion object {
		/**
		 * Amount of ticks found in a millisecond, used for calculation
		 */
		private const val TICKS_IN_MILLISECOND = 10000

		const val SINGLE_UPDATE_REQUEST_NAME = "LeanbackChannelSingleUpdateRequest"
		const val PERIODIC_UPDATE_REQUEST_NAME = "LeanbackChannelPeriodicUpdateRequest"
	}

	private val apiClient by inject<ApiClient>()
	private val userPreferences by inject<UserPreferences>()

	/**
	 * Check if the app can use Leanback features and is API level 26 or higher
	 */
	private val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
		&& context.packageManager.hasSystemFeature("android.software.leanback")

	/**
	 * Update all channels for the currently authenticated user
	 */
	@ExperimentalStdlibApi
	override suspend fun doWork(): Result = when {
		// Fail when not supported
		!isSupported -> Result.failure()
		// Retry later if no authenticated user is found
		TvApp.getApplication().currentUser == null -> Result.retry()
		else -> {
			// Get next up episodes
			val nextUpItems = getNextUpItems()

			// Get latest movies
			val latestItems = getLatestItems()

			// Get resumable items
			val resumeItems = getResumeItems()

			// Delete current items from the My Media and Next Up channels
			context.contentResolver.delete(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null)

			// Update various channels
			updateMyMedia()
			updateLatestItems(latestItems);
			updateNextUp(nextUpItems)
			updateWatchNext(resumeItems, nextUpItems)

			// Success!
			Result.success()
		}
	}

	/**
	 * Get the uri for a channel or create it if it doesn't exist.
	 * Uses the [settings] parameter to update or create the channel.
	 * The [name] parameter is used to store the id and should be unique.
	 */
	private fun getChannelUri(name: String, settings: Channel): Uri {
		val store = context.getSharedPreferences("leanback_channels", Context.MODE_PRIVATE)

		val uri = if (store.contains(name)) {
			// Retrieve uri and update content resolver
			Uri.parse(store.getString(name, null)).also { uri ->
				context.contentResolver.update(uri, settings.toContentValues(), null, null)
			}
		} else {
			// Create new channel and save uri
			context.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, settings.toContentValues())!!.also { uri ->
				store.edit().putString(name, uri.toString()).apply()
			}

			// Set as default row to display (we can request one row to automatically be added to the home screen)
			// Should be enabled when we add a row that we want to display by default
			// TvContractCompat.requestChannelBrowsable(application, ContentUris.parseId(uri))
		}

		// Update logo
		ResourcesCompat.getDrawable(context.resources, R.drawable.ic_jellyfin, null)?.let {
			ChannelLogoUtils.storeChannelLogo(context, ContentUris.parseId(uri), it.toBitmap(80.dp, 80.dp))
		}

		return uri
	}

		/**
	 * Updates the "my media" row with current media libraries
	 */
	private suspend fun updateMyMedia() {
		// Get channel
		val channelUri = getChannelUri("my_media", Channel.Builder()
				.setType(TvContractCompat.Channels.TYPE_PREVIEW)
				.setDisplayName(context.getString(R.string.lbl_my_media))
				.setAppLinkIntent(Intent(context, StartupActivity::class.java))
				.build())

		val response = apiClient.getUserViews() ?: return

		// Add new items
		context.contentResolver.bulkInsert(TvContractCompat.PreviewPrograms.CONTENT_URI, response.items.map { item ->
			val imageUri = if (item.hasPrimaryImage) Uri.parse(apiClient.GetImageUrl(item, ImageOptions()))
			else Uri.parse(ImageUtils.getResourceUrl(context, R.drawable.tile_land_tv))

			PreviewProgram.Builder()
				.setChannelId(ContentUris.parseId(channelUri))
				.setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
				.setTitle(item.name)
				.setPosterArtUri(imageUri)
				.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
				.setIntent(Intent(context, StartupActivity::class.java).apply {
					putExtra(StartupActivity.EXTRA_ITEM_ID, item.id)
					putExtra(StartupActivity.EXTRA_ITEM_IS_USER_VIEW, true)
				})
				.build()
				.toContentValues()
		}.toTypedArray())
	}

	/**
	 * Gets the latest items or returns null
	 */
	@ExperimentalStdlibApi
	private suspend fun getLatestItems(): Map<String, ItemsResult?>? = withContext(Dispatchers.Default) {
		val EXCLUDED_COLLECTION_TYPES = arrayOf("playlists", "livetv", "boxsets", "channels", "books")

		// Get user or return if no user is found (not authenticated)
		val user = TvApp.getApplication().currentUser ?: return@withContext null
		val configuration = TvApp.getApplication().currentUser!!.configuration
		val latestItemsExcludes = configuration.latestItemsExcludes

		val views = apiClient.getUserViews() ?: return@withContext null

		return@withContext buildMap {
			views.items
				.filterNot { item -> item.collectionType in EXCLUDED_COLLECTION_TYPES || item.id in latestItemsExcludes }
				.forEach { item ->
					// Create query and add it to a new row
					val query = LatestItemsQuery().apply {
						imageTypeLimit = 1
						limit = 25
						fields = arrayOf(ItemFields.DateCreated, ItemFields.Taglines)
						parentId = item.id
						groupItems = true
					}
					query.userId = user.id
					var items = ItemsResult()
					items.setItems(apiClient.getLatestItems(query))
					put(item.name, items)
				}
		}
	}

	/**
	 * Updates the "latest movies" row with current media libraries
	 */
	private suspend fun updateLatestItems(latestItems: Map<String, ItemsResult?>?) = withContext(Dispatchers.Default) {
		if (latestItems != null) {
			latestItems.forEach { (k, v) ->
				// Get channel
				val channelUri = getChannelUri("latest_" + k, Channel.Builder()
						.setType(TvContractCompat.Channels.TYPE_PREVIEW)
						.setDisplayName(context.getString(R.string.lbl_latest) + " " + k)
						.setAppLinkIntent(Intent(context, StartupActivity::class.java))
						.build())

				// Add new items
				v?.items?.map { item ->
					val imageUri = item.getPosterArtImageUrl(false)

					val seasonString = item.parentIndexNumber?.toString().orEmpty()

					val episodeString = when {
						item.indexNumberEnd != null && item.indexNumber != null ->
							"${item.indexNumber}-${item.indexNumberEnd}"
						else -> item.indexNumber?.toString().orEmpty()
					}

					when (item.type) {
						"Movie" ->
							PreviewProgram.Builder()
									.setChannelId(ContentUris.parseId(channelUri))
									.setType(WatchNextPrograms.TYPE_MOVIE)
									.setTitle(item.name)
									.setDescription(if (item.taglines.size > 0) item.taglines[0] else "")
									.setPosterArtUri(imageUri)
									.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
									.setIntent(Intent(context, StartupActivity::class.java).apply {
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id)
									})
									.build()
									.toContentValues()
						"Episode" ->
							PreviewProgram.Builder()
									.setChannelId(ContentUris.parseId(channelUri))
									.setType(WatchNextPrograms.TYPE_TV_EPISODE)
									.setTitle(item.seriesName)
									.setEpisodeTitle(item.name)
									.setSeasonNumber(seasonString, item.parentIndexNumber ?: 0)
									.setEpisodeNumber(episodeString, item.indexNumber ?: 0)
									.setPosterArtUri(imageUri)
									.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
									.setIntent(Intent(context, StartupActivity::class.java).apply {
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id)
									})
									.build()
									.toContentValues()
						"Series" ->
							PreviewProgram.Builder()
									.setChannelId(ContentUris.parseId(channelUri))
									.setType(WatchNextPrograms.TYPE_TV_SERIES)
									.setTitle(item.name)
									.setPosterArtUri(imageUri)
									.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
									.setIntent(Intent(context, StartupActivity::class.java).apply {
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id)
									})
									.build()
									.toContentValues()
						"MusicAlbum" ->
							PreviewProgram.Builder()
									.setChannelId(ContentUris.parseId(channelUri))
									.setType(WatchNextPrograms.TYPE_ALBUM)
									.setTitle(item.name)
									.setDescription(if (item.albumArtist != null) item.albumArtist else "")
									.setPosterArtUri(imageUri)
									.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
									.setIntent(Intent(context, StartupActivity::class.java).apply {
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id)
									})
									.build()
									.toContentValues()
						"MusicArtist" ->
							PreviewProgram.Builder()
									.setChannelId(ContentUris.parseId(channelUri))
									.setType(WatchNextPrograms.TYPE_ARTIST)
									.setTitle(item.name)
									.setPosterArtUri(imageUri)
									.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
									.setIntent(Intent(context, StartupActivity::class.java).apply {
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id)
									})
									.build()
									.toContentValues()
						else -> null
					}
				}?.filterNotNull()?.let { context.contentResolver.bulkInsert(TvContractCompat.PreviewPrograms.CONTENT_URI, it.toTypedArray()) }
			}
		}
	}


	/**
	 * Gets the poster art for an item
	 * Uses the [preferParentThumb] parameter to fetch the series image when preferred
	 */
	private fun BaseItemDto.getPosterArtImageUrl(preferParentThumb: Boolean): Uri? {
		return if (preferParentThumb && this.parentThumbItemId != null) {
			Uri.parse(apiClient.GetImageUrl(this.seriesId, ImageOptions().apply {
				format = ImageFormat.Png
				height = 288
				width = 512
				imageType = ImageType.Thumb
			}))
		} else {
			Uri.parse(apiClient.GetImageUrl(this, ImageOptions().apply {
				format = ImageFormat.Png
			}))
		}
	}

	/**
	 * Gets the next up episodes or returns null
	 */
	private suspend fun getNextUpItems(): ItemsResult? = withContext(Dispatchers.Default) {
		// Get user or return if no user is found (not authenticated)
		val user = TvApp.getApplication().currentUser ?: return@withContext null

		return@withContext apiClient.getNextUpEpisodes(NextUpQuery().apply {
			userId = user?.id
			imageTypeLimit = 1
			limit = 10
			fields = arrayOf(ItemFields.DateCreated)
		})
	}

	/**
	 * Gets the resumable items or returns null
	 */
	private suspend fun getResumeItems(): ItemsResult? = withContext(Dispatchers.Default) {
		// Get user or return if no user is found (not authenticated)
		val user = TvApp.getApplication().currentUser ?: return@withContext null

		val query = ItemQuery().apply {
			mediaTypes = arrayOf(MediaType.Video)
			recursive = true
			enableTotalRecordCount = false
			collapseBoxSetItems = false
			excludeLocationTypes = arrayOf(LocationType.Virtual)
			filters = arrayOf(ItemFilter.IsResumable)
			sortBy = arrayOf(ItemSortBy.DatePlayed)
			sortOrder = org.jellyfin.apiclient.model.entities.SortOrder.Descending
		}

		return@withContext apiClient.getItems(query.apply {
			userId = user?.id
			imageTypeLimit = 1
			limit = 5
			fields = arrayOf(ItemFields.DateCreated)
		})
	}

	/**
	 * Updates the "next up" row with current episodes
	 * Uses the [nextUpItems] parameter to store items returned by a NextUpQuery()
	 */
	private suspend fun updateNextUp(nextUpItems: ItemsResult?) = withContext(Dispatchers.Default) {
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		// Get channel
		val channelUri = getChannelUri("next_up", Channel.Builder()
				.setType(TvContractCompat.Channels.TYPE_PREVIEW)
				.setDisplayName(context.getString(R.string.lbl_next_up))
				.setAppLinkIntent(Intent(context, StartupActivity::class.java))
				.build())

		// Add new items
		nextUpItems?.items?.map { item ->
			val imageUri = item.getPosterArtImageUrl(preferParentThumb)

			val seasonString = item.parentIndexNumber?.toString().orEmpty()

			val episodeString = when {
				item.indexNumberEnd != null && item.indexNumber != null ->
					"${item.indexNumber}-${item.indexNumberEnd}"
				else -> item.indexNumber?.toString().orEmpty()
			}

			PreviewProgram.Builder()
				.setChannelId(ContentUris.parseId(channelUri))
				.setType(WatchNextPrograms.TYPE_TV_EPISODE)
				.setTitle(item.seriesName)
				.setEpisodeTitle(item.name)
				.setSeasonNumber(seasonString, item.parentIndexNumber ?: 0)
				.setEpisodeNumber(episodeString, item.indexNumber ?: 0)
				.setPosterArtUri(imageUri)
				.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
				.setIntent(Intent(context, StartupActivity::class.java).apply {
					putExtra(StartupActivity.EXTRA_ITEM_ID, item.id)
				})
				.build()
				.toContentValues()
		}?.let { context.contentResolver.bulkInsert(TvContractCompat.PreviewPrograms.CONTENT_URI, it.toTypedArray()) }
	}

	/**
	 * Updates the "watch next" row with new and unfinished episodes
	 * Does not include movies, music or other types of media
	 * Uses the [nextUpItems] parameter to store items returned by a NextUpQuery()
	 */
	private suspend fun updateWatchNext(resumeItems: ItemsResult?, nextUpItems: ItemsResult?) = withContext(Dispatchers.Default) {
		// Delete current items
		context.contentResolver.delete(WatchNextPrograms.CONTENT_URI, null, null)

		// Add new items
		resumeItems?.items?.let { items ->
			context.contentResolver.bulkInsert(
					WatchNextPrograms.CONTENT_URI,
					items.map { item -> getBaseItemAsWatchNextProgram(item).toContentValues() }.toTypedArray()
			)
		}
		nextUpItems?.items?.let { items ->
			context.contentResolver.bulkInsert(
					WatchNextPrograms.CONTENT_URI,
					items.map { item -> getBaseItemAsWatchNextProgram(item).toContentValues() }.toTypedArray()
			)
		}
	}

	/**
	 * Convert [BaseItemDto] to [WatchNextProgram]
	 *
	 * Assumes the item type is "episode"
	 */
	private fun getBaseItemAsWatchNextProgram(item: BaseItemDto) = WatchNextProgram.Builder().apply {
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		setInternalProviderId(item.id)

		when (item.type) {
			"Movie" -> {
				setType(WatchNextPrograms.TYPE_MOVIE)
				setTitle("${item.name}")

				// Poster image
				val imageUri = item.getPosterArtImageUrl(false)
				setPosterArtUri(imageUri)
				setPosterArtAspectRatio(WatchNextPrograms.ASPECT_RATIO_MOVIE_POSTER)
			}

			else -> {
				setType(WatchNextPrograms.TYPE_TV_EPISODE)
				setTitle("${item.seriesName} - ${item.name}")

				// Poster image
				val imageUri = item.getPosterArtImageUrl(preferParentThumb)
				setPosterArtUri(imageUri)
				setPosterArtAspectRatio(WatchNextPrograms.ASPECT_RATIO_16_9)
			}
		}

		// Use date created or fallback to current time if unavailable
		setLastEngagementTimeUtcMillis(item.dateCreated?.time ?: System.currentTimeMillis())

		when {
			// User has started playing the episode
			item.canResume -> {
				setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
				setLastPlaybackPositionMillis((item.resumePositionTicks / TICKS_IN_MILLISECOND).toInt())
				setLastEngagementTimeUtcMillis(item.userData?.lastPlayedDate?.time!!)
			}
			// First episode of the season
			item.indexNumber == 1 -> {
				setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_NEW)
			}
			// Default
			else -> {
				setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
			}
		}

		// Episode runtime has been determined
		if (item.runTimeTicks != null) {
			setDurationMillis((item.runTimeTicks / TICKS_IN_MILLISECOND).toInt())
		}

		// Set intent to open the episode
		setIntent(Intent(context, StartupActivity::class.java).apply {
			putExtra(StartupActivity.EXTRA_ITEM_ID, item.id)
		})
	}.build()
}
