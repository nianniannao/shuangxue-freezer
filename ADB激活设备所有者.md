# 用电脑 ADB 把「霜雪」激活成设备所有者（永久冻结）

> 设备所有者一旦设置成功：**永久有效、重启不掉、不需要 Shizuku、冻结最彻底**，这正是你要的效果。
>
> ⚠️ **重要**：设备所有者是"不可逆"的——设置后手机会显示"设备由某应用管理"，普通方式无法取消，**只能恢复出厂设置才能清除**。所以只在你这台专门用来冻结应用的手机上做，且确认后再执行。

组件名（已核对 App 配置，固定不变）：

```
com.freezer.app/com.freezer.app.DeviceAdminReceiver
```

---

## 第 0 步：连接手机

手机开启「开发者选项 → USB 调试」，用数据线连电脑。电脑上打开命令行（Windows 用 CMD/PowerShell），执行：

```
adb devices
```

- 看到一串序列号 + `device` = 连接成功
- 显示 `unauthorized` = 手机上弹的"允许 USB 调试"要点**确定**（勾选"一律允许"）
- 显示 `offline` = 拔插数据线，或杀掉 adb 重启：`adb kill-server` 再 `adb devices`

> 如果电脑提示 `adb 不是内部命令`，说明没装 adb。装法：搜"platform-tools 下载"，解压后把文件夹路径加入系统 PATH，或用"一键 adb 工具包"。

---

## 第 1 步：先诊断（搞清之前为什么失败）

依次执行这三条，把输出记下来：

```
adb shell pm list users
```

```
adb shell "dumpsys account | grep -i account"
```

```
adb shell "dumpsys device_policy | grep -i owner"
```

怎么看：

| 输出 | 含义 |
|---|---|
| `pm list users` 只有 `UserInfo{0:...}` 一行 | 单用户，正常 |
| `pm list users` 还有 `UserInfo{10/11/12:...}` | 有多用户/访客/应用双开，要清掉 |
| `dumpsys account` 里 `Accounts:` 下面有内容 | 有账号（Google/厂商云/邮箱等），要清掉 |
| `dumpsys device_policy` 显示已有 owner | 手机已被别的 App 设为设备所有者，要先移除 |

---

## 第 2 步：直接激活（先试这条，看报什么错）

```
adb shell dpm set-device-owner com.freezer.app/com.freezer.app.DeviceAdminReceiver
```

**成功**会显示：

```
Success: Device owner set to package ComponentInfo{com.freezer.app/com.freezer.app.DeviceAdminReceiver}
```

看到 Success 就大功告成——重启手机验证，冻结依然有效、状态栏显示"设备所有者（永久有效）"。

**报错**的话，对照下面处理。

---

## 第 3 步：按报错对症处理

### 报错 A：`already some accounts on the device`（设备上有账号）

这是你之前 App 内激活时遇到的错误。adb 也绕不过"账号数为 0"这个硬性要求，必须先把账号清干净：

1. 手机「设置 → 账号与同步 / 帐号管理」，**逐个移除所有账号**：Google 账号、努比亚账号、云服务、邮箱、微信/QQ 之类如果出现在账号列表里也要删
2. 关掉**应用双开/分身**（设置里搜"应用双开"），它会创建隐藏用户
3. 再查一次账号是否清零：

```
adb shell "dumpsys account | grep -i account"
```

4. 如果 `Accounts:` 仍有一条 `com.android.localphone`（SIM 卡联系人账号，很多机型自带、设置里看不到），临时隐藏它再激活：

```
adb shell pm hide com.android.localphone
adb shell dpm set-device-owner com.freezer.app/com.freezer.app.DeviceAdminReceiver
```

成功后恢复它：

```
adb shell pm unhide com.android.localphone
```

> 注：`com.android.localphone` 是常见的 SIM 账号提供者包名，努比亚可能不同。执行 `adb shell dumpsys account` 看那条账号的 `type`，若不是 localphone，就 `adb shell pm hide <提供该账号的包名>`——不确定包名的话把 `dumpsys account` 输出发我，我帮你判断。

### 报错 B：`already several users on the device`（有多个用户）

```
adb shell pm list users
```

假设列出 `UserInfo{10:...}`、`UserInfo{11:...}`，先切回主用户再逐个删：

```
adb shell am switch-user 0
adb shell pm remove-user 10
adb shell pm remove-user 11
```

（把 10、11 换成你实际看到的编号；User 0 不能删）

### 报错 C：`already has a device owner`（已有设备所有者）

手机已被别的 App 占了设备所有者名额。先查是谁：

```
adb shell dumpsys device_policy
```

看到 owner 包名后，尝试移除：

```
adb shell dpm remove-active-admin <那个组件的完整名>
```

移除成功后再执行第 2 步。**如果 remove 失败，通常只能恢复出厂**。

### 报错 D：`device has already been provisioned` / Android 14+ 直接被拒

这是最麻烦的一种。**Android 14/15 对设备所有者激活收紧了**：要求设备处于"未配置完成"状态。你手机已日常使用、登录过账号，很可能被系统硬性拒绝，连 adb 也不行。

唯一的根本办法：**恢复出厂 → 首次开机跳过所有账号登录 → 立即 adb 激活**：

1. 备份好数据（恢复出厂会清空一切）
2. 恢复出厂设置
3. 开机进向导，连 WiFi 但**跳过登录账号**（跳过 Google/厂商账号）
4. 进桌面后开 USB 调试，**不要登录任何账号**
5. 连电脑执行第 2 步的激活命令
6. 成功后，再把你需要的账号、App 装回来（设备所有者不会失效）

> 如果做完上面仍失败——说明这台努比亚 ROM 彻底封死了 adb 激活设备所有者。那就只能用回 **Shizuku 模式**（重启后需重新激活一次，但日常冻结完全正常），这是当前系统的硬限制，不是 App 的问题。

---

## 激活后怎么验证

1. 状态栏从"Shizuku"变成"**设备所有者（永久有效）**"
2. 重启手机 → 打开霜雪 → 依然显示设备所有者
3. 冻结任意应用 → 完全生效，不再依赖 Shizuku

---

## 需要我继续帮忙

把这两条命令的输出发我，我就能告诉你具体卡在哪、下一步怎么走：

```
adb shell pm list users
adb shell "dumpsys account | grep -i account"
```

以及第 2 步激活命令的完整报错。
