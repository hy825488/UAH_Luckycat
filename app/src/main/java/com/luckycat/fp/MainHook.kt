package com.luckycat.fp

import com.luckycat.fp.model.Constants
import com.luckycat.fp.service.BuildSpoofService
import com.luckycat.fp.service.CreateOrderService
import com.luckycat.fp.service.CreateOrderFakeService
import com.luckycat.fp.service.DeviceSourceService
import com.luckycat.fp.service.EmulatorHideService
import com.luckycat.fp.service.ExtParamsService
import com.luckycat.fp.util.Xp
import com.luckycat.fp.view.StatusToastView
import com.luckycat.fp.viewmodel.IdentityViewModel
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ Luckycat — 原神 / 崩壞星穹鐵道 / 絕區零 代充過檢 LSPosed 模組(MVVM 版)  │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * 【進入點 · Entry / 組裝層】
 * LSPosed 的載入點(assets/xposed_init 指向這個類)。負責把 MVVM 各層接起來:
 *
 *   ViewModel  ──產生+持有──▶  假身分(FakeIdentity)
 *      │                              │
 *      ▼(讀同一組身分)                 │
 *   Service(hook 執行層) ────────────┘   ← 對「遊戲 App」安裝各種 hook
 *      │
 *      ▼
 *   View(StatusToastView) ── 開遊戲彈 Toast 顯示本次偽裝內容
 *
 * 目錄分層:
 *   model/     純資料      : Constants(反編類名) / DeviceProfile(機檔) / FakeIdentity(假身分) / PayRetcode
 *   viewmodel/ 狀態持有    : IdentityViewModel(產生並持有本次假身分)
 *   service/   hook 執行層 : Build 偽裝 / getFp ext 洗淨 / 藏模擬器 / device 源頭 / ★createOrder 重簽
 *   view/      呈現        : StatusToastView(Toast)
 *   util/      共用工具    : Xp(hook 樣板 + log)
 *
 * 功能等同 v14;此版為 MVVM 重構 + 完整中文註解,方便維護與改版。
 *
 * ⚠️ 插件只負責「裝置層」;要通過還需搭配(插件外):
 *    - 網路層:全局 VPN + 換系統 DNS(無 DNS/IPv6/WebRTC 洩漏)
 *    - 地理層:VPN 出口國 = 系統時區 = 語系 = 帳號區,全對齊
 *    - 帳號層:乾淨帳號;被支付風控(135)只能冷卻 ~15 分 + 換設備 + 換 IP
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只作用於三款目標遊戲,其餘 App 直接放行
        if (lpparam.packageName !in Constants.TARGETS) return
        val cl = lpparam.classLoader
        try {
            // 1) ViewModel:產生並持有本次 session 的假身分(全鏈一致的唯一來源)
            val vm = IdentityViewModel()
            val id = vm.identity
            Xp.log("Loaded ${lpparam.packageName} profile=${id.profile.brand}/${id.profile.model} id=${id.deviceId} fp=${id.deviceFp}")

            // 2) Service:各層 hook,全部從 ViewModel 讀同一組身分
            BuildSpoofService(id).install()             // Build.* 偽裝(全域一致)
            ExtParamsService(cl, id).install()          // getFp 上報特徵洗成真機 + 三軸感測器
            EmulatorHideService(cl, id).install()       // 藏模擬器 / root(不 hook File.exists)
            DeviceSourceService(cl, id).install()       // device_id / device_fp 源頭偽造
            CreateOrderService(cl, id).install()        // ★createOrder 改 country+device + 重簽
            CreateOrderFakeService(cl).install()        // ★★實驗:createOrder 回應直接假成 retcode:0

            // 3) View:開遊戲彈 Toast 顯示本次偽裝內容
            StatusToastView(cl, lpparam.packageName, id).install()
        } catch (e: Throwable) {
            Xp.log("handleLoadPackage error: ${e.message}")
        }
    }
}
