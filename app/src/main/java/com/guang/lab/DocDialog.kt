package com.guang.lab

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 法律文档 UI —— 照抄 NekoType 的 NekoDialog：
 * 入口是「标题    ›」行，正文是 92%×90% 的可滚动、可长按复制对话框，
 * 弹入弹出走它的 cyber_pop_enter / cyber_pop_exit（左滑 + 淡入 + 0.96 缩放）。
 */
object DocDialog {

    fun builder(ctx: Context): AlertDialog.Builder = AlertDialog.Builder(ctx, R.style.AppDialog)

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** 可点条目行（左侧标题 + 右侧箭头） */
    fun row(ctx: Context, label: String, onClick: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = "$label    ›"
        t.textSize = 14f
        t.setTextColor(ctx.col(R.color.accent_purple))
        t.setPadding(dp(ctx, 4), dp(ctx, 14), dp(ctx, 4), dp(ctx, 14))
        t.isClickable = true
        t.isFocusable = true
        t.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        t.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        t.setOnClickListener { onClick() }
        return t
    }

    /** 展示长文档：可滚动、占屏 92%×90%、可长按复制、带弹入弹出动画 */
    fun showDoc(ctx: Context, title: String, body: String) {
        val tv = TextView(ctx)
        tv.text = body
        tv.textSize = 12.5f
        tv.setTextColor(ctx.col(R.color.fg_0))
        tv.setLineSpacing(0f, 1.35f)
        tv.setTextIsSelectable(true)

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(ctx, 24), dp(ctx, 4), dp(ctx, 24), dp(ctx, 8))
        box.addView(tv)

        val sc = ScrollView(ctx)
        sc.addView(box, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val d = builder(ctx)
            .setTitle(title)
            .setView(sc)
            .setPositiveButton("关闭", null)
            .create()
        d.show()
        try {
            val dm = ctx.resources.displayMetrics
            d.window?.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.9f).toInt())
            d.window?.setWindowAnimations(R.style.PopAnim)
        } catch (t: Throwable) {
        }
    }
}
