package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import com.v2ray.ang.weather.WeatherMapAlternator
import com.v2ray.ang.market.MarketRatesController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private lateinit var subGroupAdapter: SubGroupAdapter
    private var trafficJob: Job? = null
    private var geoLocationJob: Job? = null
    private var subscriptionRefreshJob: Job? = null
    private var startupJob: Job? = null
    private var updateCheckJob: Job? = null
    private var preserveMapDuringServerRestart = false
    private var weatherAlternator: WeatherMapAlternator? = null
    private var marketController: MarketRatesController? = null

    /** Fades the server list away while connected so the scenery shows through. */
    private val configListAutoHider by lazy {
        ConfigListAutoHider(
            this,
            listOf(binding.rvSubGroups, binding.viewPager, binding.layoutTest),
            onVisibilityChanged = { configsVisible ->
                if (configsVisible) {
                    weatherAlternator?.hideOverlay()
                    marketController?.setSceneVisible(false)
                } else {
                    weatherAlternator?.showOverlayAfterDelay(4_000L)
                    marketController?.setSceneVisible(true)
                }
            }
        )
    }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.toolbar.title = ""

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        marketController = MarketRatesController(this, binding.marketRates)
        // The controller must exist before drawer toggles read its default state.
        setupNavigationDrawer()

        setupToggleView()
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        binding.btnUpdateSub.setOnClickListener { animateTopAction(it) { importConfigViaSub() } }
        binding.btnAutoConnect.setOnClickListener { animateTopAction(it) { autoConnectBestServer() } }
        binding.btnAddClipboard.setOnClickListener { animateTopAction(it) { importClipboard() } }
        binding.btnAddConfig.setOnClickListener { view ->
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            animateTopAction(view) { showImportMenu(view) }
        }

        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()
        updateMapDestination()

        checkAndRequestPermissionWithResult(PermissionType.POST_NOTIFICATIONS) { granted ->
            if (!granted) {
                toast("${getString(R.string.toast_permission_denied)}  ${PermissionType.POST_NOTIFICATIONS.getLabel()}")
            }
            // PermissionHelper keeps one pending callback, so the location request
            // must wait until the notification flow has fully settled.
            setupWeatherScene()
        }

        startupJob = lifecycleScope.launch {
            delay(3000)
            checkForUpdatesAuto()
        }
    }

    /**
     * The weather pane alternates with the world map every 10 s.  Permission is
     * requested once; on denial the alternator still runs against cached data
     * (or stays on the map if no snapshot exists yet).
     */
    private fun setupWeatherScene() {
        val alternator = WeatherMapAlternator(this, binding.cinematicMap, binding.cinematicWeather)
        weatherAlternator = alternator
        alternator.setEnabled(MmkvManager.decodeSettingsBool("weather_scene_enabled", false))
        checkAndRequestPermissionWithResult(PermissionType.LOCATION) {
            alternator.start()
        }
    }

    private fun checkForUpdatesAuto() {
        updateCheckJob?.cancel()
        updateCheckJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val lastCheck = MmkvManager.decodeSettingsLong("last_update_check_time", 0L)
                val now = System.currentTimeMillis()
                if (now - lastCheck < 24 * 60 * 60 * 1000L) return@launch

                MmkvManager.encodeSettings("last_update_check_time", now)
                val result = UpdateCheckerManager.checkForUpdate(false)
                if (result.hasUpdate) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(result)
                    }
                }
            } catch (e: Exception) {
                LogUtil.d(AppConfig.TAG, "Auto update check failed: ${e.message}")
            }
        }
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, result.latestVersion))
            .setMessage(result.releaseNotes)
            .setPositiveButton(R.string.update_now) { _, _ ->
                result.downloadUrl?.let { Utils.openUri(this, it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupNavigationDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        // Telegram-style: show app version at the bottom of the drawer
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: ""
            val navMenu = binding.navView.menu
            val placeholderItem = navMenu.findItem(R.id.placeholder)
            placeholderItem?.let {
                it.title = "v2rayNG $versionName"
                it.isEnabled = false
            }
        } catch (_: Exception) {
        }
        setupWidgetToggles()
        setupDrawerClock()
        setupDailyDrawerScrollHint()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun animateTopAction(view: View, action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        view.animate().cancel()
        view.animate()
            .scaleX(.92f)
            .scaleY(.92f)
            .alpha(.72f)
            .setDuration(90)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                    .start()
            }
            .start()
        action()
    }

    private fun setupDrawerClock() {
        val header = binding.navView.getHeaderView(0)
        val clock = header.findViewById<android.widget.TextView>(R.id.tv_drawer_clock)
        val jalali = header.findViewById<android.widget.TextView>(R.id.tv_drawer_jalali)
        val gregorian = header.findViewById<android.widget.TextView>(R.id.tv_drawer_gregorian)
        val calendar = header.findViewById<DrawerCalendarView>(R.id.drawer_calendar)
        lifecycleScope.launch {
            calendar.setMonthData(CalendarOccasionRepository.currentMonth())
        }
        lifecycleScope.launch {
            while (isActive) {
                val now = Date()
                clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                gregorian.text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(now)
                jalali.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.icu.text.SimpleDateFormat(
                        "EEEE، d MMMM yyyy",
                        Locale.forLanguageTag("fa-IR-u-ca-persian")
                    ).format(now)
                } else {
                    SimpleDateFormat("EEEE، d MMMM yyyy", Locale("fa", "IR")).format(now)
                }
                calendar.invalidate()
                delay(20_000L)
            }
        }
    }

    /**
     * Once per day, gently reveals that the drawer contains more items below
     * the large calendar, then returns the user to the top.
     */
    private fun setupDailyDrawerScrollHint() {
        val preferences = getSharedPreferences("drawer_scroll_hint", MODE_PRIVATE)
        var animationRunning = false
        binding.drawerLayout.addDrawerListener(
            object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: android.view.View) {
                    if (drawerView !== binding.navView || animationRunning) return
                    val todayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    if (preferences.getString("last_shown_date", "") == todayKey) return

                    val menuList = binding.navView.getChildAt(0)
                        as? androidx.recyclerview.widget.RecyclerView ?: return
                    menuList.postDelayed({
                        if (!binding.drawerLayout.isDrawerOpen(binding.navView) ||
                            !menuList.canScrollVertically(1)
                        ) {
                            return@postDelayed
                        }
                        animationRunning = true
                        preferences.edit().putString("last_shown_date", todayKey).apply()
                        val distance = (resources.displayMetrics.density * 165f).toInt()
                        val interpolator =
                            android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)
                        menuList.smoothScrollBy(0, distance, interpolator, 1350)
                        menuList.postDelayed({
                            if (binding.drawerLayout.isDrawerOpen(binding.navView)) {
                                menuList.smoothScrollBy(0, -distance, interpolator, 1550)
                            }
                            menuList.postDelayed({ animationRunning = false }, 1600)
                        }, 1850)
                    }, 550)
                }
            }
        )
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
            configListAutoHider.onConnectionChanged(isRunning)
            weatherAlternator?.nudgeRefresh()
            if (!isRunning) {
                weatherAlternator?.hideOverlay()
            }
            if (!isRunning && preserveMapDuringServerRestart) {
                return@observe
            }
            if (isRunning) preserveMapDuringServerRestart = false
            updateMapDestination(isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        val names = groups.map { group ->
            subscriptionGroupTitle(group.id, group.remarks, includeCount = false)
        }
        if (!::subGroupAdapter.isInitialized) {
            subGroupAdapter = SubGroupAdapter { position ->
                binding.viewPager.setCurrentItem(position, true)
            }
            binding.rvSubGroups.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            binding.rvSubGroups.adapter = subGroupAdapter
        }
        subGroupAdapter.submitList(names)

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)
        subGroupAdapter.setSelected(targetIndex)

        binding.rvSubGroups.isVisible = groups.size > 1

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                subGroupAdapter.setSelected(position)
                val group = groups.getOrNull(position) ?: return@onPageSelected
                mainViewModel.subscriptionId = group.id
            }
        })

        refreshGroupTabTitles(true)
    }

    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        val groups = mainViewModel.getSubscriptions(this)
        val names = groups.map { group ->
            subscriptionGroupTitle(group.id, group.remarks, includeCount = true)
        }
        subGroupAdapter.submitList(names)
    }

    private fun subscriptionGroupTitle(
        subscriptionId: String,
        fallback: String,
        includeCount: Boolean
    ): String {
        val subscription = MmkvManager.decodeSubscription(subscriptionId)
        val username = subscription
            ?.let { AngConfigManager.resolveSubscriptionUsername(it) }
            .orEmpty()
        val title = username.ifBlank { fallback }
        return if (includeCount) {
            "$title (${MmkvManager.decodeServerList(subscriptionId).size})"
        } else {
            title
        }
    }

    private fun setupToggleView() {
        binding.fabToggle.setOnCheckedChangeListener { checked ->
                if (checked) {
                    // Turn on
                    applyRunningState(isLoading = true, isRunning = false)
                    if (mainViewModel.isRunning.value == true) {
                        CoreServiceManager.stopVService(this@MainActivity)
                    } else if (SettingsManager.isVpnMode()) {
                        val intent = VpnService.prepare(this@MainActivity)
                        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
                    } else {
                        startV2Ray()
                    }
                } else {
                    // Turn off
                    if (mainViewModel.isRunning.value == true) {
                        CoreServiceManager.stopVService(this@MainActivity)
                    }
                    applyRunningState(false, false)
                }
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        CoreServiceManager.startVService(this)
    }

    private fun autoConnectBestServer() {
        val candidates = mainViewModel.serversCache.toList()
        if (candidates.isEmpty()) {
            toast(R.string.auto_connect_unavailable)
            return
        }
        binding.btnAutoConnect.isEnabled = false
        toast(R.string.auto_connect_testing)
        lifecycleScope.launch {
            val measured = withContext(Dispatchers.IO) {
                coroutineScope {
                    candidates.map { item ->
                        async {
                            val ping = com.v2ray.ang.util.DirectPingManager.measure(
                                applicationContext,
                                item.profile.server,
                                item.profile.serverPort
                            )
                            if (ping > 0L) item.guid to ping else null
                        }
                    }.awaitAll().filterNotNull().minByOrNull { it.second }
                }
            }
            binding.btnAutoConnect.isEnabled = true
            if (measured == null) {
                toast(R.string.auto_connect_unavailable)
                return@launch
            }

            MmkvManager.setSelectServer(measured.first)
            mainViewModel.updateListAction.value = -1
            toast(getString(R.string.auto_connect_selected, measured.second))
            updateMapDestination(mainViewModel.isRunning.value == true)

            if (mainViewModel.isRunning.value == true) {
                restartV2Ray()
            } else if (SettingsManager.isVpnMode()) {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
            } else {
                startV2Ray()
            }
        }
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) return

        binding.fabToggle.setChecked(isRunning)

        if (isRunning) {
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    /** Keeps the visual endpoint tied to the selected profile without affecting connection behaviour. */
    fun updateMapDestination(active: Boolean = mainViewModel.isRunning.value == true) {
        val selected = MmkvManager.getSelectServer()
        val profile = selected?.let { MmkvManager.decodeServerConfig(it) }
        geoLocationJob?.cancel()
        if (active) {
            // First fly from the user's normal public location to the selected endpoint.
            // This deliberately does not use a name/TLD fallback, avoiding a misleading
            // intermediate country while the real IP lookup is in progress.
            geoLocationJob = lifecycleScope.launch(Dispatchers.IO) {
                val result = IpGeoLocationResolver.serverLocation(applicationContext, profile?.server)
                if (result != null) withContext(Dispatchers.Main) {
                    binding.cinematicMap.focusLocation(result.latitude, result.longitude, result.country, result.countryCode, true)
                }
                // Do not overwrite the selected server with a second app-level public-IP
                // request here. Some Android VPN implementations exempt their own process,
                // which would report the user's source network and make the marker jump back.
            }
        } else {
            // When disconnected, always use the user's ordinary public IP. Selecting a
            // config must not move the marker until the tunnel is actually turned on.
            geoLocationJob = lifecycleScope.launch(Dispatchers.IO) {
                val result = IpGeoLocationResolver.currentPublicLocation(applicationContext)
                if (result != null) withContext(Dispatchers.Main) {
                    binding.cinematicMap.focusLocation(result.latitude, result.longitude, result.country, result.countryCode, false)
                }
            }
        }
    }

    /** Prevents the transient stop event of a server switch from resetting the map to source. */
    fun preserveMapDuringServerRestart() {
        preserveMapDuringServerRestart = true
    }

    override fun onResume() {
        super.onResume()
        // Android may replace the underlying Wi-Fi/mobile Network while the
        // screen is off. Refresh both direct-ping routing and service state.
        com.v2ray.ang.util.DirectPingManager.refresh(applicationContext)
        mainViewModel.refreshServiceState()
        SubscriptionUpdater.sync()
        autoUpdateSubscriptions()
        startTrafficPolling()
        if (startupJob?.isActive != true) {
            startupJob = lifecycleScope.launch {
                delay(3000)
                checkForUpdatesAuto()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        trafficJob?.cancel()
        trafficJob = null
        geoLocationJob?.cancel()
        geoLocationJob = null
        subscriptionRefreshJob?.cancel()
        subscriptionRefreshJob = null
        startupJob?.cancel()
        startupJob = null
        updateCheckJob?.cancel()
        updateCheckJob = null
        SubscriptionUpdater.pauseAll()
    }

    private fun startTrafficPolling() {
        trafficJob?.cancel()
        trafficJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val subId = mainViewModel.subscriptionId
                var used = 0L
                var total = 0L
                var expire = 0L
                var label = ""

                if (subId.isNotEmpty()) {
                    val subItem = MmkvManager.decodeSubscription(subId)
                    if (subItem != null) {
                        used = subItem.trafficUpload + subItem.trafficDownload
                        total = subItem.trafficTotal
                        expire = subItem.trafficExpire
                        val username = AngConfigManager.resolveSubscriptionUsername(subItem)
                        label = "Username: \"${username.ifBlank { "Unknown" }}\""
                        LogUtil.d(AppConfig.TAG, "TrafficPoll: subId=$subId used=$used total=$total expire=$expire label=$label")
                    } else {
                        LogUtil.d(AppConfig.TAG, "TrafficPoll: subItem is null for subId=$subId")
                    }
                } else {
                    LogUtil.d(AppConfig.TAG, "TrafficPoll: subId is empty")
                }

                withContext(Dispatchers.Main) {
                    if (total > 0) {
                        val remaining = total - used
                        val usedPercent = ((used.toFloat() / total) * 100).toInt().coerceIn(0, 100)
                        val remainPercent = 100 - usedPercent
                        binding.layoutTraffic.visibility = android.view.View.VISIBLE
                        binding.tvTrafficLabel.text = if (label.isNotEmpty()) label else "Traffic"
                        binding.tvTrafficPercent.text = "$remainPercent% Left"

                        // Traffic color: green → red based on remaining
                        val hue = (remainPercent / 100f) * 120f
                        val progressColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.50f))
                        val progressColorLight = android.graphics.Color.HSVToColor(floatArrayOf(hue.coerceAtLeast(0f), 0.90f, 0.62f))

                        val progressFill = binding.trafficProgressFill
                        val gradient = android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(progressColor, progressColorLight)
                        )
                        gradient.cornerRadius = 0f
                        progressFill.background = gradient
                        // The first refresh can run before the traffic track is measured.
                        // Defer width calculation so the colored fill is visible immediately.
                        (progressFill.parent as android.view.View).post {
                            val parentWidth = (progressFill.parent as android.view.View).width
                            progressFill.layoutParams = progressFill.layoutParams.apply {
                                width = (parentWidth * usedPercent / 100f).toInt().coerceAtLeast(0)
                            }
                        }

                        binding.tvTrafficPercent.setTextColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.60f)))

                        binding.tvTrafficUsed.text = "${used.toTrafficString()} Used"
                        binding.tvTrafficRemaining.text = "${remaining.toTrafficString()} Rem"
                        binding.tvTrafficTotal.text = "${total.toTrafficString()} Total"

                        if (expire > 0) {
                            val expireMs = if (expire > 1000000000000L) expire else expire * 1000L
                            val daysLeft = ((expireMs - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
                            binding.tvTrafficExpiry.text = "$daysLeft days remaining"
                            binding.tvTrafficExpiry.visibility = android.view.View.VISIBLE
                        } else {
                            binding.tvTrafficExpiry.visibility = android.view.View.GONE
                        }
                    } else {
                        binding.tvTrafficLabel.text = if (label.isNotEmpty()) label else "Traffic"
                        binding.tvTrafficPercent.text = "-"
                        binding.trafficProgressFill.layoutParams.width = 0
                        binding.trafficProgressFill.requestLayout()
                        binding.tvTrafficUsed.text = "0 B Used"
                        binding.tvTrafficRemaining.text = "Unlimited"
                        binding.tvTrafficTotal.text = "- Total"
                        binding.tvTrafficExpiry.visibility = android.view.View.GONE
                    }
                }
                delay(5000)
            }
        }
    }

    private fun autoUpdateSubscriptions() {
        subscriptionRefreshJob?.cancel()
        subscriptionRefreshJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            if (result.configCount > 0) {
                withContext(Dispatchers.Main) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    private fun showImportMenu(anchor: View) {
        val items = listOf(
            GlassMenuItem(label = getString(R.string.menu_item_import_config_qrcode)) { importQRcode() },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_clipboard)) { importClipboard() },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_local)) { importConfigLocal() },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_policy_group)) { importManually(EConfigType.POLICYGROUP.value) },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_proxy_chain)) { importManually(EConfigType.PROXYCHAIN.value) },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_manually_vmess)) { importManually(EConfigType.VMESS.value) },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_manually_vless)) { importManually(EConfigType.VLESS.value) },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_manually_ss)) { importManually(EConfigType.SHADOWSOCKS.value) },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_manually_socks)) { importManually(EConfigType.SOCKS.value) },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_manually_http)) { importManually(EConfigType.HTTP.value) },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_manually_trojan)) { importManually(EConfigType.TROJAN.value) },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_manually_wireguard)) { importManually(EConfigType.WIREGUARD.value) },
            GlassMenuItem(label = getString(R.string.menu_item_import_config_manually_hysteria2)) { importManually(EConfigType.HYSTERIA2.value) },
        )
        GlassMenuHelper.show(anchor, items)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_proxy_chain -> {
            importManually(EConfigType.PROXYCHAIN.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else if (createConfigType == EConfigType.PROXYCHAIN.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerProxyChainActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            refreshGroupTabTitles()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    /** A tap while the list is faded restores it before anything else reacts. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && configListAutoHider.onUserInteraction()) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.weather_toggle -> {
                item.actionView?.findViewById<WidgetToggleView>(R.id.widget_switch)?.let {
                    it.isChecked = !it.isChecked
                }
                return true
            }
            R.id.market_toggle -> {
                item.actionView?.findViewById<WidgetToggleView>(R.id.widget_switch)?.let {
                    it.isChecked = !it.isChecked
                }
                return true
            }
            R.id.market_items -> binding.drawerLayout.postDelayed({ showMarketItemsDialog() }, 220)
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.report_bug -> Utils.openUri(this, "${AppConfig.APP_ISSUES_URL}?t=${System.currentTimeMillis()}")
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun setupWidgetToggles() {
        val menu = binding.navView.menu
        val weatherItem = menu.findItem(R.id.weather_toggle)
        val marketItem = menu.findItem(R.id.market_toggle)
        val weatherSwitch = weatherItem.actionView?.findViewById<WidgetToggleView>(R.id.widget_switch)
        val marketSwitch = marketItem.actionView?.findViewById<WidgetToggleView>(R.id.widget_switch)

        val weatherEnabled = MmkvManager.decodeSettingsBool("weather_scene_enabled", false)
        val marketEnabled = marketController?.isEnabled() == true
        weatherSwitch?.setCheckedImmediately(weatherEnabled)
        marketSwitch?.setCheckedImmediately(marketEnabled)
        menu.findItem(R.id.market_items).isVisible = marketEnabled

        weatherSwitch?.setOnCheckedChangeListener { enabled ->
            MmkvManager.encodeSettings("weather_scene_enabled", enabled)
            weatherAlternator?.setEnabled(enabled)
        }
        marketSwitch?.setOnCheckedChangeListener { enabled ->
            marketController?.setEnabled(enabled)
            menu.findItem(R.id.market_items).isVisible = enabled
        }
    }

    private fun showMarketItemsDialog() {
        val assets = MarketRatesController.ASSETS
        val selected = marketController?.selected()?.toMutableSet() ?: mutableSetOf("usd", "eur")
        val labels = mapOf(
            "usd" to R.string.asset_usd, "eur" to R.string.asset_eur,
            "gold" to R.string.asset_gold, "gbp" to R.string.asset_gbp,
            "try" to R.string.asset_try, "iqd" to R.string.asset_iqd
        )
        val items = assets.map { asset ->
            GlassMenuItem(
                label = getString(labels.getValue(asset.id)),
                checkable = true,
                selected = asset.id in selected,
                dismissOnClick = false
            ) {
                if (asset.id in selected) selected -= asset.id else selected += asset.id
                if (selected.isEmpty()) selected += "usd"
                marketController?.setSelected(selected)
            }
        } + GlassMenuItem(label = getString(R.string.market_done)) {}
        GlassMenuHelper.show(binding.root, items, (270 * resources.displayMetrics.density).toInt())
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
