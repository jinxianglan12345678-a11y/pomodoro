package com.hanvon.clear6.pomodoro

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat

class PomodoroForegroundService : Service() {

    enum class Phase { WORK, REST }
    enum class RunState { STOPPED_ON_LOCK, RUNNING, PAUSED }

    companion object {
        const val CHANNEL_ID_TIMER = "clear6_pomodoro_ongoing_channel"
        const val CHANNEL_ID_ALERT = "clear6_pomodoro_alert_channel"
        const val NOTIFICATION_ID_TIMER = 1001
        const val NOTIFICATION_ID_ALERT = 1002

        // 控制指令 Action
        const val ACTION_SERVICE_INIT = "com.hanvon.clear6.pomodoro.ACTION_INIT"
        const val ACTION_UNLOCK_EVENT = "com.hanvon.clear6.pomodoro.ACTION_UNLOCK_EVENT"
        const val ACTION_START_OR_RESUME = "com.hanvon.clear6.pomodoro.ACTION_START_OR_RESUME"
        const val ACTION_PAUSE = "com.hanvon.clear6.pomodoro.ACTION_PAUSE"
        const val ACTION_TOGGLE_PAUSE = "com.hanvon.clear6.pomodoro.ACTION_TOGGLE_PAUSE"
        const val ACTION_SKIP_PHASE = "com.hanvon.clear6.pomodoro.ACTION_SKIP_PHASE"
        const val ACTION_RESET = "com.hanvon.clear6.pomodoro.ACTION_RESET"
        const val ACTION_SET_DURATIONS = "com.hanvon.clear6.pomodoro.ACTION_SET_DURATIONS"
        const val ACTION_TOGGLE_SPEED_MODE = "com.hanvon.clear6.pomodoro.ACTION_TOGGLE_SPEED_MODE"
        const val ACTION_CYCLE_SOUND_PRESET = "com.hanvon.clear6.pomodoro.ACTION_CYCLE_SOUND_PRESET"
        const val ACTION_REQUEST_UI_SYNC = "com.hanvon.clear6.pomodoro.ACTION_REQUEST_UI_SYNC"
        const val ACTION_STOP_AND_EXIT = "com.hanvon.clear6.pomodoro.ACTION_STOP_AND_EXIT"
        const val ACTION_START_5S_OVERLAY_TEST = "com.hanvon.clear6.pomodoro.ACTION_START_5S_OVERLAY_TEST"
        const val ACTION_ALARM_PHASE_EXPIRED = "com.hanvon.clear6.pomodoro.ACTION_ALARM_PHASE_EXPIRED"
        const val ACTION_KEEPALIVE_RESTART = "com.hanvon.clear6.pomodoro.ACTION_KEEPALIVE_RESTART"

        // UI 广播 Action
        const val BROADCAST_UI_STATE = "com.hanvon.clear6.pomodoro.BROADCAST_UI_STATE"
        const val BROADCAST_EINK_FLASH = "com.hanvon.clear6.pomodoro.BROADCAST_EINK_FLASH"
        const val BROADCAST_EXIT_APP = "com.hanvon.clear6.pomodoro.BROADCAST_EXIT_APP"
        const val BROADCAST_DISMISS_CAT_OVERLAY = "com.hanvon.clear6.pomodoro.BROADCAST_DISMISS_CAT_OVERLAY"

        const val EXTRA_PHASE = "extra_phase"
        const val EXTRA_RUN_STATE = "extra_run_state"
        const val EXTRA_REMAINING_SEC = "extra_remaining_sec"
        const val EXTRA_WORK_MINUTES = "extra_work_minutes"
        const val EXTRA_REST_MINUTES = "extra_rest_minutes"
        const val EXTRA_HIGH_SPEED_MODE = "extra_high_speed_mode"
        const val EXTRA_SOUND_PRESET_NAME = "extra_sound_preset_name"
        const val EXTRA_TODAY_POMODOROS = "extra_today_pomodoros"
        const val EXTRA_TOTAL_POMODOROS = "extra_total_pomodoros"
        const val EXTRA_LAST_EVENT_MSG = "extra_last_event_msg"
    }

