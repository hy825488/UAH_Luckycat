package com.luckycat.fp

import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Luckycat 模擬器版 v9（redroid 等模擬器；通吃 原神 / 崩壞星穹鐵道 / 絕區零）
 *
 * 比真機版(v8)多兩層:①藏模擬器 ②把上報風控的硬體特徵偽造成一台【成套真旗艦機】。
 *
 * 關鍵單點(反編譯得證):device_fp 由伺服器用 app 上報的 ext 特徵算,而整包 ext 由
 *   com.mihoyo.platform.sdk.devicefp.CommonRequiredParams#processExtensionParams(Context,ArrayList)
 *   一個方法組出(回傳 HashMap)。→ afterHook 這個方法、把整包換成成套真機檔(含三軸感測器假值、
 *   emulatorStatus=0、isRoot=0)→ device_fp 從源頭乾淨,不管伺服器要哪些欄位都一致。
 *
 * 另外:
 *   - Build.* 靜態欄位一起換成同一台機(讓 telemetry / astrolabe / isEmulator 字串比對 全域一致)。
 *   - 藏模擬器/root:XDeviceUtils.isEmulator/isRooted→0、SystemProperties ro.kernel.qemu→""、
 *     System.getProperty(proxy)→null、File.exists(su)→false。
 *   - 帳號一致(deep):device_id/device_fp 在 header+body+token+telemetry 全鏈固定同一筆
 *     (模擬器可拋棄,不怕重跑更新,所以做到底)。
 *   - 地區不動(country/currency 原樣;要改 USA+重簽另議)。
 *
 * ⚠️ 有些破綻建議在 redroid 映像層(build.prop)一起改更穩:ro.* 全套屬性、/proc/cpuinfo、清 su 檔。
 *    純過 device_fp 用本插件即可;但 telemetry 的 /proc/cpuinfo(getCpuModel)是原生讀檔,建議映像層放假。
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "LuckycatFp9"
        private const val CRP        = "com.mihoyo.platform.sdk.devicefp.CommonRequiredParams"
        private const val ABS_UID    = "com.mihoyo.platform.sdk.devicefp.AbstractDeviceUniqueIdentifier"
        private const val FP_SP      = "com.mihoyo.platform.sdk.devicefp.DeviceFingerprintSharedPreferences"
        private const val XDEV_U     = "com.mihoyo.platform.utilities.XDeviceUtils"
        private const val PORTE_INFO = "com.mihoyo.platform.account.oversea.sdk.PorteOSInfo"
        private const val PORTE_DU   = "com.mihoyo.platform.account.oversea.sdk.internal.shared.utils.DeviceUtils"
        private const val COMBO_DU   = "com.combosdk.support.base.utils.DeviceUtils"
        private const val INFO_MOD   = "com.combosdk.framework.module.info.InfoModule"
        private const val SYSPROP    = "android.os.SystemProperties"
        private const val OKHTTP_B   = "okhttp3.Request\$Builder"
        private const val H_FP = "x-rpc-device_fp"
        private const val H_ID = "x-rpc-device_id"
        private val TARGETS = setOf(
            "com.miHoYo.GenshinImpact", "com.miHoYo.ys.mihoyo", "com.miHoYo.Yuanshen",
            "com.HoYoverse.hkrpgoversea", "com.miHoYo.hkrpg",
            "com.HoYoverse.Nap", "com.miHoYo.Nap"
        )
        private val RND = java.security.SecureRandom()
        private const val HEX = "0123456789abcdef"
        fun randHex(n: Int) = buildString { repeat(n) { append(HEX[RND.nextInt(16)]) } }

        // 成套真旗艦機檔(Android 13 / Qualcomm)——每欄互相對得起來,別東拼西湊
        // 若你的 redroid 是 Android 12,跟我說,我換成 API31 的檔(fingerprint 內的 13 要對齊)
        private val PROFILES = listOf(
            Profile("samsung","samsung","SM-S911B","dm1q","dm1qxeea","kalama","qcom",
                "samsung/dm1qxeea/dm1q:13/TP1A.220624.014/S911BXXU2AWF9:user/release-keys",
                "TP1A.220624.014","S911BXXU2AWF9","13",33,"SM8550","Qualcomm Technologies, Inc SM8550"),
            Profile("Xiaomi","Xiaomi","2211133C","fuxi","fuxi","fuxi","qcom",
                "Xiaomi/fuxi/fuxi:13/TKQ1.220807.001/V14.0.6.0:user/release-keys",
                "TKQ1.220807.001","fuxi-user 13","13",33,"SM8550","Qualcomm Technologies, Inc SM8550"),
            Profile("OnePlus","OnePlus","CPH2449","OP594DL1","CPH2449","kalama","qcom",
                "OnePlus/CPH2449EEA/OP594DL1:13/TP1A.220905.001/T.R4a7ac1b:user/release-keys",
                "TP1A.220905.001","T.R4a7ac1b","13",33,"SM8550","Qualcomm Technologies, Inc SM8550"),
            Profile("Qualcomm","google","Pixel 7","panther","panther","panther","panther",
                "google/panther/panther:13/TQ3A.230901.001/10750268:user/release-keys",
                "TQ3A.230901.001","10750268","13",33,"Tensor G2","Google Tensor G2")
        )
        data class Profile(
            val manufacturer:String, val brand:String, val model:String, val device:String,
            val product:String, val board:String, val hardware:String, val fingerprint:String,
            val display:String, val bootloader:String, val release:String, val sdk:Int,
            val soc:String, val chip:String)
    }

    private val prof: Profile by lazy { PROFILES[RND.nextInt(PROFILES.size)] }
    private val fp: String by lazy { randHex(13) }         // device_fp(固定一筆,全鏈一致)
    private val did: String by lazy { randHex(16) }        // device_id / android_id
    private val serial: String by lazy { randHex(16).uppercase() }
    // 三軸感測器假值(真機一定有;redroid 常回空 → 破綻)。帶微抖動避免固定值。
    private fun jitter(base: Double) = base + (RND.nextInt(1000) - 500) / 10000.0
    private val accel: String by lazy { "${"%.4f".format(jitter(0.12))}x${"%.4f".format(jitter(0.19))}x${"%.4f".format(jitter(9.79))}" }
    private val magn:  String by lazy { "${"%.4f".format(jitter(24.3))}x${"%.4f".format(jitter(-7.8))}x${"%.4f".format(jitter(40.1))}" }
    private val gyro:  String by lazy { "${"%.4f".format(jitter(0.001))}x${"%.4f".format(jitter(-0.002))}x${"%.4f".format(jitter(0.0015))}" }
    private val inBuild = ThreadLocal.withInitial { false }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGETS) return
        val cl = lpparam.classLoader
        try {
            log("Loaded ${lpparam.packageName}  profile=${prof.brand}/${prof.model}  fp=$fp  id=$did")
            spoofBuild()                 // ① Build.* 全換成成套真機(全域一致)
            hookExtParams(cl)            // ★② 單點覆寫 device_fp 上報的整包 ext(含感測器/旗標)
            hideEmulatorRoot(cl)         // ③ 藏模擬器/root/proxy
            deepIdentity(cl)             // ④ device_id/fp 全鏈一致(帳號防串連)
            hookOkHttpHeaders(cl)        //    付款/帳號請求 header 覆寫
        } catch (e: Throwable) { log("Error: ${e.message}") }
    }

    // ① Build.* 靜態欄位 → 成套真機
    private fun spoofBuild() {
        val p = prof
        setB("MANUFACTURER", p.manufacturer); setB("BRAND", p.brand); setB("MODEL", p.model)
        setB("DEVICE", p.device); setB("PRODUCT", p.product); setB("BOARD", p.board)
        setB("HARDWARE", p.hardware); setB("FINGERPRINT", p.fingerprint); setB("DISPLAY", p.display)
        setB("BOOTLOADER", p.bootloader); setB("HOST", "build-host"); setB("USER", "dpi")
        setB("TAGS", "release-keys"); setB("TYPE", "user"); setB("SERIAL", serial); setB("ID", p.display)
        setB("CPU_ABI", "arm64-v8a"); setB("CPU_ABI2", "")
        try { XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a","armeabi-v7a","armeabi")) } catch (e: Throwable) {}
        try { XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", p.release) } catch (e: Throwable) {}
        try { XposedHelpers.setStaticIntField(Build.VERSION::class.java, "SDK_INT", p.sdk) } catch (e: Throwable) {}
        log("Build spoofed -> ${p.brand}/${p.model} (${p.soc}, A${p.release})")
    }
    private fun setB(f: String, v: String) { try { XposedHelpers.setStaticObjectField(Build::class.java, f, v) } catch (e: Throwable) { log("Build.$f fail: ${e.message}") } }

    // ★② 單點:CommonRequiredParams.processExtensionParams 回傳的 HashMap 整包覆寫
    @Suppress("UNCHECKED_CAST")
    private fun hookExtParams(cl: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CRP, cl) ?: run { log("MISS $CRP"); return }
        try {
            XposedBridge.hookAllMethods(clazz, "processExtensionParams", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val m = param.result as? MutableMap<String, Any?> ?: return
                    val p = prof
                    fun s(k: String, v: String) { if (m.containsKey(k)) m[k] = v }
                    fun i(k: String, v: Int)    { if (m.containsKey(k)) m[k] = v }
                    // 硬體特徵(成套真機)
                    s("board", p.board); s("brand", p.brand); s("hardware", p.hardware)
                    s("cpuType", "arm64-v8a"); s("deviceType", p.device); s("display", p.display)
                    s("hostname", "build-host"); s("manufacturer", p.manufacturer); s("productName", p.product)
                    s("model", p.model); s("deviceInfo", p.fingerprint); s("osVersion", p.release)
                    s("buildTags", "release-keys"); s("buildType", "user"); s("buildUser", "dpi")
                    s("serialNumber", serial); s("androidId", did)
                    // ★感測器(redroid 常空 → 最大破綻)
                    s("accelerometer", accel); s("magnetometer", magn); s("gyroscope", gyro)
                    // 風控旗標歸零
                    i("emulatorStatus", 0); i("isRoot", 0); i("debugStatus", 0); i("proxyStatus", 0); i("isMockLocation", 0)
                    log("ext overwritten: ${m.keys.size} keys, sensors filled, emu/root=0")
                }
            })
            log("hooked $CRP#processExtensionParams (single-point ext override)")
        } catch (e: Throwable) { log("hook ext fail: ${e.message}") }
    }

    // ③ 藏模擬器 / root / proxy
    private fun hideEmulatorRoot(cl: ClassLoader) {
        pin(cl, XDEV_U, "isEmulator", 0); pin(cl, XDEV_U, "isRooted", 0)
        pin(cl, COMBO_DU, "isEmulator", 0); pin(cl, COMBO_DU, "isRooted", 0)
        pin(cl, COMBO_DU, "isProxy", 0); pin(cl, COMBO_DU, "hasOpenDebugMode", 0)
        pin(cl, XDEV_U, "getCpuModel", prof.chip)  // telemetry 晶片名(藏 redroid);/proc/cpuinfo 建議映像層
        // SystemProperties.get:ro.kernel.qemu→"", ro.soc.*→真晶片
        val sp = XposedHelpers.findClassIfExists(SYSPROP, cl)
        if (sp != null) {
            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args.getOrNull(0) as? String ?: return
                    when (key) {
                        "ro.kernel.qemu" -> param.result = ""
                        "ro.soc.model" -> param.result = prof.soc
                        "ro.soc.manufacturer" -> param.result = "QTI"
                        "ro.hardware" -> param.result = prof.hardware
                    }
                }
            }
            try { XposedBridge.hookAllMethods(sp, "get", cb) } catch (e: Throwable) {}
            log("SystemProperties hooked (qemu/soc)")
        }
        // proxy 屬性
        try {
            XposedHelpers.findAndHookMethod(System::class.java, "getProperty", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val k = param.args[0] as? String ?: return
                    if (k == "http.proxyHost" || k == "https.proxyHost") param.result = null
                }
            })
        } catch (e: Throwable) {}
        // su / Superuser.apk 檔案探測
        try {
            XposedHelpers.findAndHookMethod(java.io.File::class.java, "exists", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val path = (param.thisObject as? java.io.File)?.absolutePath ?: return
                    if (path.endsWith("/su") || path.endsWith("Superuser.apk") || path.contains("magisk")) param.result = false
                }
            })
        } catch (e: Throwable) {}
    }

    // ④ device_id / device_fp 全鏈一致(模擬器可拋棄,做到底)
    private fun deepIdentity(cl: ClassLoader) {
        // device_fp
        pin(cl, ABS_UID, "obtain", fp)
        pin(cl, FP_SP, "getDeviceFingerprint", fp)
        pin(cl, INFO_MOD, "getDeviceFingerprint", fp)
        pin(cl, PORTE_INFO, "getDeviceFP", fp)
        pinStatic(cl, PORTE_INFO, "deviceFP", fp)
        // device_id / android_id
        pin(cl, INFO_MOD, "getDeviceId", did)
        pin(cl, COMBO_DU, "getDeviceID", did); pin(cl, COMBO_DU, "getAndroidID", did)
        pin(cl, PORTE_DU, "getDeviceID", did)
        pin(cl, PORTE_INFO, "getDeviceID", did)
        pinStatic(cl, PORTE_INFO, "deviceID", did)
        pin(cl, XDEV_U, "getAndroidID", did)
        hookSettingsAndroidId(cl)
        // 登入 header 最終出口
        overrideHeaderMap(cl, PORTE_INFO, "getRequestCommonHeader")
        overrideHeaderMap(cl, PORTE_INFO, "updateCommonHeader")
    }

    // 付款/帳號 okhttp 請求 header 覆寫(帶 x-rpc-device_* 的才動)
    private fun hookOkHttpHeaders(cl: ClassLoader) {
        val b = XposedHelpers.findClassIfExists(OKHTTP_B, cl) ?: return
        try {
            XposedBridge.hookAllMethods(b, "build", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (inBuild.get()) return
                    try {
                        val req = param.result ?: return
                        val hasFp = header(req, H_FP) != null; val hasId = header(req, H_ID) != null
                        if (!hasFp && !hasId) return
                        inBuild.set(true)
                        val nb = req.javaClass.getMethod("newBuilder").invoke(req)
                        if (hasFp) setHeader(nb, H_FP, fp); if (hasId) setHeader(nb, H_ID, did)
                        param.result = nb.javaClass.getMethod("build").invoke(nb)
                    } catch (e: Throwable) {} finally { inBuild.set(false) }
                }
            })
        } catch (e: Throwable) { log("okhttp fail: ${e.message}") }
    }

    // ── 工具 ──
    private fun header(req: Any, name: String): String? = try {
        req.javaClass.getMethod("header", String::class.java).invoke(req, name) as? String } catch (e: Throwable) { null }
    private fun setHeader(builder: Any, name: String, value: String) {
        builder.javaClass.getMethod("header", String::class.java, String::class.java).invoke(builder, name, value) }

    private fun pin(cl: ClassLoader, cls: String, method: String, value: Any) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl) ?: return
        try {
            val n = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = value }
            }).size
            if (n == 0) log("NOMATCH $cls#$method")
        } catch (e: Throwable) { log("pin $cls#$method fail: ${e.message}") }
    }
    private fun pinStatic(cl: ClassLoader, cls: String, field: String, value: Any) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl) ?: return
        try { XposedHelpers.setStaticObjectField(clazz, field, value) } catch (e: Throwable) {}
    }
    private fun overrideHeaderMap(cl: ClassLoader, cls: String, method: String) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl) ?: return
        try {
            XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    forceMap(param.result)
                    val self = param.thisObject ?: return
                    try { forceMap(XposedHelpers.getStaticObjectField(self.javaClass, "requestCommonHeader")) } catch (e: Throwable) {}
                    try { XposedHelpers.setStaticObjectField(self.javaClass, "deviceFP", fp) } catch (e: Throwable) {}
                    try { XposedHelpers.setStaticObjectField(self.javaClass, "deviceID", did) } catch (e: Throwable) {}
                }
            })
        } catch (e: Throwable) {}
    }
    @Suppress("UNCHECKED_CAST")
    private fun forceMap(obj: Any?) {
        val m = obj as? MutableMap<String, Any?> ?: return
        try { if (m.containsKey(H_FP)) m[H_FP] = fp; if (m.containsKey(H_ID)) m[H_ID] = did } catch (e: Throwable) {}
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

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
}
