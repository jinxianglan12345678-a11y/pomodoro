package com.hanvon.clear6.pomodoro

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class PomodoroForegroundService : Service() {

    enum class Phase { WORK, REST }
    enum class RunState { STOPPED_ON_LOCK, RUNNING, PAUSED }
    enum class LockRuleMode(val displayName: String) {
        ABORT_ON_LOCK("锁屏立即清零 · 解锁自动重开"),
        CONTINUE_ON_LOCK("锁屏后台继续计时 · 不清零")
    }

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
        const val ACTION_SET_LOCK_RULE = "com.hanvon.clear6.pomodoro.ACTION_SET_LOCK_RULE"
        const val ACTION_SET_HIGH_SPEED = "com.hanvon.clear6.pomodoro.ACTION_SET_HIGH_SPEED"
        const val ACTION_SET_SOUND_PRESET = "com.hanvon.clear6.pomodoro.ACTION_SET_SOUND_PRESET"
        const val ACTION_SET_ALERT_MODE = "com.hanvon.clear6.pomodoro.ACTION_SET_ALERT_MODE"
        const val ACTION_SET_MINUTE_TICK = "com.hanvon.clear6.pomodoro.ACTION_SET_MINUTE_TICK"
        const val ACTION_SET_LOCKSCREEN_NOTIF = "com.hanvon.clear6.pomodoro.ACTION_SET_LOCKSCREEN_NOTIF"
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
        const val EXTRA_LOCK_RULE = "extra_lock_rule"
        const val EXTRA_HIGH_SPEED = "extra_high_speed"
        const val EXTRA_SOUND_PRESET = "extra_sound_preset"
        const val EXTRA_ALERT_MODE = "extra_alert_mode"
        const val EXTRA_MINUTE_TICK = "extra_minute_tick"
        const val EXTRA_LOCKSCREEN_NOTIF = "extra_lockscreen_notif"
        const val EXTRA_TODAY_POMODOROS = "extra_today_pomodoros"
        const val EXTRA_TOTAL_POMODOROS = "extra_total_pomodoros"
        const val EXTRA_LAST_EVENT_MSG = "extra_last_event_msg"
    }

    private var workDurationMinutes: Int = 15
    private var restDurationMinutes: Int = 5
    private var lockRuleMode: LockRuleMode = LockRuleMode.ABORT_ON_LOCK

    private var currentPhase: Phase = Phase.WORK
    private var currentRunState: RunState = RunState.STOPPED_ON_LOCK
    private var remainingSeconds: Int = 15 * 60
    private var highSpeedRefreshMode: Boolean = false
    private var soundPreset: MinimalistSoundEngine.SoundPreset = MinimalistSoundEngine.SoundPreset.CRISP_TICK
    private var alertMode: MinimalistSoundEngine.AlertMode = MinimalistSoundEngine.AlertMode.SOUND_PRIMARY
    private var minuteTickEnabled: Boolean = false
    private var lockscreenNotifEnabled: Boolean = true
    private var lastEventMessage: String = "等待开始或解锁触发"
    private var lastDisplayedMinute: Int = -1

    private lateinit var statsRepo: PomodoroStatsRepository
    private lateinit var notificationManager: NotificationManager
    private lateinit var windowManager: WindowManager
    private lateinit var alarmManager: AlarmManager
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var cpuPartialWakeLock: PowerManager.WakeLock? = null

    // 绝对到期时间戳（毫秒）：防止系统冻结 CPU 导致读秒变慢，且进程被杀重启后可精准恢复剩余秒数
    private var phaseEndEpochMs: Long = 0L

    // 第一重防杀装甲：工作看书阶段常驻右上角「微胶囊/1px隐形保活浮窗」(TYPE_APPLICATION_OVERLAY)
    // 让安卓 WindowManager 将本进程标记为 VISIBLE_APP（屏幕可见窗口进程），彻底阻止汉王切换微信读书时杀后台！
    private var keepAliveBadgeView: TextView? = null
    private var isKeepAliveBadgeStealth: Boolean = false

    // 跨应用全屏透明黑猫霸屏悬浮窗（在微信读书/掌阅看书时，休息阶段直接跳出趴在书页上）
    private var overlayRootLayout: LinearLayout? = null
    private var overlayCatView: RestingCatEInkView? = null
    private var overlayBgModeBtn: Button? = null
    private var isOverlayTransparentBg: Boolean = true
    private var hasLaunchedTranslucentCatActivity: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val workDurationSec: Int
        get() = workDurationMinutes * 60

    private val restDurationSec: Int
        get() = restDurationMinutes * 60

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (currentRunState != RunState.RUNNING) return

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
                maybeRefreshEInkOutputs(force = false)
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    /**
     * 动态监听屏幕熄屏与解锁广播，并根据用户设置的「锁屏规则开关」执行：
     * - 模式 1 (ABORT_ON_LOCK)：熄屏立即停止并清零作废；再次解锁重新从工作阶段开始计时。
     * - 模式 2 (CONTINUE_ON_LOCK)：熄屏保持 CPU 唤醒锁在后台继续计时，解锁后无缝延续当前进度。
     */
    private val screenEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (lockRuleMode == LockRuleMode.ABORT_ON_LOCK) {
                        abortAndDiscardOnScreenOff("检测到熄屏/合盖：当前进度已清零作废")
                    } else {
                        // 锁屏继续计时模式：释放屏幕常亮锁但保留 CPU 唤醒锁保证后台准时读秒
                        releaseScreenBrightWakeLock()
                        if (currentRunState == RunState.RUNNING) {
                            acquireCpuPartialWakeLock()
                            lastEventMessage = "熄屏后台继续计时中（锁屏不作废模式）"
                            maybeRefreshEInkOutputs(force = true)
                        }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (lockRuleMode == LockRuleMode.ABORT_ON_LOCK) {
                        restartFreshWorkOnUnlock("检测到屏幕解锁：已自动从 ${workDurationMinutes} 分钟工作开始")
                    } else {
                        if (currentRunState == RunState.RUNNING) {
                            acquireScreenBrightWakeLock()
                            lastEventMessage = "已解锁：继续当前计时进度"
                            maybeRefreshEInkOutputs(force = true)
                        } else if (currentRunState == RunState.STOPPED_ON_LOCK) {
                            restartFreshWorkOnUnlock("已解锁：开始 ${workDurationMinutes} 分钟工作")
                        }
                    }
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
        lockRuleMode = statsRepo.getLockRuleMode()
        highSpeedRefreshMode = statsRepo.isHighSpeedRefreshEnabled()
        soundPreset = statsRepo.getSoundPreset()
        alertMode = statsRepo.getAlertMode()
        minuteTickEnabled = statsRepo.isMinuteTickEnabled()
        lockscreenNotifEnabled = statsRepo.isLockscreenNotificationEnabled()
        remainingSeconds = workDurationSec

        createNotificationChannels()
        registerScreenReceiver()

        // 若服务是被汉王系统杀后台后由 START_STICKY 或 AlarmManager 自动拉起，立即从本地时间戳恢复状态
        restoreRunningStateIfKilled()

        startForeground(NOTIFICATION_ID_TIMER, buildOngoingNotification())
    }

    private fun restoreRunningStateIfKilled() {
        if (statsRepo.isUserExited()) return
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
                lastEventMessage = "后台保活守护中（已同步精确倒计时）"
                acquireScreenBrightWakeLock()
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
            // 系统回收内存后 START_STICKY 自动重启服务
            restoreRunningStateIfKilled()
            maybeRefreshEInkOutputs(force = true)
            return START_STICKY
        }
        when (intent.action) {
            ACTION_STOP_AND_EXIT -> {
                statsRepo.setUserExited(true)
                cancelHardwarePhaseAlarm()
                MinimalistSoundEngine.stopSilentKeepAliveAudio()
                mainHandler.removeCallbacksAndMessages(null)
                dismissKeepAliveBadge()
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
                    if (currentRunState != RunState.RUNNING) {
                        restoreRunningStateIfKilled()
                    }
                    val now = System.currentTimeMillis()
                    if (intent.action == ACTION_ALARM_PHASE_EXPIRED && currentPhase == Phase.WORK) {
                        // 硬件闹钟或透明黑猫窗口唤醒：无论后台之前是否被汉王冻结/强杀，立即切换到 REST 休息阶段！
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
                lockRuleMode = LockRuleMode.CONTINUE_ON_LOCK
                statsRepo.setLockRuleMode(lockRuleMode)
                currentPhase = Phase.WORK
                remainingSeconds = 5
                phaseEndEpochMs = System.currentTimeMillis() + 5000L
                currentRunState = RunState.RUNNING
                lastDisplayedMinute = -1
                lastEventMessage = "5秒跨应用霸屏测试进行中：5秒后小猫将直接跳到微信读书上方！"
                persistActivePhaseState()
                scheduleHardwarePhaseAlarm(phaseEndEpochMs)
                acquireScreenBrightWakeLock()
                acquireCpuPartialWakeLock()
                maybeRefreshEInkOutputs(force = true)
                mainHandler.postDelayed(tickRunnable, 1000L)
            }
            ACTION_SERVICE_INIT -> {
                statsRepo.setUserExited(false)
                if (currentRunState == RunState.STOPPED_ON_LOCK) {
                    restartFreshWorkOnUnlock("应用已就绪：开始 ${workDurationMinutes} 分钟工作计时")
                } else {
                    maybeRefreshEInkOutputs(force = true)
                }
            }
            ACTION_UNLOCK_EVENT -> {
                if (lockRuleMode == LockRuleMode.ABORT_ON_LOCK || currentRunState == RunState.STOPPED_ON_LOCK) {
                    restartFreshWorkOnUnlock("解锁广播触发：从 ${workDurationMinutes} 分钟工作开始")
                } else {
                    maybeRefreshEInkOutputs(force = true)
                }
            }
            ACTION_START_OR_RESUME -> startOrResumeTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_TOGGLE_PAUSE -> {
                if (currentRunState == RunState.RUNNING) pauseTimer() else startOrResumeTimer()
            }
            ACTION_SKIP_PHASE -> skipCurrentPhaseManually()
            ACTION_RESET -> resetToFreshWork("手动重置：重新从 ${workDurationMinutes} 分钟工作开始")
            ACTION_SET_DURATIONS -> {
                val newWork = intent.getIntExtra(EXTRA_WORK_MINUTES, workDurationMinutes).coerceIn(1, 180)
                val newRest = intent.getIntExtra(EXTRA_REST_MINUTES, restDurationMinutes).coerceIn(1, 90)
                workDurationMinutes = newWork
                restDurationMinutes = newRest
                statsRepo.setWorkMinutes(newWork)
                statsRepo.setRestMinutes(newRest)
                resetToFreshWork("时长已设为：工作 ${newWork} 分钟 / 休息 ${newRest} 分钟")
            }
            ACTION_SET_LOCK_RULE -> {
                val ruleName = intent.getStringExtra(EXTRA_LOCK_RULE) ?: LockRuleMode.ABORT_ON_LOCK.name
                lockRuleMode = runCatching { LockRuleMode.valueOf(ruleName) }
                    .getOrDefault(LockRuleMode.ABORT_ON_LOCK)
                statsRepo.setLockRuleMode(lockRuleMode)
                lastEventMessage = "锁屏规则已切换：${lockRuleMode.displayName}"
                maybeRefreshEInkOutputs(force = true)
            }
            ACTION_SET_HIGH_SPEED -> {
                val enabled = intent.getBooleanExtra(EXTRA_HIGH_SPEED, false)
                highSpeedRefreshMode = enabled
                statsRepo.setHighSpeedRefreshEnabled(enabled)
                lastEventMessage = if (enabled) "已开启高速刷新模式（每秒刷新显示秒）" else "已开启墨水屏省电模式（每分钟刷新一次）"
                maybeRefreshEInkOutputs(force = true)
            }
            ACTION_SET_SOUND_PRESET -> {
                val presetName = intent.getStringExtra(EXTRA_SOUND_PRESET) ?: MinimalistSoundEngine.SoundPreset.CRISP_TICK.name
                soundPreset = runCatching { MinimalistSoundEngine.SoundPreset.valueOf(presetName) }
                    .getOrDefault(MinimalistSoundEngine.SoundPreset.CRISP_TICK)
                statsRepo.setSoundPreset(soundPreset)
                MinimalistSoundEngine.playPreset(soundPreset, isWorkCompleted = true)
                lastEventMessage = "提醒音效已切换为：${soundPreset.displayName}"
                maybeRefreshEInkOutputs(force = true)
            }
            ACTION_SET_ALERT_MODE -> {
                val modeName = intent.getStringExtra(EXTRA_ALERT_MODE) ?: MinimalistSoundEngine.AlertMode.SOUND_PRIMARY.name
                alertMode = runCatching { MinimalistSoundEngine.AlertMode.valueOf(modeName) }
                    .getOrDefault(MinimalistSoundEngine.AlertMode.SOUND_PRIMARY)
                statsRepo.setAlertMode(alertMode)
                lastEventMessage = "提醒策略已切换为：${alertMode.displayName}"
                maybeRefreshEInkOutputs(force = true)
            }
            ACTION_SET_MINUTE_TICK -> {
                val enabled = intent.getBooleanExtra(EXTRA_MINUTE_TICK, false)
                minuteTickEnabled = enabled
                statsRepo.setMinuteTickEnabled(enabled)
                if (enabled) MinimalistSoundEngine.playSubtleMinuteTick()
                lastEventMessage = if (enabled) "已开启整分微弱嘀嗒提示音" else "已关闭整分微弱嘀嗒提示音"
                maybeRefreshEInkOutputs(force = true)
            }
            ACTION_SET_LOCKSCREEN_NOTIF -> {
                val enabled = intent.getBooleanExtra(EXTRA_LOCKSCREEN_NOTIF, true)
                lockscreenNotifEnabled = enabled
                statsRepo.setLockscreenNotificationEnabled(enabled)
                lastEventMessage = if (enabled) {
                    "已开启锁屏通知提示：锁屏界面将实时显示剩余时长与专注状态"
                } else {
                    "已关闭锁屏通知提示：锁屏界面将隐藏番茄钟通知"
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
        mainHandler.removeCallbacks(tickRunnable)
        dismissRestCatOverlay()
        currentPhase = Phase.WORK
        remainingSeconds = workDurationSec
        phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
        currentRunState = RunState.RUNNING
        lastEventMessage = reason
        lastDisplayedMinute = -1

        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireScreenBrightWakeLock()
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun abortAndDiscardOnScreenOff(reason: String) {
        mainHandler.removeCallbacks(tickRunnable)
        cancelHardwarePhaseAlarm()
        dismissKeepAliveBadge()
        dismissRestCatOverlay()
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
        if (currentRunState == RunState.RUNNING) return
        if (currentRunState == RunState.STOPPED_ON_LOCK) {
            currentPhase = Phase.WORK
            remainingSeconds = workDurationSec
        }
        phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
        currentRunState = RunState.RUNNING
        lastEventMessage = if (currentPhase == Phase.WORK) "工作中（四重防杀保活运行中）" else "休息中（保持屏幕常亮）"
        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireScreenBrightWakeLock()
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun pauseTimer() {
        if (currentRunState != RunState.RUNNING) return
        mainHandler.removeCallbacks(tickRunnable)
        cancelHardwarePhaseAlarm()
        val now = System.currentTimeMillis()
        if (phaseEndEpochMs > now) {
            remainingSeconds = ((phaseEndEpochMs - now + 999L) / 1000L).toInt().coerceAtLeast(1)
        }
        phaseEndEpochMs = 0L
        currentRunState = RunState.PAUSED
        lastEventMessage = "已暂停（释放常亮锁）"
        persistActivePhaseState()
        dismissKeepAliveBadge()
        releaseAllWakeLocks()
        maybeRefreshEInkOutputs(force = true)
    }

    private fun resetToFreshWork(reason: String) {
        mainHandler.removeCallbacks(tickRunnable)
        dismissRestCatOverlay()
        currentPhase = Phase.WORK
        remainingSeconds = workDurationSec
        phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
        currentRunState = RunState.RUNNING
        lastEventMessage = reason
        lastDisplayedMinute = -1
        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireScreenBrightWakeLock()
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun skipCurrentPhaseManually() {
        mainHandler.removeCallbacks(tickRunnable)
        if (currentPhase == Phase.WORK) {
            currentPhase = Phase.REST
            remainingSeconds = restDurationSec
            lastEventMessage = "手动跳过工作（未满时长不计入统计）→ 小猫已跳出霸屏休息 ${restDurationMinutes} 分钟"
        } else {
            dismissRestCatOverlay()
            currentPhase = Phase.WORK
            remainingSeconds = workDurationSec
            lastEventMessage = "已结束休息收起小猫 → 进入下一轮 ${workDurationMinutes} 分钟工作"
        }
        phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
        currentRunState = RunState.RUNNING
        lastDisplayedMinute = -1
        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireScreenBrightWakeLock()
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun onPhaseCompletedNaturally() {
        mainHandler.removeCallbacks(tickRunnable)
        if (currentPhase == Phase.WORK) {
            statsRepo.recordCompletedPomodoro()
            currentPhase = Phase.REST
            remainingSeconds = restDurationSec
            phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
            lastEventMessage = "已完成1个完整番茄！小猫已跳出霸屏休息 ${restDurationMinutes} 分钟"
            triggerPhaseTransitionAlert(
                title = "工作 ${workDurationMinutes} 分钟结束 · 小猫霸屏休息 ${restDurationMinutes} 分钟",
                body = "今日已完成 ${statsRepo.getTodayCount()} 个番茄，小猫已占领书页，请远眺休息 ${restDurationMinutes} 分钟。",
                isWorkCompleted = true
            )
        } else {
            dismissRestCatOverlay()
            currentPhase = Phase.WORK
            remainingSeconds = workDurationSec
            phaseEndEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L
            lastEventMessage = "${restDurationMinutes} 分钟休息结束！小猫已收起，自动进入下一轮 ${workDurationMinutes} 分钟工作"
            triggerPhaseTransitionAlert(
                title = "休息 ${restDurationMinutes} 分钟结束 · 小猫让出书页",
                body = "已自动收起霸屏黑猫，开启新一轮 ${workDurationMinutes} 分钟专注看书/工作。",
                isWorkCompleted = false
            )
        }
        currentRunState = RunState.RUNNING
        lastDisplayedMinute = -1
        persistActivePhaseState()
        scheduleHardwarePhaseAlarm(phaseEndEpochMs)
        acquireScreenBrightWakeLock()
        acquireCpuPartialWakeLock()
        maybeRefreshEInkOutputs(force = true)
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    private fun maybeRefreshEInkOutputs(force: Boolean) {
        val displayMinute = (remainingSeconds + 59) / 60
        val minuteChanged = (lastDisplayedMinute != -1 && displayMinute != lastDisplayedMinute)

        if (!force && !highSpeedRefreshMode) {
            if (displayMinute == lastDisplayedMinute) {
                return
            }
        }
        lastDisplayedMinute = displayMinute

        if (minuteChanged && minuteTickEnabled && currentRunState == RunState.RUNNING) {
            MinimalistSoundEngine.playSubtleMinuteTick()
        }

        notificationManager.notify(NOTIFICATION_ID_TIMER, buildOngoingNotification())
        syncRestCatOverlayState()

        val uiIntent = Intent(BROADCAST_UI_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_PHASE, currentPhase.name)
            putExtra(EXTRA_RUN_STATE, currentRunState.name)
            putExtra(EXTRA_REMAINING_SEC, remainingSeconds)
            putExtra(EXTRA_WORK_MINUTES, workDurationMinutes)
            putExtra(EXTRA_REST_MINUTES, restDurationMinutes)
            putExtra(EXTRA_LOCK_RULE, lockRuleMode.name)
            putExtra(EXTRA_HIGH_SPEED, highSpeedRefreshMode)
            putExtra(EXTRA_SOUND_PRESET, soundPreset.name)
            putExtra(EXTRA_ALERT_MODE, alertMode.name)
            putExtra(EXTRA_MINUTE_TICK, minuteTickEnabled)
            putExtra(EXTRA_LOCKSCREEN_NOTIF, lockscreenNotifEnabled)
            putExtra(EXTRA_TODAY_POMODOROS, statsRepo.getTodayCount())
            putExtra(EXTRA_TOTAL_POMODOROS, statsRepo.getTotalCount())
            putExtra(EXTRA_LAST_EVENT_MSG, lastEventMessage)
        }
        sendBroadcast(uiIntent)
    }

    private fun triggerPhaseTransitionAlert(title: String, body: String, isWorkCompleted: Boolean) {
        val hasAudioSpeaker = checkAudioOutputAvailable()

        val targetActivityClass = if (isWorkCompleted) {
            RestCatOverlayActivity::class.java
        } else {
            MainActivity::class.java
        }

        val contentIntent = PendingIntent.getActivity(
            this, if (isWorkCompleted) 3001 else 0,
            Intent(this, targetActivityClass).apply {
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
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        if (isWorkCompleted) {
            alertBuilder.setFullScreenIntent(contentIntent, true)
        }

        notificationManager.notify(NOTIFICATION_ID_ALERT, alertBuilder.build())

        val shouldPlaySound = when (alertMode) {
            MinimalistSoundEngine.AlertMode.SOUND_PRIMARY -> hasAudioSpeaker && soundPreset != MinimalistSoundEngine.SoundPreset.MUTE
            MinimalistSoundEngine.AlertMode.SOUND_ONLY -> soundPreset != MinimalistSoundEngine.SoundPreset.MUTE
            MinimalistSoundEngine.AlertMode.SOUND_AND_FLASH -> soundPreset != MinimalistSoundEngine.SoundPreset.MUTE
            MinimalistSoundEngine.AlertMode.FLASH_ONLY -> false
        }

        if (shouldPlaySound) {
            MinimalistSoundEngine.playPreset(soundPreset, isWorkCompleted)
        }

        val shouldFlashScreen = when (alertMode) {
            MinimalistSoundEngine.AlertMode.SOUND_PRIMARY -> !hasAudioSpeaker || soundPreset == MinimalistSoundEngine.SoundPreset.MUTE
            MinimalistSoundEngine.AlertMode.SOUND_ONLY -> false
            MinimalistSoundEngine.AlertMode.SOUND_AND_FLASH -> true
            MinimalistSoundEngine.AlertMode.FLASH_ONLY -> true
        }

        if (shouldFlashScreen) {
            val flashIntent = Intent(BROADCAST_EINK_FLASH).apply {
                setPackage(packageName)
                putExtra("alert_title", title)
                putExtra("has_speaker", hasAudioSpeaker)
            }
            sendBroadcast(flashIntent)
        }
    }

    private fun checkAudioOutputAvailable(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }
        return false
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

        val skipPending = PendingIntent.getService(
            this, 12,
            Intent(this, PomodoroForegroundService::class.java).apply { action = ACTION_SKIP_PHASE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resetPending = PendingIntent.getService(
            this, 13,
            Intent(this, PomodoroForegroundService::class.java).apply { action = ACTION_RESET },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exitPending = PendingIntent.getService(
            this, 14,
            Intent(this, PomodoroForegroundService::class.java).apply { action = ACTION_STOP_AND_EXIT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val phaseLabel = if (currentPhase == Phase.WORK) {
            "工作中 (${workDurationMinutes}分钟)"
        } else {
            "休息中 (${restDurationMinutes}分钟)"
        }
        val timeText = formatRemainingForNotification()
        val stateSubtitle = when (currentRunState) {
            RunState.RUNNING -> "剩余: $timeText · 今日 ${statsRepo.getTodayCount()} 番茄 · @墨茄popo"
            RunState.PAUSED -> "已暂停 (剩余 $timeText) · 点击继续"
            RunState.STOPPED_ON_LOCK -> "已熄屏作废停止 · 解锁自动重开 ${workDurationMinutes} 分钟工作"
        }

        val pauseActionLabel = if (currentRunState == RunState.RUNNING) "暂停" else "继续"
        val totalPhaseSec = if (currentPhase == Phase.WORK) workDurationSec else restDurationSec
        val elapsedSec = (totalPhaseSec - remainingSeconds).coerceIn(0, totalPhaseSec)

        // 根据「锁屏通知提示」开关设置 Android Notification 锁屏可见性 (VISIBILITY_PUBLIC vs VISIBILITY_SECRET)
        val lockscreenVisibility = if (lockscreenNotifEnabled) {
            NotificationCompat.VISIBILITY_PUBLIC
        } else {
            NotificationCompat.VISIBILITY_SECRET
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_TIMER)
            .setSmallIcon(R.drawable.ic_pomodoro_eink)
            .setContentTitle("【$phaseLabel】剩余 $timeText")
            .setContentText(stateSubtitle)
            .setSubText("墨茄番茄钟 v1.0")
            .setProgress(totalPhaseSec, elapsedSec, false)
            .setVisibility(lockscreenVisibility)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent)
            .addAction(0, pauseActionLabel, togglePausePending)
            .addAction(0, "跳过", skipPending)
            .addAction(0, "退出程序", exitPending)
            .build()
    }

    private fun formatRemainingForNotification(): String {
        return if (highSpeedRefreshMode) {
            val m = remainingSeconds / 60
            val s = remainingSeconds % 60
            String.format("%02d:%02d", m, s)
        } else {
            val m = (remainingSeconds + 59) / 60
            "${m} 分钟"
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenBrightWakeLock() {
        try {
            if (screenWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "Clear6Pomodoro::ScreenWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (screenWakeLock?.isHeld == false) {
                screenWakeLock?.acquire(180 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseScreenBrightWakeLock() {
        try {
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun acquireCpuPartialWakeLock() {
        // 同步启动无声听书级音频保活通道，防止汉王系统在切去《微信读书》时清理后台
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
        releaseScreenBrightWakeLock()
        try {
            if (cpuPartialWakeLock?.isHeld == true) {
                cpuPartialWakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenEventReceiver, filter)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun createBorderedButtonBackground(fillColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setStroke(dpToPx(2), Color.BLACK)
        }
    }

    /**
     * 硬件级系统闹钟兜底唤醒 (AlarmManager.setAlarmClock)：
     * - 当处于 WORK 工作阶段时，直接向系统 AlarmClock 注册 PendingIntent.getActivity(RestCatOverlayActivity)！
     *   这是安卓 11 官方时钟闹钟特权通道 (mAllowBgActivityStartsOnSend = true)：
     *   1) 完全不需要被汉王禁用的「悬浮窗权限 (SYSTEM_ALERT_WINDOW)」；
     *   2) 哪怕本应用在后台被汉王系统 100% 强杀清理，到点那一秒安卓系统内核也会直接弹出透明黑猫 Activity 占领《微信读书》书页！
     * - 当处于 REST 休息阶段时，注册 Broadcast 闹钟，5 分钟休息到点自动关闭黑猫回到《微信读书》。
     */
    private fun scheduleHardwarePhaseAlarm(triggerAtMillis: Long) {
        if (triggerAtMillis <= 0L) return
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
     * 第一重防杀装甲：在《微信读书》看书工作阶段，挂载右上角「防杀保活微胶囊 / 1px 隐形锚点」
     * 只要应用在 WindowManager 拥有活动悬浮窗，安卓系统就会将其锁定为 VISIBLE_APP_ADJ（屏幕可见进程），
     * 汉王系统的切后台自动杀进程机制绝对不会清理拥有可见悬浮窗的应用！
     * 点击该微胶囊可在「🐾 15' 剩余分钟角标」与「1x1像素全透明隐形保活」之间自由切换。
     */
    private fun syncKeepAliveWorkBadge() {
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!canOverlay || currentPhase != Phase.WORK || currentRunState != RunState.RUNNING) {
            dismissKeepAliveBadge()
            return
        }

        val m = (remainingSeconds + 59) / 60
        val badgeText = if (isKeepAliveBadgeStealth) "" else "🐾${m}'"

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        if (keepAliveBadgeView == null) {
            try {
                val badge = TextView(this).apply {
                    text = badgeText
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = createBorderedButtonBackground(Color.WHITE)
                    setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
                    alpha = if (isKeepAliveBadgeStealth) 0.02f else 0.95f
                    setOnClickListener {
                        isKeepAliveBadgeStealth = !isKeepAliveBadgeStealth
                        if (isKeepAliveBadgeStealth) {
                            text = "·"
                            setPadding(1, 1, 1, 1)
                            background = null
                            alpha = 0.02f
                            Toast.makeText(
                                this@PomodoroForegroundService,
                                "已切换为「隐形防杀保活锚点」，后台看书绝不掉线",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            text = "🐾${(remainingSeconds + 59) / 60}'"
                            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
                            background = createBorderedButtonBackground(Color.WHITE)
                            alpha = 0.95f
                        }
                    }
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dpToPx(8)
                    y = dpToPx(6)
                }

                windowManager.addView(badge, params)
                keepAliveBadgeView = badge
            } catch (_: Exception) {}
        } else {
            if (!isKeepAliveBadgeStealth) {
                keepAliveBadgeView?.text = badgeText
            }
        }
    }

    private fun dismissKeepAliveBadge() {
        val badge = keepAliveBadgeView ?: return
        try {
            windowManager.removeView(badge)
        } catch (_: Exception) {}
        keepAliveBadgeView = null
    }

    /**
     * 核心功能：当进入 REST 休息阶段时，直接通过 WindowManager (TYPE_APPLICATION_OVERLAY)
     * 在微信读书 / KOReader 等任意阅读软件上方弹出全屏透明背景大黑猫占领书页！
     * 休息 5 分钟结束自动移除悬浮层，无缝继续看书。
     */
    private fun syncRestCatOverlayState() {
        syncKeepAliveWorkBadge()

        if (currentPhase != Phase.REST || currentRunState == RunState.STOPPED_ON_LOCK) {
            if (hasLaunchedTranslucentCatActivity) {
                hasLaunchedTranslucentCatActivity = false
                sendBroadcast(Intent(BROADCAST_DISMISS_CAT_OVERLAY).apply { setPackage(packageName) })
            }
            dismissRestCatOverlay()
            return
        }

        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!canOverlay) {
            // 汉王 Clear 6 系统禁用了悬浮窗权限（提示“此功能会导致您手机的速度变慢”）：
            // 直接拉起免悬浮窗权限的「100%全透明窗口 RestCatOverlayActivity」浮在《微信读书》书页正上方！
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

        val formattedTime = if (highSpeedRefreshMode) {
            val m = remainingSeconds / 60
            val s = remainingSeconds % 60
            String.format("%02d:%02d", m, s)
        } else {
            val m = (remainingSeconds + 59) / 60
            String.format("%02d", m)
        }
        val subtitleText = "分钟休息剩余 · 黑猫已占领书页（请远眺护眼）"

        if (overlayRootLayout == null) {
            try {
                val root = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(if (isOverlayTransparentBg) Color.TRANSPARENT else Color.WHITE)
                    setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(24))
                    isClickable = true
                    isFocusable = true
                }

                val catView = RestingCatEInkView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(380)
                    )
                    updateRestCountdown(formattedTime, subtitleText)
                    setWagging(currentRunState == RunState.RUNNING)
                }
                overlayCatView = catView
                root.addView(catView)

                // 底部墨水屏快捷控制条（白底黑框，确保在微信读书文字上方清晰可点）
                val bar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    background = createBorderedButtonBackground(Color.WHITE)
                    setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(8)
                    }
                }

                val btnSwitchCat = Button(this).apply {
                    text = "🎲 换只猫"
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = createBorderedButtonBackground(Color.WHITE)
                    setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
                    setOnClickListener {
                        catView.performClick()
                    }
                }

                val btnToggleBg = Button(this).apply {
                    text = if (isOverlayTransparentBg) "底色:透明趴书页" else "底色:纯白遮书页"
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = createBorderedButtonBackground(Color.WHITE)
                    setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = dpToPx(6)
                        rightMargin = dpToPx(6)
                    }
                    setOnClickListener {
                        isOverlayTransparentBg = !isOverlayTransparentBg
                        root.setBackgroundColor(if (isOverlayTransparentBg) Color.TRANSPARENT else Color.WHITE)
                        text = if (isOverlayTransparentBg) "底色:透明趴书页" else "底色:纯白遮书页"
                    }
                }
                overlayBgModeBtn = btnToggleBg

                val btnSkipRest = Button(this).apply {
                    text = "结束休息 · 继续看书"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = createBorderedButtonBackground(Color.BLACK)
                    setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
                    setOnClickListener {
                        skipCurrentPhaseManually()
                    }
                }

                bar.addView(btnSwitchCat)
                bar.addView(btnToggleBg)
                bar.addView(btnSkipRest)
                root.addView(bar)

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
            overlayCatView?.setWagging(currentRunState == RunState.RUNNING)
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
        overlayBgModeBtn = null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ongoingChannel = NotificationChannel(
                CHANNEL_ID_TIMER,
                "番茄钟常驻与锁屏计时栏",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在通知栏与锁屏界面实时显示当前剩余时长、进度条及暂停/跳过/重置按钮"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "番茄钟阶段完成提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "工作或休息阶段结束时弹出提醒"
                enableVibration(false)
            }

            notificationManager.createNotificationChannel(ongoingChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 当用户在多任务列表切换微信读书或清理最近任务时，若未主动点「退出」，0.5秒内自动拉起保活
        if (!statsRepo.isUserExited() && currentRunState == RunState.RUNNING) {
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
        dismissKeepAliveBadge()
        dismissRestCatOverlay()
        releaseAllWakeLocks()
        try {
            unregisterReceiver(screenEventReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}