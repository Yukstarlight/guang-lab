package com.guang.lab

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

enum class St { OK, SKIP, FAIL }

data class Item(val group: String, val key: String, val state: St, val detail: String) {
    val ok: Boolean get() = state == St.OK
    val skip: Boolean get() = state == St.SKIP
}

/**
 * 操作脚本工厂：脚本正文全部在 res/raw 的 grant、revoke、ops 三个 .sh 里，
 * 这里只做「字符集校验 + 占位符替换」，所以任何用户输入都不可能变成任意命令。
 */
object Ops {

    val APPOPS = listOf(
        "SYSTEM_ALERT_WINDOW",
        "GET_USAGE_STATS",
        "MANAGE_EXTERNAL_STORAGE",
        "WRITE_SETTINGS",
        "RUN_IN_BACKGROUND",
        "RUN_ANY_IN_BACKGROUND",
        "START_FOREGROUND",
        "START_ACTIVITIES_FROM_BACKGROUND",
        "WAKE_LOCK",
        "POST_NOTIFICATION",
        "ACCESS_NOTIFICATIONS",
        "READ_CLIPBOARD",
        "WRITE_CLIPBOARD",
        "REQUEST_INSTALL_PACKAGES",
        "REQUEST_DELETE_PACKAGES",
        "PROJECT_MEDIA",
        "TAKE_AUDIO_FOCUS",
        "ACTIVATE_VPN",
        "TURN_SCREEN_ON",
        "MOCK_LOCATION",
        "BOOT_COMPLETED"
    )

    private val ACTIONS = setOf(
        "freeze", "unfreeze", "uninstall", "restore", "forcestop", "clear", "start",
        "battery", "unbattery", "appops", "perm_grant", "perm_revoke", "shizuku_on", "shizuku_off",
        "suspend", "hide", "gray_on", "gray_off", "install_priv", "scan_adb", "scan_pkg", "run_script", "server_state",
        "acc_on", "acc_off", "nl_on", "nl_off", "admin_on", "admin_off",
        "listdisabled", "listuninstalled"
    )

    private val PKG = Regex("[A-Za-z0-9._]{1,150}")
    private val CLS = Regex("[A-Za-z0-9._" + '$' + "]{1,200}")
    private val PERM = Regex("[A-Za-z0-9._]{1,150}")
    private val PATH_RE = Regex("/[A-Za-z0-9._/-]{1,300}")

    private val GROUP_ORDER = listOf("base", "perm", "shizuku", "op", "acc", "nl", "admin", "batt", "freeze", "canta", "act", "screen", "priv", "sys")

    private fun raw(ctx: Context, id: Int): String =
        ctx.resources.openRawResource(id).bufferedReader().use { it.readText() }

    /** 一键授权 / 撤销的合并脚本 */
    /** 这三个不是运行时权限，只能走 appops；放进 pm grant 必然失败 */
    private val APPOP_ONLY = setOf(
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.WRITE_SETTINGS",
        "android.permission.MANAGE_EXTERNAL_STORAGE"
    )

    fun grantScript(ctx: Context, t: Target, revoke: Boolean): String? {
        if (!PKG.matches(t.pkg)) return null
        val fn = if (revoke) "p" else "g"
        // API_V23 由脚本里 1.5 段专门处理，避免授两遍
        val perms = t.perms.filter {
            PERM.matches(it) && it !in APPOP_ONLY && it != "moe.shizuku.manager.permission.API_V23"
        }
        return raw(ctx, if (revoke) R.raw.revoke else R.raw.grant)
            .replace("__PKG__", t.pkg)
            .replace("__PERMS__", perms.joinToString("\n") { "$fn '$it'" })
            .replace("__OPS__", APPOPS.joinToString("\n") { "s '$it'" })
            .replace("__ACC__", t.accessibility.filter { CLS.matches(it) }.joinToString("\n") { "a '$it'" })
            .replace("__NL__", t.listeners.filter { CLS.matches(it) }.joinToString("\n") { "l '$it'" })
            .replace("__ADMIN__", t.admins.filter { CLS.matches(it) }.joinToString("\n") { "d '$it'" })
    }

    /** 单动作脚本 */
    fun opScript(ctx: Context, action: String, pkg: String, extra: String = ""): String? {
        if (action !in ACTIONS) return null
        if (!PKG.matches(pkg)) return null
        val e = when {
            extra.isEmpty() -> ""
            // 脚本路径：必须落在「目标包自己的 Android/data/<pkg>/ 下」——
            // 否则任何能写外置存储的应用都能塞个 .sh 让我们用 shell 身份执行
            action == "run_script" -> {
                val own = listOf(
                    "/sdcard/Android/data/" + pkg + "/",
                    "/storage/emulated/0/Android/data/" + pkg + "/",
                    "/storage/self/primary/Android/data/" + pkg + "/"
                )
                if (PATH_RE.matches(extra) && own.any { extra.startsWith(it) }) extra else return null
            }
            CLS.matches(extra) || PERM.matches(extra) -> extra
            action == "appops" && extra in APPOPS -> extra
            else -> return null
        }
        return raw(ctx, R.raw.ops)
            .replace("__A__", action)
            .replace("__P__", pkg)
            .replace("__E__", e)
    }

    fun run(ctx: Context, action: String, pkg: String, extra: String = "", prefer: Priv.Kind = Priv.Kind.NONE): Priv.R {
        val s = opScript(ctx, action, pkg, extra) ?: return Priv.R(false, "参数非法", Priv.Kind.NONE)
        android.util.Log.i("GuangLab", "ops " + action + " " + pkg + (if (extra.isEmpty()) "" else " " + extra))
        return Priv.exec(ctx, s, 60_000, prefer)
    }

    fun parse(out: String): List<Item> {
        val items = ArrayList<Item>()
        out.lineSequence().forEach { line ->
            val s = line.trim()
            if (!s.startsWith("R|")) return@forEach
            val f = s.split("|")
            if (f.size < 4) return@forEach
            val detail = if (f.size > 4) f.subList(4, f.size).joinToString("|").trim() else ""
            val st = when (f[3]) {
                "ok" -> St.OK
                "skip" -> St.SKIP
                else -> St.FAIL
            }
            items.add(Item(f[1], f[2], st, detail))
        }
        return items.sortedBy {
            val i = GROUP_ORDER.indexOf(it.group)
            if (i < 0) GROUP_ORDER.size else i
        }
    }

    fun groupName(g: String): String = when (g) {
        "base" -> "基础"
        "perm" -> "运行时权限"
        "shizuku" -> "Shizuku 授权"
        "op" -> "AppOps"
        "acc" -> "无障碍"
        "nl" -> "通知使用权"
        "admin" -> "设备管理员"
        "batt" -> "电池白名单"
        "freeze" -> "冻结"
        "canta" -> "卸载 / 恢复"
        "act" -> "应用操作"
        "screen" -> "屏幕"
        "priv" -> "系统级"
        else -> "系统"
    }

    /** 列表类动作（只跑一段 shell，结果逐行返回） */
    fun listPackages(ctx: Context, action: String): List<String> =
        try {
            val s = raw(ctx, R.raw.ops).replace("__A__", action).replace("__P__", "").replace("__E__", "")
            val r = Priv.exec(ctx, s, 20_000)
            r.out.lineSequence().map { it.trim() }.filter { it.matches(PKG) }.toList()
        } catch (t: Throwable) {
            emptyList()
        }
}
