# 个人账单申请辅助脚本

`bill_export.py` 在已解锁、已登录的 Android 手机上，使用 ADB 点击微信或支付宝的个人账单申请页面，并用本机 OCR 校验选中的日期。脚本不读取支付密码，不绕过刷脸或短信验证，不保存截图，也不直接读取两个应用的内部数据库。付款平台调整界面后，脚本会因文字或日期核对失败而停止。

当前坐标仅在 **vivo V2309A，1260×2800** 上校准。手机需要开启 USB 调试、授权电脑，且运行时保持屏幕解锁。一次只能连接一台授权设备。Python 3.9 或更新版本可用。

## 安装依赖

在项目目录的 PowerShell 中执行：

```powershell
py -m venv .bill-export-venv
.\.bill-export-venv\Scripts\python.exe -m pip install -r tools\requirements-bill-export.txt
```

需要已安装 Android `platform-tools`，并使 `adb` 位于 `PATH`。也可以在命令中使用 `--adb C:\path\to\platform-tools\adb.exe`。

## 申请

先以默认模式核对手机上的日期；默认模式不会点击最终提交按钮：

```powershell
.\.bill-export-venv\Scripts\python.exe tools\bill_export.py wechat 2026-09-01 2026-10-08
.\.bill-export-venv\Scripts\python.exe tools\bill_export.py alipay 2026-09-01 2026-10-08
```

确认无误后加 `--submit`。脚本在最终提交前会要求输入完整的应用和日期区间：

```powershell
.\.bill-export-venv\Scripts\python.exe tools\bill_export.py wechat 2026-09-01 2026-10-08 --submit
.\.bill-export-venv\Scripts\python.exe tools\bill_export.py alipay 2026-09-01 2026-10-08 --submit
```

微信的“下一步”之后可能要求本人刷脸、选择接收邮箱并再次提交；这些步骤需在手机上完成。支付宝选“支付宝服务消息”作为接收方式，点击“下一步”即提交，通常稍后在支付宝服务消息中收到文件。申请完成不代表文件已到达，也不会自动导入 SpendWatch；收到后仍需在 App 的“导入”页选择文件。

脚本只支持最近一年内且结束日期不晚于手机所在当天的区间。日期首尾均包含。运行期间不要手动切换应用；如果脚本停止，先检查手机停留的页面，再从命令开头重试。
