# -*- coding: utf-8 -*-
"""加强版 QR 解码：pyzbar + cv2 多预处理，针对带中心 logo 的小尺寸二维码"""
import sys
import cv2
import numpy as np
from pyzbar.pyzbar import decode as zbar_decode

img_path = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\heyp\AppData\Local\Temp\qr.png"
img0 = cv2.imread(img_path)
if img0 is None:
    print("ERR: cannot read", img_path); sys.exit(1)
h, w = img0.shape[:2]
print(f"IMG size={w}x{h}")
gray = cv2.cvtColor(img0, cv2.COLOR_BGR2GRAY)

found = set()

def try_pyzbar(name, im):
    try:
        res = zbar_decode(im)
        if res:
            for r in res:
                txt = r.data.decode("utf-8", "ignore")
                print(f"[{name}] pyzbar OK type={r.type} -> {txt!r}")
                found.add(txt)
            return True
    except Exception as e:
        print(f"[{name}] pyzbar ERR {e}")
    return False

# 1. 原图灰度
try_pyzbar("orig_gray", gray)
# 2. 彩色原图
try_pyzbar("orig_color", img0)

# 3. 多倍放大 + 多种阈值
for fx in (2, 3, 4, 6):
    big = cv2.resize(gray, None, fx=fx, fy=fx, interpolation=cv2.INTER_CUBIC)
    # Otsu
    _, t1 = cv2.threshold(big, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    try_pyzbar(f"{fx}x_otsu", t1)
    # 自适应
    t2 = cv2.adaptiveThreshold(big, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 25, 5)
    try_pyzbar(f"{fx}x_adapt", t2)
    # 反相
    try_pyzbar(f"{fx}x_otsu_inv", cv2.bitwise_not(t1))
    try_pyzbar(f"{fx}x_adapt_inv", cv2.bitwise_not(t2))

# 4. 中心 logo 修补（inpaint）后解码
for fx in (2, 4):
    bigc = cv2.resize(img0, None, fx=fx, fy=fx, interpolation=cv2.INTER_CUBIC)
    bh, bw = bigc.shape[:2]
    mask = np.zeros((bh, bw), np.uint8)
    cv2.rectangle(mask, (int(bw*0.32), int(bh*0.42)), (int(bw*0.68), int(bh*0.58)), 255, -1)
    inp = cv2.inpaint(bigc, mask, 5, cv2.INPAINT_TELEA)
    ig = cv2.cvtColor(inp, cv2.COLOR_BGR2GRAY)
    _, t3 = cv2.threshold(ig, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    try_pyzbar(f"{fx}x_inpaint_otsu", t3)
    try_pyzbar(f"{fx}x_inpaint_otsu_inv", cv2.bitwise_not(t3))

print("---")
print("UNIQUE RESULTS:", len(found))
for x in found:
    print(repr(x))
