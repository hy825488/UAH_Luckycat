package com.luckycat.fp

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.*

class MainHook : IXposedHookLoadPackage {
    companion object {
        const val TAG = "LuckycatFP"
        const val STORE_NAME = "luckycat_dev"
        const val KEY_DEV = "dev"
    }

    private var prefs: SharedPreferences? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只 Hook 米哈遊相關應用
        if (!isTargetApp(lpparam.packageName)) {
            return
        }

        try {
            prefs = lpparam.appContext?.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            log("Loaded app: ${lpparam.packageName}")
            hookOkHttp(lpparam)
        } catch (e: Exception) {
            log("Error: ${e.message}")
            e.printStackTrace()
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
        try {
            val requestBuilderClass = XposedHelpers.findClass(
                "okhttp3.Request\$Builder",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                requestBuilderClass,
                "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val request = param.result
                            val urlField = XposedHelpers.findField(
                                request.javaClass.superclass,
                                "url"
                            )
                            val httpUrl = urlField.get(request)
                            val urlString = httpUrl.toString()

                            if (shouldIntercept(urlString)) {
                                val (deviceId, deviceFp) = getOrCreateDev(urlString)
                                log("Intercepting: $urlString")
                                log("Device ID: $deviceId | FP: $deviceFp")

                                val newBuilder = request.javaClass.getMethod(
                                    "newBuilder"
                                ).invoke(request)

                                // 設置 header
                                newBuilder.javaClass.getMethod(
                                    "header",
                                    String::class.java,
                                    String::class.java
                                ).invoke(newBuilder, "x-rpc-device_id", deviceId)

                                newBuilder.javaClass.getMethod(
                                    "header",
                                    String::class.java,
                                    String::class.java
                                ).invoke(newBuilder, "x-rpc-device_fp", deviceFp)

                                val newRequest = newBuilder.javaClass.getMethod(
                                    "build"
                                ).invoke(newBuilder)

                                param.result = newRequest
                            }
                        } catch (e: Exception) {
                            log("Hook error: ${e.message}")
                        }
                    }
                }
            )
            log("OkHttp3 hook installed successfully")
        } catch (e: Exception) {
            log("Failed to hook OkHttp3: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun shouldIntercept(url: String): Boolean {
        return url.contains("hk4e-sdk-os.hoyoverse.com") ||
                url.contains("hkrpg-sdk-mihoyo.hoyoverse.com") ||
                url.contains("nap-sdk-mihoyo.hoyoverse.com")
    }

    private fun getOrCreateDev(url: String): Pair<String, String> {
        val storedDev = if (url.contains("createOrder")) {
            // 每次 createOrder 生成新的設備信息
            val newDev = newDev()
            prefs?.edit()?.putString(KEY_DEV, newDev)?.apply()
            log("Generated new device for createOrder")
            newDev
        } else {
            // 其他請求沿用之前的
            prefs?.getString(KEY_DEV, null) ?: newDev().also {
                prefs?.edit()?.putString(KEY_DEV, it)?.apply()
            }
        }

        val parts = storedDev.split("|")
        val id = parts.getOrNull(0) ?: ""
        val fp = parts.getOrNull(1) ?: ""
        return id to fp
    }

    private fun rh(n: Int, upper: Boolean): String {
        val charset = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        return (0 until n).map { 
            charset[Random().nextInt(charset.length)] 
        }.joinToString("")
    }

    private fun newDev(): String {
        val uuid = "${rh(8, true)}-${rh(4, true)}-${rh(4, true)}-${rh(4, true)}-${rh(12, true)}"
        val fp = rh(13, false)
        return "$uuid|$fp"
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }
}
