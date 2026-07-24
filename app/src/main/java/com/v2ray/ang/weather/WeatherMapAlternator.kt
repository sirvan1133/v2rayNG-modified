package com.v2ray.ang.weather

import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.ui.CinematicWorldMapView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controls the weather overlay on top of the world map.
 * Weather is shown/hidden based on VPN connection state with slide animations.
 */
class WeatherMapAlternator(
    private val owner: LifecycleOwner,
    private val mapView: CinematicWorldMapView,
    private val weatherView: CinematicWeatherView
) : DefaultLifecycleObserver {

    private var refreshJob: Job? = null
    private var visibilityJob: Job? = null
    private var hasWeather = false
    private var overlayVisible = false
    private var enabled = true

    init {
        owner.lifecycle.addObserver(this)
        weatherView.visibility = View.INVISIBLE
        weatherView.alpha = 0f
        weatherView.translationX = 0f
    }

    /** Call once location permission is settled; starts data refresh. */
    fun start() {
        WeatherRepository.cached()?.let {
            hasWeather = true
            weatherView.showWeather(it.copy(stale = true))
        }
        startRefresh()
    }

    override fun onResume(owner: LifecycleOwner) {
        if (refreshJob != null) startRefresh()
    }

    override fun onPause(owner: LifecycleOwner) {
        refreshJob?.cancel()
    }

    private fun startRefresh() {
        refreshJob?.cancel()
        refreshJob = owner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshOnce()
                delay(if (hasWeather) REFRESH_INTERVAL_MS else FIRST_FETCH_RETRY_MS)
            }
        }
    }

    fun nudgeRefresh() {
        if (hasWeather) return
        refreshJob?.cancel()
        refreshJob = owner.lifecycleScope.launch(Dispatchers.IO) {
            delay(2_000)
            while (isActive) {
                refreshOnce()
                delay(if (hasWeather) REFRESH_INTERVAL_MS else FIRST_FETCH_RETRY_MS)
            }
        }
    }

    private suspend fun refreshOnce() {
        val context = weatherView.context.applicationContext
        val fix = WeatherLocationSource.resolve(context)
        val snapshot = when {
            fix != null -> WeatherRepository.current(fix.latitude, fix.longitude)
            else -> WeatherRepository.cached()?.copy(stale = true)
        }
        withContext(Dispatchers.Main) {
            if (snapshot != null) {
                hasWeather = true
                weatherView.showWeather(snapshot)
            } else if (!hasWeather) {
                weatherView.showPlaceholder(weatherView.context.getString(R.string.weather_loading))
            }
        }
    }

    /** Show weather overlay with a pure cross-dissolve. */
    fun showOverlay() {
        visibilityJob?.cancel()
        if (!enabled) return
        if (overlayVisible) return
        overlayVisible = true
        weatherView.animate().cancel()
        weatherView.alpha = 0f
        weatherView.translationX = 0f
        weatherView.scaleX = 1f
        weatherView.scaleY = 1f
        weatherView.visibility = View.VISIBLE
        weatherView.replayEntrance()
        weatherView.animate()
            .alpha(0.88f)
            .setDuration(1_200)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()
    }

    fun showOverlayAfterDelay(delayMs: Long) {
        visibilityJob?.cancel()
        if (!enabled) return
        visibilityJob = owner.lifecycleScope.launch {
            delay(delayMs)
            showOverlay()
        }
    }

    /** Hide weather overlay with a pure cross-dissolve. */
    fun hideOverlay() {
        visibilityJob?.cancel()
        if (!overlayVisible) return
        overlayVisible = false
        weatherView.animate().cancel()
        weatherView.animate()
            .alpha(0f)
            .setDuration(900)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                // Fully parked: INVISIBLE stops the Choreographer loop so the
                // map keeps its whole frame budget while the overlay is away.
                if (!overlayVisible) weatherView.visibility = View.INVISIBLE
            }
            .start()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) hideOverlay() else nudgeRefresh()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 15 * 60 * 1000L
        private const val FIRST_FETCH_RETRY_MS = 25_000L
    }
}
