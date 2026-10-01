# Android 本地静态网页托管服务

在 Android 手机内启动本地 HTTP 服务，通过局域网浏览器访问 HTML、CSS、JavaScript、图片等静态资源。纯本地运行，不依赖 AI 或云端服务。

## 当前版本

- 一键启动/停止 HTTP 服务，默认端口 8080
- 锁屏后通过前台服务保持运行
- 无 `index.html` 时自动列出目录文件
- 支持 HTML、CSS、JS、JSON、PNG、JPG、GIF、ICO、SVG 等
- 显示手机局域网 IP 和访问地址
- 记录 200/404 请求日志
- 默认站点目录：应用私有目录 `files/sites/default`

## 构建

```bash
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

> 文件导入、多站点切换 UI 将在下一迭代加入；当前服务核心已可运行。
