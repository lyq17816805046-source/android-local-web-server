# 本地网页托管 (Android Local Web Server)

在 Android 手机内托管静态网站，通过局域网访问。纯本地运行，不依赖 AI 或云端。

## 功能特性

- 一键启停 HTTP 服务，自定义端口（默认 8080）
- 前台服务，锁屏后持续运行
- 多站点隔离：每个站点独立目录，切换站点即切换网站根目录
- 显示本机局域网 IP，其他设备同 WiFi 直接访问
- 静态资源支持：HTML、CSS、JS、PNG、JPG、GIF、ICO、SVG、JSON 等
- 最简单的目录浏览：无 index.html 时列出文件夹文件
- 访问日志：记录 200/404/400 请求状态码
- 路径穿越防护

### 文件导入方式

| 方式 | 说明 |
|:---|:---|
| 导入文件 | 单个/多个文件平铺导入到站点根目录 |
| 导入文件夹 | 保留子目录结构，递归复制到站点 |
| 导入 ZIP | 一键导入完整站点（推荐），见下方说明 |

## 导入 ZIP 站点包格式

ZIP 包内可包含一个 `site.json` 配置文件（可选）+ 网页文件（可含子目录）。

### site.json 结构

```json
{
  "name": "我的网站",
  "site": "demo",
  "entry": "index.html"
}
```

| 字段 | 必填 | 说明 |
|:---|:---|:---|
| `name` | 否 | 站点显示名称 |
| `site` | 否 | 站点目录名（决定存储到 `sites/<site>/`，不含此字段时导入到当前站点） |
| `entry` | 否 | 默认入口文件（默认 index.html） |

### ZIP 文件结构示例

```text
demo-site.zip
├── site.json
├── index.html
├── about.html
├── contact.html
├── css/
│   └── style.css
└── blog/
    ├── index.html
    ├── post1.html
    └── post2.html
```

导入规则：

1. 先读取 `site.json`（若存在），确定目标站点目录；
2. 再按 ZIP 内条目顺序解压网页文件（保持子目录结构）；
3. `site.json` 本身不写入站点目录；
4. 含 `..` 或绝对路径的条目会被跳过（防路径穿越）。

## 页面跳转（站内多 HTML）

站点内多个 HTML 通过相对路径互相链接：

```html
<a href="about.html">关于</a>
<a href="blog/post1.html">文章</a>
<a href="../index.html">返回首页</a>
```

浏览器访问：

```text
http://<手机IP>:8080/                    → 首页 index.html
http://<手机IP>:8080/about.html          → 关于页
http://<手机IP>:8080/blog/post1.html     → 博客文章
http://<手机IP>:8080/blog/               → 子目录（无 index.html 时列目录）
```

## 构建

```bash
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 发布版本

| 版本 | 说明 |
|:---|:---|
| v1.0.0 | 基础 HTTP 服务 |
| v2.0.0 | Material Design 3 界面 |
| v3.0.0 | 文件导入 + 多站点 |
| v4.0.0 | 多站点切换修复、目录美化 |

## 许可证

MIT License