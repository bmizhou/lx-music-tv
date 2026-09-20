package com.lxmusic.tv.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Android TV / 大屏设备统一屏幕自适应管理器
 *
 * 解决不同 TV、闺蜜机、触摸电视（如 24 寸 1080P 触摸屏等）因厂商固件 densityDpi 配置差异
 * （例如标准 65寸 TV 固件为 320dpi/density=2.0，而触摸电视固件设为 160dpi/density=1.0）
 * 导致 UI 缩水、字体变小、侧栏变窄以及卡片比例畸变裁剪的根本问题。
 *
 * 基准设计视口：960dp（对应标准 1080p Android TV 的 320dpi 视口）。
 * 无论物理分辨率与原始 DPI 如何变化，全屏逻辑宽度恒定自适应为 960dp。
 */
object ScreenAdaptation {
    private const val TAG = "LX-ScreenAdapt"

    /** TV 标准设计基准宽度（dp） */
    const val DESIGN_WIDTH_DP = 960f

    /** 系统原始参数（用于设备信息展示与对照诊断） */
    var rawWidthPixels: Int = 1920
        private set
    var rawHeightPixels: Int = 1080
        private set
    var rawDensityDpi: Int = 320
        private set
    var rawDensity: Float = 2.0f
        private set

    /** 自适应后的目标密度 */
    var adaptedDensity: Float = 2.0f
        private set
    var adaptedDensityDpi: Int = 320
        private set

    private var initialized = false

    /**
     * 初始化并计算适配 Density
     */
    fun init(context: Context) {
        if (!initialized) {
            val realDm = getRealDisplayMetrics(context)
            rawWidthPixels = realDm.widthPixels
            rawHeightPixels = realDm.heightPixels
            rawDensityDpi = realDm.densityDpi
            rawDensity = realDm.density

            // 无论横竖屏，TV 模式下均以较长边作为视口宽度基准
            val widthPx = max(rawWidthPixels, rawHeightPixels).toFloat()
            adaptedDensity = if (widthPx > 0f) widthPx / DESIGN_WIDTH_DP else 2.0f
            adaptedDensityDpi = (adaptedDensity * 160f).toInt()
            initialized = true
            Log.i(
                TAG,
                "屏幕适配初始化: 原始物理指标(${rawWidthPixels}x${rawHeightPixels}, ${rawDensityDpi}dpi, density=${rawDensity}) -> 适配基准960dp(density=${adaptedDensity}, ${adaptedDensityDpi}dpi)"
            )
        }
        apply(context)
    }

    /**
     * 将适配后的 Density 同步到 Context 及其 Application 的 Resources 中（作用于原生 View 等）
     */
    fun apply(context: Context) {
        val dm = context.resources.displayMetrics
        dm.density = adaptedDensity
        dm.scaledDensity = adaptedDensity
        dm.densityDpi = adaptedDensityDpi

        val appDm = context.applicationContext.resources.displayMetrics
        appDm.density = adaptedDensity
        appDm.scaledDensity = adaptedDensity
        appDm.densityDpi = adaptedDensityDpi
    }

    private fun getRealDisplayMetrics(context: Context): DisplayMetrics {
        val dm = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = wm.currentWindowMetrics
                val bounds = windowMetrics.bounds
                val config = context.resources.configuration
                dm.widthPixels = max(bounds.width(), bounds.height())
                dm.heightPixels = min(bounds.width(), bounds.height())
                dm.densityDpi = config.densityDpi
                dm.density = config.densityDpi / 160f
                dm.scaledDensity = dm.density
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
            }
        }
        if (dm.widthPixels == 0 || dm.heightPixels == 0) {
            val resMetrics = context.resources.displayMetrics
            dm.widthPixels = resMetrics.widthPixels
            dm.heightPixels = resMetrics.heightPixels
            dm.densityDpi = resMetrics.densityDpi
            dm.density = resMetrics.density
            dm.scaledDensity = resMetrics.scaledDensity
        }
        return dm
    }
}
