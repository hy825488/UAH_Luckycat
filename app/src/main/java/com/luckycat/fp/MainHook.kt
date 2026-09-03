package com.luckycat.fp

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Luckycat 裝置身分固定版 v5（通吃 原神 / 崩壞星穹鐵道 / 絕區零）
 *
 * 需求（使用者定調）：每次「冷啟遊戲」的當下產生一筆隨機裝置參數,整個 session 全程用同一筆
 *   → 每次開不一樣、但一次啟動內完全一致（登入信箱驗證才不會無限跳）。
 *
 * ★v5 修正 v4「真機指紋漏出來 + 45 秒內跳 5 個值」的根因（反編譯 7.0.1 + 稽核 agent 得證）：
 *   - 本版其實是【單程序】,跳值不是多程序造成的。
 *   - device_fp 是【非同步網路算出來】的（FingerprintService 用真硬體 ~38 個特徵算）,算好前 SDK 先丟
 *     newDefaultDeviceId()=10 位隨機數頂著,算好後真值(如 38d7f7605390f)存進 in-memory 快取
 *     (AbstractDeviceUniqueIdentifier.deviceFingerprint / PorteOSInfo.deviceFP)後續請求直接讀 → 真機漏出。
 *   - v4 攔的是 SP getter（儲水槽）,真值卻從非同步管線灌進 in-memory（出水口）→ 攔不到。
 *   ✅ v5 改攔【最終出水口 getter】：obtain() / PorteOSInfo.getDeviceFP()/getDeviceID() /
 *      InfoModule.getDeviceFingerprint()/getDeviceId() 一律回本次冷啟固定的那筆假值。
 *      不管底下非同步算出什麼真值、快取什麼,出口恆為我那筆 → 真機不漏、全程一致。
 *
 * 地區完全不動。root/代理/模擬器風控歸零保留。
 * ⚠️ 換新裝置 = 把遊戲從最近工作列滑掉殺程序再重開（只切背景不殺不會換,這是刻意的,避免下單中途亂換）。
 * ⚠️ 換裝置後首登該帳號會跳一次信箱驗證,驗過即綁定該假裝置;同一次啟動內不會再跳。
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "LuckycatFp5"
        // 全部 game-agnostic 共用 ComboSDK class（真實名,非 jadx 的 p004/p005 前綴）
        private const val ABS_UID  = "com.mihoyo.platform.sdk.devicefp.AbstractDeviceUniqueIdentifier"
        private const val FP_SP    = "com.mihoyo.platform.sdk.devicefp.DeviceFingerprintSharedPreferences"
        private const val PORTE_INFO = "com.mihoyo.platform.account.oversea.sdk.PorteOSInfo"
        private const val PORTE_DU = "com.mihoyo.platform.account.oversea.sdk.internal.shared.utils.DeviceUtils"
        private const val INFO_MOD = "com.combosdk.framework.module.info.InfoModule"
        private const val COMBO_DU = "com.combosdk.support.base.utils.DeviceUtils"
        private const val XDEV_U   = "com.mihoyo.platform.utilities.XDeviceUtils"
        private const val SETTINGS_SECURE = "android.provider.Settings\$Secure"
        // 只作用這 7 個確切套件（精確白名單,免誤傷）
        private val TARGETS = setOf(
            "com.miHoYo.GenshinImpact", "com.miHoYo.ys.mihoyo", "com.miHoYo.Yuanshen",
            "com.HoYoverse.hkrpgoversea", "com.miHoYo.hkrpg",
            "com.HoYoverse.Nap", "com.miHoYo.Nap"
        )
        private val RND = java.security.SecureRandom()
        private const val HEX = "0123456789abcdef"
        fun randHex(n: Int) = buildString { repeat(n) { append(HEX[RND.nextInt(16)]) } }
    }

    // 本次冷啟的固定假身分（單程序 → by lazy 即等於「每次啟動一筆、全程一致」）
    private val fp: String by lazy { randHex(13) }          // device_fp：13 hex
    private val did: String by lazy { randHex(16) }         // device_id / android_id：16 hex

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGETS) return
        val cl = lpparam.classLoader
        try {
            log("Loaded ${lpparam.packageName}  fp=$fp  device_id=$did")

            // ── device_fp：釘所有「最終回傳 fp」的出水口（蓋掉非同步真值 + in-memory 快取 + SP + 臨時預設）──
            pin(cl, ABS_UID,   "obtain", fp)                    // ★AbstractDeviceUniqueIdentifier.obtain() 是總源頭
            pin(cl, PORTE_INFO,"getDeviceFP", fp)               // 登入 header 讀這個
            pin(cl, INFO_MOD,  "getDeviceFingerprint", fp)      // combo 通道
            pin(cl, FP_SP,     "getDeviceFingerprint", fp)      // SP getter 也一併回固定值(不再回 null 觸發亂跳)

            // ── device_id / android_id：兩條通道 + 根,全釘同一筆 ──
            pin(cl, PORTE_INFO,"getDeviceID", did)              // 登入 header 讀這個
            pin(cl, INFO_MOD,  "getDeviceId", did)
            pin(cl, PORTE_DU,  "getDeviceID", did)
            pin(cl, COMBO_DU,  "getDeviceID", did)
            pin(cl, COMBO_DU,  "getAndroidID", did)
            pin(cl, XDEV_U,    "getAndroidID", did)
            hookSettingsAndroidId(cl)                           // Settings.Secure 兜底(含 getStringForUser)

            // ── 風控紅旗歸零(改機能出單前提,與地區無關) ──
            pin(cl, COMBO_DU, "isRooted", 0)
            pin(cl, COMBO_DU, "isProxy", 0)
            pin(cl, COMBO_DU, "isEmulator", 0)
            pin(cl, COMBO_DU, "hasOpenDebugMode", 0)
        } catch (e: Throwable) {
            log("Error: ${e.message}")
        }
    }

    // 把某 class 某方法的所有多載一律「事後改成回傳固定值」(class/方法不存在只警告不 crash)
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

    // Settings.Secure.getString / getStringForUser：只把 android_id 短路成本次固定值
    private fun hookSettingsAndroidId(cl: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(SETTINGS_SECURE, cl) ?: return
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size >= 2 && param.args[1] == "android_id") param.result = did
            }
        }
        try { XposedBridge.hookAllMethods(clazz, "getString", cb) } catch (e: Throwable) {}
        try { XposedBridge.hookAllMethods(clazz, "getStringForUser", cb) } catch (e: Throwable) {}
        log("Settings.Secure android_id -> $did")
    }

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
}
