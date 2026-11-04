# Pocket AI — notes for working on this project

Android chatbot (Kotlin + Jetpack Compose) for any OpenAI-compatible Chat Completions API.
Owner's main provider is MWAPI (`https://api.mwapi.dev`); target phone is a Galaxy Z Fold 7.

## Build & run

- Needs JDK 17 and Android SDK platform 35 (`local.properties` → `sdk.dir=...`, not committed).
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (signed with the local debug key).
- Releases: copy the APK to `release/PocketAI-<version>.apk` (git-ignored) and
  `gh release create v<version> release/PocketAI-<version>.apk --repo hiwaywu/PocketAI`.
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each release.

## Testing without spending tokens

`tools/mock_api.py` is a fake OpenAI-compatible API (`python tools/mock_api.py`, port 8765).
In the emulator use API address `http://10.0.2.2:8765`, any key, then *Load models*.
Asking anything containing "nba" makes the mock request a `web_search`, so the full tool loop
(real search on the device → result → final answer) is exercised. It logs each request's size and
roles to `mock_requests.log`.

Emulator gotcha: an attached WebView that has never loaded a page stopped the rest of the Compose UI
from drawing, so `BrowserPane` only attaches the WebView once a URL is open.

## Code map (`app/src/main/java/com/pocketai/chat/`)

- `ChatViewModel.kt` — all state; `agentLoop` (stream → run tools → repeat, max 4 steps),
  `buildApiMessages` (what gets re-sent each turn), API profile management, share handling, usage tracking.
- `api/ChatClient.kt` — streaming `/chat/completions` (SSE), `/models` listing, `providerEndpoint()` URL rule
  (same as the owner's NovelAI app: append `/v1` unless the address already ends in a version path).
- `api/WebTools.kt` — `web_search` (DeepSeek Search API, DuckDuckGo HTML, Brave, Tavily) and `fetch_url`
  (DeepSeek Fetch API when DeepSeek is the engine → plain download + Jsoup → hidden-WebView render for
  script-built pages), YouTube title/description (+captions when YouTube allows).
- `data/Settings.kt` — `AppSettings` (saved API profiles, search engine/keys), stored as one AES-GCM
  Android-Keystore-encrypted JSON blob. `data/Chats.kt` — chats as JSON files, `ReplyUsage`, photo import.
- `ui/` — `AppRoot` (fold-aware: side-by-side ≥600dp, tabs when folded), `ChatScreen`, `BrowserPane`,
  `SettingsScreen` (NovelAI-style saved APIs + "Save this API setting?" prompt + Load models),
  `UsageScreen` (per-reply and overall token breakdown), `Markdown` (small renderer).

## Decisions so far

- Token policy: don't waste, don't skimp. Chat text is always re-sent in full; raw web results only for the
  latest answer; photos/shared pages only for the last 4 user messages; system prompt has date only so
  provider prompt caching works. Each reply records tokens, steps, searches, pages, seconds.
- Search: DeepSeek Search (`POST https://api.deepseek.com/v1/tools/search`, results have a 600-800 char
  `summary`; ~$0.75 per 1,000 searches) is the recommended engine; falls back to DuckDuckGo on failure.
  DeepSeek's *built-in* `web_search` tool only exists in its Responses API with `deepseek-v4-pro`; not used.
- Only the OpenAI Chat Completions format is implemented. The NovelAI app also supports Anthropic format
  (`/v1/messages`) — a possible addition, which would also allow Claude's server-side web search via MWAPI.
- YouTube caption downloads are blocked by YouTube; don't try to impersonate YouTube app clients.
  The workaround is the in-app browser: *...more → Show transcript* → *Summarize page*.
- Never commit API keys.
