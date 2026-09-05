package com.luckycat.fp

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy

/**
 * ZZZ 簽名診斷抓包工具(com.gsnet.mon)
 *
 * 目的:絕區零 createOrder 跳「參數簽名不正確」。要修就得看 ZZZ 到底怎麼算 sign。
 * 這支把「簽名的來龍去脈」全 dump 出來:
 *   1) OkHttp 層:createOrder / verify / listAppPayPlat 的【完整】請求 body + 回應(不截斷)
 *   2) 簽名函式:OSTools.sign(map, appKey) 與 Tools.sign(map, appKey) 的【輸入 map + appKey + 輸出 sign】
 *      → 這條最關鍵:直接看到 ZZZ 用的 appKey、被簽的欄位、算出來的正確 sign
 *   3) HttpCompleteUtils.generateSign(map) 的輸入輸出
 *   4) PayModel.getCreateOrderParams 回傳的整包 map(order 結構 + sign 欄位)
 *
 * 用法:
 *   - 【重要】測 ZZZ 時,先把 v18 那顆插件「停用」,只留這支 → 抓到的是 ZZZ【原本】的正確簽名(基準)
 *   - adb -s 127.0.0.1:16xxx logcat -s ZSNIFF:I > zzz.txt
 *   - 進絕區零 → 商城 → 點一個商品到彈付款 → 把 zzz.txt 整包給我
 *   - (可選)再把 v18 打開抓一份,對比我們重簽出來的差在哪
 */
class MainHook : IXposedHookLoadPackage {

