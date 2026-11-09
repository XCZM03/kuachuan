# 跨传 · 安卓跨品牌互传

一个不挑手机品牌、不依赖厂商账号、不经过任何服务器的局域网互传工具。支持互传 APK 安装包、已安装应用，以及图片 / 视频 / 文档等任意文件。

- 包名：`com.crossshare.app`
- 版本：1.0
- 支持：Android 8.0（API 26）及以上
- 签名：自签名调试证书，可直接侧载安装

## 怎么用

1. 两台手机连到同一个 Wi‑Fi；**没有路由器时**，让 A 开个人热点、B 连上 A 的热点即可（首页有「去开热点」入口）。
2. A 打开「我要接收」→ 点「开始接收」，记下页面上的 IP（例如 `192.168.43.1`），也可以点「复制 IP」。
3. B 打开「我要发送」：
   - 同一 Wi‑Fi 下通常会自动列出附近的接收端，点一下即自动填 IP；
   - 搜不到就手动输入 A 的 IP（自动发现失效不影响传输）。
4. B 选择文件：「选择任意文件」走系统文件选择器；「已装应用（APK）」可直接把某个已装应用的安装包发给对方。
5. 点「发送」。文件保存在 A 的 `下载/CrossShare` 目录；收到 APK 时，通知栏和接收页都有「安装」按钮。

## 实现原理

- 传输层：接收端起 `ServerSocket` 监听 TCP `8765`，自定义极简协议（魔数 / 文件名 / 大小 / MIME / 原始字节 / 回执），发送端按百分比回传进度。
- 设备发现：`NsdManager` 注册 / 发现 `_crossshare._tcp.`（mDNS），配合 `WifiManager.MulticastLock`；失败时始终可以手填 IP 兜底。
- 接收落盘：Android 10+ 走 `MediaStore.Downloads` 写入公共 `下载/CrossShare`；Android 8/9 写公共目录并通过自带 `FilesProvider` 以 `content://` 交给系统安装器。
- 后台保活：`ReceiveService` 为 `dataSync` 类型前台服务 + 通知渠道，符合 Android 14 的前台服务类型要求。
- 权限：声明 `QUERY_ALL_PACKAGES` 才能列出全部已装应用；`REQUEST_INSTALL_PACKAGES` 用于收完 APK 后一键安装（需在系统设置里允许「安装未知应用」）。

## 已知限制

- 两台设备必须互相可达：连同一 Wi‑Fi，或一台开热点另一台连上。部分路由器开启「AP 隔离 / 访客网络」会阻断互访，此时请用热点方案。
- 个别国产 ROM 的 mDNS 广播不稳定，自动发现可能搜不到设备，**手动输入 IP 一定可用**。
- 全程不耗移动数据，但也不支持跨互联网远程传输。
- 传输未加密，仅建议在自己可信的局域网内使用。

## 工程结构

```
AndroidManifest.xml
res/                 原生资源（无 AndroidX / 无第三方库）
src/com/crossshare/app/
  MainActivity.java     首页与权限引导
  SendActivity.java     发现设备、选文件 / 选应用、发送
  ReceiveActivity.java  显示 IP、开关接收、安装 APK
  ReceiveService.java   前台服务、Socket 监听、NSD 广播
  Io.java               传输协议、落盘、工具方法
  Hub.java              页面与服务共享状态
  FilesProvider.java    旧系统 APK 安装的 content:// 桥
  AppEntry / DeviceEntry
```

工程只用 `android.*` 原生 API 和 Java 8，不依赖 AndroidX、Gradle 插件或任何第三方库。用 Android Studio 新建一个 Empty Activity 项目后，把 `src`、`res`、`AndroidManifest.xml` 覆盖进去即可直接构建；也可用 `aapt2 + javac/ecj + d8 + zipalign + apksigner` 手工构建（本目录同级提供了构建脚本思路）。
