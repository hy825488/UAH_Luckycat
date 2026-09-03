package com.luckycat.fp

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Luckycat 帳號/付款裝置偽裝 v8（通吃 原神 / 崩壞星穹鐵道 / 絕區零）
 *
 * 定調(使用者)：偽裝只縮到【登入 / 帳號 / 登入後的 createOrder 等付款請求】,
 *   遊戲引擎讀真機 → 資源快取穩定 → 不再每次重跑更新。
 *
 * 為什麼不能改底層 getter：miHoYo 遊戲引擎跟登入/付款 SDK 讀「同一組」底層 device getter
 *   (Settings.Secure android_id / combo DeviceUtils / InfoModule / device-fp 引擎)。改底層 → 遊戲引擎
 *   也讀到新機 → 以為新裝置 → 重新校驗/重下資源(重跑更新)。v6 就是踩這個。
 *
 * ★v8 正解 = 只改「送出去的網路請求」,不碰底層:
 *   1) 登入/帳號(PorteOS):hook getRequestCommonHeader()/updateCommonHeader() 回傳的 header map +
 *      釘死 PorteOSInfo 靜態欄位 deviceFP/deviceID(蓋掉非同步監聽器塞回的真值)。
 *   2) createOrder 等所有付款/帳號請求:hook okhttp Request.build(),凡是【本來就帶 x-rpc-device_fp /
 *      x-rpc-device_id 的請求】(帳號/付款 API 才帶,遊戲資源下載不帶)→ 把這兩個 header 覆寫成本次固定假值。
 *      只改 header、不動已簽名的 body(避免 createOrder 的 sign 失效);不碰遊戲引擎本地 device → 不重跑更新。
 *
 * 三款共用同一份 ComboSDK/PorteOS SDK(game-agnostic),一顆通吃。地區不動;風控紅旗歸零保留。
 * ⚠️ 換新登入裝置 = 殺程序重開;首登該帳號跳一次信箱驗證,驗過即綁定,同啟動內不再跳。
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "LuckycatFp8"
        private const val PORTE_INFO = "com.mihoyo.platform.account.oversea.sdk.PorteOSInfo"
        private const val PORTE_DU   = "com.mihoyo.platform.account.oversea.sdk.internal.shared.utils.DeviceUtils"
        private const val COMBO_DU   = "com.combosdk.support.base.utils.DeviceUtils" // 只關風控旗標
        private const val OKHTTP_BUILDER = "okhttp3.Request\$Builder"
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
    }

    private val fp: String by lazy { randHex(13) }
    private val did: String by lazy { randHex(16) }
    private val inBuild = ThreadLocal.withInitial { false }  // 防 okhttp build() 遞迴

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGETS) return
        val cl = lpparam.classLoader
        try {
            log("Loaded ${lpparam.packageName}  fp=$fp  device_id=$did")

            // (1) 登入/帳號 header(PorteOS)
            pinStaticField(cl, PORTE_INFO, "deviceFP", fp)
            pinStaticField(cl, PORTE_INFO, "deviceID", did)
            overrideHeaderMap(cl, PORTE_INFO, "getRequestCommonHeader")
            overrideHeaderMap(cl, PORTE_INFO, "updateCommonHeader")
            pin(cl, PORTE_INFO, "getDeviceFP", fp)
            pin(cl, PORTE_INFO, "getDeviceID", did)
            pin(cl, PORTE_DU,   "getDeviceID", did)

            // (2) createOrder 等所有付款/帳號請求:凡帶 x-rpc-device_* 的 okhttp 請求就覆寫 header
            hookOkHttpDeviceHeaders(cl)

            // 風控紅旗歸零(布林,不影響資源快取)
            pin(cl, COMBO_DU, "isRooted", 0)
            pin(cl, COMBO_DU, "isProxy", 0)
            pin(cl, COMBO_DU, "isEmulator", 0)
            pin(cl, COMBO_DU, "hasOpenDebugMode", 0)
        } catch (e: Throwable) {
            log("Error: ${e.message}")
        }
    }

    // ── okhttp:只覆寫「本來就帶 x-rpc-device_* 的請求」的那兩個 header(帳號/付款 API);不動 body ──
    private fun hookOkHttpDeviceHeaders(cl: ClassLoader) {
        val builderClass = XposedHelpers.findClassIfExists(OKHTTP_BUILDER, cl)
        if (builderClass == null) { log("MISS okhttp3.Request\$Builder"); return }
        try {
            XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (inBuild.get()) return
                    try {
                        val req = param.result ?: return
                        val hasFp = header(req, H_FP) != null
                        val hasId = header(req, H_ID) != null
                        if (!hasFp && !hasId) return          // 非帳號/付款請求(遊戲資源下載不帶)→ 放行
                        inBuild.set(true)
                        val nb = req.javaClass.getMethod("newBuilder").invoke(req)
                        if (hasFp) setHeader(nb, H_FP, fp)
                        if (hasId) setHeader(nb, H_ID, did)
                        param.result = nb.javaClass.getMethod("build").invoke(nb)
                    } catch (e: Throwable) { log("okhttp rewrite err: ${e.message}") }
                    finally { inBuild.set(false) }
                }
            })
            log("okhttp x-rpc device header rewrite installed")
        } catch (e: Throwable) { log("hook okhttp fail: ${e.message}") }
    }

    private fun header(req: Any, name: String): String? = try {
        req.javaClass.getMethod("header", String::class.java).invoke(req, name) as? String
    } catch (e: Throwable) { null }
    private fun setHeader(builder: Any, name: String, value: String) {
        builder.javaClass.getMethod("header", String::class.java, String::class.java).invoke(builder, name, value)
    }

    private fun pin(cl: ClassLoader, cls: String, method: String, value: Any) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl)
        if (clazz == null) { log("MISS $cls (skip $method)"); return }
        try {
            val n = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = value }
            }).size
            if (n > 0) log("pinned $cls#$method x$n -> $value") else log("NOMATCH $cls#$method")
        } catch (e: Throwable) { log("pin $cls#$method fail: ${e.message}") }
    }

    private fun pinStaticField(cl: ClassLoader, cls: String, field: String, value: Any) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl) ?: run { log("MISS $cls (skip field $field)"); return }
        try { XposedHelpers.setStaticObjectField(clazz, field, value); log("field $cls.$field := $value") }
        catch (e: Throwable) { log("set field $cls.$field fail: ${e.message}") }
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
            log("override header via $cls#$method")
        } catch (e: Throwable) { log("override $cls#$method fail: ${e.message}") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun forceMap(obj: Any?) {
        val m = obj as? MutableMap<String, Any?> ?: return
        try {
            if (m.containsKey(H_FP)) m[H_FP] = fp
            if (m.containsKey(H_ID)) m[H_ID] = did
        } catch (e: Throwable) {}
    }

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
}
