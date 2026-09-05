package com.luckycat.fp.model

/**
 * 【Model 層 · 常數】
 *
 * 所有「反編譯得到的 class / method 名稱」與「目標套件白名單」集中放這一個檔。
 * 遊戲改版時,只要對照這裡就能知道要改哪些名字。純資料,無邏輯。
 */
object Constants {
    // ── createOrder 改參數 + 重簽(核心)──
    /** 付款參數組裝點:回傳 createOrder 的 Map,在 sign 計算「之前」→ 我們攔這裡改欄位再重簽。 */
    const val PAY_MODEL     = "com.mihoyoos.sdk.platform.module.pay.PayModel"
    /** SDK 自己的簽章工具:generateSign(order) → 用它重簽,天生有效。 */
    const val HTTP_COMPLETE = "com.mihoyoos.sdk.platform.common.utils.HttpCompleteUtils"
    /** 讀「帳號的國家」:getAccountInfo4Pay().getCountry() → 對齊 order.country。 */
    const val ACCOUNT_UTILS = "com.mihoyoos.sdk.platform.common.utils.AccountUtils"

    // ── device 身分源頭 ──
    const val SDK_INFO    = "com.mihoyo.combo.info.SDKInfo"                                   // deviceId / getClientType
    const val PORTE_INFO  = "com.mihoyo.platform.account.oversea.sdk.PorteOSInfo"             // 帳號登入層 getClientType
    const val GAME_CONFIG = "com.mihoyoos.sdk.platform.config.GameConfig"                     // getDeviceId
    const val ABS_UID     = "com.mihoyo.platform.sdk.devicefp.AbstractDeviceUniqueIdentifier" // obtain → device_fp
    const val COMBO_DU    = "com.combosdk.support.base.utils.DeviceUtils"                     // getDeviceID / 藏模擬器
    const val PORTE_DU    = "com.mihoyo.platform.account.oversea.sdk.internal.shared.utils.DeviceUtils"
    const val XDEV_U      = "com.mihoyo.platform.utilities.XDeviceUtils"                     // android_id / isEmulator 底層

    // ── getFp 上報特徵(整包洗成真機的單一咽喉點)──
    const val CRP         = "com.mihoyo.platform.sdk.devicefp.CommonRequiredParams"

    // ── Android 系統類(藏模擬器 / Toast)──
    const val SYS_PROP        = "android.os.SystemProperties"
    const val SETTINGS_SECURE = "android.provider.Settings\$Secure"
    const val APPLICATION     = "android.app.Application"

    /** 作用的 7 個確切套件(精確白名單,避免誤傷含關鍵字的其他 App)。 */
    val TARGETS = setOf(
        "com.miHoYo.GenshinImpact", "com.miHoYo.ys.mihoyo", "com.miHoYo.Yuanshen", // 原神
        "com.HoYoverse.hkrpgoversea", "com.miHoYo.hkrpg",                          // 星穹鐵道
        "com.HoYoverse.Nap", "com.miHoYo.Nap"                                      // 絕區零
    )
}
