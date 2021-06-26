package org.jellyfin.androidtv.integration

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.*
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.TvApp
import org.jellyfin.androidtv.di.systemApiClient
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.ImageUtils
import org.jellyfin.androidtv.util.dp
import org.jellyfin.androidtv.util.sdk.isUsable
import org.jellyfin.apiclient.model.entities.MediaType
import org.jellyfin.apiclient.model.querying.ItemSortBy
import org.jellyfin.sdk.api.client.KtorClient
import org.jellyfin.sdk.api.operations.*
import org.jellyfin.sdk.model.api.*
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.ZoneOffset

/**
 * Manages channels on the android tv home screen.
 *
 * More info: https://developer.android.com/training/tv/discovery/recommendations-channel.
 */
class LeanbackChannelWorker(
	private val context: Context,
	workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams), KoinComponent {
	companion object {
		/**
		 * Amount of ticks found in a millisecond, used for calculation.
		 */
		private const val TICKS_IN_MILLISECOND = 10000

		const val PERIODIC_UPDATE_REQUEST_NAME = "LeanbackChannelPeriodicUpdateRequest"
	}

	private val apiClient by inject<KtorClient>(systemApiClient)
	private val userViewsApi by lazy { UserViewsApi(apiClient) }
	private val userLibrarypi by lazy { UserLibraryApi(apiClient) }
	private val itemsApi by lazy { ItemsApi(apiClient) }
	private val imageApi by lazy { ImageApi(apiClient) }
	private val tvShowsApi by lazy { TvShowsApi(apiClient) }
	private val userPreferences by inject<UserPreferences>()

	/**
	 * Check if the app can use Leanback features and is API level 26 or higher.
	 */
	private val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
		// Check for leanback support
		context.packageManager.hasSystemFeature("android.software.leanback")
		// Check for "android.media.tv" provider to workaround a false-positive in the previous check
		&& context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

	/**
	 * Update all channels for the currently authenticated user.
	 */
	@ExperimentalStdlibApi
	override suspend fun doWork(): Result = when {
		// Fail when not supported
		!isSupported -> Result.failure()
		// Retry later if no authenticated user is found
		!apiClient.isUsable -> Result.retry()
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
	 * Get the uri for a channel or create it if it doesn't exist. Uses the [settings] parameter to
	 * update or create the channel. The [name] parameter is used to store the id and should be
	 * unique.
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
		ResourcesCompat.getDrawable(context.resources, R.drawable.app_icon, null)?.let {
			ChannelLogoUtils.storeChannelLogo(context, ContentUris.parseId(uri), it.toBitmap(80.dp, 80.dp))
		}

		return uri
	}

	/**
	 * Updates the "my media" row with current media libraries.
	 */
	private suspend fun updateMyMedia() {
		// Get channel
		val channelUri = getChannelUri("my_media", Channel.Builder()
				.setType(TvContractCompat.Channels.TYPE_PREVIEW)
				.setDisplayName(context.getString(R.string.lbl_my_media))
				.setAppLinkIntent(Intent(context, StartupActivity::class.java))
				.build())

		val response by userViewsApi.getUserViews(includeHidden = false)

		// Add new items
		val items = response.items
			.orEmpty()
			.filterNot { it.collectionType in ItemRowAdapter.ignoredCollectionTypes }
			.map { item ->
				val imageUri = if (item.imageTags?.contains(ImageType.PRIMARY) == true)
					imageApi.getItemImageUrl(item.id, ImageType.PRIMARY).toUri()
				else
					ImageUtils.getResourceUrl(context, R.drawable.tile_land_tv).toUri()

				PreviewProgram.Builder()
					.setChannelId(ContentUris.parseId(channelUri))
					.setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
					.setTitle(item.name)
					.setPosterArtUri(imageUri)
					.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
					.setIntent(Intent(context, StartupActivity::class.java).apply {
						putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
						putExtra(StartupActivity.EXTRA_ITEM_IS_USER_VIEW, true)
					})
					.build()
					.toContentValues()
			}.toTypedArray()
		context.contentResolver.bulkInsert(TvContractCompat.PreviewPrograms.CONTENT_URI, items)
	}

	/**
	 * Gets the latest items or returns null
	 */
	@ExperimentalStdlibApi
	private suspend fun getLatestItems(): Map<String, List<BaseItemDto>?>? {
		val EXCLUDED_COLLECTION_TYPES = arrayOf("playlists", "livetv", "boxsets", "channels", "books")

		// Get user or return if no user is found (not authenticated)
		val configuration = TvApp.getApplication()?.currentUser!!.configuration
		val latestItemsExcludes = configuration.latestItemsExcludes

		val user = TvApp.getApplication()?.currentUser ?: return null
		val views by userViewsApi.getUserViews(user.id.toUUID(), includeHidden = false)

		return buildMap {
			views.items
				.orEmpty()
				.filterNot { item -> item.collectionType in EXCLUDED_COLLECTION_TYPES || item.id.toString() in latestItemsExcludes }
				.forEach { item ->
					// Create query and add it to a new row
					val items = userLibrarypi.getLatestMedia(
							userId = TvApp.getApplication()!!.currentUser?.id?.toUUIDOrNull() ?: return null,
							imageTypeLimit = 1,
							limit = 25,
							fields = listOf(ItemFields.DATE_CREATED, ItemFields.TAGLINES),
							parentId = item.id,
							groupItems = true
					)
					put(item.name!!, items.content)
				}
		}
	}

	/**
	 * Updates the "latest movies" row with current media libraries
	 */
	private suspend fun updateLatestItems(latestItems: Map<String, List<BaseItemDto>?>?) {
		if (latestItems != null) {
			latestItems.forEach { (k, v) ->
				// Get channel
				val channelUri = getChannelUri("latest_" + k, Channel.Builder()
						.setType(TvContractCompat.Channels.TYPE_PREVIEW)
						.setDisplayName(context.getString(R.string.lbl_latest) + " " + k)
						.setAppLinkIntent(Intent(context, StartupActivity::class.java))
						.build())

				// Add new items
				v?.map { item ->
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
									.setDescription(if (item.taglines != null && item.taglines!!.size > 0) item.taglines!![0] else "")
									.setPosterArtUri(imageUri)
									.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
									.setIntent(Intent(context, StartupActivity::class.java).apply {
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
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
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
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
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
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
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
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
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
									})
									.build()
									.toContentValues()
						"PhotoAlbum" ->
							PreviewProgram.Builder()
									.setChannelId(ContentUris.parseId(channelUri))
									.setType(WatchNextPrograms.TYPE_ALBUM)
									.setTitle(item.name)
									.setPosterArtUri(imageUri)
									.setIntent(Intent(context, StartupActivity::class.java).apply {
										putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
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
	 * Gets the poster art for an item. Uses the [preferParentThumb] parameter to fetch the series
	 * image when preferred.
	 */
	private fun BaseItemDto.getPosterArtImageUrl(preferParentThumb: Boolean): Uri = when {
		(preferParentThumb || imageTags?.contains(ImageType.PRIMARY) != true)
			&& parentThumbItemId?.toUUIDOrNull() != null -> imageApi.getItemImageUrl(
			itemId = parentThumbItemId!!.toUUIDOrNull()!!,
			imageType = ImageType.THUMB,
			format = ImageFormat.WEBP,
			maxHeight = 512,
			maxWidth = 512
		)
		else -> imageApi.getItemImageUrl(
			itemId = id,
			imageType = ImageType.PRIMARY,
			format = ImageFormat.WEBP,
			maxHeight = 512,
			maxWidth = 512
		)
	}.let(Uri::parse)

	/**
	 * Gets the next up episodes or returns null.
	 */
	private suspend fun getNextUpItems(): BaseItemDtoQueryResult? {
		return tvShowsApi.getNextUp(
			userId = apiClient.userId,
			imageTypeLimit = 1,
			limit = 10,
			fields = listOf(ItemFields.DATE_CREATED)
		).content
	}

	/**
	 * Gets the resumable items or returns null
	 */
	private suspend fun getResumeItems(): BaseItemDtoQueryResult? {
		// Get user or return if no user is found (not authenticated)
		val userId = TvApp.getApplication()?.currentUser?.id?.toUUIDOrNull() ?: return null

		return itemsApi.getItems(
				mediaTypes = listOf(MediaType.Video),
				recursive = true,
				enableTotalRecordCount = false,
				collapseBoxSetItems = false,
				excludeLocationTypes = listOf(LocationType.VIRTUAL),
				filters = listOf(ItemFilter.IS_RESUMABLE),
				sortBy = listOf(ItemSortBy.DateCreated),
				sortOrder = listOf(SortOrder.DESCENDING),

				userId = userId,
				imageTypeLimit = 1,
				limit = 5,
				fields = listOf(ItemFields.DATE_CREATED)
		).content

	}

	/**
	 * Updates the "next up" row with current episodes. Uses the [nextUpItems] parameter to store
	 * items returned by a NextUpQuery().
	 */
	private suspend fun updateNextUp(nextUpItems: BaseItemDtoQueryResult?) {
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
					putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
				})
				.build()
				.toContentValues()
		}?.let { context.contentResolver.bulkInsert(TvContractCompat.PreviewPrograms.CONTENT_URI, it.toTypedArray()) }
	}

	/**
	 * Updates the "watch next" row with new and unfinished episodes. Does not include movies, music
	 * or other types of media. Uses the [nextUpItems] parameter to store items returned by a
	 * NextUpQuery().
	 */
	private suspend fun updateWatchNext(resumeItems: BaseItemDtoQueryResult?, nextUpItems: BaseItemDtoQueryResult?) {
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
	 * Convert [BaseItemDto] to [WatchNextProgram]. Assumes the item type is "episode".
	 */
	private fun getBaseItemAsWatchNextProgram(item: BaseItemDto) = WatchNextProgram.Builder().apply {
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		setInternalProviderId(item.id.toString())

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
		setLastEngagementTimeUtcMillis(item.dateCreated?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
			?: System.currentTimeMillis())

		when {
			// User has started playing the episode
			item.userData?.playbackPositionTicks ?: 0 > 0 -> {
				setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
				setLastPlaybackPositionMillis((item.userData!!.playbackPositionTicks / TICKS_IN_MILLISECOND).toInt())
				setLastEngagementTimeUtcMillis(item.userData!!.lastPlayedDate?.toInstant(ZoneOffset.UTC)!!.toEpochMilli())
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
		item.runTimeTicks?.let { runTimeTicks ->
			setDurationMillis((runTimeTicks / TICKS_IN_MILLISECOND).toInt())
		}

		// Set intent to open the episode
		setIntent(Intent(context, StartupActivity::class.java).apply {
			putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
		})
	}.build()
}
