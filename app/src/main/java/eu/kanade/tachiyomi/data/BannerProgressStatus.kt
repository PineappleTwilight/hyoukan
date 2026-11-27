package eu.kanade.tachiyomi.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn

open class BannerProgressStatus {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isRunning = MutableStateFlow(false)

    val isRunning = _isRunning
        .debounce(1000L) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    suspend fun start() {
        _isRunning.emit(true)
    }

    /**
     * Marks the job finished without suspending.
     *
     * This is called from `WorkManager`'s cancel path and from a broadcast receiver's stop action,
     * neither of which is a coroutine, and it used to be a `suspend` function wrapped in
     * `runBlocking`. A [MutableStateFlow] write never actually suspends, so exposing the
     * non-suspending form is what the callers should have had all along.
     */
    fun stop() {
        _isRunning.value = false
    }

    val progress = MutableStateFlow(0f)

    suspend fun updateProgress(progress: Float) {
        this.progress.emit(progress)
    }
}

@Inject
@SingleIn(AppScope::class)
class LibraryUpdateStatus : BannerProgressStatus()

@Inject
@SingleIn(AppScope::class)
class SyncStatus : BannerProgressStatus()

@Inject
@SingleIn(AppScope::class)
class BackupRestoreStatus : BannerProgressStatus()
