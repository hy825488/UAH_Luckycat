package com.luckycat.fp.service

import com.luckycat.fp.model.Constants
import com.luckycat.fp.model.FakeIdentity
import com.luckycat.fp.util.Xp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy
import java.security.SecureRandom

/**
 * 【Service 層 · 整場偽裝成 PC 電腦版(v18 實驗)】
 *
 * v17 只把 createOrder 那一發偽裝成 PC → 登入是手機、下單是 PC,自相矛盾,風控可能直接抓。
 * v18 把「整個連線階段」全部翻成 PC(client_type=3),沒有矛盾,才是乾淨測試:
 *
 *   1) client_type 源頭:hook SDKInfo.getClientType() 與 PorteOSInfo.getClientType() → 都回 3
 *      → 登入 body / getFp / createOrder body / 各 header 的 client_type 全變 3(全鏈一致)
 *   2) header 層:對「所有 miHoYo API 請求」重寫成 PC headers
 *      (UnityPlayer UA、Windows sys_version/device_os、PC device_model/name、
 *       payment_version/goods_third_party/sub_channel_id、版本號 2.54.0.0、device_id=54hex PC)
 *   3) createOrder body:price_tier=Tier_1、country=""、device=PC長id → SDK 重簽
 *
 * device_id 已在 FakeIdentity 改成 54hex PC 格式(DeviceSourceService 釘到各 getDeviceID),
 * 所以 getFp 提交、header、body 用的都是同一顆 PC device_id。
 *
 * ⚠️ 已知取捨:getFp 的 ext 仍帶 Android 特徵(感測器/board),與 client_type=3 不完全一致;
 *    但那是為了藏模擬器+讓 getFp 成功。本版主測「client_type=3 + PC device_id/headers 一致」
 *    能否讓 createOrder 過 135。若過→PC 風控較鬆屬實,再往完整 PC 網頁付款做。
 *    另:全局 client_type=3 可能讓 App 走網頁結帳而非 Google Play,付款 UI 可能與平常不同。
 */
class PcSessionService(private val cl: ClassLoader, private val id: FakeIdentity) {

