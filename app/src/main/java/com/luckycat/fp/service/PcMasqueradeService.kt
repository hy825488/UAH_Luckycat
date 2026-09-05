package com.luckycat.fp.service

import com.luckycat.fp.model.Constants
import com.luckycat.fp.util.Xp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy
import java.security.SecureRandom

/**
 * 【Service 層 · createOrder 偽裝成 PC 電腦版(實驗)】
 *
 * 依原神 PC 版封包 1:1 把「createOrder 這一發」改成 PC 客戶端的樣子:
 *   - body:client_type=3、price_tier=Tier_1、country=""、device=PC格式長id → 再用 SDK 重簽
 *   - header:client_type=3、UnityPlayer UA、Windows sys_version/device_os、PC device_model/name、
 *            device_id=PC長id、加 payment_version/goods_third_party/sub_channel_id、版本號 2.54.0.0
 *
 * 目的:PC(client_type=3)在同帳號同商品下 createOrder 直接 retcode 0,推測 PC 風控較鬆。
 *       在模擬器上把這發偽裝成 PC,測 135 會不會消失。
 *
 * ⚠️ 只改 createOrder 請求,App 本體仍是 Android(走 Google Play)→ 就算 createOrder 過,
 *    後續付款步驟大概會斷(回應是 PC 網頁式)。此版純為驗證「PC 偽裝能否過 135」。
 */
class PcMasqueradeService(private val cl: ClassLoader) {

    private val RND = SecureRandom()
    private fun hex(n: Int) = buildString { repeat(n) { append("0123456789abcdef"[RND.nextInt(16)]) } }
    private val pcDeviceId = hex(54)                     // PC 格式的長 device_id
    private val pcName = "DESKTOP-" + buildString { repeat(7) { append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[RND.nextInt(36)]) } }
    // 跟 PC 封包一字不差(值已是 URL-encode 後的樣子,直接當 header 值送)
    private val WIN = "Windows%2010%20%20%2810.0.19045%29%2064bit"
    private val PC_UA = "UnityPlayer/2017.4.30f1 (UnityWebRequest/1.0, libcurl/7.51.0-DEV)"

    fun install() {
        hookBodyToPc()   // getCreateOrderParams → PC body + 重簽
        hookHeadersToPc() // okhttp createOrder 請求 → PC headers
    }

    /** getCreateOrderParams:把 order 改成 PC 形狀,再用 SDK 自己的 generateSign 重簽。 */
    @Suppress("UNCHECKED_CAST")
    private fun hookBodyToPc() {
        val pm = Xp.findClass(cl, Constants.PAY_MODEL) ?: return
        try {
            XposedBridge.hookAllMethods(pm, "getCreateOrderParams", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val map = param.result as? MutableMap<String, Any?> ?: return
                        val order = map["order"] as? MutableMap<String, Any?> ?: return
                        order["client_type"] = 3
                        if (order["price_tier"] == null || order["price_tier"] == "") order["price_tier"] = "Tier_1"
                        order["country"] = ""              // PC 版是空字串
                        order["device"] = pcDeviceId       // PC 長 device_id
                        reSign(order)?.let { map["sign"] = it }
                        Xp.log("[PC] createOrder body -> PC(client_type=3, device=$pcDeviceId) resign=${map["sign"]!=null}")
                    } catch (e: Throwable) { Xp.log("[PC] body err: ${e.message}") }
                }
            })
            Xp.log("[PC] getCreateOrderParams hooked")
        } catch (e: Throwable) { Xp.log("[PC] body hook fail: ${e.message}") }
    }

    private fun reSign(order: Map<String, Any?>): String? {
        val cls = XposedHelpers.findClassIfExists(Constants.HTTP_COMPLETE, cl) ?: return null
        for (h in listOf("Companion", "INSTANCE")) {
            val r = runCatching { XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(cls, h), "generateSign", order) as? String }.getOrNull()
            if (r != null) return r
        }
        return runCatching { XposedHelpers.callStaticMethod(cls, "generateSign", order) as? String }.getOrNull()
    }

    /** okhttp:createOrder 請求的 header 全換成 PC 版。 */
    private fun hookHeadersToPc() {
        val builderCls = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient\$Builder", cl) ?: return
        val interceptorCls = XposedHelpers.findClass("okhttp3.Interceptor", cl)
        val interceptor = Proxy.newProxyInstance(cl, arrayOf(interceptorCls)) { _, method, args ->
            when (method.name) {
                "intercept" -> {
                    val chain = args!![0]
                    val request = XposedHelpers.callMethod(chain, "request")
                    val url = XposedHelpers.callMethod(request, "url").toString()
                    if (url.contains("createOrder")) {
                        val nb = XposedHelpers.callMethod(request, "newBuilder")
                        fun set(k: String, v: String) = XposedHelpers.callMethod(nb, "header", k, v)
                        set("x-rpc-client_type", "3")
                        set("User-Agent", PC_UA)
                        set("x-rpc-sys_version", WIN)
                        set("x-rpc-device_os", WIN)
                        set("x-rpc-device_model", "System%20Product%20Name%20%28System%20manufacturer%29")
                        set("x-rpc-device_name", pcName)
                        set("x-rpc-device_id", pcDeviceId)
                        set("x-rpc-payment_version", "2.54.0")
                        set("x-rpc-goods_third_party", "unsupported")
                        set("x-rpc-sub_channel_id", "6")
                        set("x-rpc-mdk_version", "2.54.0.0")
                        set("x-rpc-sdk_version", "2.54.0.0")
                        set("x-rpc-channel_version", "2.54.0.0")
                        set("x-rpc-app_version", "2.54.0.0")
                        Xp.log("[PC] createOrder headers -> PC(client_type=3, UnityPlayer)")
                        XposedHelpers.callMethod(chain, "proceed", XposedHelpers.callMethod(nb, "build"))
                    } else {
                        XposedHelpers.callMethod(chain, "proceed", request)
                    }
                }
                "toString" -> "PcMasqInterceptor"; "hashCode" -> System.identityHashCode(this); "equals" -> false
                else -> null
            }
        }
        XposedBridge.hookAllMethods(builderCls, "build", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val list = XposedHelpers.getObjectField(param.thisObject, "interceptors") as MutableList<Any?>
                    if (list.none { it === interceptor }) list.add(interceptor)
                } catch (e: Throwable) {}
            }
        })
        Xp.log("[PC] createOrder header interceptor installed")
    }
}
