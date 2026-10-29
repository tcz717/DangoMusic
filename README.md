<p align="center"><img src="docs/banner.png" alt="Dango Music" width="640"></p>

# Dango Music

在 Minecraft 里听网易云音乐 / QQ 音乐 / 酷狗的 mod：液态玻璃播放器、漂浮在世界里的 3D 歌词、Song card、灵动岛，还能和朋友一起听歌。

*A Minecraft mod that brings NetEase Cloud Music, QQ Music and Kugou into the game: liquid-glass player, 3D in-world lyrics, song card HUD, Dynamic Island and Listen Together.*

<p align="center"><img src="docs/screenshots/lyrics-3d.jpg" alt="3D 歌词" width="100%"></p>

| | | |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.jpg" alt="首页"> | <img src="docs/screenshots/playlist.jpg" alt="歌单"> | <img src="docs/screenshots/coverflow.jpg" alt="封面墙"> |

## 功能

- **播放器**（默认按 `M`，主菜单也能打开）：推荐、排行榜、我的歌单、搜索，列表 / 封面墙两种视图
- **三个音源**：网易云 / QQ 音乐 / 酷狗，都在游戏内扫码登录（会员歌曲需要对应平台的会员）
- **歌词**：逐字歌词，3D 歌词漂浮在世界里，带发光、抖动、文闪等效果
- **Song card / 灵动岛**：屏幕上显示正在播放
- **一起听歌**：分享房间码，所有人同步到同一首歌、同一秒；国内网络也能用
- 设置都在播放器侧栏的 ⚙ 里，可以绑定快捷键

更新内容见 [1.6.0 更新日志](docs/CHANGELOG-1.6.0.md)。

## 安装

