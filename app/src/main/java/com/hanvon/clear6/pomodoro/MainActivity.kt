package com.hanvon.clear6.pomodoro

import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var rootContainer: LinearLayout
    private lateinit var btnTest5sOverlay: Button
    private lateinit var btnMinimizeBg: Button
    private lateinit var btnExitApp: Button
    private lateinit var tvSubtitleRule: TextView
    private lateinit var tvPhaseBanner: TextView
    private lateinit var layoutWorkTimerStage: LinearLayout
    private lateinit var restingCatView: RestingCatEInkView
    private lateinit var tvTimerDisplay: TextView
    private lateinit var tvTimerUnitHint: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var btnStartPause: Button
    private lateinit var btnSkipPhase: Button
    private lateinit var btnReset: Button
    private lateinit var btnToggleSettings: Button
    private lateinit var layoutSettingsDrawer: LinearLayout

    // 时长自定义控件（默认折叠隐藏，点击「时长与设置」展开）
    private lateinit var btnCycleDurationPreset: Button
    private lateinit var btnWorkMinus: Button
    private lateinit var btnWorkPlus: Button
    private lateinit var btnRestMinus: Button
    private lateinit var btnRestPlus: Button

    // 锁屏规则开关、锁屏通知提示开关与音效控件
    private lateinit var btnToggleLockRule: Button
    private lateinit var btnToggleLockscreenNotif: Button
    private lateinit var btnCycleSoundPreset: Button
    private lateinit var cbHighSpeedMode: CheckBox
    private lateinit var cbMinuteTick: CheckBox
    private lateinit var tvStatsSummary: TextView
    private lateinit var btnOverlayPermission: Button
    private lateinit var btnBatteryWhitelist: Button
    private lateinit var btnNotificationSettings: Button
    private lateinit var btnAutoStartSettings: Button

    private var isSettingsExpanded: Boolean = false
    private var lastBackPressTime: Long = 0L
    private var currentWorkMinutes: Int = 15
    private var currentRestMinutes: Int = 5
    private var currentLockRule = PomodoroForegroundService.LockRuleMode.ABORT_ON_LOCK
    private var currentLockscreenNotif: Boolean = true
    private var currentSoundPreset = MinimalistSoundEngine.SoundPreset.CRISP_TICK
    private val flashHandler = Handler(Looper.getMainLooper())

    // 常用番茄钟预设组 (工作分钟 to 休息分钟)
    private val durationPresets = listOf(
        15 to 5,
        25 to 5,
        30 to 5,
        45 to 10
    )

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PomodoroForegroundService.BROADCAST_UI_STATE -> {
                    val phase = intent.getStringExtra(PomodoroForegroundService.EXTRA_PHASE) ?: "WORK"
                    val runState = intent.getStringExtra(PomodoroForegroundService.EXTRA_RUN_STATE) ?: "RUNNING"
                    val remainingSec = intent.getIntExtra(PomodoroForegroundService.EXTRA_REMAINING_SEC, 900)
                    val workMin = intent.getIntExtra(PomodoroForegroundService.EXTRA_WORK_MINUTES, 15)
                    val restMin = intent.getIntExtra(PomodoroForegroundService.EXTRA_REST_MINUTES, 5)
                    val lockRuleName = intent.getStringExtra(PomodoroForegroundService.EXTRA_LOCK_RULE) ?: "ABORT_ON_LOCK"
                    val highSpeed = intent.getBooleanExtra(PomodoroForegroundService.EXTRA_HIGH_SPEED, false)
                    val presetName = intent.getStringExtra(PomodoroForegroundService.EXTRA_SOUND_PRESET) ?: "CRISP_TICK"
                    val minuteTick = intent.getBooleanExtra(PomodoroForegroundService.EXTRA_MINUTE_TICK, false)
                    val lockscreenNotif = intent.getBooleanExtra(PomodoroForegroundService.EXTRA_LOCKSCREEN_NOTIF, true)
                    val todayCount = intent.getIntExtra(PomodoroForegroundService.EXTRA_TODAY_POMODOROS, 0)
                    val totalCount = intent.getIntExtra(PomodoroForegroundService.EXTRA_TOTAL_POMODOROS, 0)
                    val eventMsg = intent.getStringExtra(PomodoroForegroundService.EXTRA_LAST_EVENT_MSG) ?: ""

                    renderEInkState(
                        phase, runState, remainingSec, workMin, restMin,
                        lockRuleName, highSpeed, presetName, minuteTick,
                        lockscreenNotif, todayCount, totalCount, eventMsg
                    )
                }
                PomodoroForegroundService.BROADCAST_EINK_FLASH -> {
                    triggerEInkScreenInvertFlash()
                }
                PomodoroForegroundService.BROADCAST_EXIT_APP -> {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupControls()
        updateBatteryButtonStatus()

        sendServiceAction(PomodoroForegroundService.ACTION_SERVICE_INIT)
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        btnTest5sOverlay = findViewById(R.id.btnTest5sOverlay)
        btnMinimizeBg = findViewById(R.id.btnMinimizeBg)
        btnExitApp = findViewById(R.id.btnExitApp)
        tvSubtitleRule = findViewById(R.id.tvSubtitleRule)
        tvPhaseBanner = findViewById(R.id.tvPhaseBanner)
        layoutWorkTimerStage = findViewById(R.id.layoutWorkTimerStage)
        restingCatView = findViewById(R.id.restingCatView)
        tvTimerDisplay = findViewById(R.id.tvTimerDisplay)
        tvTimerUnitHint = findViewById(R.id.tvTimerUnitHint)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        btnStartPause = findViewById(R.id.btnStartPause)
        btnSkipPhase = findViewById(R.id.btnSkipPhase)
        btnReset = findViewById(R.id.btnReset)
        btnToggleSettings = findViewById(R.id.btnToggleSettings)
        layoutSettingsDrawer = findViewById(R.id.layoutSettingsDrawer)

        btnCycleDurationPreset = findViewById(R.id.btnCycleDurationPreset)
        btnWorkMinus = findViewById(R.id.btnWorkMinus)
        btnWorkPlus = findViewById(R.id.btnWorkPlus)
        btnRestMinus = findViewById(R.id.btnRestMinus)
        btnRestPlus = findViewById(R.id.btnRestPlus)

        btnToggleLockRule = findViewById(R.id.btnToggleLockRule)
        btnToggleLockscreenNotif = findViewById(R.id.btnToggleLockscreenNotif)
        btnCycleSoundPreset = findViewById(R.id.btnCycleSoundPreset)
        cbHighSpeedMode = findViewById(R.id.cbHighSpeedMode)
        cbMinuteTick = findViewById(R.id.cbMinuteTick)
        tvStatsSummary = findViewById(R.id.tvStatsSummary)
        btnOverlayPermission = findViewById(R.id.btnOverlayPermission)
        btnBatteryWhitelist = findViewById(R.id.btnBatteryWhitelist)
        btnNotificationSettings = findViewById(R.id.btnNotificationSettings)
        btnAutoStartSettings = findViewById(R.id.btnAutoStartSettings)
    }

    private fun setupControls() {
        // 顶部「5秒测试霸屏」按钮：一键验证在微信读书看书时，5秒后透明黑猫直接跳到书页上方占领屏幕
        btnTest5sOverlay.setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            sendServiceAction(PomodoroForegroundService.ACTION_START_5S_OVERLAY_TEST)
            Toast.makeText(
                this,
                "已开启5秒倒计时！现在请打开微信读书，5秒后黑猫将直接跳上书页！",
                Toast.LENGTH_LONG
            ).show()
            moveTaskToBack(true)
        }

        // 顶部「后台看书」按钮：最小化回到汉王书架打开微信读书，休息时间一到透明黑猫自动跳上书页
        btnMinimizeBg.setOnClickListener {
            if (currentLockRule == PomodoroForegroundService.LockRuleMode.ABORT_ON_LOCK) {
                val intent = Intent(this, PomodoroForegroundService::class.java).apply {
                    action = PomodoroForegroundService.ACTION_SET_LOCK_RULE
                    putExtra(
                        PomodoroForegroundService.EXTRA_LOCK_RULE,
                        PomodoroForegroundService.LockRuleMode.CONTINUE_ON_LOCK.name
                    )
                }
                startServiceCompat(intent)
            }
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            Toast.makeText(
                this,
                "已切入后台计时！休息时间一到，透明黑猫将直接跳到微信读书书页上方",
                Toast.LENGTH_LONG
            ).show()
            moveTaskToBack(true)
        }

        // 顶部「退出」按钮：一键停止后台服务、释放常亮锁、清除通知栏并彻底关闭程序
        btnExitApp.setOnClickListener {
            exitAppCompletely()
        }

        btnStartPause.setOnClickListener {
            sendServiceAction(PomodoroForegroundService.ACTION_TOGGLE_PAUSE)
        }
        btnSkipPhase.setOnClickListener {
            sendServiceAction(PomodoroForegroundService.ACTION_SKIP_PHASE)
        }
        btnReset.setOnClickListener {
            sendServiceAction(PomodoroForegroundService.ACTION_RESET)
        }

        // 展开/收起「时长自定义与规则设置」面板，保持主屏极简干净
        btnToggleSettings.setOnClickListener {
            isSettingsExpanded = !isSettingsExpanded
            layoutSettingsDrawer.visibility = if (isSettingsExpanded) android.view.View.VISIBLE else android.view.View.GONE
            btnToggleSettings.text = if (isSettingsExpanded) "收起设置 ▲" else "时长与设置 ▼"
        }

        // 1. 一键切换常用时长预设（15/5 -> 25/5 -> 30/5 -> 45/10）
        btnCycleDurationPreset.setOnClickListener {
            val currentIdx = durationPresets.indexOfFirst {
                it.first == currentWorkMinutes && it.second == currentRestMinutes
            }
            val nextPair = durationPresets[(currentIdx + 1) % durationPresets.size]
            applyCustomDurations(nextPair.first, nextPair.second)
        }

        // 工作与休息时长自由加减微调
        btnWorkMinus.setOnClickListener {
            val step = if (currentWorkMinutes > 10) 5 else 1
            applyCustomDurations((currentWorkMinutes - step).coerceAtLeast(1), currentRestMinutes)
        }
        btnWorkPlus.setOnClickListener {
            val step = if (currentWorkMinutes >= 10) 5 else 1
            applyCustomDurations((currentWorkMinutes + step).coerceAtMost(180), currentRestMinutes)
        }
        btnRestMinus.setOnClickListener {
            applyCustomDurations(currentWorkMinutes, (currentRestMinutes - 1).coerceAtLeast(1))
        }
        btnRestPlus.setOnClickListener {
            applyCustomDurations(currentWorkMinutes, (currentRestMinutes + 1).coerceAtMost(90))
        }

        // 2. 切换锁屏规则开关（锁屏清零重开 vs 锁屏后台继续计时）
        btnToggleLockRule.setOnClickListener {
            val nextRule = if (currentLockRule == PomodoroForegroundService.LockRuleMode.ABORT_ON_LOCK) {
                PomodoroForegroundService.LockRuleMode.CONTINUE_ON_LOCK
            } else {
                PomodoroForegroundService.LockRuleMode.ABORT_ON_LOCK
            }
            val intent = Intent(this, PomodoroForegroundService::class.java).apply {
                action = PomodoroForegroundService.ACTION_SET_LOCK_RULE
                putExtra(PomodoroForegroundService.EXTRA_LOCK_RULE, nextRule.name)
            }
            startServiceCompat(intent)
        }

        // 2.5 切换「锁屏通知提示」开关（利用 Android Notification API VISIBILITY_PUBLIC 在锁屏界面显示剩余时长）
        btnToggleLockscreenNotif.setOnClickListener {
            val nextEnabled = !currentLockscreenNotif
            val intent = Intent(this, PomodoroForegroundService::class.java).apply {
                action = PomodoroForegroundService.ACTION_SET_LOCKSCREEN_NOTIF
                putExtra(PomodoroForegroundService.EXTRA_LOCKSCREEN_NOTIF, nextEnabled)
            }
            startServiceCompat(intent)
        }

        // 3. 切换并试听极简短促音效
        btnCycleSoundPreset.setOnClickListener {
            val values = MinimalistSoundEngine.SoundPreset.values()
            val nextPreset = values[(currentSoundPreset.ordinal + 1) % values.size]
            val intent = Intent(this, PomodoroForegroundService::class.java).apply {
                action = PomodoroForegroundService.ACTION_SET_SOUND_PRESET
                putExtra(PomodoroForegroundService.EXTRA_SOUND_PRESET, nextPreset.name)
            }
            startServiceCompat(intent)
        }

        cbHighSpeedMode.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, PomodoroForegroundService::class.java).apply {
                action = PomodoroForegroundService.ACTION_SET_HIGH_SPEED
                putExtra(PomodoroForegroundService.EXTRA_HIGH_SPEED, isChecked)
            }
            startServiceCompat(intent)
        }

        cbMinuteTick.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, PomodoroForegroundService::class.java).apply {
                action = PomodoroForegroundService.ACTION_SET_MINUTE_TICK
                putExtra(PomodoroForegroundService.EXTRA_MINUTE_TICK, isChecked)
            }
            startServiceCompat(intent)
        }

        btnOverlayPermission.setOnClickListener {
            if (hasOverlayPermission()) {
                Toast.makeText(this, "跨应用悬浮窗霸屏权限已开启！休息时黑猫将直接跳到微信读书上方", Toast.LENGTH_SHORT).show()
            } else {
                requestOverlayPermission()
            }
        }

        btnBatteryWhitelist.setOnClickListener { requestIgnoreBatteryOptimizations() }

        btnNotificationSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {
                openAppDetailsSettings()
            }
        }

        btnAutoStartSettings.setOnClickListener { openAppDetailsSettings() }
    }

    private fun applyCustomDurations(workMin: Int, restMin: Int) {
        val intent = Intent(this, PomodoroForegroundService::class.java).apply {
            action = PomodoroForegroundService.ACTION_SET_DURATIONS
            putExtra(PomodoroForegroundService.EXTRA_WORK_MINUTES, workMin)
            putExtra(PomodoroForegroundService.EXTRA_REST_MINUTES, restMin)
        }
        startServiceCompat(intent)
    }

    private fun renderEInkState(
        phase: String,
        runState: String,
        remainingSec: Int,
        workMin: Int,
        restMin: Int,
        lockRuleName: String,
        highSpeed: Boolean,
        presetName: String,
        minuteTick: Boolean,
        lockscreenNotif: Boolean,
        todayCount: Int,
        totalCount: Int,
        eventMsg: String
    ) {
        currentWorkMinutes = workMin
        currentRestMinutes = restMin
        currentLockscreenNotif = lockscreenNotif
        currentLockRule = runCatching { PomodoroForegroundService.LockRuleMode.valueOf(lockRuleName) }
            .getOrDefault(PomodoroForegroundService.LockRuleMode.ABORT_ON_LOCK)
        currentSoundPreset = runCatching { MinimalistSoundEngine.SoundPreset.valueOf(presetName) }
            .getOrDefault(MinimalistSoundEngine.SoundPreset.CRISP_TICK)

        if (runState == PomodoroForegroundService.RunState.RUNNING.name) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        tvSubtitleRule.text = "工作${workMin}分 → 休息${restMin}分 循环 · ${currentLockRule.displayName}"

        val formattedTime = if (highSpeed) {
            val m = remainingSec / 60
            val s = remainingSec % 60
            String.format("%02d:%02d", m, s)
        } else {
            val m = (remainingSec + 59) / 60
            String.format("%02d", m)
        }

        if (phase == PomodoroForegroundService.Phase.WORK.name) {
            tvPhaseBanner.text = "当前阶段：专注工作（${workMin} 分钟）"
            tvPhaseBanner.setBackgroundColor(Color.BLACK)
            tvPhaseBanner.setTextColor(Color.WHITE)

            // 工作阶段：显示超大倒计时数字，隐藏趴着的大猫
            layoutWorkTimerStage.visibility = android.view.View.VISIBLE
            restingCatView.visibility = android.view.View.GONE
            restingCatView.setWagging(false)
        } else {
            tvPhaseBanner.text = "当前阶段：放松休息（${restMin} 分钟 · 大猫霸屏休息中）"
            tvPhaseBanner.setBackgroundColor(Color.WHITE)
            tvPhaseBanner.setTextColor(Color.BLACK)

            // 休息阶段：出现趴着摇尾巴的线性大猫挡住屏幕中央！
            layoutWorkTimerStage.visibility = android.view.View.GONE
            restingCatView.visibility = android.view.View.VISIBLE
            restingCatView.setWagging(runState == PomodoroForegroundService.RunState.RUNNING.name)
            restingCatView.updateRestCountdown(
                timeText = formattedTime,
                subtitle = "休息还剩 ${formattedTime} ${if (highSpeed) "" else "分钟"} · 点击大猫摸摸尾巴"
            )
        }

        tvTimerDisplay.text = formattedTime
        tvTimerUnitHint.text = if (highSpeed) {
            "高速刷新模式 · 每秒更新"
        } else {
            "分钟剩余 · 墨水屏按分刷新模式"
        }

        tvStatusSubtitle.text = eventMsg

        btnStartPause.text = when (runState) {
            PomodoroForegroundService.RunState.RUNNING.name -> "暂停计时"
            PomodoroForegroundService.RunState.PAUSED.name -> "继续计时"
            else -> "开始 ${workMin} 分钟工作"
        }

        btnReset.text = "重置(${workMin}分)"
        btnCycleDurationPreset.text = "预设切换：工作${workMin}分 / 休息${restMin}分 (点此切换)"
        btnToggleLockRule.text = "锁屏规则：${currentLockRule.displayName}"
        btnToggleLockscreenNotif.text = if (lockscreenNotif) {
            "锁屏通知提示：已开启（锁屏实时显示剩余时长）"
        } else {
            "锁屏通知提示：已关闭（锁屏隐藏番茄钟通知）"
        }
        btnCycleSoundPreset.text = "提醒音效：${currentSoundPreset.displayName} (点此切换/试听)"

        if (cbHighSpeedMode.isChecked != highSpeed) {
            cbHighSpeedMode.setOnCheckedChangeListener(null)
            cbHighSpeedMode.isChecked = highSpeed
            cbHighSpeedMode.setOnCheckedChangeListener { _, isChecked ->
                val intent = Intent(this, PomodoroForegroundService::class.java).apply {
                    action = PomodoroForegroundService.ACTION_SET_HIGH_SPEED
                    putExtra(PomodoroForegroundService.EXTRA_HIGH_SPEED, isChecked)
                }
                startServiceCompat(intent)
            }
        }

        if (cbMinuteTick.isChecked != minuteTick) {
            cbMinuteTick.setOnCheckedChangeListener(null)
            cbMinuteTick.isChecked = minuteTick
            cbMinuteTick.setOnCheckedChangeListener { _, isChecked ->
                val intent = Intent(this, PomodoroForegroundService::class.java).apply {
                    action = PomodoroForegroundService.ACTION_SET_MINUTE_TICK
                    putExtra(PomodoroForegroundService.EXTRA_MINUTE_TICK, isChecked)
                }
                startServiceCompat(intent)
            }
        }

        tvStatsSummary.text = "今日完成番茄：${todayCount} 个   |   历史累计：${totalCount} 个"
    }

    private fun triggerEInkScreenInvertFlash() {
        flashHandler.removeCallbacksAndMessages(null)
        val intervals = longArrayOf(0L, 350L, 700L, 1050L, 1400L, 1750L)
        intervals.forEachIndexed { index, delayMs ->
            flashHandler.postDelayed({
                val invert = (index % 2 == 0)
                rootContainer.setBackgroundColor(if (invert) Color.BLACK else Color.WHITE)
                tvTimerDisplay.setTextColor(if (invert) Color.WHITE else Color.BLACK)
                tvTimerUnitHint.setTextColor(if (invert) Color.WHITE else Color.BLACK)
            }, delayMs)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    openAppDetailsSettings()
                }
            } else {
                Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Toast.makeText(
                    this,
                    "请开启「显示在其他应用上层」开关，休息时黑猫才能直接跳到微信读书上方霸屏！",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                openAppDetailsSettings()
            }
        }
    }

    private fun updateBatteryButtonStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignored = pm.isIgnoringBatteryOptimizations(packageName)
            btnBatteryWhitelist.text = if (ignored) "电池白名单:已开" else "1.电池白名单"
            val overlayGranted = Settings.canDrawOverlays(this)
            btnOverlayPermission.text = if (overlayGranted) "跨应用霸屏权限：已开启 (可在微信读书上方跳出黑猫)" else "★ 点此开启「跨应用跳出黑猫」悬浮窗权限（必开）"
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun sendServiceAction(actionName: String) {
        val intent = Intent(this, PomodoroForegroundService::class.java).apply {
            action = actionName
        }
        startServiceCompat(intent)
    }

    private fun exitAppCompletely() {
        PomodoroStatsRepository(this).setUserExited(true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            val stopIntent = Intent(this, PomodoroForegroundService::class.java).apply {
                action = PomodoroForegroundService.ACTION_STOP_AND_EXIT
            }
            startService(stopIntent)
            stopService(Intent(this, PomodoroForegroundService::class.java))
        } catch (_: Exception) {}
        Toast.makeText(this, "番茄钟已彻底退出", Toast.LENGTH_SHORT).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < 2000L) {
            exitAppCompletely()
        } else {
            lastBackPressTime = now
            Toast.makeText(this, "再按一次返回彻底退出程序（或点右上角「退出」）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        val filter = IntentFilter().apply {
            addAction(PomodoroForegroundService.BROADCAST_UI_STATE)
            addAction(PomodoroForegroundService.BROADCAST_EINK_FLASH)
            addAction(PomodoroForegroundService.BROADCAST_EXIT_APP)
        }
        registerReceiver(uiReceiver, filter)
        updateBatteryButtonStatus()
        sendServiceAction(PomodoroForegroundService.ACTION_REQUEST_UI_SYNC)
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
        try {
            unregisterReceiver(uiReceiver)
        } catch (_: Exception) {}
    }
}