package com.guang.lab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

class DetailActivity : Activity() {

    private lateinit var pkg: String
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private var entry: AppEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra("pkg").orEmpty()
        if (pkg.isEmpty()) {
            finish()
            return
        }

        val root = box(true)
        root.setBackgroundColor(col(R.color.bg_0))

        val head = box(false, 14f, 10f)
        head.gravity = Gravity.CENTER_VERTICAL
        val back = tv("‹", 26f, R.color.fg_0)
        back.setPadding(px(8f), 0, px(14f), 0)
        back.isClickable = true
        back.setOnClickListener { finish() }
        head.addView(back)
        head.addView(tv("应用详情", 16f, R.color.fg_0, true))
        root.addView(head)

        val sc = ScrollView(this)
        body = box(true, 16f, 4f)
        sc.addView(body)
        root.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        fill()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized && body.childCount > 0) fill()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun fill() {
        body.removeAllViews()
        val e = Repo.find(this, pkg)
        entry = e

        // 头部
        val cardTop = card()
        val row = box(false)
        val iv = ImageView(this)
        iv.layoutParams = LinearLayout.LayoutParams(px(48f), px(48f))
        iv.setImageDrawable(Repo.icon(this, pkg))
        row.addView(iv)
        val col = box(true)
        col.setPadding(px(12f), 0, 0, 0)
        col.addView(tv(e?.label ?: pkg, 17f, R.color.fg_0, true))
        col.addView(tv(pkg, 10.5f, R.color.fg_2))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cardTop.addView(row)

        val st = box(false)
        st.setPadding(0, px(10f), 0, 0)
        st.addView(pill(if (e == null) "未安装" else if (!e.enabled) "已冻结" else "已启用",
            if (e != null && !e.enabled) R.color.err_red else R.color.ok_green))
        st.addView(pill("uid " + (e?.uid ?: -1), R.color.fg_2).apply { setPadding(px(9f), px(4f), px(9f), px(4f)) })
        if (e != null && e.system) st.addView(pill("系统应用", R.color.warn_amber))
        cardTop.addView(st)
        body.add(cardTop)

        // 操作
        body.add(section("操作"), 12f)
        val ops = listOf(
            Pair(if (e != null && !e.enabled) "解冻应用" else "冻结应用") { freeze() },
            Pair("强制停止") { simple("forcestop", "强制停止") },
            Pair("全部授权") { grantAll(false) },
            Pair("全部撤销") { grantAll(true) },
            Pair("打开应用") { simple("start", "打开应用") },
            Pair("清除数据") { clearData() },
            Pair("卸载") { uninstall() },
            Pair("恢复应用") { simple("restore", "恢复应用") },
            Pair("应用信息") { appInfo() }
        )
        var rowOps: LinearLayout? = null
        ops.forEachIndexed { i, o ->
            if (i % 3 == 0) {
                rowOps = box(false)
                body.add(rowOps!!, 6f)
            }
            rowOps!!.addCell(btnSmall(o.first) { o.second() }, 1f, if (i % 3 != 0) 6f else 0f)
        }

        // 权限
        body.add(section("权限开关"), 14f)
        val permCard = card()
        permCard.addView(tv("勾选即 pm grant，取消即 pm revoke。", 10.5f, R.color.fg_2).apply { setPadding(0, 0, 0, px(6f)) })
        val perms = Repo.permStates(this, pkg)
        if (perms.isEmpty()) {
            permCard.addView(tv("读不到权限列表", 12f, R.color.fg_2))
        }
        for ((name, granted) in perms) {
            val r = box(false)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, px(5f), 0, px(5f))
            val t = tv(name.removePrefix("android.permission."), 11.5f, if (granted) R.color.fg_0 else R.color.fg_1)
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(this)
            sw.isChecked = granted
            sw.buttonTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent_purple))
            sw.setOnCheckedChangeListener { _, checked ->
                io.execute {
                    val act = if (checked) "perm_grant" else "perm_revoke"
                    val res = Ops.run(this, act, pkg, name)
                    val ok = res.ok && Ops.parse(res.out).all { it.ok }
                    Log.add((if (checked) "授予 " else "撤销 ") + name + " → " + if (ok) "ok" else "fail")
                    main.post {
                        if (!ok) {
                            sw.setOnCheckedChangeListener(null)
                            sw.isChecked = !checked
                            sw.setOnCheckedChangeListener { _, c -> toggle(c, name) }
                            toast("失败：" + res.out.lines().firstOrNull { it.startsWith("R|") }.orEmpty())
                        }
                    }
                }
            }
            r.addView(sw)
            permCard.addView(r)
        }
        body.add(permCard)

        // 组件
        io.execute {
            val t = Repo.describe(this, pkg)
            main.post {
                if (t == null) return@post
                body.add(section("组件能力"), 14f)
                val c = card()
                c.addView(line("无障碍服务", t.accessibility.size, t.accessibility.firstOrNull()))
                c.addView(line("通知监听", t.listeners.size, t.listeners.firstOrNull()))
                c.addView(line("设备管理员", t.admins.size, t.admins.firstOrNull()))
                if (t.accessibility.isNotEmpty()) {
                    c.add(btn("一键开无障碍", true) { accOn(t) }, 8f)
                }
                body.add(c)
                body.add(gap(24f))
            }
        }
        body.add(gap(24f))
    }

    /** 给 Switch 用（重新注册监听时避免递归） */
    private fun toggle(checked: Boolean, perm: String) {
        io.execute {
            val act = if (checked) "perm_grant" else "perm_revoke"
            Ops.run(this, act, pkg, perm)
            main.post { fill() }
        }
    }

    private fun line(name: String, count: Int, first: String?): View {
        val r = box(false)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, px(3f), 0, px(3f))
        r.addView(tv(name, 12f, R.color.fg_1), LinearLayout.LayoutParams(px(90f), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(
            tv(if (count == 0) "无" else "$count 个" + (first?.let { " · " + it.substringAfterLast('.') } ?: ""), 11.5f,
                if (count == 0) R.color.fg_2 else R.color.ok_green),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return r
    }

    private fun btnSmall(label: String, onClick: () -> Unit): TextView {
        val t = tv(label, 12f, R.color.fg_0)
        t.gravity = Gravity.CENTER
        t.setPadding(px(6f), px(11f), px(6f), px(11f))
        t.background = GradientDrawable().apply {
            cornerRadius = px(12f).toFloat()
            setColor(col(R.color.card_alt))
            setStroke(px(1f), col(R.color.border))
        }
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun simple(action: String, label: String) {
        io.execute {
            val r = Ops.run(this, action, pkg)
            Log.add(label + " " + pkg + " → " + if (r.ok) "ok" else "fail")
            main.post { report(label, r) }
        }
    }

    private fun freeze() {
        val e = entry
        val toFreeze = e == null || e.enabled
        io.execute {
            val r = Ops.run(this, if (toFreeze) "freeze" else "unfreeze", pkg)
            Log.add((if (toFreeze) "冻结 " else "解冻 ") + pkg + " → " + if (r.ok) "ok" else "fail")
            main.post {
                report(if (toFreeze) "冻结" else "解冻", r)
                fill()
            }
        }
    }

    private fun grantAll(revoke: Boolean) {
        io.execute {
            val t = Repo.describe(this, pkg)
            if (t == null) {
                main.post { toast("读不到清单") }
                return@execute
            }
            val script = Ops.grantScript(this, t, revoke) ?: return@execute
            val r = Priv.exec(this, script, 90_000)
            Log.add((if (revoke) "撤销全部 " else "全部授权 ") + pkg + " → " + if (r.ok) "ok" else "fail")
            main.post {
                report(if (revoke) "撤销全部" else "全部授权", r, script)
                fill()
            }
        }
    }

    private fun clearData() {
        confirm("清除数据", "pm clear 会删掉 " + pkg + " 的全部数据，不可撤销。", true) {
            simple("clear", "清除数据")
        }
    }

    private fun uninstall() {
        confirm("卸载", "pm uninstall --user 0 " + pkg + "。系统应用可在「应用 → 已卸载」恢复。", true) {
            io.execute {
                val r = Ops.run(this, "uninstall", pkg)
                Log.add("卸载 " + pkg + " → " + if (r.ok) "ok" else "fail")
                main.post {
                    report("卸载", r)
                    fill()
                }
            }
        }
    }

    private fun accOn(t: Target) {
        io.execute {
            var last = Priv.R(false, "无服务", Priv.Kind.NONE)
            for (c in t.accessibility) last = Ops.run(this, "acc_on", pkg, c)
            main.post { report("无障碍", last) }
        }
    }

    private fun appInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)))
        } catch (e: Throwable) {
            toast("打不开系统设置")
        }
    }
}
