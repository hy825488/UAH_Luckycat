package com.luckycat.fp

import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Locale

/**
 * Luckycat v12(模擬器/雲手機通用;通吃 原神 / 崩壞星穹鐵道 / 絕區零)
 *
 * 融合「別人的 sdkpatch 模組」+ 我們前幾版的模擬器加固:
 *   ★核心(學別人的,正解):createOrder 在【簽名前的參數組裝點】改欄位再【用 SDK 自己的函式重簽】
 *     - hook com.mihoyoos.sdk.platform.module.pay.PayModel#getCreateOrderParams(回傳 Map)
 *     - order.device → 假 device_id;order.country → 對齊「帳號的國家」(ISO3)
 *     - sign → HttpCompleteUtils.Companion.generateSign(order) 重算 → 天生有效(不破簽名)
 *   ★device 在源頭偽造(學別人的):
 *     - SDKInfo#deviceId / GameConfig#getDeviceId → 假 device_id
 *     - AbstractDeviceUniqueIdentifier#obtain → 假 device_fp(源頭捏,全鏈一致;乾淨網路下可行)
 *   ★我們的模擬器加固(別人沒有,MuMu 需要):
 *     - CommonRequiredParams#processExtensionParams 整包洗成真機 + 三軸感測器 + emu/root=0
 *     - Build.* 全套偽裝、藏模擬器(getCpuModel/ro.kernel.qemu/su 檔)
 *
 * ⚠️ 網路一致性(全局 VPN + 換系統 DNS,無 DNS/IPv6 洩漏)+ 地區三件套對齊,是插件外必須配的。
 * ⚠️ 帳號被支付風控(135)時,冷卻 ~15 分 + 換設備 + 換 IP 才解,插件改不掉帳號層。
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "LuckycatFp14"
        // createOrder 重簽核心
        private const val PAY_MODEL     = "com.mihoyoos.sdk.platform.module.pay.PayModel"
        private const val HTTP_COMPLETE = "com.mihoyoos.sdk.platform.common.utils.HttpCompleteUtils"
        private const val ACCOUNT_UTILS = "com.mihoyoos.sdk.platform.common.utils.AccountUtils"
        // device 源頭
        private const val SDK_INFO    = "com.mihoyo.combo.info.SDKInfo"
        private const val GAME_CONFIG = "com.mihoyoos.sdk.platform.config.GameConfig"
        private const val ABS_UID     = "com.mihoyo.platform.sdk.devicefp.AbstractDeviceUniqueIdentifier"
        private const val COMBO_DU    = "com.combosdk.support.base.utils.DeviceUtils"
        private const val PORTE_DU    = "com.mihoyo.platform.account.oversea.sdk.internal.shared.utils.DeviceUtils"
        private const val XDEV_U      = "com.mihoyo.platform.utilities.XDeviceUtils"
        private const val CRP         = "com.mihoyo.platform.sdk.devicefp.CommonRequiredParams"
        private val TARGETS = setOf(
            "com.miHoYo.GenshinImpact", "com.miHoYo.ys.mihoyo", "com.miHoYo.Yuanshen",
            "com.HoYoverse.hkrpgoversea", "com.miHoYo.hkrpg",
            "com.HoYoverse.Nap", "com.miHoYo.Nap"
        )
        private val RND = java.security.SecureRandom()
        private const val HEX = "0123456789abcdef"
        fun randHex(n: Int) = buildString { repeat(n) { append(HEX[RND.nextInt(16)]) } }

        // Android 12 / SM8350 成套真機檔(對齊 MuMuPlayer 12)
        private val PROFILES = listOf(
            Profile("samsung","samsung","SM-G991U","o1q","o1qsqw","lahaina","qcom",
                "samsung/o1qsqw/o1q:12/SP1A.210812.016/G991USQU5CVK3:user/release-keys",
                "SP1A.210812.016","12","SM8350","Qualcomm Technologies, Inc. SM8350"),
            Profile("Xiaomi","Xiaomi","M2011K2G","venus","venus","venus","qcom",
                "Xiaomi/venus/venus:12/SKQ1.211006.001/V13.0.10.0.SKBEUXM:user/release-keys",
                "SKQ1.211006.001","12","SM8350","Qualcomm Technologies, Inc. SM8350"),
            Profile("OnePlus","OnePlus","LE2113","OnePlus9","OnePlus9EEA","lahaina","qcom",
                "OnePlus/OnePlus9EEA/OnePlus9:12/RKQ1.211119.001/R.202203301911:user/release-keys",
                "RKQ1.211119.001","12","SM8350","Qualcomm Technologies, Inc. SM8350")
        )
        data class Profile(
            val manufacturer:String, val brand:String, val model:String, val device:String,
            val product:String, val board:String, val hardware:String, val fingerprint:String,
            val display:String, val release:String, val soc:String, val chip:String)
    }

    private val prof: Profile by lazy { PROFILES[RND.nextInt(PROFILES.size)] }
    private val fp: String by lazy { randHex(13) }
    private val did: String by lazy { randHex(16) }
    private val serial: String by lazy { randHex(16).uppercase() }
    private fun jit(b: Double) = b + (RND.nextInt(1000) - 500) / 10000.0
    private val accel by lazy { "%.4fx%.4fx%.4f".format(jit(0.12), jit(0.19), jit(9.79)) }
    private val magn  by lazy { "%.4fx%.4fx%.4f".format(jit(24.3), jit(-7.8), jit(40.1)) }
    private val gyro  by lazy { "%.4fx%.4fx%.4f".format(jit(0.001), jit(-0.002), jit(0.0015)) }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGETS) return
        val cl = lpparam.classLoader
        try {
            log("Loaded ${lpparam.packageName}  profile=${prof.brand}/${prof.model} fp=$fp id=$did")
            spoofBuild()
            hookExtParams(cl)      // getFp ext 洗乾淨(含感測器/emu=0)
            hideEmulatorRoot(cl)   // 藏模擬器/root + CPU 型號
            spoofDeviceSource(cl)  // ★device_id/fp 源頭偽造
            hookCreateOrder(cl)    // ★★createOrder:改 country+device + 重簽
            installStatusToast(cl, lpparam.packageName)  // 開遊戲時彈 Toast 顯示改了什麼
        } catch (e: Throwable) { log("Error: ${e.message}") }
    }

    // 開遊戲時彈 Toast,把這次偽裝的內容顯示出來(彈 3 次:載入→標題過渡都看得到)
    @Volatile private var toastDone = false
    private fun installStatusToast(cl: ClassLoader, pkg: String) {
        try {
            val appCls = XposedHelpers.findClass("android.app.Application", cl)
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
                    val msg = "Luckycat v14 生效 ✓  [$game]\n" +
                            "機型: ${prof.brand} ${prof.model}\n" +
                            "device_id: $did\n" +
                            "device_fp: $fp\n" +
                            "CPU: ${prof.chip}\n" +
                            "androidId: $did\n" +
                            "createOrder: country對齊帳號 + device改 + 重簽\n" +
                            "感測器/模擬器旗標: 已洗"
                    val h = android.os.Handler(android.os.Looper.getMainLooper())
                    for (delay in longArrayOf(3000, 8000, 15000)) {
                        h.postDelayed({
                            try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() }
                            catch (e: Throwable) { log("toast fail: ${e.message}") }
                        }, delay)
                    }
                    log("status toast scheduled")
                }
            })
        } catch (e: Throwable) { log("installStatusToast fail: ${e.message}") }
    }

    // ★★ createOrder 正解:簽名前改參數 + SDK 自己重簽
    private fun hookCreateOrder(cl: ClassLoader) {
        val pm = XposedHelpers.findClassIfExists(PAY_MODEL, cl) ?: run { log("MISS PayModel"); return }
        try {
            XposedBridge.hookAllMethods(pm, "getCreateOrderParams", object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val map = param.result as? MutableMap<String, Any?> ?: return
                        val order = map["order"] as? MutableMap<String, Any?> ?: run { log("[CreateOrder] no order map"); return }
                        val oldC = order["country"]; val oldD = order["device"]
                        order["device"] = did                               // 假裝置(跟源頭一致)
                        accountCountryIso3(cl)?.let { order["country"] = it }  // 對齊帳號國家
                        val sign = reSign(cl, order)                          // ★用 SDK 自己重簽
                        if (sign != null) map["sign"] = sign
                        log("[CreateOrder] country $oldC->${order["country"]} device $oldD->$did resign=${sign!=null}")
                    } catch (e: Throwable) { log("[CreateOrder] err: ${e.message}") }
                }
            })
            log("hooked PayModel#getCreateOrderParams (rewrite+resign)")
        } catch (e: Throwable) { log("hook createOrder fail: ${e.message}") }
    }

    // 呼叫 SDK 自己的 generateSign(Companion / INSTANCE / static 三種都試)
    private fun reSign(cl: ClassLoader, order: Map<String, Any?>): String? {
        val cls = XposedHelpers.findClassIfExists(HTTP_COMPLETE, cl) ?: return null
        for (holder in listOf("Companion", "INSTANCE")) {
            try {
                val obj = XposedHelpers.getStaticObjectField(cls, holder)
                return XposedHelpers.callMethod(obj, "generateSign", order) as? String
            } catch (e: Throwable) {}
        }
        try { return XposedHelpers.callStaticMethod(cls, "generateSign", order) as? String } catch (e: Throwable) {}
        return null
    }

    // 帳號的國家 → ISO3(對齊 order.country);讀不到就不動 country
    private fun accountCountryIso3(cl: ClassLoader): String? {
        try {
            val entity = XposedHelpers.callStaticMethod(XposedHelpers.findClass(ACCOUNT_UTILS, cl), "getAccountInfo4Pay") ?: return null
            val c = XposedHelpers.callMethod(entity, "getCountry") as? String ?: return null
            if (c.length == 2) { val iso3 = Locale("", c).isO3Country; if (iso3.length == 3) return iso3 }
            if (c.length == 3) return c
        } catch (e: Throwable) {}
        return null
    }

    // ★device_id / device_fp 源頭偽造
    private fun spoofDeviceSource(cl: ClassLoader) {
        pin(cl, SDK_INFO, "deviceId", did)
        pin(cl, GAME_CONFIG, "getDeviceId", did)
        pin(cl, ABS_UID, "obtain", fp)          // device_fp 源頭捏
        pin(cl, COMBO_DU, "getDeviceID", did)
        pin(cl, COMBO_DU, "getAndroidID", did)
        pin(cl, PORTE_DU, "getDeviceID", did)
        pin(cl, XDEV_U, "getAndroidID", did)
        hookSettingsAndroidId(cl)
    }

    // getFp 上報 ext 整包洗成真機(含三軸感測器/emu/root=0)
    @Suppress("UNCHECKED_CAST")
    private fun hookExtParams(cl: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CRP, cl) ?: run { log("MISS CRP"); return }
        try {
            XposedBridge.hookAllMethods(clazz, "processExtensionParams", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val m = param.result as? MutableMap<String, Any?> ?: return
                    val p = prof
                    fun s(k: String, v: String) { if (m.containsKey(k)) m[k] = v }
                    fun i(k: String, v: Int)    { if (m.containsKey(k)) m[k] = v }
                    s("board", p.board); s("brand", p.brand); s("hardware", p.hardware)
                    s("cpuType", "arm64-v8a"); s("deviceType", p.device); s("display", p.display)
                    s("hostname", "build-host"); s("manufacturer", p.manufacturer); s("productName", p.product)
                    s("model", p.model); s("deviceInfo", p.fingerprint); s("osVersion", p.release)
                    s("buildTags", "release-keys"); s("buildType", "user"); s("buildUser", "dpi")
                    s("serialNumber", serial); s("androidId", did)
                    s("accelerometer", accel); s("magnetometer", magn); s("gyroscope", gyro)
                    i("emulatorStatus", 0); i("isRoot", 0); i("debugStatus", 0); i("proxyStatus", 0); i("isMockLocation", 0)
                }
            })
            log("hooked CRP#processExtensionParams")
        } catch (e: Throwable) { log("hook ext fail: ${e.message}") }
    }

    // ① Build.* 成套真機
    private fun spoofBuild() {
        val p = prof
        setB("MANUFACTURER", p.manufacturer); setB("BRAND", p.brand); setB("MODEL", p.model)
        setB("DEVICE", p.device); setB("PRODUCT", p.product); setB("BOARD", p.board)
        setB("HARDWARE", p.hardware); setB("FINGERPRINT", p.fingerprint); setB("DISPLAY", p.display)
        setB("HOST", "build-host"); setB("USER", "dpi"); setB("TAGS", "release-keys")
        setB("TYPE", "user"); setB("SERIAL", serial); setB("ID", p.display); setB("CPU_ABI", "arm64-v8a")
        try { XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a","armeabi-v7a","armeabi")) } catch (e: Throwable) {}
        try { XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", p.release) } catch (e: Throwable) {}
        log("Build spoofed -> ${p.brand}/${p.model} (${p.soc})")
    }
    private fun setB(f: String, v: String) { try { XposedHelpers.setStaticObjectField(Build::class.java, f, v) } catch (e: Throwable) {} }

    // ③ 藏模擬器 / root
    private fun hideEmulatorRoot(cl: ClassLoader) {
        pin(cl, XDEV_U, "isEmulator", 0); pin(cl, XDEV_U, "isRooted", 0)
        pin(cl, COMBO_DU, "isEmulator", 0); pin(cl, COMBO_DU, "isRooted", 0)
        pin(cl, COMBO_DU, "isProxy", 0); pin(cl, COMBO_DU, "hasOpenDebugMode", 0)
        pin(cl, COMBO_DU, "getCpuModel", prof.chip); pin(cl, XDEV_U, "getCpuModel", prof.chip)
        val sp = XposedHelpers.findClassIfExists("android.os.SystemProperties", cl)
        if (sp != null) {
            try {
                XposedBridge.hookAllMethods(sp, "get", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        when (param.args.getOrNull(0) as? String) {
                            "ro.kernel.qemu" -> param.result = ""
                            "ro.soc.model" -> param.result = prof.soc
                            "ro.soc.manufacturer" -> param.result = "QTI"
                            "ro.hardware" -> param.result = prof.hardware
                        }
                    }
                })
            } catch (e: Throwable) {}
        }
        // ★不 hook File.exists:它是 Unity 遊戲最熱的方法(每秒上萬次),全域 hook 會累積開銷→玩一陣子卡死。
        //   root 隱藏已由 XDeviceUtils.isRooted→0 涵蓋(su 檔存不存在都回沒 root),故 File.exists 多餘,移除。
    }

    private fun hookSettingsAndroidId(cl: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists("android.provider.Settings\$Secure", cl) ?: return
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size >= 2 && param.args[1] == "android_id") param.result = did
            }
        }
        try { XposedBridge.hookAllMethods(clazz, "getString", cb) } catch (e: Throwable) {}
        try { XposedBridge.hookAllMethods(clazz, "getStringForUser", cb) } catch (e: Throwable) {}
    }

    private fun pin(cl: ClassLoader, cls: String, method: String, value: Any) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl) ?: return
        try {
            val n = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = value }
            }).size
            if (n == 0) log("NOMATCH $cls#$method")
        } catch (e: Throwable) { log("pin $cls#$method fail: ${e.message}") }
    }

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
}
