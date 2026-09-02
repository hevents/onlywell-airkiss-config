package com.onlywell.airkiss;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Onlywell 表芯 Airkiss 配网发送端（手机版，v1.1）。
 * 用法：手机连上 2.4G WiFi → 表芯长按 WIFI 键 3 秒(秒针到 12)进入监听态
 *      → 本应用自动读取当前 SSID 并判断频段(5G 会警告) → 填密码 → 点发送
 *      → 发送后监听设备 ACK 回播(端口 10000, SO_REUSEADDR 防 EADDRINUSE)；ACK 载荷为 MAC 非 IP，用 MAC 唯一标识设备。
 *      → v1.11：同时显示 ACK 回播包的源 IP 地址(IP 头源地址，即设备发包所用地址)，与 MAC 一并展示。
 *      → v1.12：密码输入框改为明文显示(textVisiblePassword)，方便核对；实测源 IP 即设备 LAN IP。
 *      → v1.13：① 根治端口 10000 泄漏(finally 关闭 rs)，bind 失败不再无引导；② 拆分 BindException 提示「强行停止/等 35 秒」；
 *               ③ 发送侧对全 1 广播 255.255.255.255 的 EPERM 自动切换为子网定向广播兜底。
 *      → v1.14：① 界面标题显示版本号(vX.Y)，便于核对装的是哪一版；② 修正 EADDRINUSE 引导——强制停止本应用仍占用说明占用者是微信/安信可IoT 等别的配网应用，引导强制停止对应应用或重启；并说明广播不受影响。
 *      → v1.1：WiFi 刷新——打开时连的是 5G、去系统设置切到 2.4G 后回到本页自动重读当前 WiFi；
 *               页面上部下拉手势也可手动刷新；用户手动改过 SSID 框则刷新不覆盖其输入，只更新频段提示行。
 *      → v1.2：下拉刷新完整动画——拖拽时刷新头部跟手展开(超阈值后半程 0.5x 阻尼)，提示文字随高度切换
 *               「↓ 下拉刷新」/「↑ 松开立即刷新」，松手回弹动画；达到阈值松手才触发刷新(转圈+「正在刷新…」)，
 *               完成后头部平滑收起；未达阈值松手则收起不刷新。
 *      → v1.3：修复下拉后顶部提示第一行被顶出屏幕——① 头部布局去掉负 margin(部分 ROM 上测量异常)；
 *               ② 拖拽激活死区 12px→28px(轻碰不再误触)；③ 兜底归零：松手/onResume 时头部高度>0 一律强制收起，
 *               保证任何路径下内容都回到原始位置。
 */
public class MainActivity extends Activity {

    private static final int REQ_LOC = 1001;

    private EditText etSsid;
    private EditText etPwd;
    private TextView tvLog;
    private TextView tvBand;
    private TextView tvStatus;
    private ProgressBar pbSend;
    private Button btnSend;
    private WifiManager wifiManager;

    /** ACK 监听器检测到的设备 MAC（volatile：监听器线程写入、主线程读取） */
    private volatile String ackMac = null;

    /** v1.11：ACK 回播包的源 IP 地址（IP 头源地址，即设备发包所用地址；volatile 同上） */
    private volatile String ackIp = null;

    /** v1.1: 用户是否手动编辑过 SSID 输入框（编辑过后刷新不再覆盖其输入，只更新频段提示行） */
    private boolean ssidEditedByUser = false;

    /** v1.1: 程序自动 setText 期间抑制 TextWatcher，避免被误判为用户手动编辑 */
    private boolean suppressSsidWatcher = false;

    /** v1.1: 首次 onResume 标志——首次初始化由 onCreate/权限回调负责（此时定位权限可能尚未批准），跳过 */
    private boolean firstResume = true;

    /** v1.1: 下拉手势的起点坐标（仅起点位于屏幕上部时记录，-1 表示无效）与拖拽激活标志 */
    private float touchStartX = -1f, touchStartY = -1f;
    private boolean pullTriggered = false;

    /** v1.2: 下拉刷新头部三件套——头部容器、刷新中转圈、提示文字 */
    private LinearLayout refreshHeader;
    private ProgressBar pbRefresh;
    private TextView tvRefreshHint;

    /** v1.2: 刷新中标志（头部展开转圈期间禁止再次触发，防重入） */
    private boolean refreshing = false;

