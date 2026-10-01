package com.lyq.localwebserver

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var statusDot: ImageView
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var switchServer: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var port: EditText
    private lateinit var log: TextView
    private lateinit var currentSite: TextView
    private lateinit var fileList: LinearLayout
    private lateinit var root: View

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == WebServerService.ACTION_LOG) {
                append(i.getStringExtra("message") ?: "")
            }
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri -> copyFileToSite(uri) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        root = findViewById(android.R.id.content)
        statusDot = findViewById(R.id.statusDot)
        status = findViewById(R.id.status)
        address = findViewById(R.id.address)
        switchServer = findViewById(R.id.switchServer)
        port = findViewById(R.id.port)
        log = findViewById(R.id.log)
        currentSite = findViewById(R.id.currentSite)
        fileList = findViewById(R.id.fileList)

        registerReceiver(receiver, IntentFilter(WebServerService.ACTION_LOG), RECEIVER_NOT_EXPORTED)

        switchServer.setOnCheckedChangeListener { _, checked ->
            if (checked) startServer() else stopServer()
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnUpload).setOnClickListener { pickFiles() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSites).setOnClickListener { showSitesDialog() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewSite).setOnClickListener { showNewSiteDialog() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearLog).setOnClickListener { log.text = "" }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        }

        updateUI(false)
        refreshFileList()
    }

    private fun startServer() {
        val p = port.text.toString().toIntOrNull() ?: 8080
        if (p !in 1024..65535) {
            Snackbar.make(root, "端口范围 1024-65535", Snackbar.LENGTH_SHORT).show()
            switchServer.isChecked = false
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, WebServerService::class.java).putExtra("port", p))
        Handler(Looper.getMainLooper()).postDelayed({ updateUI(WebServerService.running) }, 500)
    }

    private fun stopServer() {
        stopService(Intent(this, WebServerService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({ updateUI(false) }, 500)
    }

    private fun updateUI(on: Boolean) {
        if (on) {
            statusDot.setImageResource(R.drawable.dot_on)
            status.text = "服务运行中"
            status.setTextColor(0xFF4CAF50.toInt())
            address.text = "http://${WebServerService.localIp()}:${port.text}"
            switchServer.isChecked = true
        } else {
            statusDot.setImageResource(R.drawable.dot_off)
            status.text = "服务已停止"
            status.setTextColor(0xFF888888.toInt())
            address.text = "http://—:${port.text}"
            switchServer.isChecked = false
        }
    }

    private fun append(s: String) {
        log.append("$s\n")
        log.post { (log.parent.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun pickFiles() {
        pickFile.launch("*/*")
    }

    private fun copyFileToSite(uri: Uri) {
        val name = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else "file_${System.currentTimeMillis()}"
            } ?: uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
        } catch (_: Exception) { "file_${System.currentTimeMillis()}" }

        val dest = File(filesDir, "sites/${WebServerService.currentSite}/$name")
        dest.parentFile?.mkdirs()
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            append("[文件] 已导入: $name")
            Snackbar.make(root, "已导入: $name", Snackbar.LENGTH_SHORT).show()
            refreshFileList()
        } catch (e: Exception) {
            Snackbar.make(root, "导入失败: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun refreshFileList() {
        val dir = File(filesDir, "sites/${WebServerService.currentSite}")
        currentSite.text = "站点: ${WebServerService.currentSite}"
        fileList.removeAllViews()
        if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
            val tv = TextView(this).apply {
                text = "（空目录，请导入文件）"
                textSize = 12f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(8, 12, 8, 0)
            }
            fileList.addView(tv)
            return
        }
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 10, 8, 10)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = "${if (f.isDirectory) "📁 " else "📄 "}${f.name}"
                textSize = 13f
                setTextColor(0xFF333333.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = TextView(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(0xFFCC4444.toInt())
                setPadding(16, 4, 4, 4)
                setOnClickListener {
                    f.deleteRecursively()
                    refreshFileList()
                    Snackbar.make(root, "已删除: ${f.name}", Snackbar.LENGTH_SHORT).show()
                }
            }
            row.addView(name)
            row.addView(del)
            fileList.addView(row)
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 4; bottomMargin = 4 }
                setBackgroundColor(0xFFEEEEEE.toInt())
            })
        }
    }

    private fun showSitesDialog() {
        val dir = File(filesDir, "sites")
        val sites = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: listOf("default")
        val items = sites.toTypedArray()
        val checked = sites.indexOf(WebServerService.currentSite).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("切换站点")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                WebServerService.currentSite = items[which]
                File(filesDir, "sites/${WebServerService.currentSite}").mkdirs()
                dialog.dismiss()
                refreshFileList()
                if (WebServerService.running) {
                    stopServer()
                    Handler(Looper.getMainLooper()).postDelayed({ startServer() }, 600)
                }
                Snackbar.make(root, "已切换到: ${items[which]}", Snackbar.LENGTH_SHORT).show()
            }
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showNewSiteDialog() {
        val input = EditText(this).apply { hint = "请输入站点名称"; setPadding(32, 16, 32, 16) }
        MaterialAlertDialogBuilder(this)
            .setTitle("新建站点")
            .setMessage("输入站点名（字母/数字/中文）")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name.matches(Regex("^[\\w\\u4e00-\\u9fa5-]+$"))) {
                    File(filesDir, "sites/$name").mkdirs()
                    WebServerService.currentSite = name
                    refreshFileList()
                    if (WebServerService.running) { stopServer(); Handler(Looper.getMainLooper()).postDelayed({ startServer() }, 600) }
                    Snackbar.make(root, "站点已创建: $name", Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(root, "站点名不合法", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}