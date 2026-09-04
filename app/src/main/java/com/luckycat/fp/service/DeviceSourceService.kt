package com.luckycat.fp.service

import com.luckycat.fp.model.Constants
import com.luckycat.fp.model.FakeIdentity
import com.luckycat.fp.util.Xp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 【Service 層 · device 身分源頭偽造】
 *
 * 在「產生 device_id / device_fp 的源頭」把值換掉,而不是在請求層(okhttp header)改寫。
 * 為什麼:請求層改寫在簽名之後 → 會跟 body/token 內的原值對不上、破簽名。
 * 改源頭 → header / body / token / lifecycle_id 全鏈都拿到同一個值,天生一致。
 */
class DeviceSourceService(private val cl: ClassLoader, private val id: FakeIdentity) {

    fun install() {
        // device_id 的各個源頭(不同通道各有一個)
        Xp.pin(cl, Constants.SDK_INFO, "deviceId", id.deviceId)
        Xp.pin(cl, Constants.GAME_CONFIG, "getDeviceId", id.deviceId)
        Xp.pin(cl, Constants.COMBO_DU, "getDeviceID", id.deviceId)
        Xp.pin(cl, Constants.COMBO_DU, "getAndroidID", id.deviceId)
        Xp.pin(cl, Constants.PORTE_DU, "getDeviceID", id.deviceId)
        Xp.pin(cl, Constants.XDEV_U, "getAndroidID", id.deviceId)

        // device_fp 源頭:直接在 obtain() 捏假值(全鏈一致;乾淨網路下可行)
        Xp.pin(cl, Constants.ABS_UID, "obtain", id.deviceFp)

        // Settings.Secure android_id 兜底(有些路徑直接讀這個)
        hookAndroidId()
    }

    private fun hookAndroidId() {
        val clazz = XposedHelpers.findClassIfExists(Constants.SETTINGS_SECURE, cl) ?: return
        // getString(cr, name) 與 getStringForUser(cr, name, userId) 的 name 都在 args[1]
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size >= 2 && param.args[1] == "android_id") param.result = id.deviceId
            }
        }
        runCatching { XposedBridge.hookAllMethods(clazz, "getString", cb) }
        runCatching { XposedBridge.hookAllMethods(clazz, "getStringForUser", cb) }
        Xp.log("[DeviceSrc] android_id -> ${id.deviceId}")
    }
}
