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
    private lateinit var btnProvider1: com.google.android.material.button.MaterialButton
    private lateinit var btnProvider2: com.google.android.material.button.MaterialButton
    private lateinit var btnTestConnection: com.google.android.material.button.MaterialButton
    private lateinit var statsView: TextView
    // 三页容器 + 底部导航
    private lateinit var pageHome: View
    private lateinit var pageFiles: View
    private lateinit var pageConsole: View
    private lateinit var tabHome: View
    private lateinit var tabFiles: View
    private lateinit var tabConsole: View
    private lateinit var tabHomeIcon: ImageView
    private lateinit var tabFilesIcon: ImageView
    private lateinit var tabConsoleIcon: ImageView
    private lateinit var tabHomeText: TextView
    private lateinit var tabFilesText: TextView
    private lateinit var tabConsoleText: TextView
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar
    private var selectedProvider: Provider = TunnelService.LOCALHOST_RUN
    private var accessPassword: String? = null
    // 自定义服务器参数（从 SharedPreferences 恢复）
    private var customHost: String = ""
    private var customPort: Int = 22
    private var customRemotePort: Int = 80
    private var customUsername: String = "root"
    private var customPassword: String = ""

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == WebServerService.ACTION_STATS) {
                val total = i.getLongExtra("total", 0L)
                val today = i.getLongExtra("today", 0L)
                handler.post { updateStats(total, today) }
            }
        }
    }

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
            btnProvider1 = findViewById(R.id.btnProvider1)
            btnProvider2 = findViewById(R.id.btnProvider2)
            btnTestConnection = findViewById(R.id.btnTestConnection)
            statsView = findViewById(R.id.statsView)
            pageHome = findViewById(R.id.pageHome)
            pageFiles = findViewById(R.id.pageFiles)
            pageConsole = findViewById(R.id.pageConsole)
            tabHome = findViewById(R.id.tabHome)
            tabFiles = findViewById(R.id.tabFiles)
            tabConsole = findViewById(R.id.tabConsole)
            tabHomeIcon = findViewById(R.id.tabHomeIcon)
            tabFilesIcon = findViewById(R.id.tabFilesIcon)
            tabConsoleIcon = findViewById(R.id.tabConsoleIcon)
            tabHomeText = findViewById(R.id.tabHomeText)
            tabFilesText = findViewById(R.id.tabFilesText)
            tabConsoleText = findViewById(R.id.tabConsoleText)
            toolbar = findViewById(R.id.toolbar)

            // 底部导航
            tabHome.setOnClickListener { switchPage(0) }
            tabFiles.setOnClickListener { switchPage(1) }
            tabConsole.setOnClickListener { switchPage(2) }

            // 右上角菜单（已在布局通过 app:menu 配置）
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_info -> { showAboutDialog(); true }
                    else -> false
                }
            }

            // 图标着色
            tabHomeIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_faint))
            tabFilesIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_faint))
            tabConsoleIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_faint))

            switchPage(0)

            registerReceiver(receiver, IntentFilter(WebServerService.ACTION_LOG), RECEIVER_NOT_EXPORTED)
            registerReceiver(tunnelReceiver, IntentFilter(TunnelService.ACTION_TUNNEL), RECEIVER_NOT_EXPORTED)
            registerReceiver(statsReceiver, IntentFilter(WebServerService.ACTION_STATS), RECEIVER_NOT_EXPORTED)

            // 读取已保存的访问密码
            accessPassword = getSharedPreferences("settings", MODE_PRIVATE).getString("access_password", null)

            // 恢复上次使用的站点（防止退出重进后站点丢失）
            val savedSite = getSharedPreferences("settings", MODE_PRIVATE).getString("current_site", null)
            if (savedSite != null && savedSite.isNotEmpty()) {
                WebServerService.currentSite = savedSite
            }

            // 恢复自定义服务器参数
            val cprefs = getSharedPreferences("settings", MODE_PRIVATE)
            customHost = cprefs.getString("custom_host", "") ?: ""
            customPort = cprefs.getInt("custom_port", 22)
            customRemotePort = cprefs.getInt("custom_remote_port", 80)
            customUsername = cprefs.getString("custom_username", "root") ?: "root"
            customPassword = cprefs.getString("custom_password", "") ?: ""

            switchServer.setOnCheckedChangeListener { _, checked ->
                if (checked) startServer() else stopServer()
            }

            switchTunnel.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!WebServerService.running) {
                        Snackbar.make(root, "请先启动 HTTP 服务", Snackbar.LENGTH_SHORT).show()
                        switchTunnel.isChecked = false
                    } else {
                        showTunnelWarningIfFirst()
                    }
                } else {
                    stopTunnel()
                }
            }

            btnProvider1.setOnClickListener { selectBuiltinProvider() }
            btnProvider2.setOnClickListener { showCustomProviderDialog() }

            btnTestConnection.setOnClickListener { testProviderConnection() }

            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQRCode).setOnClickListener { showQRCode() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAddr).setOnClickListener { copyAddress() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSetPassword).setOnClickListener { showPasswordDialog() }

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
            refreshStatsFromPrefs()
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
        val intent = Intent(this, WebServerService::class.java)
            .putExtra("port", port.text.toString().toIntOrNull() ?: 8080)
            .putExtra("site", site)
        accessPassword?.let { intent.putExtra("password", it) }
        return intent
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
        tunnelStatus.text = "正在连接 ${selectedProvider.label} ..."
        val intent = Intent(this, TunnelService::class.java)
            .putExtra("port", p)
            .putExtra("provider", selectedProvider.key)
        if (selectedProvider.key == "custom") {
            intent.putExtra("custom_host", customHost)
                .putExtra("custom_port", customPort)
                .putExtra("custom_remote_port", customRemotePort)
                .putExtra("custom_username", customUsername)
                .putExtra("custom_password", customPassword)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTunnel() {
        stopService(Intent(this, TunnelService::class.java))
        tunnelStatus.text = "关闭。开启后可通过外网访问你的站点"
        tunnelUrl.visibility = View.GONE
        tunnelUrl.text = ""
    }

    // 选择内置 localhost.run
    private fun selectBuiltinProvider() {
        selectedProvider = TunnelService.LOCALHOST_RUN
        btnProvider1.isChecked = true
        btnProvider2.isChecked = false
        if (switchTunnel.isChecked) {
            stopTunnel()
            handler.postDelayed({ startTunnel() }, 500)
        }
    }

    // 自定义服务器弹窗
    private fun showCustomProviderDialog() {
        val hostInput = EditText(this).apply {
            hint = "服务器地址，如 myserver.com 或 1.2.3.4"
            setText(customHost)
            setPadding(32, 16, 32, 16)
        }
        val portInput = EditText(this).apply {
            hint = "SSH 端口（默认 22）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(customPort.toString())
            setPadding(32, 16, 32, 16)
        }
        val remotePortInput = EditText(this).apply {
            hint = "公网端口（默认 80）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(customRemotePort.toString())
            setPadding(32, 16, 32, 16)
        }
        val userInput = EditText(this).apply {
            hint = "用户名（默认 root）"
            setText(customUsername)
            setPadding(32, 16, 32, 16)
        }
        val pwdInput = EditText(this).apply {
            hint = "密码（无密码留空）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(customPassword)
            setPadding(32, 16, 32, 16)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 16, 40, 16)
            addView(hostInput)
            addView(portInput)
            addView(remotePortInput)
            addView(userInput)
            addView(pwdInput)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("自定义服务器")
            .setMessage("填入你自己的 SSH 服务器信息（需要服务器开启 SSH 并允许反向隧道）")
            .setView(container)
            .setPositiveButton("保存并使用") { _, _ ->
                customHost = hostInput.text.toString().trim()
                customPort = portInput.text.toString().toIntOrNull() ?: 22
                customRemotePort = remotePortInput.text.toString().toIntOrNull() ?: 80
                customUsername = userInput.text.toString().trim().ifEmpty { "root" }
                customPassword = pwdInput.text.toString().trim()
                if (customHost.isEmpty()) {
                    Snackbar.make(root, "请填写服务器地址", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 持久化
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("custom_host", customHost)
                    .putInt("custom_port", customPort)
                    .putInt("custom_remote_port", customRemotePort)
                    .putString("custom_username", customUsername)
                    .putString("custom_password", customPassword)
                    .apply()
                selectedProvider = TunnelService.CUSTOM
                btnProvider1.isChecked = false
                btnProvider2.isChecked = true
                if (switchTunnel.isChecked) {
                    stopTunnel()
                    handler.postDelayed({ startTunnel() }, 500)
                }
                Snackbar.make(root, "已切换到自定义服务器: $customHost", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 首次开启公网模式时显示 5 秒警告
    private fun showTunnelWarningIfFirst() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val warned = prefs.getBoolean("tunnel_warned", false)
        if (warned) {
            startTunnel()
            return
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ 公网访问提醒")
            .setMessage(
                "开启公网隧道后，你的站点将暴露到互联网，任何获得该公网地址的人都能访问。\n\n" +
                "请注意：\n" +
                "• 不要托管敏感、隐私内容\n" +
                "• 免费隧道服务可能不稳定，公网地址会变化\n" +
                "• 关闭开关即可立即停止公网访问\n\n" +
                "是否继续开启？"
            )
            .setPositiveButton("继续开启") { _, _ ->
                prefs.edit().putBoolean("tunnel_warned", true).apply()
                startTunnel()
            }
            .setNegativeButton("取消") { _, _ ->
                switchTunnel.isChecked = false
            }
            .show()
    }

    // 从 SharedPreferences 读统计刷新
    private fun refreshStatsFromPrefs() {
        val prefs = getSharedPreferences("stats", MODE_PRIVATE)
        val total = prefs.getLong("total_visits", 0L)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())
        val todayCount = prefs.getLong("day_$today", 0L)
        updateStats(total, todayCount)
    }

    private fun updateStats(total: Long, today: Long) {
        statsView.text = "今日访问 $today · 累计访问 $total"
    }

    // 获取当前可分享的地址：优先公网地址，其次局域网
    private fun currentShareUrl(): String? {
        val publicUrl = TunnelService.publicUrl
        if (publicUrl != null && publicUrl.isNotEmpty()) return publicUrl
        if (WebServerService.running) {
            return "http://${WebServerService.localIp()}:${port.text}/"
        }
        return null
    }

    // 显示二维码
    private fun showQRCode() {
        val url = currentShareUrl()
        if (url == null) {
            Snackbar.make(root, "请先启动 HTTP 服务或开启公网隧道", Snackbar.LENGTH_SHORT).show()
            return
        }
        val qrBitmap = QRCodeGenerator.generate(url) ?: run {
            Snackbar.make(root, "二维码生成失败", Snackbar.LENGTH_SHORT).show()
            return
        }
        val iv = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            setPadding(40, 40, 40, 40)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("扫码访问")
            .setMessage(url)
            .setView(iv)
            .setPositiveButton("关闭", null)
            .show()
    }

    // 复制地址到剪贴板
    private fun copyAddress() {
        val url = currentShareUrl()
        if (url == null) {
            Snackbar.make(root, "请先启动 HTTP 服务或开启公网隧道", Snackbar.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        Snackbar.make(root, "已复制: $url", Snackbar.LENGTH_SHORT).show()
    }

    // 设置访问密码
    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            hint = "留空则关闭密码保护"
            setPadding(32, 16, 32, 16)
        }
        input.setText(accessPassword ?: "")
        MaterialAlertDialogBuilder(this)
            .setTitle("访问密码")
            .setMessage("设置后，访问者需要输入密码才能浏览网站（HTTP Basic 认证）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val pwd = input.text.toString().trim()
                accessPassword = if (pwd.isEmpty()) null else pwd
                getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putString("access_password", accessPassword).apply()
                if (WebServerService.running) {
                    // 重启服务使密码生效
                    stopServer()
                    handler.postDelayed({ startServer() }, 600)
                }
                val tip = if (accessPassword == null) "已关闭访问密码" else "访问密码已设置"
                Snackbar.make(root, tip, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 测试当前服务商连接（简单 TCP socket 检测 SSH 端口）
    private fun testProviderConnection() {
        thread(name = "test-conn") {
            val host = if (selectedProvider.key == "custom") customHost else selectedProvider.host
            val sshPort = if (selectedProvider.key == "custom") customPort else selectedProvider.port
            if (host.isEmpty()) {
                handler.post {
                    Snackbar.make(root, "请先配置自定义服务器", Snackbar.LENGTH_SHORT).show()
                }
                return@thread
            }
            val ok = try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, sshPort), 8000)
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
            handler.post {
                if (ok) {
                    Snackbar.make(root, "✅ $host 连接正常", Snackbar.LENGTH_SHORT).show()
                    tunnelStatus.text = "✅ $host 测试连接成功"
                } else {
                    Snackbar.make(root, "❌ $host 无法连接", Snackbar.LENGTH_LONG).show()
                    tunnelStatus.text = "❌ $host 连接失败"
                }
            }
        }
    }

    private fun updateUI(on: Boolean) {
        statusDot.setImageResource(if (on) R.drawable.dot_on else R.drawable.dot_off)
        status.text = if (on) "服务运行中" else "服务已停止"
        status.setTextColor(if (on) ContextCompat.getColor(this, R.color.success) else ContextCompat.getColor(this, R.color.text_secondary))
        val site = WebServerService.currentSite.ifEmpty { "default" }
        address.text = if (on) "http://${WebServerService.localIp()}:${port.text}/  |  $site" else "http://—:${port.text}"
        switchServer.isChecked = on
    }

    private fun append(s: String) {
        handler.post {
            log.append("$s\n")
            val scrollView = findViewById<ScrollView>(R.id.logScrollView)
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
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

            // 同步当前站点并持久化
            WebServerService.currentSite = targetSite
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putString("current_site", targetSite).apply()
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
                    text = "（空目录，请导入文件）"; textSize = 12f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_faint)); setPadding(8, 12, 8, 0)
                })
                return
            }
            files.sortedBy { it.name }.forEach { f ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(8, 10, 8, 10); gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this).apply {
                    text = "${if (f.isDirectory) "📁 " else "📄 "}${f.name}"; textSize = 13f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_file))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = "✕"; textSize = 16f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger)); setPadding(16, 4, 4, 4)
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
        getSharedPreferences("settings", MODE_PRIVATE)
            .edit().putString("current_site", newSite).apply()
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

    private fun switchPage(index: Int) {
        pageHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageFiles.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageConsole.visibility = if (index == 2) View.VISIBLE else View.GONE

        val accent = ContextCompat.getColor(this, R.color.accent)
        val faint = ContextCompat.getColor(this, R.color.text_faint)
        val dim = ContextCompat.getColor(this, R.color.text_dim)

        tabHomeIcon.setColorFilter(if (index == 0) accent else faint)
        tabFilesIcon.setColorFilter(if (index == 1) accent else faint)
        tabConsoleIcon.setColorFilter(if (index == 2) accent else faint)

        tabHomeText.setTextColor(if (index == 0) accent else dim)
        tabFilesText.setTextColor(if (index == 1) accent else dim)
        tabConsoleText.setTextColor(if (index == 2) accent else dim)

        tabHome.isSelected = index == 0
        tabFiles.isSelected = index == 1
        tabConsole.isSelected = index == 2
    }

    private fun showAboutDialog() {
        val title = getString(R.string.app_name)
        val version = "7.1.2"
        val packageName = packageName
        val msg = "版本：$version\n包名：$packageName\n\n" +
                "一个纯本地运行的 Android 静态网页托管服务。\n" +
                "支持多站点、文件导入、二维码分享、访问密码、公网隧道。\n\n" +
                "已使用固定签名，更新可直接覆盖安装。"
        MaterialAlertDialogBuilder(this)
            .setTitle("关于 $title")
            .setMessage(msg)
            .setPositiveButton("GitHub 主页") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lyqxml/android-local-web-server")))
                } catch (_: Exception) {}
            }
            .setNeutralButton("检查更新") { _, _ ->
                checkUpdate()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun checkUpdate() {
        thread(name = "check-update") {
            var result = "检查失败，请稍后再试"
            try {
                val url = java.net.URL("https://api.github.com/repos/lyqxml/android-local-web-server/releases/latest")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val text = conn.inputStream.bufferedReader().readText()
                val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                result = if (tag != null) {
                    if (tag == "v7.1.2") "已是最新版本（$tag）"
                    else "发现新版本：$tag\n请到 GitHub Releases 下载"
                } else "无法解析版本信息"
            } catch (e: Exception) {
                result = "检查失败：${e.message}"
            }
            val finalResult = result
            handler.post {
                MaterialAlertDialogBuilder(this)
                    .setTitle("检查更新")
                    .setMessage(finalResult)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { unregisterReceiver(tunnelReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(statsReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}