    private var workDurationMinutes: Int = 15
    private var restDurationMinutes: Int = 5
    private var isHighSpeedMode: Boolean = false
    private var soundPreset: MinimalistSoundEngine.SoundPreset = MinimalistSoundEngine.SoundPreset.CRISP_TICK
    private var isMuteFlashOnly: Boolean = false

    private var currentPhase: Phase = Phase.WORK
    private var currentRunState: RunState = RunState.STOPPED_ON_LOCK
    private var remainingSeconds: Int = 15 * 60
    private var lastEventMessage: String = "锁屏自动清零 · 开启重新计时"
    private var lastDisplayedMinute: Int = -1
    private var is5sTestActive: Boolean = false
    private var lastRestartTimestampMs: Long = 0L

    private lateinit var statsRepo: PomodoroStatsRepository
    private lateinit var notificationManager: NotificationManager
    private lateinit var windowManager: WindowManager
    private lateinit var alarmManager: AlarmManager
    private var cpuPartialWakeLock: PowerManager.WakeLock? = null

    private var phaseEndEpochMs: Long = 0L

    // 跨应用全屏透明黑猫霸屏层（仅显示纯净黑猫与倒计时，无任何多余按钮）
    private var overlayRootLayout: LinearLayout? = null
    private var overlayCatView: RestingCatEInkView? = null
    private var hasLaunchedTranslucentCatActivity: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val workDurationSec: Int
        get() = workDurationMinutes * 60

    private val restDurationSec: Int
        get() = restDurationMinutes * 60

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (currentRunState != RunState.RUNNING) return

            // 只要检测到待机/熄屏，立即清零停止，绝不在待机后台继续倒计时！
            if (!isScreenInteractive()) {
                abortAndDiscardOnScreenOff("已进入待机锁屏：自动清零停止计时")
                return
            }

            val now = System.currentTimeMillis()
            if (phaseEndEpochMs > 0L) {
                remainingSeconds = ((phaseEndEpochMs - now + 999L) / 1000L).toInt().coerceAtLeast(0)
            } else if (remainingSeconds > 0) {
                remainingSeconds--
                phaseEndEpochMs = now + remainingSeconds * 1000L
            }

            if (remainingSeconds <= 0) {
                onPhaseCompletedNaturally()
            } else {
                maybeRefreshEInkOutputs(force = is5sTestActive)
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    /**
     * 唯一核心规则：
     * - 锁屏/待机 (ACTION_SCREEN_OFF)：立即清零并停止一切后台计时与闹钟。
     * - 开启/解锁 (ACTION_USER_PRESENT / ACTION_SCREEN_ON)：立即从头重新开始工作计时。
     */
    private val screenEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    abortAndDiscardOnScreenOff("已锁屏待机：当前计时已自动清零")
                }
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    if (km?.isKeyguardLocked != true) {
                        restartFreshWorkOnUnlock("屏幕已开启：重新从 ${workDurationMinutes} 分钟开始计时")
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    restartFreshWorkOnUnlock("屏幕已解锁开启：重新从 ${workDurationMinutes} 分钟开始计时")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        statsRepo = PomodoroStatsRepository(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        workDurationMinutes = statsRepo.getWorkMinutes()
        restDurationMinutes = statsRepo.getRestMinutes()
        isHighSpeedMode = statsRepo.isHighSpeedMode()
        soundPreset = statsRepo.getSoundPreset()
        isMuteFlashOnly = statsRepo.isMuteFlashOnly()
        remainingSeconds = workDurationSec

        createNotificationChannels()
        registerScreenReceiver()

        if (isScreenInteractive()) {
            restoreRunningStateIfKilled()
        } else {
            abortAndDiscardOnScreenOff("待机状态：计时已清零")
        }

        startForeground(NOTIFICATION_ID_TIMER, buildOngoingNotification())
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            pm.isInteractive
        } else {
            @Suppress("DEPRECATION")
            pm.isScreenOn
        }
    }

