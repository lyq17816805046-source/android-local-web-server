package com.lyq.localwebserver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SettingsActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val pickBgImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val ok = ThemeManager.saveCustomBgImage(this, uri)
            if (ok) {
                updateCustomBgStatus()
                Snackbar.make(findViewById(android.R.id.content), "自定义背景图已设置", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(findViewById(android.R.id.content), "图片导入失败", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        setupDesignStyle()
        setupBgColors()
        setupCardColors()
        setupAbout()
    }

    private fun setupDesignStyle() {
        val rg = findViewById<RadioGroup>(R.id.rgDesignStyle)
        val rbModern = findViewById<RadioButton>(R.id.rbModern)
        val rbClassic = findViewById<RadioButton>(R.id.rbClassic)

        if (ThemeManager.isModern(this)) {
            rbModern.isChecked = true
        } else {
            rbClassic.isChecked = true
        }

        rg.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbModern) {
                ThemeManager.setDesignStyle(this, "modern")
            } else {
                ThemeManager.setDesignStyle(this, "classic")
            }
            Snackbar.make(findViewById(android.R.id.content), "设计语言已切换", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupBgColors() {
        val btnDefault = findViewById<Button>(R.id.btnBgDefault)
        val btnDark = findViewById<Button>(R.id.btnBgDark)
        val btnNavy = findViewById<Button>(R.id.btnBgNavy)
        val btnWarm = findViewById<Button>(R.id.btnBgWarm)

        btnDefault.setOnClickListener {
            ThemeManager.setBgColor(this, "")
            updateCustomBgStatus()
            Snackbar.make(findViewById(android.R.id.content), "已恢复系统默认背景", Snackbar.LENGTH_SHORT).show()
        }
        btnDark.setOnClickListener {
            ThemeManager.setBgColor(this, "#0D1117")
            updateCustomBgStatus()
            Snackbar.make(findViewById(android.R.id.content), "背景已设为纯黑", Snackbar.LENGTH_SHORT).show()
        }
        btnNavy.setOnClickListener {
            ThemeManager.setBgColor(this, "#0F1A2E")
            updateCustomBgStatus()
            Snackbar.make(findViewById(android.R.id.content), "背景已设为幽蓝", Snackbar.LENGTH_SHORT).show()
        }
        btnWarm.setOnClickListener {
            ThemeManager.setBgColor(this, "#21262D")
            updateCustomBgStatus()
            Snackbar.make(findViewById(android.R.id.content), "背景已设为深灰", Snackbar.LENGTH_SHORT).show()
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPickBgImage).setOnClickListener {
            pickBgImage.launch("image/*")
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearBgImage).setOnClickListener {
            ThemeManager.clearCustomBgImage(this)
            updateCustomBgStatus()
            Snackbar.make(findViewById(android.R.id.content), "已清除自定义背景", Snackbar.LENGTH_SHORT).show()
        }

        updateCustomBgStatus()
    }

    private fun updateCustomBgStatus() {
        val tv = findViewById<TextView>(R.id.tvCustomBgStatus)
        if (ThemeManager.hasCustomBgImage(this)) {
            tv.text = "当前使用：相册自定义壁纸"
        } else {
            val hex = ThemeManager.getBgColor(this)
            if (hex != null) {
                tv.text = "当前使用：自定义预设背景色"
            } else {
                tv.text = "当前使用：系统默认跟随深浅色"
            }
        }
    }

    private fun setupCardColors() {
        val btnDefault = findViewById<Button>(R.id.btnCardColorDefault)
        val btnLight = findViewById<Button>(R.id.btnCardColorLight)
        val btnDark = findViewById<Button>(R.id.btnCardColorDark)
        val btnBlue = findViewById<Button>(R.id.btnCardColorBlue)

        btnDefault.setOnClickListener {
            ThemeManager.setCardColor(this, "")
            Snackbar.make(findViewById(android.R.id.content), "卡片恢复默认颜色", Snackbar.LENGTH_SHORT).show()
        }
        btnLight.setOnClickListener {
            ThemeManager.setCardColor(this, "#FFFFFF")
            Snackbar.make(findViewById(android.R.id.content), "卡片设为纯白", Snackbar.LENGTH_SHORT).show()
        }
        btnDark.setOnClickListener {
            ThemeManager.setCardColor(this, "#161B22")
            Snackbar.make(findViewById(android.R.id.content), "卡片设为暗灰", Snackbar.LENGTH_SHORT).show()
        }
        btnBlue.setOnClickListener {
            ThemeManager.setCardColor(this, "#1C273A")
            Snackbar.make(findViewById(android.R.id.content), "卡片设为暗蓝", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupAbout() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "8.1.0"
        } catch (_: Exception) { "8.1.0" }

        findViewById<TextView>(R.id.tvAboutVersion).text = "版本：$versionName"
        findViewById<TextView>(R.id.tvAboutPackage).text = "包名：$packageName"

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCheckUpdate).setOnClickListener {
            checkUpdate()
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGithubLink).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lyqxml/android-local-web-server")))
            } catch (_: Exception) {}
        }
    }

    private fun checkUpdate() {
        thread(name = "check-update-settings") {
            var latestTag: String? = null
            var error: String? = null
            try {
                val currentVer = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "8.1.0" } catch (_: Exception) { "8.1.0" }
                val apiUrl = URL("https://api.github.com/repos/lyqxml/android-local-web-server/releases/latest")
                val conn = apiUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val text = conn.inputStream.bufferedReader().readText()
                latestTag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            } catch (e: Exception) {
                error = e.message
            }

            handler.post {
                val cur = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "8.1.0" } catch (_: Exception) { "8.1.0" }
                val msg = when {
                    error != null -> "检查失败：$error"
                    latestTag != null && latestTag.removePrefix("v") == cur.removePrefix("v") -> "已是最新版本（$cur）"
                    latestTag != null -> "发现新版本：$latestTag\n当前版本：$cur\n请到主页检查更新下载"
                    else -> "无法解析版本信息"
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("检查更新")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }
}
