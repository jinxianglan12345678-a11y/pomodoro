package com.hanvon.clear6.pomodoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootAndUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // 若用户已主动点击「退出程序」，则解锁或重启时不自动拉起后台服务，彻底零耗电
        if (PomodoroStatsRepository(context).isUserExited()) return
        when (action) {
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