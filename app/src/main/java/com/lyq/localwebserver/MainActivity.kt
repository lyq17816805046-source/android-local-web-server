package com.lyq.localwebserver

import android.Manifest
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
import kotlin.concurrent.thread

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
    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == WebServerService.ACTION_LOG) {
                append(i.getStringExtra("message") ?: "")
            }
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            thread { uris.forEach { uri -> copyFileToSite(uri) } }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
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
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
            }

            updateUI(false)
            refreshFileList()
        } catch (e: Exception) {
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun makeIntent(): Intent {
        val site = WebServerService.currentSite.ifEmpty { "default" }
        return Intent(this, WebServerService::class.java)
            .putExtra("port", port.text.toString().toIntOrNull() ?: 8080)
            .putExtra("site", site)
    }

    private fun startServer() {
        val p = port.text.toString().toIntOrNull() ?: 8080
        if (p !in 1024..65535) {
            Snackbar.make(root, "端口范围 1024-65535", Snackbar.LENGTH_SHORT).show()
            switchServer.isChecked = false
            return
        }
        ContextCompat.startForegroundService(this, makeIntent())
        handler.postDelayed({ updateUI(WebServerService.running) }, 800)
    }

    private fun stopServer() {
        stopService(Intent(this, WebServerService::class.java))
        handler.postDelayed({ updateUI(false) }, 800)
    }

    private fun updateUI(on: Boolean) {
        statusDot.setImageResource(if (on) R.drawable.dot_on else R.drawable.dot_off)
        status.text = if (on) "服务运行中" else "服务已停止"
        status.setTextColor(if (on) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
        val site = WebServerService.currentSite.ifEmpty { "default" }
        address.text = if (on) "http://${WebServerService.localIp()}:${port.text}/  |  $site" else "http://—:${port.text}"
        switchServer.isChecked = on
    }

    private fun append(s: String) {
        handler.post {
            log.append("$s\n")
            (log.parent.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun pickFiles() {
        try {
            pickFile.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Snackbar.make(root, "无法打开文件选择器: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun copyFileToSite(uri: Uri) {
        try {
            var name: String? = null
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx)
                    }
                }
            } catch (_: Exception) {}

            val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
            val site = WebServerService.currentSite.ifEmpty { "default" }
            val dest = File(filesDir, "sites/$site/$fileName")
            dest.parentFile?.mkdirs()

            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            append("[文件] 已导入: $fileName")
            handler.post {
                Snackbar.make(root, "已导入: $fileName", Snackbar.LENGTH_SHORT).show()
                refreshFileList()
            }
        } catch (e: SecurityException) {
            handler.post { Snackbar.make(root, "权限不足，请重新选择文件", Snackbar.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            handler.post { Snackbar.make(root, "导入失败: ${e.message}", Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun refreshFileList() {
        try {
            val site = WebServerService.currentSite.ifEmpty { "default" }
            val dir = File(filesDir, "sites/$site")
            currentSite.text = "站点: $site"
            fileList.removeAllViews()
            val files = dir.listFiles()
            if (files == null || files.isEmpty()) {
                fileList.addView(TextView(this).apply {
                    text = "（空目录，请导入文件）"; textSize = 12f; setTextColor(0xFFAAAAAA.toInt()); setPadding(8, 12, 8, 0)
                })
                return
            }
            files.sortedBy { it.name }.forEach { f ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(8, 10, 8, 10); gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this).apply {
                    text = "${if (f.isDirectory) "📁 " else "📄 "}${f.name}"; textSize = 13f; setTextColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = "✕"; textSize = 16f; setTextColor(0xFFCC4444.toInt()); setPadding(16, 4, 4, 4)
                    setOnClickListener {
                        try { f.deleteRecursively() } catch (_: Exception) {}
                        refreshFileList(); Snackbar.make(root, "已删除: ${f.name}", Snackbar.LENGTH_SHORT).show()
                    }
                })
                fileList.addView(row)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "刷新文件列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartServerWithSite(newSite: String) {
        WebServerService.currentSite = newSite
        File(filesDir, "sites/$newSite").mkdirs()
        refreshFileList()
        stopServer()
        // wait for old service to fully stop, then restart
        handler.postDelayed({
            ContextCompat.startForegroundService(this, makeIntent())
            handler.postDelayed({ updateUI(WebServerService.running) }, 800)
        }, 1200)
    }

    private fun showSitesDialog() {
        try {
            val sitesDir = File(filesDir, "sites").apply { mkdirs() }
            if (!File(sitesDir, "default").exists()) File(sitesDir, "default").mkdirs()
            val sites = sitesDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: listOf("default")
            val current = WebServerService.currentSite.ifEmpty { "default" }
            val checked = sites.indexOf(current).coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle("切换站点")
                .setSingleChoiceItems(sites.toTypedArray(), checked) { dialog, which ->
                    dialog.dismiss()
                    val chosen = sites[which]
                    if (chosen != WebServerService.currentSite) {
                        restartServerWithSite(chosen)
                    }
                    Snackbar.make(root, "已切换到: $chosen", Snackbar.LENGTH_SHORT).show()
                }
                .setPositiveButton("确定", null)
                .show()
        } catch (e: Exception) {
            Snackbar.make(root, "切换站点失败", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showNewSiteDialog() {
        try {
            val input = EditText(this).apply { hint = "请输入站点名称"; setPadding(32, 16, 32, 16) }
            MaterialAlertDialogBuilder(this)
                .setTitle("新建站点")
                .setMessage("输入站点名（字母/数字/中文/下划线）")
                .setView(input)
                .setPositiveButton("创建") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty() && name.matches(Regex("^[a-zA-Z0-9_\\u4e00-\\u9fa5-]+$"))) {
                        File(filesDir, "sites/$name").mkdirs()
                        restartServerWithSite(name)
                        Snackbar.make(root, "站点已创建: $name", Snackbar.LENGTH_SHORT).show()
                    } else {
                        Snackbar.make(root, "站点名不合法", Snackbar.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            Snackbar.make(root, "创建站点失败", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}