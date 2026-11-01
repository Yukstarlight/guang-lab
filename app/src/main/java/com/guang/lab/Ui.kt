package com.guang.lab

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

fun Context.px(v: Float): Int = (v * resources.displayMetrics.density).toInt()
fun Context.col(id: Int): Int = getColor(id)

fun Context.tv(s: CharSequence, sp: Float = 14f, colorId: Int = R.color.fg_0, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(col(colorId))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

fun Context.box(vertical: Boolean = true, padH: Float = 0f, padV: Float = 0f): LinearLayout =
    LinearLayout(this).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setPadding(px(padH), px(padV), px(padH), px(padV))
    }

fun LinearLayout.add(v: View, top: Float = 0f): View {
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.topMargin = context.px(top)
    addView(v, lp)
    return v
}

/** 横向排布、等宽子项（网格用） */
fun LinearLayout.addCell(v: View, weight: Float = 1f, left: Float = 0f, top: Float = 0f): View {
    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
    lp.leftMargin = context.px(left)
    lp.topMargin = context.px(top)
    addView(v, lp)
    return v
}

/** 竖向填满剩余空间（ListView 用） */
fun LinearLayout.addFill(v: View, top: Float = 0f): View {
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
    lp.topMargin = context.px(top)
    addView(v, lp)
    return v
}

fun Context.gap(h: Float): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(1, px(h))
}

fun Context.round(fillId: Int, strokeId: Int = 0, radius: Float = 18f, gradient: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = px(radius).toFloat()
        if (gradient) {
            colors = intArrayOf(col(R.color.accent_purple), col(R.color.accent_gold))
            orientation = GradientDrawable.Orientation.TL_BR
        } else {
            setColor(col(fillId))
            if (strokeId != 0) setStroke(px(1f), col(strokeId))
        }
    }

fun Context.card(pad: Float = 15f, fill: Int = R.color.card, radius: Float = 18f, onClick: (() -> Unit)? = null): LinearLayout {
    val l = box(true)
    l.setPadding(px(pad), px(pad), px(pad), px(pad))
    val bg = round(fill, R.color.border, radius)
    l.background = if (onClick != null) RippleDrawable(ColorStateList.valueOf(0x33C8A2FF), bg, null) else bg
    if (onClick != null) {
        l.isClickable = true
        l.setOnClickListener { onClick() }
    }
    return l
}

fun Context.pill(s: CharSequence, colorId: Int = R.color.fg_1, bgId: Int = R.color.card_alt): TextView =
    tv(s, 10.5f, colorId).apply {
        setPadding(px(9f), px(4f), px(9f), px(4f))
        background = GradientDrawable().apply {
            cornerRadius = px(100f).toFloat()
            setColor(col(bgId))
        }
    }

fun Context.btn(label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
    val t = tv(label, 13.5f, if (primary) R.color.bg_0 else R.color.fg_0, bold = primary)
    t.gravity = Gravity.CENTER
    t.setPadding(px(10f), px(11f), px(10f), px(11f))
    val bg = GradientDrawable().apply {
        cornerRadius = px(13f).toFloat()
        setColor(col(if (primary) R.color.accent_purple else R.color.card_alt))
        if (!primary) setStroke(px(1f), col(R.color.border))
    }
    t.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
    t.isClickable = true
    t.setOnClickListener { onClick() }
    return t
}

fun Context.icon(id: Int, size: Float = 20f, tint: Int = R.color.accent_purple): ImageView =
    ImageView(this).apply {
        setImageResource(id)
        setColorFilter(col(tint))
        layoutParams = LinearLayout.LayoutParams(px(size), px(size))
    }

fun Context.section(t: String): TextView = tv(t, 12f, R.color.fg_1, bold = true).apply {
    setPadding(px(4f), px(6f), 0, px(6f))
}

fun Context.toast(s: CharSequence) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

fun Context.copyText(s: String, tip: String = "已复制") {
    try {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("guang-lab", s))
        toast(tip)
    } catch (t: Throwable) {
    }
}

fun Context.hideKeyboard(v: View) {
    try {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    } catch (t: Throwable) {
    }
}

fun Activity.confirm(title: String, msg: String, danger: Boolean = false, onOk: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(msg)
        .setPositiveButton(if (danger) "确认执行" else "确定") { _, _ -> onOk() }
        .setNegativeButton("取消", null)
        .show()
}

fun Activity.ask(title: String, hint: String, onOk: (String) -> Unit) {
    val e = EditText(this).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(col(R.color.fg_0))
        setHintTextColor(col(R.color.fg_2))
        setSingleLine()
        setPadding(px(24f), px(12f), px(24f), px(12f))
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(e)
        .setPositiveButton("确定") { _, _ -> onOk(e.text.toString().trim()) }
        .setNegativeButton("取消", null)
        .show()
}

