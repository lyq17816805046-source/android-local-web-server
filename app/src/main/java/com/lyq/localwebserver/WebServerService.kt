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
        var running = false
        var currentSite = "default"
        private var server: ServerSocket? = null
        private var port = 8080

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

    private lateinit var root: File

    override fun onCreate() {
        super.onCreate()
        root = File(filesDir, "sites/$currentSite").apply { mkdirs() }
        notifyChannel()
        startForeground(7, notification())
        broadcast("服务已初始化，站点: $currentSite")
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        port = i?.getIntExtra("port", 8080) ?: 8080
        currentSite = i?.getStringExtra("site") ?: currentSite
        root = File(filesDir, "sites/$currentSite").apply { mkdirs() }
        if (!running) launch()
        return START_STICKY
    }

    private fun launch() {
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
        s.use {
            val input = BufferedReader(InputStreamReader(it.getInputStream()))
            val first = input.readLine() ?: return
            while (input.readLine()?.isNotEmpty() == true) {} // skip headers
            val parts = first.split(" ")
            val raw = if (parts.size > 1) parts[1] else "/"
            val path = URLDecoder.decode(raw.substringBefore('?'), "UTF-8").removePrefix("/")
            val safe = File(root, path).canonicalFile
            val base = root.canonicalFile
            if (safe.path != base.path && !safe.path.startsWith(base.path + File.separator)) {
                broadcast("400  非法路径")
                val body = "<h1>400 Bad Request</h1>".toByteArray()
                writeResponse(it, 400, "text/html; charset=utf-8", body)
                return
            }
            val target = if (safe.isDirectory) File(safe, "index.html") else safe
            val code = if (target.exists() && target.isFile) 200 else 404
            val body = if (code == 200) target.readBytes()
            else if (safe.isDirectory) directory(safe, path).toByteArray()
            else "<h1>404 Not Found</h1><p>${URLEncoder.encode(raw, "UTF-8")}</p>".toByteArray()
            val type = mime(target.name)
            writeResponse(it, code, type, body)
            broadcast("${code}  ${raw}")
        }
    }

    private fun writeResponse(s: Socket, code: Int, type: String, body: ByteArray) {
        val out = s.getOutputStream()
        val status = if (code == 200) "OK" else "Not Found"
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun directory(dir: File, path: String) = buildString {
        append("<!DOCTYPE html><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
        append("<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 16px;color:#333}" +
                "h2{font-size:20px;border-bottom:2px solid #1565C0;padding-bottom:8px}" +
                "ul{list-style:none;padding:0}li{padding:10px 0;border-bottom:1px solid #eee}" +
                "a{color:#1565C0;text-decoration:none;font-size:15px}a:hover{text-decoration:underline}" +
                ".dir{font-weight:600}.dir::after{content:'/'}</style>")
        append("<h2>📁 /$path</h2><ul>")
        if (path.isNotEmpty()) append("<li><a href='/${path.substringBeforeLast('/')}'>⬆ 上级目录</a></li>")
        dir.listFiles()?.sortedBy { it.name }?.forEach {
            append("<li><a class='${if (it.isDirectory) "dir" else ""}' href='/${if (path.isEmpty()) "" else "$path/"}${URLEncoder.encode(it.name, "UTF-8")}'>${it.name}</a></li>")
        }
        append("</ul>")
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
        .setContentText("HTTP 服务运行中 — 端口: $port")
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