在 [Releases](https://github.com/Sorihiko/DangoMusic/releases) 下载对应版本的 jar，放进 `.minecraft/mods`，进游戏按 `M`。

| Minecraft | Fabric（另需 [Fabric API](https://modrinth.com/mod/fabric-api)） | Forge / NeoForge |
|---|---|---|
| 1.8.9 | — | `+1.8.9.jar`（Forge 11.15.1.2318，或 Lunar 等客户端） |
| 1.20.1 | `+1.20.1-fabric.jar` | `+1.20.1-forge.jar`（Forge 47.x） |
| 1.21.1 | `+1.21.1-fabric.jar` | `+1.21.1-neoforge.jar` |
| 1.21.11 | `+1.21.11-fabric.jar` | `+1.21.11-neoforge.jar` |
| 26.2 | `+26.2-fabric.jar` | `+26.2-neoforge.jar` |
| 26.3 | `+26.3-fabric.jar` | `+26.3-neoforge.jar` |

26.x 的歌词和播放器界面需要 OpenGL（选 Vulkan 时只能放音乐）。配置和登录信息存在 `.minecraft/config/dangomusic/`。

## 构建

需要 JDK 8、21、25，运行 `build-all.bat`，jar 收集到 `dist/`（JDK 不在默认位置时设置 `DANGO_JDK21` / `DANGO_JDK25`）。

### Apple Music 外部歌词同步 / external lyrics (Windows)

这是**可选、只读**功能，默认关闭。需要 Windows 10 1809 或更新版本、x64 JVM，以及提供 Windows 系统媒体会话（SMTC）的 Windows Apple Music 应用。
使用本 fork 的 **Apple Music Windows bridge** Actions 工作流产物中的 mod jar；其中包含从本仓库源码构建的桥接程序，无需自行运行脚本。
普通、未打包桥接程序的 jar 仍可正常加载，但不能连接 Apple Music。

1. 在 Apple Music 中播放歌曲。在 Minecraft 按 `M` → ⚙ → **音乐与 HUD** → **播放器**，打开 **外部播放器 / Apple Music**。
2. 在侧栏选择歌词来源（网易云 / QQ / 酷狗）。匹配仅使用所选来源的搜索和歌词接口，不获取播放地址或下载音频。
3. 确保 **歌词 → 3D 歌词** 开启；可以同时打开 **正在播放 / 封面**。封面来自可靠匹配的歌词曲库，不是 Apple 音频。
4. **歌词偏移（毫秒）**会自动保存，对两种播放器均生效：正值提前显示歌词，负值延后（`歌词时间 = 播放时间 + 偏移`，限制在歌曲范围内）。例如歌词晚了 500ms，设为 `+500`。
5. 播放、暂停、拖动、切歌和音量请在 Apple Music 中操作。开启外部模式会停止内置音频并锁住内置播放控制；先退出一起听歌房间才能开启。关闭外部模式不会自动恢复内置播放。

**English:** Enable **External Player / Apple Music** under `M → Settings → Music & HUD → Player`.
Use the mod jar from this fork's **Apple Music Windows bridge** workflow artifact: it bundles the source-built helper and extracts it lazily when enabled.
Windows 10 1809+, Windows x64 Java, and the Windows Apple Music application's SMTC session are required.
Other operating systems load normally and display an unsupported status; no Windows library is loaded at startup.
Choose NetEase, QQ or Kugou in the sidebar for lyric lookup. Enable **3D lyrics**, and optionally **Now playing / Cover**, in Settings.
Control playback in Apple Music, not Minecraft. Internal audio stops, internal transport/queue playback is blocked, and Listen Together is unavailable while external mode is active.
The signed **Lyric offset (ms)** is persisted and shared with internal playback: positive means earlier lyrics, negative means later, clamped to song bounds.

**匹配与限制 / matching and limitations**

- Only the Apple Music app identity is accepted, never Windows' arbitrary “current player.” Multiple Apple sessions are rejected rather than guessed. No DRM, credentials, protected streams, decryption, recording or audio capture is involved.
- SMTC exposes the app identity, not a stable session-instance/process ID. An identical-song restart between polls cannot be identified as a distinct process; the next valid timeline is used, and observed disconnects invalidate pending lyric work.
- Metadata/timeline polling and lyric IO run on workers. A timestamp-anchored monotonic clock handles rate, pause and seeks; missing, invalid or expired timelines clear the display rather than extrapolating forever. Helper failure or disconnect clears metadata/lyrics and polling retries automatically.
- Polling is every 500ms after each result, with a 2-second helper deadline. A feed expires after 5 seconds without a valid sample. Playing anchors expire after 30 seconds; a provider that publishes less frequently will deliberately show unavailable until its next timeline update. Paused positions never extrapolate and may use an older anchor while samples stay fresh.
- Matching requires normalized title and the complete artist set, plus a conservative duration tolerance when available. Ambiguous results are rejected. Live/remastered versions, artist aliases, regional catalogs and missing SMTC data can produce **no lyrics**; there is deliberately no fuzzy fallback. The bounded cache avoids repeated queries.
- Word timing, ordinary 3D animation and matched catalog artwork reuse the existing renderer. Audio-energy beat pulses are zero in external mode. Song card and Dynamic Island remain internal-player HUDs.
- No Apple login is performed by the mod. Enabling matching sends title/artist search queries to the selected lyric provider, which may require network access/account permissions. No Firebase credentials are needed to build or use external lyrics.
- The helper is an unsigned local executable. Windows policy/antivirus may block it; never disable protections or substitute an untrusted executable. Missing helper/app/timeline shows an unavailable status. The helper is not a playback-control API.

**Reproducible bridge and mod build (maintainers)**

Use Visual Studio 2022 C++ tools, Windows SDK 10.0.22621.0 with C++/WinRT headers (as in CI), CMake 3.21+, and JDK 21. From the repository root:

```powershell
cmake -S native/apple-music-bridge -B native/apple-music-bridge/build -A x64 -DCMAKE_SYSTEM_VERSION=10.0.22621.0
cmake --build native/apple-music-bridge/build --config Release
ctest --test-dir native/apple-music-bridge/build -C Release --output-on-failure
cmake --install native/apple-music-bridge/build --config Release --prefix common/core/src/main/resources
.\modern\gradlew.bat -p common :core:test :ui:build --no-daemon
.\modern\gradlew.bat -p modern :1.21.1-fabric:build --no-daemon
```

The install places `native/windows-x64/dango-apple-music.exe` in core resources before shading. The workflow verifies that the distributable jar contains it and uploads the jar plus helper.
No executable is committed. A separately built trusted helper may alternatively be installed beside the mod jar as `dango-apple-music.exe`.
Shared code still emits Java 8 bytecode; modern uses JDK 21, modern26 JDK 25, legacy JDK 8.
The helper uses only Microsoft's official [`GlobalSystemMediaTransportControlsSessionManager`](https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssessionmanager) APIs.
Each invocation emits one bounded JSON snapshot (app/session identity, title/artists/album, duration, state, rate, position, timeline `LastUpdatedTime` and capture timestamp); the JVM enforces a process deadline and never logs process output.

**Validation / 手动验收（Windows + Apple Music + Minecraft，尚待实机验证）**

Automated fake-clock/provider tests and portable native protocol tests are separate from hardware validation. Consult the PR/Actions results for commands actually run; the following is a **pending manual checklist**, not a claim of tested hardware:

- [ ] Enable external mode during internal playback: internal audio stops; queue/transport/hotkeys cannot start a second stream; disabling restores normal internal controls without auto-play.
- [ ] Play a known matching track in Apple Music: title, artists, cover and timed words appear in the existing 3D renderer. Switch lyric provider and language; verify Chinese/English settings/help/status.
- [ ] Pause for at least 30 seconds, resume, seek both backward and forward, and change playback rate if exposed: no advance while paused, no stale lyrics after seeks.
- [ ] Change tracks quickly, including while a lyric query is pending; unrelated/ambiguous recordings show no lyrics and old covers/words do not attach to the new song.
- [ ] Close/reopen Apple Music, lose the session/timeline, block or terminate the helper, then reconnect: stale content clears and recovery is automatic.
- [ ] Play another media app with Apple Music closed, and with both apps open: never attach to the unrelated app.
- [ ] Calibrate `+500` / `-500` ms and song-boundary clamping; restart Minecraft and confirm persistence.
- [ ] Try Listen Together in external mode and external mode while in a room; confirm mutual exclusion and no external track broadcast.
- [ ] Load on Linux/macOS, Windows without a helper, and Windows with a blocked helper: normal mod loading, appropriate status, no native startup dependency or crashes.

<details>
<summary>启用一起听歌（Firebase）</summary>

1. 在 [Firebase 控制台](https://console.firebase.google.com/) 新建项目，开启 **匿名登录**，创建 **Realtime Database**
2. 把 `firebase.properties.example` 复制成 `common/core/src/main/resources/firebase.properties`，填上 `apiKey` 和 `databaseUrl`
3. 数据库规则：

```json
{
  "rules": {
    "rooms": {
      "$code": {
        ".read": "auth != null",
        ".write": "auth != null && (!data.exists() || data.child('host').val() === auth.uid || data.child('members').child(data.child('host').val()).child('lastSeen').val() < now - 600000)",
        ".validate": "newData.child('host').val() === auth.uid || newData.child('host').val() === data.child('host').val()",
        "members": {
          "$uid": {
            ".write": "auth != null && auth.uid === $uid && root.child('rooms').child($code).exists()"
          }
        }
      }
    },
    "clock": {
      "$uid": {
        ".read": "auth != null && auth.uid == $uid",
        ".write": "auth != null && auth.uid == $uid"
      }
    }
  }
}
```

不配置也能构建，只是一起听歌不可用；只有连同一个 Firebase 项目的人才能一起听。
</details>

## 说明

使用各音乐平台的非官方接口，仅供个人学习交流，歌曲版权归各平台和版权方所有。

第三方：[Inter](https://github.com/rsms/inter)（OFL 1.1）、[JLayer](https://central.sonatype.com/artifact/javazoom/jlayer/1.0.1)（LGPL）、[ZXing](https://github.com/zxing/zxing)（Apache 2.0）、Kotlin 标准库（Apache 2.0）。

许可证：[GPL-3.0](LICENSE)