    private val targets = setOf(
        "com.miHoYo.GenshinImpact", "com.miHoYo.ys.mihoyo", "com.miHoYo.Yuanshen",
        "com.HoYoverse.hkrpgoversea", "com.miHoYo.hkrpg",
        "com.HoYoverse.Nap", "com.miHoYo.Nap"
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in targets) return
        val cl = lpparam.classLoader
        out("== ZSNIFF 載入 ${lpparam.packageName} ==")
        runCatching { hookSign(cl) }.onFailure { out("hookSign err: ${it.message}") }
        runCatching { hookGenerateSign(cl) }.onFailure { out("hookGenerateSign err: ${it.message}") }
        runCatching { hookCreateOrderParams(cl) }.onFailure { out("hookCOParams err: ${it.message}") }
        runCatching { hookOkHttp(cl) }.onFailure { out("hookOkHttp err: ${it.message}") }
    }

    // ───────────────────────── 簽名函式(最關鍵)─────────────────────────

    /** OSTools.sign(map,appKey) 與 Tools.sign(map,appKey):dump 輸入 map + appKey + 輸出 sign。 */
    private fun hookSign(cl: ClassLoader) {
        val cb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val map = param.args.getOrNull(0)
                    val appKey = param.args.getOrNull(1) as? String
                    val result = param.result as? String
                    val sb = StringBuilder("\n########## sign() ${param.method.declaringClass.name} ##########\n")
                    sb.append("appKey = ").append(appKey).append('\n')
                    sb.append("輸入 map(sorted):\n").append(dumpMap(map, "  "))
                    sb.append("輸出 sign = ").append(result).append('\n')
                    out(sb.toString())
                } catch (e: Throwable) { out("sign dump err: ${e.message}") }
            }
        }
        for (cn in listOf("com.mihoyoos.sdk.platform.common.utils.OSTools", "com.miHoYo.support.utils.Tools")) {
            val c = XposedHelpers.findClassIfExists(cn, cl)
            if (c == null) { out("MISS $cn"); continue }
            var n = 0
            for (m in c.declaredMethods) if (m.name == "sign") { XposedBridge.hookMethod(m, cb); n++ }
            out("hooked $cn.sign x$n")
        }
    }

    /** HttpCompleteUtils.generateSign(map) 輸入輸出。 */
    private fun hookGenerateSign(cl: ClassLoader) {
        val c = XposedHelpers.findClassIfExists("com.mihoyoos.sdk.platform.common.utils.HttpCompleteUtils\$Companion", cl)
            ?: XposedHelpers.findClassIfExists("com.mihoyoos.sdk.platform.common.utils.HttpCompleteUtils", cl)
            ?: run { out("MISS HttpCompleteUtils"); return }
        var n = 0
        for (m in c.declaredMethods) if (m.name == "generateSign") {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    out("\n===== generateSign =====\n輸入:\n" + dumpMap(param.args.getOrNull(0), "  ") + "輸出 = " + param.result)
                }
            }); n++
        }
        out("hooked HttpCompleteUtils.generateSign x$n")
    }

    /** PayModel.getCreateOrderParams:回傳的整包 map(order + sign)。 */
    private fun hookCreateOrderParams(cl: ClassLoader) {
        val c = XposedHelpers.findClassIfExists("com.mihoyoos.sdk.platform.module.pay.PayModel", cl)
            ?: run { out("MISS PayModel"); return }
        XposedBridge.hookAllMethods(c, "getCreateOrderParams", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                out("\n@@@@@ getCreateOrderParams 回傳 @@@@@\n" + dumpMap(param.result, "  "))
            }
        })
        out("hooked PayModel.getCreateOrderParams")
    }

    // ───────────────────────── OkHttp 完整請求/回應 ─────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun hookOkHttp(cl: ClassLoader) {
        val builderCls = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient\$Builder", cl)
            ?: run { out("MISS OkHttpClient\$Builder"); return }
        val interceptorCls = XposedHelpers.findClass("okhttp3.Interceptor", cl)
        val interceptor = Proxy.newProxyInstance(cl, arrayOf(interceptorCls)) { _, method, args ->
            when (method.name) {
                "intercept" -> {
                    val chain = args!![0]
                    val request = XposedHelpers.callMethod(chain, "request")
                    val response = XposedHelpers.callMethod(chain, "proceed", request)
                    try { logExchange(cl, request, response) } catch (_: Throwable) {}
                    response
                }
                "toString" -> "ZSniffInterceptor"; "hashCode" -> System.identityHashCode(this); "equals" -> false
                else -> null
            }
        }
        XposedBridge.hookAllMethods(builderCls, "build", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val list = XposedHelpers.getObjectField(param.thisObject, "interceptors") as MutableList<Any?>
                    if (list.none { it === interceptor }) list.add(interceptor)
                } catch (_: Throwable) {}
            }
        })
        out("OkHttp logger installed")
    }

    private fun logExchange(cl: ClassLoader, request: Any, response: Any) {
        val url = XposedHelpers.callMethod(request, "url").toString()
        val name = url.substringAfterLast('/').substringBefore('?')
        if (name !in setOf("createOrder", "verify", "listAppPayPlat")) return
        val method = XposedHelpers.callMethod(request, "method") as? String ?: "?"
        val code = XposedHelpers.callMethod(response, "code")
        val sb = StringBuilder()
        sb.append("\n───────── $method $url -> HTTP $code ─────────\n")
        for (h in listOf("x-rpc-client_type","x-rpc-device_id","x-rpc-device_fp","x-rpc-device_model","x-rpc-device_os","user-agent"))
            appendHeader(sb, request, h)
        val reqBody = readRequestBody(cl, request)
        if (reqBody.isNotEmpty()) sb.append("REQ:\n").append(reqBody).append('\n')
        sb.append("RESP:\n").append(peekResponseBody(cl, response))
        out(sb.toString())
    }

    private fun appendHeader(sb: StringBuilder, request: Any, name: String) {
        try {
            val v = XposedHelpers.callMethod(request, "header", name) as? String
            if (!v.isNullOrEmpty()) sb.append("H ").append(name).append(": ").append(v).append('\n')
        } catch (_: Throwable) {}
    }

    private fun readRequestBody(cl: ClassLoader, request: Any): String = try {
        val body = XposedHelpers.callMethod(request, "body")
        if (body == null) "" else {
            val bufferCls = XposedHelpers.findClass("okio.Buffer", cl)
            val sinkCls = XposedHelpers.findClass("okio.BufferedSink", cl)
            val buffer = bufferCls.getDeclaredConstructor().newInstance()
            body.javaClass.getMethod("writeTo", sinkCls).invoke(body, buffer)
            bufferCls.getMethod("readUtf8").invoke(buffer) as? String ?: ""
        }
    } catch (e: Throwable) { "(read req body 失敗:${e.message})" }

    private fun peekResponseBody(cl: ClassLoader, response: Any): String = try {
        val rb = XposedHelpers.callMethod(response, "peekBody", 1048576L)
        XposedHelpers.callMethod(rb, "string") as? String ?: ""
    } catch (e: Throwable) { "(peek 失敗:${e.message})" }

    // ───────────────────────── 工具 ─────────────────────────

    /** 把 Map 依 key 排序 dump(巢狀 Map 遞迴),模擬 sign 的排序串接。 */
    @Suppress("UNCHECKED_CAST")
    private fun dumpMap(obj: Any?, indent: String): String {
        if (obj !is Map<*, *>) return "$indent(非 Map: $obj)\n"
        val sb = StringBuilder()
        val keys = obj.keys.map { it.toString() }.sorted()
        for (k in keys) {
            val v = obj[k]
            if (v is Map<*, *>) { sb.append("$indent$k = {\n").append(dumpMap(v, "$indent  ")).append("$indent}\n") }
            else sb.append("$indent$k = $v\n")
        }
        return sb.toString()
    }

    private fun out(msg: String) {
        XposedBridge.log("ZSNIFF: $msg")
        var s = msg
        while (s.isNotEmpty()) { Log.i("ZSNIFF", s.take(3800)); s = s.drop(3800) }
    }
}
