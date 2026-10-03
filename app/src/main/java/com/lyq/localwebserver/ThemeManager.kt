package com.lyq.localwebserver

import android.content.Context
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ThemeManager {
    const val PREFS_NAME = "theme_settings"
    const val KEY_DESIGN_STYLE = "design_style" // "modern" or "classic"
    const val KEY_BG_COLOR = "bg_color"         // 颜色 hex，如 "#0F1729", 或 "" (系统默认)
    const val KEY_BG_IMAGE_EXISTS = "bg_image_exists"
    const val KEY_CARD_COLOR = "card_color"     // 颜色 hex，如 "#FFFFFF", 或 "" (系统默认)

    fun isModern(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DESIGN_STYLE, "modern") != "classic"
    }

    fun setDesignStyle(context: Context, style: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DESIGN_STYLE, style).apply()
    }

    fun getBgColor(context: Context): Int? {
        val hex = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BG_COLOR, "") ?: ""
        return if (hex.isNotEmpty()) try { Color.parseColor(hex) } catch (_: Exception) { null } else null
    }

    fun setBgColor(context: Context, hex: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_BG_COLOR, hex)
            .putBoolean(KEY_BG_IMAGE_EXISTS, false)
            .apply()
    }

    fun hasCustomBgImage(context: Context): Boolean {
        val file = File(context.filesDir, "custom_bg.jpg")
        val exists = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BG_IMAGE_EXISTS, false)
        return exists && file.exists()
    }

    fun getCustomBgFile(context: Context): File {
        return File(context.filesDir, "custom_bg.jpg")
    }

    fun saveCustomBgImage(context: Context, uri: Uri): Boolean {
        return try {
            val file = getCustomBgFile(context)
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { ins ->
                FileOutputStream(file).use { output ->
                    ins.copyTo(output)
                }
            }
            if (!file.exists() || file.length() == 0L) return false
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_BG_IMAGE_EXISTS, true)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearCustomBgImage(context: Context) {
        val file = getCustomBgFile(context)
        if (file.exists()) file.delete()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BG_IMAGE_EXISTS, false)
            .putString(KEY_BG_COLOR, "")
            .apply()
    }

    fun getCardColor(context: Context): Int? {
        val hex = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CARD_COLOR, "") ?: ""
        return if (hex.isNotEmpty()) try { Color.parseColor(hex) } catch (_: Exception) { null } else null
    }

    fun setCardColor(context: Context, hex: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARD_COLOR, hex).apply()
    }
}
