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
    companion object { const val ACTION_LOG="com.lyq.localwebserver.LOG"; var running=false; private var server: ServerSocket?=null; private var port=8080
        fun localIp():String { try { NetworkInterface.getNetworkInterfaces().toList().flatMap{it.inetAddresses.toList()}.firstOrNull{!it.isLoopbackAddress && it is Inet4Address}?.let{return it.hostAddress ?: "127.0.0.1"} } catch(_:Throwable){}; return "127.0.0.1" }
    }
    private lateinit var root: File
    override fun onCreate(){super.onCreate(); root=File(filesDir,"sites/default").apply{mkdirs()}; notifyChannel(); startForeground(7, notification()); broadcast("服务初始化") }
    override fun onStartCommand(i:Intent?, flags:Int, id:Int):Int { port=i?.getIntExtra("port",8080)?:8080; if(!running) launch(); return START_STICKY }
    private fun launch(){ try { server=ServerSocket(port); running=true; broadcast("HTTP 服务已启动：http://${localIp()}:$port") } catch(e:Exception){broadcast("启动失败：${e.message}"); stopSelf(); return}; thread(name="http-server"){ while(running){try{val s=server?.accept(); if(s!=null) thread{handle(s)}}catch(_:Throwable){}}} }
    private fun handle(s:Socket){ s.use { val input=BufferedReader(InputStreamReader(it.getInputStream())); val first=input.readLine() ?: return; while(input.readLine()?.isNotEmpty()==true){}; val parts=first.split(" "); val raw=if(parts.size>1) parts[1] else "/"; val path=URLDecoder.decode(raw.substringBefore('?'),"UTF-8").removePrefix("/"); val safe=File(root,path).canonicalFile; val base=root.canonicalFile; if(safe.path != base.path && !safe.path.startsWith(base.path + File.separator)){ broadcast("400  非法路径"); return }; val target=if(safe.isDirectory) File(safe,"index.html") else safe; val code=if(target.exists() && target.isFile) 200 else 404; val body=if(code==200) target.readBytes() else if(safe.isDirectory) directory(safe,path).toByteArray() else "<h1>404 Not Found</h1>".toByteArray(); val type=mime(target.name); val out=it.getOutputStream(); out.write("HTTP/1.1 $code ${if(code==200)"OK" else "Not Found"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush(); broadcast("${code}  ${raw}") } }
    private fun directory(dir:File,path:String)=buildString { append("<meta charset='utf-8'><h2>目录：/$path</h2><ul>"); dir.listFiles()?.sortedBy{it.name}?.forEach{ append("<li><a href='/${if(path.isEmpty())"" else "$path/"}${URLEncoder.encode(it.name,"UTF-8")}' >${it.name}${if(it.isDirectory)"/" else ""}</a></li>")}; append("</ul>") }
    private fun mime(n:String)=when(n.substringAfterLast('.',"").lowercase()){"html","htm"->"text/html; charset=utf-8";"css"->"text/css";"js"->"text/javascript";"json"->"application/json";"png"->"image/png";"jpg","jpeg"->"image/jpeg";"gif"->"image/gif";"ico"->"image/x-icon";"svg"->"image/svg+xml";else->"application/octet-stream"}
    private fun broadcast(s:String){ val t=SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(Date()); sendBroadcast(Intent(ACTION_LOG).putExtra("message","[$t] $s")) }
    private fun notifyChannel(){ val nm=getSystemService(NotificationManager::class.java); if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel("web","网页服务",NotificationManager.IMPORTANCE_LOW)) }
    private fun notification()=NotificationCompat.Builder(this,"web").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("本地网页托管").setContentText("HTTP 服务运行中").setOngoing(true).build()
    override fun onDestroy(){running=false; try{server?.close()}catch(_:Throwable){}; server=null; broadcast("HTTP 服务已停止"); super.onDestroy()}
    override fun onBind(i:Intent?):IBinder?=null
}
