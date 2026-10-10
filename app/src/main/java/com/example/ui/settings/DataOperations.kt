package com.example.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings' long data operations (exporting, backing up, restoring, deleting everything), run on the application's own
 * scope, one at a time.
 *
 * They ran on the Settings screen's scope, and a rotation (or anything else that recreates the activity) cancelled it
 * part-way: the work itself is non-cancellable and finished, but what came after it was skipped. A restore then showed no
 * message and did not rebuild the activity, so the restored language and theme appeared only after a restart; the
 * progress dialog, kept in the screen's own state, disappeared while the restore was still running, and nothing stopped
 * "Delete all data" from starting beside it (a production review, 2026-10-10). Here the progress ([busy]) outlives the
 * screen, a second operation waits for none to be running, and a finished restore or wipe asks the activity that is
 * showing to rebuild itself ([rebuild], read by MainActivity), not the one that started it.
 */
object DataOperations {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _busy = MutableStateFlow<String?>(null)

    /** What is running, for the progress dialog; null when nothing is. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _rebuild = MutableStateFlow(0)

    /** Bumped when an operation changed the settings themselves: the activity on screen recreates itself. */
    val rebuild: StateFlow<Int> = _rebuild.asStateFlow()

    /** Starts [block] with [message] in the progress dialog. False, and nothing started, while another one runs. */
    fun run(message: String, block: suspend () -> Unit): Boolean {
        if (_busy.value != null) return false
        _busy.value = message
        scope.launch {
            try {
                block()
            } finally {
                _busy.value = null
            }
        }
        return true
    }

    fun requestRebuild() {
        _rebuild.value++
    }
}
