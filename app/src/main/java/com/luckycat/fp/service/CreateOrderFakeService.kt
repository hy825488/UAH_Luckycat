package com.luckycat.fp.service

import com.luckycat.fp.util.Xp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy

/**
 * 【Service 層 · createOrder 假回應(實驗用)】
 *
 * hook OkHttp:凡是打 createOrder 的請求,回應【整包】替換成:
 *   {"retcode":0,"message":"OK","data":null}
 *
 * ⚠️ 實驗性/不保證到貨:data:null 沒有 order_no,客戶端拿不到訂單 → 後續 Google Play 付款
 *    多半無法進行(只是騙過 135「請求過於頻繁」彈框)。要讓流程真的往下走,需塞真的 order_no。
 *
 * 想關掉:把 MainHook 裡的 CreateOrderFakeService(cl).install() 那行拿掉即可。
 */
class CreateOrderFakeService(private val cl: ClassLoader) {

    private val FAKE = "{\"retcode\":0,\"message\":\"OK\",\"data\":null}"

    fun install() {
        val builderCls = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient\$Builder", cl)
            ?: run { Xp.log("[FakeCO] MISS OkHttpClient\$Builder"); return }
        val interceptorCls = XposedHelpers.findClass("okhttp3.Interceptor", cl)

        val interceptor = Proxy.newProxyInstance(cl, arrayOf(interceptorCls)) { _, method, args ->
            when (method.name) {
                "intercept" -> {
                    val chain = args!![0]
                    val request = XposedHelpers.callMethod(chain, "request")
                    val response = XposedHelpers.callMethod(chain, "proceed", request)
                    val url = XposedHelpers.callMethod(request, "url").toString()
                    if (url.contains("createOrder")) {
                        runCatching { fakeResponse(response) }.getOrDefault(response)
                    } else response
                }
                "toString" -> "FakeCOInterceptor"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
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
        Xp.log("[FakeCO] createOrder 假回應攔截器已裝")
    }

    /** 把 createOrder 回應整包換成 FAKE。 */
    private fun fakeResponse(response: Any): Any {
        val body = makeBody(FAKE)
        val b = XposedHelpers.callMethod(response, "newBuilder")
        runCatching { XposedHelpers.callMethod(b, "code", 200) }
        runCatching { XposedHelpers.callMethod(b, "message", "OK") }
        if (body != null) runCatching { XposedHelpers.callMethod(b, "body", body) }
        Xp.log("[FakeCO] createOrder 回應已替換 -> retcode:0")
        return XposedHelpers.callMethod(b, "build")
    }

    /** 反射建 okhttp3.ResponseBody(相容 3.x / 4.x 幾種 create 簽章)。 */
    private fun makeBody(json: String): Any? {
        val rb = XposedHelpers.findClass("okhttp3.ResponseBody", cl)
        val mtCls = XposedHelpers.findClass("okhttp3.MediaType", cl)
        val mt = parseMediaType(mtCls)
        runCatching { return rb.getMethod("create", mtCls, String::class.java).invoke(null, mt, json) }
        runCatching { return rb.getMethod("create", String::class.java, mtCls).invoke(null, json, mt) }
        runCatching {
            val c = XposedHelpers.getStaticObjectField(rb, "Companion")
            return c.javaClass.getMethod("create", String::class.java, mtCls).invoke(c, json, mt)
        }
        return null
    }

    private fun parseMediaType(mtCls: Class<*>): Any? {
        runCatching { return mtCls.getMethod("parse", String::class.java).invoke(null, "application/json; charset=utf-8") }
        runCatching {
            val c = XposedHelpers.getStaticObjectField(mtCls, "Companion")
            return c.javaClass.getMethod("parse", String::class.java).invoke(c, "application/json; charset=utf-8")
        }
        return null
    }
}
