package com.luckycat.fp

import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.security.SecureRandom
import java.util.Locale

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  Luckycat 原神 / 星穹鐵道 / 絕區零 過檢 Patch —— 單檔、可直接整合到別的 Xposed 模組
 * ══════════════════════════════════════════════════════════════════════════
 *
 * 這是把整個插件濃縮成「一個 object + 一個 install() function」的整合版。
 * 只依賴 Xposed API + Android SDK,整個檔複製到你的模組就能用,不用其他檔案。
 *
 * ── 用法 A(最簡單:丟 lpparam 進去,它自己判斷是不是三款遊戲)──
 *   class YourHook : IXposedHookLoadPackage {
 *       override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
 *           // ... 你原本的邏輯 ...
 *           LuckycatPatch.install(lpparam)   // 偵測到原神/星鐵/絕區零 → 自動全套 hook
 *       }
 *   }
 *
 * ── 用法 B(你自己判斷好包名再呼叫)──
 *   if (lpparam.packageName == "com.miHoYo.GenshinImpact")
 *       LuckycatPatch.install(lpparam.classLoader, lpparam.packageName)
 *
 * install() 一次做完:Build 偽裝 → getFp ext 洗真機(含三軸感測器)→ 藏模擬器/root →
 *   device_id/fp 源頭偽造 → ★createOrder 改 country+device 並用 SDK 重簽 → 開遊戲 Toast。
 *
 * ⚠️ 插件只管「裝置層」;要通過還需搭配全局 VPN+換 DNS(網路層)、地區三件套對齊、乾淨帳號。
 */
object LuckycatPatch {

    private const val TAG = "LuckycatPatch"

    /** 作用的 7 個確切套件。 */
    val TARGETS = setOf(
        "com.miHoYo.GenshinImpact", "com.miHoYo.ys.mihoyo", "com.miHoYo.Yuanshen",
        "com.HoYoverse.hkrpgoversea", "com.miHoYo.hkrpg",
        "com.HoYoverse.Nap", "com.miHoYo.Nap"
    )

