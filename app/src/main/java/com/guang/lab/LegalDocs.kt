package com.guang.lab

/**
 * 法务文本：免责声明 / 第三方共享清单 / 开源许可。
 * 结构对齐 NekoType 的 LegalDocs（同一套三份文档），内容按本应用的真实情况重写：
 * 本应用没有广告/统计/推送 SDK，唯一的网络活动是连本机 127.0.0.1 的 adbd。
 */
object LegalDocs {

    fun disclaimer(): String = """
        光 · 实验室 免责声明
        更新日期：2026-10-31

        【一、工具性质】
        本应用是一个系统工具，通过 Shizuku / 无线调试 ADB / Root 三条通道，以 shell 身份执行
        你主动触发的系统命令（授权权限、冻结、卸载、清数据等）。
        它不联网、不收集数据、不含广告与统计 SDK。

        【二、请只在自己的设备上使用】
        请仅对你拥有、或已获得明确许可的设备使用本工具。
        未经授权修改他人设备的系统设置，可能违法。

        【三、高危操作后果自负】
        以下操作不可撤销或难以撤销，执行前请确认：
        · 卸载：pm uninstall --user 0，第三方应用会被真删
        · 清除数据：pm clear，等于恢复出厂，登录态一并消失
        · 冻结：pm disable-user，应用会从桌面消失且无法启动（可解冻）
        · 撤销全部权限：会让应用立刻出现异常行为
        · 禁用无障碍：可能让依赖无障碍的应用直接失效
        开发者不对数据丢失、设备异常、账号风控等后果承担责任。

        【四、不提供绕过能力】
        本工具不提供绕过付费、破解、去订阅、绕过风控或规避应用自身安全机制的功能。

        【五、按现状提供】
        本软件按「现状」提供，不作任何明示或暗示担保。
        因使用本软件产生的直接或间接损失，作者不承担责任。
    """.trimIndent()

    fun thirdParty(): String = """
        第三方 SDK / 组件信息共享清单
        更新日期：2026-10-31

        结论先说：本应用不包含任何会收集个人信息、或向服务器上传数据的第三方 SDK
        （无广告、无统计、无推送、无社交、无崩溃上报）。没有账号体系。

        【一、使用的开源组件】
        · Shizuku API / Provider / AIDL 13.1.5 — dev.rikka.shizuku — Apache-2.0
          用途：以 shell 身份执行系统命令（需你自行安装并授权 Shizuku）
        · libadb-android 3.0.0 — MuntashirAkon — Apache-2.0
          用途：无线调试（ADB over TLS）连接本机 adbd
        · spake2-java（android 2.0.0）— MuntashirAkon — Apache-2.0
          用途：无线调试配对用的 SPAKE2 密钥交换，由 libadb 依赖
        · BouncyCastle bcprov / bcpkix 1.78 — The Legion of the Bouncy Castle — MIT
          用途：生成 ADB TLS 客户端自签名证书
        · Kotlin 标准库 / AndroidX — JetBrains / Google — Apache-2.0

        【二、数据流向】
        · 不向任何远程服务器发送数据
        · 唯一的网络活动：连接本机回环地址 127.0.0.1 上的 adbd（无线调试端口）
        · 清单里的 INTERNET 权限由 ADB 库声明，安卓要求本机 socket 也需要该权限

        【三、共享给第三方的个人信息】
        无。本应用不收集、不存储、不共享任何个人信息。
    """.trimIndent()

    fun licenses(): String = """
        开源许可（Open Source Licenses）

        本应用构建于以下开源项目之上，在此致谢。

        ── Apache License 2.0 ──
        AndroidX / Material Components — Google
        Kotlin 标准库 / kotlinx.coroutines — JetBrains
        Shizuku API（dev.rikka.shizuku）— RikkaApps
        libadb-android（io.github.muntashirakon.adb）— Muntashir Akon
        spake2-java — Muntashir Akon

        ── MIT License ──
        BouncyCastle — Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc.

        ── 许可摘要 ──
        Apache-2.0：允许商用、修改、再分发；需保留版权与许可声明，附带专利授权；
        不提供担保。完整文本：https://www.apache.org/licenses/LICENSE-2.0
        MIT：允许任何用途；需保留版权声明与许可声明；不提供担保。
        完整文本：https://opensource.org/licenses/MIT

        ── 特别致谢（只借鉴思路与交互，未复制这些项目的代码）──
        · NekoType — Yukstarlight（本应用界面、配色与交互的直接来源）
        · Hail 雹（com.aistra.hail）— 冻结 / 解冻的三种工作模式设计
        · Canta — 系统应用卸载与 install-existing 恢复的做法
        · NRFR（com.lmhcodes.nrfr）— 免 Shizuku 的激活思路
        · 幻 · 实验室 — 屏幕灰度等系统测试项的取材
        · Stellar（roro.stellar.manager）— 与 Shizuku 兼容的服务端实现
        · AppManager / libadb-android — 本机无线调试连接
        以上项目的作者与授权许可以其各自项目主页为准。
    """.trimIndent()
}
