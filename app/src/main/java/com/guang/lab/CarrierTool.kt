package com.guang.lab

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.TelephonyManager
import rikka.shizuku.ShizukuBinderWrapper

/**
 * SIM 运营商配置覆盖 —— 逻辑移植自 Ackites/Nrfr（Apache-2.0）。
 *
 * carrier_config 是 @SystemApi 服务，公开 SDK 里没有 TelephonyFrameworkInitializer，
 * 所以这里走反射拿 binder；再用 ShizukuBinderWrapper 以 shell 身份转发，
 * 否则 Binder 会以「权限不足」拒绝普通应用。
 *
 * transaction code 来自 AOSP ICarrierConfigLoader.aidl 的声明顺序：
 *   1 = getConfigForSubId(int, String)
 *   3 = overrideConfig(int, in PersistableBundle, boolean)
 */
object CarrierTool {

    private const val DESCRIPTOR = "com.android.internal.telephony.ICarrierConfigLoader"
    private const val T_GET = 1
    private const val T_OVERRIDE = 3

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31

    data class Sim(val slot: Int, val subId: Int, val carrier: String, val country: String?, val name: String?)

    /// carrier_config binder：先试 TelephonyFrameworkInitializer（反射），再退回 ServiceManager
    private fun rawBinder(): IBinder? {
        try {
            val c = Class.forName("android.telephony.TelephonyFrameworkInitializer")
            val mgr = c.getMethod("getTelephonyServiceManager").invoke(null) ?: return null
            val reg = mgr.javaClass.getMethod("getCarrierConfigServiceRegisterer").invoke(mgr) ?: return null
            val b = reg.javaClass.getMethod("get").invoke(reg)
            if (b is IBinder) return b
        } catch (t: Throwable) {
        }
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, "carrier_config") as? IBinder
        } catch (t: Throwable) {
            null
        }
    }

    private fun wrapped(): IBinder? {
        val b = rawBinder() ?: return null
        return try {
            ShizukuBinderWrapper(b)
        } catch (t: Throwable) {
            null
        }
    }

    private fun readConfig(b: IBinder, subId: Int): PersistableBundle? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(subId)
            data.writeString("com.guang.lab")
            b.transact(T_GET, data, reply, 0)
            reply.readException()
            if (reply.readInt() != 0) PersistableBundle.CREATOR.createFromParcel(reply) else null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun writeConfig(b: IBinder, subId: Int, overrides: PersistableBundle?, persist: Boolean) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(subId)
            if (overrides != null) {
                data.writeInt(1)
                overrides.writeToParcel(data, 0)
            } else {
                data.writeInt(0)
            }
            data.writeInt(if (persist) 1 else 0)
            b.transact(T_OVERRIDE, data, null, 0)
        } finally {
            data.recycle()
        }
    }

    private fun subIds(): List<Int> {
        val out = ArrayList<Int>()
        // SubscriptionManager.getSubId(int) 是 @SystemApi，反射调
        try {
            val m = Class.forName("android.telephony.SubscriptionManager")
                .getMethod("getSubId", Int::class.javaPrimitiveType)
            for (slot in 0..1) {
                val arr = m.invoke(null, slot)
                if (arr is IntArray && arr.isNotEmpty()) out.add(arr[0])
            }
        } catch (t: Throwable) {
        }
        if (out.isEmpty()) {
            out.add(1)
            out.add(2)
        }
        return out
    }

    fun load(ctx: Context, slotCount: Int = 2): List<Sim> {
        val res = ArrayList<Sim>()
        val b = wrapped()
        val ids = subIds().take(slotCount.coerceAtLeast(1))
        ids.forEachIndexed { idx, subId ->
            var country: String? = null
            var name: String? = null
            try {
                val c = if (b != null) readConfig(b, subId) else null
                if (c != null) {
                    country = c.getString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING)
                    if (c.getBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, false)) {
                        name = c.getString(CarrierConfigManager.KEY_CARRIER_NAME_STRING)
                    }
                }
            } catch (t: Throwable) {
            }
            val carrier = try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.createForSubscriptionId(subId).networkOperatorName.orEmpty()
            } catch (t: Throwable) {
                ""
            }
            res.add(Sim(idx + 1, subId, carrier, country, name))
        }
        return res
    }

    fun apply(subId: Int, country: String?, name: String?): String? {
        if (!supported) return "需要 Android 12+"
        val b = wrapped() ?: return "拿不到 carrier_config 服务（Shizuku 未运行/未授权？）"
        return try {
            val pb = PersistableBundle()
            if (!country.isNullOrEmpty() && country.length == 2) {
                pb.putString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING, country.lowercase())
            }
            if (!name.isNullOrEmpty()) {
                pb.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
                pb.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, name)
            }
            writeConfig(b, subId, pb, true)
            null
        } catch (t: Throwable) {
            t.javaClass.simpleName + ": " + (t.message ?: "")
        }
    }

    fun reset(subId: Int): String? {
        if (!supported) return "需要 Android 12+"
        val b = wrapped() ?: return "拿不到 carrier_config 服务（Shizuku 未运行/未授权？）"
        return try {
            writeConfig(b, subId, null, true)
            null
        } catch (t: Throwable) {
            t.javaClass.simpleName + ": " + (t.message ?: "")
        }
    }

    val COUNTRIES = listOf(
        "CN" to "中国", "HK" to "中国香港", "MO" to "中国澳门", "TW" to "中国台湾",
        "JP" to "日本", "KR" to "韩国", "SG" to "新加坡", "MY" to "马来西亚",
        "TH" to "泰国", "VN" to "越南", "ID" to "印度尼西亚", "PH" to "菲律宾",
        "IN" to "印度", "AU" to "澳大利亚", "NZ" to "新西兰",
        "US" to "美国", "CA" to "加拿大", "MX" to "墨西哥", "BR" to "巴西", "AR" to "阿根廷",
        "GB" to "英国", "DE" to "德国", "FR" to "法国", "IT" to "意大利",
        "ES" to "西班牙", "PT" to "葡萄牙", "RU" to "俄罗斯", "ZA" to "南非"
    )
}