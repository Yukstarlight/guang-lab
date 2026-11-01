package com.guang.lab

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 特权通道门面：三条后路，按优先级自动挑一条能用的。
 *
 * 1. Shizuku —— `IShizukuService.newProcess()` 由服务端以 shell 身份 fork `sh -c`
 * 2. 无线 ADB —— 本机直连无线调试端口（见 [Adb]），不需要 Shizuku
 * 3. Root —— `su -c`
 *
 * 调用方只传「脚本字符串」，通道自己决定谁去执行。
 */
object Priv {

    enum class Kind(val title: String) {
        NONE("自动"),
        SHIZUKU("Shizuku"),
        ADB("无线 ADB"),
        ROOT("Root"),
        PRIVAPP("系统级")
    }

    data class R(val ok: Boolean, val out: String, val chan: Kind)

    data class St(
        val shizukuAlive: Boolean,
        val shizukuPerm: Boolean,
        val shizukuVer: Int,
        val adbConnected: Boolean,
        val adbState: String,
        val root: Boolean
    )

    @Volatile var lastChan: Kind = Kind.NONE
        private set
    @Volatile var lastError: String? = null
        private set

    // ---------- 状态 ----------

    fun status(ctx: Context): St = St(
        shizukuAlive = shizukuAlive(),
        shizukuPerm = shizukuPermitted(),
        shizukuVer = try {
            Shizuku.getVersion()
        } catch (t: Throwable) {
            -1
        },
        adbConnected = Adb.connected,
        adbState = Adb.state,
        root = hasRoot()
    )

    fun shizukuInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (t: Throwable) {
        false
    }

    fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun shizukuPermitted(): Boolean = try {
        shizukuAlive() && Shizuku.checkSelfPermission() == 0
    } catch (t: Throwable) {
        false
    }

    fun requestShizuku(code: Int) {
        try {
            Shizuku.requestPermission(code)
        } catch (t: Throwable) {
        }
    }

    private var rootCheckedAt = 0L
    @Volatile private var rootCached = false
    @Volatile private var rootProbed = false
    @Volatile private var rootProbing = false

    /**
     * 主线程安全版：只读缓存。首次调用立刻返回 false，同时起一个后台线程去探 su。
     * （su 在某些 ROM 上会弹授权框卡住几秒，绝不能阻塞 UI。）
     */
    fun hasRoot(): Boolean {
        if (!rootProbed) {
            if (!rootProbing) {
                rootProbing = true
                val t = Thread {
                    try {
                        rootCached = probeRoot()
                    } catch (e: Throwable) {
                    }
                    rootProbed = true
                    rootProbing = false
                }
                t.isDaemon = true
                t.start()
            }
            return false
        }
        // 缓存 20 秒，避免频繁 fork
        if (System.currentTimeMillis() - rootCheckedAt > 20_000) {
            val t = Thread {
                try {
                    rootCached = probeRoot()
                } catch (e: Throwable) {
                }
            }
            t.isDaemon = true
            t.start()
        }
        return rootCached
    }

