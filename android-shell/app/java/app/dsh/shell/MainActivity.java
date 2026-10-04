package app.dsh.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.content.pm.PackageManager;
import android.provider.MediaStore;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * DSH 的"壳"：整屏 WebView 直连 DSH Web UI，不带任何多余 chrome。
 *
 * 关于 edge-to-edge（Android 15 / targetSdk 35 强制生效，setStatusBarColor 已是 no-op）：
 *   · 窗口铺满全屏，状态栏/手势条区域透明，露出的是页面自身的底色（自动适配深色主题）
 *   · WebView 用 layout margin 让出状态栏、手势条与输入法高度 —— 不用 padding，
 *     因为 padding 是否被渲染器计入视口尺寸在各版本 WebView 上并不一致，margin 是确定的
 *   · 页面底色变化（切主题）由注入的 MutationObserver 回调 Java，同步根视图底色与图标明暗
 */
public class MainActivity extends Activity {

    private static final String TAG = "DSH";
    private static final long DOUBLE_BACK_MS = 2000L;
    /** 加载页最长再挡这么久（onPageFinished 之后算起），到点强制收起。 */
    private static final long LOADING_FALLBACK_MS = 12000L;
    private static final long LONG_PRESS_MS = 600L;
    private static final int REQ_FILE = 1001;
    private static final int REQ_STORAGE = 1002;
    private static final int FALLBACK_BG = 0xFFFFFFFF;

