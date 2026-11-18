package com.freezer.app

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager.PERMISSION_GRANTED
import kotlin.concurrent.thread

data class AppItem(val label: String, val pkg: String, var icon: Drawable?, val isSystem: Boolean)

class MainActivity : Activity() {

    companion object {
        // 进程级缓存：应用列表 + 图标。界面冷启动直接用缓存秒开，列表/图标后台增量刷新。
        private var cachedApps: List<AppItem>? = null
        private val iconCache = HashMap<String, Drawable>()

        // 高危系统应用精确包名（冻结会导致系统/桌面崩溃、无法开机）
        private val DANGEROUS_PKGS = setOf(
            "android", "com.android.systemui", "com.android.settings",
            "com.android.launcher3", "com.android.phone", "com.android.server.telecom",
            "com.google.android.gms", "com.google.android.gsf", "com.google.android.gms.location.history",
            "com.android.shell", "com.android.providers.settings", "com.android.keychain",
            "com.nubia.launcher", "com.nubia.systemui", "com.android.incallui"
        )
        // 高危关键字（覆盖各厂商定制桌面/输入法/框架）
        private val DANGEROUS_KEYWORDS = listOf(
            "systemui", "launcher", ".settings", "dialer", "telecom", "incallui",
            "messaging", "mms", "inputmethod", ".ime", "keyboard",
            "google.android.gms", "google.android.gsf", "nubia.launcher", "nubia.systemui",
            "providers.settings", "keychain", "android.framework", "com.android.shell"
        )
    }

    private lateinit var homeView: View
    private lateinit var appsView: View
    private lateinit var settingsView: View
    private lateinit var aboutView: View
    private lateinit var container: ViewGroup

    private lateinit var gridView: GridView
    private lateinit var adapter: AppAdapter
    private lateinit var searchBox: EditText

    private var allApps = listOf<AppItem>()
    private var currentMode = FreezerEngine.MODE_NONE
    private val frozenCache = HashMap<String, Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    private val reqShizuku = 1001
    // 首页筛选：false=仅显示已选（白名单∪已冻结），true=显示全部
    private var showAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.install(this)
        setContentView(R.layout.activity_main)

