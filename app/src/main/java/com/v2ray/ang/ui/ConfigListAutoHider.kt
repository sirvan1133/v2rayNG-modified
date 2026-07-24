package com.v2ray.ang.ui

import android.view.View
import android.view.animation.PathInterpolator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Slides the server-list chrome out of frame shortly after the tunnel
 * connects so the cinematic background (map/weather) is unobstructed, and
 * restores it the moment the user disconnects or touches the screen.
 *
 * Uses a compact macOS-like window transition: fade and a subtle scale
 * around the view centre. Views become INVISIBLE (not GONE) after the exit
 * so the underlying layout does not shift.
 */
class ConfigListAutoHider(
    private val owner: LifecycleOwner,
    private val views: List<View>,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    private var hideJob: Job? = null
    private var connected = false
    private var hidden = false

    fun onConnectionChanged(isRunning: Boolean) {
        connected = isRunning
        if (isRunning) scheduleHide(INITIAL_DELAY_MS) else reveal()
    }

    /** Call from dispatchTouchEvent; returns true when the touch was consumed to reveal. */
    fun onUserInteraction(): Boolean {
        if (!connected) return false
        val consumed = hidden
        reveal()
        scheduleHide(REHIDE_DELAY_MS)
        return consumed
    }

    private fun scheduleHide(delayMs: Long) {
        hideJob?.cancel()
        hideJob = owner.lifecycleScope.launch {
            delay(delayMs)
            if (!connected) return@launch
            hidden = true
            for (view in views) {
                view.animate().cancel()
                view.animate()
                    .alpha(0f)
                    .scaleX(HIDDEN_SCALE)
                    .scaleY(HIDDEN_SCALE)
                    .translationY(0f)
                    .setDuration(HIDE_MS)
                    .setInterpolator(MAC_EASING)
                    .withEndAction {
                        if (hidden) {
                            view.visibility = View.INVISIBLE
                            if (view === views.lastOrNull()) onVisibilityChanged(false)
                        }
                    }
                    .start()
            }
        }
    }

    private fun reveal() {
        hideJob?.cancel()
        if (!hidden && views.firstOrNull()?.alpha == 1f) return
        hidden = false
        onVisibilityChanged(true)
        for (view in views) {
            view.animate().cancel()
            view.visibility = View.VISIBLE
            if (view.alpha == 0f) {
                view.scaleX = HIDDEN_SCALE
                view.scaleY = HIDDEN_SCALE
            }
            view.translationY = 0f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SHOW_MS)
                .setInterpolator(MAC_EASING)
                .withEndAction(null)
                .start()
        }
    }

    companion object {
        private const val INITIAL_DELAY_MS = 3_000L
        private const val REHIDE_DELAY_MS = 6_000L
        private const val HIDE_MS = 320L
        private const val SHOW_MS = 420L
        private const val HIDDEN_SCALE = .965f
        private val MAC_EASING = PathInterpolator(.16f, 1f, .3f, 1f)
    }
}
