package com.lyq.localwebserver

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class WebServerService : Service() {
    companion object {
        const val ACTION_LOG = "com.lyq.localwebserver.LOG"
        const val ACTION_STATS = "com.lyq.localwebserver.STATS"
        var running = false
        var currentSite = "default"
        private var server: ServerSocket? = null
        private var port = 8080
        private var password: String? = null

        fun localIp(): String {
            try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                    ?.let { return it.hostAddress ?: "127.0.0.1" }
            } catch (_: Throwable) {}
            return "127.0.0.1"
        }
    }

    private var root: File? = null

    override fun onCreate() {
        super.onCreate()
        root = File(filesDir, "sites/$currentSite").apply { mkdirs() }
        notifyChannel()
        startForeground(7, notification())
        broadcast("服务已初始化，站点: $currentSite")
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        port = i?.getIntExtra("port", 8080) ?: 8080
        val site = i?.getStringExtra("site")
        if (site != null && site.isNotEmpty()) {
            currentSite = site
        }
        password = i?.getStringExtra("password")?.takeIf { it.isNotEmpty() }
        root = File(filesDir, "sites/$currentSite").apply { mkdirs() }
        if (!running) launch()
        else broadcast("已重新加载站点: $currentSite（需重启服务使站点切换生效）")
        return START_STICKY
    }

    private fun launch() {
        try {
            server?.close()
        } catch (_: Throwable) {}
        server = null
        try {
            server = ServerSocket(port)
            running = true
            broadcast("HTTP 服务已启动: http://${localIp()}:$port  |  站点: $currentSite")
        } catch (e: Exception) {
            broadcast("启动失败: ${e.message}")
            stopSelf()
            return
        }
        thread(name = "http-server") {
            while (running) {
                try {
                    val s = server?.accept()
                    if (s != null) thread { handle(s) }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun handle(s: Socket) {
        val startMs = System.currentTimeMillis()
        s.use {
            val input = BufferedReader(InputStreamReader(it.getInputStream()))
            val first = input.readLine() ?: return
            var authHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Authorization:")) authHeader = line.substringAfter("Authorization:").trim()
            }
            val parts = first.split(" ")
            val raw = if (parts.size > 1) parts[1] else "/"

            // 访问密码校验（HTTP Basic Auth）
            val pwd = password
            if (pwd != null) {
                val expected = "Basic " + android.util.Base64.encodeToString((":$pwd").toByteArray(), android.util.Base64.NO_WRAP)
                if (authHeader != expected) {
                    writeResponse(it, 401, "text/html; charset=utf-8",
                        "<!DOCTYPE html><meta charset='utf-8'><h1>401 需要访问密码</h1><p>请输入密码访问本站。</p>".toByteArray(),
                        "WWW-Authenticate: Basic realm=\"LocalWebServer\"\r\n")
                    broadcast("401  ${raw}")
                    incrementStats()
                    return
                }
            }

            val path = URLDecoder.decode(raw.substringBefore('?'), "UTF-8").removePrefix("/")
            val base = root ?: return
            val safe = File(base, path).canonicalFile
            if (safe.path != base.canonicalPath && !safe.canonicalPath.startsWith(base.canonicalPath + File.separator)) {
                broadcast("400  非法路径")
                writeResponse(it, 400, "text/html; charset=utf-8", "<h1>400 Bad Request</h1>".toByteArray())
                incrementStats()
                return
            }
            val target = if (safe.isDirectory) File(safe, "index.html") else safe
            val code = if (target.exists() && target.isFile) 200 else 404
            val body = if (code == 200) target.readBytes()
            else if (safe.isDirectory) directory(safe, path, currentSite).toByteArray()
            else notFoundPage(raw).toByteArray()
            writeResponse(it, code, mime(target.name), body)
            val dur = System.currentTimeMillis() - startMs
            broadcast("${code}  ${raw}  (${dur}ms)")
            incrementStats()
        }
    }

    private fun writeResponse(s: Socket, code: Int, type: String, body: ByteArray, extraHeaders: String? = null) {
        val out = s.getOutputStream()
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Content-Type: $type\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        if (extraHeaders != null) sb.append(extraHeaders)
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun directory(dir: File, path: String, siteName: String) = buildString {
        append("<!DOCTYPE html><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
        append("<style>")
        append("*{margin:0;padding:0;box-sizing:border-box}")
        append("body{font-family:system-ui,-apple-system,sans-serif;background:#f5f7fa;color:#1a1c1e;min-height:100vh}")
        append(".header{background:linear-gradient(135deg,#1565C0,#1976D2);color:#fff;padding:24px 20px}")
        append(".header h1{font-size:20px;font-weight:500;margin-bottom:6px}")
        append(".header span{font-size:13px;opacity:.85}")
        append(".body{max-width:680px;margin:0 auto;padding:20px}")
        append(".bread{font-size:13px;color:#666;margin-bottom:16px}")
        append(".bread a{color:#1565C0;text-decoration:none}")
        append(".card{background:#fff;border-radius:12px;box-shadow:0 1px 3px rgba(0,0,0,.08);overflow:hidden}")
        append(".item{display:flex;align-items:center;padding:14px 16px;border-bottom:1px solid #f0f0f0;text-decoration:none;color:#333;transition:background .15s}")
        append(".item:last-child{border-bottom:0}")
        append(".item:hover{background:#f8faff}")
        append(".icon{width:36px;height:36px;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:18px;margin-right:14px}")
        append(".icon-dir{background:#e3f2fd}.icon-file{background:#f5f5f5}")
        append(".name{flex:1;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
        append(".size{font-size:12px;color:#999;margin-left:12px}")
        append(".up{font-weight:500;color:#1565C0}")
        append(".empty{text-align:center;padding:48px 20px;color:#999}")
        append(".empty .big{font-size:48px;margin-bottom:12px}")
        append(".footer{text-align:center;padding:16px;font-size:11px;color:#bbb}")
        append("</style>")
        append("<div class='header'><h1>📁 $siteName</h1><span>/$path</span></div>")
        append("<div class='body'>")
        if (path.isNotEmpty()) {
            val parent = path.substringBeforeLast('/', "")
            append("<div class='bread'><a href='/'>🏠 根目录</a> / $path</div>")
            append("<a class='item up' href='/${if (parent.isEmpty()) "" else "$parent/"}'>")
            append("<span class='icon icon-dir'>⬆</span><span class='name'>上级目录</span></a>")
        } else {
            append("<div class='bread'>🏠 根目录</div>")
        }
        append("<div class='card'>")
        val files = dir.listFiles()
        if (files == null || files.isEmpty()) {
            append("<div class='empty'><div class='big'>📭</div><div>此目录为空</div></div>")
        } else {
            files.sortedBy { it.name }.forEach { f ->
                val href = if (path.isEmpty()) URLEncoder.encode(f.name, "UTF-8")
                           else "$path/${URLEncoder.encode(f.name, "UTF-8")}"
                val isDir = f.isDirectory
                append("<a class='item' href='/$href'>")
                append("<span class='icon ${if (isDir) "icon-dir" else "icon-file"}'>${if (isDir) "📁" else "📄"}</span>")
                append("<span class='name'>${f.name}</span>")
                if (!isDir) {
                    val sz = f.length()
                    val szStr = when { sz < 1024 -> "${sz}B"; sz < 1048576 -> "${sz/1024}KB"; else -> "${"%.1f".format(sz/1048576.0)}MB" }
                    append("<span class='size'>$szStr</span>")
                }
                append("</a>")
            }
        }
        append("</div></div>")
        append("<div class='footer'>本地网页托管 · $siteName</div>")
    }

    private fun notFoundPage(raw: String) = buildString {
        append("<!DOCTYPE html><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
        append("<style>body{font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f5f7fa;color:#333}.box{text-align:center;padding:40px;background:#fff;border-radius:16px;box-shadow:0 2px 8px rgba(0,0,0,.08);max-width:400px;width:90%}.box h1{font-size:72px;margin:0;color:#1565C0}.box p{font-size:16px;color:#666;margin:12px 0 24px}.box a{color:#1565C0;text-decoration:none;font-size:14px}code{background:#f0f0f0;padding:2px 8px;border-radius:4px;font-size:13px}</style>")
        append("<div class='box'><h1>404</h1><p>页面未找到</p><p><code>${raw.take(60)}</code></p><a href='/'>← 返回首页</a></div>")
    }

    private fun mime(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "svg" -> "image/svg+xml"
        "txt" -> "text/plain; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun broadcast(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        sendBroadcast(Intent(ACTION_LOG).putExtra("message", "[$t] $s"))
    }

    // 访问统计：持久化 totalVisits，广播给 UI
    private fun incrementStats() {
        try {
            val prefs = getSharedPreferences("stats", MODE_PRIVATE)
            val total = prefs.getLong("total_visits", 0L) + 1
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
            val todayKey = "day_$today"
            val todayCount = prefs.getLong(todayKey, 0L) + 1
            prefs.edit()
                .putLong("total_visits", total)
                .putLong(todayKey, todayCount)
                .putString("last_today", today)
                .apply()
            sendBroadcast(Intent(ACTION_STATS)
                .putExtra("total", total)
                .putExtra("today", todayCount))
        } catch (_: Exception) {}
    }

    fun getStats(): Pair<Long, Long> {
        val prefs = getSharedPreferences("stats", MODE_PRIVATE)
        val total = prefs.getLong("total_visits", 0L)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val todayCount = prefs.getLong("day_$today", 0L)
        return Pair(total, todayCount)
    }

    private fun notifyChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(
                NotificationChannel("web", "网页托管服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "HTTP 服务运行状态"
                })
        }
    }

    private fun notification() = NotificationCompat.Builder(this, "web")
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("本地网页托管")
        .setContentText("HTTP 服务运行中 — 端口: $port — 站点: $currentSite")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        broadcast("HTTP 服务已停止")
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}