        // Android 13+ 通知权限：前台服务通知可见性需要
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
                }
            } catch (_: Exception) {}
        }

        container = findViewById(R.id.contentContainer)
        homeView = LayoutInflater.from(this).inflate(R.layout.view_home, null)
        appsView = LayoutInflater.from(this).inflate(R.layout.view_apps, null)
        settingsView = LayoutInflater.from(this).inflate(R.layout.view_settings, null)
        aboutView = LayoutInflater.from(this).inflate(R.layout.view_about, null)

        setupHome()
        setupApps()
        setupSettings()
        setupAbout()

        // 底部导航
        findViewById<View>(R.id.tabHome).setOnClickListener { switchTab(0) }
        findViewById<View>(R.id.tabApps).setOnClickListener { switchTab(1) }
        findViewById<View>(R.id.tabSettings).setOnClickListener { switchTab(2) }
        findViewById<View>(R.id.tabAbout).setOnClickListener { switchTab(3) }

        Shizuku.addRequestPermissionResultListener { req, grant ->
            if (req == reqShizuku && grant == PERMISSION_GRANTED) handler.post { doActivateOwner() }
        }

        initMode()
        switchTab(0)
    }

    private fun switchTab(index: Int) {
        container.removeAllViews()
        container.addView(when (index) {
            0 -> homeView
            1 -> appsView
            2 -> settingsView
            else -> aboutView
        })
        val tabs = listOf(R.id.tabHome, R.id.tabApps, R.id.tabSettings, R.id.tabAbout)
        tabs.forEachIndexed { i, id ->
            findViewById<View>(id).alpha = if (i == index) 1f else 0.4f
        }
        if (index == 1) refreshAutoList()
        if (index == 2) refreshSettingsUI()
        if (index == 0) filterApps()
    }

    // ---------------- 首页 ----------------
    private fun setupHome() {
        gridView = homeView.findViewById(R.id.gridView)
        searchBox = homeView.findViewById(R.id.searchBox)
        adapter = AppAdapter()
        gridView.adapter = adapter

        // 单击 = 打开应用（若已冻结则先解冻再启动）
        gridView.setOnItemClickListener { _, _, pos, _ ->
            openApp(adapter.visible[pos])
        }
        // 长按 = 管理菜单（冻结/解冻、加入/移出冻结列表、快捷方式、详情）
        gridView.setOnItemLongClickListener { _, view, pos, _ ->
            showItemMenu(view, adapter.visible[pos])
            true
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filterApps()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // 悬浮按钮：单击 = 一键冻结冻结列表；长按 = 更多操作
        val fab = homeView.findViewById<View>(R.id.fab)
        fab.setOnClickListener { freezeSelected() }
        fab.setOnLongClickListener { showFabMenu(); true }
        // 顶部按钮
        homeView.findViewById<View>(R.id.btnMore).setOnClickListener { showModeDialog() }
        // 筛选切换：仅已选 / 全部
        homeView.findViewById<View>(R.id.btnFilter).setOnClickListener {
            showAll = !showAll
            (it as TextView).text = if (showAll) "▣" else "▦"
            filterApps()
            Toast.makeText(this, if (showAll) "显示全部应用" else "仅显示已选应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFabMenu() {
        val items = arrayOf("冻结冻结列表中的应用", "解冻冻结列表全部", "刷新列表", "管理冻结列表（去添加/移除）", "激活设备所有者")
        AlertDialog.Builder(this)
            .setTitle("快捷操作（列表共 ${getWhiteList().size} 个）")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> freezeSelected()
                    1 -> unfreezeSelected()
                    2 -> refreshAll()
                    3 -> switchTab(1)
                    4 -> showActivationGuide()
                }
            }.show()
    }

    /** 冻结列表 = 用户在「应用」页勾选的白名单 */
    private fun getWhiteList(): Set<String> =
        getSharedPreferences("autofreeze", MODE_PRIVATE)
            .getStringSet("whitelist", emptySet()) ?: emptySet()

    private fun initMode() {
        val saved = getSharedPreferences("settings", MODE_PRIVATE).getInt("mode", -99)
        currentMode = if (saved == -99) FreezerEngine.bestMode(this) else saved
        // 有缓存先秒开界面（不再弹"正在读取应用列表"），后台再增量刷新
        val cached = cachedApps
        if (cached != null) {
            allApps = cached
            filterApps()
            refreshFrozenStates()
        }
        loadApps()
    }

    /**
     * 静默加载应用列表：只读包名/标签（快），图标不在这里加载，
     * 由列表项渲染时懒加载（lazy icon）。加载完成后增量更新 UI，全程无对话框。
     */
    private fun loadApps() {
        thread {
            val pm = packageManager
            val list = mutableListOf<AppItem>()
            for (info in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                val label = info.loadLabel(pm)?.toString() ?: info.packageName
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                list.add(AppItem(label, info.packageName, iconCache[info.packageName], isSystem))
            }
            list.sortByDescending { it.label }
            cachedApps = list
            handler.post {
                // 只有列表真的变化了才整体刷新，避免反复 notifyDataSetChanged 闪烁
                if (allApps.map { it.pkg } != list.map { it.pkg }) {
                    allApps = list
                }
                refreshFrozenStates()
            }
        }
    }

    private fun refreshFrozenStates() {
        thread {
            for (item in allApps) frozenCache[item.pkg] = FreezerEngine.isFrozen(this, item.pkg, currentMode)
            handler.post { filterApps() }
        }
    }

    private fun refreshAll() {
        currentMode = FreezerEngine.bestMode(this)
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("mode", currentMode).apply()
        loadApps()
        Toast.makeText(this, "已刷新 · ${FreezerEngine.modeName(currentMode)}", Toast.LENGTH_SHORT).show()
    }

    private fun filterApps() {
        val q = searchBox.text.toString().trim()
        val whitelist = getWhiteList()
        // 默认只显示"已选"：白名单 ∪ 已冻结；搜索时无视 showAll，直接在已选范围内找
        val result = allApps.filter { item ->
            val selected = whitelist.contains(item.pkg) || frozenCache[item.pkg] == true
            (showAll || selected) && (q.isEmpty() || item.label.contains(q, true) || item.pkg.contains(q, true))
        }
        adapter.visible = result
        adapter.notifyDataSetChanged()
        // 状态栏：冻结列表数量 / 已冻结数量，与一键冻结作用对象保持一致
        val status = homeView.findViewById<TextView>(R.id.homeStatus)
        val modeText = if (currentMode == FreezerEngine.MODE_NONE) "○ 未激活" else "● ${FreezerEngine.modeName(currentMode)}"
        val frozenCount = whitelist.count { frozenCache[it] == true }
        status?.text = when {
            allApps.isEmpty() -> "正在加载应用列表…"
            q.isNotEmpty() -> "$modeText · 找到 ${result.size} 个"
            showAll -> "$modeText · 全部 ${result.size} 个 · 冻结列表 ${whitelist.size} 个（已冻 $frozenCount）"
            else -> "$modeText · 冻结列表 ${whitelist.size} 个 · 已冻结 $frozenCount 个"
        }
    }

    /** 单击应用：乐观解冻（UI 立即响应）+ 后台一条龙启动，全程不卡顿 */
    private fun openApp(item: AppItem) {
        val wasFrozen = frozenCache[item.pkg] == true
        if (wasFrozen && currentMode == FreezerEngine.MODE_NONE) { showActivationGuide(); return }

        // 乐观更新：点下去立刻当已解冻、图标变亮，不等后台命令
        if (wasFrozen) {
            frozenCache[item.pkg] = false
            adapter.notifyDataSetChanged()
        }
        AppLauncher.launch(
            activity = this,
            pkg = item.pkg,
            mode = currentMode,
            needUnfreeze = wasFrozen,
            onUnfreezeResult = { ok ->
                if (!ok) {
                    // 解冻失败：回滚图标状态并提示
                    frozenCache[item.pkg] = true
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "解冻失败，请检查授权后再试", Toast.LENGTH_SHORT).show()
                }
            },
            onFail = {
                if (wasFrozen) {
                    frozenCache[item.pkg] = true
                    adapter.notifyDataSetChanged()
                }
            }
        )
    }

    private fun showItemMenu(view: View, item: AppItem) {
        val frozen = frozenCache[item.pkg] == true
        val inList = isInWhitelist(item.pkg)
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, if (frozen) "解冻此应用" else "冻结此应用（彻底）")
        popup.menu.add(0, 3, 1, if (inList) "从冻结列表移出" else "加入冻结列表")
        popup.menu.add(0, 2, 2, "创建桌面快捷方式")
        popup.menu.add(0, 4, 3, "应用详情")
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> toggleFreeze(item)
                2 -> createShortcut(item)
                3 -> toggleWhitelist(item)
                4 -> openAppDetails(item.pkg)
            }
            true
        }
        popup.show()
    }

    private fun toggleFreeze(item: AppItem) {
        if (currentMode == FreezerEngine.MODE_NONE) { showActivationGuide(); return }
        val frozen = frozenCache[item.pkg] == true
        val result = if (frozen) FreezerEngine.unfreeze(this, item.pkg, currentMode)
        else FreezerEngine.freeze(this, item.pkg, currentMode)
        if (result.isSuccess) {
            frozenCache[item.pkg] = !frozen
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "${item.label} 已${if (frozen) "解冻" else "彻底冻结"}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "操作失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 一键冻结：只针对冻结列表中的应用，数量与首页显示一致 */
    private fun freezeSelected() {
        if (currentMode == FreezerEngine.MODE_NONE) { showActivationGuide(); return }
        val list = getWhiteList().filter { it != packageName }
        if (list.isEmpty()) {
            Toast.makeText(this, "冻结列表为空，请长按右下角图标选「管理冻结列表」添加", Toast.LENGTH_LONG).show()
            return
        }
        val targets = list.filter { frozenCache[it] != true }
        if (targets.isEmpty()) {
            Toast.makeText(this, "冻结列表中的 ${list.size} 个应用已全部冻结", Toast.LENGTH_SHORT).show()
            return
        }
        // 不再询问，点击即直接冻结
        val pd = ProgressDialog(this).apply { setMessage("正在冻结 ${targets.size} 个应用…"); setCancelable(false); show() }
        thread {
            var ok = 0
            val fails = mutableListOf<String>()
            for (pkg in targets) {
                val r = FreezerEngine.freeze(this, pkg, currentMode)
                if (r.isSuccess) ok++ else fails.add(pkg)
            }
            handler.post {
                pd.dismiss(); refreshFrozenStates()
                val msg = if (fails.isEmpty()) "冻结完成：$ok/${targets.size} 个"
                else "冻结 $ok/${targets.size} 个，${fails.size} 个失败（可能系统保护或授权失效）"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 解冻冻结列表中的应用 */
    private fun unfreezeSelected() {
        if (currentMode == FreezerEngine.MODE_NONE) { showActivationGuide(); return }
        val targets = getWhiteList().filter { frozenCache[it] == true }
        if (targets.isEmpty()) { Toast.makeText(this, "冻结列表中没有已冻结的应用", Toast.LENGTH_SHORT).show(); return }
        val pd = ProgressDialog(this).apply { setMessage("正在解冻 ${targets.size} 个应用…"); setCancelable(false); show() }
        thread {
            var ok = 0
            for (pkg in targets) if (FreezerEngine.unfreeze(this, pkg, currentMode).isSuccess) ok++
            handler.post {
                pd.dismiss(); refreshFrozenStates()
                Toast.makeText(this, "已解冻 $ok/${targets.size} 个", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun freezeAllUserApps() {
        if (currentMode == FreezerEngine.MODE_NONE) { showActivationGuide(); return }
        val targets = allApps.filter { !it.isSystem && it.pkg != packageName && frozenCache[it.pkg] != true }
        AlertDialog.Builder(this)
            .setTitle("冻结全部用户应用")
            .setMessage("将彻底冻结 ${targets.size} 个用户应用，确定吗？")
            .setPositiveButton("确定") { _, _ ->
                val pd = ProgressDialog(this).apply { setMessage("正在冻结…"); setCancelable(false); show() }
                thread {
                    targets.forEach { FreezerEngine.freeze(this, it.pkg, currentMode) }
                    handler.post { pd.dismiss(); refreshFrozenStates(); Toast.makeText(this, "已冻结 ${targets.size} 个应用", Toast.LENGTH_SHORT).show() }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun unfreezeAll() {
        if (currentMode == FreezerEngine.MODE_NONE) { showActivationGuide(); return }
        val targets = allApps.filter { frozenCache[it.pkg] == true }
        val pd = ProgressDialog(this).apply { setMessage("正在解冻…"); setCancelable(false); show() }
        thread {
            targets.forEach { FreezerEngine.unfreeze(this, it.pkg, currentMode) }
            handler.post { pd.dismiss(); refreshFrozenStates(); Toast.makeText(this, "已解冻 ${targets.size} 个应用", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun createShortcut(item: AppItem) {
        val target = Intent(this, UnfreezeActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(UnfreezeActivity.EXTRA_PKG, item.pkg)
        }
        var done = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val sm = getSystemService(ShortcutManager::class.java)
                if (sm.isRequestPinShortcutSupported) {
                    val bmp = (item.icon as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        ?: android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                    val shortcut = ShortcutInfo.Builder(this, "freezer_${item.pkg}")
                        .setShortLabel(item.label)
                        .setLongLabel("打开 ${item.label}")
                        .setIcon(android.graphics.drawable.Icon.createWithBitmap(bmp))
                        .setIntent(target)
                        .build()
                    sm.requestPinShortcut(shortcut, null)
                    done = true
                }
            } catch (e: Exception) { done = false }
        }
        if (!done) {
            sendBroadcast(Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
                putExtra(Intent.EXTRA_SHORTCUT_NAME, item.label)
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)
                putExtra("duplicate", false)
            })
        }
        Toast.makeText(this, "已请求创建「${item.label}」快捷方式，请在桌面确认", Toast.LENGTH_LONG).show()
    }

    private fun openAppDetails(pkg: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            startActivity(intent)
        } catch (e: Exception) { Toast.makeText(this, "无法打开详情", Toast.LENGTH_SHORT).show() }
    }

    // ---------------- 应用（自动冻结白名单） ----------------
    private lateinit var autoListView: GridView
    private lateinit var autoAdapter: AutoAdapter
    private lateinit var appsTypeBtn: TextView
    private val autoItems = mutableListOf<AppItem>()
    // 应用页当前页签：false=用户应用，true=系统应用
    private var appsShowSystem = false

    private fun setupApps() {
        autoListView = appsView.findViewById(R.id.autoGridView)
        autoAdapter = AutoAdapter()
        autoListView.adapter = autoAdapter
        autoListView.setOnItemClickListener { _, _, pos, _ ->
            val item = autoItems[pos]
            toggleWhitelist(item)
            autoAdapter.notifyDataSetChanged()
        }
        // 左上角「用户应用 / 系统应用」切换
        appsTypeBtn = appsView.findViewById(R.id.btnAppType)
        appsTypeBtn.setOnClickListener {
            appsShowSystem = !appsShowSystem
            appsTypeBtn.text = if (appsShowSystem) "系统应用 ▾" else "用户应用 ▾"
            (appsTypeBtn.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(if (appsShowSystem) 0xFFC62828.toInt() else 0xFF2E7D32.toInt())
            refreshAutoList()
        }
    }

    /** 高危系统应用：冻结可能导致系统崩溃/无法开机，界面标「危险」并二次确认 */
    private fun isDangerous(pkg: String): Boolean {
        val p = pkg.lowercase()
        return DANGEROUS_PKGS.any { p == it } || DANGEROUS_KEYWORDS.any { p.contains(it) }
    }

    private fun refreshAutoList() {
        if (allApps.isEmpty()) { loadApps(); return }
        autoItems.clear()
        // 当前页签：用户应用 / 系统应用；已加入冻结列表的始终显示，方便管理
        autoItems.addAll(allApps.filter { item ->
            (if (appsShowSystem) item.isSystem else !item.isSystem) || isInWhitelist(item.pkg)
        })
        autoAdapter.notifyDataSetChanged()
    }

    private fun isInWhitelist(pkg: String): Boolean =
        getSharedPreferences("autofreeze", MODE_PRIVATE).getStringSet("whitelist", emptySet())?.contains(pkg) == true

    private fun toggleWhitelist(item: AppItem) {
        val isAdding = !isInWhitelist(item.pkg)
        // 高危系统应用加入冻结列表时，二次确认防误操作导致系统崩溃
        if (isAdding && item.isSystem && isDangerous(item.pkg)) {
            AlertDialog.Builder(this)
                .setTitle("⚠ 高危应用")
                .setMessage("「${item.label}」是系统核心应用，冻结后可能导致桌面崩溃、无法开机或系统功能失效，且恢复困难。\n\n确定要加入冻结列表吗？")
                .setPositiveButton("仍要加入") { _, _ -> applyWhitelist(item, true) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        applyWhitelist(item, isAdding)
    }

    private fun applyWhitelist(item: AppItem, adding: Boolean) {
        val prefs = getSharedPreferences("autofreeze", MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet("whitelist", emptySet()) ?: emptySet())
        if (adding) set.add(item.pkg) else set.remove(item.pkg)
        prefs.edit().putStringSet("whitelist", set).apply()
        Toast.makeText(this, if (adding) "「${item.label}」已加入冻结列表" else "「${item.label}」已移出冻结列表", Toast.LENGTH_SHORT).show()
        // 同步刷新首页（列表数量、状态）
        filterApps()
        autoAdapter.notifyDataSetChanged()
        if (currentMode == FreezerEngine.MODE_NONE) return
        refreshFrozenStates()
    }

    // ---------------- 设置 ----------------
    private fun setupSettings() {
        settingsView.findViewById<View>(R.id.btnActivate2).setOnClickListener { showActivationGuide() }
        settingsView.findViewById<View>(R.id.btnUsageAccess).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            catch (e: Exception) { Toast.makeText(this, "无法打开，请到设置中搜索「使用情况访问权限」", Toast.LENGTH_LONG).show() }
        }
        settingsView.findViewById<View>(R.id.btnBattery).setOnClickListener {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show()
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
            } catch (e: Exception) { Toast.makeText(this, "无法打开，请到设置中搜索「电池优化」并设为不优化", Toast.LENGTH_LONG).show() }
        }
        settingsView.findViewById<View>(R.id.btnTestFreeze).setOnClickListener {
            AutoFreezeHelper.appendLog(this, "—— 手动测试 ——")
            AutoFreezeHelper.freeze(this, "手动测试冻结")
            Toast.makeText(this, "已触发，请查看下方日志", Toast.LENGTH_SHORT).show()
            settingsView.findViewById<TextView>(R.id.diagLog).text = AutoFreezeHelper.readLog(this)
        }
        settingsView.findViewById<View>(R.id.btnCheckUnfrozen).setOnClickListener {
            val whitelist = getSharedPreferences("autofreeze", MODE_PRIVATE)
                .getStringSet("whitelist", emptySet()) ?: emptySet()
            thread {
                val mode = currentMode
                val unfrozen = whitelist.filter { !FreezerEngine.isFrozen(this, it, mode) }
                val names = unfrozen.map { p ->
                    try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
                }
                runOnUiThread {
                    val msg = if (whitelist.isEmpty()) "冻结列表为空"
                    else if (names.isEmpty()) "全部 ${whitelist.size} 个都已冻结，无异常（首页显示 25/26 则是刷新滞后，下拉刷新即可）"
                    else "以下 ${names.size} 个应用实际未冻结：\n${names.joinToString("\n")}"
                    AlertDialog.Builder(this).setTitle("未冻结检查结果").setMessage(msg)
                        .setPositiveButton("知道了", null).show()
                }
            }
        }
        settingsView.findViewById<View>(R.id.btnClearLog).setOnClickListener {
            AutoFreezeHelper.clearLog(this)
            settingsView.findViewById<TextView>(R.id.diagLog).text = AutoFreezeHelper.readLog(this)
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshSettingsUI() {
        val prefs = getSharedPreferences("autofreeze", MODE_PRIVATE)
        settingsView.findViewById<TextView>(R.id.modeValue).text = FreezerEngine.modeName(currentMode)
        setCheck(R.id.chkScreenOff, prefs.getBoolean("screenoff_enabled", false))
        setCheck(R.id.chkExit, prefs.getBoolean("exit_enabled", false))
        setCheck(R.id.chkTimer, prefs.getBoolean("timer_enabled", false))
        settingsView.findViewById<TextView>(R.id.rowScreenOffDelay).text = "锁屏后延迟：${prefs.getLong("screenoff_delay", 0)} 秒"
        settingsView.findViewById<TextView>(R.id.rowExitDelay).text = "退出应用后延迟：${prefs.getLong("exit_delay", 10)} 秒"
        settingsView.findViewById<TextView>(R.id.rowTimerInterval).text = "定时冻结间隔：每 ${prefs.getLong("timer_interval", 30)} 分钟"
        settingsView.findViewById<TextView>(R.id.diagLog).text = AutoFreezeHelper.readLog(this)

        fun bindCheck(id: Int, key: String) {
            settingsView.findViewById<View>(id).setOnClickListener {
                val v = !getSharedPreferences("autofreeze", MODE_PRIVATE).getBoolean(key, false)
                getSharedPreferences("autofreeze", MODE_PRIVATE).edit().putBoolean(key, v).apply()
                setCheck(id, v)
                syncService()
            }
        }
        bindCheck(R.id.chkScreenOff, "screenoff_enabled")
        bindCheck(R.id.chkExit, "exit_enabled")
        bindCheck(R.id.chkTimer, "timer_enabled")

        fun bindNum(id: Int, key: String, default: Long, title: String) {
            settingsView.findViewById<View>(id).setOnClickListener { showNumberDialog(title, key, default) }
        }
        bindNum(R.id.rowScreenOffDelay, "screenoff_delay", 0, "锁屏后延迟冻结（秒）")
        bindNum(R.id.rowExitDelay, "exit_delay", 10, "退出应用后延迟冻结（秒）")
        bindNum(R.id.rowTimerInterval, "timer_interval", 30, "定时冻结间隔（分钟）")
    }

    private fun showNumberDialog(title: String, key: String, default: Long) {
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(default.toString()) }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().toLongOrNull() ?: default
                getSharedPreferences("autofreeze", MODE_PRIVATE).edit().putLong(key, v).apply()
                refreshSettingsUI()
            }.setNegativeButton("取消", null).show()
    }

    private fun setCheck(id: Int, checked: Boolean) {
        val v = settingsView.findViewById<TextView>(id)
        val name = when (id) {
            R.id.chkScreenOff -> "锁屏后自动冻结"
            R.id.chkExit -> "退出应用后冻结"
            R.id.chkTimer -> "定时冻结"
            else -> ""
        }
        v.text = if (checked) "☑ $name（已开启）" else "☐ $name（已关闭）"
        v.setTextColor(if (checked) 0xFF2E7D32.toInt() else 0xFF9E9E9E.toInt())
    }

    private fun syncService() {
        val prefs = getSharedPreferences("autofreeze", MODE_PRIVATE)
        val any = prefs.getBoolean("screenoff_enabled", false) || prefs.getBoolean("exit_enabled", false) || prefs.getBoolean("timer_enabled", false)
        if (any) AutoFreezeService.start(this) else AutoFreezeService.stop(this)
    }

    // ---------------- 关于 ----------------
    private fun setupAbout() {}

    // ---------------- 激活向导 ----------------
    private fun showActivationGuide() {
        val sb = StringBuilder()
        sb.append("【方式一 · Shizuku 模式（推荐，立刻可用）】\n")
        sb.append("装好 Shizuku 并用无线调试启动后，点「用 Shizuku 激活」。\n")
        sb.append("注意：手机重启后需重新激活一次 Shizuku。\n\n")
        sb.append("【方式二 · 设备所有者（重启不失活，但有限制）】\n")
        sb.append("Android 规定「设备上有账号时不能设设备所有者」。\n")
        sb.append("若激活报 accounts 错误，需先退出设备上的账号，或改用电脑 ADB：\n")
        sb.append("adb shell dpm set-device-owner com.freezer.app/.DeviceAdminReceiver\n\n")
        sb.append("【方式三 · 已 Root】\n刷新列表会自动识别 Root 模式。")

        AlertDialog.Builder(this).setTitle("选择工作模式").setMessage(sb.toString())
            .setPositiveButton("用 Shizuku 激活") { _, _ -> activateByShizuku() }
            .setNeutralButton("仅用 Shizuku 冻结") { _, _ ->
                currentMode = FreezerEngine.MODE_SHIZUKU
                getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("mode", currentMode).apply()
                refreshFrozenStates()
                Toast.makeText(this, "已切到 Shizuku 模式，确保 Shizuku 已授权后即可冻结", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("关闭", null).show()
    }

    private fun activateByShizuku() {
        if (!FreezerEngine.isShizukuAvailable()) { Toast.makeText(this, "未检测到 Shizuku，请先安装并启动", Toast.LENGTH_LONG).show(); return }
        if (!FreezerEngine.isShizukuGranted()) {
            try { Shizuku.requestPermission(reqShizuku) } catch (e: Exception) { Toast.makeText(this, "授权请求失败", Toast.LENGTH_SHORT).show() }
            return
        }
        doActivateOwner()
    }

    private fun doActivateOwner() {
        val pd = ProgressDialog(this).apply { setMessage("正在激活设备所有者…"); setCancelable(false); show() }
        thread {
            val err = FreezerEngine.activateDeviceOwner()
            handler.post {
                pd.dismiss()
                if (err == null) {
                    currentMode = FreezerEngine.MODE_DEVICE_OWNER
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("mode", currentMode).apply()
                    refreshFrozenStates()
                    AlertDialog.Builder(this).setTitle("激活成功")
                        .setMessage("已设为设备所有者，重启不失活，Shizuku 可以卸载了。\n\n卸载本 App 前请先在 设置→安全→设备管理员 中取消授权。")
                        .setPositiveButton("好的", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("设备所有者激活失败")
                        .setMessage("$err\n\n你仍可点「仅用 Shizuku 冻结」正常冻结应用。")
                        .setPositiveButton("知道了", null)
                        .setNeutralButton("诊断账号") { _, _ -> showAccountDiagnostic() }
                        .show()
                }
            }
        }
    }

    /** 列出设备上残留的账号，帮助用户排查为何无法设为设备所有者 */
    private fun showAccountDiagnostic() {
        val pd = ProgressDialog(this).apply { setMessage("正在读取系统账号…"); setCancelable(false); show() }
        thread {
            val result = FreezerEngine.listAccounts()
            handler.post {
                pd.dismiss()
                val msg = "设备 AccountManager 仍记录的账号：\n\n$result\n\n" +
                        "只要这里还有任何账号，Android 就禁止设为设备所有者。\n" +
                        "这些多为系统/云账号，需到 设置→账号与同步 逐个移除；" +
                        "若无法移除，请直接用「仅用 Shizuku 冻结」模式。"
                AlertDialog.Builder(this).setTitle("账号诊断").setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("复制结果") { _, _ -> copy(result) }
                    .show()
            }
        }
    }

    private fun showModeDialog() {
        val modes = arrayOf("设备所有者（永久·需激活）", "Root", "Shizuku", "自动检测")
        AlertDialog.Builder(this).setTitle("工作模式").setItems(modes) { _, which ->
            val target = if (which == 3) FreezerEngine.bestMode(this) else which
            // 校验目标模式是否真的可用
            when (target) {
                FreezerEngine.MODE_DEVICE_OWNER -> if (!FreezerEngine.isDeviceOwner(this)) {
                    Toast.makeText(this, "尚未设为设备所有者，请先激活", Toast.LENGTH_LONG).show(); return@setItems
                }
                FreezerEngine.MODE_ROOT -> if (!FreezerEngine.isRootAvailable()) {
                    Toast.makeText(this, "未检测到 Root", Toast.LENGTH_LONG).show(); return@setItems
                }
                FreezerEngine.MODE_SHIZUKU -> if (!FreezerEngine.isShizukuGranted()) {
                    Toast.makeText(this, "Shizuku 未授权，请先激活 Shizuku", Toast.LENGTH_LONG).show(); return@setItems
                }
            }
            currentMode = target
            getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("mode", currentMode).apply()
            refreshFrozenStates()
            Toast.makeText(this, "已切换：${FreezerEngine.modeName(currentMode)}", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    // ---------------- Adapters ----------------
    /** 懒加载图标：缓存命中直接显示；未命中后台线程取，回 UI 后仅更新该格子 */
    private fun bindIcon(iv: ImageView, item: AppItem) {
        iv.tag = item.pkg
        val cached = item.icon
        if (cached != null) {
            iv.setImageDrawable(cached)
            return
        }
        iv.setImageDrawable(null)
        thread {
            val d = try { packageManager.getApplicationIcon(item.pkg) } catch (e: Exception) { null }
            if (d != null) {
                synchronized(iconCache) { iconCache[item.pkg] = d }
                item.icon = d
            }
            handler.post {
                if (iv.tag == item.pkg && d != null) iv.setImageDrawable(d)
            }
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        var visible: List<AppItem> = emptyList()
        override fun getCount() = visible.size
        override fun getItem(p: Int) = visible[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, c: View?, parent: ViewGroup?): View {
            val v = c ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_app_grid, parent, false)
            val item = visible[p]
            val icon = v.findViewById<ImageView>(R.id.appIcon)
            bindIcon(icon, item)
            val frozen = frozenCache[item.pkg] == true
            icon.alpha = if (frozen) 0.35f else 1f
            v.findViewById<TextView>(R.id.appLabel).text = item.label
            v.findViewById<View>(R.id.frozenBadge).visibility = if (frozen) View.VISIBLE else View.GONE
            return v
        }
    }

    private inner class AutoAdapter : BaseAdapter() {
        override fun getCount() = autoItems.size
        override fun getItem(p: Int) = autoItems[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, c: View?, parent: ViewGroup?): View {
            val v = c ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_auto, parent, false)
            val item = autoItems[p]
            bindIcon(v.findViewById(R.id.appIcon), item)
            v.findViewById<TextView>(R.id.appLabel).text = item.label
            v.findViewById<TextView>(R.id.autoState).text = if (isInWhitelist(item.pkg)) "☑ 已在列表" else "☐ 未加入"
            // 高危系统应用标注「危险」，防止误冻导致系统崩溃
            val danger = item.isSystem && isDangerous(item.pkg)
            v.findViewById<View>(R.id.dangerTag).visibility = if (danger) View.VISIBLE else View.GONE
            return v
        }
    }
}