    private val RND = SecureRandom()
    private val pcName = "DESKTOP-" + buildString { repeat(7) { append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[RND.nextInt(36)]) } }
    // 值照 PC 封包(已是 URL-encode 後的樣子,直接當 header 值送)
    private val WIN = "Windows%2010%20%20%2810.0.19045%29%2064bit"
    private val PC_UA = "UnityPlayer/2017.4.30f1 (UnityWebRequest/1.0, libcurl/7.51.0-DEV)"
    private val PC_MODEL = "System%20Product%20Name%20%28System%20manufacturer%29"

    fun install() {
        forceClientType3()   // client_type 源頭 → 3(登入/getFp/下單 全鏈)
        hookHeadersToPc()    // 所有 miHoYo 請求 header → PC
        hookCreateOrderBody() // createOrder body → PC 形狀 + 重簽
    }

    /** hook 兩個 getClientType() → 回 3。這是「登入/下單 body 的 client_type」與部分 header 的源頭。 */
    private fun forceClientType3() {
        for (cn in listOf(Constants.SDK_INFO, Constants.PORTE_INFO)) {
            val c = XposedHelpers.findClassIfExists(cn, cl)
            if (c == null) { Xp.log("[PC] MISS $cn"); continue }
            runCatching {
                XposedBridge.hookAllMethods(c, "getClientType", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { param.result = 3 }
                })
                Xp.log("[PC] $cn.getClientType -> 3")
            }.onFailure { Xp.log("[PC] $cn.getClientType hook fail: ${it.message}") }
        }
    }

    /** createOrder body:改成 PC 形狀,再用 SDK 的 generateSign 重簽(client_type 已由上面變 3)。 */
    @Suppress("UNCHECKED_CAST")
    private fun hookCreateOrderBody() {
        val pm = Xp.findClass(cl, Constants.PAY_MODEL) ?: return
        runCatching {
            XposedBridge.hookAllMethods(pm, "getCreateOrderParams", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val map = param.result as? MutableMap<String, Any?> ?: return
                        val order = map["order"] as? MutableMap<String, Any?> ?: return
                        order["client_type"] = 3
                        // ★ 不要碰 price_tier!它代表使用者選的面額檔位,SDK 已填好。
                        //   之前寫死 Tier_1(=$1 檔)導致所有面額都被壓成 $1 → 只有 $1 到帳。
                        order["country"] = ""
                        order["device"] = id.deviceId
                        // ★ SDK 簽名只涵蓋「簽名前」的 order 欄位;biz_meta / goods_plat 是簽名後才塞進 order 的
                        //   (見 PayModel.getCreateOrderParams:先 generateSign 再 put biz_meta/goods_plat)。
                        //   重簽時必須把這兩個剔掉,否則簽名範圍多欄位 → 後端算不一致 →「參數簽名不正確」(ZZZ 就是這個)。
                        val signMap = LinkedHashMap<String, Any?>(order).apply {
                            remove("biz_meta"); remove("goods_plat")
                        }
                        reSign(signMap)?.let { map["sign"] = it }
                        Xp.log("[PC] createOrder body -> PC(device=${id.deviceId}) resign=${map["sign"]!=null} signKeys=${signMap.keys.size}")
                    } catch (e: Throwable) { Xp.log("[PC] body err: ${e.message}") }
                }
            })
            Xp.log("[PC] getCreateOrderParams hooked")
        }.onFailure { Xp.log("[PC] body hook fail: ${it.message}") }
    }

    private fun reSign(order: Map<String, Any?>): String? {
        val cls = XposedHelpers.findClassIfExists(Constants.HTTP_COMPLETE, cl) ?: return null
        for (h in listOf("Companion", "INSTANCE")) {
            val r = runCatching { XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(cls, h), "generateSign", order) as? String }.getOrNull()
            if (r != null) return r
        }
        return runCatching { XposedHelpers.callStaticMethod(cls, "generateSign", order) as? String }.getOrNull()
    }

    /** okhttp:對所有 miHoYo API 請求把 header 換成 PC 版(整場一致)。 */
    private fun hookHeadersToPc() {
        val builderCls = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient\$Builder", cl)
            ?: run { Xp.log("[PC] MISS OkHttpClient\$Builder"); return }
        val interceptorCls = XposedHelpers.findClass("okhttp3.Interceptor", cl)
        val interceptor = Proxy.newProxyInstance(cl, arrayOf(interceptorCls)) { _, method, args ->
            when (method.name) {
                "intercept" -> {
                    val chain = args!![0]
                    val request = XposedHelpers.callMethod(chain, "request")
                    val url = XposedHelpers.callMethod(request, "url").toString()
                    if (isMihoyoApi(url)) {
                        val nb = XposedHelpers.callMethod(request, "newBuilder")
                        fun set(k: String, v: String) = XposedHelpers.callMethod(nb, "header", k, v)
                        set("x-rpc-client_type", "3")
                        set("User-Agent", PC_UA)
                        set("x-rpc-sys_version", WIN)
                        set("x-rpc-device_os", WIN)
                        set("x-rpc-device_model", PC_MODEL)
                        set("x-rpc-device_name", pcName)
                        set("x-rpc-device_id", id.deviceId)
                        set("x-rpc-payment_version", "2.54.0")
                        set("x-rpc-goods_third_party", "unsupported")
                        set("x-rpc-sub_channel_id", "6")
                        set("x-rpc-mdk_version", "2.54.0.0")
                        set("x-rpc-sdk_version", "2.54.0.0")
                        set("x-rpc-channel_version", "2.54.0.0")
                        set("x-rpc-app_version", "2.54.0.0")
                        if (url.contains("createOrder")) Xp.log("[PC] createOrder headers -> PC")
                        XposedHelpers.callMethod(chain, "proceed", XposedHelpers.callMethod(nb, "build"))
                    } else {
                        XposedHelpers.callMethod(chain, "proceed", request)
                    }
                }
                "toString" -> "PcSessionInterceptor"; "hashCode" -> System.identityHashCode(this); "equals" -> false
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
        Xp.log("[PC] 全 miHoYo 請求 PC header 攔截器已裝")
    }

    /** 只處理 miHoYo 的 API 網域,略過資源 CDN,避免動到不相干連線。 */
    private fun isMihoyoApi(url: String): Boolean {
        val u = url.lowercase()
        if (!u.contains("hoyoverse") && !u.contains("mihoyo") && !u.contains("yuanshen")) return false
        if (u.contains("autopatch") || u.contains("/pkg_version") || u.contains("bundle") || u.endsWith(".wmv")) return false
        return true
    }
}