    private fun restoreRunningStateIfKilled() {
        if (statsRepo.isUserExited()) return
        if (!isScreenInteractive()) {
            abortAndDiscardOnScreenOff("待机状态：计时已清零")
            return
        }
        val savedState = statsRepo.getSavedRunState()
        val savedPhase = statsRepo.getSavedPhase()
        val savedEndMs = statsRepo.getPhaseEndEpochMs()
        if (savedState == RunState.RUNNING && savedEndMs > 0L) {
            currentPhase = savedPhase
            currentRunState = RunState.RUNNING
            phaseEndEpochMs = savedEndMs
            val now = System.currentTimeMillis()
            val remain = ((savedEndMs - now + 999L) / 1000L).toInt()
            if (remain <= 0) {
                remainingSeconds = 0
                mainHandler.post { onPhaseCompletedNaturally() }
            } else {
                remainingSeconds = remain
                lastEventMessage = "亮屏计时进行中（锁屏即自动清零）"
                acquireCpuPartialWakeLock()
                scheduleHardwarePhaseAlarm(phaseEndEpochMs)
                mainHandler.removeCallbacks(tickRunnable)
                mainHandler.postDelayed(tickRunnable, 1000L)
            }
        } else if (savedState == RunState.PAUSED) {
            currentPhase = savedPhase
            currentRunState = RunState.PAUSED
            remainingSeconds = statsRepo.getPausedRemainingSec(workDurationSec)
        }
    }

