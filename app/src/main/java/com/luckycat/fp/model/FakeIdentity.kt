package com.luckycat.fp.model

import java.security.SecureRandom

/**
 * 【Model 層 · 假身分】
 *
 * 本次冷啟(session)要對外偽裝的一整組身分:
 *   一台隨機真機檔(DeviceProfile)+ 隨機 device_id / device_fp / serial + 三軸感測器假值。
 *
 * 由 IdentityViewModel 產生並持有;所有 Service(hook 層)都從這裡讀值 → 保證全鏈一致
 * (header / body / token / lifecycle_id 都用同一個 device_id、同一個 device_fp)。
 * 純資料,一建立就固定不變。
 */
class FakeIdentity {
    /** 這次抽到的真機檔(Build 偽裝 / getFp ext / CPU 型號都用它)。 */
    val profile: DeviceProfile = DeviceProfile.POOL[RND.nextInt(DeviceProfile.POOL.size)]

    /** 假 device_id(16 hex):= x-rpc-device_id / order.device / android_id。 */
    val deviceId: String = randHex(16)

    /** 假 device_fp(13 hex):= x-rpc-device_fp(源頭在 AbstractDeviceUniqueIdentifier.obtain 捏)。 */
    val deviceFp: String = randHex(13)

    /** 假序號(Build.SERIAL / getFp serialNumber)。 */
    val serial: String = randHex(16).uppercase()

    // ── 三軸感測器假值(redroid / MuMu 常回空字串 = 最大破綻)──
    // 帶微抖動,避免每次都固定值被判定為造假。
    val accelerometer: String = sensor(0.12, 0.19, 9.79)
    val magnetometer: String = sensor(24.3, -7.8, 40.1)
    val gyroscope: String = sensor(0.001, -0.002, 0.0015)

    companion object {
        private val RND = SecureRandom()
        private const val HEX = "0123456789abcdef"

        /** 產生 n 位隨機 16 進位字串。 */
        fun randHex(n: Int) = buildString { repeat(n) { append(HEX[RND.nextInt(16)]) } }

        /** 在基準值上加 ±0.05 內的抖動。 */
        private fun jit(base: Double) = base + (RND.nextInt(1000) - 500) / 10000.0

        /** 組成「x軸x y軸x z軸」格式的感測器字串(用字元 x 分隔,對齊 SDK 讀出來的格式)。 */
        private fun sensor(x: Double, y: Double, z: Double) =
            "%.4fx%.4fx%.4f".format(jit(x), jit(y), jit(z))
    }
}
