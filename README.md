# ConnectIt (Android)

Windows 版 ConnectIt 的 Android 對應端:用標準 **mDNS/DNS-SD**(`NsdManager`)廣播/搜尋裝置,
跟 Windows 端走同一套 TCP 交握協定與訊框協定,可以互相探索、互相連線、傳送檔案/資料夾;
另外「影片」頁可以搜尋其他裝置開啟的影片伺服器,並用原生播放器(Media3/ExoPlayer)觀看,
功能對齊 Windows 觀看頁(排序、記住播放進度/音量/速度、自動播放下一部等);也可以把手機裡的
影片資料夾分享出去當影片伺服器,並用遙控模式控制所有觀看端同步播放。

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
- **影片觀看**:搜尋到其他裝置開的影片伺服器後,自己
  呼叫 `GET /manifest` 拿影片清單、`GET /thumbnail/{path}` 拿縮圖(Coil 載入),點下去用
  Media3(ExoPlayer)播放 `GET /media/{path}`(原生支援 HTTP Range,可以拖曳進度)。控制列整組
  自己刻(而不是用 PlayerView 內建的),對應 Windows 觀看頁 BuildWatchPageScript 的功能:
  排序(檔名/大小/修改時間、遞增遞減)、上一部/下一部、快轉倒轉 10 秒、播放速度、音量、
  自動播放下一部(播完直接接下一部)、記住播放進度/音量/速度(`PlaybackPreferences`,對應
  網頁版的 `localStorage`)、全螢幕(強制橫向 + 隱藏系統列)。
  **為什麼不用 WebView 直接開伺服器端網頁**:試過內嵌 WebView 直接開網頁(不用自己刻清單/
  播放器),但 WebView 內嵌在 Compose 的 `AndroidView` 裡播放 `<video>` 時有相容性問題——聲音
  正常但畫面全黑,在真機(Pixel 8)上重現過,連加上 `WebChromeClient`(含
  `onShowCustomView`/`onHideCustomView`)都無法解決。改用 ExoPlayer 搭配 `PlayerView` 的
  `surface_type="texture_view"`(見 `res/layout/view_video_player.xml`)才能正常顯示畫面——
  預設的 `SurfaceView` 會在系統視窗上打洞合成,跟 Compose 用 RenderNode 合成畫面的方式衝突。
- **影片伺服器(主機端)**:`video/VideoHostServer.kt` 用 SAF 選的資料夾提供跟 Windows 端
  `VideoStreamingService.cs` 同一組 HTTP 端點(`/`、`/watch`、`/manifest`、`/media/`、`/thumbnail/`、
  `/control/state`、`/control/events`),網頁由 `video/VideoHostPages.kt` 產生,HTML/CSS/JS 刻意跟
  Windows 端幾乎逐字一致,包含排序、資料夾瀏覽、隨機播放(網址帶 `shuffle=1&seed=...`)。
- **遙控模式**:主機端遙控畫面(`ui/screens/VideoHostControlScreen.kt`,TV 版
  `ui/tv/TvVideoHostControlScreen.kt`)把指令寫進 `video/PlaybackControlState.kt`(碼表模型),
  觀看端透過 `/control/events` 同步。遙控畫面有自己的排序/隨機播放/自動播放下一部設定,
  自動播放下一部由 `ConnectItService` 負責,離開遙控畫面也照樣會接下一部。

- **螢幕鏡像**:跟檔案傳輸共用同一條配對連線,多兩種訊框型別(2 = 控制訊息 JSON、3 = `[序號][JPEG]`
  畫面),協定說明在 `net/ScreenMessages.kt` 開頭,跟 Windows 端 `ScreenMessage.cs` 逐欄位對應。
  分享端用 MediaProjection → VirtualDisplay → ImageReader 擷取(長邊縮到 1280),要送下一張時才取
  「最新」一張壓縮,觀看端每解完一張回 ack、最多 4 張未確認,延遲不會越積越多。
  **遠端操控**靠無障礙服務(`screen/ScreenControlAccessibilityService.kt`):Android 沒有給一般 App 注入
  觸控的 API,只能用 `dispatchGesture` 重播手勢、`performGlobalAction` 返回/主畫面、對輸入框
  `ACTION_SET_TEXT` 打字。滑動/拖曳是即時的:用 `StrokeDescription.continueStroke` 一段一段接續送出,
  每段完成就把期間累積的移動接成下一段;很快放開的點擊則整筆一次送出(最可靠)。
  使用者必須在分享時勾選「允許對方操控」**而且**到系統無障礙設定開啟這個服務,兩者缺一就只能觀看;
  缺服務時 App 會主動跳出說明並直接帶到這個服務的設定頁。Android 13 起側載安裝(非商店)的 App
  開關會是灰的「受限制的設定」,說明裡也有「應用程式資訊 → ⋮ → 允許受限制的設定」的捷徑。

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
| `video/VideoHostServer.kt`、`video/VideoHostPages.kt` | 影片伺服器主機端:HTTP 端點與網頁(對應 `VideoStreamingService.cs`) |
| `video/PlaybackControlState.kt` | 遙控模式的播放狀態(對應 Windows 端同名類別) |
| `ui/screens/VideoHostControlScreen.kt` | 主機端遙控畫面(選片、播放控制、排序/隨機播放/自動播放下一部) |
| `screen/` | 螢幕鏡像:狀態機(`ScreenMirrorController`)、MediaProjection 擷取、畫面解碼、無障礙服務與輸入轉換 |
| `ui/screens/ScreenViewerScreen.kt` | 觀看對方螢幕(全螢幕)。對方是電腦時預設「觸控板模式」:單指移動游標、點=左鍵、兩指點=右鍵、兩指滑=捲動、長按後拖曳;可切成「直接點按模式」 |
| `ui/` | Jetpack Compose 畫面(裝置、已連線、影片、設定) |

