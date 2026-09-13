# ConnectIt (Android)

Windows 版 ConnectIt 的 Android 對應端:用標準 **mDNS/DNS-SD**(`NsdManager`)廣播/搜尋裝置,
跟 Windows 端走同一套 TCP 交握協定與訊框協定,可以互相探索、互相連線、傳送檔案/資料夾;
另外「影片」頁可以搜尋 Windows 端開啟的影片伺服器,並用原生播放器(Media3/ExoPlayer)觀看,
功能對齊 Windows 觀看頁(排序、記住播放進度/音量/速度、自動播放下一部等)。

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
- **影片觀看**:Android 端目前只作為**觀看端**——搜尋到 Windows 端開的影片伺服器後,自己
  呼叫 `GET /manifest` 拿影片清單、`GET /thumbnail/{path}` 拿縮圖(Coil 載入),點下去用
  Media3(ExoPlayer)播放 `GET /media/{path}`(原生支援 HTTP Range,可以拖曳進度)。控制列整組
  自己刻(而不是用 PlayerView 內建的),對應 Windows 觀看頁 BuildWatchPageScript 的功能:
  排序(檔名/大小/修改時間、遞增遞減)、上一部/下一部、快轉倒轉 10 秒、播放速度、音量、
  自動播放下一部(含可取消的倒數提示)、記住播放進度/音量/速度(`PlaybackPreferences`,對應
  網頁版的 `localStorage`)、全螢幕(強制橫向 + 隱藏系統列)。
  **為什麼不用 WebView 直接開伺服器端網頁**:試過內嵌 WebView 直接開網頁(不用自己刻清單/
  播放器),但 WebView 內嵌在 Compose 的 `AndroidView` 裡播放 `<video>` 時有相容性問題——聲音
  正常但畫面全黑,在真機(Pixel 8)上重現過,連加上 `WebChromeClient`(含
  `onShowCustomView`/`onHideCustomView`)都無法解決。改用 ExoPlayer 搭配 `PlayerView` 的
  `surface_type="texture_view"`(見 `res/layout/view_video_player.xml`)才能正常顯示畫面——
  預設的 `SurfaceView` 會在系統視窗上打洞合成,跟 Compose 用 RenderNode 合成畫面的方式衝突。
  還沒有實作「從手機分享影片給別人看」的功能(Windows 端獨有)。

## 專案結構

| 位置 | 說明 |
| --- | --- |
| `net/` | 連線交握 JSON、訊框協定的編解碼(對應 Windows `ConnectMessage.cs`/`FileControlMessage.cs`/`ConnectionService.cs` 的訊框部分) |
| `discovery/NsdDiscoveryManager.kt` | 包住 `NsdManager` 的 mDNS 廣播/搜尋(對應 `MdnsDiscoveryService.cs`) |
| `connection/ConnectionEngine.kt` | TCP 交握、斷線偵測、檔案/資料夾傳輸狀態機(對應 `ConnectionService.cs`) |
| `service/ConnectItService.kt` | 前景服務,持有上述兩者、暴露 StateFlow 給 UI 觀察(對應「關閉視窗躲進系統匣但持續運作」的概念) |
| `repo/SettingsRepository.kt` | 裝置名稱、主題設定(DataStore Preferences) |
| `util/SafUtils.kt` | Storage Access Framework 輔助(查詢檔名/大小、遞迴列出資料夾內容) |
| `util/DownloadStorage.kt` | 接收檔案落地到公用 `Download/ConnectIt` 資料夾(API 29+ 用 MediaStore.Downloads,API 26-28 退回傳統檔案路徑) |
| `net/VideoManifestClient.kt` | 向影片伺服器要 `/manifest` 清單、組 `/media/`、`/thumbnail/` 的網址 |
| `repo/PlaybackPreferences.kt` | 記住播放進度/音量/速度/自動播放下一部設定(DataStore Preferences) |
| `ui/screens/VideoServerScreen.kt` | 影片清單頁(縮圖、排序選單) |
| `ui/screens/VideoPlayerScreen.kt` | 原生播放頁(Media3 ExoPlayer + TextureView + 自製控制列) |
| `ui/` | Jetpack Compose 畫面(裝置、已連線、影片、設定) |

## 跟 Windows 端的差異(刻意簡化之處)

- **搜尋秒數設定**沒有對應項目:Windows 端要定期重送 mDNS 查詢是 Makaretu.Dns 函式庫的保險機制,
  Android 的 `NsdManager` 本身就是持續推播 `onServiceFound`/`onServiceLost`,不需要手動重複查詢。
- Android 端不提供「影片伺服器」(分享手機裡的影片給別人看)功能,只能搜尋/觀看別人開的。

## 設定頁

除了裝置名稱/主題之外,設定頁(`ui/screens/SettingsScreen.kt`,對應資料存在
`repo/SettingsRepository.kt`)還提供:

- **通知開關**:關閉後,收到檔案/資料夾、對方斷線時不再跳系統通知(連線請求一律會通知,
  因為那是唯一能提醒使用者去開 App 處理的管道)。
- **信任的裝置**:收到連線請求時可以勾選「信任此裝置」,之後同名裝置的連線請求會自動接受、
  不再跳確認對話框;也可以在設定頁移除已信任的裝置。
- **接收檔案儲存位置**:預設仍是公用的 `Download/ConnectIt` 資料夾,可以改用 SAF
  (Storage Access Framework)選任意資料夾(見 `util/DownloadStorage.kt` 的 `createViaSafTree`)。
- **連線設定**:監聽連接埠(留空 = 系統自動指派;變更需要重啟 App 才會生效)與主動連線的逾時秒數。

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