/** 结果对话框：分组打勾/打叉，附「复制脚本」 */
fun Activity.report(title: String, r: Priv.R, script: String? = null) {
    val items = Ops.parse(r.out)
    if (items.isEmpty()) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(
                if (r.out.isBlank()) "没有输出（通道：${r.chan.title}）"
                else r.out.trim().takeLast(1600)
            )
            .setPositiveButton("知道了", null)
            .show()
        return
    }
    val okCount = items.count { it.state == St.OK }
    val skipCount = items.count { it.state == St.SKIP }
    val failCount = items.count { it.state == St.FAIL }
    val sb = SpannableStringBuilder()
    sb.append("通道 ").append(r.chan.title).append("    完成 ")
        .append((items.size - skipCount - failCount).toString()).append(" / ").append(items.size.toString())
    if (skipCount > 0) sb.append("   · 受限 ").append(skipCount.toString())
    if (failCount > 0) sb.append("   · 失败 ").append(failCount.toString())
    var last = ""
    for (item in items) {
        if (item.group != last) {
            last = item.group
            sb.append("\n\n【").append(Ops.groupName(item.group)).append("】")
        }
        sb.append("\n")
        val at = sb.length
        val mark = when (item.state) {
            St.OK -> "✓ "
            St.SKIP -> "⚠ "
            else -> "✗ "
        }
        sb.append(mark)
        val mc = when (item.state) {
            St.OK -> R.color.ok_green
            St.SKIP -> R.color.warn_amber
            else -> R.color.err_red
        }
        sb.setSpan(ForegroundColorSpan(col(mc)), at, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(item.key)
        if (item.state != St.OK && item.detail.isNotEmpty()) {
            val d = sb.length
            sb.append("  ").append(item.detail)
            sb.setSpan(ForegroundColorSpan(col(R.color.fg_2)), d, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    val b = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(sb)
        .setPositiveButton("知道了", null)
    if (script != null) b.setNeutralButton("复制脚本") { _, _ -> copyText(script) }
    b.show()
}

// ---------- 选应用 ----------

class PickAdapter(private val ctx: Context, private val all: List<AppEntry>) : BaseAdapter() {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val shown = ArrayList<AppEntry>(all)

    fun setQuery(q: String) {
        val s = q.trim().lowercase()
        shown.clear()
        if (s.isEmpty()) shown.addAll(all) else shown.addAll(
            all.filter { it.label.lowercase().contains(s) || it.pkg.lowercase().contains(s) }
        )
        notifyDataSetChanged()
    }

    override fun getCount(): Int = shown.size
    override fun getItem(position: Int): AppEntry = shown[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_pick, parent, false)
        val e = shown[position]
        (v.findViewById<View>(R.id.name) as TextView).text = e.label
        (v.findViewById<View>(R.id.pkg) as TextView).text = e.pkg
        val tag = v.findViewById<View>(R.id.tag) as TextView
        if (e.system) {
            tag.text = "系统"
            tag.visibility = View.VISIBLE
        } else {
            tag.visibility = View.GONE
        }
        val iv = v.findViewById<View>(R.id.icon) as ImageView
        val cached = Repo.icon(ctx, e.pkg)
        if (cached != null) {
            iv.setImageDrawable(cached)
        } else {
            iv.setImageDrawable(null)
            iv.tag = e.pkg
            pool.execute {
                val d = Repo.icon(ctx, e.pkg)
                main.post { if (iv.tag == e.pkg) iv.setImageDrawable(d) }
            }
        }
        return v
    }
}

fun Activity.pickApp(title: String, filter: (AppEntry) -> Boolean = { true }, onPick: (AppEntry) -> Unit) {
    if (!Repo.loaded) Repo.load(this)
    val all = Repo.apps.filter(filter)
    if (all.isEmpty()) {
        toast("没有可选应用")
        return
    }

    val root = box(true, 14f, 10f)
    val search = EditText(this).apply {
        hint = "搜索应用 / 包名"
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
    val adapter = PickAdapter(this, all)
    val list = ListView(this).apply {
        divider = null
        dividerHeight = 0
        setBackgroundColor(Color.TRANSPARENT)
        setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        this.adapter = adapter
    }
    root.add(search)
    root.addFill(list, 8f)

    val dlg = AlertDialog.Builder(this).setTitle(title).setView(root).setNegativeButton("取消", null).create()
    list.setOnItemClickListener { _, _, pos, _ ->
        val e = adapter.getItem(pos)
        dlg.dismiss()
        onPick(e)
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            adapter.setQuery(s?.toString().orEmpty())
        }

        override fun afterTextChanged(s: Editable?) {}
    })
    dlg.show()
    try {
        val dm = resources.displayMetrics
        dlg.window?.setLayout((dm.widthPixels * 0.94f).toInt(), (dm.heightPixels * 0.72f).toInt())
    } catch (t: Throwable) {
    }
}
