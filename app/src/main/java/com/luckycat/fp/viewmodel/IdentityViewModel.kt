package com.luckycat.fp.viewmodel

import com.luckycat.fp.model.FakeIdentity

/**
 * 【ViewModel 層 · 身分狀態持有】
 *
 * MVVM 的核心:持有本次 session 的「假身分」狀態(FakeIdentity),供 Service(hook 執行層)
 * 與 View(Toast)讀取。
 *
 * 為什麼要這一層:把「身分怎麼產生 / 什麼時候換」跟「誰用它」解耦——
 *   Service 只管 hook、View 只管顯示,身分來源集中在這。
 *
 * 目前策略:每個進程(= 每次冷啟遊戲)產生一組,session 內固定(by lazy)。
 * 若日後要改成「每帳號固定 / 持久化到檔案 / 從 persist 屬性讀」,只要改這裡的產生策略,
 * Service 與 View 完全不用動——這就是分層的好處。
 */
class IdentityViewModel {
    /** 本次 session 的假身分:第一次存取時產生,之後固定不變(保證全鏈一致)。 */
    val identity: FakeIdentity by lazy { FakeIdentity() }
}
