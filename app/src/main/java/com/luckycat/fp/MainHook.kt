package com.luckycat.fp

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Luckycat 登入裝置偽裝 v7（通吃 原神 / 崩壞星穹鐵道 / 絕區零）
 *
 * 演進：
 *   v6 把裝置改在很底層(Settings.Secure android_id / combo DeviceUtils / InfoModule / device-fp 引擎),
 *       登入成功了,但這些「遊戲引擎本身也在讀」→ 遊戲每次啟動以為是新機 → 重新校驗/重下資源(重跑更新)。
 *   ★v7 只把偽裝縮到【登入/帳號那條(PorteOS)】:
 *       - 帳號伺服器靠登入 header 的 device 認「這帳號在哪台機」→ 只釘這裡就能防帳號串連 + 每次不同的新機。
 *       - 遊戲引擎讀到的是【真機】→ 資源快取穩定 → 不再重跑更新。
 *   登入 header 的最終出口 = PorteOSInfo.getRequestCommonHeader() 回傳的 map(updateCommonHeader 直讀
 *   靜態欄位 deviceFP/deviceID 組成,繞過 getter)→ v7 直接覆寫這個 map + 釘死那兩個靜態欄位。
 *
 * 地區不動;root/代理/模擬器風控歸零保留(避免被判改機,布林值不影響資源快取)。
 * ⚠️ 換新登入裝置 = 殺程序重開;首登該帳號會跳一次信箱驗證,驗過即綁定,同啟動內不再跳。
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "LuckycatFp7"
        private const val PORTE_INFO = "com.mihoyo.platform.account.oversea.sdk.PorteOSInfo"
        private const val PORTE_DU   = "com.mihoyo.platform.account.oversea.sdk.internal.shared.utils.DeviceUtils"
        private const val COMBO_DU   = "com.combosdk.support.base.utils.DeviceUtils" // 只用來關風控旗標
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

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGETS) return
        val cl = lpparam.classLoader
        try {
            log("Loaded ${lpparam.packageName}  fp=$fp  device_id=$did")

            // ── 只在登入/帳號(PorteOS)那條偽裝,遊戲引擎讀真機不受影響 ──
            pin(cl, PORTE_INFO, "getDeviceFP", fp)   // login-scoped getter(belt)
            pin(cl, PORTE_INFO, "getDeviceID", did)
            pin(cl, PORTE_DU,   "getDeviceID", did)  // 登入 SDK 的 device_id 來源(帳號 SDK 內部,非遊戲引擎)
            pinStaticField(cl, PORTE_INFO, "deviceFP", fp)  // 蓋掉非同步監聽器塞回的真值
            pinStaticField(cl, PORTE_INFO, "deviceID", did)
            overrideHeaderMap(cl, PORTE_INFO, "getRequestCommonHeader") // ★最終出口:登入/驗證 header
            overrideHeaderMap(cl, PORTE_INFO, "updateCommonHeader")

            // ── 風控紅旗歸零(避免被判改機;布林值,不影響資源快取/不觸發重跑更新) ──
            pin(cl, COMBO_DU, "isRooted", 0)
            pin(cl, COMBO_DU, "isProxy", 0)
            pin(cl, COMBO_DU, "isEmulator", 0)
            pin(cl, COMBO_DU, "hasOpenDebugMode", 0)
        } catch (e: Throwable) {
            log("Error: ${e.message}")
        }
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
            if (m.containsKey("x-rpc-device_fp")) m["x-rpc-device_fp"] = fp
            if (m.containsKey("x-rpc-device_id")) m["x-rpc-device_id"] = did
        } catch (e: Throwable) {}
    }

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
}
