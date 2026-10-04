# DSH 安卓壳（APK）

把 DSH Web UI 包成一个纯粹的安卓应用：打开即整屏 DSH，**没有 ActionBar、没有标题栏、没有底部菜单栏、没有自带导航按钮**。

- 产物：`dist/DSH-2.0.apk`（约 61 KB，minSdk 24 / targetSdk 35，v2+v3 签名）
- 包名：`app.dsh.shell`
- **不内置任何地址**：首次进入让用户手填，之后记住。同一个 APK 可以给任何自建 DSH 的人用。

## 构建

不依赖 Gradle（这台机器 内存有限，Gradle 太奢侈），直接 aapt2 + javac + d8 + zipalign + apksigner：

```bash
bash build.sh          # 内部会先跑纯 Java 自测，再出 dist/DSH-1.0.apk
```

依赖（都已落在磁盘上）：

| 组件 | 位置 |
| --- | --- |
| JDK 17 | `$JAVA_HOME`（需 JDK 17）|
| Android SDK | `$ANDROID_SDK_ROOT`（build-tools 35.0.0 / platform android-35）|
| 签名密钥 | `keystore/dsh.keystore`（口令从 `KS_PASS` 环境变量读取（自制自签），与 Play 无关） |

## 行为

| 场景 | 行为 |
| --- | --- |
| 首次启动 | 弹框要求填写 DSH 入口地址；输入不合法会带着原输入重新弹，不会停在白屏 |
| 后续启动 | 直接加载已记住的地址 |
| 填 http | 允许（内网自建场景真实存在）但会先弹警告：DSH 在非安全上下文下功能受限 |
| 认证 | nginx Basic Auth → WebView 原生认证弹窗。勾「记住」后写入应用私有 SharedPreferences，下次自动登录 |
| 返回键 | 有历史 → 后退；无历史 → 2 秒内再按一次退出 |
| 长按返回键 | 弹出菜单：重新加载 / 输入地址 / 清除账号密码（三键导航的手机适用） |
| **长按桌面图标** | 四个快捷方式：重新加载 / 设置 / 设置地址 / 清除账号（手势导航手机的主入口） |
| 文件上传 | 接管 `onShowFileChooser`：`GET_CONTENT` + 多选 + 相机拍照入口（`CaptureProvider`） |
| 上传前压缩 | 默认开启：缩到 DSH 会「原样放行」的尺寸（见下），在「设置」面板里可关 |
| 对话框 | 同一时刻只留一个卡片；新卡片弹出前先收掉上面那个 |
| 返回键 | 有卡片打开时先关卡片，不拿去后退网页 |
| 内部跳转 | 同**注册域**（末两段）都留在应用内 —— DSH 的同域服务是同域兄弟子域，比 host 会被误判成外部链接 |
| 外部链接 | 真正的外部站点才交给系统浏览器；`mailto:`/`tel:`/`intent:` 等非 http scheme 也交系统 |
| 新窗口链接 | `target="_blank"` / `window.open` 用临时 WebView 接住，同站本窗打开、异站交系统（否则会被静默吞掉） |
| 下载 | 交系统 DownloadManager（**不是**跳浏览器），并把 WebView 的 Cookie 转过去，否则同域服务会 401 |
| 下载文件名 | 自己解析 `Content-Disposition`（含 RFC 5987 的 `filename*`），见 `Filenames.java` |
| 页面底色 | 注入 MutationObserver 把 `body` 底色回传原生，同步系统栏图标明暗，深色主题下不会白边 |

## 长截图 / 滚动截图：**做不了**（已查死，别再试）

结论（2026-09-25 实测）：**这个壳里做不到，换什么机器都一样**，原因是页面结构而非设备能力。

平台从 Android 12L 起原生支持 WebView 的 scroll capture，本壳也显式声明了
`setScrollCaptureHint(SCROLL_CAPTURE_HINT_INCLUDE)`，但实测：

| 实测项 | 结果 |
| --- | --- |
| `可截目标`（走 decorView 的 `onScrollCaptureSearch`） | **0**（hint=INCLUDE 也没用）|
| 文档可滚 | **0px** |
| 全场最大 `scrollHeight` | **= 视口高**（即没有任何元素有内容溢出）|
| 有内容溢出的元素 | **0** |
| 对链上 `overflow:auto` 的元素试 `scrollTop += 200` | **无变化** |

