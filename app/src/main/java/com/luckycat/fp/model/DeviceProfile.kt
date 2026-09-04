package com.luckycat.fp.model

/**
 * 【Model 層 · 裝置檔】
 *
 * 一台「成套真旗艦機」的完整硬體特徵。
 * ⚠️ 每個欄位必須互相對得起來(brand / board / model / fingerprint 同一台),不能東拼西湊,
 *     否則風控做交叉比對會露餡。純資料,無邏輯。
 */
data class DeviceProfile(
    val manufacturer: String,  // Build.MANUFACTURER
    val brand: String,         // Build.BRAND
    val model: String,         // Build.MODEL
    val device: String,        // Build.DEVICE
    val product: String,       // Build.PRODUCT
    val board: String,         // Build.BOARD
    val hardware: String,      // Build.HARDWARE
    val fingerprint: String,   // Build.FINGERPRINT(內含 Android 版本,要對齊 release)
    val display: String,       // Build.DISPLAY / Build.ID
    val release: String,       // Android 版本(Build.VERSION.RELEASE)
    val soc: String,           // ro.soc.model
    val chip: String           // CPU 型號字串(藏 redroid / x86)
) {
    companion object {
        /**
         * 內建機檔池:Android 12 / 高通 SM8350,對齊 MuMuPlayer 12(Android 12)。
         * 每次冷啟隨機抽一台(見 FakeIdentity)。要加機型就往這裡加,欄位務必成套。
         */
        val POOL = listOf(
            // 三星 Galaxy S21 5G(SM-G991U)
            DeviceProfile(
                "samsung", "samsung", "SM-G991U", "o1q", "o1qsqw", "lahaina", "qcom",
                "samsung/o1qsqw/o1q:12/SP1A.210812.016/G991USQU5CVK3:user/release-keys",
                "SP1A.210812.016", "12", "SM8350", "Qualcomm Technologies, Inc. SM8350"
            ),
            // 小米 11(M2011K2G)
            DeviceProfile(
                "Xiaomi", "Xiaomi", "M2011K2G", "venus", "venus", "venus", "qcom",
                "Xiaomi/venus/venus:12/SKQ1.211006.001/V13.0.10.0.SKBEUXM:user/release-keys",
                "SKQ1.211006.001", "12", "SM8350", "Qualcomm Technologies, Inc. SM8350"
            ),
            // 一加 9(LE2113)
            DeviceProfile(
                "OnePlus", "OnePlus", "LE2113", "OnePlus9", "OnePlus9EEA", "lahaina", "qcom",
                "OnePlus/OnePlus9EEA/OnePlus9:12/RKQ1.211119.001/R.202203301911:user/release-keys",
                "RKQ1.211119.001", "12", "SM8350", "Qualcomm Technologies, Inc. SM8350"
            )
        )
    }
}
