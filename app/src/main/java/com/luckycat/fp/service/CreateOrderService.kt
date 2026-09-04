package com.luckycat.fp.service

import com.luckycat.fp.model.Constants
import com.luckycat.fp.model.FakeIdentity
import com.luckycat.fp.util.Xp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Locale

/**
 * 【Service 層 · createOrder 改參數 + 重簽】★核心
 *
 * hook PayModel#getCreateOrderParams —— 回傳 createOrder 的 Map,在 sign 計算「之前」。
 * 我們在這裡:
 *   1) order.device  → 假 device_id(跟源頭一致)
 *   2) order.country → 對齊「帳號的國家」(ISO3;讀不到就不動)
 *   3) map.sign      → 用 SDK 自己的 HttpCompleteUtils.generateSign(order) 重算
 * → sign 天生有效,不破簽名。這是整個插件過付款的關鍵一步。
 *
 * ⚠️ 提醒:who.account / order.uid / token 是「帳號身分」,我們沒動也不能動——帳號被支付風控(135)
 *     時改設備/IP 都沒用,只能冷卻 ~15 分。
 */
class CreateOrderService(private val cl: ClassLoader, private val id: FakeIdentity) {

    @Suppress("UNCHECKED_CAST")
    fun install() {
        val pm = Xp.findClass(cl, Constants.PAY_MODEL) ?: return
        try {
            XposedBridge.hookAllMethods(pm, "getCreateOrderParams", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val map = param.result as? MutableMap<String, Any?> ?: return
                        val order = map["order"] as? MutableMap<String, Any?> ?: run {
                            Xp.log("[CreateOrder] 回傳無 order map,跳過"); return
                        }
                        val oldCountry = order["country"]; val oldDevice = order["device"]
                        order["device"] = id.deviceId                        // 假裝置
                        accountCountryIso3()?.let { order["country"] = it }   // 對齊帳號國家
                        reSign(order)?.let { map["sign"] = it }               // SDK 自己重簽
                        Xp.log("[CreateOrder] country $oldCountry->${order["country"]} device $oldDevice->${id.deviceId} resign=${map["sign"] != null}")
                    } catch (e: Throwable) {
                        Xp.log("[CreateOrder] err: ${e.message}")
                    }
                }
            })
            Xp.log("[CreateOrder] PayModel#getCreateOrderParams hooked")
        } catch (e: Throwable) {
            Xp.log("[CreateOrder] fail: ${e.message}")
        }
    }

    /** 呼叫 SDK 自己的 generateSign(順序試 Companion / INSTANCE / static)。 */
    private fun reSign(order: Map<String, Any?>): String? {
        val cls = XposedHelpers.findClassIfExists(Constants.HTTP_COMPLETE, cl) ?: return null
        for (holder in listOf("Companion", "INSTANCE")) {
            val r = runCatching {
                val obj = XposedHelpers.getStaticObjectField(cls, holder)
                XposedHelpers.callMethod(obj, "generateSign", order) as? String
            }.getOrNull()
            if (r != null) return r
        }
        return runCatching { XposedHelpers.callStaticMethod(cls, "generateSign", order) as? String }.getOrNull()
    }

    /** 讀「帳號的國家」→ ISO3(2 碼轉 3 碼);讀不到回 null(表示不改 country)。 */
    private fun accountCountryIso3(): String? = runCatching {
        val entity = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass(Constants.ACCOUNT_UTILS, cl), "getAccountInfo4Pay"
        ) ?: return@runCatching null
        val c = XposedHelpers.callMethod(entity, "getCountry") as? String ?: return@runCatching null
        when {
            c.length == 2 -> Locale("", c).isO3Country.takeIf { it.length == 3 }
            c.length == 3 -> c
            else -> null
        }
    }.getOrNull()
}
