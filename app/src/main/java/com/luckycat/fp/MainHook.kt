package com.luckycat.fp

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * Luckycat 裝置指紋輪換插件（保留原始地區，每次請求換一台隨機裝置）
 *
 * 需求變更（2026-09）：
 *   - ❌ 不再做任何「國碼/地區/語言/時區」spoof —— country / currency / language /
 *        age_gate 全部維持 app 原本要送的值（帳號是哪一區就送哪一區）。
 *   - ✅ 只把「裝置身分指紋」隨機化：每次請求（或每次啟動）換一組全新的
 *        device_id / device_fp / device_model / device_name / lifecycle_id / UA。
 *        目的：讓大量代充訂單不會全部指向同一台硬體指紋、被風控做裝置串連 / 速率封鎖。
 *   - ✅ 保留「乾淨裝置」風控紅旗歸零（root/代理/模擬器/除錯）—— 這是越獄/改機環境
 *        能正常出單的前提，且與「地區」無關，不動。
 *
 * 指紋輪換點（實測 HAR 得證的 x-rpc-* header）：
 *   x-rpc-device_id(16hex) / x-rpc-device_fp(13hex) / x-rpc-device_model /
 *   x-rpc-device_name / x-rpc-lifecycle_id(內嵌 device_id) / user-agent(Dalvik 機型)
 *   同一請求內所有 header + body 用「同一台」隨機裝置，跨請求才不同 → 對外自洽、對內每單不同。
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "LuckycatFpRotate"

        // true=每一個請求都換一台新裝置；false=每次 app 啟動固定一台（整個 session 一致）
        private const val RANDOM_PER_REQUEST = true

        // 要輪換的裝置身分 header（全部保留原本大小寫）
        private const val H_DEVICE_ID   = "x-rpc-device_id"
        private const val H_DEVICE_FP   = "x-rpc-device_fp"
        private const val H_MODEL       = "x-rpc-device_model"
        private const val H_NAME        = "x-rpc-device_name"
        private const val H_LIFECYCLE   = "x-rpc-lifecycle_id"
        private const val H_UA          = "user-agent"

        // body 內若出現這些 key 也一併換成「同一台」隨機裝置（getFp / report 等）
        private val BODY_ID_KEYS   = listOf("device_id", "deviceId")
        private val BODY_FP_KEYS   = listOf("device_fp", "deviceFp")
        private val BODY_MODEL_KEYS= listOf("device_model", "deviceModel")
        private val BODY_NAME_KEYS = listOf("device_name", "deviceName")

        // 真實機型池（含對應 build id），隨機挑一台，避免亂碼機型自己變成破綻
        private val DEVICE_POOL = listOf(
            Triple("Pixel 7",      "Pixel 7",          "TQ3A.230901.001"),
            Triple("Pixel 8",      "Pixel 8",          "UP1A.231105.003"),
            Triple("Pixel 6a",     "Pixel 6a",         "TQ3A.230805.001"),
            Triple("SM-S918B",     "Galaxy S23 Ultra", "UP1A.231005.007"),
            Triple("SM-S911B",     "Galaxy S23",       "UP1A.231005.007"),
            Triple("SM-G991B",     "Galaxy S21",       "SP1A.210812.016"),
            Triple("SM-A536B",     "Galaxy A53",       "TP1A.220624.014"),
            Triple("2201122G",     "Xiaomi 12",        "TKQ1.220807.001"),
            Triple("23127PN0CG",   "Xiaomi 14",        "UKQ1.230804.001"),
            Triple("22101316C",    "Redmi Note 12",    "TP1A.220624.014"),
            Triple("CPH2449",      "OnePlus 11",       "UP1A.231005.007"),
            Triple("CPH2371",      "OPPO Reno8",       "TP1A.220905.001"),
            Triple("V2130",        "vivo X80",         "TP1A.220624.014"),
            Triple("RMX3363",      "realme GT2 Pro",   "SKQ1.220303.001"),
            Triple("motorola edge 30", "moto edge 30", "TP1A.220624.014")
        )

        private val RND = java.security.SecureRandom()
    }

    // 每次啟動固定一台時用的快取（RANDOM_PER_REQUEST=false）
    @Volatile private var cachedDevice: FakeDevice? = null

    /** 一台完整、內部自洽的隨機裝置 */
    private class FakeDevice {
        val deviceId: String
        val deviceFp: String
        val model: String
        val name: String
        val buildId: String
        val lifecycleId: String
        init {
            deviceId = randHex(16)
            deviceFp = randHex(13)
            val (m, n, b) = DEVICE_POOL[RND.nextInt(DEVICE_POOL.size)]
            model = m; name = n; buildId = b
            // 觀察到的格式：aos + 13位毫秒 + device_id前8碼 + 8碼隨機 hex
            val ms = (1700000000000L + (RND.nextLong() and 0x7FFFFFFFFL)).toString().padStart(13, '0').take(13)
            lifecycleId = "aos" + ms + deviceId.take(8) + randHex(8)
        }
        /** 只替換 Dalvik UA 裡的「機型 + Build id」，保留原本的 Android 版本字串 */
        fun dalvikUA(orig: String): String {
            // Dalvik/2.1.0 (Linux; U; Android 12; <MODEL> Build/<BUILD>)
            val re = Regex("(Android[^;]*;\\s*).*?(\\s*Build/)[^)]*")
            return if (re.containsMatchIn(orig))
                re.replace(orig) { m -> m.groupValues[1] + model + m.groupValues[2] + buildId }
            else "Dalvik/2.1.0 (Linux; U; Android 12; $model Build/$buildId)"
        }
        companion object {
            private const val HEXCH = "0123456789abcdef"
            fun randHex(n: Int) = buildString { repeat(n) { append(HEXCH[RND.nextInt(16)]) } }
        }
    }

    private fun nextDevice(): FakeDevice {
        if (RANDOM_PER_REQUEST) return FakeDevice()
        cachedDevice?.let { return it }
        synchronized(this) {
            cachedDevice?.let { return it }
            val d = FakeDevice(); cachedDevice = d; return d
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!isTargetApp(lpparam.packageName)) return
        try {
            log("Loaded app: ${lpparam.packageName}  (mode=${if (RANDOM_PER_REQUEST) "per-request" else "per-launch"})")
            hookRiskFlags(lpparam)      // 只歸零 root/代理/模擬器/除錯（與地區無關，保留）
            hookDeviceRotate(lpparam)   // ★每請求輪換裝置身分指紋（header + body）
            // ❌ 移除 hookSign（不再改 country）、移除地區 body 改寫 —— 地區一律原樣
        } catch (e: Throwable) {
            log("Error: ${e.message}")
        }
    }

    // 風控紅旗歸零：改機/越獄環境能出單的前提，非地區資訊，保留。
    private fun hookRiskFlags(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val du = "com.combosdk.support.base.utils.DeviceUtils"
        val ctx = "android.content.Context"
        hookConst(du, cl, "isRooted", 0, ctx)
        hookConst(du, cl, "isProxy", 0)
        hookConst(du, cl, "isEmulator", 0, ctx)
        hookConst(du, cl, "hasOpenDebugMode", 0, ctx)
        log("risk flags zeroed (root/proxy/emulator/debug) — region left untouched")
    }

    // ★核心：hook okhttp3.Request$Builder.build()，把已存在的裝置身分 header + body 欄位
    //   換成「本請求」的隨機裝置。只動已帶 x-rpc-device_* 的請求（自然只命中 miHoYo/combo 流量）。
    //   不碰 country/currency/language/age_gate → 地區完全原樣、createOrder 的 sign 也不受影響。
    private fun hookDeviceRotate(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        try {
            val builderClass = XposedHelpers.findClass("okhttp3.Request\$Builder", cl)
            XposedHelpers.findAndHookMethod(builderClass, "build", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val request = param.result ?: return
                        val hasIdHeader = header(request, H_DEVICE_ID) != null ||
                                          header(request, H_DEVICE_FP) != null
                        // body 也可能帶 device 欄位（getFp/report）
                        val oldJson = if (!hasIdHeader) readBody(request, cl) else null
                        val bodyHasDev = oldJson != null && BODY_ID_KEYS.plus(BODY_FP_KEYS)
                            .any { oldJson.contains("\"$it\"") }
                        if (!hasIdHeader && !bodyHasDev) return   // 非目標請求，放行

                        val dev = nextDevice()
                        val builder = request.javaClass.getMethod("newBuilder").invoke(request)

                        // 1) headers（只替換原本就存在的）
                        setHeaderIfPresent(request, builder, H_DEVICE_ID, dev.deviceId)
                        setHeaderIfPresent(request, builder, H_DEVICE_FP, dev.deviceFp)
                        setHeaderIfPresent(request, builder, H_MODEL,     dev.model)
                        setHeaderIfPresent(request, builder, H_NAME,      dev.name)
                        setHeaderIfPresent(request, builder, H_LIFECYCLE, dev.lifecycleId)
                        header(request, H_UA)?.let { ua ->
                            if (ua.startsWith("Dalvik") && ua.contains("Build/"))
                                setHeader(builder, H_UA, dev.dalvikUA(ua))
                        }

                        // 2) body device 欄位（若有），用同一台裝置，維持請求內自洽
                        val json: String? = oldJson ?: readBody(request, cl)
                        if (json != null) {
                            var nj: String = json
                            for (k in BODY_ID_KEYS)    nj = setFieldIfPresent(nj, k, dev.deviceId)
                            for (k in BODY_FP_KEYS)    nj = setFieldIfPresent(nj, k, dev.deviceFp)
                            for (k in BODY_MODEL_KEYS) nj = setFieldIfPresent(nj, k, dev.model)
                            for (k in BODY_NAME_KEYS)  nj = setFieldIfPresent(nj, k, dev.name)
                            if (nj != json) applyBody(request, builder, nj, cl)
                        }

                        param.result = builder.javaClass.getMethod("build").invoke(builder)
                        log("rotated device -> id=${dev.deviceId} fp=${dev.deviceFp} model=${dev.model}")
                    } catch (e: Throwable) {
                        log("rotate error: ${e.message}")
                    }
                }
            })
            log("device-rotate hook installed on Request\$Builder.build()")
        } catch (e: Throwable) {
            log("Failed to hook Request\$Builder.build(): ${e.message}")
        }
    }

    // ---- header 反射工具 ----
    private fun header(request: Any, name: String): String? = try {
        request.javaClass.getMethod("header", String::class.java).invoke(request, name) as? String
    } catch (e: Throwable) { null }

    private fun setHeader(builder: Any, name: String, value: String) {
        builder.javaClass.getMethod("header", String::class.java, String::class.java)
            .invoke(builder, name, value)
    }

    private fun setHeaderIfPresent(request: Any, builder: Any, name: String, value: String) {
        if (header(request, name) != null) setHeader(builder, name, value)
    }

    // 把改好的 JSON 塞回 builder（維持原 method 與 contentType）
    private fun applyBody(request: Any, builder: Any, newJson: String, cl: ClassLoader) {
        try {
            val requestBodyClass = XposedHelpers.findClass("okhttp3.RequestBody", cl)
            val mediaTypeClass   = XposedHelpers.findClass("okhttp3.MediaType", cl)
            val oldBody = findNoArgMethod(request.javaClass, "body")?.invoke(request)
            val mediaType = try { oldBody?.javaClass?.getMethod("contentType")?.invoke(oldBody) } catch (e: Throwable) { null }
            val newBody = createRequestBody(requestBodyClass, mediaTypeClass, mediaType, newJson) ?: return
            val httpMethod = findNoArgMethod(request.javaClass, "method")?.invoke(request) as? String ?: "POST"
            builder.javaClass.getMethod("method", String::class.java, requestBodyClass)
                .invoke(builder, httpMethod, newBody)
        } catch (e: Throwable) {
            log("applyBody error: ${e.message}")
        }
    }

    // 小工具：把指定類的方法直接替換成回傳固定值。
    private fun hookConst(cls: String, cl: ClassLoader, method: String, value: Any, vararg paramTypes: String) {
        try {
            val args = ArrayList<Any>()
            args.addAll(paramTypes)
            args.add(XC_MethodReplacement.returnConstant(value))
            XposedHelpers.findAndHookMethod(cls, cl, method, *args.toTypedArray())
            log("hooked $method -> $value")
        } catch (e: Throwable) {
            log("hook $method fail: ${e.message}")
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

    // "key":"anything" -> "key":"value"
    private fun setField(json: String, key: String, value: String): String {
        val re = Regex("(\"" + Regex.escape(key) + "\"\\s*:\\s*\")[^\"]*(\")")
        return re.replace(json) { m -> m.groupValues[1] + value + m.groupValues[2] }
    }
    private fun setFieldIfPresent(json: String, key: String, value: String): String =
        if (json.contains("\"$key\"")) setField(json, key, value) else json

    // ---- OkHttp 反射工具 ----
    private fun readBody(request: Any, cl: ClassLoader): String? {
        return try {
            val bodyMethod = findNoArgMethod(request.javaClass, "body") ?: return null
            val body = bodyMethod.invoke(request) ?: return null
            val bufferClass = XposedHelpers.findClass("okio.Buffer", cl)
            val bufferedSinkClass = XposedHelpers.findClass("okio.BufferedSink", cl)
            val buffer = bufferClass.getDeclaredConstructor().newInstance()
            body.javaClass.getMethod("writeTo", bufferedSinkClass).invoke(body, buffer)
            bufferClass.getMethod("readUtf8").invoke(buffer) as? String
        } catch (e: Throwable) { null }
    }

    private fun createRequestBody(requestBodyClass: Class<*>, mediaTypeClass: Class<*>, mediaType: Any?, json: String): Any? {
        try { return requestBodyClass.getMethod("create", mediaTypeClass, String::class.java).invoke(null, mediaType, json) } catch (e: Throwable) {}
        try { return requestBodyClass.getMethod("create", String::class.java, mediaTypeClass).invoke(null, json, mediaType) } catch (e: Throwable) {}
        try { return requestBodyClass.getMethod("create", mediaTypeClass, ByteArray::class.java).invoke(null, mediaType, json.toByteArray(Charsets.UTF_8)) } catch (e: Throwable) {}
        return null
    }

    private fun findNoArgMethod(clazz: Class<*>, name: String): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            try { val m = c.getDeclaredMethod(name); m.isAccessible = true; return m }
            catch (e: NoSuchMethodException) { c = c.superclass }
        }
        return null
    }

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
}
