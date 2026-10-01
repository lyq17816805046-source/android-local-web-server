package com.lyq.localwebserver

import android.Manifest
import android.content.*
import android.os.*
import android.content.pm.PackageManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView; private lateinit var address: TextView; private lateinit var log: TextView; private lateinit var toggle: Button; private lateinit var port: EditText
    private val receiver = object: BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { if (i?.action == WebServerService.ACTION_LOG) append(i.getStringExtra("message") ?: "") } }
    override fun onCreate(b: Bundle?) { super.onCreate(b); setContentView(R.layout.activity_main)
        status=findViewById(R.id.status); address=findViewById(R.id.address); log=findViewById(R.id.log); toggle=findViewById(R.id.toggle); port=findViewById(R.id.port)
        registerReceiver(receiver, IntentFilter(WebServerService.ACTION_LOG), RECEIVER_NOT_EXPORTED)
        toggle.setOnClickListener { if (WebServerService.running) stopService(Intent(this, WebServerService::class.java)) else start() }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        update(false)
    }
    private fun start() { val p=port.text.toString().toIntOrNull() ?: 8080; if (p !in 1024..65535) { Toast.makeText(this,"端口范围 1024-65535",Toast.LENGTH_SHORT).show(); return }; startForegroundService(Intent(this,WebServerService::class.java).putExtra("port",p)); update(true) }
    private fun update(on:Boolean) { toggle.text=if(on) "停止服务" else "启动服务"; status.text=if(on) "服务运行中（端口 ${port.text}）" else "服务未启动"; address.text=if(on) "局域网地址：http://${WebServerService.localIp()}:${port.text}" else "局域网地址：—" }
    private fun append(s:String) { log.append(s+"\n"); log.post { (log.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) } }
    override fun onDestroy(){ unregisterReceiver(receiver); super.onDestroy() }
}