⇒ DSH 页面是**虚拟滚动**（内容用绝对定位/transform，不贡献布局高度），
**页面里没有任何原生可滚的抓手**。所以：
- 系统的 scroll capture 找不到目标；
- 自己实现 `ScrollCaptureCallback` 也没有抓手（该回调要求「把指定区域渲染到 Surface」，
  而我们既滚不动文档、也滚不动任何内部容器）。

唯一可行的路是「模拟手势逐屏滚动 + 拼接」（等于自写长截图引擎，还要处理吸底输入框/左侧栏
被重复截进每屏、虚拟列表可能空白），**投入产出比不划算，已放弃**。
替代方案：第三方手势型长截图 App，或在电脑浏览器里截。

⚠️ 设置面板里**只留「运行环境」**（Android + WebView 版本），不再显示截图能力诊断
（那个查死了，摆着只会误导）。`setScrollCaptureHint` 那行**保留**（声明该声明的），
但注释里写明了"对本页无效、别再重新排查"。

## 切回前台时的「正在同步」胶囊（v2.2）

从后台切回 DSH App 时，前端要重连 + 重拉会话历史，这几秒页面是停住的（主线程在同步重渲染），
看起来像服务器死了。所以加了个小胶囊提示。

**⚠️ 必须由原生 View 画，不能在注入脚本里画**：卡住的正是 WebView 主线程，
网页里画的转圈会跟着一起卡死，等于没提示。`SyncView` 是独立渲染，照样转。

行为（v2.5 起改为**数据驱动**，`THEME_SCRIPT` ⑤ 的 `__dshWatchNet`）：

| 页面观测到 | 壳做什么 |
| --- | --- |
| 有流量（`netAt` 前进） | `DshBridge.sync(seq,"busy")` → 显示「正在同步…」+ 转圈 |
| 之后静默 500ms | `sync(seq,"done")` → 切成「同步完成」（停圈，绿色），停 700ms 后淡出 |
| 自始至终无流量 | **什么都不报**，气泡根本不出现 |
| 切后台 / 15s 兜底 | 直接收起 |

⚠️ **别再做成"先无条件弹出来再判断"**：v2.4 及以前是 onResume 后延迟 300ms 先显示、
再等页面回调收起，结果**锁屏解锁这种完全没流量的场景也会弹至少 1.5 秒**（用户实报：
「每次锁屏再打开都会弹一次」）。现在气泡的出现*只*由「真的在传数据」触发。

⚠️ **静默 500ms 就宣布完成，是从「数据传完」到「内容画出来」那段（约 1s）没有信号可测**
的折中：靠「同步完成」这四个字多停 700ms 把视觉空档盖过去。大会话可能仍略早，小会话也不至于
白等 —— 比 v2.4 的固定 1500ms 硬等更贴近实际（那个还会在没流量时误弹）。

⚠️ **气泡位置必须跟随 insets**：它挂在全屏的 `root` 上（insets 只给了 WebView），
不自己让位就会压在状态栏/摄像头挖孔上（前置摄像头在顶部正中，实测被压住）。
所以 `applyEdgeToEdge()` 的 insets 回调里会把它下移到 `top + 12dp`（`top` 已含 displayCutout）。

**⚠️ 不要额外去"检测定时器迟到"**：主线程真被占住时，回调本身就跑不了，天然会等到阻塞结束；
反而在系统钳制定时器（无头浏览器实测被钳到 1s）时会永远判不出空闲。这点绕了一圈才想明白。

实测（`tools/selftest/watch-idle.mjs`，真浏览器里同步阻塞主线程 2 秒）：
阻塞期间不提前报空闲、结束后 2242ms 才报；本来就空闲则 150ms 报。**5/5 通过**。

## 被外部拉起的私有 scheme（v2.1）

APK 注册了 `dshapp://open`（见 `AndroidManifest.xml`），用来被**通知**拉回前台：

- **不要**去抢 `http`/`https`：那样本 App 会变成浏览器、抢走所有链接。
- `launchMode="singleTask"` ⇒ 走 `onNewIntent`，`handleIntent` 的 `default` 分支什么都不做
  ⇒ 只是回到前台，**不会重载页面**（继续上次的会话）。
- 外部应用可通过 `dshapp://open` 拉起本 App。