    private fun persistActivePhaseState() {
        statsRepo.saveRuntimeState(
            phase = currentPhase,
            runState = currentRunState,
            endEpochMs = phaseEndEpochMs,
            pausedRemainingSec = remainingSeconds
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (isScreenInteractive()) {
                restoreRunningStateIfKilled()
                maybeRefreshEInkOutputs(force = true)
            } else {
                abortAndDiscardOnScreenOff("待机锁屏：计时已清零")
            }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_STOP_AND_EXIT -> {
                statsRepo.setUserExited(true)
                is5sTestActive = false
                cancelHardwarePhaseAlarm()
                MinimalistSoundEngine.stopSilentKeepAliveAudio()
                mainHandler.removeCallbacksAndMessages(null)
                dismissRestCatOverlay()
                releaseAllWakeLocks()
                phaseEndEpochMs = 0L
                currentRunState = RunState.STOPPED_ON_LOCK
                persistActivePhaseState()
                sendBroadcast(Intent(BROADCAST_DISMISS_CAT_OVERLAY).apply { setPackage(packageName) })
                sendBroadcast(Intent(BROADCAST_EXIT_APP).apply { setPackage(packageName) })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                notificationManager.cancelAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ALARM_PHASE_EXPIRED,
            ACTION_KEEPALIVE_RESTART -> {
                if (!statsRepo.isUserExited()) {
                    // 若此时处于待机锁屏状态，直接清零停止，绝不在待机时响闹钟或跳出小猫！
                    if (!isScreenInteractive()) {
                        abortAndDiscardOnScreenOff("待机锁屏中：已自动清零停止")
                        return START_STICKY
                    }
                    if (currentRunState != RunState.RUNNING) {
                        restoreRunningStateIfKilled()
                    }
                    val now = System.currentTimeMillis()
                    if (intent.action == ACTION_ALARM_PHASE_EXPIRED && currentPhase == Phase.WORK) {
                        onPhaseCompletedNaturally()
                    } else if (currentRunState == RunState.RUNNING && phaseEndEpochMs in 1..(now + 1500L)) {
                        onPhaseCompletedNaturally()
                    } else {
                        maybeRefreshEInkOutputs(force = true)
                    }
                }
            }
            ACTION_START_5S_OVERLAY_TEST -> {
                statsRepo.setUserExited(false)
                mainHandler.removeCallbacks(tickRunnable)
                dismissRestCatOverlay()
                is5sTestActive = true
                currentPhase = Phase.WORK
                remainingSeconds = 5
                phaseEndEpochMs = System.currentTimeMillis() + 5000L
                currentRunState = RunState.RUNNING
                lastDisplayedMinute = -1
                lastEventMessage = "5秒测试：5秒后小猫跳出（锁屏即刻清零）"
                persistActivePhaseState()
                scheduleHardwarePhaseAlarm(phaseEndEpochMs)
                acquireCpuPartialWakeLock()
                maybeRefreshEInkOutputs(force = true)
                mainHandler.postDelayed(tickRunnable, 1000L)
            }
            ACTION_SERVICE_INIT -> {
                statsRepo.setUserExited(false)
                if (currentRunState == RunState.STOPPED_ON_LOCK && isScreenInteractive()) {
                    restartFreshWorkOnUnlock("已开启：从 ${workDurationMinutes} 分钟开始计时")
                } else {
                    maybeRefreshEInkOutputs(force = true)
                }
            }
            ACTION_UNLOCK_EVENT -> {
                if (isScreenInteractive()) {
                    restartFreshWorkOnUnlock("屏幕已开启：重新从 ${workDurationMinutes} 分钟开始计时")
                }
            }
            ACTION_START_OR_RESUME -> startOrResumeTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_TOGGLE_PAUSE -> {
                if (currentRunState == RunState.RUNNING) pauseTimer() else startOrResumeTimer()
            }
            ACTION_SKIP_PHASE -> skipCurrentPhaseManually()
            ACTION_RESET -> resetToFreshWork("已重置：重新从 ${workDurationMinutes} 分钟开始计时")
            ACTION_SET_DURATIONS -> {
                val newWork = intent.getIntExtra(EXTRA_WORK_MINUTES, workDurationMinutes).coerceIn(1, 180)
                val newRest = intent.getIntExtra(EXTRA_REST_MINUTES, restDurationMinutes).coerceIn(1, 90)
                workDurationMinutes = newWork
                restDurationMinutes = newRest
                statsRepo.setWorkMinutes(newWork)
                statsRepo.setRestMinutes(newRest)
                resetToFreshWork("工作 ${newWork} 分钟 / 休息 ${newRest} 分钟 · 已重新开始")
            }
            ACTION_TOGGLE_SPEED_MODE -> {
                isHighSpeedMode = !isHighSpeedMode
                statsRepo.setHighSpeedMode(isHighSpeedMode)
                lastEventMessage = if (isHighSpeedMode) {
                    "已切换为：分:秒 每秒刷新模式"
                } else {
                    "已切换为：仅显示分钟（每分钟刷新一次，省电）"
                }
                maybeRefreshEInkOutputs(force = true)
            }
            ACTION_CYCLE_SOUND_PRESET -> {
                val presets = MinimalistSoundEngine.SoundPreset.values()
                if (isMuteFlashOnly) {
                    isMuteFlashOnly = false
                    soundPreset = presets[0]
                } else {
                    val idx = soundPreset.ordinal
                    if (idx + 1 < presets.size) {
                        soundPreset = presets[idx + 1]
                    } else {
                        isMuteFlashOnly = true
                    }
                }
                statsRepo.setSoundPreset(soundPreset)
                statsRepo.setMuteFlashOnly(isMuteFlashOnly)
                val label = if (isMuteFlashOnly) "静音反色闪烁" else soundPreset.displayName
                lastEventMessage = "阶段提醒音效已切换为：${label}"
                if (!isMuteFlashOnly) {
                    MinimalistSoundEngine.playPreset(soundPreset, isWorkCompleted = true)
                } else {
                    sendBroadcast(Intent(BROADCAST_EINK_FLASH).apply { setPackage(packageName) })
                }
                maybeRefreshEInkOutputs(force = true)
            }
            ACTION_REQUEST_UI_SYNC -> {
                maybeRefreshEInkOutputs(force = true)
            }
        }
        return START_STICKY
    }

    private fun restartFreshWorkOnUnlock(reason: String) {
        val now = System.currentTimeMillis()
        // 避免 SCREEN_ON 与 USER_PRESENT 短时间内重复触发两次重置
        if (currentRunState == RunState.RUNNING && now - lastRestartTimestampMs < 1500L) {
            return
        }
        lastRestartTimestampMs = now
        is5sTestActive = false
        mainHandler.removeCallbacks(tickRunnable)
        dismissRestCatOverlay()
        sendBroadcast(Intent(BROADCAST_DISMISS_CAT_OVERLAY).apply { setPackage(packageName) })

        currentPhase = Phase.WORK
        remainingSeconds = workDurationSec
        phaseEndEpochMs = now + remainingSeconds * 1000L
        currentRunState = RunState.RUNNING
        lastEventMessage = reason
        lastDisplayedMinute = -1

        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun abortAndDiscardOnScreenOff(reason: String) {
        is5sTestActive = false
        mainHandler.removeCallbacks(tickRunnable)
        cancelHardwarePhaseAlarm()
        dismissRestCatOverlay()
        sendBroadcast(Intent(BROADCAST_DISMISS_CAT_OVERLAY).apply { setPackage(packageName) })
        releaseAllWakeLocks()

        currentPhase = Phase.WORK
        remainingSeconds = workDurationSec
        phaseEndEpochMs = 0L
        currentRunState = RunState.STOPPED_ON_LOCK
        lastEventMessage = reason
        lastDisplayedMinute = -1

        persistActivePhaseState()
        maybeRefreshEInkOutputs(force = true)
    }

    private fun startOrResumeTimer() {
        if (!isScreenInteractive()) {
            abortAndDiscardOnScreenOff("待机状态：计时已清零")
            return
        }
        if (currentRunState == RunState.RUNNING) return
        if (currentRunState == RunState.STOPPED_ON_LOCK) {
            currentPhase = Phase.WORK
            remainingSeconds = workDurationSec
        }
        phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
        currentRunState = RunState.RUNNING
        lastEventMessage = "计时进行中（锁屏即自动清零，开启重新计时）"
        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun pauseTimer() {
        if (currentRunState != RunState.RUNNING) return
        is5sTestActive = false
        mainHandler.removeCallbacks(tickRunnable)
        cancelHardwarePhaseAlarm()
        val now = System.currentTimeMillis()
        if (phaseEndEpochMs > now) {
            remainingSeconds = ((phaseEndEpochMs - now + 999L) / 1000L).toInt().coerceAtLeast(1)
        }
        phaseEndEpochMs = 0L
        currentRunState = RunState.PAUSED
        lastEventMessage = "已暂停计时"
        persistActivePhaseState()
        releaseAllWakeLocks()
        maybeRefreshEInkOutputs(force = true)
    }

    private fun resetToFreshWork(reason: String) {
        is5sTestActive = false
        mainHandler.removeCallbacks(tickRunnable)
        dismissRestCatOverlay()
        sendBroadcast(Intent(BROADCAST_DISMISS_CAT_OVERLAY).apply { setPackage(packageName) })
        currentPhase = Phase.WORK
        remainingSeconds = workDurationSec
        phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
        currentRunState = RunState.RUNNING
        lastEventMessage = reason
        lastDisplayedMinute = -1
        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun skipCurrentPhaseManually() {
        is5sTestActive = false
        mainHandler.removeCallbacks(tickRunnable)
        if (currentPhase == Phase.WORK) {
            currentPhase = Phase.REST
            remainingSeconds = restDurationSec
            lastEventMessage = "休息中（${restDurationMinutes} 分钟）"
        } else {
            dismissRestCatOverlay()
            sendBroadcast(Intent(BROADCAST_DISMISS_CAT_OVERLAY).apply { setPackage(packageName) })
            currentPhase = Phase.WORK
            remainingSeconds = workDurationSec
            lastEventMessage = "重新开始 ${workDurationMinutes} 分钟工作"
        }
        phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
        currentRunState = RunState.RUNNING
        lastDisplayedMinute = -1
        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun onPhaseCompletedNaturally() {
        mainHandler.removeCallbacks(tickRunnable)
        if (!isScreenInteractive()) {
            abortAndDiscardOnScreenOff("待机锁屏中：已自动清零停止")
            return
        }
        is5sTestActive = false
        if (currentPhase == Phase.WORK) {
            statsRepo.recordCompletedPomodoro()
            currentPhase = Phase.REST
            remainingSeconds = restDurationSec
            phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
            lastEventMessage = "休息 ${restDurationMinutes} 分钟"
            triggerPhaseTransitionAlert(isWorkCompleted = true)
        } else {
            dismissRestCatOverlay()
            sendBroadcast(Intent(BROADCAST_DISMISS_CAT_OVERLAY).apply { setPackage(packageName) })
            currentPhase = Phase.WORK
            remainingSeconds = workDurationSec
            phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
            lastEventMessage = "休息结束 · 开始 ${workDurationMinutes} 分钟工作"
            triggerPhaseTransitionAlert(isWorkCompleted = false)
        }
        currentRunState = RunState.RUNNING
        lastDisplayedMinute = -1
        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun maybeRefreshEInkOutputs(force: Boolean) {
        val displayMinute = (remainingSeconds + 59) / 60
        if (!force && !isHighSpeedMode && !is5sTestActive && displayMinute == lastDisplayedMinute) {
            return
        }
        lastDisplayedMinute = displayMinute

        notificationManager.notify(NOTIFICATION_ID_TIMER, buildOngoingNotification())
        syncRestCatOverlayState()

        val soundLabel = if (isMuteFlashOnly) "静音反色闪烁" else soundPreset.displayName
        val uiIntent = Intent(BROADCAST_UI_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_PHASE, currentPhase.name)
            putExtra(EXTRA_RUN_STATE, currentRunState.name)
            putExtra(EXTRA_REMAINING_SEC, remainingSeconds)
            putExtra(EXTRA_WORK_MINUTES, workDurationMinutes)
            putExtra(EXTRA_REST_MINUTES, restDurationMinutes)
            putExtra(EXTRA_HIGH_SPEED_MODE, isHighSpeedMode || is5sTestActive)
            putExtra(EXTRA_SOUND_PRESET_NAME, soundLabel)
            putExtra(EXTRA_TODAY_POMODOROS, statsRepo.getTodayCount())
            putExtra(EXTRA_TOTAL_POMODOROS, statsRepo.getTotalCount())
            putExtra(EXTRA_LAST_EVENT_MSG, lastEventMessage)
        }
        sendBroadcast(uiIntent)
    }

    private fun triggerPhaseTransitionAlert(isWorkCompleted: Boolean) {
        sendBroadcast(Intent(BROADCAST_EINK_FLASH).apply { setPackage(packageName) })
        if (!isMuteFlashOnly) {
            MinimalistSoundEngine.playPreset(soundPreset, isWorkCompleted)
        }

        if (isWorkCompleted) {
            val contentIntent = PendingIntent.getActivity(
                this, 3001,
                Intent(this, RestCatOverlayActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alertBuilder = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setSmallIcon(R.drawable.ic_pomodoro_eink)
                .setContentTitle("休息 ${restDurationMinutes} 分钟")
                .setContentText("小猫跳出休息中")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setAutoCancel(true)
                .setFullScreenIntent(contentIntent, true)

            notificationManager.notify(NOTIFICATION_ID_ALERT, alertBuilder.build())
        } else {
            notificationManager.cancel(NOTIFICATION_ID_ALERT)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePausePending = PendingIntent.getService(
            this, 11,
            Intent(this, PomodoroForegroundService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resetPending = PendingIntent.getService(
            this, 12,
            Intent(this, PomodoroForegroundService::class.java).apply { action = ACTION_RESET },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exitPending = PendingIntent.getService(
            this, 14,
            Intent(this, PomodoroForegroundService::class.java).apply { action = ACTION_STOP_AND_EXIT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val m = (remainingSeconds + 59) / 60
        val phaseLabel = if (currentPhase == Phase.WORK) "工作中" else "休息中"
        val titleText = when (currentRunState) {
            RunState.STOPPED_ON_LOCK -> "已锁屏清零 · 开启屏幕自动重计"
            RunState.PAUSED -> "${phaseLabel}(已暂停) · 剩余 ${m} 分钟"
            RunState.RUNNING -> "${phaseLabel} · 剩余 ${m} 分钟"
        }
        val toggleActionLabel = if (currentRunState == RunState.RUNNING) "暂停" else "开始"

        return NotificationCompat.Builder(this, CHANNEL_ID_TIMER)
            .setSmallIcon(R.drawable.ic_pomodoro_eink)
            .setContentTitle(titleText)
            .setContentText("下拉可直接【暂停/开始】或【重置】 · 锁屏自动清零")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(0, toggleActionLabel, togglePausePending)
            .addAction(0, "重置", resetPending)
            .addAction(0, "退出", exitPending)
            .build()
    }

    private fun acquireCpuPartialWakeLock() {
        if (!isScreenInteractive()) return
        MinimalistSoundEngine.startSilentKeepAliveAudio()
        try {
            if (cpuPartialWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                cpuPartialWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Clear6Pomodoro::CpuWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (cpuPartialWakeLock?.isHeld == false) {
                cpuPartialWakeLock?.acquire(180 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseAllWakeLocks() {
        MinimalistSoundEngine.stopSilentKeepAliveAudio()
        try {
            if (cpuPartialWakeLock?.isHeld == true) {
                cpuPartialWakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenEventReceiver, filter)
    }

    private fun scheduleHardwarePhaseAlarm(triggerAtMillis: Long) {
        if (triggerAtMillis <= 0L || !isScreenInteractive()) return
        try {
            cancelHardwarePhaseAlarm()
            val catActivityIntent = Intent(this, RestCatOverlayActivity::class.java).apply {
                action = ACTION_ALARM_PHASE_EXPIRED
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            val pendingCatActivity = PendingIntent.getActivity(
                this,
                2005,
                catActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmBroadcastIntent = Intent(this, BootAndUnlockReceiver::class.java).apply {
                action = ACTION_ALARM_PHASE_EXPIRED
            }
            val pendingBroadcast = PendingIntent.getBroadcast(
                this,
                2001,
                alarmBroadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val operationIntent = if (currentPhase == Phase.WORK) {
                pendingCatActivity
            } else {
                pendingBroadcast
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, pendingCatActivity),
                    operationIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operationIntent)
            }
        } catch (_: Exception) {}
    }

    private fun cancelHardwarePhaseAlarm() {
        try {
            val catActivityIntent = Intent(this, RestCatOverlayActivity::class.java).apply {
                action = ACTION_ALARM_PHASE_EXPIRED
            }
            val pendingCatActivity = PendingIntent.getActivity(
                this,
                2005,
                catActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingCatActivity)

            val alarmIntent = Intent(this, BootAndUnlockReceiver::class.java).apply {
                action = ACTION_ALARM_PHASE_EXPIRED
            }
            val pendingAlarm = PendingIntent.getBroadcast(
                this,
                2001,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingAlarm)
        } catch (_: Exception) {}
    }

    /**
     * 小猫跳出来时：去掉所有多余按钮和其他功能，全屏仅显示纯粹的透明黑猫与休息倒计时！
     */
    private fun syncRestCatOverlayState() {
        if (currentPhase != Phase.REST || currentRunState != RunState.RUNNING || !isScreenInteractive()) {
            if (hasLaunchedTranslucentCatActivity) {
                hasLaunchedTranslucentCatActivity = false
                sendBroadcast(Intent(BROADCAST_DISMISS_CAT_OVERLAY).apply { setPackage(packageName) })
            }
            dismissRestCatOverlay()
            return
        }

        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!canOverlay) {
            if (!hasLaunchedTranslucentCatActivity) {
                hasLaunchedTranslucentCatActivity = true
                try {
                    val translucentCatIntent = Intent(this, RestCatOverlayActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                    }
                    startActivity(translucentCatIntent)
                } catch (_: Exception) {}
            }
            return
        }

        val m = (remainingSeconds + 59) / 60
        val formattedTime = String.format("%02d", m)
        val subtitleText = "分钟休息剩余"

        if (overlayRootLayout == null) {
            try {
                val root = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.TRANSPARENT)
                    isClickable = true
                    isFocusable = true
                }

                val catView = RestingCatEInkView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    updateRestCountdown(formattedTime, subtitleText)
                    setWagging(true)
                }
                overlayCatView = catView
                root.addView(catView)

                val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                windowManager.addView(root, params)
                overlayRootLayout = root
            } catch (_: Exception) {}
        } else {
            overlayCatView?.updateRestCountdown(formattedTime, subtitleText)
            overlayCatView?.setWagging(true)
        }
    }

    private fun dismissRestCatOverlay() {
        val view = overlayRootLayout ?: return
        overlayCatView?.setWagging(false)
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {}
        overlayRootLayout = null
        overlayCatView = null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ongoingChannel = NotificationChannel(
                CHANNEL_ID_TIMER,
                "番茄钟后台计时",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "番茄钟休息提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            notificationManager.createNotificationChannel(ongoingChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!statsRepo.isUserExited() && currentRunState == RunState.RUNNING && isScreenInteractive()) {
            try {
                val restartIntent = Intent(this, BootAndUnlockReceiver::class.java).apply {
                    action = ACTION_KEEPALIVE_RESTART
                }
                val pending = PendingIntent.getBroadcast(
                    this,
                    2009,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 500L,
                    pending
                )
            } catch (_: Exception) {}
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(tickRunnable)
        dismissRestCatOverlay()
        releaseAllWakeLocks()
        try {
            unregisterReceiver(screenEventReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}