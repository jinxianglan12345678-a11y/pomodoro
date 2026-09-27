package com.hanvon.clear6.pomodoro

import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * 专为汉王 Clear 6 等禁用「显示在其他应用上层 (SYSTEM_ALERT_WINDOW)」的墨水屏电纸书打造：
 * 采用 Theme.Clear6Pomodoro.TranslucentOverlay (windowIsTranslucent = true + 独立 taskAffinity)，
 * 无需任何悬浮窗权限即可在《微信读书》上方弹出 100% 透明底色的摇尾黑猫占领书页！
 * 休息结束或点击「结束休息 · 继续看书」立即关闭透明层，无缝回到《微信读书》原书页。
 */
class RestCatOverlayActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var catView: RestingCatEInkView
    private lateinit var btnToggleBg: Button
    private var isTransparentBg: Boolean = true
    private var openedTimestampMs: Long = 0L

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PomodoroForegroundService.BROADCAST_UI_STATE -> {
                    val phase = intent.getStringExtra(PomodoroForegroundService.EXTRA_PHASE) ?: "REST"
                    val runState = intent.getStringExtra(PomodoroForegroundService.EXTRA_RUN_STATE) ?: "RUNNING"
                    val remainingSec = intent.getIntExtra(PomodoroForegroundService.EXTRA_REMAINING_SEC, 300)
                    val highSpeed = intent.getBooleanExtra(PomodoroForegroundService.EXTRA_HIGH_SPEED, false)

                    if (runState == PomodoroForegroundService.RunState.STOPPED_ON_LOCK.name) {
                        closeTranslucentOverlay()
                        return
                    }
                    if (phase != PomodoroForegroundService.Phase.REST.name) {
                        // 刚由系统硬件闹钟拉起的前 2.5 秒内等待后台服务完成 WORK -> REST 切换，不提前关闭
                        if (System.currentTimeMillis() - openedTimestampMs > 2500L) {
                            closeTranslucentOverlay()
                        }
                        return
                    }

                    val formattedTime = if (highSpeed) {
                        val m = remainingSec / 60
                        val s = remainingSec % 60
                        String.format("%02d:%02d", m, s)
                    } else {
                        val m = (remainingSec + 59) / 60
                        String.format("%02d", m)
                    }
                    catView.updateRestCountdown(
                        formattedTime,
                        "分钟休息剩余 · 黑猫已占领书页（请远眺护眼）"
                    )
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
            setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(24))
            isClickable = true
            isFocusable = true
        }

        catView = RestingCatEInkView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(380)
            )
            updateRestCountdown("05", "分钟休息剩余 · 黑猫已占领书页（请远眺护眼）")
            setWagging(true)
        }
        rootLayout.addView(catView)

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

        btnToggleBg = Button(this).apply {
            text = "底色:透明趴书页"
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
                isTransparentBg = !isTransparentBg
                rootLayout.setBackgroundColor(if (isTransparentBg) Color.TRANSPARENT else Color.WHITE)
                text = if (isTransparentBg) "底色:透明趴书页" else "底色:纯白遮书页"
            }
        }

        val btnSkipRest = Button(this).apply {
            text = "结束休息 · 继续看书"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = createBorderedButtonBackground(Color.BLACK)
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
            setOnClickListener {
                val skipIntent = Intent(this@RestCatOverlayActivity, PomodoroForegroundService::class.java).apply {
                    action = PomodoroForegroundService.ACTION_SKIP_PHASE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(skipIntent)
                } else {
                    startService(skipIntent)
                }
                closeTranslucentOverlay()
            }
        }

        bar.addView(btnSwitchCat)
        bar.addView(btnToggleBg)
        bar.addView(btnSkipRest)
        rootLayout.addView(bar)

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
        // 休息霸屏期间按返回键提示远眺护眼，或点按钮提前结束休息
    }

    private fun closeTranslucentOverlay() {
        catView.setWagging(false)
        finish()
        overridePendingTransition(0, 0)
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
}