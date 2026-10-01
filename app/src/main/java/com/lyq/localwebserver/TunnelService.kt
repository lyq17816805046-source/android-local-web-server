package com.lyq.localwebserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlin.concurrent.thread

/**
 * 公网隧道服务：通过免费 SSH 反向隧道（localhost.run / serveo.net）
 * 将本地 HTTP 服务映射到公网，供外网访问。
 */
class TunnelService : Service() {

    companion object {
        const val ACTION_TUNNEL = "com.lyq.localwebserver.TUNNEL"
        const val EXTRA_STATUS = "status"
        const val EXTRA_URL = "url"
        var active = false
        var publicUrl: String? = null
        private var session: Session? = null
        private var port = 8080
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(8, buildNotification("公网隧道准备中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        port = intent?.getIntExtra("port", 8080) ?: 8080
        if (!active) startTunnel()
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        thread(name = "tunnel") {
            try {
                val service = "localhost.run"
                sendStatus("正在连接免费隧道 $service ...")
                active = true

                val jsch = JSch()
                val s = jsch.getSession("nokey", service, 22)
                s.setConfig("StrictHostKeyChecking", "no")
                s.setConfig("PreferredAuthentications", "publickey,password")
                s.userInfo = object : UserInfo {
                    override fun getPassphrase(): String? = null
                    override fun getPassword(): String? = ""
                    override fun promptPassword(message: String?): Boolean = true
                    override fun promptPassphrase(message: String?): Boolean = true
                    override fun promptYesNo(message: String?): Boolean = true
                    override fun showMessage(message: String?) {}
                }
                s.connect(20000)
                session = s

                // 反向端口转发：公网 80 -> 本地 port
                s.setPortForwardingR(80, "localhost", port)
                sendStatus("隧道已建立，等待分配公网地址...")

                // 读取 shell 输出，解析公网 URL
                val channel = s.openChannel("shell")
                channel.setInputStream(null)
                val reader = channel.inputStream.bufferedReader()
                channel.connect()

                var found = false
                reader.forEachLine { line ->
                    if (!found) {
                        val url = Regex("https?://[a-zA-Z0-9.-]+\\.(lhr\\.life|serveo\\.net|localhost\\.run)").find(line)?.value
                        if (url != null) {
                            found = true
                            publicUrl = url
                            sendUrl(url)
                            updateNotification("公网访问: $url")
                        }
                    }
                }

                // 循环结束 = 连接断开
                active = false
                publicUrl = null
                session = null
                sendStatus("公网隧道已断开")
                stopSelf()
            } catch (e: Exception) {
                active = false
                publicUrl = null
                sendStatus("公网隧道失败: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun sendStatus(s: String) {
        sendBroadcast(Intent(ACTION_TUNNEL).putExtra(EXTRA_STATUS, s))
        sendBroadcast(Intent(WebServerService.ACTION_LOG).putExtra("message", "[隧道] $s"))
    }

    private fun sendUrl(url: String) {
        sendBroadcast(Intent(ACTION_TUNNEL).putExtra(EXTRA_URL, url))
        sendBroadcast(Intent(WebServerService.ACTION_LOG).putExtra("message", "[隧道] 公网地址: $url"))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("tunnel", "公网隧道", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "公网隧道状态"
                })
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "tunnel")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("公网隧道")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(8, buildNotification(text))
    }

    override fun onDestroy() {
        try { session?.disconnect() } catch (_: Throwable) {}
        session = null
        active = false
        publicUrl = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}