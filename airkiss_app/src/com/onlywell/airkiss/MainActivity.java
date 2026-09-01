package com.onlywell.airkiss;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
 * Onlywell 表芯 Airkiss 配网发送端（手机版，v1.12）。
 * 用法：手机连上 2.4G WiFi → 表芯长按 WIFI 键 3 秒(秒针到 12)进入监听态
 *      → 本应用自动读取当前 SSID 并判断频段(5G 会警告) → 填密码 → 点发送
 *      → 发送后监听设备 ACK 回播(端口 10000, SO_REUSEADDR 防 EADDRINUSE)；ACK 载荷为 MAC 非 IP，用 MAC 唯一标识设备。
 *      → v1.11：同时显示 ACK 回播包的源 IP 地址(IP 头源地址，即设备发包所用地址)，与 MAC 一并展示。
 *      → v1.12：密码输入框改为明文显示(textVisiblePassword)，方便核对；实测源 IP 即设备 LAN IP。
 *      → v1.13：① 根治端口 10000 泄漏(finally 关闭 rs)，bind 失败不再无引导；② 拆分 BindException 提示「强行停止/等 35 秒」；
 *               ③ 发送侧对全 1 广播 255.255.255.255 的 EPERM 自动切换为子网定向广播兜底。
 *      → v1.14：① 界面标题显示版本号(vX.Y)，便于核对装的是哪一版；② 修正 EADDRINUSE 引导——强制停止本应用仍占用说明占用者是微信/安信可IoT 等别的配网应用，引导强制停止对应应用或重启；并说明广播不受影响。
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

    /** 自动读取当前 WiFi 的 SSID 与频段，填好输入框，并对 5G 给出红色警告 */
    private void fillNetworkInfo() {
        if (wifiManager == null) return;
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) {
            tvBand.setTextColor(0xFFCC0000);
            tvBand.setText("未连接到 WiFi，请先连上 WiFi");
            return;
        }
        String ssid = info.getSSID();
        if (ssid != null) ssid = ssid.replace("\"", "");
        int freq = info.getFrequency(); // MHz，API 21+

        // 自动填充 SSID（仅当能读到且不是占位值）
        if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equals(ssid)) {
            etSsid.setText(ssid);
        } else {
            tvBand.setTextColor(0xFFCC0000);
            tvBand.setText("无法读取 SSID：请在系统设置授予本应用“位置”权限后重开应用");
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
