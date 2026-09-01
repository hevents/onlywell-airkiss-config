package com.onlywell.airkiss;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Airkiss 编码（微信配网协议，纯本地、不依赖微信服务器）。
 * 编码逻辑参考 zhchbin/WeChatAirKiss 与 PC 端 airkiss_sender.py 实现，并交叉校验编码结果一致，
 * 仅把一个"9bit 编码值"列表交给发送端，由发送端把每个值当作一个 UDP 广播包的负载长度发出去。
 */
public class AirKissEncoder {

    /** Airkiss CRC-8：多项式 0x31 / 反射形式 0x8C / 初值 0。 */
    public static int crc8(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            int extract = b & 0xFF;
            for (int i = 0; i < 8; i++) {
                int s = (crc ^ extract) & 0x01;
                crc = (crc & 0xFF) >> 1;
                if (s != 0) crc = (crc & 0xFF) ^ 0x8C;
                extract = (extract & 0xFF) >> 1;
            }
        }
        return crc & 0xFF;
    }

    public static class EncodeResult {
        public List<Integer> frames = new ArrayList<>();
        public int randomChar;
    }

    /**
     * 把 SSID + 密码 编码成整数列表（每个整数 = 一个 UDP 广播包的负载字节数，范围 0~511）。
     */
    public static EncodeResult encode(String ssid, String password) {
        EncodeResult r = new EncodeResult();
        List<Integer> out = r.frames;
        Random rand = new Random();
        r.randomChar = rand.nextInt(0x7E); // [0, 126)

        // 拼接顺序与协议固定：password + random + ssid
        String dataStr = password + (char) r.randomChar + ssid;
        byte[] dataBytes = dataStr.getBytes(StandardCharsets.UTF_8);

        // magic：总长度 + SSID 的 CRC8
        int length = ssid.length() + password.length() + 1;
        int[] mc = new int[4];
        mc[0] = 0x00 | ((length >> 4) & 0xF);
        if (mc[0] == 0) mc[0] = 0x08;
        mc[1] = 0x10 | (length & 0xF);
        int c = crc8(ssid.getBytes(StandardCharsets.UTF_8));
        mc[2] = 0x20 | ((c >> 4) & 0xF);
        mc[3] = 0x30 | (c & 0xF);

        // prefix：密码长度 + 密码长度的 CRC8
        int plen = password.length();
        int[] pc = new int[4];
        pc[0] = 0x40 | ((plen >> 4) & 0xF);
        pc[1] = 0x50 | (plen & 0xF);
        int pcrc = crc8(new byte[]{(byte) (plen & 0xFF)});
        pc[2] = 0x60 | ((pcrc >> 4) & 0xF);
        pc[3] = 0x70 | (pcrc & 0xF);

        // 外层重复 5 次（提高可靠性）
        for (int outer = 0; outer < 5; outer++) {
            // 前导：让接收端锁定信道并算出基准长度
            for (int i = 0; i < 50; i++)
                for (int j = 1; j <= 4; j++) out.add(j);
            // magic
            for (int i = 0; i < 20; i++)
                for (int v : mc) out.add(v);
            // 15 段数据
            for (int k = 0; k < 15; k++) {
                for (int v : pc) out.add(v);
                int idx = 0;
                int n = dataBytes.length;
                while (idx * 4 < n) {
                    int len = Math.min(4, n - idx * 4);
                    byte[] chunk = new byte[len];
                    System.arraycopy(dataBytes, idx * 4, chunk, 0, len);
                    sequence(out, idx, chunk);
                    idx++;
                }
            }
        }
        return r;
    }

    /** 一段 4 字节数据：序列头(CRC8 + index) + 数据帧。 */
    private static void sequence(List<Integer> out, int index, byte[] chunk) {
        byte[] content = new byte[1 + chunk.length];
        content[0] = (byte) (index & 0xFF);
        System.arraycopy(chunk, 0, content, 1, chunk.length);
        int c = crc8(content);
        out.add(0x80 | c);            // 序列头帧1：CRC8(控制帧)
        out.add(0x80 | (index & 0xFF)); // 序列头帧2：index(控制帧)
        for (byte b : chunk) out.add(0x100 | (b & 0xFF)); // 数据帧(bit8=1)
    }
}