## 加载页：为什么要有

用户反馈「有时候打开就白屏」。实际原因是首屏要拉约 13.7MB（见下），
这段时间**什么都不显示**会让人以为 App 死了。所以加载页必须做到：

1. **有东西在动**（旋转环）——证明进程没死
2. **秒数一直在跳**——数字停住就是真卡了，这是判断"卡死"最直接的信号
3. **阶段文案**（连接 → 加载资源 N% → 渲染）——知道卡在哪一步

⚠️ **收起时机不能只看 `onPageFinished`**：那只是 HTML 到了，SPA 还要拉 12MB
插件包再渲染首屏。所以由注入脚本检测「真正出现可见内容」后回调 `DshBridge.ready()`；
另留 12s 兜底强制收起，避免页面结构变了导致加载页永远挡着。

⚠️ **「元素可见」≠「可以收起了」**：实测首屏在 `DOMContentLoaded` 之后还有一串长任务
（最大 ~600ms，React 提交）。若加载页在这串长任务中间消失，用户看到的是
「页面出来了但僵住不动」。所以 `ready()` 是两段门：**元素可见 → `requestIdleCallback`
（主线程空了才回调）→ 双 rAF**，再加 900ms 硬上限兜底。实测无长任务时只比旧版多 ~20ms。

⚠️ **就绪判据必须对「任意站点」成立，不能只对 DSH 成立**。这壳是通用壳（地址由用户手填），
实测把地址填成 同域服务 时，早期判据永远不成立 ⇒ 每次都拖到 12s 兜底才收。
原因是 同域服务 的整棵应用挂在 **body 下一个 div** 里，那个 div 高 1887px 但
**子元素只有 1 个**，而当时我卡的是 `children >= 2`。现在的判据：

| 信号 | 条件 |
| --- | --- |
| 输入类（`[class*=composer]` / `textarea` / `contenteditable`） | 可见高度 ≥ 8px |
| **`<body>` 的每个直接子元素**（通用兜底，任何站点都成立） | 高度 ≥ 120px **且** 子元素 ≥ 1 |
| 兜底自检 | 已渲染正文（`innerText`）≥ 20 字 |
| 应用根容器（DSH 的 `#root` 空壳） | 高度 600px 但子元素 0 ⇒ **不**算就绪 |

实测：同域服务 2156ms 就绪（改前 12s 兜底）、DSH 1358ms、把页面 JS 全部屏蔽后
**不触发**（不会误判成渲染完成）。

⚠️ **阈值别按桌面写**。手机上输入框/工具条可能只有几十像素高，早期版本卡
`height > 80` 会让 `ready()` 永不回调，加载页一路拖到 12s 兜底才强收。

⚠️ **容器必须有子元素**。`#root` 在应用还没挂载时是个 **600px 高、0 子元素**的空壳
（`height:100%` 撑起来的）—— 只看高度会把它当成"渲染完成"，加载页提前收起、露出白屏。

## 加载页卡在「正在渲染 Ns」怎么查

这一段**纯粹在等页面自己**（网络进度已 100%，壳没在下载），所以秒数大只有两种可能：
页面真的还在算（首屏 12.3MB 插件包在手机 CPU 上解析 + React 首次提交），
或者页面在 WebView 里报错卡死了。

手机没有 adb，所以注入脚本会**每秒自检一次并把页面处境报给壳**，显示在加载页第三行：

```
资源12 正文680字             ← 一切正常，马上就好
资源44 正文0字               ← 还在下载（资源数在涨）
资源16 正文0字 ⚠TypeError: …  ← 页面 JS 报错，看报错内容
```

判读方法：`资源` 数在涨 = 还在下载；不涨且 `正文` 为 0 字 = 页面根本没渲染出来；
出现 `⚠` = 页面抛了运行时错误。

⚠️ **`正文` 必须用 `innerText`（已渲染的文字），不能用 `textContent`**：实测页面一个像素都没画出来时
`textContent` 仍有 516 字（`<script>`/`<noscript>` 的内容也算在内），拿它当判据会骗人。
`innerText` 会触发布局，所以只在未就绪时每秒读一次、最多 12 次。

这条自检同时也是**最后一道就绪判据**：DOM 结构千奇百怪，看不懂结构时只要页面出现了
≥20 字的成句正文，就认为渲染出来了。

