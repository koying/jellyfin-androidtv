package org.jellyfin.androidtv.integration

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.di.systemApiClient
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.ImageUtils
import org.jellyfin.androidtv.util.dp
import org.jellyfin.androidtv.util.sdk.isUsable
import org.jellyfin.apiclient.model.dto.BaseItemType
import org.jellyfin.apiclient.model.entities.MediaType
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.ZoneOffset

import org.jellyfin.androidtv.TvApp

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

	private val api by inject<ApiClient>(systemApiClient)
	private val userPreferences by inject<UserPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()

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
		!api.isUsable -> Result.retry()
		else -> {
			// Get next up episodes
			val (resumeItems, nextUpItems) = getNextUpItems()

			// Get latest movies
			val latestItems = getLatestItems()

			// Get resumable items
			// Delete current items from the My Media and Next Up channels
			context.contentResolver.delete(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null)

			// Update various channels
			updateMyMedia()
			updateLatestItems(latestItems);
			updateNextUp(nextUpItems)
			updateWatchNext(resumeItems + nextUpItems)

			// Success!
			Result.success()
		}
	}

	/**
	 * Get the uri for a channel or create it if it doesn't exist. Uses the [settings] parameter to
	 * update or create the channel. The [name] parameter is used to store the id and should be
	 * unique.
	 */
	private fun getChannelUri(name: String, settings: Channel, default: Boolean = false): Uri {
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
				if (default) {
					// Set as default row to display (we can request one row to automatically be added to the home screen)
					TvContractCompat.requestChannelBrowsable(context, ContentUris.parseId(uri))
				}
			}
		}

		// Update logo
		ResourcesCompat.getDrawable(context.resources, R.drawable.app_icon, null)?.let {
			ChannelLogoUtils.storeChannelLogo(context, ContentUris.parseId(uri), it.toBitmap(80.dp(context), 80.dp(context)))
		}

		return uri
	}

	/**
	 * Updates the "my media" row with current media libraries.
	 */
	@Suppress("RestrictedApi")
	private suspend fun updateMyMedia() {
		// Get channel
		val channelUri = getChannelUri("my_media", Channel.Builder()
			.setType(TvContractCompat.Channels.TYPE_PREVIEW)
			.setDisplayName(context.getString(R.string.lbl_my_media))
			.setAppLinkIntent(Intent(context, StartupActivity::class.java))
			.build(), default = true)

		val response by api.userViewsApi.getUserViews(includeHidden = false)

		// Add new items
		val items = response.items
			.orEmpty()
			.filter { userViewsRepository.isSupported(it.collectionType) }
			.map { item ->
				val imageUri = if (item.imageTags?.contains(ImageType.PRIMARY) == true)
					api.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY).toUri()
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

		// Get configuration (to find excluded items)
		val configuration = TvApp.getApplication()!!.currentUser!!.configuration

		// Create a list of views to include
		val latestItemsExcludes = configuration.latestItemsExcludes

		val response by api.userViewsApi.getUserViews(includeHidden = false)

		return buildMap {
			response.items
				.orEmpty()
				.filterNot { it.collectionType in EXCLUDED_COLLECTION_TYPES || it.id.toString() in latestItemsExcludes }
				.forEach { item ->
					// Create query and add it to a new row
					val items = api.userLibraryApi.getLatestMedia(
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
			&& parentThumbItemId?.toUUIDOrNull() != null -> api.imageApi.getItemImageUrl(
			itemId = parentThumbItemId!!.toUUIDOrNull()!!,
			imageType = ImageType.THUMB,
			format = ImageFormat.WEBP,
			maxHeight = 512,
			maxWidth = 512
		)
		else -> api.imageApi.getItemImageUrl(
			itemId = id,
			imageType = ImageType.PRIMARY,
			format = ImageFormat.WEBP,
			maxHeight = 512,
			maxWidth = 512
		)
	}.let(Uri::parse)

	/**
	 * Gets the resume and next up episodes. The returned pair contains two lists:
	 * 1. resume items
	 * 2. next up items
	 */
	private suspend fun getNextUpItems(): Pair<List<BaseItemDto>, List<BaseItemDto>> = withContext(Dispatchers.IO) {
		val resume = async {
			api.itemsApi.getResumeItems(
				fields = listOf(ItemFields.DATE_CREATED),
				imageTypeLimit = 1,
				limit = 10,
				mediaTypes = listOf(MediaType.Video)
			).content.items.orEmpty().filter { item ->
				// Movies are not supported right now
				item.type.equals(BaseItemType.Episode.toString(), true)
			}
		}

		val nextup = async {
			api.tvShowsApi.getNextUp(
				userId = api.userId,
				imageTypeLimit = 1,
				limit = 10,
				fields = listOf(ItemFields.DATE_CREATED),
			).content.items.orEmpty()
		}

		// Concat
		Pair(resume.await(), nextup.await())
	}

	/**
	 * Updates the "next up" row with current episodes. Uses the [nextUpItems] parameter to store
	 * items returned by a NextUpQuery().
	 */
	@Suppress("RestrictedApi")
	private fun updateNextUp(nextUpItems: List<BaseItemDto>) {
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		// Get channel
		val channelUri = getChannelUri("next_up", Channel.Builder()
			.setType(TvContractCompat.Channels.TYPE_PREVIEW)
			.setDisplayName(context.getString(R.string.lbl_next_up))
			.setAppLinkIntent(Intent(context, StartupActivity::class.java))
			.build())

		// Add new items
		nextUpItems.map { item ->
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
		}.let { context.contentResolver.bulkInsert(TvContractCompat.PreviewPrograms.CONTENT_URI, it.toTypedArray()) }
	}

	/**
	 * Updates the "watch next" row with new and unfinished episodes. Does not include movies, music
	 * or other types of media. Uses the [nextUpItems] parameter to store items returned by a
	 * NextUpQuery().
	 */
	private fun updateWatchNext(nextUpItems: List<BaseItemDto>) {
		// Delete current items
		context.contentResolver.delete(WatchNextPrograms.CONTENT_URI, null, null)

		// Add new items
		context.contentResolver.bulkInsert(
			WatchNextPrograms.CONTENT_URI,
			nextUpItems.map { item -> getBaseItemAsWatchNextProgram(item).toContentValues() }.toTypedArray()
		)
	}

	/**
	 * Convert [BaseItemDto] to [WatchNextProgram]. Assumes the item type is "episode".
	 */
	@Suppress("RestrictedApi")
	private fun getBaseItemAsWatchNextProgram(item: BaseItemDto) = WatchNextProgram.Builder().apply {
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		setInternalProviderId(item.id.toString())
		setType(WatchNextPrograms.TYPE_TV_EPISODE)
		setTitle("${item.seriesName} - ${item.name}")

		// Poster image
		val imageUri = item.getPosterArtImageUrl(preferParentThumb)
		setPosterArtUri(imageUri)
		setPosterArtAspectRatio(WatchNextPrograms.ASPECT_RATIO_16_9)

		// Use date created or fallback to current time if unavailable
		var engagement = item.dateCreated

		when {
			// User has started playing the episode
			item.userData?.playbackPositionTicks ?: 0 > 0 -> {
				setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
				setLastPlaybackPositionMillis((item.userData!!.playbackPositionTicks / TICKS_IN_MILLISECOND).toInt())
				// Use last played date to prioritze
				engagement = item.userData?.lastPlayedDate
			}
			// First episode of the season
			item.indexNumber == 1 -> setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_NEW)
			// Default
			else -> setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
		}

		setLastEngagementTimeUtcMillis(engagement?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
			?: System.currentTimeMillis())

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
