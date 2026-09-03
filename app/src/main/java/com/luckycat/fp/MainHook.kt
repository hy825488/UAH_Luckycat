package com.luckycat.fp

import android.content.ContentResolver
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * Luckycat 裝置身分輪換插件 v4（通吃 原神 / 崩壞星穹鐵道 / 絕區零）
 *
 * 目的：讓每次「冷啟遊戲」= 一台全新且自洽的裝置（device_fp + device_id + android_id 全換）,
 *      配合換 IP,分散 miHoYo createOrder 的限流。地區完全不動。
 *
 * ★v4 相對 v3 補齊的關鍵缺口（反編譯 7.0.1 + 兩個稽核 agent 得證）：
 *   裝置身分是「三個東西、三個 SharedPreferences、共用 ComboSDK」——v3 只清了 device_fp,漏了 device_id：
 *   1) device_fp  → com.mihoyo.platform.sdk.devicefp.DeviceFingerprintSharedPreferences(SP combo_device_fingerprint, key seed/fp)
 *   2) device_id(combo 通道)  → com.combosdk.support.base.utils.DeviceUtils(SP mihoyo_sdk_preference)= androidId
 *   3) device_id(登入 PorteOS)→ com.mihoyo.platform.account.oversea.sdk.internal.shared.utils.DeviceUtils(SP porte_sdk_common)= androidId
 *   根都是 Settings.Secure "android_id"。→ v4 把「兩條 device_id getter + android_id 源」直接回本 session 值,
 *   device_fp 沿用「seed/fp getter 首次回 null → SDK 向 getFp 要伺服器真發的新 fp」。全部 game-agnostic,一顆通吃。
 *
 * ⚠️ 用法：要換新裝置就「把遊戲從最近工作列滑掉(殺程序)再重開」。只是切背景不殺,不會換(SDK 常駐快取)。
 *   —— 這是刻意的：避免下單流程中途(Google 付款頁返回)亂換造成不一致。每單前殺掉重開即可。
 *
 * ⚠️ 換裝置後首登該帳號會撞 -3239「新裝置登入需驗證」,此為換裝置必然代價。
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "LuckycatFp4"
        // 全部 game-agnostic 的共用 ComboSDK class（真實名,非 jadx 的 p004/p005 前綴）
        private const val FP_SP    = "com.mihoyo.platform.sdk.devicefp.DeviceFingerprintSharedPreferences"
        private const val COMBO_DU = "com.combosdk.support.base.utils.DeviceUtils"
        private const val PORTE_DU = "com.mihoyo.platform.account.oversea.sdk.internal.shared.utils.DeviceUtils"
        private const val XDEV_U   = "com.mihoyo.platform.utilities.XDeviceUtils"
        // 只輪換這 7 個確切套件（精確白名單,不用寬鬆 contains,免誤傷 snapchat 等）
        private val TARGETS = setOf(
            "com.miHoYo.GenshinImpact", "com.miHoYo.ys.mihoyo", "com.miHoYo.Yuanshen",
            "com.HoYoverse.hkrpgoversea", "com.miHoYo.hkrpg",
            "com.HoYoverse.Nap", "com.miHoYo.Nap"
        )
        private val RND = java.security.SecureRandom()
        private const val HEX = "0123456789abcdef"
        fun randHex(n: Int) = buildString { repeat(n) { append(HEX[RND.nextInt(16)]) } }
    }

    // 本次冷啟的裝置身分（session 內一致；殺程序重開才換）
    private val sessionAndroidId: String by lazy { randHex(16) }
    private val firstFired = ConcurrentHashMap<String, Boolean>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGETS) return
        try {
            log("Loaded ${lpparam.packageName}  sessionAndroidId=$sessionAndroidId")
            hookRiskFlags(lpparam.classLoader)   // root/代理/模擬器/除錯歸零（與地區無關）
            hookDeviceFp(lpparam.classLoader)    // device_fp：seed/fp getter 首次回 null → 伺服器發新 fp
            hookDeviceId(lpparam.classLoader)    // ★device_id 兩條通道 + androidId 源，全回本 session 值
        } catch (e: Throwable) {
            log("Error: ${e.message}")
        }
    }

    // ── device_fp：清 SP getter,逼 SDK 每次冷啟重生 seed → getFp 拿伺服器真發的新 fp ──
    private fun hookDeviceFp(cl: ClassLoader) {
        firstCallReturnNull(cl, FP_SP, "getSeedId")
        firstCallReturnNull(cl, FP_SP, "getDeviceFingerprint")
    }

    // ── device_id：兩條 DeviceUtils.getDeviceID + androidId 源,一律回本 session 的 16 hex ──
    private fun hookDeviceId(cl: ClassLoader) {
        alwaysReturn(cl, COMBO_DU, "getDeviceID", sessionAndroidId)
        alwaysReturn(cl, COMBO_DU, "getAndroidID", sessionAndroidId)
        alwaysReturn(cl, PORTE_DU, "getDeviceID", sessionAndroidId)
        alwaysReturn(cl, XDEV_U,   "getAndroidID", sessionAndroidId)
        // 兜底：直接攔 Settings.Secure（含 getStringForUser 多載）的 android_id
        hookSettingsAndroidId(cl)
    }

    // ── 風控紅旗歸零（改機/越獄能出單的前提）──
    private fun hookRiskFlags(cl: ClassLoader) {
        alwaysReturn(cl, COMBO_DU, "isRooted", 0)
        alwaysReturn(cl, COMBO_DU, "isProxy", 0)
        alwaysReturn(cl, COMBO_DU, "isEmulator", 0)
        alwaysReturn(cl, COMBO_DU, "hasOpenDebugMode", 0)
    }

    // ===== 通用 hook 工具（都按方法名 hook 所有多載,不寫死簽章;class 不存在只警告不 crash）=====

    // 某方法所有多載一律回傳固定值
    private fun alwaysReturn(cl: ClassLoader, cls: String, method: String, value: Any) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl)
        if (clazz == null) { log("MISS class $cls (skip $method)"); return }
        try {
            val n = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = value }
            }).size
            log("hooked $cls#$method x$n -> $value")
        } catch (e: Throwable) { log("hook $cls#$method fail: ${e.message}") }
    }

    // 開機後某方法「第一次」呼叫回 null(逼重生),之後放行 SDK 自己存的新值 → session 內一致
    private fun firstCallReturnNull(cl: ClassLoader, cls: String, method: String) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl)
        if (clazz == null) { log("MISS class $cls (skip $method)"); return }
        try {
            val n = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (firstFired.putIfAbsent(method, true) == null) {
                        param.result = null
                        log("[$method] first-call -> null (regenerate)")
                    }
                }
            }).size
            log("hooked $cls#$method x$n (one-shot null)")
        } catch (e: Throwable) { log("hook $cls#$method fail: ${e.message}") }
    }

    // Settings.Secure.getString / getStringForUser：只把 android_id 短路成本 session 值
    private fun hookSettingsAndroidId(cl: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists("android.provider.Settings\$Secure", cl) ?: return
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // getString(cr, name) / getStringForUser(cr, name, userId) — name 都在 args[1]
                if (param.args.size >= 2 && param.args[1] == "android_id") {
                    param.result = sessionAndroidId
                }
            }
        }
        try { XposedBridge.hookAllMethods(clazz, "getString", cb) } catch (e: Throwable) {}
        try { XposedBridge.hookAllMethods(clazz, "getStringForUser", cb) } catch (e: Throwable) {}
        log("Settings.Secure android_id -> $sessionAndroidId")
    }

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
}
