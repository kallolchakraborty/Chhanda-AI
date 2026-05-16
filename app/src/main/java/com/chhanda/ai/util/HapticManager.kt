package com.chhanda.ai.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HapticManager @Inject constructor(
    private val context: Context
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    enum class HapticPattern {
        LIGHT_TICK,
        SUCCESS_DOUBLE_TAP,
        ERROR_PULSE,
        MESSAGE_RECEIVED,
        THINKING_START,
        HEAVY_CLICK
    }

    fun play(pattern: HapticPattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return

        val effect = when (pattern) {
            HapticPattern.LIGHT_TICK -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                } else {
                    VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }
            HapticPattern.SUCCESS_DOUBLE_TAP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), intArrayOf(0, 120, 0, 200), -1)
                } else {
                    VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
                }
            }
            HapticPattern.ERROR_PULSE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100, 50, 100), intArrayOf(0, 255, 0, 255, 0, 255), -1)
                } else {
                    VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100, 50, 100), -1)
                }
            }
            HapticPattern.MESSAGE_RECEIVED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                } else {
                    VibrationEffect.createWaveform(longArrayOf(0, 30, 100, 30), -1)
                }
            }
            HapticPattern.THINKING_START -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                } else {
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }
            HapticPattern.HEAVY_CLICK -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } else {
                    VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }
        }

        vibrator.vibrate(effect)
    }

    /**
     * Special tick for streaming tokens.
     * Uses a very light effect to avoid annoying the user.
     */
    fun streamingTick() {
        if (vibrator == null || !vibrator.hasVibrator()) return
        
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(5, 50) // Very light
        }
        vibrator.vibrate(effect)
    }
}
