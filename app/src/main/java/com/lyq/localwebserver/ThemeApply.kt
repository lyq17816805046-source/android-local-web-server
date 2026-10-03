package com.lyq.localwebserver

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.card.MaterialCardView

/**
 * 把「设计语言（现代/经典）+ 自定义背景 + 卡片颜色」应用到任意界面。
 * 主页和设置页都用它，避免只有主页生效、设置页漏掉。
 */
object ThemeApply {

    /** 应用背景（自定义背景图 > 自定义背景色 > 系统默认） */
    fun background(context: Context, root: View) {
        if (ThemeManager.hasCustomBgImage(context)) {
            try {
                val bmp = BitmapFactory.decodeFile(ThemeManager.getCustomBgFile(context).absolutePath)
                if (bmp != null) {
                    root.background = BitmapDrawable(context.resources, bmp)
                    return
                }
            } catch (_: Exception) {
            }
        }
        val color = ThemeManager.getBgColor(context)
        if (color != null) root.setBackgroundColor(color) else root.background = null
    }

    /** 应用卡片样式：现代=16dp 圆角，经典=直角 */
    fun cards(context: Context, root: View) {
        val modern = ThemeManager.isModern(context)
        val density = context.resources.displayMetrics.density
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val color = ThemeManager.getCardColor(context)
            ?: if (isNight) 0xFF14181F.toInt() else 0xFFFDFCFF.toInt()
        walk(root, modern, color, density)
    }

    private fun walk(v: View, modern: Boolean, color: Int, density: Float) {
        if (v is MaterialCardView) {
            try {
                v.radius = if (modern) 16f * density else 0f
            } catch (_: Exception) {
            }
            try {
                v.setCardBackgroundColor(color)
            } catch (_: Exception) {
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), modern, color, density)
        }
    }
}
