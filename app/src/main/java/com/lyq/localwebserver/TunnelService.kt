package com.lyq.localwebserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlin.concurrent.thread

/**
 * 公网隧道服务：通过免费 SSH 反向隧道将本地 HTTP 服务映射到公网。
 * 仅保留仍在运行的服务商：localhost.run
 */
// 隧道服务商定义（顶层，供 MainActivity 引用）
data class Provider(val key: String, val label: String, val host: String, val port: Int, val remotePort: Int)

class TunnelService : Service() {

    companion object {
        const val ACTION_TUNNEL = "com.lyq.localwebserver.TUNNEL"
        const val EXTRA_STATUS = "status"
        const val EXTRA_URL = "url"
        var active = false
        var publicUrl: String? = null

        val PROVIDERS = listOf(
            Provider("localhost.run", "localhost.run", "localhost.run", 22, 80)
        )

        fun providerByKey(key: String): Provider = PROVIDERS.firstOrNull { it.key == key } ?: PROVIDERS[0]

        private var session: Session? = null
        private var port = 8080
        private var currentProvider = PROVIDERS[0]
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(8, buildNotification("公网隧道准备中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        port = intent?.getIntExtra("port", 8080) ?: 8080
        val providerKey = intent?.getStringExtra("provider")
        if (providerKey != null) {
            currentProvider = providerByKey(providerKey)
        }
        if (!active) startTunnel()
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        thread(name = "tunnel") {
            try {
                active = true
                sendStatus("正在连接 ${currentProvider.label} ...")

                val jsch = JSch()
                val s: Session = jsch.getSession("nokey", currentProvider.host, currentProvider.port)
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

                // 反向端口转发：公网 remotePort -> 本地 port
                s.setPortForwardingR(currentProvider.remotePort, "localhost", port)
                sendStatus("隧道已建立，等待分配公网地址...")

                // 读取输出解析公网 URL
                val channel: com.jcraft.jsch.Channel = s.openChannel("shell")
                val reader = channel.inputStream.bufferedReader()
                channel.connect()

                var found = false
                reader.forEachLine { line ->
                    if (!found) {
                        val url = extractUrl(line, currentProvider.key)
                        if (url != null) {
                            found = true
                            publicUrl = url
                            sendUrl(url)
                            updateNotification("公网访问: $url")
                        }
                    }
                }

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

    private fun extractUrl(line: String, providerKey: String): String? {
        return Regex("https?://[a-zA-Z0-9.-]+\\.(lhr\\.life|localhost\\.run)").find(line)?.value
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