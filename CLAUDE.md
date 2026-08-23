# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
./gradlew assembleDebug       # Build debug APK
./gradlew installDebug        # Install debug APK to connected device
./gradlew build               # Full build + lint
./gradlew lint                # Run lint only
./gradlew :app:dependencies   # Inspect dependency tree
```

Gradle wrapper is `8.6` (not 8.2 as the README claims — the README is stale on this point). There is no CI/CD and no test suite.

## Debugging

```bash
adb shell logcat -s ProxyServer:BaiduYunClient:HlsProxyHandler:PlaybackService
```

All `Log` tags are class names (e.g., `"BaiduYunClient"`, `"ProxyServer"`).

## Architecture

This is a **single-module Android app** (no library modules) that runs a local Ktor HTTP proxy server and plays HLS video through Media3 ExoPlayer. It is a Kotlin-native port of a Python "Baidu Pan HLS proxy service."

**Data flow for playback:**
1. User enters a directory name in the Compose UI → `PlaybackService` constructs a playlist URI: `http://127.0.0.1:<port>/hls/<dir>/playlist.m3u8`
2. ExoPlayer requests the `.m3u8` → local `ProxyServer` (Ktor CIO, binds 127.0.0.1, auto-assigned port) → `HlsProxyHandler`
3. `HlsProxyHandler` fetches the file list from `BaiduYunClient`, rewrites chunk URLs via `M3u8Rewriter`, and streams `.ts` segments from Baidu Pan through the proxy
4. Both handlers resolve the target file through `YunIndex.fsid(yunPath)` (path mapping + directory listing + in-memory TTL cache); segment content is never cached

**Key layers:**

| Layer | Package | Role |
|---|---|---|
| UI | `ui/` | Jetpack Compose (Material 3), hand-rolled `NavState` stack, `MediaController` connection to playback service |
| Playback | `service/` | `PlaybackService` (MediaSessionService) that owns ExoPlayer, MediaSession, and the proxy lifecycle |
| Proxy | `proxy/` | Embedded Ktor CIO server (`ProxyServer`), request routing (`HlsProxyHandler`), playlist URL rewriting (`M3u8Rewriter`), and `YunIndex` (path → fsid resolution: mapping, directory listing, TTL cache) |
| API client | `baidu/` | `BaiduYunClient` (Ktor OkHttp engine) calling Baidu Pan REST API — uses browser UA for listing, `pan.baidu.com` UA + access_token for downloads |
| Config | `config/` | `AppSettings` wrapping DataStore Preferences (access_token, fsid cache TTL, port, movie_api base URL) |

## Important Conventions & Gotchas

- **Log tags** use the class name as a string literal.
- **Network security**: cleartext HTTP is only permitted to localhost (`res/xml/network_security_config.xml`). Do not add cleartext for remote hosts.
- **HLS directory convention**: the app expects playlists at `/apps/movies/<user-input>/playlist.m3u8` on Baidu Pan. This path prefix is hardcoded in `PlaybackService.buildPlaylistUri()`.
- **BLAST BufferQueue deadlock**: when destroying a `PlayerView`, first pause the player, then detach the surface, then destroy. Do not bind the player before the view is attached to a window. See `GesturePlayerView.kt` for inline notes.
- **Fullscreen orientation**: handled via `configChanges` in the manifest (orientation changes do not recreate the activity).
- **Cache settings** are snapshotted at service start time; changes require a process restart.
- **access_token** is stored in DataStore; refresh is manual (re-enter in Settings UI). The app only detects expiry when Baidu rejects a request.
- **No tests exist** — there are no test source sets, no test dependencies in the version catalog, and no testOptions configured.
