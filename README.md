# ConnectIt (Android)

Windows 版 ConnectIt 的 Android 對應端:用標準 **mDNS/DNS-SD**(`NsdManager`)廣播/搜尋裝置,
跟 Windows 端走同一套 TCP 交握協定與訊框協定,可以互相探索、互相連線、傳送檔案/資料夾;
另外「影片」頁可以搜尋 Windows 端開啟的影片伺服器,並用內嵌瀏覽器(WebView)觀看。

## 跟 Windows 端的協定對應

- **裝置探索**:`NsdManager` 註冊/搜尋 `_connectit._tcp.`(裝置配對)與 `_connectit-video._tcp.`
  (影片伺服器)兩個 DNS-SD 服務類型,跟 Windows 端 `MdnsDiscoveryService` 用的完全一樣,
  TXT 記錄的 `name` 欄位是顯示用的裝置名稱。
- **連線交握**:TCP 連線後送出一行 JSON(`{"Type":"request","DeviceName":"..."}`),對方回
  `{"Type":"accept"}` / `{"Type":"reject"}`。JSON 欄位刻意維持與 Windows 端 `System.Text.Json`
  預設序列化一致的 PascalCase 命名(`Type`、`TransferId`…),因為兩邊都沒有放寬大小寫比對。
- **檔案/資料夾傳輸**:連線建立後,同一個 socket 改用長度前綴的二進位訊框協定(控制訊息 JSON
  / 檔案內容區塊),與 Windows 端 `ConnectionService.cs` 逐行對應,詳見
  `app/src/main/java/com/connectit/android/connection/ConnectionEngine.kt` 開頭的註解。
- **影片觀看**:Android 端目前只作為**觀看端**——搜尋到 Windows 端開的影片伺服器後,直接用
  `WebView` 開啟對方提供的網站(首頁清單、觀看頁、播放器都是伺服器端的網頁)。還沒有實作「從
  手機分享影片給別人看」的功能(Windows 端獨有)。

## 專案結構

| 位置 | 說明 |
| --- | --- |
| `net/` | 連線交握 JSON、訊框協定的編解碼(對應 Windows `ConnectMessage.cs`/`FileControlMessage.cs`/`ConnectionService.cs` 的訊框部分) |
| `discovery/NsdDiscoveryManager.kt` | 包住 `NsdManager` 的 mDNS 廣播/搜尋(對應 `MdnsDiscoveryService.cs`) |
| `connection/ConnectionEngine.kt` | TCP 交握、斷線偵測、檔案/資料夾傳輸狀態機(對應 `ConnectionService.cs`) |
| `service/ConnectItService.kt` | 前景服務,持有上述兩者、暴露 StateFlow 給 UI 觀察(對應「關閉視窗躲進系統匣但持續運作」的概念) |
| `repo/SettingsRepository.kt` | 裝置名稱、主題設定(DataStore Preferences) |
| `util/SafUtils.kt` | Storage Access Framework 輔助(查詢檔名/大小、遞迴列出資料夾內容) |
| `ui/` | Jetpack Compose 畫面(裝置、已連線、影片、觀看網頁、設定) |

## 跟 Windows 端的差異(刻意簡化之處)

- **接收檔案的儲存位置**固定在應用程式專屬的外部儲存資料夾
  (`/Android/data/com.connectit.android/files/Download`),不像 Windows 端可以在設定頁選任意資料夾。
  Android 的 scoped storage 底下,選任意資料夾需要 SAF 樹狀 Uri 授權,複雜度不成比例,先簡化掉。
- **搜尋秒數設定**沒有對應項目:Windows 端要定期重送 mDNS 查詢是 Makaretu.Dns 函式庫的保險機制,
  Android 的 `NsdManager` 本身就是持續推播 `onServiceFound`/`onServiceLost`,不需要手動重複查詢。
- Android 端不提供「影片伺服器」(分享手機裡的影片給別人看)功能,只能搜尋/觀看別人開的。

## 建置與執行

需要 Android Studio(或指令列的 Android SDK + JDK 17)。

```powershell
# 在 Android/ 目錄下
./gradlew assembleDebug

# 或直接用 Android Studio 開啟這個資料夾,執行 app 這個 run configuration
```

實際測試裝置探索/配對/檔案傳輸,兩端(手機 + 執行 Windows 版的電腦)必須在**同一個區網、
同一個 Wi-Fi**;Android 模擬器預設的網路是 NAT 過的虛擬網路,不會跟主機所在的真實區網互通
multicast,無法用模擬器測到跟 Windows 端的 mDNS 互相發現,請用實體手機測試。
