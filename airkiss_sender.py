# -*- coding: utf-8 -*-
"""
Onlywell 表芯(WiFi 石英钟) Airkiss 配网广播脚本
================================================
背景：
  Onlywell 表芯是微信生态的 ESP 设备，固件跑的是「微信 Airkiss」协议，
  而不是 Espressif 的 SmartConfig(EspTouch 用的就是 SmartConfig)。
  微信 App 因为后端/二维码票据过期，已经无法真正发起广播；
  本脚本直接把 SSID+密码 按 Airkiss 协议编码进 UDP 广播帧的「长度字段」打出去，
  不依赖任何微信服务器。

原理(来自微信 Airkiss 协议 + zhchbin/WeChatAirKiss 参考实现)：
  1) 设备先进入混杂监听模式(秒针走到12点)，sniff 空中的 802.11 帧；
  2) 发送端发出一串 UDP 广播包，每个包的「负载字节数」= 一个 9bit 编码值；
     接收端用 帧长度 - 基准长度 还原出编码值，再按协议解出 SSID/密码；
  3) 设备联网成功后，会向 255.255.255.255:10000 回播一个 random 字节作为确认。

编码结构(每帧的 9bit 值)：
  前导(leadingPart): [1,2,3,4] 重复 50 次  -> 让接收端算出 基准长度
  magicCode: 总长度 + SSID的CRC8，重复 20 次
  prefixCode: 密码长度 + 密码长度的CRC8
  sequence:  每 4 字节数据一段，含 序列头(CRC8+index) + 4 个数据帧
  上面整体外层再重复 5 次(提高可靠性)

使用：
  python airkiss_sender.py --ssid <你的WiFi名称> --password <你的WiFi密码>
可选：
  --bind-ip  指定从哪块网卡发出(默认自动探测名为 WLAN 的无线网卡 IP)
  --port     目标端口(默认 10000)
  --dry-run  只编码并打印，不真正发包(用于自检)
"""

import argparse
import random
import socket
import struct
import subprocess
import sys
import time


def crc8(data):
    """Airkiss 使用的 CRC-8(多项式 0x31, 反射形式 0x8C, 初值 0)。
    逐位实现，与参考 Java 代码交叉校验结果一致。"""
    crc = 0
    for byte in data:
        extract = byte & 0xFF
        for _ in range(8):
            s = (crc ^ extract) & 0x01
            crc = (crc & 0xFF) >> 1
            if s != 0:
                crc = (crc & 0xFF) ^ 0x8C
            extract = (extract & 0xFF) >> 1
    return crc & 0xFF


def encode(ssid, password):
    """返回编码后的整数列表(每个整数 = 一个 UDP 包的负载字节数)。"""
    random_char = random.randint(0, 0x7E)  # [0,127)
    data_str = password + chr(random_char) + ssid
    data_bytes = data_str.encode('utf-8', 'replace')

    out = []

    def append(v):
        out.append(v)

    # 前导：让接收端锁定信道并算出基准长度
    def leading_part():
        for _ in range(50):
            for j in range(1, 5):
                append(j)

    # magic code：总长度 + SSID 的 CRC8
    def magic_code():
        length = len(ssid) + len(password) + 1
        mc = [0] * 4
        mc[0] = 0x00 | ((length >> 4) & 0xF)
        if mc[0] == 0:
            mc[0] = 0x08
        mc[1] = 0x10 | (length & 0xF)
        c = crc8(ssid.encode('utf-8', 'replace'))
        mc[2] = 0x20 | ((c >> 4) & 0xF)
        mc[3] = 0x30 | (c & 0xF)
        for _ in range(20):
            for v in mc:
                append(v)

    # prefix code：密码长度 + 密码长度的 CRC8(标记数据段正式开始)
    def prefix_code():
        length = len(password)
        pc = [0] * 4
        pc[0] = 0x40 | ((length >> 4) & 0xF)
        pc[1] = 0x50 | (length & 0xF)
        c = crc8(bytes([length & 0xFF]))
        pc[2] = 0x60 | ((c >> 4) & 0xF)
        pc[3] = 0x70 | (c & 0xF)
        for v in pc:
            append(v)

    # sequence：4 字节一段(最后一段可不足4字节)，含序列头 + 数据帧
    def sequence(index, chunk):
        content = bytes([index & 0xFF]) + chunk
        c = crc8(content)
        append(0x80 | c)        # 序列头帧1：CRC8(控制帧)
        append(0x80 | index)    # 序列头帧2：index(控制帧)
        for b in chunk:
            append(0x100 | b)   # 数据帧(bit8=1 表示数据字段)

    for _ in range(5):  # 外层重复 5 次提高可靠性
        leading_part()
        magic_code()
        for _ in range(15):
            prefix_code()
            i = 0
            n = len(data_bytes)
            while i * 4 < n:
                chunk = data_bytes[i * 4:(i + 1) * 4]
                sequence(i, chunk)
                i += 1

    return out, random_char