    /** v1.2: 松手触发刷新的高度阈值(px，72dp) 与头部最大高度(px，110dp)，onCreate 里按密度换算 */
    private int triggerPx = 0, maxPx = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etSsid = findViewById(R.id.et_ssid);
        etPwd = findViewById(R.id.et_pwd);
        tvLog = findViewById(R.id.tv_log);
        tvBand = findViewById(R.id.tv_band);
        tvStatus = findViewById(R.id.tv_status);
        pbSend = findViewById(R.id.pb_send);
        btnSend = findViewById(R.id.btn_send);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        // v1.2: 下拉刷新头部三件套 + 阈值按屏幕密度换算(72dp 触发 / 110dp 封顶)
        refreshHeader = findViewById(R.id.refresh_header);
        pbRefresh = findViewById(R.id.pb_refresh);
        tvRefreshHint = findViewById(R.id.tv_refresh_hint);
        float density = getResources().getDisplayMetrics().density;
        triggerPx = (int) (72 * density);
        maxPx = (int) (110 * density);

        // v1.1: 监听用户对 SSID 框的手动编辑（程序 setText 期间抑制），编辑过后刷新不再覆盖其输入
        etSsid.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressSsidWatcher) {
                    ssidEditedByUser = true;
                }
            }
        });

        // v1.14: 标题栏显示版本号（从 PackageManager 取 versionName），方便核对当前装的是哪一版
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            setTitle("Onlywell 配网工具 V" + ver);
        } catch (Exception e) {
            setTitle("Onlywell 配网工具");
        }

        // 纯 B：每次重发先清掉上一轮日志，避免多轮结果挤在一起被底部截断
        btnSend.setOnClickListener(v -> {
            tvLog.setText("");
            new Thread(this::send).start();
        });

        // Android 10+ 读取 SSID 需要位置权限；在 UI 线程申请，结果回来后再填
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
            } else {
                fillNetworkInfo();
            }
        } else {
            fillNetworkInfo();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC) {
            fillNetworkInfo();
        }
    }

    /**
     * v1.1: 从系统设置切网（如 5G → 2.4G）回到本应用时，自动重新读取当前 WiFi 并刷新界面。
     * 首次 onResume 跳过——首次初始化由 onCreate/权限回调负责，避免权限尚未批准时的重复读取与误报。
     * v1.3: 兜底归零——若因任何异常路径导致刷新头部滞留在非 0 高度（内容被顶出原始位置），回到前台时强制收起复位。
     */
    @Override
    protected void onResume() {
        super.onResume();
        // v1.3: 头部高度>0 且不在刷新流程中 = 异常滞留，立即归零复位
        if (!refreshing && refreshHeader != null && refreshHeader.getLayoutParams().height > 0) {
            setHeaderHeight(0);
        }
        if (firstResume) {
            firstResume = false;
            return;
        }
        fillNetworkInfo(false);
    }

    /**
     * v1.2: 下拉刷新拖拽手势（完整动画版）。
     * 交互模型：页面上部起手向下拖 → 刷新头部跟手展开（提示「↓ 下拉刷新」，拉过 72dp 阈值变「↑ 松开立即刷新」，
     * 超阈值后半程 0.5x 阻尼，封顶 110dp）→ 松手：达到阈值则回弹到刷新位开始刷新（转圈+「正在刷新…」，
     * 完成后头部平滑收起）；未达阈值则收起不刷新。
     * 经 dispatchTouchEvent 只监听不拦截（事件仍正常分发给子控件），不影响按钮/输入框交互；
     * 仅当起点位于屏幕上部 40% 区域且不在刷新中时启用，避免与密码框长按拖选等手势冲突。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 起点在屏幕上部且当前不在刷新中，才算候选下拉手势；否则标记无效
                if (!refreshing && ev.getY() < getResources().getDisplayMetrics().heightPixels * 0.4f) {
                    touchStartX = ev.getX();
                    touchStartY = ev.getY();
                    pullTriggered = false;
                } else {
                    touchStartX = touchStartY = -1f;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (touchStartY >= 0 && !refreshing) {
                    float dy = ev.getY() - touchStartY;
                    float dx = Math.abs(ev.getX() - touchStartX);
                    // 激活判定：明显向下(v1.3: 死区 12px→28px，约 9dp，轻碰不再误触；dy>2|dx| 且激活后手指
                    // 回滑也继续跟手，可回收取消)
                    if (!pullTriggered && dy > 28f && dy > dx * 2f) {
                        pullTriggered = true;
                    }
                    if (pullTriggered) {
                        updateHeaderHeight(dy);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touchStartY >= 0) {
                    touchStartX = touchStartY = -1f;
                    if (pullTriggered && !refreshing) {
                        if (refreshHeader.getLayoutParams().height >= triggerPx) {
                            startRefreshWithAnim();   // 达到阈值：回弹到刷新位并触发
                        } else {
                            animateHeaderTo(0, null); // 未达阈值：收起，不刷新
                        }
                    }
                }
                // v1.3: 兜底归零——无论手势状态如何，只要不在刷新流程中且头部高度>0（异常滞留），
                // 一律强制收起，保证内容回到原始位置（修复"第一行再也看不到"）
                if (!refreshing && refreshHeader.getLayoutParams().height > 0 && !pullTriggered) {
                    animateHeaderTo(0, null);
                }
                pullTriggered = false;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * v1.2: 拖拽中根据手指位移更新头部高度（跟手）。
     * 阈值内 1:1 跟手；超过阈值后半程 0.5x 阻尼（SwipeRefreshLayout 同款手感）；封顶 maxPx；
     * 手指回滑(dy 变小/变负)时高度同步回收，提示文字随是否达到阈值切换。
     */
    private void updateHeaderHeight(float dy) {
        int h;
        if (dy <= triggerPx) {
            h = (int) dy;
        } else {
            h = (int) (triggerPx + (dy - triggerPx) * 0.5f);
        }
        h = Math.max(0, Math.min(h, maxPx));
        setHeaderHeight(h);
        tvRefreshHint.setText(h >= triggerPx ? "↑ 松开立即刷新" : "↓ 下拉刷新");
    }

    /** v1.2: 直接设置头部高度(px)并触发重排（内容区被自然下推） */
    private void setHeaderHeight(int px) {
        ViewGroup.LayoutParams lp = refreshHeader.getLayoutParams();
        if (lp.height != px) {
            lp.height = px;
            refreshHeader.setLayoutParams(lp);
        }
    }

    /**
     * v1.2: 头部高度补间动画（收起/回弹共用），减速插值器，时长随距离自适应。
     * onEnd 在动画结束时回调（可空）。
     */
    private void animateHeaderTo(int target, Runnable onEnd) {
        int from = refreshHeader.getLayoutParams().height;
        if (from == target) {
            if (onEnd != null) onEnd.run();
            return;
        }
        ValueAnimator va = ValueAnimator.ofInt(from, target);
        va.setDuration(Math.max(150, Math.abs(target - from)));
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(anim -> setHeaderHeight((Integer) anim.getAnimatedValue()));
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onEnd != null) onEnd.run();
            }
        });
        va.start();
    }

    /**
     * v1.2: 松手达到阈值后进入刷新：头部回弹到触发高度 → 显示转圈与「正在刷新…」→ 执行刷新
     * （fillNetworkInfo 为毫秒级同步操作）→ 至少停留 600ms 让刷新状态可见 → 平滑收起头部。
     */
    private void startRefreshWithAnim() {
        if (refreshing) return;
        refreshing = true;
        animateHeaderTo(triggerPx, () -> {
            pbRefresh.setVisibility(View.VISIBLE);
            tvRefreshHint.setText("正在刷新…");
            fillNetworkInfo(true);
            refreshHeader.postDelayed(() -> {
                pbRefresh.setVisibility(View.GONE);
                // v1.3: 收起结束后强制精确归零（动画取整误差/异常路径的最后一道保险），内容必定复位
                animateHeaderTo(0, () -> {
                    setHeaderHeight(0);
                    refreshing = false;
                });
            }, 600);
        });
    }

    /** 自动读取当前 WiFi 的 SSID 与频段，填好输入框，并对 5G 给出红色警告（启动路径） */
    private void fillNetworkInfo() {
        fillNetworkInfo(false);
    }

    /**
     * 读取当前 WiFi 的 SSID 与频段并刷新界面。
     * v1.1: fromRefresh=true 表示由下拉手势触发，附 Toast 反馈；onResume 自动刷新为静默。
     * 用户手动编辑过 SSID 框时不覆盖其输入，只更新频段提示行。
     */
    private void fillNetworkInfo(boolean fromRefresh) {
        if (wifiManager == null) return;
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) {
            tvBand.setTextColor(0xFFCC0000);
            tvBand.setText("未连接到 WiFi，请先连上 WiFi");
            if (fromRefresh) Toast.makeText(this, "当前未连接 WiFi", Toast.LENGTH_SHORT).show();
            return;
        }
        String ssid = info.getSSID();
        if (ssid != null) ssid = ssid.replace("\"", "");
        int freq = info.getFrequency(); // MHz，API 21+

        // 自动填充 SSID（仅当能读到且不是占位值）
        if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equals(ssid)) {
            // v1.1: 用户手动编辑过 SSID 框则不覆盖（避免冲掉手输内容），只更新频段提示行
            if (!ssidEditedByUser) {
                suppressSsidWatcher = true;   // 抑制 TextWatcher，程序填充不算用户编辑
                etSsid.setText(ssid);
                suppressSsidWatcher = false;
            }
        } else {
            tvBand.setTextColor(0xFFCC0000);
            tvBand.setText("无法读取 SSID：请在系统设置授予本应用“位置”权限后重开应用");
            if (fromRefresh) Toast.makeText(this, "无法读取 WiFi 名称（位置权限未授予）", Toast.LENGTH_SHORT).show();
            return;
        }

        // 频段判断
        String band;
        int color;
        if (freq >= 4900 && freq <= 5900) {
            band = "5G";
            color = 0xFFCC0000; // 红
        } else if (freq > 0 && freq < 3000) {
            band = "2.4G";
            color = 0xFF006600; // 绿
        } else {
            band = "未知";
            color = 0xFF888800;
        }
        tvBand.setTextColor(color);
        if ("5G".equals(band)) {
            tvBand.setText("⚠️ 当前是 5G WiFi（" + freq + "MHz），表芯只支持 2.4G，请先切到 2.4G！");
        } else {
            tvBand.setText("当前 WiFi：" + ssid + "（" + band + " " + freq + "MHz）");
        }
        if (fromRefresh) Toast.makeText(this, "已刷新：" + ssid + "（" + band + "）", Toast.LENGTH_SHORT).show();
    }

    private void log(final String s) {
        runOnUiThread(() -> tvLog.append(s + "\n"));
    }

    /** 取本机在 WiFi 上的 IP（点分字符串），用于 ACK 监听时排除自身回环 */
    private String getLocalIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            byte[] b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ip).array();
            return String.format("%d.%d.%d.%d", b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF);
        } catch (Exception e) {
            return null;
        }
    }

    /** 监听设备 ACK 回播：Onlywell 固件设备联网后向 :10000 回播 [random(1B)] + [MAC(6B)]（载荷不含 IP；
     *  v1.11 起同时取 UDP 包 IP 头源地址作为设备 IP 展示）。
     *  v1.4 修复：旧版 setSoTimeout(6000) 在广播开始即起算，而设备需收满约 2.5 遍序列(~8~11s)才解出密码，
     *  窗口第 6s 就关 → 必然漏接。现改为持续接收到 deadline（覆盖整段广播 + 联网余量）。 */
    private void listenAck(int randomChar, String localIp, long deadline) {
        // v1.13: rs 提到 try 外，finally 中保证关闭，根治端口泄漏导致的 EADDRINUSE 难自愈
        DatagramSocket rs = null;
        try {
            // v1.5: 用 SO_REUSEADDR 绑定 10000，规避上一次残留监听/其他进程占用导致 EADDRINUSE
            rs = new DatagramSocket(null);
            rs.setReuseAddress(true);
            rs.bind(new java.net.InetSocketAddress(10000));
            rs.setBroadcast(true);
            byte[] buf = new byte[64];
            int loops = 0;
            while (System.currentTimeMillis() < deadline) {
                int wait = (int) Math.min(1000, deadline - System.currentTimeMillis());
                if (wait <= 0) break;
                rs.setSoTimeout(wait);
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    rs.receive(p);
                    String src = p.getAddress().getHostAddress();
                    if (localIp != null && localIp.equals(src)) continue; // 忽略本机回环
                    if ("255.255.255.255".equals(src) || "0.0.0.0".equals(src)) continue; // 忽略广播/空源回环
                    loops++; // 仅统计真正的非本机包（排除自身广播回环，避免计数虚高）
                    if ((buf[0] & 0xFF) == randomChar) {
                        // v1.6: Onlywell 固件 ACK 载荷 = random(1B) + MAC(6B)，不含 IP。
                        // 旧版按 IP 解析 buf[1..4] 会把 MAC 前缀当成 IP(如 92.207.127.45 = 5C:CF:7F:2D)。
                        StringBuilder mac = new StringBuilder();
                        for (int k = 1; k <= 6 && k < p.getLength(); k++) {
                            if (mac.length() > 0) mac.append(':');
                            mac.append(String.format("%02X", buf[k] & 0xFF));
                        }
                        ackMac = mac.toString();
                        // v1.11：记录 ACK 回播包的源 IP（IP 头源地址）。
                        // 载荷里不含 IP(只有 random+MAC)，但设备联网后才发包，源地址即其网卡地址，
                        // 大概率就是 DHCP 分配的 LAN IP；与安信可 IoT 小程序显示 IP 的原理一致。
                        ackIp = src;
                        final String matchedMac = ackMac;
                        final String matchedIp = ackIp;
                        // 即时反馈：ACK 一收到立刻把状态栏置绿，不必等广播跑完
                        runOnUiThread(() -> {
                            tvStatus.setTextColor(0xFF006600);
                            tvStatus.setText("✅ 已收到设备回播　MAC=" + matchedMac + "　IP=" + matchedIp);
                        });
                        // 把整段 ACK 原始字节打出来，便于核对是否还携带 IP
                        StringBuilder hex = new StringBuilder();
                        for (int k = 0; k < p.getLength(); k++) hex.append(String.format("%02X ", buf[k] & 0xFF));
                        log("ACK 原始字节: " + hex.toString().trim() + "（回播源 IP=" + ackIp + "）");
                        rs.close();
                        return;
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // 单轮超时，继续等下一窗口直到 deadline
                }
            }
            rs.close();
            log("ACK 监听窗口结束，共收到 " + loops + " 个非本机包（均未匹配 random）");
        } catch (java.net.BindException e) {
            // v1.14: 端口 10000 被其他进程占用。已强制停止本应用仍占用，说明占用者不是本应用，
            // 而是同样走 Airkiss 的其他应用（如微信/安信可IoT 小程序的后台进程，它们也用 10000 收设备回播）。
            // 代码无法抢占别的进程的端口，只能引导用户释放占用者或重启。注意：广播不受影响，仍照常发出。
            runOnUiThread(() -> {
                tvStatus.setTextColor(0xFFB00000);
                tvStatus.setText("❌ 端口 10000 被占用：多为微信/安信可IoT 等配网类应用占着。请强制停止「微信」等应用或重启手机后再试；广播仍会照常发出，可去路由器看 ESP_ 设备是否上线。");
            });
            log("ACK 监听异常(BindException/EADDRINUSE): 端口 10000 被其他进程占用（非本应用）。常见为微信/安信可IoT 等同样使用 10000 的配网应用。请强制停止对应应用或重启手机释放端口。广播不受影响，仍可在路由器侧确认 ESP_ 设备上线。");
        } catch (Exception e) {
            log("ACK 监听异常: " + e);
        } finally {
            // v1.13: 无论成功/异常都关闭 rs，防止 socket 泄漏致使端口长期被占
            if (rs != null && !rs.isClosed()) {
                try { rs.close(); } catch (Exception ignore) {}
            }
        }
    }

    private void send() {
        ackMac = null;
        ackIp = null; // v1.11：每轮重发前清空上一轮的源 IP，避免串轮
        String ssid = etSsid.getText().toString().trim();
        String pwd = etPwd.getText().toString();
        if (ssid.isEmpty()) {
            log("请填写 WiFi 名称(SSID)");
            return;
        }

        // 发送前再次确认频段，5G 直接拦截，避免必然失败的广播
        if (wifiManager != null) {
            WifiInfo info = wifiManager.getConnectionInfo();
            int freq = info != null ? info.getFrequency() : 0;
            if (freq >= 4900 && freq <= 5900) {
                log("❌ 当前是 5G WiFi，表芯无法识别，已停止发送。请切到 2.4G 后重试。");
                runOnUiThread(() -> Toast.makeText(this, "请先切换到 2.4G WiFi", Toast.LENGTH_LONG).show());
                return;
            }
        }

        runOnUiThread(() -> {
            tvStatus.setTextColor(0xFF000000);
            tvStatus.setText("准备发送…");
        });

        log("开始编码: SSID=" + ssid + " 密码长度=" + pwd.length());
        AirKissEncoder.EncodeResult r = AirKissEncoder.encode(ssid, pwd);
        log("编码完成: " + r.frames.size() + " 个帧, random=" + r.randomChar);

        final int n = r.frames.size();
        runOnUiThread(() -> {
            pbSend.setVisibility(View.VISIBLE);
            pbSend.setMax(n);
            pbSend.setProgress(0);
            btnSend.setEnabled(false);
        });

        // 先启动 ACK 监听（排除本机回环），与广播并发；窗口覆盖整段广播 + 联网余量
        final String localIp = getLocalIp();
        final long t0 = System.currentTimeMillis();
        final long listenDeadline = t0 + (long) n * 6 + 10000L; // 广播(~n×4ms,实测~22s) + 10s 联网余量
        final Thread listener = new Thread(() -> listenAck(r.randomChar, localIp, listenDeadline));
        listener.start();

        try {
            DatagramSocket sock = new DatagramSocket();
            sock.setBroadcast(true);
            InetAddress addr = InetAddress.getByName("255.255.255.255");
            int port = 10000;
            // v1.13: 计算子网定向广播地址作为兜底（将本机 IP 末段替换为 255）。
            // 部分 OEM 内核会对全 1 广播 255.255.255.255 直接返回 EPERM，定向广播可绕开该限制。
            String subnetBc = null;
            if (localIp != null && localIp.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
                int lastDot = localIp.lastIndexOf('.');
                subnetBc = localIp.substring(0, lastDot + 1) + "255";
            }
            boolean useFallback = false;
            log("开始广播(每帧 4ms, 约 " + (n * 4 / 1000.0) + " 秒)...");
            runOnUiThread(() -> tvStatus.setText("广播中…（请保持表芯秒针停在 12）"));
            for (int i = 0; i < n; i++) {
                int v = r.frames.get(i);          // 0~511，作为本帧 UDP 负载字节数
                byte[] payload = new byte[v];
                DatagramPacket p = new DatagramPacket(payload, payload.length, addr, port);
                try {
                    sock.send(p);
                } catch (java.io.IOException sendEx) {
                    // v1.13: 全 1 广播被拒(EPERM/Operation not permitted)，自动切换为子网定向广播重试一次
                    if (!useFallback && subnetBc != null) {
                        useFallback = true;
                        addr = InetAddress.getByName(subnetBc);
                        log("发送被拒(" + sendEx.getMessage() + ")，已切换为子网定向广播 " + subnetBc + " 重试");
                        sock.send(p);
                    } else {
                        throw sendEx;
                    }
                }
                Thread.sleep(4);
                // v1.14: 收到设备回播(ackMac 已被监听线程置位)则立即中止剩余广播帧，进度条随之停止
                if (ackMac != null) break;
                // 每 16 帧刷新一次进度条，减少 UI 抖动
                if ((i & 15) == 0 || i == n - 1) {
                    final int prog = i + 1;
                    runOnUiThread(() -> pbSend.setProgress(prog));
                }
            }
            sock.close();
            runOnUiThread(() -> pbSend.setProgress(n));
            if (ackMac != null) {
                // ACK 已在广播期间收到，不再显示"继续等待"（避免误导）
                log("广播完成：ACK 已在广播期间收到。");
            } else {
                runOnUiThread(() -> tvStatus.setText("广播完成，正在等待设备回播 ACK…"));
                log("广播完成，继续等待设备 ACK（最长约 " + ((long) n * 6 + 10000L) / 1000 + " 秒）");
            }
        } catch (Exception e) {
            log("发送异常: " + e);
        }

        // 等 ACK 监听线程自行按 deadline 退出（收到即提前 return）
        try {
            listener.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 检测阶段：仅依赖 ACK 回播（v1.5 移除 ARP 扫描；v1.6 确认 ACK 载荷是 MAC 非 IP，用 MAC 唯一标识设备）
        String deviceMac = ackMac;
        String deviceIp = ackIp; // v1.11：ACK 回播包源 IP（IP 头源地址）

        if (deviceMac != null) {
            final String foundMac = deviceMac;
            final String foundIp = deviceIp != null ? deviceIp : "未知";
            runOnUiThread(() -> {
                tvStatus.setTextColor(0xFF006600);
                tvStatus.setText("✅ 配网成功！\n表芯 MAC=" + foundMac + "\n表芯 IP=" + foundIp);
                pbSend.setVisibility(View.GONE);
            });
            log("👉 去微信重新扫码，选「跳过联网」绑定该设备");
        } else {
            runOnUiThread(() -> {
                tvStatus.setTextColor(0xFFCC0000);
                tvStatus.setText("⚠️ 未收到设备回播，请重新发送配网广播试试（确认表芯处于监听态）");
            });
            log("未收到设备 ACK 回播。Onlywell 表芯配网成功必会回播，未收到多为表芯未处于监听态；请重新发送配网广播试试（先长按表芯 WIFI 键进监听，再立即点发送）。");
        }

        runOnUiThread(() -> btnSend.setEnabled(true));
    }
}
