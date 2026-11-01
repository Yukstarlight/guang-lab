package com.guang.lab

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 免 Shizuku 的「无线调试」通道。
 *
 * 原理：Android 11+ 的开发者选项里，无线调试本身就是一台跑在 127.0.0.1 上的
 * adbd（TLS）。用 6 位配对码完成 SPAKE2 配对后，本 App 就成了那台「电脑」，
 * 直接用 ADB 协议向本机 adbd 要一个 shell —— 拿到的是 shell(2000) 身份，
 * 和 Shizuku 一样的权限，且完全不需要电脑。
 *
 * 依赖 libadb-android（AppManager 作者 MuntashirAkon 的库）做 ADB 协议 + 配对。
 */
object Adb {

    @Volatile var connected = false
        private set

    @Volatile var state = "未连接"
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastPort = -1
        private set

    private var mgr: Mgr? = null
    private var privKey: PrivateKey? = null
    private var cert: Certificate? = null

    /** ADB 无线调试（TLS）从 Android 11 才有 */
    fun supported(): Boolean = android.os.Build.VERSION.SDK_INT >= 30

    private class Mgr(
        private val k: PrivateKey,
        private val c: Certificate,
        private val n: String
    ) : AbsAdbConnectionManager() {
        override fun getPrivateKey(): PrivateKey = k
        override fun getCertificate(): Certificate = c
        override fun getDeviceName(): String = n
    }

    @Synchronized
    fun connect(ctx: Context): String {
        if (!supported()) {
            state = "需要 Android 11+"
            return state
        }
        return try {
            loadKeys(ctx)
            val m = newMgr()
            val ok = m.autoConnect(ctx, 15_000)
            if (ok) {
                mgr = m
                connected = true
                state = "已连接"
                lastError = null
            } else {
                connected = false
                state = "连接失败"
                lastError = "autoConnect 返回 false"
            }
            state
        } catch (e: AdbPairingRequiredException) {
            connected = false
            state = "需要配对"
            lastError = e.message
            state
        } catch (t: Throwable) {
            connected = false
            state = "失败：" + (t.message ?: t.javaClass.simpleName)
            lastError = state
            state
        }
    }

    /**
     * 用 6 位配对码配对，成功后立刻连接。
     * 配对端口是随机端口，靠 mDNS（_adb-tls-pairing._tcp）现发现。
     */
    @Synchronized
    fun pair(ctx: Context, code: String): String {
        if (!supported()) {
            state = "需要 Android 11+"
            return state
        }
        if (code.length != 6 || code.any { it !in '0'..'9' }) {
            state = "配对码是 6 位数字"
            return state
        }
        return try {
            loadKeys(ctx)
            state = "正在找配对端口…"
            val port = discover(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 9000)
            if (port <= 0) {
                state = "没发现配对服务，先打开「无线调试」"
                return state
            }
            state = "正在配对（端口 $port）…"
            val m = newMgr()
            val paired = m.pair(port, code)
            if (!paired) {
                state = "配对失败：码错或已过期，关掉无线调试再开一次"
                return state
            }
            state = "配对成功，正在连接…"
            // 配对把我们的公钥写进了 adbd 的信任列表，后续用新连接即可
            return connect(ctx)
        } catch (t: Throwable) {
            connected = false
            state = "配对异常：" + (t.message ?: t.javaClass.simpleName)
            lastError = t.message
            state
        }
    }

    fun disconnect() {
        try {
            mgr?.disconnect()
        } catch (t: Throwable) {
        }
        try {
            mgr?.close()
        } catch (t: Throwable) {
        }
        mgr = null
        connected = false
        state = "未连接"
    }

    /** 跑一段脚本，返回输出；null = 通道不可用 */
    fun exec(ctx: Context, script: String, timeoutMs: Long = 60_000): String? {
        var m = mgr
        if (m == null || !m.isConnected) {
            connect(ctx)
            m = mgr
        }
        val use = m ?: return null
        var stream: AdbStream? = null
        return try {
            stream = use.openStream("shell:$script")
            val ins = stream.openInputStream()
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val done = CountDownLatch(1)
            val reader = Thread {
                try {
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        bos.write(buf, 0, n)
                    }
                } catch (t: Throwable) {
                }
                done.countDown()
            }
            reader.isDaemon = true
            reader.start()
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                try {
                    stream.close()
                } catch (t: Throwable) {
                }
                return String(bos.toByteArray(), Charsets.UTF_8) + "\n[读取超时]"
            }
            String(bos.toByteArray(), Charsets.UTF_8)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            connected = false
            null
        } finally {
            try {
                stream?.close()
            } catch (t: Throwable) {
            }
        }
    }

    // ---------- 内部 ----------

    private fun newMgr(): Mgr {
        val m = Mgr(privKey!!, cert!!, "guang-lab")
        m.setHostAddress("127.0.0.1")
        m.setApi(android.os.Build.VERSION.SDK_INT)
        m.setThrowOnUnauthorised(true)
        return m
    }

    private fun discover(ctx: Context, serviceType: String, timeoutMs: Long): Int {
        val latch = CountDownLatch(1)
        val port = AtomicInteger(-1)
        val mdns = try {
            AdbMdns(ctx, serviceType) { _, p ->
                if (p > 0) {
                    port.set(p)
                    latch.countDown()
                }
            }
        } catch (t: Throwable) {
            return -1
        }
        try {
            mdns.start()
        } catch (t: Throwable) {
            return -1
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
        }
        try {
            mdns.stop()
        } catch (t: Throwable) {
        }
        lastPort = port.get()
        return lastPort
    }

    private fun loadKeys(ctx: Context) {
        if (privKey != null && cert != null) return
        val dir = File(ctx.filesDir, "adb")
        if (!dir.exists()) dir.mkdirs()
        val kf = File(dir, "key.pk8")
        val cf = File(dir, "cert.der")
        if (kf.exists() && cf.exists()) {
            try {
                val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                val c = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
                privKey = k
                cert = c
                return
            } catch (t: Throwable) {
                kf.delete()
                cf.delete()
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val c = selfSigned(kp)
        kf.writeBytes(kp.private.encoded)
        cf.writeBytes(c.encoded)
        privKey = kp.private
        cert = c
    }

    /** ADB 的 TLS 客户端必须带证书，自己签一张（BouncyCastle） */
    private fun selfSigned(kp: java.security.KeyPair): X509Certificate {
        val prov = org.bouncycastle.jce.provider.BouncyCastleProvider()
        val name = org.bouncycastle.asn1.x500.X500Name("CN=Guang Lab")
        val from = Date(System.currentTimeMillis() - 86400000L)
        val to = Date(System.currentTimeMillis() + 10L * 365 * 86400000L)
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(System.currentTimeMillis()), from, to, name, kp.public
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val conv = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().setProvider(prov)
        return conv.getCertificate(builder.build(signer))
    }
}
