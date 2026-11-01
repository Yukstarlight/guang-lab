package com.guang.lab

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var chip: TextView

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val navViews = ArrayList<LinearLayout>()
    private var tab = 0

    private var search = ""
    private var filter = 0
    private var appsAdapter: AppAdapter? = null
    private var uninstalled: List<String> = emptyList()
    private var uninstalledLoaded = false
    private var adbState: TextView? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, res ->
        if (code == REQ_SHIZUKU) {
            refreshChip()
            toast(if (res == 0) "Shizuku 已授权" else "Shizuku 拒绝授权")
        }
    }
    private val liveListener = Shizuku.OnBinderReceivedListener { main.post { refreshChip() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = box(true)
        root.setBackgroundColor(Color.TRANSPARENT)   // 用主题的 bg_app 渐变

        val head = box(false, 16f, 12f)
        head.gravity = Gravity.CENTER_VERTICAL
        val titles = box(true)
        titles.addView(tv(getString(R.string.app_name), 21f, R.color.fg_0, true))
        titles.addView(tv("Shizuku / 无线 ADB / Root · 三通道直写", 11f, R.color.fg_2))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        chip = pill("检测中…", R.color.fg_1)
        chip.isClickable = true
        chip.setOnClickListener { select(2) }
        head.addView(chip)
        root.addView(head)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val navBar = box(false)
        navBar.setPadding(px(4f), px(6f), px(4f), px(8f))
        navBar.background = GradientDrawable().apply {
            setColor(col(R.color.bg_1))
            setStroke(px(1f), col(R.color.divider))
        }
        buildNav(navBar)
        root.addView(navBar)

        setContentView(root)

        try {
            Shizuku.addRequestPermissionResultListener(permListener)
            Shizuku.addBinderReceivedListenerSticky(liveListener)
        } catch (t: Throwable) {
        }

        select(0)
        if (!Prefs.greeted(this)) {
            main.postDelayed({ firstLaunchFlow() }, 500)
        }
        runBg {
            val st0 = Priv.status(this)
            android.util.Log.i("GuangLab", "boot ch=" + st0.shizukuAlive + "/" + st0.shizukuPerm + " ver=" + st0.shizukuVer + " adb=" + st0.adbConnected + " root=" + st0.root + " privapp=" + Priv.privappReady(this))
            val ping = Priv.exec(this, "id; echo MARK_$?", 15_000)
            android.util.Log.i("GuangLab", "probe ok=" + ping.ok + " chan=" + ping.chan.title + " out=" + ping.out.trim().take(160))
            Repo.load(this)
            main.post {
                if (tab == 0 || tab == 1) select(tab)
                // 重装/换签名后 Shizuku 那边没有本应用的授权记录（binder 层判 false，
                // 即使 runtime 权限是 granted）—— 主动请求一次，用户点「允许」即可恢复
                if (st0.shizukuAlive && !st0.shizukuPerm) {
                    Priv.requestShizuku(REQ_SHIZUKU)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChip()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
        }
        try {
            Shizuku.removeBinderReceivedListener(liveListener)
        } catch (t: Throwable) {
        }
        io.shutdownNow()
    }

    // ---------- 导航 ----------

    private fun buildNav(bar: LinearLayout) {
        val items = listOf(
            Triple(R.drawable.ic_nav_home, "首页", 0),
            Triple(R.drawable.ic_nav_rules, "应用", 1),
            Triple(R.drawable.ic_nav_terminal, "通道", 2),
            Triple(R.drawable.ic_nav_mode, "记录", 3),
            Triple(R.drawable.ic_nav_settings, "设置", 4)
        )
        for ((iconRes, label, idx) in items) {
            val item = box(true)
            item.gravity = Gravity.CENTER
            item.setPadding(px(2f), px(5f), px(2f), px(5f))
            val holder = FrameLayout(this)
            val iv = ImageView(this)
            iv.setImageResource(iconRes)
            holder.addView(iv, FrameLayout.LayoutParams(px(21f), px(21f), Gravity.CENTER))
            holder.layoutParams = LinearLayout.LayoutParams(px(42f), px(30f))
            val t = tv(label, 10f, R.color.fg_2)
            t.gravity = Gravity.CENTER
            item.addView(holder)
            item.addView(t)
            item.isClickable = true
            item.setOnClickListener { select(idx) }
            val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            nlp.leftMargin = px(6f)
            nlp.rightMargin = px(6f)
            bar.addView(item, nlp)
            navViews.add(item)
        }
    }

    private fun select(idx: Int) {
        tab = idx
        for (i in navViews.indices) {
            val v = navViews[i]
            val on = i == idx
            val c = col(if (on) R.color.accent_purple else R.color.fg_2)
            val holder = v.getChildAt(0) as FrameLayout
            (holder.getChildAt(0) as ImageView).setColorFilter(c)
            (v.getChildAt(1) as TextView).setTextColor(c)
            holder.background = if (on) GradientDrawable().apply {
                cornerRadius = px(15f).toFloat()
                setColor(col(R.color.nav_pill))
            } else null
        }
        content.removeAllViews()
        val v = when (idx) {
            0 -> buildHome()
            1 -> buildApps()
            2 -> buildDebug()
            3 -> buildLog()
            else -> buildSettings()
        }
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (idx == 0 || idx == 2) refreshChip()
    }

    private fun refreshChip() {
        val st = Priv.status(this)
        val label = when {
            st.shizukuPerm -> "Shizuku"
            st.adbConnected -> "无线 ADB"
            st.root -> "Root"
            st.shizukuAlive -> "待授权"
            else -> "无通道"
        }
        chip.text = label
        chip.setTextColor(col(if (label == "无通道") R.color.err_red else R.color.ok_green))
    }

    // ---------- 首页 ----------

    private fun buildHome(): View {
        val sc = ScrollView(this)
        val root = box(true, 16f, 6f)

        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.VERTICAL
        hero.setPadding(px(18f), px(18f), px(18f), px(16f))
        hero.background = resources.getDrawable(R.drawable.bg_hero, null)
        val st = Priv.status(this)
        // NekoType 同款：左边 48dp 圆形头像 + 右标题/副标题
        val headRow = box(false)
        headRow.gravity = Gravity.CENTER_VERTICAL
        val avatar = ImageView(this)
        avatar.setImageResource(R.drawable.avatar_art)
        avatar.clipToOutline = true
        avatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        headRow.addView(avatar, LinearLayout.LayoutParams(px(48f), px(48f)))
        val titleCol = box(true)
        titleCol.setPadding(px(12f), 0, 0, 0)
        titleCol.addView(tv("光 · 实验室", 22f, R.color.fg_0, true))
        titleCol.addView(tv("一键直写 · 冻结 · 卸载 · 免 root", 11f, R.color.fg_1))
        headRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(headRow)
        val chips = box(false)
        chips.setPadding(0, px(12f), 0, 0)
        chips.addView(heroChip("Shizuku " + if (st.shizukuPerm) "就绪" else "未就绪"))
        chips.addView(heroChip("ADB " + if (st.adbConnected) "已连" else "未连"))
        chips.addView(heroChip("Root " + if (st.root) "可用" else "无"))
        hero.addView(chips)
        root.add(hero)

        root.add(section("一键操作"), 8f)
        var gridRow: LinearLayout? = null
        tiles().forEachIndexed { i, t ->
            if (i % 2 == 0) {
                gridRow = box(false)
                root.add(gridRow!!, 8f)
            }
            gridRow!!.addCell(tile(t.first, t.second, t.third) { onTile(i) }, 1f, if (i % 2 == 1) 8f else 0f)
        }

        root.add(section("最近记录"), 14f)
        val logCard = card(14f) { select(3) }
        val lines = Log.all().takeLast(4)
        if (lines.isEmpty()) {
            logCard.addView(tv("还没有操作记录", 12f, R.color.fg_2))
        } else {
            lines.forEach { logCard.addView(tv(it, 11f, R.color.fg_1).apply { setPadding(0, px(2f), 0, px(2f)) }) }
        }
        root.add(logCard)

        root.add(gap(20f))
        sc.addView(root)
        return sc
    }

    private fun tiles(): List<Triple<String, String, String>> = listOf(
        Triple("◎", "全部权限", "权限+AppOps+无障碍+通知+电池"),
        Triple("◉", "无障碍", "写入服务列表"),
        Triple("⬡", "Shizuku/ADB 授权", "给应用 shell 权限"),
        Triple("❄", "冻结·解冻", "禁用 / 挂起 / 隐藏"),
        Triple("✕", "卸载系统&第三方", "系统应用可恢复"),
        Triple("↺", "恢复应用", "把卸载的装回来"),
        Triple("◐", "屏幕灰度", "一键变灰，防沉迷"),
        Triple("■", "强制停止", "立刻杀掉进程")
    )

    private fun heroChip(s: String): TextView {
        val t = tv(s, 10.5f, R.color.fg_0)
        t.setPadding(px(9f), px(4f), px(9f), px(4f))
        t.background = GradientDrawable().apply {
            cornerRadius = px(100f).toFloat()
            setColor(Color.parseColor("#2AC8A2FF"))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.rightMargin = px(8f)
        t.layoutParams = lp
        return t
    }

    private fun tile(glyph: String, title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val c = card(14f, onClick = onClick)
        val chip = FrameLayout(this)
        chip.background = GradientDrawable().apply {
            cornerRadius = px(12f).toFloat()
            setColor(Color.parseColor("#2AC8A2FF"))
        }
        val g = tv(glyph, 17f, R.color.accent_purple, true)
        g.gravity = Gravity.CENTER
        chip.addView(g, FrameLayout.LayoutParams(px(24f), px(24f), Gravity.CENTER))
        c.addView(chip, LinearLayout.LayoutParams(px(38f), px(38f)))
        c.addView(tv(title, 13.5f, R.color.fg_0, true).apply { setPadding(0, px(9f), 0, 0) })
        c.addView(tv(sub, 10.5f, R.color.fg_2).apply { setPadding(0, px(3f), 0, 0) })
        return c
    }

    private fun onTile(id: Int) {
        when (id) {
            0 -> pickApp("选择要「一键直写」的应用") { doGrantAll(it) }
            1 -> pickApp("选择要开启无障碍的应用") { doAcc(it) }
            2 -> pickApp("选择要授权的应用") { doShizuku(it) }
            3 -> pickApp("选择要冻结 / 解冻的应用") { doFreeze(it, !it.enabled) }
            4 -> pickApp("选择要卸载的应用（系统 / 第三方）") { doUninstall(it) }
            5 -> restorePick()
            6 -> grayDialog()
            7 -> pickApp("选择要强制停止的应用") { e ->
                runBg { after(Ops.run(this, "forcestop", e.pkg), "强制停止 · " + e.label) }
            }
        }
    }

    // ---------- 应用 ----------

    private fun buildApps(): View {
        val root = box(true)
        val top = box(true, 16f, 8f)
        val s = EditText(this).apply {
            hint = "搜索应用 / 包名"
            textSize = 14f
            setSingleLine()
            setText(search)
            setTextColor(col(R.color.fg_0))
            setHintTextColor(col(R.color.fg_2))
            background = GradientDrawable().apply {
                cornerRadius = px(12f).toFloat()
                setColor(col(R.color.card))
                setStroke(px(1f), col(R.color.border))
            }
            setPadding(px(12f), px(9f), px(12f), px(9f))
        }
        s.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {
                search = c?.toString().orEmpty()
                refreshApps()
            }

            override fun afterTextChanged(c: Editable?) {}
        })
        top.addView(s)

        val chipsRow = HorizontalScrollView(this)
        chipsRow.isHorizontalScrollBarEnabled = false
        val chips = box(false)
        chips.setPadding(0, px(8f), 0, 0)
        val names = listOf("全部", "用户", "系统", "已冻结", "已卸载")
        for (i in names.indices) {
            val on = filter == i
            val p = pill(
                names[i],
                if (on) R.color.bg_0 else R.color.fg_1,
                if (on) R.color.accent_purple else R.color.card_alt
            )
            p.isClickable = true
            p.setOnClickListener {
                filter = i
                select(1)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = px(8f)
            chips.addView(p, lp)
        }
        chipsRow.addView(chips)
        top.addView(chipsRow)
        root.addView(top)

        val list = ListView(this).apply {
            divider = ColorDrawable(col(R.color.divider))
            dividerHeight = px(0.5f)
            setBackgroundColor(Color.TRANSPARENT)
            setSelector(ColorDrawable(Color.TRANSPARENT))
            setPadding(0, px(4f), 0, px(4f))
            clipToPadding = false
        }
        val ad = AppAdapter()
        list.adapter = ad
        appsAdapter = ad
        list.setOnItemClickListener { _, _, pos, _ -> openDetail(ad.getItem(pos).pkg) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            quickActions(ad.getItem(pos))
            true
        }
        root.addFill(list)
        refreshApps()
        return root
    }

    private fun openDetail(pkg: String) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra("pkg", pkg))
    }

    private fun quickActions(e: AppEntry) {
        val items = arrayOf(
            if (e.enabled) "冻结（禁用）" else "解冻（启用）",
            "强制停止",
            "卸载（--user 0）",
            "查看详情"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(e.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> doFreeze(e, e.enabled)
                    1 -> runBg { after(Ops.run(this, "forcestop", e.pkg), "强制停止 · " + e.label) }
                    2 -> doUninstall(e)
                    else -> openDetail(e.pkg)
                }
            }
            .show()
    }

    private fun refreshApps() {
        val ad = appsAdapter ?: return
        if (filter == 4) {
            if (!uninstalledLoaded) {
                ad.submit(emptyList())
                runBg {
                    val all = Ops.listPackages(this, "listuninstalled")
                    val installed = Repo.apps.map { it.pkg }.toSet()
                    val un = all.filter { it !in installed }
                    main.post {
                        uninstalled = un
                        uninstalledLoaded = true
                        if (filter == 4) refreshApps()
                    }
                }
                return
            }
            val data = uninstalled.filter { search.isEmpty() || it.contains(search, true) }
                .map { AppEntry(it, it, false, false, -1) }
            ad.submit(data)
            return
        }
        val q = search.trim().lowercase()
        var data: List<AppEntry> = Repo.apps
        data = when (filter) {
            1 -> data.filter { !it.system }
            2 -> data.filter { it.system }
            3 -> data.filter { !it.enabled }
            else -> data
        }
        if (q.isNotEmpty()) data = data.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        ad.submit(data)
    }

    private inner class AppAdapter : BaseAdapter() {
        private val data = ArrayList<AppEntry>()
        private val pool = Executors.newFixedThreadPool(3)

        fun submit(list: List<AppEntry>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = data.size
        override fun getItem(position: Int): AppEntry = data[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_pick, parent, false)
            val e = data[position]
            (v.findViewById<View>(R.id.name) as TextView).text = e.label
            (v.findViewById<View>(R.id.pkg) as TextView).text = e.pkg
            val tag = v.findViewById<View>(R.id.tag) as TextView
            val t = when {
                filter == 4 -> "已卸载"
                !e.enabled -> "已冻结"
                e.system -> "系统"
                else -> ""
            }
            if (t.isEmpty()) {
                tag.visibility = View.GONE
            } else {
                tag.text = t
                tag.setTextColor(col(if (t == "已冻结" || t == "已卸载") R.color.err_red else R.color.warn_amber))
                tag.visibility = View.VISIBLE
            }
            val iv = v.findViewById<View>(R.id.icon) as ImageView
            iv.tag = e.pkg
            val cached = Repo.icon(this@MainActivity, e.pkg)
            if (cached != null) {
                iv.setImageDrawable(cached)
            } else {
                iv.setImageDrawable(null)
                pool.execute {
                    val d = Repo.icon(this@MainActivity, e.pkg)
                    main.post { if (iv.tag == e.pkg) iv.setImageDrawable(d) }
                }
            }
            return v
        }
    }

    // ---------- 通道 ----------

    private fun buildDebug(): View {
        val sc = ScrollView(this)
        val root = box(true, 16f, 6f)
        val st = Priv.status(this)

        val c1 = card()
        c1.addView(tv("Shizuku", 15f, R.color.fg_0, true))
        c1.addView(
            tv(
                when {
                    !shizukuInstalled() -> "未安装。Shizuku 需要先用无线调试或 root 启动一次。"
                    st.shizukuPerm -> "已连接 · v" + st.shizukuVer + " · 通道可用"
                    st.shizukuAlive -> "服务在跑，但本应用还没拿到授权。"
                    else -> "服务未运行，请先打开 Shizuku 应用启动服务。"
                }, 11.5f, R.color.fg_1
            ).apply { setPadding(0, px(6f), 0, px(10f)) }
        )
        val r1 = box(false)
        r1.addCell(btn("请求授权", true) { Priv.requestShizuku(REQ_SHIZUKU) })
        r1.addCell(btn("打开 Shizuku") { launchShizukuManager() }, 1f, 8f)
        r1.addCell(btn("刷新") { select(2) }, 1f, 8f)
        c1.addView(r1)
        val srv = tv("服务端：检测中…", 11f, R.color.fg_2)
        srv.setPadding(0, px(8f), 0, 0)
        c1.addView(srv)
        runBg {
            val r = Ops.run(this, "server_state", "com.guang.lab")
            val list = r.out.lineSequence().map { it.trim() }.filter { it.startsWith("SRV|") }
                .map { it.removePrefix("SRV|") }.toList()
            main.post {
                srv.text = when {
                    list.isEmpty() -> "服务端：没有检测到 Shizuku / Stellar 进程"
                    list.size >= 2 -> "服务端：" + list.joinToString(" + ") + "（两个同时在跑，会互抢 binder —— 建议只留一个）"
                    else -> "服务端：" + list[0]
                }
                srv.setTextColor(col(if (list.size >= 2) R.color.warn_amber else R.color.fg_1))
            }
        }
        if (pkgInstalled("roro.stellar.manager")) {
            c1.add(btn("Stellar 授权页（询问/允许/拒绝）") { openStellarAuth(null) }, 8f)
        }
        root.add(c1, 4f)

        val c2 = card()
        c2.addView(tv("无线调试 ADB（免 root · 免电脑）", 15f, R.color.fg_0, true))
        c2.addView(
            tv(
                "打开开发者选项 → 无线调试 → 使用配对码配对设备，把 6 位码填进来。配对后本机直连 adbd，" +
                        "拿到 shell(2000) 权限 —— 和 Shizuku 同级，完全不需要 root，也不需要电脑。",
                11.5f, R.color.fg_1
            ).apply { setPadding(0, px(6f), 0, px(10f)) }
        )
        val ast = tv(
            when {
                !Adb.supported() -> "需要 Android 11 及以上"
                Adb.connected -> "已连接（无线调试）"
                else -> "未连接 · " + Adb.state
            }, 11f, if (Adb.connected) R.color.ok_green else R.color.fg_2
        )
        adbState = ast
        c2.addView(ast)
        val input = EditText(this).apply {
            hint = "6 位配对码"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            setSingleLine()
            setTextColor(col(R.color.fg_0))
            setHintTextColor(col(R.color.fg_2))
            background = GradientDrawable().apply {
                cornerRadius = px(12f).toFloat()
                setColor(col(R.color.card_alt))
                setStroke(px(1f), col(R.color.border))
            }
            setPadding(px(12f), px(9f), px(12f), px(9f))
        }
        c2.add(input, 8f)
        val r2 = box(false)
        r2.addCell(btn("配对并连接", true) {
            val code = input.text.toString().trim()
            if (code.length != 6) {
                toast("配对码是 6 位数字")
            } else {
                toast("正在配对…")
                runBg {
                    val msg = Adb.pair(this, code)
                    Log.add("ADB 配对：$msg")
                    main.post {
                        adbState?.text = msg
                        adbState?.setTextColor(col(if (Adb.connected) R.color.ok_green else R.color.err_red))
                        toast(msg)
                        refreshChip()
                    }
                }
            }
        })
        r2.addCell(btn("断开") {
            Adb.disconnect()
            adbState?.text = "未连接"
            refreshChip()
        }, 1f, 8f)
        c2.add(r2, 8f)
        root.add(c2, 12f)

        val c3 = card()
        c3.addView(tv("Root", 15f, R.color.fg_0, true))
        c3.addView(
            tv(
                if (st.root) "su 可用" else "未检测到 su（不影响 Shizuku / ADB 通道）",
                11.5f, if (st.root) R.color.ok_green else R.color.fg_2
            ).apply { setPadding(0, px(6f), 0, 0) }
        )
        c3.addView(
            DocDialog.row(this, "访问我们的其他产品：nekotype.top") {
                openUrl("https://nekotype.top")
            }
        )
        root.add(c3, 12f)

        // 系统级（priv-app）：把本应用装成系统应用，冻结就不再需要任何通道
        val c3b = card()
        c3b.addView(tv("系统级（priv-app）", 15f, R.color.fg_0, true))
        val ready = Priv.privappReady(this)
        c3b.addView(
            tv(
                if (ready) "已获得 CHANGE_COMPONENT_ENABLED_STATE，冻结可直接走系统 API"
                else "未授权。装成系统应用后，「冻结方式 → 系统级」这一项才可用",
                11.5f, if (ready) R.color.ok_green else R.color.fg_2
            ).apply { setPadding(0, px(6f), 0, px(8f)) }
        )
        if (!ready) {
            c3b.add(btn(if (st.root) "装成系统应用（Root）" else "装成系统应用（需 Root）", st.root) {
                if (!st.root) {
                    toast("这一步必须 Root：要往 /data/adb/modules 写 Magisk 模块")
                } else {
                    confirm(
                        "装成系统应用",
                        "会用 root 在 /data/adb/modules/guanglab 生成一个 Magisk 模块（含本应用 APK + privapp 白名单），重启后生效。\n\n重启后「冻结方式」里就能选「系统级」。",
                        true
                    ) {
                        runBg { after(Ops.run(this, "install_priv", "com.guang.lab"), "系统级安装") }
                    }
                }
            }, 0f)
        }
        root.add(c3b, 12f)

        val c4 = card()
        c4.addView(tv("手动命令（实验）", 15f, R.color.fg_0, true))
        c4.addView(tv("走当前可用通道执行，输出原样返回。", 11.5f, R.color.fg_1).apply { setPadding(0, px(4f), 0, px(8f)) })
        val cmd = EditText(this).apply {
            hint = "例如：pm list packages -3"
            textSize = 13f
            setTextColor(col(R.color.fg_0))
            setHintTextColor(col(R.color.fg_2))
            background = GradientDrawable().apply {
                cornerRadius = px(12f).toFloat()
                setColor(col(R.color.card_alt))
                setStroke(px(1f), col(R.color.border))
            }
            setPadding(px(12f), px(9f), px(12f), px(9f))
        }
        c4.add(cmd, 6f)
        val out = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(col(R.color.fg_1))
            setTextIsSelectable(true)
            setPadding(0, px(8f), 0, 0)
        }
        c4.add(btn("执行", true) {
            val s = cmd.text.toString().trim()
            if (s.isNotEmpty()) {
                runBg {
                    val r = Priv.exec(this, s, 20_000)
                    Log.add("命令 " + s.take(30) + " → " + r.chan.title)
                    main.post {
                        out.text = if (r.ok) r.out.ifBlank { "（无输出）" } else "执行失败：" + r.out
                        refreshChip()
                    }
                }
            }
        }, 8f)
        c4.add(out)
        root.add(c4, 12f)

        root.add(gap(20f))
        sc.addView(root)
        return sc
    }

    /** 兼容 Shizuku / Stellar / 其它同 API 的服务端（它们共用 moe.shizuku 的 binder 与权限名） */
    private val SHIZUKU_PKGS = arrayOf(
        "moe.shizuku.privileged.api",
        "roro.stellar.manager",
        "moe.shizuku.privileged.api.stellar"
    )

    private fun pkgInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    private fun shizukuInstalled(): Boolean = SHIZUKU_PKGS.any {
        try {
            packageManager.getPackageInfo(it, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun launchShizukuManager() {
        for (p in SHIZUKU_PKGS) {
            val i = try {
                packageManager.getLaunchIntentForPackage(p)
            } catch (t: Throwable) {
                null
            }
            if (i != null) {
                try {
                    startActivity(i)
                    return
                } catch (t: Throwable) {
                }
            }
        }
        toast("没装 Shizuku / Stellar")
    }

    // ---------- 记录 ----------

    private fun buildLog(): View {
        val sc = ScrollView(this)
        val root = box(true, 16f, 6f)
        val c = card()
        c.addView(tv("操作记录", 15f, R.color.fg_0, true))
        val lines = Log.all().takeLast(80)
        val body = tv(if (lines.isEmpty()) "还没有记录" else lines.reversed().joinToString("\n"), 10.5f, R.color.fg_1)
        body.typeface = Typeface.MONOSPACE
        body.setTextIsSelectable(true)
        body.setPadding(0, px(8f), 0, px(8f))
        c.addView(body)
        val row = box(false)
        row.addCell(btn("刷新") { select(3) })
        row.addCell(btn("清空") {
            Log.clear()
            select(3)
        }, 1f, 8f)
        row.addCell(btn("复制全部") { copyText(lines.joinToString("\n")) }, 1f, 8f)
        c.add(row, 4f)
        root.add(c)
        root.add(gap(20f))
        sc.addView(root)
        return sc
    }

    // ---------- 设置 ----------

    private fun buildSettings(): View {
        val sc = ScrollView(this)
        val root = box(true, 16f, 6f)

        val c1 = card()
        c1.addView(tv("通道优先级", 15f, R.color.fg_0, true))
        c1.addView(tv("脚本默认先走谁；失败自动降级到下一个。", 11f, R.color.fg_2).apply { setPadding(0, px(4f), 0, px(8f)) })
        val rg = RadioGroup(this)
        val names = listOf("自动（Shizuku → ADB → Root）", "只用 Shizuku", "只用无线 ADB", "只用 Root")
        val cur = Prefs.chan(this)
        names.forEachIndexed { i, n ->
            val rb = RadioButton(this)
            rb.text = n
            rb.id = 1000 + i
            rb.setTextColor(col(R.color.fg_0))
            rb.textSize = 13f
            rb.isChecked = cur == i
            rg.addView(rb)
        }
        rg.setOnCheckedChangeListener { _, id -> Prefs.setChan(this, id - 1000) }
        c1.addView(rg)
        root.add(c1)

        val c2 = card()
        val sw = Switch(this)
        sw.text = "危险操作二次确认"
        sw.textSize = 13f
        sw.setTextColor(col(R.color.fg_0))
        sw.isChecked = Prefs.confirm(this)
        sw.buttonTintList = ColorStateList.valueOf(col(R.color.accent_purple))
        sw.setOnCheckedChangeListener { _, b -> Prefs.setConfirm(this, b) }
        c2.addView(sw)
        root.add(c2, 12f)

        val c3 = card()
        c3.addView(tv("关于", 15f, R.color.fg_0, true))
        c3.addView(
            tv(
                "光 · 实验室 1.0\n" +
                        "所有操作都通过 shell 写系统，不弹设置页、不模拟点击。\n" +
                        "通道：Shizuku（newProcess 直写）/ 无线调试 ADB / Root。\n" +
                        "签名级权限 shell 也无权授予，失败属正常。",
                11.5f, R.color.fg_1
            ).apply { setPadding(0, px(6f), 0, 0) }
        )
        c3.addView(
            DocDialog.row(this, "访问我们的其他产品：nekotype.top") {
                openUrl("https://nekotype.top")
            }
        )
        root.add(c3, 12f)

        val c4 = card()
        c4.addView(tv("法律与开源", 15f, R.color.fg_0, true))
        c4.addView(DocDialog.row(this, "免责声明") { DocDialog.showDoc(this, "免责声明", LegalDocs.disclaimer()) })
        c4.addView(DocDialog.row(this, "第三方共享清单") { DocDialog.showDoc(this, "第三方共享清单", LegalDocs.thirdParty()) })
        c4.addView(DocDialog.row(this, "开源许可") { DocDialog.showDoc(this, "开源许可", LegalDocs.licenses()) })
        root.add(c4, 12f)

        root.add(gap(20f))
        sc.addView(root)
        return sc
    }

    // ---------- 首次进入：免责声明（等 5 秒）→ 其他产品 ----------

    private fun openUrl(u: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)))
        } catch (t: Throwable) {
            toast("没有可用的浏览器")
        }
    }

    private fun firstLaunchFlow() {
        if (isFinishing || Prefs.greeted(this)) return

        val body = TextView(this)
        body.text = LegalDocs.disclaimer()
        body.textSize = 12.5f
        body.setTextColor(col(R.color.fg_0))
        body.setLineSpacing(0f, 1.35f)
        body.setTextIsSelectable(true)

        val boxv = LinearLayout(this)
        boxv.orientation = LinearLayout.VERTICAL
        boxv.setPadding(px(22f), px(2f), px(22f), px(6f))
        boxv.addView(body)
        val sc = ScrollView(this)
        sc.addView(boxv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val d = DocDialog.builder(this)
            .setTitle("免责声明")
            .setView(sc)
            .setPositiveButton("知道了", null)
            .create()
        d.setCancelable(false)
        d.setOnShowListener {
            val b = d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            b.isEnabled = false
            var left = 5
            b.text = "知道了（" + left + "s）"
            val tick = object : Runnable {
                override fun run() {
                    left--
                    if (left > 0) {
                        b.text = "知道了（" + left + "s）"
                        main.postDelayed(this, 1000)
                    } else {
                        b.text = "知道了"
                        b.isEnabled = true
                        d.setCancelable(true)
                    }
                }
            }
            main.postDelayed(tick, 1000)
            b.setOnClickListener { d.dismiss() }
        }
        d.setOnDismissListener {
            Prefs.setGreeted(this, true)
            main.postDelayed({ promoDialog() }, 300)
        }
        d.show()
    }

    private fun promoDialog() {
        if (isFinishing) return
        android.app.AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("另一个作品")
            .setMessage("您可以试试我们的其他产品，也非常好用哦！\n\nnekotype.top")
            .setPositiveButton("去看看") { _, _ -> openUrl("https://nekotype.top") }
            .setNegativeButton("知道了", null)
            .show()
    }
    // ---------- 动作 ----------

    private fun runBg(block: () -> Unit) {
        try {
            io.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    main.post { toast("出错：" + (t.message ?: t.javaClass.simpleName)) }
                }
            }
        } catch (t: Throwable) {
            // 页面已销毁、线程池已关：静默丢弃
        }
    }

    private fun after(r: Priv.R, label: String, script: String? = null) {
        Log.add("" + label + " → " + (if (r.ok) "ok" else "fail") + "（" + r.chan.title + "）")
        main.post {
            report(label, r, script)
            refreshChip()
        }
    }

    private fun doGrantAll(e: AppEntry) {
        runBg {
            val t = Repo.describe(this, e.pkg)
            if (t == null) {
                main.post { toast("读不到 " + e.pkg + " 的清单") }
                return@runBg
            }
            val script = Ops.grantScript(this, t, false) ?: return@runBg
            after(Priv.exec(this, script, 90_000), "全部权限 · " + e.label, script)
        }
    }


    private fun labelOf(pkg: String): String = Repo.find(this, pkg)?.label ?: pkg

    /** 通用授权：有脚本就跑脚本，然后授它申请过的 ADB/Shizuku 权限 + 关键 appops */
    private fun runAuth(pkg: String, name: String, script: String?) {
        runBg {
            val sb = StringBuilder()
            if (script != null) sb.append(Ops.run(this, "run_script", pkg, script).out)
            val perms = try {
                packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS)
                    .requestedPermissions?.toList().orEmpty()
            } catch (t: Throwable) {
                emptyList()
            }
            val szp = perms.filter { it.contains("shizuku", true) }
            val targets = if (szp.isNotEmpty()) szp else ADB_LEVEL.filter { it in perms }
            for (p in targets) sb.append(Ops.run(this, "perm_grant", pkg, p).out)
            for (op in arrayOf("GET_USAGE_STATS", "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW")) {
                sb.append(Ops.run(this, "appops", pkg, op).out)
            }
            after(Priv.R(true, sb.toString(), Priv.Kind.NONE), "授权 · " + name)
        }
    }
    /** pm grant 能授的「ADB 级」权限（都带 development 标记，shell 可授） */
    private val ADB_LEVEL = arrayOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.GET_APP_OPS_STATS",
        "android.permission.CHANGE_CONFIGURATION",
        "android.permission.INTERACT_ACROSS_USERS",
        "android.permission.DUMP",
        "android.permission.READ_LOGS",
        "android.permission.BATTERY_STATS",
        "android.permission.SET_ANIMATION_SCALE",
        "android.permission.READ_PRECISE_PHONE_STATE"
    )

    /**
     * 给应用「shell 权限」。
     *
     * 关键：Android 只允许授「应用自己申请过的权限」。所以先看目标清单里有什么：
     *  · 申请了 moe.shizuku.*  → 授 Shizuku 权限（它就能走 Shizuku 通道）
     *  · 没申请 Shizuku（比如 Scene）→ 改授它的 ADB 级权限（WRITE_SECURE_SETTINGS 等），
     *    这才是那类应用说的「SHELL 权限」
     */
    /** 打开 Shizuku 官方的「应用管理」页 —— 只有在这里开关，Shizuku 才会把 App 登记进分发表 */
    private fun openShizukuAppManagement() {
        val i = Intent().setComponent(
            android.content.ComponentName(
                "moe.shizuku.privileged.api",
                "moe.shizuku.manager.management.ApplicationManagementActivity"
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (t: Throwable) {
            // 退回到 Shizuku 主界面
            try {
                startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
            } catch (t2: Throwable) {
                toast("打不开 Shizuku")
            }
        }
    }
    /** 跳 Stellar 的「授权应用」页（它自己那份 询问/允许/拒绝 只能在那儿改） */
    private fun openStellarAuth(pkg: String? = null) {
        if (!pkgInstalled("roro.stellar.manager")) {
            toast("没装 Stellar")
            return
        }
        val i = Intent("roro.stellar.manager.intent.action.REQUEST_PERMISSION")
            .setPackage("roro.stellar.manager")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.putExtra("moe.shizuku.privileged.api.intent.extra.PACKAGE_NAME", pkg)
        try {
            startActivity(i)
        } catch (t: Throwable) {
            toast("打不开 Stellar 授权页")
        }
    }

    /** 目标是不是 Shizuku / Stellar 服务端本身（给它授 API_V23 没有意义） */
    private fun isShizukuServer(pkg: String): Boolean = pkg in SHIZUKU_PKGS

    private fun doShizuku(e: AppEntry) {
        if (isShizukuServer(e.pkg)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("这是服务端，不是客户端")
                .setMessage(
                    e.label + "（" + e.pkg + "）自己就是提供 shell 的那个服务端，它「声明」这个权限而不是「申请」它。\n\n" +
                            "所以给它授 moe.shizuku.manager.permission.API_V23：\n" +
                            "· 没有意义（它本来就有）\n" +
                            "· Android 会直接拒绝（has not requested permission）\n\n" +
                            "要授权的是**客户端应用** —— 像本应用、NekoType、Hail、AppManager 这种在清单里申请了 API_V23 的。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        runBg {
            val sb = StringBuilder()

            // 1) 先扫这个应用自己的 ADB 激活脚本（/sdcard/Android/data/<pkg>/*.sh）
            val scan = Ops.run(this, "scan_pkg", e.pkg)
            if (!scan.ok) sb.append("R|sys|扫描脚本失败|skip|" + scan.out.take(80) + "\n")
            var best: String? = null
            var bestScore = 99
            for (line in scan.out.lineSequence()) {
                val t = line.trim()
                if (!t.startsWith("SCAN|")) continue
                val f = t.split("|")
                if (f.size < 3) continue
                val path = f[2]
                val name = path.substringAfterLast('/').lowercase()
                val sc = when {
                    name.contains("rish") -> -1
                    name.startsWith("up") -> 0
                    name.contains("adb") || name.contains("activ") || name.contains("init") -> 1
                    else -> -1
                }
                if (sc < 0) continue
                val cur = best
                if (cur == null || sc < bestScore || (sc == bestScore && path.length < cur.length)) {
                    best = path
                    bestScore = sc
                }
            }

            // 2) 它申请了哪些 ADB / Shizuku 权限
            val perms = try {
                packageManager.getPackageInfo(e.pkg, android.content.pm.PackageManager.GET_PERMISSIONS)
                    .requestedPermissions?.toList().orEmpty()
            } catch (t: Throwable) {
                emptyList()
            }
            val szp = perms.filter { it.contains("shizuku", true) }
            val adbPerms = ADB_LEVEL.filter { it in perms }

            // 只补「申请了但还没授予」的那几项 —— 这就是电脑 ADB 那条命令的等价物
            val missing = adbPerms.filter {
                packageManager.checkPermission(it, e.pkg) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val targets = if (szp.isNotEmpty()) szp else missing


            // 3) 有脚本先跑脚本（Scene 这类），再授权限
            val script = best
            if (script != null) sb.append(Ops.run(this, "run_script", e.pkg, script).out)
            for (p in targets) sb.append(Ops.run(this, "perm_grant", e.pkg, p).out)

            // 授权后重算，别用授权前的快照
            val stillMissing = adbPerms.filter {
                packageManager.checkPermission(it, e.pkg) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val fixed = missing.size - stillMissing.count { it in missing }
            sb.append(
                "R|sys|ADB 级权限|" + (if (stillMissing.isEmpty()) "ok" else "skip") + "|申请 " +
                        adbPerms.size + " 项 · 本次补 " + fixed + " 项 · 仍缺 " + stillMissing.size + " 项\n"
            )
            for (op in arrayOf("GET_USAGE_STATS", "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW")) {
                sb.append(Ops.run(this, "appops", e.pkg, op).out)
            }
            if (script == null && targets.isEmpty()) {
                sb.append("R|sys|没有可用的授权方式|skip|它既没放激活脚本，也没申请 ADB / Shizuku 权限\n")
            }
            if (szp.isNotEmpty()) {
                Priv.dispatchPackageChanged(e.pkg)   // 尽力而为，不承诺效果
                if (pkgInstalled("roro.stellar.manager") && !pkgInstalled("moe.shizuku.privileged.api")) {
                    sb.append("R|sys|Stellar 那一层|skip|Stellar 的 询问/允许/拒绝 要在它的授权页点一次\n")
                } else {
                    sb.append("R|sys|Shizuku 分发|skip|权限已写；要让它真正把 binder 发给该应用，需在 Shizuku「应用管理」里勾一次（已帮你打开）\n")
                }
            }
            val label = "授权 · " + e.label + (if (script != null) "（" + script.substringAfterLast('/') + "）" else "")
            after(Priv.R(true, sb.toString(), Priv.Kind.NONE), label)
            if (szp.isNotEmpty()) {
                main.post {
                    if (pkgInstalled("roro.stellar.manager") && !pkgInstalled("moe.shizuku.privileged.api")) {
                        openStellarAuth(e.pkg)
                    } else if (pkgInstalled("moe.shizuku.privileged.api")) {
                        openShizukuAppManagement()
                    }
                }
            }
        }
    }
    private fun doAcc(e: AppEntry) {
        runBg {
            val t = Repo.describe(this, e.pkg)
            if (t == null || t.accessibility.isEmpty()) {
                main.post { toast(e.label + " 没有声明无障碍服务") }
                return@runBg
            }
            var last = Priv.R(false, "无服务", Priv.Kind.NONE)
            for (c in t.accessibility) last = Ops.run(this, "acc_on", e.pkg, c)
            after(last, "无障碍 · " + e.label)
        }
    }


    /**
     * 冻结 / 解冻。冻结时先选「工作模式」，对应 Hail 的三种冻结方式：
     * disable-user（禁用，最通用）/ suspend（挂起，Android 12+）/ hide（隐藏）
     */
    /** 系统级冻结：直接调 PackageManager，不经过任何 shell；只有 priv-app 身份可用 */
    private fun sysFreeze(e: AppEntry, enable: Boolean) {
        runBg {
            val r = Priv.setEnabledPriv(this, e.pkg, enable)
            if (r == null) {
                main.post { showSysHint() }
                return@runBg
            }
            val label = (if (enable) "启用(系统级) · " else "禁用(系统级) · ") + e.label
            val out = "R|freeze|" + (if (enable) "系统级启用" else "系统级禁用") + "|" + (if (r.ok) "ok" else "fail") + "|" + (if (r.ok) "" else r.out) + "|"
            Log.add(label + " → " + (if (r.ok) "ok" else "fail"))
            main.post {
                report(label, Priv.R(r.ok, out, Priv.Kind.PRIVAPP))
                refreshChip()
            }
        }
    }

    private fun showSysHint() {
        android.app.AlertDialog.Builder(this)
            .setTitle("系统级冻结不可用")
            .setMessage(
                "本应用没有 CHANGE_COMPONENT_ENABLED_STATE 权限 —— 这个权限只发给系统应用。\n\n" +
                        "要用这一项，得把 APK 装进系统分区，例如 root / Magisk：\n" +
                        "/data/adb/modules/guanglab/system/priv-app/GuangLab/GuangLab.apk\n" +
                        "再在 /data/adb/modules/guanglab/system/etc/permissions/ 加一条 privapp 白名单，重启。\n\n" +
                        "不想动系统分区就走「Shizuku / Root · 禁用 / 挂起 / 隐藏」，效果一样。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }
    private fun doFreeze(e: AppEntry, freeze: Boolean) {
        if (!freeze) {
            runBg { after(Ops.run(this, "unfreeze", e.pkg), "解冻 · " + e.label) }
            return
        }
        // 工作模式清单照 Hail（雹）的 WorkingMode：通道 × 方式
        val modes = arrayOf(
            "Shizuku · 禁用（pm disable-user）",
            "Shizuku · 挂起（pm suspend）",
            "Shizuku · 隐藏（pm hide）",
            "Shizuku · 强制停止",
            "Root · 禁用（pm disable-user）",
            "Root · 挂起（pm suspend）",
            "Root · 隐藏（pm hide）",
            "Root · 强制停止",
            "Dhizuku · 挂起（需安装 Dhizuku）",
            "Island/Insular · 挂起（需安装 Island）",
            "系统级 · 禁用（priv-app，无需通道）",
            "系统级 · 启用（priv-app）",
            "Device Owner · 挂起（需本应用为设备所有者）"
        )
        val acts = arrayOf("freeze", "suspend", "hide", "forcestop", "freeze", "suspend", "hide", "forcestop")
        android.app.AlertDialog.Builder(this)
            .setTitle("冻结方式 · " + e.label)
            .setItems(modes) { _, which ->
                when {
                    which < 8 -> {
                        val chan = if (which < 4) Priv.Kind.SHIZUKU else Priv.Kind.ROOT
                        danger("冻结 " + e.label, "工作模式：" + modes[which] + "\n应用会从桌面消失或无法启动，用「解冻」恢复。", false) {
                            runBg {
                                after(
                                    Ops.run(this, acts[which], e.pkg, "", chan),
                                    "冻结(" + modes[which].substringBefore("（") + ") · " + e.label
                                )
                            }
                        }
                    }
                    which == 8 -> sysFreeze(e, false)
                    which == 9 -> sysFreeze(e, true)
                    which == 10 -> toast("Dhizuku 未接入：需要安装 Dhizuku 并授权")
                    which == 11 -> toast("Island/Insular 未接入：需要安装 Island 并启用它的挂起权限")
                    else -> toast("Device Owner 未接入：需要把本应用设为设备所有者（dpm set-device-owner）")
                }
            }
            .show()
    }

    /** 从「已卸载」里挑一个装回来（Canta 的恢复） */
    private fun restorePick() {
        runBg {
            val all = Ops.listPackages(this, "listuninstalled")
            val installed = Repo.apps.map { it.pkg }.toSet()
            val un = all.filter { it !in installed }
            main.post {
                if (un.isEmpty()) {
                    toast("没有已卸载的应用")
                    return@post
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("恢复应用（共 " + un.size + " 个）")
                    .setItems(un.toTypedArray()) { _, i ->
                        val pkg = un[i]
                        runBg { after(Ops.run(this, "restore", pkg), "恢复 · " + pkg) }
                        uninstalledLoaded = false
                    }
                    .show()
            }
        }
    }

    /** 屏幕灰度：daltonizer 灰度模式，纯系统设置，不改屏幕亮度 */
    private fun grayDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("屏幕灰度")
            .setItems(arrayOf("开启灰度", "关闭灰度（恢复彩色）")) { _, which ->
                runBg {
                    after(
                        Ops.run(this, if (which == 0) "gray_on" else "gray_off", "com.guang.lab"),
                        if (which == 0) "屏幕灰度 · 开" else "屏幕灰度 · 关"
                    )
                }
            }
            .show()
    }

    private fun doUninstall(e: AppEntry) {
        danger(
            "卸载 " + e.label,
            (if (e.system) "系统应用：pm uninstall --user 0，只对当前用户卸载，随时可用「恢复应用」装回。"
            else "第三方应用：pm uninstall --user 0 会真删 APK，无法恢复，请确认。"),
            true
        ) {
            runBg {
                after(Ops.run(this, "uninstall", e.pkg), "卸载 · " + e.label)
                uninstalled = emptyList()
                uninstalledLoaded = false
            }
        }
    }

    private fun danger(title: String, msg: String, warn: Boolean, body: () -> Unit) {
        if (Prefs.confirm(this)) confirm(title, msg, warn) { body() } else body()
    }

    private companion object {
        const val REQ_SHIZUKU = 4001
    }
}
