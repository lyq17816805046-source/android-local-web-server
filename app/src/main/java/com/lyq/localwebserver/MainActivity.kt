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
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            thread { importFolderToSite(uri) }
        }
    }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            thread { importZipToSite(uri) }
        }
    }

    private lateinit var switchTunnel: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var tunnelStatus: TextView
    private lateinit var tunnelUrl: TextView

    private val tunnelReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == TunnelService.ACTION_TUNNEL) {
                val status = i.getStringExtra(TunnelService.EXTRA_STATUS)
                val url = i.getStringExtra(TunnelService.EXTRA_URL)
                if (status != null) {
                    handler.post { tunnelStatus.text = status }
                }
                if (url != null) {
                    handler.post {
                        tunnelUrl.visibility = View.VISIBLE
                        tunnelUrl.text = url
                    }
                }
            }
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
            switchTunnel = findViewById(R.id.switchTunnel)
            tunnelStatus = findViewById(R.id.tunnelStatus)
            tunnelUrl = findViewById(R.id.tunnelUrl)

            registerReceiver(receiver, IntentFilter(WebServerService.ACTION_LOG), RECEIVER_NOT_EXPORTED)
            registerReceiver(tunnelReceiver, IntentFilter(TunnelService.ACTION_TUNNEL), RECEIVER_NOT_EXPORTED)

            switchServer.setOnCheckedChangeListener { _, checked ->
                if (checked) startServer() else stopServer()
            }

            switchTunnel.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!WebServerService.running) {
                        Snackbar.make(root, "请先启动 HTTP 服务", Snackbar.LENGTH_SHORT).show()
                        switchTunnel.isChecked = false
                    } else {
                        startTunnel()
                    }
                } else {
                    stopTunnel()
                }
            }

            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnUpload).setOnClickListener { pickFiles() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnImportFolder).setOnClickListener { pickFolder() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnImportZip).setOnClickListener { pickZipFile() }
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
            handleIncomingIntent(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // 处理「打开方式」传入的文件：ZIP 或 HTML
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val uri = when (action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        }
        if (uri != null) {
            val name = queryName(uri).lowercase()
            when {
                name.endsWith(".zip") -> {
                    handler.postDelayed({ importZipToSite(uri) }, 300)
                }
                name.endsWith(".html") || name.endsWith(".htm") -> {
                    handler.postDelayed({ copyFileToSite(uri) }, 300)
                }
                else -> {
                    // 其他文件也尝试复制
                    handler.postDelayed({ copyFileToSite(uri) }, 300)
                }
            }
        }
    }

    private fun queryName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else "file"
                } else "file"
            } ?: uri.lastPathSegment ?: "file"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "file"
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

    private fun startTunnel() {
        val p = port.text.toString().toIntOrNull() ?: 8080
        tunnelUrl.visibility = View.GONE
        tunnelUrl.text = ""
        tunnelStatus.text = "正在连接免费公网隧道..."
        ContextCompat.startForegroundService(this, Intent(this, TunnelService::class.java).putExtra("port", p))
    }

    private fun stopTunnel() {
        stopService(Intent(this, TunnelService::class.java))
        tunnelStatus.text = "关闭。开启后可通过外网访问你的站点"
        tunnelUrl.visibility = View.GONE
        tunnelUrl.text = ""
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

    private fun pickFolder() {
        try {
            pickFolder.launch(null)
        } catch (e: Exception) {
            Snackbar.make(root, "无法打开文件夹选择器: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun pickZipFile() {
        try {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
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

    // 导入文件夹：保留子目录结构，复制到当前站点
    private fun importFolderToSite(treeUri: Uri) {
        try {
            val site = WebServerService.currentSite.ifEmpty { "default" }
            val baseDir = File(filesDir, "sites/$site").apply { mkdirs() }
            var count = 0
            count = copyTree(treeUri, baseDir, count)
            append("[文件夹] 导入了 $count 个文件到站点: $site")
            handler.post {
                Snackbar.make(root, "导入完成，共 $count 个文件", Snackbar.LENGTH_SHORT).show()
                refreshFileList()
            }
        } catch (e: Exception) {
            handler.post { Snackbar.make(root, "文件夹导入失败: ${e.message}", Snackbar.LENGTH_LONG).show() }
        }
    }

    // 递归复制 DocumentFile 树到 File
    private fun copyTree(uri: Uri, destDir: File, count: Int): Int {
        val dfNode = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri) ?: return count
        var c = count
        destDir.mkdirs()
        val items = dfNode.listFiles()
        for (item in items) {
            if (item.isDirectory) {
                val subDir = File(destDir, item.name ?: "dir_${System.currentTimeMillis()}")
                c = copyTree(item.uri, subDir, c)
            } else {
                val name = item.name ?: "file_${System.currentTimeMillis()}"
                val out = File(destDir, name)
                contentResolver.openInputStream(item.uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                c++
            }
        }
        return c
    }

    // 导入 ZIP：先读 site.json，再按配置解压到站点目录
    private fun importZipToSite(uri: Uri) {
        try {
            // 先复制 ZIP 到临时文件
            val tmpZip = File(cacheDir, "import_${System.currentTimeMillis()}.zip")
            contentResolver.openInputStream(uri)?.use { input ->
                tmpZip.outputStream().use { output -> input.copyTo(output) }
            }

            // 第一遍：读取 site.json
            var siteConfig: JSONObject? = null
            try {
                ZipInputStream(FileInputStream(tmpZip)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (e.isDirectory) { zin.closeEntry(); continue }
                        val name = e.name
                        if (name == "site.json" || name.endsWith("/site.json")) {
                            val content = zin.readBytes().toString(Charsets.UTF_8)
                            siteConfig = try { JSONObject(content) } catch (_: Exception) { null }
                        }
                        zin.closeEntry()
                    }
                }
            } catch (e: Exception) {
                handler.post { Snackbar.make(root, "ZIP 读取失败: ${e.message}", Snackbar.LENGTH_LONG).show() }
                tmpZip.delete()
                return
            }

            // 确定目标站点目录
            var targetSite = WebServerService.currentSite.ifEmpty { "default" }
            var siteName: String? = null
            val cfg = siteConfig
            if (cfg != null) {
                val cfgSite = cfg.optString("site", "")
                if (cfgSite.isNotEmpty()) targetSite = cfgSite
                siteName = cfg.optString("name", "")
            }

            // 第二遍：按顺序解压，防止路径穿越
            val destRoot = File(filesDir, "sites/$targetSite").apply { mkdirs() }
            var imported = 0
            ZipInputStream(FileInputStream(tmpZip)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory) {
                        val name = e.name
                        if (name != "site.json" && !name.endsWith("/site.json")) {
                            val safe = safePath(name)
                            if (safe != null) {
                                val out = File(destRoot, safe)
                                out.parentFile?.mkdirs()
                                out.outputStream().use { o -> zin.copyTo(o) }
                                imported++
                            }
                        }
                    }
                    zin.closeEntry()
                }
            }
            tmpZip.delete()

            // 同步当前站点
            WebServerService.currentSite = targetSite
            append("[ZIP] 站点导入完成: ${siteName ?: targetSite}，共 $imported 个文件")
            handler.post {
                val msg = if (siteName != null) "已导入站点「$siteName」：$imported 个文件" else "已导入 $imported 个文件"
                Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show()
                refreshFileList()
            }
        } catch (e: Exception) {
            handler.post { Snackbar.make(root, "ZIP 导入失败: ${e.message}", Snackbar.LENGTH_LONG).show() }
        }
    }

    // 防路径穿越：规范化 zip entry 名称
    private fun safePath(name: String): String? {
        val cleaned = name.replace('\\', '/').trim('/')
        if (cleaned.isEmpty()) return null
        if (cleaned.contains("..")) return null
        if (cleaned.startsWith("/")) return null
        return cleaned
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
        try { unregisterReceiver(tunnelReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}