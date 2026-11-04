# Pocket AI — notes for working on this project

Android chatbot (Kotlin + Jetpack Compose) for any OpenAI-compatible Chat Completions API.
Owner's main provider is now NT Media (`https://api.ntmedia.store`, used ~99% of the time; MWAPI
`https://api.mwapi.dev` before); target phone is a Galaxy Z Fold 7.

## Build & run

- Needs JDK 17 and Android SDK platform 35 (`local.properties` → `sdk.dir=...`, not committed).
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. Signed with the permanent
  release key when `keystore.properties` (git-ignored: storeFile, storePassword, keyAlias, keyPassword) or
  the `POCKETAI_KEYSTORE*` env vars are present; otherwise with the debug key, which can NOT update an
  installed app. The key is never committed; the owner keeps a copy, and GitHub has it as the secrets
  `POCKETAI_KEYSTORE_BASE64` / `POCKETAI_KEYSTORE_PASSWORD`. (1.0–1.2 and the 1.3 test builds used throwaway debug keys, so 1.4
  needed one reinstall.)
- Releases: bump `versionCode`/`versionName` in `app/build.gradle.kts`, write `release-notes/v<version>.md`,
  merge to `main`, then run the **Release APK** workflow (`.github/workflows/release.yml`, workflow_dispatch
  with input `version`). It builds the APK with the release key and creates the GitHub release.

## Testing without spending tokens

When a test must hit the real NT Media API, use **`deepseek-v4-flash`** (×1) only — never `deepseek-v4-pro`
or other pricier models — and keep the key out of the repo (put it in a scratch file / env var).

`tools/mock_api.py` is a fake OpenAI-compatible API (`python tools/mock_api.py`, port 8765).
In the emulator use API address `http://10.0.2.2:8765`, any key, then *Load models*.
Asking anything containing "nba" makes the mock request a `web_search`, so the full tool loop
(real search on the device → result → final answer) is exercised. It logs each request's size and
roles to `mock_requests.log`.

Emulator gotcha: an attached WebView that has never loaded a page stopped the rest of the Compose UI
from drawing, so `BrowserPane` only attaches the WebView once a URL is open.

## Code map (`app/src/main/java/com/pocketai/chat/`)

- `PocketApp.kt` — owns the single process-wide `ChatViewModel` (not tied to the Activity), so replies
  survive leaving the app, swiping it from Recents, and fold changes.
- `ReplyService.kt` — `dataSync` foreground service + partial wake lock while any chat has a reply
  running (`UiState.running`); stops itself when none do. Also posts "reply ready" notifications
  when the app isn't visible; tapping one opens that chat (`EXTRA_OPEN_CHAT`). It must be started
  while the app is in the foreground (Android 12+ rule), i.e. at Send.

- `ChatViewModel.kt` — all state; `agentLoop` (stream → run tools → repeat, max 4 steps),
  `buildApiMessages` (what gets re-sent each turn), API profile management, share handling, usage tracking.
- `api/ChatClient.kt` — streaming `/chat/completions` (SSE), `/models` listing, `providerEndpoint()` URL rule
  (same as the owner's NovelAI app: append `/v1` unless the address already ends in a version path).
- `api/WebTools.kt` — `web_search` (DeepSeek Search API, DuckDuckGo HTML, Brave, Tavily) and `fetch_url`
  (DeepSeek Fetch API when DeepSeek is the engine → plain download + Jsoup → hidden-WebView render for
  script-built pages), YouTube title/description (+captions when YouTube allows).
- `data/Rates.kt` — NT Media per-model usage multipliers (from its price list; its API doesn't expose
  them). `ReplyUsage.multiplier`/`billed` = tokens × multiplier, shown in chat, usage dialog/screen, model menu.
- `data/Settings.kt` — `AppSettings` (saved API profiles, search engine/keys), stored as one AES-GCM
  Android-Keystore-encrypted JSON blob. `data/Chats.kt` — chats as JSON files, `ReplyUsage`, photo import.
- `ui/` — `AppRoot` (fold-aware: side-by-side ≥600dp, tabs when folded), `ChatScreen`, `BrowserPane`,
  `SettingsScreen` (NovelAI-style saved APIs + "Save this API setting?" prompt + Load models),
  `UsageScreen` (per-reply and overall token breakdown), `Markdown` (small renderer).

## Decisions so far

- Background: replies run per chat (`ChatViewModel.jobs`), switching chats no longer stops a reply.
  Dropped connections (IOException, 5xx, SSE ending without `[DONE]`/finish_reason) retry the step
  after 2 s / 5 s / 10 s, rolling back that step's half-written text.
- NT Media (tested Nov 2026): adds ~165 hidden prompt tokens to every request; reports
  `prompt_tokens_details.cached_tokens` (prefix caching works, incl. between tool steps);
  `reasoning_effort` is not reliably honored; no balance/usage endpoint; reasoning isn't streamed.

- The system prompt names the answering model id + host (models otherwise copy earlier replies' identity
  claims, e.g. "I'm Claude" after switching models; NT Media injects its own identity prompt, OpenCode doesn't).
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
- OpenCode Go (`https://opencode.ai/inference/go/v1`; old `/zen/go/v1` also answers) rejects requests
  without `x-opencode-session` (400 MissingSessionID). `ChatClient` sends the chat id there for opencode.ai
  hosts, and a `PocketAI/<version> (Android)` User-Agent everywhere. OpenCode says Go is designed for coding
  agents and monitors traffic; don't impersonate other clients.
  OpenCode addresses: `/inference/go/v1` = Go subscription (needs the session header, models at
  `/inference/go/v1/models`); `/inference/openai/v1` = pay-as-you-go Console balance (no session header,
  but its `/models` is 404 — the list is at `/inference/v1/models`). Usage carries `cost` (USD) and
  `opencode.tokens.cache_read` (read as cached tokens). Docs: https://opencode.ai/v2/docs/console/api/inference
- Never commit API keys or the signing key.