    /**
     * 注入脚本：① 把页面底色同步给原生（系统栏配色）② 检测首屏真正渲染完成后收起加载页。
     *
     * ⚠️ 两个性能坑（都是真实踩过的，会造成「正在渲染 → 进入页面」明显卡顿）：
     *   1. **不要轮询 `querySelector('[class*=xxx]')`**：那是全文档属性扫描，
     *      再叠加 `getBoundingClientRect()` 强制重排，在 12MB 的 SPA 首屏期间
     *      每 150ms 做一次，直接把主线程抢光。改用 IntersectionObserver（事件驱动，
     *      元素可见时才回调，完全不轮询）；只在它不可用时退化为**低频**兜底轮询。
     *   2. **MutationObserver 回调要合并到一帧**：React 首屏会疯狂改 class/style，
     *      每次都 `getComputedStyle` 会触发强制样式重算。这里用 rAF 把一帧内的
     *      多次变更合成一次，并且颜色没变就不回调原生。
     */
    private static final String THEME_SCRIPT =
            "(function(){"
            // ── ⓪ 网络活动探针（切回前台后的"还在拉数据吗"全靠它）──
            // DSH 客户端走的是 fetch 流式传输（不是 WebSocket），所以：
            //   · 包一层 fetch：请求开始/结束都记个时间；
            //   · 更重要的是**包一层 ReadableStream.prototype.getReader**，
            //     在每次读到新分片时记时间。这样"流还在吐数据"= 还在加载。
            // 只加时间戳、不改任何数据与对象本身（比替换 Response.body 安全得多，
            // 不会丢 res.url/ok/headers，也不会破坏 clone/pipe 语义）。
            + "var netAt=0;"
            + "try{var of=window.fetch;if(of){window.fetch=function(){"
            + "  netAt=performance.now();var p=of.apply(this,arguments);"
            + "  try{p.then(function(){netAt=performance.now();},function(){netAt=performance.now();});}catch(e){}"
            + "  return p;};}}catch(e){}"
            + "try{var gr=window.ReadableStream&&ReadableStream.prototype&&ReadableStream.prototype.getReader;"
            + "if(gr){ReadableStream.prototype.getReader=function(){"
            + "  var rd=gr.apply(this,arguments);"
            + "  try{var orig=rd.read.bind(rd);"
            + "    rd.read=function(){return orig().then(function(x){"
            + "      if(x&&!x.done)netAt=performance.now();return x;});};"
            + "  }catch(e){}"
            + "  return rd;};}}catch(e){}"
            // ── ① 主题色回传（rAF 合并 + 去重）──
            + "var lastC='',pending=false,lastAt=0;"
            + "function readBg(){var b=document.body,e=document.documentElement;"
            + "var c=b?getComputedStyle(b).backgroundColor:'';"
            + "if(!c||c==='rgba(0, 0, 0, 0)'||c==='transparent'){c=getComputedStyle(e).backgroundColor;}"
            + "if(!c||c==='rgba(0, 0, 0, 0)'||c==='transparent'){c='rgb(255,255,255)';}"
            + "return c;}"
            + "function flush(){pending=false;lastAt=Date.now();var c;try{c=readBg();}catch(e){return;}"
            + "if(c===lastC)return;lastC=c;"
            + "try{if(window.DshBridge)window.DshBridge.setBackground(c);}catch(e){}}"
            // rAF 合并 + 最小间隔 300ms 双重限流：getComputedStyle 会强制样式重算，
            // 首屏 React 提交期间每帧都算一遍仍然偏重，加间隔后最多 3 次/秒。
            + "function cur(){if(pending)return;"
            + "var wait=Math.max(0,300-(Date.now()-lastAt));"
            + "if(wait>0){pending=true;setTimeout(function(){pending=false;cur();},wait);return;}"
            + "pending=true;"
            + "if(window.requestAnimationFrame)requestAnimationFrame(flush);else setTimeout(flush,50);}"
            + "cur();"
            // 主题同步改成**低频轮询**，不再挂 MutationObserver。
            // 实测（高频 DOM 变动 5 秒）：观察器触发 16 次、单次 getComputedStyle 0.006ms、
            // 合计开销 ≈0.1ms/5s（0.00%）——本来就不是瓶颈；但该开销**随 DOM 变大而涨**
            // （强制样式重算在大页面上更贵），而主题变化本身极罕见（只在用户切深浅色时），
            // 所以退化成 2 秒一次最划算：开销再降 ~6 倍，代价只是状态栏配色最多晚 2 秒。
            + "setTimeout(cur,400);setTimeout(cur,1500);"
            + "setInterval(cur,2000);"
            // ── ② 让页面自曝处境（诊断用）──
            // 加载页卡在「正在渲染 Ns」时，光看秒数分不清是「还在下载」「JS 报错」
            // 还是「真在渲染」。手机上没有 adb，所以让页面每秒把自己的处境报给壳、
            // 直接显示在加载页上 —— 用户截一张图就能定位。只记真正的运行时错误
            // （捕获阶段的资源加载失败没有 message，别拿来当报错刷屏）。
            + "var errMsg='';"
            + "try{window.addEventListener('error',function(e){"
            + "if(!errMsg&&e&&e.message)errMsg=e.message+'';},true);"
            + "window.addEventListener('unhandledrejection',function(e){"
            + "if(!errMsg)errMsg='Promise: '+((e&&e.reason&&e.reason.message)||(e&&e.reason)||'');},true);"
            + "}catch(e){}"
            // ── ③ 首屏就绪检测（事件驱动，不轮询、不强制重排）──
            + "var done=false;"
            // 收起时机：元素可见 ≠ 主线程空了。实测首屏在 DOMContentLoaded 之后
            //    还有一串长任务（最大 ~600ms，React 提交），若加载页在这串任务中间消失，
            //    用户看到的就是「页面出来了但僵住不动」。所以：可见后先等
            //    requestIdleCallback（主线程空了才回调），再等两帧绘制，另有硬上限兜底。
            + "function ready(){if(done)return;done=true;var fired=false;"
            + "function once(){if(fired)return;fired=true;"
            + "function go(){try{if(window.DshBridge)window.DshBridge.ready();}catch(e){}}"
            + "if(window.requestAnimationFrame)"
            + "requestAnimationFrame(function(){requestAnimationFrame(go);});"
            + "else setTimeout(go,32);}"
            + "try{if(window.requestIdleCallback)requestIdleCallback(once,{timeout:500});"
            + "else setTimeout(once,60);}catch(e){setTimeout(once,60);}"
            + "setTimeout(once,900);}"
            + "var io=null,seen=false;"
            + "function scan(){"
            + "  if(done)return true;"
            + "  var found=false;"
            // 输入类：任何站点的"可交互"标志
            + "  var cands=['[class*=composer],[class*=Composer],textarea,[contenteditable=true]',"
            + "             '#app,#root,main,[class*=appRoot],[class*=AppRoot]'];"
            + "  for(var k=0;k<cands.length;k++){"
            + "    try{var els=document.querySelectorAll(cands[k]);"
            + "      for(var j=0;j<els.length;j++){io.observe(els[j]);found=true;}}catch(e){}"
            + "  }"
            // 通用兜底：观察 body 的每个直接子元素。这是唯一对"任意站点"都成立的
            // 位置 —— 不管是 React/Vue/原生 DOM，正文总归挂在 body 下面。
            + "  try{var bc=document.body?document.body.children:[];"
            + "    for(var m=0;m<bc.length;m++){io.observe(bc[m]);found=true;}}catch(e){}"
            + "  return found;}"
            // 元素可能晚于脚本挂载：用 MutationObserver 事件驱动补挂，不要每秒轮询
            // （慢 1 秒就等于加载页白挡 1 秒）；频繁变更用 80ms 合流，少跑几次 querySelectorAll。
            + "function scanSoon(){if(seen||done)return;"
            + "if(scan()){seen=true;return;}"
            + "if(scanSoon.p)return;scanSoon.p=true;"
            + "setTimeout(function(){scanSoon.p=false;if(scan())seen=true;},80);}"
            + "try{"
            + "  if(window.IntersectionObserver){"
            + "    io=new IntersectionObserver(function(es){"
            + "      for(var i=0;i<es.length;i++){"
            + "        if(!es[i].isIntersecting)continue;"
            + "        var h=es[i].intersectionRect.height;if(h<8)continue;"
            + "        var tg=es[i].target,cn=(tg.className||'')+'',tn=tg.tagName;"
            // 输入框只要露头就算就绪
            + "        var input=(cn.indexOf('omposer')>=0||tn==='TEXTAREA'||tg.isContentEditable);"
            // 其它元素要同时够大**且有子元素**：实测 #root 在应用没挂载时就是
            // 600px 高、0 子元素的空壳（height:100% 撑的），只看高度必误判成"渲染完成"。
            // ⚠️ 子元素数只能要求 ≥1，不能要求 ≥2：实测 同域服务 的整棵应用
            //    只挂在 body 下一个 div 里（h=1887 但子元素只有 1 个），
            //    卡 ≥2 会让通用壳在 同域服务 这类站点上永远等不到 ready、一路拖到 12s 兜底。
            + "        if(!input&&(h<120||!tg.children.length))continue;"
            + "        io.disconnect();seen=true;ready();return;}"
            + "    },{threshold:0.01});"
            + "    seen=scan();"
            + "    if(!seen){"
            + "      try{var mo=new MutationObserver(function(){scanSoon();});"
            + "        mo.observe(document.documentElement,{childList:true,subtree:true});"
            + "        setTimeout(function(){try{mo.disconnect();}catch(e){}},30000);}catch(e){}"
            + "      var r1=0;"
            + "      var t1=setInterval(function(){r1++;"
            + "        if(done||r1>20){clearInterval(t1);return;}"
            + "        if(scan())seen=true;},1000);"
            + "    }"
            + "  } else {"
            // 没有 IntersectionObserver：极低频兜底（1s 一次），仍避免 getBoundingClientRect
            + "    var r2=0;"
            + "    var t2=setInterval(function(){"
            + "      r2++;if(done||r2>30){clearInterval(t2);return;}"
            + "      if(document.getElementById('app')||document.querySelector('[class*=composer]'))ready();"
            + "    },1000);"
            + "  }"
            + "}catch(e){}"
            // ── ④ 每秒自检 + 把「在等什么」报给壳（就绪后停，最多 12 次）──
            // innerText 是**已渲染**的文字（textContent 会把 <script>/<noscript> 也算进去，
            // 实测页面完全没渲染时 textContent 仍有 516 字，会骗人）。它会触发布局，
            // 所以只在还没就绪时每秒读一次，且最多读 12 次。
            + "var rep=setInterval(function(){"
            + "  if(done){clearInterval(rep);return;}"
            + "  rep.n=(rep.n||0)+1;if(rep.n>12){clearInterval(rep);return;}"
            + "  try{"
            + "    var txt='';"
            + "    try{txt=((document.body&&document.body.innerText)||'').replace(/\\s+/g,' ').trim();}catch(e){}"
            + "    var rn=0;try{rn=performance.getEntriesByType('resource').length;}catch(e){}"
            + "    var s='资源'+rn+' 正文'+txt.length+'字';"
            + "    if(errMsg)s+=' ⚠'+errMsg.slice(0,60);"
            + "    if(window.DshBridge&&window.DshBridge.note)window.DshBridge.note(s);"
            // DOM 结构千奇百怪，看不懂结构时还有这条最朴素的判据：页面有成句的正文了
            // 就算渲染出来了。同域服务 就是靠它兜住的（它的应用根只有 1 个子元素）。
            + "    if(!seen&&txt.length>=20){seen=true;ready();}"
            + "  }catch(e){}"
            + "},1000);"
            // ── ⑤ 切回前台后：盯着网络，有流量才提示 ──
            // 只做一件事：把「现在有没有在传数据」「传完了没」报给壳。
            //   · 观测到流量（netAt 前进）→ busy（壳显示"正在同步…"）
            //   · 之后静默 SETTLE_NET_MS → done（壳切成"同步完成"再淡出）
            //   · 自始至终没有任何流量（比如锁屏解锁、会话本来就没在动）→ 什么都不报，
            //     壳也就什么都不显示 —— 之前无条件先弹再判，导致每次解锁都弹 1.5 秒（用户实报）。
            + "window.__dshWatchNet=function(seq){"
            + "  var net0=netAt,seen=false,lastSeen=0,rounds=0;"
            + "  function tell(kind){try{if(window.DshBridge&&window.DshBridge.sync)window.DshBridge.sync(seq,kind);}catch(e){}}"
            + "  function tick(){"
            + "    rounds++;"
            + "    var now=performance.now();"
            + "    if(netAt>net0){seen=true;net0=netAt;lastSeen=now;tell(\"busy\");}"
            + "    else if(seen&&(now-lastSeen)>=500){tell(\"done\");return;}"   // 500ms 静默即认为传完
            + "    else if(!seen&&rounds>20){return;}"                            // 约 3s 没动静 ⇒ 啥都不显示
            + "    if(rounds>200){tell(\"done\");return;}"                       // 约 30s 兜底
            + "    setTimeout(tick,150);"
            + "  }"
            + "  setTimeout(tick,150);"
            + "};"
            + "})();";

