package com.hanvon.clear6.pomodoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class BootAndUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // 若用户已主动点击「退出程序」，则解锁或重启时不自动拉起后台服务，彻底零耗电
        if (PomodoroStatsRepository(context).isUserExited()) return

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isInteractive = pm.isInteractive

        when (action) {
            PomodoroForegroundService.ACTION_ALARM_PHASE_EXPIRED,
            PomodoroForegroundService.ACTION_KEEPALIVE_RESTART -> {
                // 唯一铁律：如果当前处于待机/锁屏状态 (!isInteractive)，绝不在后台继续计时或唤醒弹猫
                if (!isInteractive) {
                    val resetIntent = Intent(context, PomodoroForegroundService::class.java).apply {
                        this.action = PomodoroForegroundService.ACTION_ALARM_PHASE_EXPIRED
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(resetIntent)
                        } else {
                            context.startService(resetIntent)
                        }
                    } catch (_: Exception) {}
                    return
                }

                val serviceIntent = Intent(context, PomodoroForegroundService::class.java).apply {
                    this.action = action
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                // 仅在亮屏看书状态下：利用 AlarmManager.setAlarmClock 特权直接在《微信读书》上方弹出纯粹黑猫霸屏！
                if (action == PomodoroForegroundService.ACTION_ALARM_PHASE_EXPIRED) {
                    try {
                        val catOverlayIntent = Intent(context, RestCatOverlayActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                            )
                        }
                        context.startActivity(catOverlayIntent)
                    } catch (_: Exception) {}
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT -> {
                val serviceIntent = Intent(context, PomodoroForegroundService::class.java).apply {
                    this.action = PomodoroForegroundService.ACTION_UNLOCK_EVENT
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}