    /** 入口 A:吃 lpparam,自動判斷是不是目標遊戲。 */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGETS) return
        install(lpparam.classLoader, lpparam.packageName)
    }

    /** 入口 B:你自己判斷好包名後呼叫(cl = 該遊戲進程的 classLoader)。 */
    fun install(cl: ClassLoader, pkg: String) {
        try {
            val id = Identity()   // 本次冷啟的假身分(整個 install 全鏈共用同一組)
            log("install $pkg profile=${id.brand}/${id.model} id=${id.deviceId} fp=${id.deviceFp}")
            spoofBuild(id)
            hookExtParams(cl, id)
            hideEmulator(cl, id)
            spoofDeviceSource(cl, id)
            hookCreateOrder(cl, id)
            statusToast(cl, pkg, id)
        } catch (e: Throwable) { log("install error: ${e.message}") }
    }

    // ══════════════ 假身分(一次產生,install 內共用)══════════════
    private class Identity {
        // 成套真機檔(Android 12 / SM8350),每次隨機抽一台
        private val prof = PROFILES[RND.nextInt(PROFILES.size)]
        val manufacturer get() = prof[0]; val brand get() = prof[1]; val model get() = prof[2]
        val device get() = prof[3]; val product get() = prof[4]; val board get() = prof[5]
        val hardware get() = prof[6]; val fingerprint get() = prof[7]; val display get() = prof[8]
        val release get() = prof[9]; val soc get() = prof[10]; val chip get() = prof[11]
        val deviceId = randHex(16)   // device_id / android_id / order.device
        val deviceFp = randHex(13)   // device_fp
        val serial = randHex(16).uppercase()
        val accel = sensor(0.12, 0.19, 9.79)
        val magn = sensor(24.3, -7.8, 40.1)
        val gyro = sensor(0.001, -0.002, 0.0015)
    }

    // ══════════════ ① Build 偽裝 ══════════════
    private fun spoofBuild(id: Identity) {
        fun set(f: String, v: String) = runCatching { XposedHelpers.setStaticObjectField(Build::class.java, f, v) }
        set("MANUFACTURER", id.manufacturer); set("BRAND", id.brand); set("MODEL", id.model)
        set("DEVICE", id.device); set("PRODUCT", id.product); set("BOARD", id.board)
        set("HARDWARE", id.hardware); set("FINGERPRINT", id.fingerprint); set("DISPLAY", id.display)
        set("HOST", "build-host"); set("USER", "dpi"); set("TAGS", "release-keys")
        set("TYPE", "user"); set("SERIAL", id.serial); set("ID", id.display); set("CPU_ABI", "arm64-v8a")
        runCatching { XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a","armeabi-v7a","armeabi")) }
        runCatching { XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", id.release) }
    }

    // ══════════════ ② getFp 上報特徵洗成真機 ══════════════
    @Suppress("UNCHECKED_CAST")
    private fun hookExtParams(cl: ClassLoader, id: Identity) {
        val clazz = XposedHelpers.findClassIfExists("com.mihoyo.platform.sdk.devicefp.CommonRequiredParams", cl) ?: return
        runCatching {
            XposedBridge.hookAllMethods(clazz, "processExtensionParams", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val m = param.result as? MutableMap<String, Any?> ?: return
                    fun s(k: String, v: String) { if (m.containsKey(k)) m[k] = v }
                    fun i(k: String, v: Int) { if (m.containsKey(k)) m[k] = v }
                    s("board", id.board); s("brand", id.brand); s("hardware", id.hardware)
                    s("cpuType", "arm64-v8a"); s("deviceType", id.device); s("display", id.display)
                    s("hostname", "build-host"); s("manufacturer", id.manufacturer); s("productName", id.product)
                    s("model", id.model); s("deviceInfo", id.fingerprint); s("osVersion", id.release)
                    s("buildTags", "release-keys"); s("buildType", "user"); s("buildUser", "dpi")
                    s("serialNumber", id.serial); s("androidId", id.deviceId)
                    s("accelerometer", id.accel); s("magnetometer", id.magn); s("gyroscope", id.gyro)
                    i("emulatorStatus", 0); i("isRoot", 0); i("debugStatus", 0); i("proxyStatus", 0); i("isMockLocation", 0)
                }
            })
        }
    }

    // ══════════════ ③ 藏模擬器 / root(★不 hook File.exists,避免卡死)══════════════
    private fun hideEmulator(cl: ClassLoader, id: Identity) {
        val xd = "com.mihoyo.platform.utilities.XDeviceUtils"
        val du = "com.combosdk.support.base.utils.DeviceUtils"
        pin(cl, xd, "isEmulator", 0); pin(cl, xd, "isRooted", 0)
        pin(cl, du, "isEmulator", 0); pin(cl, du, "isRooted", 0)
        pin(cl, du, "isProxy", 0); pin(cl, du, "hasOpenDebugMode", 0)
        pin(cl, du, "getCpuModel", id.chip); pin(cl, xd, "getCpuModel", id.chip)
        val sp = XposedHelpers.findClassIfExists("android.os.SystemProperties", cl) ?: return
        runCatching {
            XposedBridge.hookAllMethods(sp, "get", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    when (param.args.getOrNull(0) as? String) {
                        "ro.kernel.qemu" -> param.result = ""
                        "ro.soc.model" -> param.result = id.soc
                        "ro.soc.manufacturer" -> param.result = "QTI"
                        "ro.hardware" -> param.result = id.hardware
                    }
                }
            })
        }
    }

    // ══════════════ ④ device_id / device_fp 源頭偽造 ══════════════
    private fun spoofDeviceSource(cl: ClassLoader, id: Identity) {
        pin(cl, "com.mihoyo.combo.info.SDKInfo", "deviceId", id.deviceId)
        pin(cl, "com.mihoyoos.sdk.platform.config.GameConfig", "getDeviceId", id.deviceId)
        pin(cl, "com.combosdk.support.base.utils.DeviceUtils", "getDeviceID", id.deviceId)
        pin(cl, "com.combosdk.support.base.utils.DeviceUtils", "getAndroidID", id.deviceId)
        pin(cl, "com.mihoyo.platform.account.oversea.sdk.internal.shared.utils.DeviceUtils", "getDeviceID", id.deviceId)
        pin(cl, "com.mihoyo.platform.utilities.XDeviceUtils", "getAndroidID", id.deviceId)
        pin(cl, "com.mihoyo.platform.sdk.devicefp.AbstractDeviceUniqueIdentifier", "obtain", id.deviceFp)
        // Settings.Secure android_id
        val ss = XposedHelpers.findClassIfExists("android.provider.Settings\$Secure", cl) ?: return
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size >= 2 && param.args[1] == "android_id") param.result = id.deviceId
            }
        }
        runCatching { XposedBridge.hookAllMethods(ss, "getString", cb) }
        runCatching { XposedBridge.hookAllMethods(ss, "getStringForUser", cb) }
    }

    // ══════════════ ⑤ ★createOrder 改參數 + SDK 重簽 ══════════════
    @Suppress("UNCHECKED_CAST")
    private fun hookCreateOrder(cl: ClassLoader, id: Identity) {
        val pm = XposedHelpers.findClassIfExists("com.mihoyoos.sdk.platform.module.pay.PayModel", cl) ?: return
        runCatching {
            XposedBridge.hookAllMethods(pm, "getCreateOrderParams", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val map = param.result as? MutableMap<String, Any?> ?: return
                        val order = map["order"] as? MutableMap<String, Any?> ?: return
                        order["device"] = id.deviceId
                        accountCountryIso3(cl)?.let { order["country"] = it }
                        reSign(cl, order)?.let { map["sign"] = it }
                        log("[CreateOrder] device=${id.deviceId} country=${order["country"]} resign=${map["sign"] != null}")
                    } catch (e: Throwable) { log("[CreateOrder] ${e.message}") }
                }
            })
        }
    }

    private fun reSign(cl: ClassLoader, order: Map<String, Any?>): String? {
        val cls = XposedHelpers.findClassIfExists("com.mihoyoos.sdk.platform.common.utils.HttpCompleteUtils", cl) ?: return null
        for (h in listOf("Companion", "INSTANCE")) {
            val r = runCatching { XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(cls, h), "generateSign", order) as? String }.getOrNull()
            if (r != null) return r
        }
        return runCatching { XposedHelpers.callStaticMethod(cls, "generateSign", order) as? String }.getOrNull()
    }

    private fun accountCountryIso3(cl: ClassLoader): String? = runCatching {
        val e = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.mihoyoos.sdk.platform.common.utils.AccountUtils", cl), "getAccountInfo4Pay") ?: return@runCatching null
        val c = XposedHelpers.callMethod(e, "getCountry") as? String ?: return@runCatching null
        when {
            c.length == 2 -> Locale("", c).isO3Country.takeIf { it.length == 3 }
            c.length == 3 -> c
            else -> null
        }
    }.getOrNull()

    // ══════════════ ⑥ 開遊戲 Toast(顯示這次改了什麼)══════════════
    @Volatile private var toastDone = false
    private fun statusToast(cl: ClassLoader, pkg: String, id: Identity) {
        val appCls = runCatching { XposedHelpers.findClass("android.app.Application", cl) }.getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllMethods(appCls, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (toastDone) return
                    toastDone = true
                    val ctx = param.thisObject as? android.content.Context ?: return
                    val game = when {
                        pkg.contains("Genshin", true) || pkg.contains("ys", true) || pkg.contains("Yuanshen", true) -> "原神"
                        pkg.contains("hkrpg", true) -> "星穹鐵道"
                        pkg.contains("Nap", true) -> "絕區零"
                        else -> pkg
                    }
                    val msg = "Luckycat 生效 ✓ [$game]\n機型: ${id.brand} ${id.model}\ndevice_id: ${id.deviceId}\ndevice_fp: ${id.deviceFp}\nCPU: ${id.chip}\ncreateOrder: country對齊+device改+重簽"
                    val h = android.os.Handler(android.os.Looper.getMainLooper())
                    for (d in longArrayOf(3000, 8000, 15000))
                        h.postDelayed({ runCatching { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() } }, d)
                }
            })
        }
    }

    // ══════════════ 共用小工具 ══════════════
    /** 把某類某方法所有多載事後改成回傳固定值。 */
    private fun pin(cl: ClassLoader, cls: String, method: String, value: Any) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl) ?: return
        runCatching {
            XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = value }
            })
        }
    }

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")

    // ══════════════ 靜態資料 ══════════════
    private val RND = SecureRandom()
    private const val HEX = "0123456789abcdef"
    private fun randHex(n: Int) = buildString { repeat(n) { append(HEX[RND.nextInt(16)]) } }
    private fun jit(b: Double) = b + (RND.nextInt(1000) - 500) / 10000.0
    private fun sensor(x: Double, y: Double, z: Double) = "%.4fx%.4fx%.4f".format(jit(x), jit(y), jit(z))
    // 每台 12 欄:manufacturer,brand,model,device,product,board,hardware,fingerprint,display,release,soc,chip
    private val PROFILES = listOf(
        arrayOf("samsung","samsung","SM-G991U","o1q","o1qsqw","lahaina","qcom",
            "samsung/o1qsqw/o1q:12/SP1A.210812.016/G991USQU5CVK3:user/release-keys","SP1A.210812.016","12","SM8350","Qualcomm Technologies, Inc. SM8350"),
        arrayOf("Xiaomi","Xiaomi","M2011K2G","venus","venus","venus","qcom",
            "Xiaomi/venus/venus:12/SKQ1.211006.001/V13.0.10.0.SKBEUXM:user/release-keys","SKQ1.211006.001","12","SM8350","Qualcomm Technologies, Inc. SM8350"),
        arrayOf("OnePlus","OnePlus","LE2113","OnePlus9","OnePlus9EEA","lahaina","qcom",
            "OnePlus/OnePlus9EEA/OnePlus9:12/RKQ1.211119.001/R.202203301911:user/release-keys","RKQ1.211119.001","12","SM8350","Qualcomm Technologies, Inc. SM8350")
    )
}
