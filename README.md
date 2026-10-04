# DSH-android-client

DSH 安卓客户端 —— 把 DSH 管理面板变成手机上「像原生 App」的全屏体验。

## 简介

**DSH（DeepSeek Harness）** 是一个 Web 管理面板。本项目把 DSH 面板包装成安卓原生 App：

| 组件 | 说明 |
|---|---|
| **安卓壳**（`android-shell/`）| 无浏览器 UI 的 WebView 壳，全屏显示 DSH 面板；含加载页、图片压缩、拍照上传、远程调试通道 |
| **远程调试**（`phone-relay/`）| 手机出站连主机中继，Shizuku 以 shell 身份执行命令，等效「远程 adb」——不插 USB 线 |

## 架构

```
手机 App ──HTTPS──> DSH 面板（公网 443，nginx 反代 + Basic Auth）
手机 App ──HTTPS──> phone-relay（nginx 反代 + TLS + token 鉴权）
```

## 功能

- **无浏览器 UI**：没有地址栏、标签页、菜单，纯全屏
- **加载页**：进度环 + 阶段文案 + 页面自曝处境（资源数/文字数/JS 报错），失败时给可操作提示
- **同步气泡**：切回前台时有数据在转就弹「正在同步」，传完变「同步完成」快速消失
- **图片压缩**：上传前压到 2048 长边 + 无 EXIF，命中服务端直通判据（不重编码）
- **拍照/选图/下载**：FileProvider + RFC 5987 文件名解析
- **私有 scheme** `dshapp://open`：外部拉起 App
- **设置面板**：桌面长按图标进入（压缩开关、运行环境、调试通道）
- **崩溃自显屏**：启动异常直接画堆栈在屏幕上
- **远程调试**：不插 USB 线，主机端执行 shell 命令（需 Shizuku）
- **minSdk 24**（Android 7.0+），纯 Java 无原生库

## 获取 APK

从 [Releases](../../releases) 下载最新 APK 安装即可。

## 从源码构建

```bash
# 依赖
# - JDK 17（JAVA_HOME）
# - Android SDK（ANDROID_SDK_ROOT，需 build-tools 35.0.0 + platform android-35）
# - Python 3（跑自测）

export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export KS_PASS=your-keystore-password   # 签名口令，不设则生成新密钥

cd android-shell
bash build.sh
```

构建流程自动运行 **7 组自测**（纯 Java 逻辑 + 结构自检 + 递归检查）+ 版本号硬校验 + 签名指纹校验，全部通过才出包。

输出：`android-shell/dist/DSH-x.y.apk`

## 远程调试

主机跑 `phone-relay/relay.py`（token 鉴权），手机端在壳的设置面板填地址 + token + 开开关（需 Shizuku）。

详见 [phone-relay/](phone-relay/)。

## 目录结构

```
├── android-shell/          安卓壳源码 + 构建脚本 + 自测
│   ├── app/java/app/dsh/shell/   Java 源码（15 个文件）
│   ├── tools/selftest/           8 组自测
│   ├── libs/                     Shizuku 库（Apache 2.0）
│   └── build.sh                  构建 + 自测 + 签名
├── phone-relay/            远程调试中继（主机端 + CLI + 测试工具）
├── LICENSE                 GPL-2.0
└── README.md
```

## License

GPL-2.0