    private WebView web;
    private FrameLayout root;
    private SharedPreferences prefs;

    private long lastBackAt = 0L;
    private long backDownAt = 0L;
    private boolean longPressFired = false;

    private String pendingUser;
    private String pendingPass;
    private ValueCallback<Uri[]> fileCallback;
    private boolean errorDialogShowing = false;
    private boolean credsRejected = false;
    private int lastBg = FALLBACK_BG;

    /**
     * 上传前是否压缩图片。默认开：实测能把 3~6MB 原图压到 ~400KB，
     * 且因为服务端会「已合规就放行」，两边都省一遍重编码。
     * 可用长按菜单关掉（要传未经处理的原始文件时）。
     */
    private boolean compressUploads = true;

    /** 上次运行崩在哪儿（下次启动时显示出来）。 */
    private static final String KEY_LAST_CRASH = "last_crash";

    /** 页面自报的最后一条处境（形如「资源8 正文0字」），加载兜底时用它判断页面起没起来。 */
    private String lastPageNote = "";

    /** 全屏加载页：白屏时至少能看出"在加载"还是"卡死了"。 */
    private LoadingView loading;

    /**
     * 「正在同步」小胶囊：从后台切回前台时显示。
     * ⚠️ 必须原生画 —— 那几秒卡的正是 WebView 主线程，网页里画的转圈会一起卡死。
     */
    private SyncView syncing;

    /** 切后台的时刻；用来判断"回来时值不值得提示"。 */
    private long backgroundedAt = 0L;
    /** 每次切回前台递增，用来丢弃过期的 idle 回调。 */
    private int syncSeq = 0;
    /** 超过这么久没等到 idle 就强制收起，免得胶囊挂着不走。 */
    private static final long SYNC_GIVE_UP_MS = 15000L;
    /** 后台待了不足这么久，回来基本是瞬时的，不提示。 */
    private static final long SYNC_MIN_BACKGROUND_MS = 800L;
    /** 有数据在传时的静默判定交给页面（500ms）；这里只留一个兜底收起的上限。 */

    /** 主线程 Handler（切回前台后的延迟任务与兜底收起用）。 */
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * 当前打开的卡片。必须自己管：认证弹窗由 **401 异步回调**触发，它不知道用户
     * 此刻是不是正开着自己的窗口。实测顺序 —— 先「清除账号密码」→ 401 回来正要弹认证框
     * → 用户同时点了「修改地址」→ 两个卡片叠一起，认证框盖住地址框（用户报的 bug）。
     *
     * 规则：同一时刻只留一个卡片，新卡片弹出前先收掉上面那个。
     */
    /** 「拍照」分支的输出 Uri（相机不通过 data 返回，只能我们自己记着）。 */
    private Uri photoUri;

    private Ui.Card activeCard;

