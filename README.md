# Luckycat FP - LSPosed 設備指紋輪換模組

自動輪換米哈遊遊戲（原神、星鐵、鳴潮等）的 device_id 和 device_fp，用於規避設備指紋風控。

## 功能

- 每筆 `createOrder` 請求自動生成新的 UUID 格式 device_id 和隨機 device_fp
- 同一筆訂單的後續請求（如 listAppPayPlat、verify）沿用相同指紋
- 持久化儲存，應用重啟後保留指紋
- 僅修改 HTTP header，不觸及 body 和簽名

## 支持的域名

- `hk4e-sdk-os.hoyoverse.com` (Genshin Impact Global)
- `hkrpg-sdk-mihoyo.hoyoverse.com` (Honkai: Star Rail)
- `nap-sdk-mihoyo.hoyoverse.com` (Zenless Zone Zero)

## 前置條件

- Android 28+ (Pie 或更新)
- Magisk + LSPosed 已安裝
- 目標遊戲已安裝

## 安裝方法

### 方法 1：直接編譯（推薦）

1. Fork 或 clone 本倉庫
2. 修改任何需要的設置（見下方），或直接 push
3. GitHub Actions 自動編譯，進入 Actions 標籤找最新的 build artifact
4. 下載 `luckycat-fp.apk`

### 方法 2：本地編譯

```bash
git clone https://github.com/YOUR_USERNAME/lsposed-luckycat.git
cd lsposed-luckycat
./gradlew assembleRelease
# APK 在 app/build/outputs/apk/release/app-release.apk
```

### 安裝到設備

1. 在 LSPosed Manager 中點「安裝」或「Install from file」
2. 選擇 `luckycat-fp.apk`
3. 等待安裝完成
4. 勾選「啟用」
5. **重啟設備**

## 配置修改

編輯 `app/src/main/java/com/luckycat/fp/MainHook.kt`：

### 添加更多應用包名

在 `isTargetApp()` 方法中添加：
```kotlin
pkgName.contains("your_app", ignoreCase = true)
```

### 添加更多域名

在 `shouldIntercept()` 方法中添加：
```kotlin
url.contains("your.domain.com")
```

## 工作原理

1. **監聽 OkHttp3 請求**：Hook `okhttp3.Request$Builder.build()`
2. **檢查 URL**：如果匹配目標域名
3. **生成指紋**：
   - `createOrder` 請求 → 生成新的 UUID + FP，儲存
   - 其他請求 → 讀取已儲存的指紋，沒有才生成新的
4. **修改 header**：注入 `x-rpc-device_id` 和 `x-rpc-device_fp`
5. **發送請求**：完成修改後的請求

## 日誌

通過 Xposed 日誌查看：
```bash
adb logcat | grep "LuckycatFP"
```

## 免責聲明

本模組僅用於技術研究和教育目的。使用者應遵守當地法律和相關服務條款。作者不對濫用此工具造成的後果負責。

## License

MIT

# UAH_Luckycat
