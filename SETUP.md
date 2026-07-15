# 項目初始化步驟

## 第一次使用

### 方式 1：直接從 GitHub 編譯（推薦）

1. Fork 本倉庫到你的帳號
2. 進入倉庫設置，確保 Actions 已啟用
3. 做任何修改並 push 到 main 分支
4. GitHub Actions 會自動編譯
5. 在 Actions 標籤找到最新的 workflow run，下載 artifact

### 方式 2：本地初始化編譯

如果你要在本地編譯，需要先初始化 Gradle Wrapper：

```bash
# 進入項目目錄
cd lsposed-luckycat

# 初始化 gradle wrapper (需要 Java 11+)
gradle wrapper --gradle-version=7.6.2

# 現在應該生成了 gradlew 和 gradle/wrapper/ 目錄
# 編譯
./gradlew assembleRelease
```

生成的 APK 在：`app/build/outputs/apk/release/app-release.apk`

## GitHub 構建注意事項

- 第一次 push 時，GitHub Actions 會自動下載 gradle wrapper
- **不需要**手動提交 gradle wrapper 或 .gradle/ 目錄（已在 .gitignore）
- Actions 會在 Ubuntu 上自動執行，編譯完全自動化

## 本地開發

### 編輯 Hook 邏輯

修改 `app/src/main/java/com/luckycat/fp/MainHook.kt`

常見改動：
- **添加應用**：編輯 `isTargetApp()` 方法
- **添加域名**：編輯 `shouldIntercept()` 方法
- **修改指紋生成**：編輯 `newDev()` 和 `rh()` 方法

### 本地測試

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 查看日誌
adb logcat | grep "LuckycatFP"
```

## 常見問題

### Q: 編譯失敗，提示找不到 OkHttp3

A: 這是正常的。OkHttp3 是 `compileOnly` 依賴，只在編譯時檢查。實際運行時由應用自己提供。

### Q: 怎麼確保編譯成功？

A: 查看 GitHub Actions 的 build.yml 日誌。如果最後看到 "Upload APK as artifact"，說明編譯成功。

### Q: 需要簽名嗎？

A: 需要。build.gradle.kts 已配置調試簽名。GitHub Actions 使用默認的 debug keystore 簽名。

如果要自定義簽名，修改 `app/build.gradle.kts` 的 `signingConfigs` 部分。

## 項目結構確認

確保你的本地結構如下：

```
lsposed-luckycat/
├── .github/workflows/build.yml
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── SETUP.md
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/xposed_init
        ├── java/com/luckycat/fp/
        │   └── MainHook.kt
        └── res/values/
            └── strings.xml
```

如果缺少任何文件，複製本倉庫中的對應文件。