    private void showCard(Ui.Card card) {
        if (activeCard != null && activeCard != card && activeCard.dialog.isShowing()) {
            activeCard.dialog.dismiss();
        }
        activeCard = card;
        card.dialog.show();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    /**
     * 启动流程包一层 try/catch：壳在老设备上崩过（某平板 Android 10 一打开就闪退），
     * 而手机/平板上没有 adb，拿不到堆栈。所以一旦启动异常，**直接把堆栈画在屏幕上**，
     * 用户截个图就能反馈 —— 这是没 adb 时唯一可靠的取堆栈办法。
     * 另外装一个全局兜底：onCreate 之后才崩的，写进 SharedPreferences，下次启动时显示。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            onCreateBody(savedInstanceState);
        } catch (Throwable t) {
            showCrashScreen(android.util.Log.getStackTraceString(t));
        }
    }

    private void onCreateBody(Bundle savedInstanceState) {
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);

        // ── 崩溃兜底：onCreate 之外崩的，记下来，下次启动时展示 ──
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                String stack = android.util.Log.getStackTraceString(ex);
                prefs.edit().putString(KEY_LAST_CRASH, stack).commit();
                java.io.File dir = getExternalFilesDir(null);
                if (dir != null) {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(
                            new java.io.File(dir, "crash.txt"));
                    fos.write(stack.getBytes("UTF-8"));
                    fos.close();
                }
            } catch (Throwable ignored) {
                // 记不下来也不能再抛
            }
            Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            if (prev != null && prev != Thread.currentThread()
                    .getUncaughtExceptionHandler()) {
                // 交回系统默认处理（打印 + 结束进程）
            }
        });

        // 上次崩过？先把它显示出来，别急着建界面（可能就是建界面这一步崩的）
        String lastCrash = prefs.getString(KEY_LAST_CRASH, null);
        if (lastCrash != null && !lastCrash.isEmpty()) {
            prefs.edit().remove(KEY_LAST_CRASH).commit();
            showCrashScreen(lastCrash);
            return;
        }

        compressUploads = prefs.getBoolean(Config.K_COMPRESS, true);

        // 调试通道：上次开着就悄悄接回去（Shizuku 不在/未授权就放弃，不弹框）
        try {
            if (prefs.getBoolean(Config.K_RELAY_ON, false)) {
                DebugRelay.restore(prefs.getString(Config.K_RELAY_ADDR, ""),
                        prefs.getString(Config.K_RELAY_TOKEN, ""));
            }
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        root = new FrameLayout(this);
        root.setBackgroundColor(FALLBACK_BG);

        web = new WebView(this);
        web.setBackgroundColor(FALLBACK_BG);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        syncing = new SyncView(this);
        root.addView(syncing, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        loading = new LoadingView(this);
        loading.setVisibility(View.GONE);
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        applyEdgeToEdge();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);   // 见 DshChrome.onCreateWindow：_blank 需要它才不被吞
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }
        // 声明这个 WebView 可参与系统的 scroll capture（Android 12L 起平台支持）。
        // ⚠️ **但实测对本页无效，留着只是为了"该声明的都声明了"**：DSH 页面是虚拟滚动，
        //    文档和内部容器都不可滚（见 environmentReport() 的注释），系统拿不到可滚目标，
        //    所以长截图在这个壳里做不了 —— 换机器也一样。别再为此重新排查一遍。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            web.setScrollCaptureHint(View.SCROLL_CAPTURE_HINT_INCLUDE);
        }

        web.addJavascriptInterface(new Bridge(), "DshBridge");

        web.setWebViewClient(new DshClient());
        web.setWebChromeClient(new DshChrome());
        web.setDownloadListener(new DshDownload());

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else if (currentUrl().isEmpty()) {
            showFirstRunDialog();            // 通用壳：首次进入由用户手填地址
        } else {
            web.loadUrl(currentUrl());
        }
        handleIntent(getIntent());
    }

    /**
     * 全屏铺满 + 用 margin 让出系统栏与输入法。
     * margin 而不是 padding：WebView 的视口尺寸是否扣除 padding 在各版本实现不一致，
     * 而 View 的实际布局尺寸永远等于视口尺寸，所以 margin 是唯一确定的做法。
     */
    private void applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    top = bars.top;
                    bottom = Math.max(bars.bottom, ime.bottom);
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                ViewGroup.LayoutParams p = web.getLayoutParams();
                if (p instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) p;
                    if (m.topMargin != top || m.bottomMargin != bottom) {
                        m.topMargin = top;
                        m.bottomMargin = bottom;
                        web.setLayoutParams(m);
                    }
                }
                // 「正在同步」气泡挂在 root 上（root 是全屏的），所以必须自己让位，
                // 否则会压在状态栏/摄像头挖孔上（小米 15 前置摄像头在顶部正中，实测压住了）。
                // top 已经包含 displayCutout，这里再留 12dp 呼吸空间。
                if (syncing != null) {
                    ViewGroup.LayoutParams sp = syncing.getLayoutParams();
                    if (sp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams sm = (ViewGroup.MarginLayoutParams) sp;
                        int want = top + Ui.dp(MainActivity.this, 12);
                        if (sm.topMargin != want) {
                            sm.topMargin = want;
                            syncing.setLayoutParams(sm);
                        }
                    }
                }
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /** 把系统栏图标的明暗切成与页面底色相配的（深色底 → 亮图标）。 */
    private void applyBarIcons(boolean lightBackground) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) {
                return;
            }
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(lightBackground ? mask : 0, mask);
        } else {
            int flags = getWindow().getDecorView().getSystemUiVisibility()
                    & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            if (lightBackground) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (lightBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void applyBackground(int color) {
        if (color == lastBg) {
            return;
        }
        lastBg = color;
        ColorDrawable d = new ColorDrawable(color);
        root.setBackground(d);
        web.setBackground(d);
        double lum = (0.2126 * Color.red(color) + 0.7152 * Color.green(color)
                + 0.0722 * Color.blue(color)) / 255.0;
        // 页面是深色时，弹出的卡片也跟着走深色，否则一道白卡片会很刺眼
        Ui.dark = lum <= 0.5;
        applyBarIcons(lum > 0.5);
    }

    /** 供页面调用：把当前页面底色同步给原生壳。 */
    private class Bridge {

        /** 页面真正渲染出内容了 → 收起加载页。 */
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                if (loading != null) {
                    loading.finish();
                }
            });
        }

        /** 供页面调用：切回前台后的网络状态（"busy"=有数据在传，"done"=传完了）。 */
        @JavascriptInterface
        public void sync(final int seq, final String kind) {
            runOnUiThread(() -> {
                if (seq != syncSeq || syncing == null) {
                    return;
                }
                if ("busy".equals(kind)) {
                    syncing.showBusy();
                } else if ("done".equals(kind)) {
                    syncing.showDone();
                }
            });
        }

        /** 供页面调用：把当前处境同步给加载页（诊断用，见 THEME_SCRIPT ④）。 */
        @JavascriptInterface
        public void note(final String s) {
            runOnUiThread(() -> {
                if (s == null) {
                    return;
                }
                lastPageNote = s;                       // 兜底分流要用它判断"渲染出来没有"
                if (loading != null) {
                    loading.setHint(s);
                }
            });
        }

        @JavascriptInterface
        public void setBackground(final String css) {
            final int c = Css.parse(css);
            if (c < 0) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyBackground(c);
                }
            });
        }
    }

    private String currentUrl() {
        return prefs.getString(Config.K_URL, "");
    }

    private void loadUrl(String url) {
        prefs.edit().putString(Config.K_URL, url).apply();
        showLoading("正在连接");
        web.loadUrl(url);
    }

    /**
     * 兜底：万一注入脚本没回调 ready()（页面结构变了等），
     * 也不能让加载页永远挡着用户 —— 到点强制收起。
     */
    /**
     * 兜底：注入脚本迟迟没回调 ready() 时（页面结构变了、或者页面压根没跑起来）。
     *
     * ⚠️ 不能无脑收起 —— 会露白屏。实测某平板（系统 WebView 太旧，报
     * `Iterator is not defined`）就是「资源8 正文0」卡到 12 秒兜底，收起后一片白，
     * 用户完全不知道发生了什么。所以这里按「页面到底渲染出文字没有」分流：
     *   · 有文字（正文 > 0）⇒ 只是慢，正常收起；
     *   · 没有文字（正文 == 0）⇒ 页面根本没起来，把加载页变成"失败说明 + 建议 + 点按重试"。
     */
    private void scheduleLoadingFallback() {
        if (loading == null) {
            return;
        }
        loading.postDelayed(() -> {
            if (loading == null || !loading.isActive()) {
                return;
            }
            if (pageRenderedText()) {
                loading.finish();
                return;
            }
            loading.fail("页面没能渲染出来",
                    lastPageNote + "\n\n常见原因：这台设备的系统 WebView 太旧"
                            + "（需要 Chromium 122 以上）。\n可以试试：更新系统 WebView / "
                            + "Huawei WebView，或改用 Edge、Chrome 打开这个地址。\n\n点按本页重试");
            loading.setOnClickListener(v -> web.reload());
        }, LOADING_FALLBACK_MS);
    }

    /** 页面自报的处境里「正文N字」N 是否 > 0（判据见 THEME_SCRIPT ④）。 */
    private boolean pageRenderedText() {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("正文(\\d+)字").matcher(lastPageNote == null ? "" : lastPageNote);
        return m.find() && Integer.parseInt(m.group(1)) > 0;
    }

    /**
     * 设置面板显示的运行环境。
     *
     * ⚠️ 这里**故意不再显示「能不能长截图」** —— 那个结论已经查死了（2026-09-25）：
     * DSH 页面是虚拟滚动 —— 全场最大 `scrollHeight` 就等于视口高、有内容溢出的元素 = 0、
     * `scrollTop` 推不动 ⇒ **页面里没有任何原生可滚的抓手**。因此：
     *   · 平台/MIUI 的 scroll capture 找不到目标（实测 `可截目标=0`，hint 设 INCLUDE 也没用）；
     *   · 自己实现 ScrollCaptureCallback 也没抓手（它要求"把指定区域渲染到 Surface"，
     *     而我们既滚不动文档、也滚不动任何内部容器）。
     * **换什么机器都一样**（这是页面结构决定的，不是设备能力问题），所以不必再测。
     * 真想在这台手机上截长图，只能用「模拟手势逐屏滚动 + 拼接」的第三方工具（走无障碍），
     * 或者干脆在电脑浏览器里截 —— DSH 本身就是网页。
     */
    String environmentReport() {
        return "Android " + Build.VERSION.SDK_INT + " · WebView " + webViewVersion();
    }

    /**
     * 当前 WebView 的版本。
     * 装到老安卓（8/9/11/12）上时，**这一项才决定 DSH 能不能跑起来** —— 壳用的是系统
     * WebView，太旧（比如 Android 8 出厂没更新过的 Chromium 60 一代）会直接白屏。
     * WebView ≥ 100 基本没问题。
     */
    private String webViewVersion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null && pi.versionName != null) {
                    return pi.versionName;
                }
            } catch (Throwable ignored) {
                // 落到下面用 UA 猜
            }
        }
        try {
            String ua = android.webkit.WebSettings.getDefaultUserAgent(this);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("Chrome/([0-9.]+)").matcher(ua);
            if (m.find()) {
                return "~" + m.group(1) + "(UA)";
            }
        } catch (Throwable ignored) {
            // 拿不到就算了
        }
        return "未知";
    }

    /** 显示加载页并开始计时；若已在显示则只更新阶段文案。 */
    private void showLoading(String stage) {
        if (loading == null) {
            return;
        }
        loading.applyTheme(Ui.dark);
        if (loading.isActive()) {
            loading.setStage(stage);
        } else {
            loading.setStage("正在连接");
            loading.start();
        }
        if (stage != null) {
            loading.setStage(stage);
        }
    }

    private void injectThemeScript() {
        web.evaluateJavascript(THEME_SCRIPT, null);
    }

    /** 处理「长按桌面图标」的快捷方式动作（手势导航手机没有实体返回键，这是主入口）。 */
    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        switch (intent.getAction()) {
            case "app.dsh.shell.action.RELOAD":
                web.reload();
                break;
            case "app.dsh.shell.action.CLEAR_CREDS":
                clearCredsAndReload();
                break;
            case "app.dsh.shell.action.SET_URL":
                showUrlDialog();
                break;
            case "app.dsh.shell.action.SETTINGS":
                showSettings();
                break;
            default:
                break;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void clearCredsAndReload() {
        pendingUser = null;
        credsRejected = false;
        prefs.edit().remove(Config.K_USER).remove(Config.K_PASS).apply();
        CookieManager.getInstance().removeAllCookies(null);
        Toast.makeText(this, "已清除，重新加载中", Toast.LENGTH_SHORT).show();
        web.reload();
    }

    // ───────────────────────── WebViewClient ─────────────────────────

    private class DshClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        /**
         * 只把「真正的外部站点」丢给系统浏览器。
         *
         * DSH 自己会跳到兄弟子域（同域服务、面板等），都属于同一个部署，
         * 必须留在应用内 —— 否则点一下侧栏的「文件」就被踢出去了（正是用户反馈的问题）。
         * 判定按注册域（末两段）比，见 Urls.sameSite()。
         */
        private boolean handleUrl(String url) {
            if (url == null || url.isEmpty()) {
                return false;
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                String host = Uri.parse(url).getHost();
                String self = Uri.parse(currentUrl()).getHost();
                if (host == null || Urls.sameSite(host, self)) {
                    return false;
                }
                return openExternally(url);
            }
            // 非 http scheme（mailto: / tel: / intent: / 自定义 scheme）交系统处理。
            // 这类链接点在应用内没有任何意义，交给系统才是对的。
            return openExternally(url);
        }

        private boolean openExternally(String url) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                // 没有应用能处理（比如没装邮件客户端）→ 留在应用内打开，避免白点一下
                return false;
            }
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                              String host, String realm) {
            if (pendingUser != null) {
                String u = pendingUser;
                String p = pendingPass;
                pendingUser = null;
                pendingPass = null;
                handler.proceed(u, p);
                return;
            }
            String user = prefs.getString(Config.K_USER, "");
            String pass = prefs.getString(Config.K_PASS, "");
            // 存的密码已被服务器拒绝过（见 onReceivedHttpError）时不再自动重试，直接问用户
            if (!user.isEmpty() && prefs.getBoolean(Config.K_REMEMBER, true) && !credsRejected) {
                handler.proceed(user, pass);
                return;
            }
            handler.cancel();
            credsRejected = false;
            showAuthDialog();
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            // 401 = 我们自动填的账号密码不对。标记一下，下一次认证请求改为弹框，
            // 否则会「proceed → 401 → proceed → 401」无限循环。
            if (response != null && response.getStatusCode() == 401 && request.isForMainFrame()) {
                credsRejected = true;
            }
        }

        @Override
        public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
            Ui.Card card = Ui.card(MainActivity.this, false);
            LinearLayout col = card.content;
            col.addView(Ui.title(MainActivity.this, "证书异常"));
            TextView msg = Ui.body(MainActivity.this, "错误码 " + error.getPrimaryError()
                    + "\n" + error.getUrl() + "\n\n仅在确认这是你自己的服务器时才继续。");
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = Ui.dp(MainActivity.this, 10);
            col.addView(msg, mlp);

            TextView go = Ui.primaryButton(MainActivity.this, "继续");
            TextView back = Ui.ghostButton(MainActivity.this, "取消");
            Ui.addButtons(col, MainActivity.this, back, go);

            // 若这张卡片被别的卡片顶掉（或被返回键取消），handler 必须 cancel，
            // 否则这次 TLS 握手会永远悬着。用标志位区分「用户已选择」与「被动消失」。
            final boolean[] resolved = {false};
            go.setOnClickListener(v -> {
                resolved[0] = true;
                card.dialog.dismiss();
                handler.proceed();
            });
            back.setOnClickListener(v -> {
                resolved[0] = true;
                card.dialog.dismiss();
                handler.cancel();
            });
            card.dialog.setOnDismissListener(d -> {
                if (!resolved[0]) {
                    handler.cancel();
                }
            });
            showCard(card);
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            // 只有主框架导航才显示全屏加载页；子资源加载不动它
            showLoading("正在连接");
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            errorDialogShowing = false;
            injectThemeScript();
            // 页面 onPageFinished 后 SPA 还要拉插件包并首屏渲染，
            // 所以这里不立刻收掉加载页，交给注入脚本在真正渲染后回调。
            if (loading != null) {
                loading.setStage("正在渲染");
            }
            scheduleLoadingFallback();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showErrorDialog(String.valueOf(error.getDescription()));
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (failingUrl != null && failingUrl.equals(currentUrl())) {
                showErrorDialog(description);
            }
        }
    }

    // ───────────────────────── WebChromeClient ─────────────────────────

    private class DshChrome extends WebChromeClient {

        /** 真实加载进度：进度 100 不代表 SPA 渲染完，所以只用来切换阶段文案。 */
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (loading == null || !loading.isActive()) {
                return;
            }
            if (newProgress < 30) {
                loading.setStage("正在连接");
            } else if (newProgress < 90) {
                loading.setStage("正在加载资源 " + newProgress + "%");
            } else {
                loading.setStage("正在渲染");
            }
        }

        /**
         * `target="_blank"` 与 `window.open()` 的落点。
         *
         * 设 setSupportMultipleWindows(false) 时这类点击会被静默吞掉（DSH 前端确有
         * _blank 链接），所以这里反而要打开多窗口开关，把它接住。
         *
         * ⚠️ onCreateWindow **不提供目标 URL**，只给一个 WebViewTransport。标准做法是
         * 塞一个临时 WebView 进去，让它在自己的 WebViewClient 里先收到那次导航，
         * 我们再从那儿拿 URL 决定去向（本窗打开 / 系统浏览器），随后销毁它。
         */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      android.os.Message resultMsg) {
            final WebView probe = new WebView(MainActivity.this);
            probe.getSettings().setJavaScriptEnabled(false);
            probe.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                    routeNewWindow(req.getUrl().toString());
                    return true;
                }

                @SuppressWarnings("deprecation")
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    routeNewWindow(url);
                    return true;
                }
            });
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = callback;
            boolean multi = params != null
                    && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
            try {
                // 用 ACTION_GET_CONTENT（而非 OPEN_DOCUMENT）：前者能看到相册/相机等
                // 应用入口，后者只能浏览文档提供器，选图时很别扭。
                Intent content = new Intent(Intent.ACTION_GET_CONTENT);
                content.addCategory(Intent.CATEGORY_OPENABLE);
                content.setType("*/*");
                String[] accept = params != null ? params.getAcceptTypes() : null;
                if (accept != null && accept.length > 0 && accept[0] != null
                        && !accept[0].isEmpty()) {
                    // DSH 传 accept 时可借此缩小到图片等类别（"image/*" 这类通配也支持）
                    content.setType(accept[0]);
                }
                if (multi) {
                    content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }

                // 额外给一个「拍照」入口：很多 ROM 的 GET_CONTENT 里没有相机，
                // 而用户传图时拍照是高频操作。
                Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (capture.resolveActivity(getPackageManager()) == null) {
                    startActivityForResult(Intent.createChooser(content, "选择文件"),
                            REQ_FILE);
                    return true;
                }
                photoUri = createPhotoUri();
                if (photoUri == null) {
                    startActivityForResult(Intent.createChooser(content, "选择文件"),
                            REQ_FILE);
                    return true;
                }
                capture.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                // ⚠️ provider 是 exported=false，相机属于别的应用、默认没权限；
                // 必须显式把读写权限授给它，否则写不进文件（或相机直接报错）。
                // photoUri 还要放进 clipData，权限才会随 chooser 一起传播。
                capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(content, "选择文件");
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{capture});
                chooser.setClipData(android.content.ClipData.newRawUri("capture", photoUri));
                chooser.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(chooser, REQ_FILE);
                return true;
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
        }
    }

    /** 给「拍照」准备一个输出 Uri（FileProvider；相册目录，避免申请存储权限）。 */
    private Uri createPhotoUri() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "capture");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            java.io.File f = new java.io.File(dir,
                    "dsh-" + System.currentTimeMillis() + ".jpg");
            return Uri.parse("content://" + getPackageName()
                    + CaptureProvider.AUTHORITY_SUFFIX + "/" + f.getName());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 新窗口请求的最终去向：同站留在主 WebView，异站丢系统浏览器。
     * 由 DshChrome.onCreateWindow 里那个临时 WebView 回调过来。
     */
    private void routeNewWindow(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            String host = Uri.parse(url).getHost();
            String self = Uri.parse(currentUrl()).getHost();
            if (host != null && Urls.sameSite(host, self)) {
                web.loadUrl(url);               // 同站：就在本窗打开，不另开
                return;
            }
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            web.loadUrl(url);                   // 没应用能处理就自己开
        }
    }

    // ───────────────────────── 下载 ─────────────────────────

    /**
     * 下载交给系统 DownloadManager，而不是丢给浏览器 —— 用户反馈"直接跳浏览器太草率"。
     *
     * 关键点：WebView 自己不带 cookie，直接交给系统下载会 401；
     * 所以把 CookieManager 里的 Cookie 原样转给 DownloadManager
     * （同域服务的鉴权就走这条链：nginx 下发的 auth cookie → 同域服务）。
     */
    private class DshDownload implements DownloadListener {

        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null || url.startsWith("blob:")) {
                Toast.makeText(MainActivity.this, "该下载方式暂不支持", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!ensureStoragePermission()) {
                return;
            }
            try {
                String cookies = CookieManager.getInstance().getCookie(url);
                // ⚠️ 不能用 android.webkit.URLUtil.guessFileName：它不认 RFC 5987 的
                // `filename*=utf-8''...`，而 同域服务 只发这一种，Content-Type 又恒为
                // application/octet-stream ⇒ 解析不到名字就按 MIME 猜测，octet-stream
                // 恰好映射成 .bin，结果**所有文件都存成 .bin**。用 Filenames 自己解析。
                final String name = Filenames.resolve(contentDisposition, url, mimeType);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                if (mimeType != null && !mimeType.isEmpty()) {
                    req.setMimeType(mimeType);
                }
                req.addRequestHeader("User-Agent", userAgent);
                req.addRequestHeader("Cookie", cookies == null ? "" : cookies);
                req.addRequestHeader("Referer", url);
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                // 这个名字不参与下载本身，只影响"文件名对不对"，出问题时便于排查
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    req.setDescription(name);
                }
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) {
                    throw new IllegalStateException("no DownloadManager");
                }
                dm.enqueue(req);
                Toast.makeText(MainActivity.this,
                        getString(R.string.dl_started) + "：" + name,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, R.string.dl_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** API 29 以下写公共下载目录要权限；被拒就退化为系统浏览器，别静默失败。 */
    private boolean ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(
                new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        return false;                        // 授权后才能下，先不启动
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_STORAGE && results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "已授权，请再点一次下载", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK) {
                if (data != null && data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                } else if (photoUri != null) {
                    // 走的是「拍照」分支：相机把图写进了我们给的 Uri，data 为 null
                    result = new Uri[]{photoUri};
                }
            }
            photoUri = null;
            final Uri[] picked = result;
            if (picked == null || picked.length == 0) {
                finishFileChoice(null);
                return;
            }
            if (compressUploads) {
                // 压缩必须离开主线程：12MP 解码+编码要几百毫秒~数秒，
                // 放主线程会把界面卡死（比不压还糟）。
                new Thread(() -> {
                    Uri[] out = new Uri[picked.length];
                    for (int i = 0; i < picked.length; i++) {
                        ImageCompressor.Result r =
                                ImageCompressor.compress(MainActivity.this, picked[i]);
                        out[i] = r.uri;
                        if (r.compressed) {
                            Log.i(TAG, "已压缩: " + r.detail);
                        }
                    }
                    runOnUiThread(() -> finishFileChoice(out));
                }, "dsh-compress").start();
                return;
            }
            finishFileChoice(picked);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 把最终选择的 Uri 交回 WebView（可能是压缩后的，也可能是原图）。 */
    private void finishFileChoice(Uri[] uris) {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        }
        // 清掉旧的临时文件，否则 cache 会一直涨
        trimCaptureCache();
        ImageCompressor.trim(this, 6);
    }

    /**
     * 只保留最近 3 张拍照临时文件。
     * 同样跳过刚写的那几个 —— 刚拍完就删会让 WebView 读不到内容。
     */
    private void trimCaptureCache() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "capture");
            java.io.File[] files = dir.listFiles();
            if (files == null || files.length <= 3) {
                return;
            }
            java.util.Arrays.sort(files, (a, b) ->
                    Long.compare(b.lastModified(), a.lastModified()));   // 新的在前
            long now = System.currentTimeMillis();
            int kept = 0;
            for (java.io.File f : files) {
                // 刚拍的（WebView 可能还在读）一律保留，否则会出现「拍了却传不上去」
                if (now - f.lastModified() < ImageCompressor.TRIM_GRACE_MS) {
                    continue;
                }
                kept++;
                if (kept <= 3) {
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
            // 清理失败无所谓，不能因此影响上传
        }
    }

    // ───────────────────────── 对话框 ─────────────────────────

    /** 首次进入：必须先把地址填了才继续。地址不合格会带着原输入重新弹，避免停在白屏。 */
    private void showFirstRunDialog() {
        showFirstRunDialog("");
    }

    private void showFirstRunDialog(final String prefill) {
        Ui.Card card = Ui.card(this, false);
        LinearLayout col = card.content;
        col.addView(Ui.title(this, getString(R.string.first_title)));
        TextView msg = Ui.body(this, getString(R.string.first_msg));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 10);
        col.addView(msg, mlp);

        final EditText input = Ui.addField(col, this, getString(R.string.url_label),
                getString(R.string.url_hint), prefill, false);
        TextView go = Ui.primaryButton(this, getString(R.string.enter));
        TextView quit = Ui.ghostButton(this, getString(R.string.exit));
        Ui.addButtons(col, this, quit, go);
        go.setOnClickListener(v -> {
            card.dialog.dismiss();
            resolveUrl(input.getText().toString(), url -> {
                if (url != null) {
                    loadUrl(url);
                } else {
                    showFirstRunDialog(input.getText().toString());
                }
            });
        });
        quit.setOnClickListener(v -> finish());
        showCard(card);
        Ui.showKeyboard(input);
    }

    private void showUrlDialog() {
        Ui.Card card = Ui.card(this, true);
        LinearLayout col = card.content;
        col.addView(Ui.title(this, getString(R.string.url_title)));
        TextView msg = Ui.body(this, getString(R.string.url_msg));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 10);
        col.addView(msg, mlp);

        final EditText input = Ui.addField(col, this, getString(R.string.url_label),
                getString(R.string.url_hint), currentUrl(), false);
        TextView save = Ui.primaryButton(this, getString(R.string.save));
        TextView cancel = Ui.ghostButton(this, getString(R.string.cancel));
        Ui.addButtons(col, this, cancel, save);
        save.setOnClickListener(v -> resolveUrl(input.getText().toString(), url -> {
            if (url == null) {
                return;                          // 保留卡片，用户可继续改
            }
            card.dialog.dismiss();
            pendingUser = null;
            credsRejected = false;
            prefs.edit().remove(Config.K_USER).remove(Config.K_PASS).apply();
            loadUrl(url);
        }));
        cancel.setOnClickListener(v -> card.dialog.dismiss());
        showCard(card);
        Ui.showKeyboard(input);
    }

    /** 地址校验通过后的回调；url 为 null 表示用户放弃或输入不合法。 */
    private interface OnUrlReady {
        void ready(String url);
    }

    /**
     * 校验并规范化待加载地址，合格才回调。
     *
     * http 不禁止（内网自建场景真实存在），但会明确警告 DSH 在非安全上下文下功能受限 ——
     * 这是用户明确指出的点，不能默默放过。
     */
    private void resolveUrl(String raw, final OnUrlReady cb) {
        final String u = Urls.normalize(raw);
        if (u == null) {
            Toast.makeText(this, R.string.url_invalid, Toast.LENGTH_LONG).show();
            cb.ready(null);
            return;
        }
        if (u.startsWith("http://")) {
            Ui.Card card = Ui.card(this, true);
            LinearLayout col = card.content;
            col.addView(Ui.title(this, getString(R.string.http_title)));
            TextView msg = Ui.body(this, getString(R.string.http_msg));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = Ui.dp(this, 10);
            col.addView(msg, mlp);
            TextView cont = Ui.primaryButton(this, getString(R.string.continue_anyway));
            TextView back = Ui.ghostButton(this, getString(R.string.go_back));
            Ui.addButtons(col, this, back, cont);
            // 回调只能触发一次：被顶掉、按返回、点按钮都算「已答复」，
            // 否则 cb 可能被调用两次，导致重复弹出首次进入卡片。
            final boolean[] done = {false};
            cont.setOnClickListener(v -> {
                done[0] = true;
                card.dialog.dismiss();
                cb.ready(u);
            });
            back.setOnClickListener(v -> {
                done[0] = true;
                card.dialog.dismiss();
                cb.ready(null);
            });
            card.dialog.setOnDismissListener(d -> {
                if (!done[0]) {
                    cb.ready(null);
                }
            });
            showCard(card);
            return;
        }
        cb.ready(u);
    }

    private void showAuthDialog() {
        Ui.Card card = Ui.card(this, false);
        LinearLayout col = card.content;
        col.addView(Ui.title(this, getString(R.string.auth_title)));
        TextView msg = Ui.body(this, getString(R.string.auth_msg));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 10);
        col.addView(msg, mlp);

        final EditText user = Ui.addField(col, this, getString(R.string.auth_user_label),
                getString(R.string.auth_user), prefs.getString(Config.K_USER, ""), false);
        final EditText pass = Ui.addField(col, this, getString(R.string.auth_pass_label),
                getString(R.string.auth_pass), "", true);
        final CheckBox remember = Ui.addCheck(col, this, getString(R.string.auth_remember),
                prefs.getBoolean(Config.K_REMEMBER, true));

        TextView login = Ui.primaryButton(this, getString(R.string.login));
        TextView cancel = Ui.ghostButton(this, getString(R.string.cancel));
        Ui.addButtons(col, this, cancel, login);
        login.setOnClickListener(v -> {
            String u = user.getText().toString();
            String p = pass.getText().toString();
            if (remember.isChecked()) {
                prefs.edit().putString(Config.K_USER, u)
                        .putString(Config.K_PASS, p)
                        .putBoolean(Config.K_REMEMBER, true).apply();
            } else {
                prefs.edit().putBoolean(Config.K_REMEMBER, false)
                        .remove(Config.K_PASS).apply();
            }
            pendingUser = u;
            pendingPass = p;
            card.dialog.dismiss();
            web.reload();
        });
        cancel.setOnClickListener(v -> card.dialog.dismiss());
        showCard(card);
        if (user.getText().length() == 0) {
            Ui.showKeyboard(user);
        } else {
            Ui.showKeyboard(pass);
        }
    }

    /**
     * 把崩溃堆栈直接画在屏幕上（没 adb 时唯一能拿到堆栈的办法）。
     * 只用 ScrollView + TextView，不依赖任何可能有问题的主题/资源；自己也包了 try。
     */
    private void showCrashScreen(String stack) {
        try {
            ScrollView sv = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setTextSize(11f);
            tv.setTextColor(0xFF0F172A);
            tv.setTextIsSelectable(true);
            int pad = Ui.dp(this, 14);
            tv.setPadding(pad, pad * 2, pad, pad * 2);
            tv.setText("壳启动失败 —— 请把这一屏截图发给开发者\n\n"
                    + "Android " + Build.VERSION.SDK_INT
                    + " · WebView " + webViewVersion() + "\n\n" + stack);
            sv.setBackgroundColor(0xFFFFFFFF);
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {
            // 连崩溃屏都画不出来就只能认了
        }
    }

    private void showErrorDialog(String detail) {
        if (errorDialogShowing || currentUrl().isEmpty()) {
            return;
        }
        errorDialogShowing = true;
        // 加载页留着并说明情况，别退化成一片白（用户报过"莫名其妙白屏"）
        if (loading != null) {
            loading.fail("连接失败", "检查地址与网络后重试；点按本页可重新加载。");
        }
        Ui.Card card = Ui.card(this, true);
        LinearLayout col = card.content;
        col.addView(Ui.title(this, "连不上 DSH"));
        TextView msg = Ui.body(this, detail + "\n\n" + currentUrl());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 10);
        col.addView(msg, mlp);

        TextView retry = Ui.primaryButton(this, getString(R.string.retry));
        TextView edit = Ui.ghostButton(this, getString(R.string.reenter));
        Ui.addButtons(col, this, edit, retry);
        retry.setOnClickListener(v -> {
            errorDialogShowing = false;
            card.dialog.dismiss();
            showLoading("正在连接");
            web.reload();
        });
        edit.setOnClickListener(v -> {
            errorDialogShowing = false;
            card.dialog.dismiss();
            showUrlDialog();
        });
        // 被顶掉/按返回也要复位，否则 errorDialogShowing 永远为 true，之后再也弹不出错误提示
        card.dialog.setOnDismissListener(d -> errorDialogShowing = false);
        showCard(card);
    }

    private void showMenu() {
        String host = currentUrl().replace("https://", "").replace("http://", "")
                .replaceAll("/+$", "");
        if (host.isEmpty()) {
            host = "未设置地址";
        }
        Ui.Card card = Ui.card(this, true);
        LinearLayout col = card.content;
        col.addView(Ui.title(this, "DSH"));
        TextView sub = Ui.body(this, host);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 6);
        col.addView(sub, slp);

        addMenuRow(col, "重新加载", "刷新当前页面", v -> {
            card.dialog.dismiss();
            web.reload();
        });
        addMenuRow(col, "修改地址", "换一个 DSH 入口", v -> {
            card.dialog.dismiss();
            showUrlDialog();
        });
        SettingsPanel.addEntryRow(this, col, () -> {
            card.dialog.dismiss();
            showSettings();
        });
        addMenuRow(col, "清除账号密码", "同时清掉登录 Cookie", v -> {
            card.dialog.dismiss();
            clearCredsAndReload();
        });
        showCard(card);
    }

    /** 打开设置面板（桌面快捷方式与返回键菜单都走这里）。 */
    private void showSettings() {
        SettingsPanel.show(this);
        // 面板里改了开关后，onResume 会重新读；这里先同步一次状态
        compressUploads = prefs.getBoolean(Config.K_COMPRESS, true);
    }

    /** 菜单行：标题 + 说明，整行可点。 */
    private void addMenuRow(LinearLayout col, String label, String desc,
                            View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int ph = Ui.dp(this, 14);
        row.setPadding(ph, ph, ph, ph);
        row.setBackground(Ui.round(Ui.field(), 14f, this));
        row.setClickable(true);
        row.setFocusable(true);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16f);
        t.setTextColor(Ui.text());
        t.setIncludeFontPadding(false);
        row.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12.5f);
        d.setTextColor(Ui.sub());
        d.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 3);
        row.addView(d, dlp);

        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        col.addView(row, lp);
    }

    // ───────────────────────── 按键 ─────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }
        // 有卡片打开时，返回键先交给卡片（关闭它），不要拿去后退网页。
        // 卡片是 Dialog，本来会自己消费返回键，但这里 overriding 了 dispatchKeyEvent
        // 会把事件拦在自己手里，所以必须显式让路。
        if (activeCard != null && activeCard.dialog.isShowing()) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                activeCard.dialog.onBackPressed();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                backDownAt = System.currentTimeMillis();
                longPressFired = false;
            } else if (!longPressFired
                    && System.currentTimeMillis() - backDownAt > LONG_PRESS_MS) {
                longPressFired = true;
                showMenu();
            }
            return true;
        }
        long duration = System.currentTimeMillis() - backDownAt;
        longPressFired = false;
        if (duration > LONG_PRESS_MS) {
            return true; // 已被长按消费
        }
        if (web.canGoBack()) {
            web.goBack();
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < DOUBLE_BACK_MS) {
            finish();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        backgroundedAt = System.currentTimeMillis();
        syncSeq++;                       // 让在途的 idle 回调失效
        if (syncing != null) {
            syncing.hide();
        }
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        // 切回来时 DSH 前端要重连并重拉会话历史，这几秒内容不刷新、看起来像死了。
        // 注意：**主线程这段时间通常是空的**（实测"思考中"计时器照常跳），瓶颈在网络 ——
        // 所以收起判据看的是网络活动，不是主线程（见 THEME_SCRIPT ⑤）。
        // 这里延迟一点点再决定要不要提示（短促的同步不闪），收起时机交给页面的 idle 回调。
        if (backgroundedAt == 0L || System.currentTimeMillis() - backgroundedAt < SYNC_MIN_BACKGROUND_MS) {
            return;
        }
        final int seq = ++syncSeq;
        // 不再"先弹出来再说"：只有页面观测到真的在传数据，才会回调 busy 让我们显示。
        // （之前无条件延迟 300ms 显示，导致锁屏解锁这种没有任何流量的场景也弹 1.5 秒。）
        handler.postDelayed(() -> {
            if (seq == syncSeq) {
                syncing.hide();          // 兜底：万一页面回了 busy 但没回 done
            }
        }, SYNC_GIVE_UP_MS);
        web.evaluateJavascript("window.__dshWatchNet&&window.__dshWatchNet(" + seq + ")", null);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
