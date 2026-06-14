package com.example.hellorokid.glass.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult

/**
 * Rokid 眼镜定焦镜头（AF modes=0、minFocusDistance=0）的 AF 能力检测。
 * 官方规格：AF Not supported，景深 34cm~∞，设计焦距 1.9m。
 */
object CameraAfHelper {

    fun isFixedFocusLens(characteristics: CameraCharacteristics): Boolean {
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if (hasAdjustableAf(afModes)) return false
        val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        return minFocus == null || minFocus == 0f
    }

    fun hasAdjustableAf(afModes: IntArray): Boolean {
        return afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            || afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
            || afModes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO)
    }

    fun isAeReady(aeState: Int?): Boolean {
        return aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
            || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
            || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
    }

    /** 仅在有可调 AF 时设置 AF 模式；定焦镜头保持 OFF */
    fun applyPreviewAf(builder: CaptureRequest.Builder, afModes: IntArray) {
        when {
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            else ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }
    }

    fun applyStillAf(builder: CaptureRequest.Builder, afModes: IntArray) {
        when {
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO)
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            else ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }
    }

    fun shouldTriggerAf(afModes: IntArray): Boolean = hasAdjustableAf(afModes)
}
