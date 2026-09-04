package com.luckycat.fp.util

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 【工具層 · Util】
 *
 * Xposed hook 的共用小工具 + 統一日誌。
 * 讓 Service(hook 執行層)寫 hook 時不用重複那些 try/catch、findClassIfExists 的樣板。
 * 這一層不含業務邏輯,只是「把 Xposed API 包得好用一點」。
 */
object Xp {
    /** LSPosed 日誌標籤;在 LSPosed App 的「日誌」頁用這個字串就能過濾出本模組的輸出。 */
    const val TAG = "LuckycatFp"

    /** 統一日誌:輸出到 XposedBridge log(LSPosed 日誌可見)。 */
    fun log(msg: String) = XposedBridge.log("$TAG: $msg")

    /**
     * 把某類別某方法的「所有多載」一律【事後】改成回傳固定值。
     * 用途:把偵測方法(isRooted / isEmulator…)或身分 getter(deviceId / obtain…)釘死成我們要的值。
     *
     * 設計重點:
     *  - 用 hookAllMethods 按「方法名」hook,不寫死參數簽章,避免遊戲版本簽章不同就整條失效。
     *  - class 不存在只記 log,不 crash(findClassIfExists)。
     *
     * @param cls 完整類名字串
     */
    fun pin(cl: ClassLoader, cls: String, method: String, value: Any) {
        val clazz = XposedHelpers.findClassIfExists(cls, cl) ?: run { log("MISS $cls (skip $method)"); return }
        try {
            val n = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = value }
            }).size
            if (n == 0) log("NOMATCH $cls#$method") else log("pin $cls#$method x$n -> $value")
        } catch (e: Throwable) {
            log("pin $cls#$method fail: ${e.message}")
        }
    }

    /** findClassIfExists 包一層,回 null 時記 log(給 Service 用來早退)。 */
    fun findClass(cl: ClassLoader, cls: String) =
        XposedHelpers.findClassIfExists(cls, cl) ?: run { log("MISS $cls"); null }
}