## 首屏体积与缓存（实测）

| 资源 | 体积 | Cache-Control |
| --- | --- | --- |
| 插件合并包（58 个 client.js，含 6.7MB 的 PDF.js） | 12.3 MB | `max-age=31536000, immutable` ✅ |
| 核心 JS + CSS | 1.4 MB | **无**（无 ETag / Last-Modified）❌ |

⇒ 第二次打开能复用 12.3MB，但仍要重下 1.4MB。另：插件包的 `rev` 是**全局**指纹，
任一插件更新会让整个 12.3MB 缓存失效。

## 上传前压缩：为什么是 2048 长边

「合规 = 服务端一次重编码都不跑」的阈值（读源码 + **用 DSH 自己的
`canPassThroughNormalization` 实测**，不是推断）：

| 条件 | 阈值 |
| --- | --- |
| 宽×高 | ≤ **4,194,304**（= 2048×2048，取等号也放行） |
| 单边 | ≤ 8192 |
| 编码字节 | ≤ **1MB**（发请求侧更严；附件侧是 4MB） |
| 其他 | 非 GIF/动图、8-bit sRGB、**不能带 EXIF** |

所以压到 **2048 长边 + q85**（实测真实照片 ~400-500KB）。三条理由：

1. 相机原图 4080×3060 = 12.5MP、3~6MB，移动网上传要好几秒 —— 压完只 ~450KB。
2. 尺寸合规 ⇒ 服务端**直接放行**，省掉 1250 万像素解码 + 三级重编码（低配 VM 上实测 482ms）。
3. **模型根本用不上那么大**：DeepSeek 那条链路 384 token 预算 ⇒ 等效仅约 823×823。
   实测把 2048 和 1024 两张图分别喂给模型，**读出的最小字号完全相同（Z9）**。
   ⇒ 压到 2048 是**零画质损失**的纯提速。

⚠️ **EXIF 是个容易漏的点**：相机照片默认带 EXIF（方向/GPS/机型），而带元数据的图
**即使尺寸合规也会被重编码**。canvas/Bitmap 重绘后导出的 JPEG 天然无 EXIF，所以走重绘路径就自动满足。

## 踩过的坑（改之前先看）