    /** 真去跑一次 su（仅在后台线程用） */
    fun probeRoot(): Boolean {
        rootCheckedAt = System.currentTimeMillis()
        return try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(4, TimeUnit.SECONDS)
            out.contains("uid=0")
        } catch (t: Throwable) {
            false
        }
    }

    // ---------- 执行 ----------

    fun exec(ctx: Context, script: String, timeoutMs: Long = 60_000, prefer: Kind = Kind.NONE): R {
        val pref = Prefs.chan(ctx)
        val order = when {
            // 工作模式指定了通道就只走它（Hail 的 shizuku_*/su_* 语义）
            prefer != Kind.NONE -> listOf(prefer)
            pref == 1 -> listOf(Kind.SHIZUKU, Kind.ADB, Kind.ROOT)
            pref == 2 -> listOf(Kind.ADB, Kind.SHIZUKU, Kind.ROOT)
            pref == 3 -> listOf(Kind.ROOT, Kind.SHIZUKU, Kind.ADB)
            else -> listOf(Kind.SHIZUKU, Kind.ADB, Kind.ROOT)
        }
        val errs = StringBuilder()
        for (k in order) {
            val out: String? = when (k) {
                Kind.SHIZUKU -> execShizuku(script, timeoutMs)
                Kind.ADB -> Adb.exec(ctx, script, timeoutMs)
                Kind.ROOT -> execRoot(script, timeoutMs)
                Kind.PRIVAPP -> null
                Kind.NONE -> null
            }
            if (out != null) {
                lastChan = k
                android.util.Log.i("GuangLab", "exec via " + k.title + " len=" + out.length + " head=" + out.lineSequence().firstOrNull().orEmpty().take(120))
                return R(true, out, k)
            }
            android.util.Log.w("GuangLab", "channel " + k.title + " failed: " + (when (k) {
                Kind.SHIZUKU -> if (!shizukuAlive()) "notRunning" else if (!shizukuPermitted()) "noPermission" else "execFailed"
                Kind.ADB -> (Adb.lastError ?: "notConnected")
                Kind.ROOT -> "noRoot"
                else -> "n/a"
            }))
            errs.append(k.title).append("：").append(
                when (k) {
                    Kind.SHIZUKU -> if (!shizukuAlive()) "未运行" else if (!shizukuPermitted()) "未授权" else "执行失败"
                    Kind.ADB -> Adb.lastError ?: "未连接"
                    else -> "无 root"
                }
            ).append("  ")
        }
        lastError = errs.toString().trim()
        return R(false, lastError ?: "没有可用通道", Kind.NONE)
    }

    // ---------- Shizuku 登记表刷新 ----------

    /**
     * 通知 Shizuku 服务端「这个包变了」，让它重新读权限状态。
     * IShizukuService.dispatchPackageChanged(Intent) = transaction 16（按 AIDL 声明顺序）。
     * 我们外部 pm grant 不会被 Shizuku 感知，调这一条等于替它补一次刷新。
     */
    fun dispatchPackageChanged(pkg: String): Boolean {
        return try {
            val binder = try {
                Shizuku.getBinder()
            } catch (t: Throwable) {
                null
            } ?: return false
            val data = android.os.Parcel.obtain()
            try {
                data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
                val intent = android.content.Intent(android.content.Intent.ACTION_PACKAGE_CHANGED)
                    .setData(android.net.Uri.parse("package:" + pkg))
                data.writeInt(1)
                intent.writeToParcel(data, 0)
                binder.transact(16, data, null, android.os.IBinder.FLAG_ONEWAY)
                true
            } finally {
                data.recycle()
            }
        } catch (t: Throwable) {
            false
        }
    }
    // ---------- Shizuku ----------

    private fun execShizuku(script: String, timeoutMs: Long): String? {
        if (!shizukuPermitted()) return null
        val binder = try {
            Shizuku.getBinder()
        } catch (t: Throwable) {
            null
        } ?: return null
        val svc = try {
            moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
        } catch (t: Throwable) {
            null
        } ?: return null
        val proc = try {
            svc.newProcess(arrayOf("sh", "-c", script), null, null)
        } catch (t: Throwable) {
            null
        } ?: return null

        val out = StringBuilder()
        val err = StringBuilder()
        val drained = AtomicBoolean(false)
        val tOut = Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream).use { i ->
                    InputStreamReader(i, Charsets.UTF_8).use { r -> out.append(r.readText()) }
                }
                drained.set(true)
            } catch (t: Throwable) {
            }
        }
        val tErr = Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream).use { i ->
                    InputStreamReader(i, Charsets.UTF_8).use { r -> err.append(r.readText()) }
                }
            } catch (t: Throwable) {
            }
        }
        tOut.start()
        tErr.start()
        tOut.join(timeoutMs)
        if (!drained.get()) {
            try {
                proc.destroy()
            } catch (t: Throwable) {
            }
            return out.toString() + "\n" + err.toString() + "\n[读取超时]"
        }
        tErr.join(3000)
        return out.toString() + err.toString()
    }

    // ---------- 系统级（priv-app） ----------

    /** 装成系统应用并被授予 CHANGE_COMPONENT_ENABLED_STATE 时才算可用 */
    fun privappReady(ctx: Context): Boolean = try {
        ctx.checkSelfPermission("android.permission.CHANGE_COMPONENT_ENABLED_STATE") == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** 直接调 PackageManager 改 enabled 状态，不需要任何 shell 通道 */
    fun setEnabledPriv(ctx: Context, pkg: String, enable: Boolean): R? {
        if (!privappReady(ctx)) return null
        return try {
            val pm = ctx.packageManager
            pm.setApplicationEnabledSetting(
                pkg,
                if (enable) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                0
            )
            val info = pm.getApplicationInfo(pkg, 0)
            R(info.enabled == enable, "enabled=" + info.enabled, Kind.PRIVAPP)
        } catch (t: Throwable) {
            R(false, t.javaClass.simpleName + ": " + (t.message ?: ""), Kind.PRIVAPP)
        }
    }
    // ---------- Root ----------

    private fun execRoot(script: String, timeoutMs: Long): String? {
        // 这里是后台线程，可以直接真跑一次 su 探测
        if (!probeRoot()) return null
        return try {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val out = StringBuilder()
            val done = AtomicBoolean(false)
            val t = Thread {
                try {
                    p.inputStream.bufferedReader().use { r -> out.append(r.readText()) }
                } catch (e: Throwable) {
                }
                done.set(true)
            }
            t.start()
            t.join(timeoutMs)
            if (!done.get()) {
                p.destroy()
                return out.toString() + "\n[读取超时]"
            }
            p.waitFor(3, TimeUnit.SECONDS)
            out.toString()
        } catch (t: Throwable) {
            null
        }
    }
}
