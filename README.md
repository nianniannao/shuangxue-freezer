# 霜雪 · Shuangxue

> 本项目为个人使用方便，与 AI 协同制作。

一款面向 Android 的免 Root 应用冻结工具，针对 Android 15 / 努比亚（nubia）ROM 深度适配，当前版本 **v1.14**。

「霜雪」可将指定应用彻底冻结（先强制停止进程，再停用 / 隐藏组件），支持锁屏自动冻结、退出应用自动冻结、定时冻结，并提供桌面快捷方式一键解冻启动。图标为深蓝冰晶底 + 白色六角雪花。

> 前身名为「蝾螈」，v1.13 起全面更名为「霜雪」。

---

## 特性

- **彻底冻结**：先 `am force-stop` 进程，再 `pm disable-user --user 0`（Shizuku / Root）或隐藏组件（设备所有者），比普通冰箱类工具更干净。
- **三种工作模式**，按需选择：
  - **设备所有者（Device Owner）**：一次激活永久有效，重启不失活；
  - **Root**：自动识别，开箱即用；
  - **Shizuku**：不要求删除手机账号，重启手机后用无线调试重新激活一次即可。
- **三种自动冻结策略**：
  - 锁屏后延迟 N 秒自动冻结（监听解锁，已排程但用户提前解锁则自动取消，不会"正在用应用却被冻"）；
  - 切到后台延迟 N 秒自动冻结（需"使用情况访问"权限）；
  - 每隔 N 分钟定时冻结。
- **冻结结果复查**：每次自动冻结后逐个反查真实状态，没冻上的自动重试一次，仍失败的应用名写入诊断日志。
- **安全防护**：桌面、SystemUI、设置、电话、短信、输入法、Google 服务等核心组件标红「危险」，加入冻结列表时二次确认，防止误冻成砖。
- **用户 / 系统应用切换**：应用页左上角一键切换，可自由管理系统自带应用。
- **桌面快捷方式**：标准 Shortcut，任意第三方桌面可固定，点击先解冻再启动（带轮询等待系统就绪 + 自愈补解冻）。
- **启动提速**：应用列表后台静默加载，图标懒加载 + 进程级缓存，再次打开秒开。
- **完整诊断**：设置页内置「自动冻结诊断」「测试自动冻结」「检查未冻结项」「账号诊断」「忽略电池优化」直达按钮。

## 截图

（欢迎在此处补充应用截图）

## 下载

Release 页面提供已签名 APK（targetSdk 35，适配 Android 15）。首次安装请允许"安装未知来源应用"。

## 快速开始

1. 安装 APK 后打开「霜雪」。
2. 按右上角菜单激活一种模式：
   - 手机有账号 → 安装 [Shizuku](https://shizuku.rikka.app/) 并通过无线调试启动，本 App 选「仅用 Shizuku 冻结」；
   - 已 Root → 直接刷新列表即可；
   - 想永久激活 → 电脑 adb 执行下方命令。
3. 在「应用」页勾选要管理的应用加入冻结列表。
4. 在「设置」页开启锁屏 / 退出 / 定时自动冻结，并务必点「忽略电池优化」允许后台运行。

### ADB 激活设备所有者

> 手机需先退出所有账号（含厂商云服务）、清除锁屏密码与指纹。

```bash
adb shell dpm set-device-owner com.freezer.app/.DeviceAdminReceiver
```

提示 `Success: Device owner set` 即成功。卸载本 App 前请先在系统设置中移除设备管理员授权。

## 构建

环境要求：JDK 17+、Android SDK（compileSdk 35）。

```bash
# 在工程根目录
./gradlew assembleRelease
```

产物为未签名 APK：`app/build/outputs/apk/release/app-release-unsigned.apk`。

## 签名说明

本仓库**不包含签名私钥**（`signing/*.jks`、`signing/aligned.apk` 已在 `.gitignore` 中排除，请勿上传）。首次自行构建请生成新密钥：

```bash
keytool -genkeypair -v -keystore signing/freezer.jks \
  -alias freezer -keyalg RSA -keysize 2048 -validity 10000
```

再用 `zipalign` + `apksigner` 完成 v1/v2/v3 签名。

> 更换签名密钥后将无法覆盖安装旧版本（需先卸载）。如需保留数据，请妥善保管原密钥。

## 目录结构

```
app/src/main/java/com/freezer/app/
├── MainActivity.kt        主界面（首页 / 应用 / 设置 / 关于 四个 Tab）
├── FreezerEngine.kt       冻结 / 解冻引擎，Shizuku 反射调用
├── AutoFreezeHelper.kt    自动冻结执行器 + 诊断日志 + 复查重试
├── AutoFreezeService.kt   前台服务，动态注册锁屏 / 解锁广播
├── FreezeAlarmReceiver.kt AlarmManager 精确闹钟，触发前校验屏幕状态
├── ScreenOffReceiver.kt   锁屏 / 解锁监听
├── AppLauncher.kt         解冻 + 启动一条龙（轮询等待系统就绪）
├── UnfreezeActivity.kt    桌面快捷方式入口
├── DeviceAdminReceiver.kt 设备管理员接收器
├── BootReceiver.kt        开机自启恢复前台服务
├── CrashHandler.kt        崩溃兜底，日志落盘 files/crash/
└── App.kt                 Application，初始化崩溃捕获
```

详细使用说明、常见问题与版本历史见仓库内 [使用说明.md](./使用说明.md)。

## 技术要点

- 包名：`com.freezer.app`，minSdk 24 / targetSdk 35。
- 依赖仅引入 Shizuku `api` + `provider`（13.1.5），无其他三方库。
- 锁屏广播在 Android 8+ 必须动态注册，故放在常驻前台服务内；锁屏冻结走 `AlarmManager` + `WakeLock`，Doze 下也能触发。
- 解冻后系统 `PackageManager` 状态刷新是异步的，启动目标 Activity 前轮询等待（最多 2.5s），期间发现目标仍被冻结会自动补解冻。

## 更新记录

- **v1.14**：应用页左上角新增「用户应用 / 系统应用」切换；核心系统应用标红「危险」并加入冻结列表时二次确认。
- **v1.13**：品牌全面改为「霜雪」，图标统一；新增解锁监听取消未执行的延迟锁屏冻结；解冻 + 启动后台一条龙，界面乐观更新。
- **v1.12**：修复解冻后偶发打不开（轮询等待系统就绪 + 自动补解冻自愈）。
- **v1.11**：启动提速，列表后台静默加载，图标懒加载 + 进程缓存。
- **v1.10**：冻结后逐个复查真实状态 + 自动重试，新增「检查未冻结项」。
- **v1.9**：Shizuku 状态实时检测显示。
- **v1.8**：锁屏冻结改前台服务内动态注册广播，修复 Android 8+ 静态注册收不到 `SCREEN_OFF`。

## 许可

仅供个人学习与自用，请勿用于恶意目的。使用本工具造成的任何系统异常由使用者自行承担。
