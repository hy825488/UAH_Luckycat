package com.luckycat.fp

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * Luckycat 國家欄位改寫插件（UA/UAH）
 *
 * 針對 HoYoverse luckycat 金流三段做 request body 改寫：
 *   listAppPayPlat → createOrder → verify
 *
 * 改寫方式為「依 key、不依現值」的正則替換，所以不管目前是 TWN/HKD 還是別的值都會被覆蓋。
 *
 * ⚠️ 重要（實測 HAR 得證）：
 *   - listAppPayPlat 沒有 sign，改了會直接生效。
 *   - createOrder 帶 sign（在 native il2cpp 算，Java hook 無法重算）。
 *     改 order.country / order.currency 後 sign 會對不上，server 可能回 sign 錯誤 / 退款碼。
 *     要真的送出 UA 的 createOrder 必須改用 Frida 掛 libil2cpp.so 重簽，不是這裡能做到的。
 *   - 兩單國家欄位本來就完全相同（TWN/HK/HKD），到帳/不到帳的差別在 Google 收據本身，不在這些欄位。
 *   把 MODIFY_CREATE_ORDER 設 true 是為了「送出去看 retcode」做實驗診斷用。
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "LuckycatUA"

        // ===== 烏克蘭欄位設定（要改格式直接改這裡）=====
        // 註：app 自己的格式不一致 —— app_download_country / order.country 用 alpha-3(TWN)，
        //     store_country 用 alpha-2(HK)。這裡各自沿用對應格式：UKR / UA。
        private const val APP_DOWNLOAD_COUNTRY = "UA"   // 原本 TWN(alpha-3)，改用 UA 統一
        private const val STORE_COUNTRY        = "UA"   // alpha-2，對應原本 HK
        private const val STORE_CURRENCY       = "UAH"
        private const val GAME_CURRENCY        = "UAH"
        private const val ORDER_COUNTRY        = "UA"   // 原本 order.country=TWN(alpha-3)，改用 UA 統一
        private const val ORDER_CURRENCY       = "UAH"

        // createOrder 段的開關。true = 照要求一起改（會破壞 sign，僅供實驗看 retcode）。
        private const val MODIFY_CREATE_ORDER = true
        // verify 段：若 body 內出現同名 key 就一併改（防禦性，key 不存在則不動）。
        private const val MODIFY_VERIFY = true
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!isTargetApp(lpparam.packageName)) return
        try {
            log("Loaded app: ${lpparam.packageName}")
            hookOkHttp(lpparam)
        } catch (e: Throwable) {
            log("Error: ${e.message}")
        }
    }

    private fun isTargetApp(pkgName: String): Boolean {
        return pkgName.contains("genshin", ignoreCase = true) ||
                pkgName.contains("starrail", ignoreCase = true) ||
                pkgName.contains("zenless", ignoreCase = true) ||
                pkgName.contains("honkai", ignoreCase = true) ||
                pkgName.contains("hoyoverse", ignoreCase = true) ||
                pkgName.contains("mihoyo", ignoreCase = true) ||
                pkgName.contains("nap", ignoreCase = true)
    }

    private fun hookOkHttp(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        try {
            val requestBuilderClass = XposedHelpers.findClass("okhttp3.Request\$Builder", cl)
            XposedHelpers.findAndHookMethod(
                requestBuilderClass, "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val request = param.result ?: return
                            val url = extractUrl(request) ?: return
                            val kind = classify(url) ?: return

                            val oldJson = readBody(request, cl) ?: return
                            val newJson = rewrite(kind, oldJson) ?: return
                            if (newJson == oldJson) return

                            log("[$kind] URL: $url")
                            log("[$kind] BEFORE: $oldJson")
                            log("[$kind] AFTER : $newJson")

                            val newRequest = replaceBody(request, newJson, cl) ?: return
                            param.result = newRequest
                        } catch (e: Throwable) {
                            log("Hook error: ${e.message}")
                        }
                    }
                }
            )
            log("OkHttp3 body-rewrite hook installed")
        } catch (e: Throwable) {
            log("Failed to hook OkHttp3: ${e.message}")
        }
    }

    // ---- 分類三段 ----
    private fun classify(url: String): String? {
        if (!url.contains("luckycat", ignoreCase = true)) return null
        return when {
            url.contains("listAppPayPlat", ignoreCase = true) -> "listAppPayPlat"
            url.contains("createOrder", ignoreCase = true)     -> if (MODIFY_CREATE_ORDER) "createOrder" else null
            url.contains("verify", ignoreCase = true)          -> if (MODIFY_VERIFY) "verify" else null
            else -> null
        }
    }

    // ---- 依段落改寫 JSON body ----
    private fun rewrite(kind: String, json: String): String? {
        return when (kind) {
            "listAppPayPlat" -> json
                .let { setField(it, "app_download_country", APP_DOWNLOAD_COUNTRY) }
                .let { setField(it, "store_country", STORE_COUNTRY) }
                .let { setField(it, "store_currency", STORE_CURRENCY) }
                .let { setField(it, "game_currency", GAME_CURRENCY) }
            "createOrder" -> json
                // order 物件內的 country / currency（這兩個 key 只出現在 order 裡）
                .let { setField(it, "country", ORDER_COUNTRY) }
                .let { setField(it, "currency", ORDER_CURRENCY) }
            "verify" -> json
                // 防禦性：verify body 若含這些 key 才改，沒有就原樣返回
                .let { setFieldIfPresent(it, "app_download_country", APP_DOWNLOAD_COUNTRY) }
                .let { setFieldIfPresent(it, "store_country", STORE_COUNTRY) }
                .let { setFieldIfPresent(it, "store_currency", STORE_CURRENCY) }
                .let { setFieldIfPresent(it, "game_currency", GAME_CURRENCY) }
                .let { setFieldIfPresent(it, "country", ORDER_COUNTRY) }
                .let { setFieldIfPresent(it, "currency", ORDER_CURRENCY) }
            else -> null
        }
    }

    // "key":"anything" -> "key":"value" （值不論原本是什麼都覆蓋）
    private fun setField(json: String, key: String, value: String): String {
        val re = Regex("(\"" + Regex.escape(key) + "\"\\s*:\\s*\")[^\"]*(\")")
        return re.replace(json) { m -> m.groupValues[1] + value + m.groupValues[2] }
    }

    private fun setFieldIfPresent(json: String, key: String, value: String): String {
        return if (json.contains("\"$key\"")) setField(json, key, value) else json
    }

    // ---- OkHttp 反射工具 ----
    private fun extractUrl(request: Any): String? {
        return try {
            // Request 有 url() (3.x) 或 getUrl()；直接呼叫 url() 取 HttpUrl 再 toString
            val m = findNoArgMethod(request.javaClass, "url")
            m?.invoke(request)?.toString()
        } catch (e: Throwable) {
            try {
                val superClass = request.javaClass.superclass
                val f = superClass?.getDeclaredField("url")?.apply { isAccessible = true }
                f?.get(request)?.toString()
            } catch (e2: Throwable) { null }
        }
    }

    private fun readBody(request: Any, cl: ClassLoader): String? {
        return try {
            val bodyMethod = findNoArgMethod(request.javaClass, "body") ?: return null
            val body = bodyMethod.invoke(request) ?: return null

            val bufferedSinkClass = XposedHelpers.findClass("okio.BufferedSink", cl)
            val bufferClass = XposedHelpers.findClass("okio.Buffer", cl)
            val buffer = bufferClass.getDeclaredConstructor().newInstance()

            val writeTo = body.javaClass.getMethod("writeTo", bufferedSinkClass)
            writeTo.invoke(body, buffer)

            val readUtf8 = bufferClass.getMethod("readUtf8")
            readUtf8.invoke(buffer) as? String
        } catch (e: Throwable) {
            log("readBody error: ${e.message}")
            null
        }
    }

    private fun replaceBody(request: Any, newJson: String, cl: ClassLoader): Any? {
        return try {
            val requestBodyClass = XposedHelpers.findClass("okhttp3.RequestBody", cl)
            val mediaTypeClass   = XposedHelpers.findClass("okhttp3.MediaType", cl)

            // 取原本 body 的 contentType，維持一致
            val bodyMethod = findNoArgMethod(request.javaClass, "body")
            val oldBody = bodyMethod?.invoke(request)
            val mediaType = try {
                oldBody?.javaClass?.getMethod("contentType")?.invoke(oldBody)
            } catch (e: Throwable) { null }

            // 找 static create(MediaType, String) 或 create(String, MediaType)
            val newBody = createRequestBody(requestBodyClass, mediaTypeClass, mediaType, newJson)
                ?: return null

            val httpMethod = findNoArgMethod(request.javaClass, "method")?.invoke(request) as? String
                ?: "POST"

            val newBuilder = request.javaClass.getMethod("newBuilder").invoke(request)
            val methodM = newBuilder.javaClass.getMethod("method", String::class.java, requestBodyClass)
            methodM.invoke(newBuilder, httpMethod, newBody)
            newBuilder.javaClass.getMethod("build").invoke(newBuilder)
        } catch (e: Throwable) {
            log("replaceBody error: ${e.message}")
            null
        }
    }

    private fun createRequestBody(
        requestBodyClass: Class<*>,
        mediaTypeClass: Class<*>,
        mediaType: Any?,
        json: String
    ): Any? {
        // 優先 create(MediaType, String)（3.x / 4.x 皆有此靜態橋接）
        try {
            val m = requestBodyClass.getMethod("create", mediaTypeClass, String::class.java)
            return m.invoke(null, mediaType, json)
        } catch (e: Throwable) { /* fallthrough */ }
        // 退而求其次 create(String, MediaType)（4.x Kotlin 順序）
        try {
            val m = requestBodyClass.getMethod("create", String::class.java, mediaTypeClass)
            return m.invoke(null, json, mediaType)
        } catch (e: Throwable) { /* fallthrough */ }
        // 最後用 byte[] 版本 create(MediaType, byte[])
        try {
            val m = requestBodyClass.getMethod("create", mediaTypeClass, ByteArray::class.java)
            return m.invoke(null, mediaType, json.toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) {
            log("createRequestBody: no create() variant matched")
            return null
        }
    }

    private fun findNoArgMethod(clazz: Class<*>, name: String): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(name)
                m.isAccessible = true
                return m
            } catch (e: NoSuchMethodException) { c = c.superclass }
        }
        return null
    }

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
}