1. **DSH 需要安全上下文（https）**。`http://<host>:3081` 在手机上会被判非安全上下文，前端功能受限。壳不禁止 http（内网自建真实存在），但会明确警告。
1b. **下载必须转 Cookie**。`DownloadManager` 是独立进程，拿不到 WebView 的 Cookie，同域服务/需要鉴权的下载会 401。必须 `CookieManager.getCookie(url)` 再 `addRequestHeader`。
1c. **`onDownloadStart` 不在 `WebChromeClient` 上**，要用 `web.setDownloadListener(...)`；写成 override 会编译不过。
1d. **manifest 里的 `versionCode`/`versionName` 会压过 aapt2 的 `--version-code` 参数**（实测 2.19）⇒ 版本号只写在 `build.sh`，并由构建脚本在末尾硬校验，否则会出现「改了版本号、装出来还是旧的」。
1e. **XML 注释里不能出现 `--`**，我写 `--version-code` 导致 `aapt2 link` 直接 `not well-formed`。
1f. **别用 `URLUtil.guessFileName` 推下载文件名**。它只认 `filename=`，**不认 RFC 5987 的 `filename*=utf-8''...`**；同域服务 恰好只发后者且 `Content-Type` 恒为 `application/octet-stream`，于是解析不到名字就按 MIME 猜，而 octet-stream 在 Android 的映射表里正是 `bin` ⇒ **所有下载文件都变成 .bin**（用户实测报的 bug）。用 `Filenames.resolve()`，并注意 `filename*` 按 RFC 6266 优先于 `filename`。
1h. **别在 manifest 里写 `${applicationId}`**（那是 Gradle 占位符）。aapt2 原样写进包，provider 的 authority 永远匹配不上，而且不报错。build.sh 现在会校验 authority == 包名 + `.fileprovider`。
1i. **provider 是 `exported="false"` 时，外部应用（相机）默认没权限**：必须给 Intent 加 `FLAG_GRANT_WRITE_URI_PERMISSION`，并且**把 Uri 放进 chooser 的 clipData**，权限才会随 chooser 传播。少这一步的表现是拍照存不进去。
1l. **压缩必须离开主线程**：12MP 解码 + 多轮编码要几百毫秒~数秒，放主线程会把界面卡死（比不压还糟）。已放到 `dsh-compress` 线程，完成后 `runOnUiThread` 回传。
1m. **每次试探编码都会落一个文件**：质量/尺寸阶梯最坏要试 8 次 ⇒ cache 里会攒下几个 MB 的废文件。必须记录所有候选、只留胜出的那个（`encodeWithinLimit` 里做）。
1n. **12MP 直接解码要 48MB 内存**：必须两段式（先 `inJustDecodeBounds` 读尺寸，再按 `inSampleSize` 降采样解码），否则低端机会 OOM。
1o. **PNG 截图不能盲转 JPEG**：文字会出现压缩噪点。优先保 PNG，实在超 1MB 才转 JPEG。
1s. **限制是「总像素 ≤ 2048×2048」不是「长边 ≤ 2048」**：4:3 照片缩完长边可以是 2364（2364×1773=4,191,372 ≤ 4,194,304），16:9 能到 2730，只有正方形才恰好 2048。按"长边 2048"卡会白白损失 25% 分辨率。
1q. ⚠️ **ContentProvider 的 `resolve()` 里绝不能加 `isFile()` 检查**：相机拍照是「拿到 Uri → 新建文件 → 写入」，调用 openFile 那一刻文件**还不存在**。加了存在性检查 ⇒ 相机写不进去 ⇒ **拍完等于没拍（图不显示）**；而选图上传正常（文件本来就在），所以现象很迷惑。存在性只该在**读**模式检查。真机踩过。
1r. **清理临时文件要留宽限期**：WebView 是**异步**读取 Uri 内容的，刚产出就删会导致「选了图传不上去」（多选尤其明显）。trim 需跳过最近 2 分钟内动过的文件。
1p. ⚠️ **「长按返回键」不能作为唯一入口**：手势导航的手机没有返回键，塞在那个菜单里的开关用户永远打不开（真实踩过：压缩开关加了却"看不到"）。所以功能入口必须同时挂到**桌面快捷方式**（`res/xml/shortcuts.xml`）上。
1k. **`setSupportMultipleWindows(false)` 会静默吞掉 `target="_blank"`**：这类点击不走 `shouldOverrideUrlLoading`。DSH 前端确实有 `_blank` 链接。修法：打开该开关，在 `onCreateWindow` 里塞一个**临时 WebView**接住导航（该方法本身不给目标 URL），拿到 URL 后再决定去向。
1j. **覆盖了 `dispatchKeyEvent` 就夺走了 Dialog 的返回键**：Dialog 本来自己消费返回键，但 Activity 覆写后会先拦下来，于是"按返回"变成后退网页而不是关弹窗。必须显式让路（`dialog.onBackPressed()`，已确认是公开 API）。顺带：被动消失（被顶掉）要用 `setOnDismissListener` 兜底，别只在按钮回调里复位状态位。
1g. **文件名要防目录穿越**：`../../etc/passwd` 若只把 `/` 换成 `_` 会得到 `_.._etc_passwd`（点段还在）。必须按路径切段、丢掉 `.`/`..` 段再拼。
2. **Android 15 强制 edge-to-edge**（targetSdk 35）：`setStatusBarColor` 是 no-op，透明系统栏得靠 `WindowInsets` 自己让位。
3. **让位要用 layout margin，不能用 padding**。WebView 的视口尺寸是否扣除 padding 在各版本实现不一致，padding 会导致页面被裁或底部留白；margin 改的是 View 实际尺寸，永远等于视口尺寸。
4. **`javac -source 8 -bootclasspath android.jar`**：JDK 17 对 `-target >= 9` 禁止 `--boot-class-path`，所以只能降到 8；还要把 build-tools 的 `core-lambda-stubs.jar` 一起放进 bootclasspath，否则 lambda 报 `找不到 LambdaMetafactory`（脱糖是 d8 干的）。
5. **401 死循环**：自动填的密码若被服务器拒绝，WebView 会「proceed → 401 → 再问 → proceed」无限转。用 `onReceivedHttpError` 捕获 401 并置 `credsRejected`，下一次改为弹框。
6. **aapt2 的 `--no-crunch`** 必须加，否则 PNG 会被重编码。
7. `apkanalyzer` / `apksigner` 都是脚本，跑之前要把 JDK 的 `bin` 放进 `PATH`，否则报 `exec: java: 未找到`。
7b. **注入脚本的任何 DOM 观测都不能轮询**：`querySelector('[class*=composer]')` 是全文档属性扫描，再叠 `getBoundingClientRect()` 就是强制重排；首屏期间每 150ms 一次会直接抢光主线程。改用 `IntersectionObserver`（事件驱动）；MutationObserver 回调要用 rAF 合并 + 300ms 最小间隔，且颜色没变就不回调原生。
7c. **但改完观测代码别急着宣布"卡顿修好了"——先做 A/B 对照**。把 `THEME_SCRIPT` 从 Java 源里抽出来（正则抓字面量 + 反转义）当 `addInitScript` 跑，用 `PerformanceObserver({entryTypes:['longtask']})` 量长任务与 TBT，开/关注入各跑一次。实测两者是 807ms vs 822ms（噪声内）⇒ **观测代码无罪**，那 ~600ms 长任务是 SPA 自己的。`/tmp/firstpaint3.mjs` 就是这么干的。
7d. **别拿 APK 体积当「改动有没有生效」的判据**：加了两百字符 JS 后 APK 字节数与上一版完全相同（zip deflate 凑巧同长），差点误判成没编译进去。看 `sha256sum`，或 `unzip -p x.apk classes.dex | strings | grep <新出现的字符串>`。
7e. **`#app` 在这套 SPA 里高度恒为 0**：它不是可见根容器，所以就绪判据里 `#app`/`#root` 实际从不触发，真正生效的是 `[class*=composer]`。别假设 `#app` 有高度。

