package com.luckycat.fp

import android.content.ContentResolver
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Luckycat 裝置指紋輪換插件 v3（源頭級：hook miHoYo device-fp SDK）
 *
 * 目標：解 createOrder 的 retcode 135「付款請求過於頻繁」——那是 miHoYo 對「同一台裝置」
 *      下單的速率限流。大量代充跨帳號共用一台實機 → 全擠進同一個 device 桶 → 撞 135。
 *      讓「每次開 app = 一台全新裝置」→ 各自的桶,不互相累加。
 *
 * ★為什麼 v2(改 okhttp header)沒用（實測 HAR 得證）：
 *   device_fp 不是 app 隨手填的 header，是 com.mihoyo.platform.sdk.devicefp 這個 SDK 跟
 *   /device-fp/api/getFp 要來、存進 SharedPreferences「combo_device_fingerprint」(key: seed / fp)
 *   之後到處重用的。改 header 改不到那個快取 → fp 永遠不變。
 *
 * ★v3 正解（源頭）：
 *   hook DeviceFingerprintSharedPreferences.getSeedId / getDeviceFingerprint，
 *   每次啟動「一次性」清掉 → SDK 用 UUID.randomUUID() 生新 seed → 向 getFp 要一個
 *   伺服器真的發的、對應新 seed 的新 device_fp。整個 session 一致（SDK 自己 in-memory 快取），
 *   下次開 app 才換一台。fp 是伺服器發的 → 合法、被認得。
 *   另 spoof Settings.Secure.android_id（device_id 的源、也內嵌在登入 combo_token）→ device_id 同步換。
 *
 * ⚠️ 已知代價：每次「新裝置」首次登入該帳號，miHoYo 會要求身分驗證
 *   （appLoginByPassword 回 -3239「您正在新裝置上登入，請先驗證身分」）。這是「換裝置」必然的。
 *   若要免驗證，需改成「每帳號固定綁一台」而非「每次隨機」——見底部 note，一個開關就能切。
 *
 * 地區完全不動：country / currency / language / age_gate 全部原樣（v2 的國碼 spoof 已全移除）。
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "LuckycatFp3"
        private const val FP_SP_CLASS = "com.mihoyo.platform.sdk.devicefp.DeviceFingerprintSharedPreferences"
        private val RND = java.security.SecureRandom()
        private const val HEX = "0123456789abcdef"
        fun randHex(n: Int) = buildString { repeat(n) { append(HEX[RND.nextInt(16)]) } }
    }

    // 本次啟動的假 android_id（device_id 源），整個 session 固定，下次啟動才換
    private val sessionAndroidId: String by lazy { randHex(16) }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!isTargetApp(lpparam.packageName)) return
        try {
            log("Loaded ${lpparam.packageName}  sessionAndroidId=$sessionAndroidId")
            hookRiskFlags(lpparam)     // root/代理/模擬器/除錯歸零（改機必須，與地區無關）
            hookDeviceFpRotate(lpparam) // ★每次啟動換一顆伺服器真發的 device_fp
            hookAndroidId(lpparam)      // ★device_id 源跟著換
        } catch (e: Throwable) {
            log("Error: ${e.message}")
        }
    }

    // ★核心：清掉 device-fp SDK 的 SP 快取，逼它每次啟動重生 seed+fp
    private fun hookDeviceFpRotate(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        // getSeedId(Context): String? — 第一次回 null → SDK newSeedId()=UUID.randomUUID() → 存回
        hookFirstCallReturnNull(FP_SP_CLASS, cl, "getSeedId")
        // getDeviceFingerprint(Context): String? — 第一次回 null → SDK 用新 seed 向 getFp 要新 fp
        hookFirstCallReturnNull(FP_SP_CLASS, cl, "getDeviceFingerprint")
        log("device-fp SDK cache hooked (fresh seed+fp per launch)")
    }

    // 開機後某方法第一次被呼叫時把回傳改 null(逼 SDK 重生)，之後放行它自己存的新值 → session 內穩定
    private val firstFired = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private fun hookFirstCallReturnNull(cls: String, cl: ClassLoader, method: String) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, "android.content.Context",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (firstFired.putIfAbsent(method, true) == null) {
                            param.result = null
                            log("[$method] first-call -> null (force regenerate)")
                        }
                    }
                })
            log("hooked $method (one-shot null)")
        } catch (e: Throwable) {
            log("hook $method fail: ${e.message}")
        }
    }

    // device_id 源：Settings.Secure.getString(cr, "android_id") → 本次 session 的假值
    private fun hookAndroidId(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings\$Secure", lpparam.classLoader, "getString",
                ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[1] == "android_id") {
                            param.result = sessionAndroidId  // 直接短路，不呼叫原方法
                        }
                    }
                }
            )
            log("android_id hook installed -> $sessionAndroidId")
        } catch (e: Throwable) {
            log("hook android_id fail: ${e.message}")
        }
    }

    // 風控紅旗歸零（改機/越獄環境能出單的前提，非地區）
    private fun hookRiskFlags(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val du = "com.combosdk.support.base.utils.DeviceUtils"
        val ctx = "android.content.Context"
        hookConst(du, cl, "isRooted", 0, ctx)
        hookConst(du, cl, "isProxy", 0)
        hookConst(du, cl, "isEmulator", 0, ctx)
        hookConst(du, cl, "hasOpenDebugMode", 0, ctx)
    }

    private fun hookConst(cls: String, cl: ClassLoader, method: String, value: Any, vararg paramTypes: String) {
        try {
            val args = ArrayList<Any>()
            args.addAll(paramTypes)
            args.add(XC_MethodReplacement.returnConstant(value))
            XposedHelpers.findAndHookMethod(cls, cl, method, *args.toTypedArray())
            log("hooked $method -> $value")
        } catch (e: Throwable) {
            log("hook $method fail: ${e.message}")
        }
    }

    private fun isTargetApp(pkgName: String): Boolean {
        return pkgName.contains("genshin", ignoreCase = true) ||
                pkgName.contains("starrail", ignoreCase = true) ||
                pkgName.contains("zenless", ignoreCase = true) ||
                pkgName.contains("honkai", ignoreCase = true) ||
                pkgName.contains("hoyoverse", ignoreCase = true) ||
                pkgName.contains("mihoyo", ignoreCase = true) ||
                pkgName.contains("nap", ignoreCase = true)
    }

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
}