def detect_wlan_ip():
    """在 Windows 上探测名为 WLAN 的无线网卡 IPv4 地址。"""
    try:
        out = subprocess.check_output(
            ['powershell', '-NoProfile', '-Command',
             "(Get-NetIPAddress -InterfaceAlias 'WLAN' -AddressFamily IPv4).IPAddress"],
            stderr=subprocess.DEVNULL,
        ).decode('utf-8', 'ignore')
        for line in out.splitlines():
            line = line.strip()
            if line and '.' in line:
                return line
    except Exception:
        pass
    return None


def send_airkiss(ssid, password, bind_ip, port, dry_run):
    encoded, random_char = encode(ssid, password)
    print("编码完成：共 %d 个帧，random=%d(0x%02X)" % (len(encoded), random_char, random_char))
    print("SSID=%s  密码长度=%d" % (ssid, len(password)))

    # 校验编码值范围(9bit，0~511)
    bad = [v for v in encoded if v < 0 or v > 511]
    if bad:
        print("错误：编码值超出 0~511 范围：%s" % bad[:10])
        return False

    if dry_run:
        print("[dry-run] 不真正发包。前 30 个帧长度：", encoded[:30])
        return True

    if not bind_ip:
        bind_ip = detect_wlan_ip()
    if not bind_ip:
        print("无法自动探测 WLAN 网卡 IP，请用 --bind-ip 手动指定(在 PowerShell 里用 "
              "Get-NetIPAddress -InterfaceAlias 'WLAN' -AddressFamily IPv4 查看)。")
        return False
    print("将从无线网卡 IP %s 发出广播(目的 %s:%d)" % (bind_ip, '255.255.255.255', port))

    # 发送套接字：绑定到 WLAN IP，确保广播从 WiFi 网卡出
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind((bind_ip, 0))
    except Exception as e:
        print("绑定 %s 失败：%s" % (bind_ip, e))
        sock.close()
        return False

    # 确认监听线程：设备联网后会向 :port 回播 random 字节
    confirm = {'count': 0, 'done': False}
    def listener():
        try:
            ls = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            ls.bind(('', port))
            ls.settimeout(1.0)
            while not confirm['done']:
                try:
                    data, _ = ls.recvfrom(1500)
                except socket.timeout:
                    continue
                confirm['count'] += data.count(bytes([random_char]))
                if confirm['count'] > 5:
                    confirm['done'] = True
                    break
            ls.close()
        except Exception as e:
            print("[listener] 异常：%s" % e)

    import threading
    t = threading.Thread(target=listener, daemon=True)
    t.start()

    print("开始广播(每帧间隔 4ms，预计约 %.1f 秒)..." % (len(encoded) * 0.004))
    start = time.time()
    dummy = b'\x00' * 512
    for i, v in enumerate(encoded):
        try:
            sock.sendto(dummy[:v], ('255.255.255.255', port))
        except Exception as e:
            if i < 5:
                print("发送异常：%s" % e)
        time.sleep(0.004)
        if confirm['done']:
            print("中途收到设备确认(random 回播)，提前结束。")
            break
    sock.close()
    elapse = time.time() - start
    print("广播结束，耗时 %.1f 秒。" % elapse)

    # 等一下确认线程
    t.join(timeout=3.0)
    if confirm['done']:
        print("★ Airkiss 配网成功：设备已回播确认(random=%d)。去路由器看 ESP_ 开头的设备是否拿到 IP。" % random_char)
    else:
        print("广播已发完，但未在本机收到设备回播确认。请去路由器 DHCP 看 ESP_ 开头的 Espressif 设备是否上线；"
              "若上线即成功(设备可能不回播或回播被路由丢弃)。")
    return True


def main():
    p = argparse.ArgumentParser(description="Onlywell 表芯 Airkiss 配网广播")
    p.add_argument('--ssid', default='', help='WiFi 名称(无默认，必填)')
    p.add_argument('--password', required=True, help='WiFi 密码')
    p.add_argument('--bind-ip', default=None, help='发出广播的无线网卡 IP(默认自动探测 WLAN)')
    p.add_argument('--port', type=int, default=10000, help='目标 UDP 端口(默认 10000)')
    p.add_argument('--dry-run', action='store_true', help='只编码不真正发包')
    args = p.parse_args()
    if not args.ssid:
        print("错误：必须指定 --ssid（WiFi 名称）。例如：")
        print("  python airkiss_sender.py --ssid 你的WiFi名 --password 你的WiFi密码")
        return
    send_airkiss(args.ssid, args.password, args.bind_ip, args.port, args.dry_run)


if __name__ == '__main__':
    main()
