package com.hanvon.clear6.pomodoro

import android.content.*
import android.graphics.Color
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class RestCatOverlayActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var catView: RestingCatEInkView
    private var openedTimestampMs: Long = 0L

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PomodoroForegroundService.BROADCAST_UI_STATE -> {
                    val phase = intent.getStringExtra(PomodoroForegroundService.EXTRA_PHASE) ?: "REST"
                    val runState = intent.getStringExtra(PomodoroForegroundService.EXTRA_RUN_STATE) ?: "RUNNING"
                    val remainingSec = intent.getIntExtra(PomodoroForegroundService.EXTRA_REMAINING_SEC, 300)

                    if (runState == PomodoroForegroundService.RunState.STOPPED_ON_LOCK.name) {
                        closeTranslucentOverlay()
                        return
                    }
                    if (phase != PomodoroForegroundService.Phase.REST.name) {
                        if (System.currentTimeMillis() - openedTimestampMs > 2500L) {
                            closeTranslucentOverlay()
                        }
                        return
                    }

                    val m = (remainingSec + 59) / 60
                    val formattedTime = String.format("%02d", m)
                    catView.updateRestCountdown(formattedTime, "分钟休息剩余")
                    catView.setWagging(runState == PomodoroForegroundService.RunState.RUNNING.name)
                }
                PomodoroForegroundService.BROADCAST_DISMISS_CAT_OVERLAY,
                PomodoroForegroundService.BROADCAST_EXIT_APP -> {
                    closeTranslucentOverlay()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        openedTimestampMs = System.currentTimeMillis()
        notifyServiceEnterRestIfNeeded(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }

        catView = RestingCatEInkView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            updateRestCountdown("05", "分钟休息剩余")
            setWagging(true)
        }
        rootLayout.addView(catView)

        setContentView(rootLayout)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        openedTimestampMs = System.currentTimeMillis()
        notifyServiceEnterRestIfNeeded(intent)
    }

    private fun notifyServiceEnterRestIfNeeded(incomingIntent: Intent?) {
        try {
            val actionToSend = if (incomingIntent?.action == PomodoroForegroundService.ACTION_ALARM_PHASE_EXPIRED) {
                PomodoroForegroundService.ACTION_ALARM_PHASE_EXPIRED
            } else {
                PomodoroForegroundService.ACTION_REQUEST_UI_SYNC
            }
            val serviceIntent = Intent(this, PomodoroForegroundService::class.java).apply {
                action = actionToSend
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        val filter = IntentFilter().apply {
            addAction(PomodoroForegroundService.BROADCAST_UI_STATE)
            addAction(PomodoroForegroundService.BROADCAST_DISMISS_CAT_OVERLAY)
            addAction(PomodoroForegroundService.BROADCAST_EXIT_APP)
        }
        registerReceiver(overlayReceiver, filter)
        val syncIntent = Intent(this, PomodoroForegroundService::class.java).apply {
            action = PomodoroForegroundService.ACTION_REQUEST_UI_SYNC
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(syncIntent)
        } else {
            startService(syncIntent)
        }
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
        try {
            unregisterReceiver(overlayReceiver)
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 小猫休息期间不响应多余操作，倒计时结束或锁屏自动关闭
    }

    private fun closeTranslucentOverlay() {
        catView.setWagging(false)
        finish()
        overridePendingTransition(0, 0)
    }
}