## ⚠️ 最严重的一次事故：脚本误改导致 showCard 无限递归（v3.3 才暴露）

**症状**：某 Android 10 平板**新装后一打开就闪退**；手机上完全正常。

**根因**：某次用脚本改代码时，`showCard()` 的最后一行从 `card.dialog.show();`
被误弄成了 `showCard(card);` —— **方法直接调用自己**。编译完全通过（编译器不管语义），
所以一路漏到真机上。

**为什么只在平板暴露**：平板是新装、没存过入口地址 ⇒ 启动即弹「首次进入」对话框 ⇒ 走
`showCard()` ⇒ 深递归 ⇒ `StackOverflowError`。手机早就存过地址，压根不走这条路。

**为什么难查**：手机/平板上没有 adb，拿不到堆栈。所以 v3.3 加了**崩溃自显屏**
（`onCreate` 包 `try/catch(Throwable)` + 全局 `UncaughtExceptionHandler` 写
SharedPreferences、下次启动展示）—— 装上后一次截图就定位了。

**防复发（已接入 `build.sh`）**：`tools/selftest/check_recursion.py`
—— 直接自递归静态检查。判据只抓"直接"自递归、宁可漏也不误报：
剥字符串/注释（否则注入的 JS 里 `function(){...}` 会被当方法定义）、只认行首方法定义、
排除 `obj.method(`、**跳过同名重载**（`fit/round/card/showFirstRunDialog` 这些合法同名调用）。
已双向验证：干净源码 PASS；把 bug 塞回去必被抓出。

> 教训：**编译通过 ≠ 语义正确**。凡是用脚本改代码，改完必须 grep/静态校验产物 ——
> 这已经是第三次同类问题（`MARKER_FILE` 常量没插进去、`showErrorDialog` 锚点写错、
> 这次的 `showCard`），所以现在把它做成构建期硬检查，而不是靠记性。

## 目录

