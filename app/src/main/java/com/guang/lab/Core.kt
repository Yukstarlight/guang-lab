package com.guang.lab

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
    val uid: Int
)

/** 目标应用的可写清单 */
data class Target(
    val pkg: String,
    val label: String,
    val perms: List<String>,
    val accessibility: List<String>,
    val listeners: List<String>,
    val admins: List<String>
)

object Repo {

    private val icons = ConcurrentHashMap<String, Drawable>()

    @Volatile var apps: List<AppEntry> = emptyList()
        private set
    @Volatile var loaded = false
        private set
    @Volatile var loading = false
        private set

    private const val P_ACC = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    private const val P_NL = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    private const val P_ADMIN = "android.permission.BIND_DEVICE_ADMIN"

    /** 非 dangerous、但 shell 有权授予的高价值权限 */
    private val SPECIAL = setOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.READ_LOGS",
        "android.permission.DUMP",
        "android.permission.CHANGE_CONFIGURATION",
        "android.permission.BATTERY_STATS",
        "android.permission.SET_ANIMATION_SCALE",
        "android.permission.READ_PRECISE_PHONE_STATE"
    )

    fun load(ctx: Context) {
        loading = true
        val pm = ctx.packageManager
        val list = ArrayList<AppEntry>(256)
        try {
            for (ai in pm.getInstalledApplications(0)) {
                if (ai.packageName == ctx.packageName) continue
                list.add(
                    AppEntry(
                        pkg = ai.packageName,
                        label = try {
                            pm.getApplicationLabel(ai).toString()
                        } catch (t: Throwable) {
                            ai.packageName
                        },
                        system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        enabled = ai.enabled,
                        uid = ai.uid
                    )
                )
            }
        } catch (t: Throwable) {
        }
        apps = list.sortedBy { it.label.lowercase() }
        loaded = true
        loading = false
    }

    fun find(ctx: Context, pkg: String): AppEntry? {
        apps.firstOrNull { it.pkg == pkg }?.let { return it }
        // 列表可能还没加载完，兜底现查
        val pm = ctx.packageManager
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            AppEntry(pkg, pm.getApplicationLabel(ai).toString(), (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0, ai.enabled, ai.uid)
        } catch (t: Throwable) {
            null
        }
    }

    fun icon(ctx: Context, pkg: String): Drawable? {
        icons[pkg]?.let { return it }
        return try {
            ctx.packageManager.getApplicationIcon(pkg).also { icons[pkg] = it }
        } catch (t: Throwable) {
            null
        }
    }

    fun describe(ctx: Context, pkg: String): Target? {
        val pm = ctx.packageManager
        val pi = try {
            pm.getPackageInfo(
                pkg,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
            )
        } catch (t: Throwable) {
            return null
        }
        val perms = pi.requestedPermissions?.toList().orEmpty()
            .filter { grantable(pm, it) }
            .sorted()
        val services = pi.services?.toList().orEmpty()
        return Target(
            pkg = pkg,
            label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (t: Throwable) {
                pkg
            },
            perms = perms,
            accessibility = services.filter { it.permission == P_ACC }.mapNotNull { it.name },
            listeners = services.filter { it.permission == P_NL }.mapNotNull { it.name },
            admins = pi.receivers?.toList().orEmpty().filter { it.permission == P_ADMIN }.mapNotNull { it.name }
        )
    }

    /** 该应用声明过的全部权限 + 当前是否已授予 */
    fun permStates(ctx: Context, pkg: String): List<Pair<String, Boolean>> {
        val pm = ctx.packageManager
        val pi = try {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        } catch (t: Throwable) {
            return emptyList()
        }
        return pi.requestedPermissions?.map { p ->
            p to (pm.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED)
        }.orEmpty().sortedBy { it.first }
    }

    private fun grantable(pm: PackageManager, perm: String): Boolean {
        if (perm in SPECIAL) return true
        return try {
            val info = pm.getPermissionInfo(perm, 0)
            val base = info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
            val dev = info.protectionLevel and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0
            base == PermissionInfo.PROTECTION_DANGEROUS || dev
        } catch (t: Throwable) {
            false
        }
    }
}

object Log {
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(s: String) {
        synchronized(buf) {
            val line = fmt.format(Date()) + "  " + if (s.length > 90) s.take(90) + "…" else s
            buf.addLast(line)
            while (buf.size > 300) buf.removeFirst()
        }
    }

    fun all(): List<String> = synchronized(buf) { buf.toList() }

    fun clear() = synchronized(buf) { buf.clear() }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("guang", Context.MODE_PRIVATE)

    /** 0=自动 1=Shizuku 2=ADB 3=Root */
    fun chan(ctx: Context): Int = sp(ctx).getInt("chan", 0)
    fun setChan(ctx: Context, v: Int) = sp(ctx).edit().putInt("chan", v).apply()

    fun confirm(ctx: Context): Boolean = sp(ctx).getBoolean("confirm", true)
    fun setConfirm(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("confirm", v).apply()

    /** 首次进入：免责声明 + 其他产品提示只弹一次 */
    fun greeted(ctx: Context): Boolean = sp(ctx).getBoolean("greeted", false)
    fun setGreeted(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("greeted", v).apply()
}
