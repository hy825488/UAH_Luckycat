package com.luckycat.fp.service

import com.luckycat.fp.model.Constants
import com.luckycat.fp.model.FakeIdentity
import com.luckycat.fp.util.Xp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 【Service 層 · 藏模擬器 / root】
 *
 * 把「偵測方法本身」釘成乾淨:isEmulator / isRooted → 0、CPU 型號 → 真晶片、qemu 屬性清掉。
 *
 * ★效能教訓(v14):不 hook File.exists!
 *   原神是 Unity,File.exists() 是每秒上萬次的熱點方法(載資源/串流/快取),
 *   全域 hook 會累積開銷 → 玩一陣子卡死 / ANR。
 *   而 root 隱藏已由 isRooted→0 涵蓋(su 檔存不存在都回沒 root),所以 File.exists 多餘,移除。
 *
 * 原則:只 hook「偵測方法」與「低頻屬性讀取」,絕不 hook 每幀/每秒高頻的通用 API。
 */
class EmulatorHideService(private val cl: ClassLoader, private val id: FakeIdentity) {

    fun install() {
        // 模擬器/root 偵測方法 → 回 0(乾淨)
        Xp.pin(cl, Constants.XDEV_U, "isEmulator", 0)
        Xp.pin(cl, Constants.XDEV_U, "isRooted", 0)
        Xp.pin(cl, Constants.COMBO_DU, "isEmulator", 0)
        Xp.pin(cl, Constants.COMBO_DU, "isRooted", 0)
        Xp.pin(cl, Constants.COMBO_DU, "isProxy", 0)
        Xp.pin(cl, Constants.COMBO_DU, "hasOpenDebugMode", 0)
        // CPU 型號 → 真晶片(藏 redroid / x86)
        Xp.pin(cl, Constants.COMBO_DU, "getCpuModel", id.profile.chip)
        Xp.pin(cl, Constants.XDEV_U, "getCpuModel", id.profile.chip)

        // SystemProperties.get:清 qemu、回真晶片(此 API 低頻,安全)
        val sp = XposedHelpers.findClassIfExists(Constants.SYS_PROP, cl) ?: return
        runCatching {
            XposedBridge.hookAllMethods(sp, "get", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    when (param.args.getOrNull(0) as? String) {
                        "ro.kernel.qemu" -> param.result = ""            // 清模擬器旗標
                        "ro.soc.model" -> param.result = id.profile.soc
                        "ro.soc.manufacturer" -> param.result = "QTI"
                        "ro.hardware" -> param.result = id.profile.hardware
                    }
                }
            })
        }
        Xp.log("[EmuHide] installed (刻意不 hook File.exists)")
    }
}
