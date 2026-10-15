package org.jellyfin.mobile.player.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ServerState
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.BaseItemDto
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Uses the normal downloaded-video playback path without a Jellyfin server. */
@SdkSuppress(minSdkVersion = 26)
class PlayerPictureInPictureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val koin = GlobalContext.get()
    private val preferences = koin.get<AppPreferences>()
    private val database = koin.get<JellyfinDatabase>()
    private val sharedPreferences = context.getSharedPreferences(
        "${context.packageName}_preferences",
        Context.MODE_PRIVATE
    )
    private val previousServerId = preferences.currentServerId
    private val previousUserId = preferences.currentUserId
    private val previousBackgroundAudio =
        sharedPreferences.all[Constants.PREF_EXOPLAYER_ALLOW_BACKGROUND_AUDIO] as Boolean?
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var activity: MainActivity
    private lateinit var fragment: PlayerFragment
    private lateinit var viewModel: PlayerViewModel
    private lateinit var video: File
    private var serverId = 0L
    private var userId = 0L
    private var downloadId = 0L

    @Before
    fun playDownloadedVideo() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        preferences.currentServerId = null
        preferences.currentUserId = null
        sharedPreferences.edit { putBoolean(Constants.PREF_EXOPLAYER_ALLOW_BACKGROUND_AUDIO, false) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity = it }
        await("Initial connection screen") { main { activity.mainViewModel.serverState.value is ServerState.Unset } }

        val itemId = createDownload()
        main {
            koin.get<ActivityEventHandler>().emit(
                ActivityEvent.LaunchNativePlayer(
                    PlayOptions(
                        ids = listOf(itemId),
                        mediaSourceId = itemId.toString(),
                        startIndex = 0,
                        startPosition = null,
                        audioStreamIndex = null,
                        subtitleStreamIndex = null,
                        playFromDownloads = true
                    ),
                ),
            )
        }
        await("Native player fragment") {
            main { activity.supportFragmentManager.findFragmentById(R.id.fragment_container) is PlayerFragment }
        }
        main {
            fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_container) as PlayerFragment
            viewModel = ViewModelProvider(fragment)[PlayerViewModel::class.java]
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        await("Video playback in landscape") {
            main {
                viewModel.playerOrNull?.isPlaying == true && viewModel.playerOrNull!!.currentPosition > 250 &&
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    activity.hasWindowFocus()
            }
        }
        // Configuration changes precede the fullscreen/rotation animation finishing.
        SystemClock.sleep(2000)
        await("Video still playing after rotation") { main { viewModel.playerOrNull?.isPlaying == true } }
    }

    private fun createDownload(): UUID {
        val itemId = UUID.randomUUID()
        val hostname = "http://127.0.0.1:9/$itemId"
        serverId = database.serverDao.insert(hostname)
        userId = database.userDao.upsert(serverId, UUID.randomUUID(), "instrumentation-test")
        preferences.currentServerId = serverId
        preferences.currentUserId = userId
        runBlocking { koin.get<ApiClientController>().loadSavedServerUser() }

        video = File(context.cacheDir, "$itemId.mp4")
        instrumentation.context.assets.open("pip-video.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        val item = instrumentation.context.assets.open("pip-video.json").bufferedReader().use {
            Json.decodeFromString<BaseItemDto>(it.readText()).copy(id = itemId)
        }
        runBlocking {
            downloadId = database.downloadDao.insert(
                DownloadEntity(
                    serverId = serverId,
                    userId = userId,
                    itemId = itemId,
                    path = video.path,
                    item = item,
                    status = DownloadStatus.DOWNLOADED,
                ),
            )
            database.downloadDao.insertFile(
                DownloadFileEntity(
                    downloadId = downloadId,
                    type = DownloadFileType.ITEM,
                    size = video.length(),
                    fileName = video.name,
                    uri = Uri.fromFile(video),
                    status = DownloadStatus.DOWNLOADED
                ),
            )
        }
        return itemId
    }

    @After
    fun cleanUp() {
        scenario?.close()
        preferences.currentServerId = previousServerId
        preferences.currentUserId = previousUserId
        sharedPreferences.edit {
            when (val value = previousBackgroundAudio) {
                null -> remove(Constants.PREF_EXOPLAYER_ALLOW_BACKGROUND_AUDIO)
                else -> putBoolean(Constants.PREF_EXOPLAYER_ALLOW_BACKGROUND_AUDIO, value)
            }
        }
        runBlocking {
            koin.get<ApiClientController>().loadSavedServerUser()
            if (downloadId != 0L) database.downloadDao.delete(downloadId)
        }
        // Remove only the account created by this test, preserving any existing device data.
        database.runInTransaction {
            if (userId != 0L) {
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM user WHERE id = ?",
                    arrayOf(userId)
                )
            }
            if (serverId != 0L) {
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM server WHERE id = ?",
                    arrayOf(serverId)
                )
            }
        }
        if (::video.isInitialized) video.delete()
    }

    @Test
    fun homeGestureKeepsVideoPlayingInPictureInPicture() {
        val position = main { viewModel.playerOrNull!!.currentPosition }
        gestureHome()
        assertPictureInPictureStaysOpen()
        assertTrue("Video must keep playing", main { viewModel.playerOrNull?.isPlaying == true })
        assertTrue("Playback must advance", main { viewModel.playerOrNull!!.currentPosition > position })
    }

    @Test
    fun returningToFullscreenAllowsAnotherPictureInPictureTransition() {
        gestureHome()
        assertPictureInPictureStaysOpen()
        returnToFullscreen()
        gestureHome()
        assertPictureInPictureStaysOpen()
    }

    @Test
    fun pausedVideoCanEnterPictureInPicture() {
        main { viewModel.pause() }
        await("Paused video") { main { viewModel.playerOrNull?.isPlaying == false } }
        gestureHome()
        assertPictureInPictureStaysOpen()
        assertFalse("Entering PiP must preserve pause", main { viewModel.playerOrNull!!.playWhenReady })
    }

    @Test
    fun leavingPlayerDisablesPictureInPicture() {
        main { activity.supportFragmentManager.popBackStackImmediate() }
        await("Player removed") { main { !fragment.isAdded } }
        gestureHome()
        assertFalse("The connection screen must not enter PiP", main { activity.isInPictureInPictureMode })
    }

    @Test
    fun endingPlaybackInPictureInPictureReturnsToTheApp() {
        gestureHome()
        assertPictureInPictureStaysOpen()
        main { viewModel.playerOrNull!!.let { it.seekTo(it.duration) } }
        await("Playback ended") { main { viewModel.playerOrNull == null } }
        returnToFullscreen()
        await("Player removed after playback ended") { main { !fragment.isAdded } }
        gestureHome()
        assertFalse(
            "Completed playback must not leave automatic PiP enabled",
            main { activity.isInPictureInPictureMode }
        )
    }

    private fun gestureHome() {
        // The connection form can leave an IME open behind the added player fragment.
        main {
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            controller.hide(WindowInsetsCompat.Type.ime())
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        await("Keyboard hidden") {
            main {
                ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
        }
        SystemClock.sleep(500)
        // Input coordinates are display coordinates, not the possibly inset/resized decor bounds.
        val size = Point()
        @Suppress("DEPRECATION")
        main { activity.windowManager.defaultDisplay.getRealSize(size) }
        // Keep a nonzero release velocity; shell swipes can intermittently open Recents.
        fun swipe() {
            val start = SystemClock.uptimeMillis()
            for (step in 0..12) {
                val action = when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    12 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                SystemClock.sleep((start + step * 10 - SystemClock.uptimeMillis()).coerceAtLeast(0))
                val event = MotionEvent.obtain(
                    start,
                    SystemClock.uptimeMillis(),
                    action,
                    size.x / 2f,
                    size.y - 2f - size.y * 0.75f * step / 12f,
                    0
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    assertTrue("Gesture injection", instrumentation.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
            }
        }
        swipe()
        SystemClock.sleep(500)
        // A transient system bar can consume the first swipe; retry only while the app has focus.
        if (main { activity.hasWindowFocus() && !activity.isInPictureInPictureMode }) swipe()
        await("Home gesture completed") { main { activity.isInPictureInPictureMode } || isHomeResumed() }
    }

    private fun isHomeResumed(): Boolean {
        val home = shell(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        ).lineSequence().lastOrNull { it.contains('/') }?.trim()
            ?.let(ComponentName::unflattenFromString) ?: return false
        return shell("dumpsys activity activities").lineSequence().any {
            (it.contains("topResumedActivity") || it.contains("mResumedActivity")) &&
                (it.contains(home.flattenToString()) || it.contains(home.flattenToShortString()))
        }
    }

    private fun returnToFullscreen() {
        main {
            activity.startActivity(
                Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        await("Return from PiP") { main { activity.hasWindowFocus() && !activity.isInPictureInPictureMode } }
    }

    private fun assertPictureInPictureStaysOpen() {
        await("Picture-in-picture entry") { main { activity.isInPictureInPictureMode } }
        repeat(20) {
            assertTrue("PiP must stay open", main { activity.isInPictureInPictureMode })
            SystemClock.sleep(100)
        }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }

    private fun <T : Any> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(description, condition())
    }
}
