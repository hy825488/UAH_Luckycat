package com.luckycat.fp.service

import android.os.Build
import com.luckycat.fp.model.FakeIdentity
import com.luckycat.fp.util.Xp
import de.robv.android.xposed.XposedHelpers

/**
 * 【Service 層 · Build 偽裝】
 *
 * 把 android.os.Build 的靜態欄位換成假身分那台真機。
 * 目的:讓遊戲本體 / telemetry / astrolabe / isEmulator 的字串比對「全域」看到同一台真機。
 * 一次性設定靜態欄位,無執行期開銷(不像 hook 方法)。
 */
class BuildSpoofService(private val id: FakeIdentity) {

    fun install() {
        val p = id.profile
        set("MANUFACTURER", p.manufacturer); set("BRAND", p.brand); set("MODEL", p.model)
        set("DEVICE", p.device); set("PRODUCT", p.product); set("BOARD", p.board)
        set("HARDWARE", p.hardware); set("FINGERPRINT", p.fingerprint); set("DISPLAY", p.display)
        set("HOST", "build-host"); set("USER", "dpi"); set("TAGS", "release-keys")
        set("TYPE", "user"); set("SERIAL", id.serial); set("ID", p.display); set("CPU_ABI", "arm64-v8a")
        // 只支援 arm64(遮掉模擬器的 x86 ABI)
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "SUPPORTED_ABIS",
                arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        }
        // Android 版本對齊機檔(fingerprint 內也有這個版本,要一致)
        runCatching { XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", p.release) }
        Xp.log("[Build] -> ${p.brand}/${p.model} (${p.soc})")
    }

    private fun set(field: String, value: String) =
        runCatching { XposedHelpers.setStaticObjectField(Build::class.java, field, value) }
}
