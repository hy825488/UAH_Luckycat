package com.luckycat.fp.service

import com.luckycat.fp.model.Constants
import com.luckycat.fp.model.FakeIdentity
import com.luckycat.fp.util.Xp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 【Service 層 · getFp 上報特徵洗淨】
 *
 * hook CommonRequiredParams#processExtensionParams —— 這是「組出上報 /device-fp/api/getFp 的整包 ext」
 * 的單一咽喉點。事後把整包覆寫成:成套真機硬體 + 三軸感測器假值 + 模擬器/root 旗標歸零。
 * → 伺服器據此算出的 device_fp 就是「一台乾淨真機」的指紋。
 *
 * 這是「模擬器版」必要、真機版不需要的一層(真機硬體本來就真)。
 */
class ExtParamsService(private val cl: ClassLoader, private val id: FakeIdentity) {

    @Suppress("UNCHECKED_CAST")
    fun install() {
        val clazz = Xp.findClass(cl, Constants.CRP) ?: return
        try {
            XposedBridge.hookAllMethods(clazz, "processExtensionParams", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val m = param.result as? MutableMap<String, Any?> ?: return
                    val p = id.profile
                    // 只覆寫「本來就存在」的 key(不同遊戲版本 ext 欄位略有差異,存在才改)
                    fun s(k: String, v: String) { if (m.containsKey(k)) m[k] = v }
                    fun i(k: String, v: Int)    { if (m.containsKey(k)) m[k] = v }

                    // 硬體特徵 → 成套真機
                    s("board", p.board); s("brand", p.brand); s("hardware", p.hardware)
                    s("cpuType", "arm64-v8a"); s("deviceType", p.device); s("display", p.display)
                    s("hostname", "build-host"); s("manufacturer", p.manufacturer); s("productName", p.product)
                    s("model", p.model); s("deviceInfo", p.fingerprint); s("osVersion", p.release)
                    s("buildTags", "release-keys"); s("buildType", "user"); s("buildUser", "dpi")
                    s("serialNumber", id.serial); s("androidId", id.deviceId)

                    // ★三軸感測器(模擬器最容易露餡的欄位)
                    s("accelerometer", id.accelerometer)
                    s("magnetometer", id.magnetometer)
                    s("gyroscope", id.gyroscope)

                    // 風控旗標歸零
                    i("emulatorStatus", 0); i("isRoot", 0); i("debugStatus", 0)
                    i("proxyStatus", 0); i("isMockLocation", 0)
                }
            })
            Xp.log("[Ext] processExtensionParams hooked")
        } catch (e: Throwable) {
            Xp.log("[Ext] fail: ${e.message}")
        }
    }
}
