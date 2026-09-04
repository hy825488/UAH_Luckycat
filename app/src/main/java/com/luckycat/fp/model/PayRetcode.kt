package com.luckycat.fp.model

/**
 * 【Model 層 · 付款回應碼對照】
 *
 * miHoYo luckycat 付款 retcode 對照(反編別人 sdkpatch 模組解出來的)。
 * 純資料,方便 log 判讀「被哪一種擋下」。
 */
object PayRetcode {
    fun describe(code: Int): String = when (code) {
        115 -> "tokenInvalidWhenPay(token 失效)"
        121 -> "purchaseCancel(取消)"
        122 -> "isAi/风控"
        127 -> "簽名錯誤(改了欄位卻沒重簽會出這個)"
        134 -> "payLimit4Japan(日本限制)"
        135 -> "isPayRisk/支付风控(彈窗:請求過於頻繁)"   // ← 我們最常卡的
        150 -> "ageGateForbidden(年齡限制)"
        else -> "unknown($code)"
    }
}
