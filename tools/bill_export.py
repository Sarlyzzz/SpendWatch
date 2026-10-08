"""Assist personal WeChat/Alipay bill export on the owner's connected Android phone.

The UI was calibrated on a vivo V2309A at 1260x2800 in October 2026. OCR and
post-tap checks make the script stop when a provider changes its UI. Screenshots
are processed in memory and are never written to disk by this script.
"""

from __future__ import annotations

import argparse
import io
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR


REFERENCE_SIZE = (1260, 2800)
PACKAGES = {"wechat": "com.tencent.mm", "alipay": "com.eg.android.AlipayGphone"}


class FlowError(RuntimeError):
    pass


@dataclass(frozen=True)
class TextBox:
    text: str
    x: int
    y: int
    confidence: float


def find_adb(explicit: str | None) -> str:
    candidates = [
        explicit,
        shutil.which("adb"),
        str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"),
        str(Path.home() / "AppData/Local/Temp/codex-android-build/sdk/platform-tools/adb.exe"),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise FlowError("找不到 adb；请使用 --adb 指定 platform-tools/adb.exe")


class Phone:
    def __init__(self, adb: str):
        self.adb = adb
        self.ocr_engine = RapidOCR()
        devices = self.run("devices").splitlines()[1:]
        ready = [line.split()[0] for line in devices if "\tdevice" in line]
        if len(ready) != 1:
            raise FlowError(f"需要恰好一台已授权的手机，当前检测到 {len(ready)} 台")
        self.serial = ready[0]
        size = self.run("shell", "wm", "size")
        match = re.search(r"Physical size: (\d+)x(\d+)", size)
        if not match or tuple(map(int, match.groups())) != REFERENCE_SIZE:
            raise FlowError(f"脚本只校准了 {REFERENCE_SIZE[0]}x{REFERENCE_SIZE[1]}，当前为 {size.strip()}")

    def run(self, *args: str, binary: bool = False):
        result = subprocess.run([self.adb, *args], capture_output=True, check=False)
        if result.returncode:
            raise FlowError(f"adb 命令失败：{' '.join(args)}")
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def foreground(self, package: str):
        focus = self.run("shell", "dumpsys", "window")
        if not re.search(r"mCurrentFocus=.*" + re.escape(package), focus):
            raise FlowError("手机已离开目标支付应用，停止操作")

    def launch(self, package: str):
        self.run("shell", "monkey", "-p", package, "1")
        time.sleep(1.5)
        self.foreground(package)

    def home(self, package: str, marker: str):
        """Return from a resumed bill page to the app's main tab bar."""
        for _ in range(9):
            if any(box.text == marker for box in self.ocr((0, 2450, 1260, 2770))):
                return
            self.back(package)
        raise FlowError("无法返回支付应用首页，请手动打开首页后重试")

    def tap(self, x: int, y: int, package: str):
        self.foreground(package)
        self.run("shell", "input", "tap", str(x), str(y))
        time.sleep(0.45)

    def swipe(self, x: int, y1: int, y2: int, package: str):
        self.foreground(package)
        self.run("shell", "input", "swipe", str(x), str(y1), str(x), str(y2), "300")
        time.sleep(0.22)

    def back(self, package: str):
        self.foreground(package)
        self.run("shell", "input", "keyevent", "4")
        time.sleep(0.55)

    def image(self) -> Image.Image:
        raw = self.run("exec-out", "screencap", "-p", binary=True)
        return Image.open(io.BytesIO(raw)).convert("RGB")

    def ocr(self, crop: tuple[int, int, int, int] | None = None) -> list[TextBox]:
        image = self.image()
        ox, oy = 0, 0
        if crop:
            ox, oy = crop[:2]
            image = image.crop(crop)
        scale = 3 if crop else 1
        if crop:
            image = image.resize((image.width * scale, image.height * scale))
        result, _ = self.ocr_engine(np.asarray(image))
        boxes = []
        for points, label, confidence in result or []:
            x = round(sum(point[0] for point in points) / 4 / scale + ox)
            y = round(sum(point[1] for point in points) / 4 / scale + oy)
            boxes.append(TextBox(label.replace(" ", ""), x, y, float(confidence)))
        return boxes

    def wait_text(self, target: str, *, exact: bool = False, timeout: float = 12,
                  region: tuple[int, int, int, int] | None = None) -> TextBox:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            candidates = self.ocr(region)
            found = next((box for box in candidates if box.confidence >= 0.65 and
                          (box.text == target if exact else target in box.text)), None)
            if found:
                return found
            time.sleep(0.5)
        raise FlowError(f"页面未出现预期文字“{target}”，可能是加载缓慢或界面已改变")

    def tap_text(self, target: str, package: str, *, exact: bool = False,
                 region: tuple[int, int, int, int] | None = None):
        box = self.wait_text(target, exact=exact, region=region)
        self.tap(box.x, box.y, package)

    def wheel_value(self, column_x: int, center_y: int) -> int:
        boxes = self.ocr((column_x - 105, center_y - 55,
                          column_x + 105, center_y + 55))
        parsed = [(abs(box.y - center_y), int(match.group()))
                  for box in boxes if (match := re.search(r"\d+", box.text))]
        if not parsed:
            raise FlowError("日期滚轮当前值无法读取，停止操作")
        return min(parsed)[1]

    def set_wheel(self, column_x: int, center_y: int, target: int, package: str,
                  maximum_steps: int):
        current = self.wheel_value(column_x, center_y)
        for _ in range(maximum_steps):
            if current == target:
                return
            direction = 1 if target > current else -1
            offset = 75
            start = center_y + offset if direction > 0 else center_y - offset
            end = center_y - offset if direction > 0 else center_y + offset
            self.swipe(column_x, start, end, package)
            following = self.wheel_value(column_x, center_y)
            if following != current + direction:
                raise FlowError(f"日期滚轮未按预期变化：{current} → {following}")
            current = following
        raise FlowError("日期滚轮步数超出预期，停止操作")

    def set_date(self, wanted: date, package: str, center_y: int):
        for column_x, target, max_steps in ((210, wanted.year, 2),
                                            (630, wanted.month, 12),
                                            (1030, wanted.day, 31)):
            self.set_wheel(column_x, center_y, target, package, max_steps)
        actual = tuple(self.wheel_value(x, center_y) for x in (210, 630, 1030))
        if actual != (wanted.year, wanted.month, wanted.day):
            raise FlowError(f"日期选择校验失败：读回 {actual}")


def validate_range(start: date, end: date):
    today = date.today()
    if start > end or end > today or start < today - timedelta(days=366):
        raise FlowError("日期必须在最近一年内，开始日期不得晚于结束日期，结束日期不得晚于今天")
    if (end - start).days > 366:
        raise FlowError("单次账单跨度不得超过一年")


def confirm_submission(source: str, start: date, end: date):
    expected = f"{source} {start.isoformat()} {end.isoformat()}"
    print(f"即将实际申请账单：{expected}")
    if input(f"请在手机核对后，输入完整文字“{expected}”继续：").strip() != expected:
        raise FlowError("未确认提交，脚本已停止")


def wechat(phone: Phone, start: date, end: date, submit: bool):
    pkg = PACKAGES["wechat"]
    phone.launch(pkg)
    phone.home(pkg, "通讯录")
    phone.wait_text("通讯录", region=(0, 2450, 1260, 2750))
    phone.tap(1100, 2665, pkg)
    phone.tap_text("服务", pkg, exact=True)
    phone.wait_text("钱包", exact=True)
    phone.tap_text("钱包", pkg, exact=True)
    phone.tap_text("账单", pkg, exact=True, region=(900, 80, 1260, 350))
    phone.wait_text("全部账单")
    phone.tap(1180, 210, pkg)
    phone.tap_text("下载账单", pkg, exact=True)
    phone.tap_text("用于个人对账", pkg, exact=True)
    phone.wait_text("下载账单流水")
    phone.tap_text("自定义时间", pkg, exact=True)
    phone.wait_text("开始日期")
    phone.set_date(start, pkg, 1910)
    phone.tap(950, 1320, pkg)
    phone.wait_text("结束日期")
    phone.set_date(end, pkg, 1910)
    phone.tap_text("确定", pkg, exact=True, region=(600, 2320, 1200, 2570))
    lines = [box.text for box in phone.ocr()]
    expected_start = f"{start.year}年{start.month}月{start.day}日"
    expected_end = f"{end.month}月{end.day}日"
    if not any(expected_start in line and expected_end in line for line in lines):
        raise FlowError("微信页面的日期汇总与目标区间不一致")
    print(f"微信日期已核对：{start} 至 {end}。当前停在“下一步”之前。")
    if submit:
        confirm_submission("wechat", start, end)
        phone.tap_text("下一步", pkg, exact=True)
        print("已进入微信下一步；请在手机完成可能出现的身份验证、接收方式和最终提交。")


def alipay(phone: Phone, start: date, end: date, submit: bool):
    pkg = PACKAGES["alipay"]
    phone.launch(pkg)
    phone.home(pkg, "我的")
    phone.wait_text("我的", exact=True, region=(950, 2450, 1260, 2770))
    phone.tap(1120, 2650, pkg)
    phone.tap_text("账单", pkg, exact=True, region=(0, 500, 1260, 1050))
    phone.wait_text("搜索交易记录")
    phone.tap(1180, 215, pkg)
    phone.tap_text("开具交易流水证明", pkg)
    phone.wait_text("选择申请用途")
    phone.tap_text("申请", pkg, exact=True, region=(0, 2200, 1260, 2700))
    phone.wait_text("选择交易流水范围")
    phone.tap_text("自定义", pkg, exact=True)
    phone.tap_text("开始日期", pkg, exact=True)
    phone.wait_text("选择时间")
    phone.set_date(start, pkg, 2325)
    phone.tap_text("确定", pkg, exact=True, region=(900, 1680, 1260, 1940))
    phone.tap_text("结束日期", pkg, exact=True)
    phone.wait_text("选择时间")
    phone.set_date(end, pkg, 2325)
    phone.tap_text("确定", pkg, exact=True, region=(900, 1680, 1260, 1940))
    lines = [box.text for box in phone.ocr()]
    for wanted in (start.isoformat(), end.isoformat()):
        if wanted not in lines:
            raise FlowError(f"支付宝页面未显示目标日期 {wanted}")
    if not any("接收方式" in line for line in lines):
        phone.swipe(630, 2200, 1250, pkg)
    phone.tap_text("接收方式", pkg, exact=True)
    phone.wait_text("选择接收方式")
    phone.tap_text("支付宝", pkg, exact=True, region=(0, 1700, 1260, 2200))
    phone.wait_text("下一步", exact=True)
    lines = [box.text for box in phone.ocr()]
    if not all(wanted in lines for wanted in (start.isoformat(), end.isoformat())):
        raise FlowError("提交前支付宝日期再次核对失败")
    print(f"支付宝日期已核对：{start} 至 {end}，接收方式为支付宝服务消息。")
    if submit:
        confirm_submission("alipay", start, end)
        phone.tap_text("下一步", pkg, exact=True)
        phone.wait_text("提交成功", timeout=20)
        print("支付宝账单申请已提交；可在支付宝服务消息中获取文件。")
    else:
        print("当前停在“下一步”之前；支付宝此按钮会直接提交账单申请。")


def main() -> int:
    parser = argparse.ArgumentParser(description="辅助申请个人微信／支付宝账单")
    parser.add_argument("source", choices=PACKAGES)
    parser.add_argument("start", type=date.fromisoformat, help="开始日期 YYYY-MM-DD")
    parser.add_argument("end", type=date.fromisoformat, help="结束日期 YYYY-MM-DD")
    parser.add_argument("--adb", help="adb.exe 的绝对路径")
    parser.add_argument("--submit", action="store_true", help="核对后实际发起申请；默认停在提交前")
    args = parser.parse_args()
    try:
        validate_range(args.start, args.end)
        phone = Phone(find_adb(args.adb))
        (wechat if args.source == "wechat" else alipay)(phone, args.start, args.end, args.submit)
        return 0
    except (FlowError, ValueError) as error:
        print(f"已安全停止：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
