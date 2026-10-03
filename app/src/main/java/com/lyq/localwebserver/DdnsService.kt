package com.lyq.localwebserver

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * DDNS 服务：定时检测本机公网 IPv4/IPv6，变化时通过 DNSPod API 自动更新域名解析。
 *
 * 需要用户提供（留在设置中由用户填写）：
 *  - domain: 域名（如 example.eu.org）
 *  - token:  DNSPod API Token（格式 "ID,Token"）
 *  - sub:    主机记录（默认 "@"，即根域名）
 */
class DdnsService : Service() {

    companion object {
        const val ACTION_DDNS = "com.lyq.localwebserver.DDNS"
        const val EXTRA_STATUS = "status"
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟
        private const val NOTIF_ID = 9
    }

    private var loopThread: Thread? = null
    private var lastV4 = ""
    private var lastV6 = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("正在初始化..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopThread == null) {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            lastV4 = prefs.getString("ddns_last_v4", "") ?: ""
            lastV6 = prefs.getString("ddns_last_v6", "") ?: ""
            loopThread = thread(name = "ddns") {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        checkOnce()
                    } catch (e: Exception) {
                        updateStatus("出错: ${e.message}")
                    }
                    try {
                        Thread.sleep(CHECK_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        return START_STICKY
    }

    /** 一轮检测 + 更新 */
    private fun checkOnce() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val domain = prefs.getString("ddns_domain", "") ?: ""
        val token = prefs.getString("ddns_token", "") ?: ""
        val sub = prefs.getString("ddns_sub", "").let { if (it.isNullOrEmpty()) "@" else it }

        if (domain.isEmpty() || token.isEmpty()) {
            updateStatus("未配置域名或 Token，等待填写")
            return
        }

        val report = StringBuilder()
        var anyUpdated = false

        // ---- IPv4 ----
        val v4 = fetchIp("https://4.ipw.cn") ?: fetchIp("https://api.ipify.org")
        if (!v4.isNullOrEmpty() && v4.contains(".") && !v4.contains(":")) {
            if (v4 != lastV4) {
                val ok = updateRecord(domain, sub, "A", v4, token)
                if (ok) {
                    lastV4 = v4
                    prefs.edit().putString("ddns_last_v4", v4).apply()
                    report.append("IPv4 已更新: $v4\n")
                    anyUpdated = true
                } else {
                    report.append("IPv4 更新失败\n")
                }
            } else {
                report.append("IPv4 未变化: $v4\n")
            }
        } else {
            report.append("IPv4 获取失败\n")
        }

        // ---- IPv6 ----
        val v6 = fetchIp("https://6.ipw.cn") ?: fetchIp("https://api64.ipify.org")
        if (!v6.isNullOrEmpty() && v6.contains(":")) {
            if (v6 != lastV6) {
                val ok = updateRecord(domain, sub, "AAAA", v6, token)
                if (ok) {
                    lastV6 = v6
                    prefs.edit().putString("ddns_last_v6", v6).apply()
                    report.append("IPv6 已更新: $v6\n")
                    anyUpdated = true
                } else {
                    report.append("IPv6 更新失败\n")
                }
            } else {
                report.append("IPv6 未变化: $v6\n")
            }
        } else {
            report.append("IPv6 获取失败（无 IPv6 网络时正常）\n")
        }

        updateStatus(if (anyUpdated) report.toString().trim() else "解析已是最新\n" + report.toString().trim())
    }

    /** 获取公网 IP 地址（带超时） */
    private fun fetchIp(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "LocalWebServer/1.0")
            val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (text.isNotEmpty() && text.length < 120 && !text.contains("<")) text else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 通过 DNSPod API 更新一条记录：
     *  1) Record.List 查记录 ID（按 sub_domain + record_type 过滤）
     *  2) 存在 → Record.Ddns 更新；不存在 → Record.Create 创建
     */
    private fun updateRecord(domain: String, sub: String, type: String, value: String, token: String): Boolean {
        return try {
            val listJson = JSONObject(httpPost("https://dnsapi.cn/Record.List", mapOf(
                "login_token" to token,
                "format" to "json",
                "domain" to domain,
                "sub_domain" to sub,
                "record_type" to type
            )))
            val statusObj = listJson.optJSONObject("status")
            if (statusObj?.optString("code") != "1") {
                updateStatus("DNSPod 查询失败: " + (statusObj?.optString("message") ?: "未知错误"))
                return false
            }

            val records = listJson.optJSONArray("records")
            if (records == null || records.length() == 0) {
                // 记录不存在，尝试创建
                val createJson = JSONObject(httpPost("https://dnsapi.cn/Record.Create", mapOf(
                    "login_token" to token,
                    "format" to "json",
                    "domain" to domain,
                    "sub_domain" to sub,
                    "record_type" to type,
                    "record_line" to "默认",
                    "value" to value
                )))
                val cStatus = createJson.optJSONObject("status")
                if (cStatus?.optString("code") != "1") {
                    updateStatus("DNSPod 创建失败: " + (cStatus?.optString("message") ?: "未知错误"))
                    return false
                }
                return true
            }

            val recordObj = records.getJSONObject(0)
            val recordId = recordObj.optString("id")
            val line = recordObj.optString("line", "默认").let { if (it.isEmpty()) "默认" else it }

            val ddnsJson = JSONObject(httpPost("https://dnsapi.cn/Record.Ddns", mapOf(
                "login_token" to token,
                "format" to "json",
                "domain" to domain,
                "record_id" to recordId,
                "record_line" to line,
                "value" to value
            )))
            val dStatus = ddnsJson.optJSONObject("status")
            if (dStatus?.optString("code") != "1") {
                updateStatus("DNSPod 更新失败: " + (dStatus?.optString("message") ?: "未知错误"))
                return false
            }
            true
        } catch (e: Exception) {
            updateStatus("DNSPod 请求异常: ${e.message}")
            false
        }
    }

    private fun httpPost(urlStr: String, params: Map<String, String>): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun updateStatus(s: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        val full = "[$time] $s"
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("ddns_status", full).apply()
        sendBroadcast(Intent(ACTION_DDNS).putExtra(EXTRA_STATUS, full))
        sendBroadcast(Intent(WebServerService.ACTION_LOG).putExtra("message", "[DDNS] $s"))
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(s.replace("\n", " ").take(60)))
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("ddns", "域名绑定 DDNS", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "DDNS 自动解析状态"
                })
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "ddns")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("域名绑定（DDNS）")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        loopThread?.interrupt()
        loopThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}