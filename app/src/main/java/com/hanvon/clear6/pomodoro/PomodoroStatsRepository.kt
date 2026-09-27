package com.hanvon.clear6.pomodoro

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PomodoroStatsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("clear6_pomodoro_stats", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TODAY_DATE = "key_today_date"
        private const val KEY_TODAY_COUNT = "key_today_count"
        private const val KEY_TOTAL_COUNT = "key_total_count"
        private const val KEY_WORK_MINUTES = "key_work_minutes"
        private const val KEY_REST_MINUTES = "key_rest_minutes"
        private const val KEY_LOCK_RULE_MODE = "key_lock_rule_mode"
        private const val KEY_HIGH_SPEED_MODE = "key_high_speed_mode"
        private const val KEY_SOUND_PRESET = "key_sound_preset"
        private const val KEY_ALERT_MODE = "key_alert_mode"
        private const val KEY_MINUTE_TICK = "key_minute_tick"
        private const val KEY_LOCKSCREEN_NOTIF = "key_lockscreen_notif"
        private const val KEY_USER_EXITED = "key_user_exited"
    }

    private fun currentDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    fun recordCompletedPomodoro() {
        val today = currentDateKey()
        val savedDate = prefs.getString(KEY_TODAY_DATE, "") ?: ""
        val currentTodayCount = if (savedDate == today) prefs.getInt(KEY_TODAY_COUNT, 0) else 0
        val currentTotalCount = prefs.getInt(KEY_TOTAL_COUNT, 0)

        prefs.edit()
            .putString(KEY_TODAY_DATE, today)
            .putInt(KEY_TODAY_COUNT, currentTodayCount + 1)
            .putInt(KEY_TOTAL_COUNT, currentTotalCount + 1)
            .apply()
    }

    fun getTodayCount(): Int {
        val today = currentDateKey()
        val savedDate = prefs.getString(KEY_TODAY_DATE, "") ?: ""
        return if (savedDate == today) prefs.getInt(KEY_TODAY_COUNT, 0) else 0
    }

    fun getTotalCount(): Int {
        return prefs.getInt(KEY_TOTAL_COUNT, 0)
    }

    fun getWorkMinutes(): Int {
        return prefs.getInt(KEY_WORK_MINUTES, 15).coerceIn(1, 180)
    }

    fun setWorkMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_WORK_MINUTES, minutes.coerceIn(1, 180)).apply()
    }

    fun getRestMinutes(): Int {
        return prefs.getInt(KEY_REST_MINUTES, 5).coerceIn(1, 90)
    }

    fun setRestMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_REST_MINUTES, minutes.coerceIn(1, 90)).apply()
    }

    fun getLockRuleMode(): PomodoroForegroundService.LockRuleMode {
        val saved = prefs.getString(
            KEY_LOCK_RULE_MODE,
            PomodoroForegroundService.LockRuleMode.ABORT_ON_LOCK.name
        ) ?: PomodoroForegroundService.LockRuleMode.ABORT_ON_LOCK.name
        return runCatching { PomodoroForegroundService.LockRuleMode.valueOf(saved) }
            .getOrDefault(PomodoroForegroundService.LockRuleMode.ABORT_ON_LOCK)
    }

    fun setLockRuleMode(mode: PomodoroForegroundService.LockRuleMode) {
        prefs.edit().putString(KEY_LOCK_RULE_MODE, mode.name).apply()
    }

    fun isHighSpeedRefreshEnabled(): Boolean {
        return prefs.getBoolean(KEY_HIGH_SPEED_MODE, false)
    }

    fun setHighSpeedRefreshEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGH_SPEED_MODE, enabled).apply()
    }

    fun getSoundPreset(): MinimalistSoundEngine.SoundPreset {
        val saved = prefs.getString(KEY_SOUND_PRESET, MinimalistSoundEngine.SoundPreset.CRISP_TICK.name)
            ?: MinimalistSoundEngine.SoundPreset.CRISP_TICK.name
        return runCatching { MinimalistSoundEngine.SoundPreset.valueOf(saved) }
            .getOrDefault(MinimalistSoundEngine.SoundPreset.CRISP_TICK)
    }

    fun setSoundPreset(preset: MinimalistSoundEngine.SoundPreset) {
        prefs.edit().putString(KEY_SOUND_PRESET, preset.name).apply()
    }

    fun getAlertMode(): MinimalistSoundEngine.AlertMode {
        val saved = prefs.getString(KEY_ALERT_MODE, MinimalistSoundEngine.AlertMode.SOUND_PRIMARY.name)
            ?: MinimalistSoundEngine.AlertMode.SOUND_PRIMARY.name
        return runCatching { MinimalistSoundEngine.AlertMode.valueOf(saved) }
            .getOrDefault(MinimalistSoundEngine.AlertMode.SOUND_PRIMARY)
    }

    fun setAlertMode(mode: MinimalistSoundEngine.AlertMode) {
        prefs.edit().putString(KEY_ALERT_MODE, mode.name).apply()
    }

    fun isMinuteTickEnabled(): Boolean {
        return prefs.getBoolean(KEY_MINUTE_TICK, false)
    }

    fun setMinuteTickEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MINUTE_TICK, enabled).apply()
    }

    fun isLockscreenNotificationEnabled(): Boolean {
        return prefs.getBoolean(KEY_LOCKSCREEN_NOTIF, true)
    }

    fun setLockscreenNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCKSCREEN_NOTIF, enabled).apply()
    }

    fun isUserExited(): Boolean {
        return prefs.getBoolean(KEY_USER_EXITED, false)
    }

    fun setUserExited(exited: Boolean) {
        prefs.edit().putBoolean(KEY_USER_EXITED, exited).apply()
    }
}