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
- `ui/` — `AppRoot` (fold-aware: side-by-side ≥600dp, tabs when folded), `ChatScreen`, `BrowserPane`
  (`BrowserController`: tabs = one WebView each, restored lazily from prefs; bookmarks (new-tab tiles); ⋮ menu with
  find/share/copy/open externally/desktop UA/text size/ad block; Chrome-style address pill that selects all on
  tap; message bar that sends the question with the page text, no preset prompts; user agent has `; wv`/
  `Version/x` stripped so sites serve normal mobile pages; `textZoom` fixed at 100 by default because following
  the phone's font scale made text overflow its boxes), `AdBlocker` (StevenBlack hosts list cached weekly +
  built-in list, subresources only via `shouldInterceptRequest`; injected CSS hides Reddit/Google/GPT ad boxes;
  auto-dismisses "allow ads" JS alerts/confirms; NAG_JS removes in-page anti-adblock overlays + backdrop and
  restores scrolling, only when their text matches; hides SCMP-style empty `AdSlot` boxes and sticky "adhesion" ads).
  Never blocks a page's own site (`siteOf`, e.g. dd.nytimes.com on nytimes.com) or `NEVER_BLOCK` services
  (DataDome/captchas, Piano/tinypass, consent managers): the hosts list blocks them and NYT/NY Daily News got
  stuck dimmed with an empty popup (1.13). ⋮ → Unblock page runs `UNBLOCK_PAGE_JS` (removes cover layers and
  mid-screen high-z popups, restores scrolling).
  Page text for the AI: `assets/readability/` (Mozilla Readability 0.5.0, Apache-2.0) + `extract.js` → article
  only when `isProbablyReaderable` (CJK-lowered thresholds; it crashes on RTHK, so guarded) or the page declares
  itself an article (og:type / article:published_time / <article> / JSON-LD); else `innerText`. Tested in Playwright:
  RTHK 3,326→221 chars, hk01/NYPost/BBC −30–40%, RTHK list/Reddit unchanged. Sidebar headlines leaked into
  summaries before.
  `SettingsScreen` (NovelAI-style saved APIs + "Save this API setting?" prompt + Load models),
  `UsageScreen` (per-reply and overall token breakdown; filter chips per API *name* — `ReplyUsage.apiName`
  since 1.10; the owner wants names only, no key hints), `Markdown` (small renderer).

## Decisions so far

- Background: replies run per chat (`ChatViewModel.jobs`), switching chats no longer stops a reply.
  Dropped connections (IOException, 5xx, SSE ending without `[DONE]`/finish_reason) retry the step
  after 2 s / 5 s / 10 s, rolling back that step's half-written text.
- NT Media (tested Nov 2026): adds ~165 hidden prompt tokens to every request; reports
  `prompt_tokens_details.cached_tokens` (prefix caching works, incl. between tool steps);
  `reasoning_effort` is not reliably honored; no balance/usage endpoint; reasoning isn't streamed.

- Don't tell the model its name in the system prompt (tried in 1.5, removed in 1.6): the owner asks models
  what they are to check whether a provider is honest, and naming it just makes the model repeat it.
  NT Media injects its own identity prompt; OpenCode doesn't. In Nov 2026 tests several NT Media "models"
  (Claude/GPT/Gemini/DeepSeek names) gave near-identical answers, searches and `call_00_…` tool-call ids,
  which hints they share one backend — unproven.
- Freshness (1.9): the web-tools prompt tells the model its knowledge is old, to search "latest/last/next"
  questions with an open, dated query (not the answer it remembers), trust the newest dated results, and treat
  shared pages as possibly outdated. Before: 4/4 runs answered "who did Makhachev last beat" from a stale
  article without searching; after: 4/4 searched "… last fight November 2026" and answered from the newest
  result. The no-tools final step gets an "answer now" note (DeepSeek otherwise prints raw DSML tool calls).
- Replies are in the language of the user's typed words ("Summary" = English), never the source's; foreign text is
  translated (original in brackets only when it matters; never guess name spellings). Without the explicit
  "one-word request" wording, a Chinese article + "Summary" got a Chinese reply 2 of 2 times.
- Summaries: journalist-style brief (what happened + why it matters, key-detail bullets, what's unclear), not a
  sentence-by-sentence translation; only the page's main content; no added facts.
- Token policy: don't waste, don't skimp. Chat text is always re-sent in full; raw web results only for the
  latest answer; photos/shared pages only for the last 4 user messages; system prompt has date only so
  provider prompt caching works. Each reply records tokens, steps, searches, pages, seconds.
- Search: DeepSeek Search (`POST https://api.deepseek.com/v1/tools/search`, results have a 600-800 char
  `summary`; ~$0.75 per 1,000 searches) is the recommended engine; falls back to DuckDuckGo on failure.
  DeepSeek's *built-in* `web_search` tool only exists in its Responses API with `deepseek-v4-pro`; not used.
- Only the OpenAI Chat Completions format is implemented. The NovelAI app also supports Anthropic format
  (`/v1/messages`) — a possible addition, which would also allow Claude's server-side web search via MWAPI.
- YouTube caption downloads are blocked by YouTube; don't try to impersonate YouTube app clients.
  The workaround is the in-app browser: *...more → Show transcript*, then ask in the browser's message bar.
- OpenCode Go (`https://opencode.ai/inference/go/v1`; old `/zen/go/v1` also answers) rejects requests
  without `x-opencode-session` (400 MissingSessionID). `ChatClient` sends the chat id there for opencode.ai
  hosts, and a `PocketAI/<version> (Android)` User-Agent everywhere. OpenCode says Go is designed for coding
  agents and monitors traffic; don't impersonate other clients.
  OpenCode addresses: `/inference/go/v1` = Go subscription (needs the session header, models at
  `/inference/go/v1/models`); `/inference/openai/v1` = pay-as-you-go Console balance (no session header,
  but its `/models` is 404 — the list is at `/inference/v1/models`). Usage carries `cost` (USD) and
  `opencode.tokens.cache_read` (read as cached tokens). Docs: https://opencode.ai/v2/docs/console/api/inference
- Never commit API keys or the signing key.
