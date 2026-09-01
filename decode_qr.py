# -*- coding: utf-8 -*-
"""QR 解码：针对带中心 logo 的二维码做多种预处理后用 cv2.QRCodeDetector 尝试解码"""
import sys, os
import cv2
import numpy as np

img_path = sys.argv[1] if len(sys.argv) > 1 else r"D:\@Herbert\Desktop\@Workbuddy\onlywell表芯WIFI\二维码.png"

img0 = cv2.imread(img_path)
if img0 is None:
    print("ERR: cannot read", img_path); sys.exit(1)
h, w = img0.shape[:2]
print(f"IMG size={w}x{h}")

def try_decode(name, bgr):
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY) if len(bgr.shape)==3 else bgr
    det = cv2.QRCodeDetector()
    data, pts, _ = det.detectAndDecode(g)
    ok = bool(data)
    print(f"[{name}] ok={ok} len={len(data) if data else 0} preview={data[:120] if data else ''!r}")
    return data if ok else None

results = []
# 1. 原图
r = try_decode("orig", img0);  results.append(r)

# 2. 灰度
gray = cv2.cvtColor(img0, cv2.COLOR_BGR2GRAY)
r = try_decode("gray", gray); results.append(r)

# 3. 放大 2x + 锐化 + Otsu
big = cv2.resize(gray, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
blur = cv2.GaussianBlur(big, (0,0), 1.0)
sharp = cv2.addWeighted(big, 1.6, blur, -0.6, 0)
_, thr = cv2.threshold(sharp, 0, 255, cv2.THRESH_BINARY+cv2.THRESH_OTSU)
r = try_decode("2x_sharp_otsu", thr); results.append(r)

# 4. 放大 3x + 自适应阈值
big3 = cv2.resize(gray, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC)
ath = cv2.adaptiveThreshold(big3, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 51, 5)
r = try_decode("3x_adaptive", ath); results.append(r)

# 5. 放大 2x + 闭运算填补 logo 黑洞
big2 = cv2.resize(img0, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
mask = np.zeros(big2.shape[:2], np.uint8)
mh, mw = big2.shape[:2]
cv2.rectangle(mask, (int(mw*0.30), int(mh*0.42)), (int(mw*0.70), int(mh*0.60)), 255, -1)
inpainted = cv2.inpaint(big2, mask, 5, cv2.INPAINT_TELEA)
g2 = cv2.cvtColor(inpainted, cv2.COLOR_BGR2GRAY)
_, th2 = cv2.threshold(g2, 0, 255, cv2.THRESH_BINARY+cv2.THRESH_OTSU)
r = try_decode("inpaint_logo_otsu", th2); results.append(r)

# 6. 放大 2x + inpaint + 自适应
ath2 = cv2.adaptiveThreshold(g2, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 61, 7)
r = try_decode("inpaint_logo_adaptive", ath2); results.append(r)

# 7. 黑白反相（有些 logo 是深色被误判）
inv = cv2.bitwise_not(th2)
r = try_decode("inpaint_inverted", inv); results.append(r)

# 8. 二维码区域检测 + 几何校正后解码（如果有 Aruco / WeChatQR）
try:
    wechat = cv2.wechat_qrcode_WeChatQRCode()
    res, pts = wechat.detectAndDecode(big3)
    if res:
        print(f"[wechat_qrcode] ok={True} count={len(res)} preview={[x[:80] for x in res]!r}")
        results.extend(res)
    else:
        print("[wechat_qrcode] no result (model files missing?)")
except Exception as e:
    print(f"[wechat_qrcode] ERR: {e}")

# 汇总
uniq = [x for x in results if x]
print("---")
print("UNIQUE RESULTS:", len(uniq))
for x in set(uniq):
    print(repr(x))