package com.luckycat.fp.view

import com.luckycat.fp.model.Constants
import com.luckycat.fp.model.FakeIdentity
import com.luckycat.fp.util.Xp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 【View 層 · 狀態 Toast】
 *
 * MVVM 的 View:本模組唯一的「畫面」。觀察 ViewModel 的假身分,開遊戲時彈 Toast 顯示本次偽裝內容,
 * 讓使用者一眼確認「插件有生效 + 這次抽到的機型 / device_id / device_fp」。
 *
 * 實作:hook Application#onCreate 拿到 context,延遲 3 / 8 / 15 秒在主執行緒各彈一次
 * (涵蓋 載入 → 標題 的過渡,不會錯過)。Toast 也能蓋在遊戲的 Unity 畫面上。
 */
class StatusToastView(
    private val cl: ClassLoader,
    private val pkg: String,
    private val id: FakeIdentity
) {
    @Volatile private var done = false

    fun install() {
        val appCls = runCatching { XposedHelpers.findClass(Constants.APPLICATION, cl) }.getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllMethods(appCls, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (done) return
                    done = true
                    val ctx = param.thisObject as? android.content.Context ?: return
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    for (delay in longArrayOf(3000, 8000, 15000)) {
                        handler.postDelayed({
                            runCatching {
                                android.widget.Toast.makeText(ctx, message(), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }, delay)
                    }
                    Xp.log("[Toast] scheduled")
                }
            })
        }
    }

    private fun gameName() = when {
        pkg.contains("Genshin", true) || pkg.contains("ys", true) || pkg.contains("Yuanshen", true) -> "原神"
        pkg.contains("hkrpg", true) -> "星穹鐵道"
        pkg.contains("Nap", true) -> "絕區零"
        else -> pkg
    }

    private fun message() =
        "MuMu 版本 12 全部生效 ✓  [${gameName()}]"
}
