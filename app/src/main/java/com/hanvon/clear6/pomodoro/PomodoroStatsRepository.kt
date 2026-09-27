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
        private const val KEY_SAVED_PHASE = "key_saved_phase"
        private const val KEY_SAVED_RUN_STATE = "key_saved_run_state"
        private const val KEY_PHASE_END_EPOCH_MS = "key_phase_end_epoch_ms"
        private const val KEY_PAUSED_REMAINING_SEC = "key_paused_remaining_sec"
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

    fun isHighSpeedMode(): Boolean {
        return prefs.getBoolean(KEY_HIGH_SPEED_MODE, false)
    }

    fun setHighSpeedMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGH_SPEED_MODE, enabled).apply()
    }

    fun getSoundPreset(): MinimalistSoundEngine.SoundPreset {
        val raw = prefs.getString(KEY_SOUND_PRESET, MinimalistSoundEngine.SoundPreset.CRISP_TICK.name)
            ?: MinimalistSoundEngine.SoundPreset.CRISP_TICK.name
        return runCatching { MinimalistSoundEngine.SoundPreset.valueOf(raw) }
            .getOrDefault(MinimalistSoundEngine.SoundPreset.CRISP_TICK)
    }

    fun setSoundPreset(preset: MinimalistSoundEngine.SoundPreset) {
        prefs.edit().putString(KEY_SOUND_PRESET, preset.name).apply()
    }

    fun isMuteFlashOnly(): Boolean {
        return prefs.getBoolean(KEY_ALERT_MODE, false)
    }

    fun setMuteFlashOnly(mute: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_MODE, mute).apply()
    }

    fun isUserExited(): Boolean {
        return prefs.getBoolean(KEY_USER_EXITED, false)
    }

    fun setUserExited(exited: Boolean) {
        prefs.edit().putBoolean(KEY_USER_EXITED, exited).apply()
    }

    fun saveRuntimeState(
        phase: PomodoroForegroundService.Phase,
        runState: PomodoroForegroundService.RunState,
        endEpochMs: Long,
        pausedRemainingSec: Int
    ) {
        prefs.edit()
            .putString(KEY_SAVED_PHASE, phase.name)
            .putString(KEY_SAVED_RUN_STATE, runState.name)
            .putLong(KEY_PHASE_END_EPOCH_MS, endEpochMs)
            .putInt(KEY_PAUSED_REMAINING_SEC, pausedRemainingSec)
            .apply()
    }

    fun getSavedPhase(): PomodoroForegroundService.Phase {
        val raw = prefs.getString(KEY_SAVED_PHASE, PomodoroForegroundService.Phase.WORK.name)
            ?: PomodoroForegroundService.Phase.WORK.name
        return runCatching { PomodoroForegroundService.Phase.valueOf(raw) }
            .getOrDefault(PomodoroForegroundService.Phase.WORK)
    }

    fun getSavedRunState(): PomodoroForegroundService.RunState {
        val raw = prefs.getString(KEY_SAVED_RUN_STATE, PomodoroForegroundService.RunState.STOPPED_ON_LOCK.name)
            ?: PomodoroForegroundService.RunState.STOPPED_ON_LOCK.name
        return runCatching { PomodoroForegroundService.RunState.valueOf(raw) }
            .getOrDefault(PomodoroForegroundService.RunState.STOPPED_ON_LOCK)
    }

    fun getPhaseEndEpochMs(): Long {
        return prefs.getLong(KEY_PHASE_END_EPOCH_MS, 0L)
    }

    fun getPausedRemainingSec(defaultSec: Int): Int {
        return prefs.getInt(KEY_PAUSED_REMAINING_SEC, defaultSec).coerceAtLeast(1)
    }
}