## 介面與操作流程

- **Aurora 品牌介面**:跟 Windows 端同一組靛藍 → 青色色票(`ui/theme/`),共用元件在 `ui/components/`
  (漸層按鈕、Hero 卡片、雷達搜尋動畫、設定分組列、影片卡片等);Android TV 版(`ui/tv/`)也套用同一組色票,
  卡片取得焦點時會有品牌漸層描邊與放大效果。
- **首次啟動引導**:歡迎 → 替裝置取名 → 怎麼使用(並在這一步詢問通知權限);設定頁可以重新顯示。
- **裝置頁**:「這台裝置」可以點筆形圖示直接改名;已連線時「目前連線」卡片出現在裝置清單上方
  (平板/折疊機攤開時在右側),包含傳送檔案/資料夾、傳輸進度、接收完成後「開啟檔案」、這次連線的傳輸紀錄。
- **連線流程**:按下連線後顯示可取消的「等待對方接受」對話框;被拒絕、沒回應、連不到時分別說明原因。
- **傳輸進度與通知**:傳輸卡片顯示百分比、傳輸速度與預計剩餘時間;通知列有即時進度條,完成或失敗時跳出通知。
- **螢幕鏡像**:「目前連線」卡片可以「觀看對方螢幕」或「分享我的螢幕」,對方同意後開始;分享時系統會
  再詢問一次是否允許擷取螢幕。觀看時可以隱藏工具列、叫出鍵盤打字(支援注音等輸入法)。TV 版可以分享與觀看,
  但觀看時只能看不能操控(沒有觸控)。
- **異常斷線偵測**:連線期間雙方每 4 秒互送心跳(跟 Windows 端同一套),對方沒有正常關閉連線就消失時,約 15 秒內就會判定斷線並通知。
- **從其他 App 分享**:在相簿或檔案管理員按「分享」→ ConnectIt,選一台裝置就會傳送(還沒連線的話會先送出連線請求,對方接受後自動開始傳送)。
- **影片頁**:分成「觀看」(含「繼續觀看」,記住最近看到一半的影片,伺服器重新上線時直接從上次的位置繼續)
  與「分享」(顯示網址與 QR Code,沒有安裝 App 的裝置用瀏覽器就能觀看)兩個分頁。
- **設定頁**:所有變更即時自動儲存(沒有「儲存」按鈕),數字欄位停止輸入後自動驗證,不合法時直接顯示原因。

## 跟 Windows 端的差異(刻意簡化之處)

- **搜尋秒數設定**沒有對應項目:Windows 端要定期重送 mDNS 查詢是 Makaretu.Dns 函式庫的保險機制,
  Android 的 `NsdManager` 本身就是持續推播 `onServiceFound`/`onServiceLost`,不需要手動重複查詢。

## 設定頁

除了裝置名稱/主題之外,設定頁(`ui/screens/SettingsScreen.kt`,對應資料存在
`repo/SettingsRepository.kt`,輸入驗證在 `repo/SettingsValidation.kt`)還提供:

- **通知開關**:關閉後,收到檔案/資料夾、對方斷線時不再跳系統通知(連線請求一律會通知,
  因為那是唯一能提醒使用者去開 App 處理的管道)。
- **信任的裝置**:收到連線請求時可以勾選「信任此裝置」,之後同名裝置的連線請求會自動接受、
  不再跳確認對話框;也可以在設定頁移除已信任的裝置。
- **接收檔案儲存位置**:預設仍是公用的 `Download/ConnectIt` 資料夾,可以改用 SAF
  (Storage Access Framework)選任意資料夾(見 `util/DownloadStorage.kt` 的 `createViaSafTree`)。
- **進階**(預設收合):監聽連接埠(留空 = 系統自動指派;變更需要重啟 App 才會生效)與主動連線的逾時秒數。
- **影片伺服器**:對應 Windows 端 `VideoServerSettingsService`——下次按「選擇資料夾開始分享」時
  套用的預設值:清單排序方式、自動播放下一部、隨機播放、預設音量/速度,以及掃描資料夾時
  額外要當作影片的副檔名(內建清單以外)。只影響下一次開始分享套用的初始值,不會在伺服器執行
  中即時生效。遙控畫面的排序/隨機播放/自動播放下一部也以這些值為初始值,但在遙控畫面上的調整
  只影響當次分享,不會寫回設定。

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