```
app/AndroidManifest.xml          清单（权限、edge-to-edge 主题、快捷方式）
app/java/app/dsh/shell/
  MainActivity.java              整个壳：WebView、认证、insets、按键
  Config.java                   存储键名（刻意不写死地址）
  Css.java                       CSS 颜色解析（纯 Java，可脱机自测）
  Urls.java                      地址规范化 + 同站判定（纯 Java，可脱机自测）
  Filenames.java                 下载文件名解析（RFC 5987 / 防穿越，纯 Java，可脱机自测）
  Ui.java                        自绘对话框（卡片/圆角/深浅色），不依赖 AppCompat
  SettingsPanel.java             设置面板（自绘开关胶囊），由桌面快捷方式或返回键菜单打开
  LoadingView.java               全屏加载页（自绘旋转环 + 耗时计数 + 阶段文案）
  CaptureProvider.java           极简 FileProvider（服务拍照输出 + 压缩产物，仅 cache 下两个目录）
  ImageSizing.java               按比例缩到合规尺寸（纯 Java，覆盖极端宽高比）
  ImageCompressor.java           上传前压缩：两段式解码 + 质量/尺寸阶梯 + 去 EXIF
  DebugRelay.java                调试通道控制端（查/要 Shizuku 授权、bind UserService、下发配置）
  DebugRelayService.java         调试通道执行端（Shizuku UserService，shell 身份独立进程，跑命令流回输出）
  B64.java                       极小 Base64（java.util.Base64 是 API 26+，壳 minSdk 24 自己写）
app/res/                         图标（鲸鱼由 tools/svg2png.py 生成）、字符串、主题、shortcuts.xml
assets/whale.svg                 图标源文件（取自 DSH 的 favicon.svg）
tools/svg2png.py                 极简 SVG→PNG 渲染器（纯标准库，扫线填充 + 抗锯齿）
tools/gen_icons.py               生成全套图标，留白参数集中在这里
tools/selftest/TestCss.java      颜色解析单元测试（build.sh 会跑）
tools/selftest/TestUrls.java     地址规范化 / 同站判定单元测试（build.sh 会跑）
tools/selftest/TestFilenames.java 文件名解析单元测试（28 项，样本取自实测响应头）
tools/selftest/TestNav.java      导航去向判定用例（应用内 / 系统 / 忽略，14 项）
tools/selftest/TestImageSizing.java  尺寸计算用例（含极端比例 + 20000 组随机，29 项）
tools/selftest/test_capture_provider.py  CaptureProvider 路径放行规则用例 + 元验证
tools/selftest/TestB64.java      Base64 编解码用例（RFC 4648 向量 + 全字节往返，13 项）
libs/shizuku-*.jar               Shizuku API 13.1.5（Maven aar 解出 classes.jar，调试通道用）
tools/preview_ui.py              把 Ui.java 的配色渲染成预览图（无模拟器时靠它看界面）
tools/preview-mobile.mjs         按某 Android 15 手机 参数（436x971@2.75）远程渲染预览
build.sh                         一键构建
```

## 调试通道（ADB，v4.0）

目的：不插 USB 线，让 agent 在主机上直接对手机执行 shell 命令调试这个壳
（拿 logcat 堆栈、装新包、截图、dumpsys），等效「远程 adb shell」。

原理：手机**出站**连主机的 phone-relay（`tools/phone-relay/relay.py`，
systemd 服务 `phone-relay`，端口 2900），收到命令后经 Shizuku UserService 以
shell(uid 2000) 身份在**独立进程**执行、输出流回。手机不开任何监听端口、不碰
adbd；App 被杀不影响通道（正好用来看崩溃日志）。

- **手机端**：设置面板「调试通道」填 中继地址 + 令牌（主机
  `/etc/dsh-relay/token`），打开开关（首次弹 Shizuku 授权框）。
  Shizuku 没启动先去 Shizuku App 启动；**手机重启后 Shizuku 和本开关都要重开**。
  地址两种填法：域名 `relay.example.com:443`（走公网 443 链路，外面也能用，
  TLS 真证书）；内网 IP `<host>:2900`（内网直连，明文）。
- **公网链路**：`手机 → 反代服务器→ 隧道 →
  网关分流 → TLS 终结→ relay`。
  DNS `relay.example.com` 指向 relay 服务所在机器。
- **主机端**：`bin/phone <shell 命令>`；`phone status` 看在线，
  `phone -t 秒` 超时自动杀任务。
- 依赖代价：`libs/shizuku-*.jar`（Maven aar 手工解包，没引 Gradle），包体
  120KB→186KB；签名指纹未变，可覆盖安装。
- 已知边界：身份是 shell 不是 root（读不了别家 App 私有数据）；KILL 只保证杀掉
  sh 及其直接子进程；公网链路 TLS 在 nginx 终结（App 走系统信任库 + 主机名校验）；鉴权靠 token。
- 回滚：面板关掉开关即断；或装回 DSH-3.6.apk（签名一致，覆盖安装即可）。
  网关侧回滚：恢复 `配置备份` + `nginx -s reload`。
