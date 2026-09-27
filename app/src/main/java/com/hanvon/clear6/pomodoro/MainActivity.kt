package com.hanvon.clear6.pomodoro

import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var rootContainer: LinearLayout
    private lateinit var layoutTopHeader: LinearLayout
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
    private lateinit var layoutBottomControls: LinearLayout
    private lateinit var btnStartPause: Button
    private lateinit var btnReset: Button
    private lateinit var btnToggleSettings: Button
    private lateinit var layoutSettingsDrawer: LinearLayout

    // 折叠设置抽屉内的控件（时长、秒数刷新切换、音效模式切换、电池白名单）
    private lateinit var btnCycleDurationPreset: Button
    private lateinit var btnWorkMinus: Button
    private lateinit var btnWorkPlus: Button
    private lateinit var btnRestMinus: Button
    private lateinit var btnRestPlus: Button
    private lateinit var btnToggleSpeed: Button
    private lateinit var btnCycleSound: Button
    private lateinit var btnBatteryWhitelist: Button
    private lateinit var tvStatsSummary: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isSettingsExpanded: Boolean = false
    private var lastBackPressTime: Long = 0L
    private var currentWorkMinutes: Int = 15
    private var currentRestMinutes: Int = 5

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
                    val highSpeed = intent.getBooleanExtra(PomodoroForegroundService.EXTRA_HIGH_SPEED_MODE, false)
                    val soundName = intent.getStringExtra(PomodoroForegroundService.EXTRA_SOUND_PRESET_NAME) ?: "清脆嘀嗒"
                    val todayCount = intent.getIntExtra(PomodoroForegroundService.EXTRA_TODAY_POMODOROS, 0)
                    val totalCount = intent.getIntExtra(PomodoroForegroundService.EXTRA_TOTAL_POMODOROS, 0)
                    val eventMsg = intent.getStringExtra(PomodoroForegroundService.EXTRA_LAST_EVENT_MSG) ?: ""

                    renderEInkState(
                        phase, runState, remainingSec, workMin, restMin,
                        highSpeed, soundName, todayCount, totalCount, eventMsg
                    )
                }
                PomodoroForegroundService.BROADCAST_EINK_FLASH -> {
                    triggerScreenInvertFlash()
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
        layoutTopHeader = findViewById(R.id.layoutTopHeader)
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
        layoutBottomControls = findViewById(R.id.layoutBottomControls)
        btnStartPause = findViewById(R.id.btnStartPause)
        btnReset = findViewById(R.id.btnReset)
        btnToggleSettings = findViewById(R.id.btnToggleSettings)
        layoutSettingsDrawer = findViewById(R.id.layoutSettingsDrawer)

        btnCycleDurationPreset = findViewById(R.id.btnCycleDurationPreset)
        btnWorkMinus = findViewById(R.id.btnWorkMinus)
        btnWorkPlus = findViewById(R.id.btnWorkPlus)
        btnRestMinus = findViewById(R.id.btnRestMinus)
        btnRestPlus = findViewById(R.id.btnRestPlus)
        btnToggleSpeed = findViewById(R.id.btnToggleSpeed)
        btnCycleSound = findViewById(R.id.btnCycleSound)
        btnBatteryWhitelist = findViewById(R.id.btnBatteryWhitelist)
        tvStatsSummary = findViewById(R.id.tvStatsSummary)
    }

    private fun setupControls() {
        btnTest5sOverlay.setOnClickListener {
            sendServiceAction(PomodoroForegroundService.ACTION_START_5S_OVERLAY_TEST)
            Toast.makeText(
                this,
                "已开启5秒测试！5秒后透明小猫跳出（待机锁屏即刻清零）",
                Toast.LENGTH_SHORT
            ).show()
            moveTaskToBack(true)
        }

        btnMinimizeBg.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    requestIgnoreBatteryOptimizations()
                    return@setOnClickListener
                }
            }
            sendServiceAction(PomodoroForegroundService.ACTION_START_OR_RESUME)
            moveTaskToBack(true)
        }

        btnExitApp.setOnClickListener {
            exitAppCompletely()
        }

        btnStartPause.setOnClickListener {
            sendServiceAction(PomodoroForegroundService.ACTION_TOGGLE_PAUSE)
        }
        btnReset.setOnClickListener {
            sendServiceAction(PomodoroForegroundService.ACTION_RESET)
        }

        btnToggleSettings.setOnClickListener {
            isSettingsExpanded = !isSettingsExpanded
            layoutSettingsDrawer.visibility = if (isSettingsExpanded) View.VISIBLE else View.GONE
            btnToggleSettings.text = if (isSettingsExpanded) "收起设置 ▲" else "时长设置 ▼"
        }

        btnCycleDurationPreset.setOnClickListener {
            val currentIdx = durationPresets.indexOfFirst {
                it.first == currentWorkMinutes && it.second == currentRestMinutes
            }
            val nextPair = durationPresets[(currentIdx + 1) % durationPresets.size]
            applyCustomDurations(nextPair.first, nextPair.second)
        }

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

        btnToggleSpeed.setOnClickListener {
            sendServiceAction(PomodoroForegroundService.ACTION_TOGGLE_SPEED_MODE)
        }
        btnCycleSound.setOnClickListener {
            sendServiceAction(PomodoroForegroundService.ACTION_CYCLE_SOUND_PRESET)
        }

        btnBatteryWhitelist.setOnClickListener { requestIgnoreBatteryOptimizations() }
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
        highSpeed: Boolean,
        soundName: String,
        todayCount: Int,
        totalCount: Int,
        eventMsg: String
    ) {
        currentWorkMinutes = workMin
        currentRestMinutes = restMin

        if (runState == PomodoroForegroundService.RunState.RUNNING.name) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        tvSubtitleRule.text = "工作${workMin}分 / 休息${restMin}分 · 锁屏自动清零 · 开启重新计时"

        val formattedTime = if (highSpeed) {
            val m = remainingSec / 60
            val s = remainingSec % 60
            String.format("%02d:%02d", m, s)
        } else {
            val m = (remainingSec + 59) / 60
            String.format("%02d", m)
        }

        if (phase == PomodoroForegroundService.Phase.WORK.name) {
            // 工作阶段：正常显示倒计时与控制栏
            layoutTopHeader.visibility = View.VISIBLE
            tvPhaseBanner.visibility = View.VISIBLE
            tvPhaseBanner.text = "当前阶段：专注工作（${workMin} 分钟）"
            tvPhaseBanner.setBackgroundColor(Color.BLACK)
            tvPhaseBanner.setTextColor(Color.WHITE)

            layoutWorkTimerStage.visibility = View.VISIBLE
            layoutBottomControls.visibility = View.VISIBLE
            restingCatView.visibility = View.GONE
            restingCatView.setWagging(false)
        } else {
            // 休息阶段（小猫跳出来的时候）：去掉所有其他功能和按钮，全屏只展示小猫与休息倒计时！
            layoutTopHeader.visibility = View.GONE
            tvPhaseBanner.visibility = View.GONE
            layoutWorkTimerStage.visibility = View.GONE
            layoutBottomControls.visibility = View.GONE
            layoutSettingsDrawer.visibility = View.GONE
            isSettingsExpanded = false

            restingCatView.visibility = View.VISIBLE
            restingCatView.setWagging(runState == PomodoroForegroundService.RunState.RUNNING.name)
            restingCatView.updateRestCountdown(
                timeText = formattedTime,
                subtitle = if (highSpeed) "休息剩余" else "分钟休息剩余"
            )
        }

        tvTimerDisplay.text = formattedTime
        tvTimerUnitHint.text = if (highSpeed) "分 : 秒（每秒刷新）" else "分钟剩余（每分刷新）"
        tvStatusSubtitle.text = eventMsg

        btnStartPause.text = when (runState) {
            PomodoroForegroundService.RunState.RUNNING.name -> "暂停计时"
            PomodoroForegroundService.RunState.PAUSED.name -> "继续计时"
            else -> "开始 ${workMin} 分钟工作"
        }

        btnReset.text = "重置(${workMin}分)"
        btnCycleDurationPreset.text = "预设切换：工作${workMin}分 / 休息${restMin}分 (点此切换)"
        btnToggleSpeed.text = if (highSpeed) "刷新：分:秒(每秒)" else "刷新：仅分钟(省电)"
        btnCycleSound.text = "音效：${soundName}"
        tvStatsSummary.text = "今日完成番茄：${todayCount} 个   |   历史累计：${totalCount} 个"
    }

    private fun triggerScreenInvertFlash() {
        val flashSteps = longArrayOf(0L, 280L, 560L, 840L)
        flashSteps.forEachIndexed { index, delayMs ->
            uiHandler.postDelayed({
                val inverted = index % 2 == 0
                rootContainer.setBackgroundColor(if (inverted) Color.BLACK else Color.WHITE)
                tvTimerDisplay.setTextColor(if (inverted) Color.WHITE else Color.BLACK)
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
                } catch (_: Exception) {}
            } else {
                Toast.makeText(this, "已在电池白名单中", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBatteryButtonStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignored = pm.isIgnoringBatteryOptimizations(packageName)
            btnBatteryWhitelist.text = if (ignored) "✓ 电池白名单：已开启" else "点此开启「电池白名单」（防杀后台）"
        }
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
            Toast.makeText(this, "再按一次返回彻底退出程序", Toast.LENGTH_SHORT).show()
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