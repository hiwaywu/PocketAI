# Pocket AI — notes for working on this project

Android chatbot (Kotlin + Jetpack Compose) for any OpenAI-compatible Chat Completions API.
Owner's main provider is now NT Media (`https://api.ntmedia.store`, used ~99% of the time; MWAPI
`https://api.mwapi.dev` before); target phone is a Galaxy Z Fold 7.

## Build & run

- Needs JDK 17+ (21 used) and Android SDK platform **37.2** (`platforms;android-37.2`), `local.properties` →
  `sdk.dir=...` (not committed). Toolchain (2.0): Gradle 9.8.2, AGP 9.5 (built-in Kotlin, no kotlin-android
  plugin), Kotlin 2.4.20, Compose BOM 2026.09.00. Maven Central often answers 429 to cloud builds, so
  `settings.gradle.kts` lists Google's Maven Central mirror first. In a fresh cloud container: download the
  command-line tools from dl.google.com, `sdkmanager "platforms;android-37.2" "build-tools;36.0.0"`.
- APK is ~14 MB and runs on any phone (2.2 removed GeckoView, which made 2.0–2.1 ~100 MB and arm64-only). No R8
  yet (`isMinifyEnabled = false`); turning it on would roughly quarter the size but needs a test on a real phone.
- `./gradlew testDebugUnitTest`: JVM tests for the Reddit reader, article extraction and transcripts. To *see*
  Compose screens without an emulator (no KVM in cloud containers), Robolectric 4.17 + `ui-test-junit4` with
  `@GraphicsMode(NATIVE)` and `onRoot().captureToImage()` renders real PNGs; set
  `robolectric.dependency.repo.url` to the Google Maven mirror. (Used in 2.2, not kept in the repo.)
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. Signed with the permanent
  release key when `keystore.properties` (git-ignored: storeFile, storePassword, keyAlias, keyPassword) or
  the `POCKETAI_KEYSTORE*` env vars are present; otherwise with the debug key, which can NOT update an
  installed app. The key is never committed. (1.0–1.2 and the 1.3 test builds used throwaway debug keys, so 1.4
  needed one reinstall.) The 1.4 key turned out to be unavailable: the GitHub secrets were never set (the 2.1 and
  2.2 workflow runs failed at "Release key"), so a **new key** was made for 2.2 at the owner's request
  (`CN=Pocket AI`, alias `pocketai`, cert SHA-256 `a443e9e6…6612373`), and 2.2 needed one reinstall. The owner has the
  .jks and password; until they add them as the secrets `POCKETAI_KEYSTORE_BASE64` / `POCKETAI_KEYSTORE_PASSWORD`
  (sessions can't set secrets), the Release APK workflow can't sign. Never make yet another key without asking:
  every new key forces a reinstall that wipes chats.
- Test builds: `./gradlew assemblePreview` → `app/build/outputs/apk/preview/app-preview.apk`, "Pocket AI Test"
  (`com.pocketai.chat.test`, debug key): installs next to the real app with its own chats/settings, so feature
  branches can be tried on the Fold without risking chats. A build from another container has another debug key,
  so the test app may need uninstalling first.
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

Emulator: any image works again (no native code since 2.2).

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
- `api/WebTools.kt` — `web_search` (DeepSeek Search API, DuckDuckGo HTML, Brave, Tavily) and `fetch_url`:
  YouTube → Reddit → article (own download + `PageText` → DeepSeek Fetch API when DeepSeek is the engine →
  hidden-WebView render, whose finished HTML also goes through `PageText`). `onStatus` feeds the progress line.
- `api/PageText.kt` — article-only text via Readability4J (Kotlin port of Mozilla Readability, Apache-2.0) when the
  page declares itself an article (og:type / JSON-LD) or passes Mozilla's `isProbablyReaderable` (CJK-lowered
  thresholds); else the whole page minus menus (front pages: Readability would keep one block). JSON-LD
  `articleBody` wins when longer (teaser pages). Header line "Site · By … · Published yyyy-mm-dd". Lengths are
  `weight()`ed (CJK ×2.5): a 300-character RTHK story is complete. Tested Nov 2026: RTHK 563→299 chars, BBC
  article 5,015, NYPost 3,061, BBC/Guardian front pages kept whole. `Transcript` groups timed lines into 30 s
  chunks and samples long videos evenly (~12k tokens).
- `api/Reddit.kt` — threads via Reddit's JSON (`/comments/<id>/.json?sort=top&limit=200&depth=5&raw_json=1`;
  app UA `android:com.pocketai.chat:<ver> (Pocket AI)` first, browser UA second), else the shreddit HTML
  (`<shreddit-post>/<shreddit-comment>`); `/s/` share links are resolved with redirects off. Output: post stats
  (score, % upvoted, comments, flair, age), media type, the linked article read through `fetch` (8k chars), then
  ~14k chars of top comments with ↳ replies, upvotes, OP and flair marks (no usernames; AutoModerator/stickied
  dropped), led by `Reddit.GUIDE` (how to summarize a thread). Reddit and YouTube refuse cloud IPs (403 / Google
  "sorry" page), so these were tested with fixtures only; old.reddit.com now redirects logged-out users to login.
- YouTube: metadata from `ytInitialPlayerResponse`; caption URLs usually return nothing (YouTube needs a PO token),
  so `transcriptFromPage` opens the desktop watch page in a hidden WebView and clicks YouTube's own
  "Show transcript" (`TRANSCRIPT_JS`, polled every 0.6 s, ≤22 s). That's the page's own UI, not an impersonated
  client; written without device access, so verify on the phone. The guide asks for key moments linked as
  `[m:ss](https://youtu.be/ID?t=S)`.
- `screen/AskService.kt` (2.4) — accessibility service "Ask Pocket AI" with `flagRequestAccessibilityButton`: the
  floating accessibility button (not the side key: the owner keeps Gemini as the digital assistant for its screen
  mode). On tap only (events ignored) it reads the active app window's node tree (web content incl. off-screen
  text, apps only visible nodes, no password/editable fields) and the browser's address bar (`ScreenText.URL_BAR_IDS`;
  Chrome often shows only the site), caps at 25k chars, stores it in `PocketApp.pendingCapture` and opens
  `AskActivity`: a translucent bottom-sheet panel over the app (own task, singleTask) that starts a normal chat
  (`ChatViewModel.askAbout`, message `pageSource = "screen"`) and also handles `PROCESS_TEXT` ("Ask Pocket AI" on
  selected text, `pageSource = "selection"`). The AI is told screen text includes menus and to `fetch_url` the link
  for more. Sideloaded apps need App info → ⋮ → Allow restricted settings before the switch can be turned on.
  YouTube app (2.5): if "Show transcript" is on screen it's clicked, and a scrollable list with ≥4 timestamp lines is
  scrolled through (`ACTION_SCROLL_FORWARD`, 300 ms waits) to collect the whole transcript. Otherwise the capture
  carries `ScreenText.YOUTUBE_NOTE`, telling the AI to `fetch_url` a youtube.com/results?search_query=<title> link
  (parsed by `api/YouTubeSearch.kt` from ytInitialData) and then the video's link. Written without a device;
  verify on the Fold.
  2.7, after the first device test (button + panel worked): the YouTube app keeps title/channel/description in
  `contentDescription` of clickable containers, which 2.4–2.6 skipped (only 83 chars, player time) — labels with
  `PageText.weight` ≥ 20 now count. Newer Firefox has no `mozac_browser_toolbar_url_view` id, so browsers fall back
  to address-shaped text in the toolbar (`ScreenText.looksLikeAddress`). The button continues the last panel chat
  for 30 min (`ui` prefs `panelChat`/`panelChatAt`, `ChatViewModel.askInPanel`), attaching each new capture once;
  "+" starts a new chat. 2.8 (owner: continuing everything costs tokens; "new website → new chat"):
  `screen/SameTopic.kt` decides on each tap — different app or >30 min → new; both page links known → same link?;
  else ≥20% of the longer lines shared with the chat's last screen (`ChatMessage.pageApp/pageText`); too little text
  to compare → continue. A new chat offers "↩ Continue last chat".
  2.9 (device test: 522 chars incl. the title, but the model ignored YOUTUBE_NOTE and searched news sites): on the
  video page the service clicks the title's "...more" (`ScreenText.isMoreLink`), scrolls the description panel to
  "Show transcript", clicks it and reads the panel (YouTube app UI seen in the owner's screenshots: Description →
  Transcript → "Show transcript"; Transcript panel rows = time chip + text). If that fails, the app itself searches
  YouTube with `ScreenText.titleCandidates` and matches by longest shared text (`YouTubeSearch.bestMatch`), then
  passes the exact link (`youTubeLinkNote`, also capture.url for SameTopic).
  2.10 (device test with the transcript panel open: only the visible ~1 minute was read, 542 chars): the list is
  found from its time stamps (`ScreenText.leadingTime` also parses "1 minute, 49 seconds"; skips "2:04 / 21:25" and
  "… ago") via the nearest scrollable ancestor, and scrolled with SCROLL_FORWARD → SCROLL_DOWN → a
  `dispatchGesture` swipe (`canPerformGestures` in ask_service.xml). Never taps "...more" while time lines show.
  2.11 (device test on 2.10 still read only the first ~54 s): YouTube's transcript opens at the playing position
  and follows playback, and a scroll action can report success without moving. Now: pause ("Pause video"), sweep UP
  then DOWN, each way switching action → page → swipe when a way brings no new lines; lines sorted by time;
  complete = starts ≤0:15 and reaches ≥85% of the player's length (`ScreenText.videoLength`). Incomplete → video
  link lookup + `youTubePartialNote` (fetch_url the link before answering). `ScreenCapture.debug` ("How it went")
  is shown under the panel's "What Pocket AI read" preview — ask the owner for a screenshot of it when debugging.
  2.12 (owner: "comments section summary good too if can read it all"): with YouTube's comments panel open ("Comments"
  header + ≥3 "@author" rows in one scrolling list), the button sweeps it down (same `sweep`), one comment per list
  row via `ScreenText.formatComment` → "[1.2K likes] text (23 replies)" (no @names/times/buttons), max 150 or
  ~20k chars, + `YOUTUBE_COMMENTS_NOTE`. Checked before the transcript path. Untested on a device.
  2.13 (device tests of 2.11/2.12 failed: transcript still ~30 s; comments panel gave like counts but no text/authors;
  once the button read Pocket AI's own panel): `appWindow()` skips our own windows. Likely cause for the missing
  comment text: YouTube is built with Litho, which only exposes text components to accessibility when touch
  exploration (TalkBack) is on — so the app's comments (and maybe more) may be unreadable; `commentsOpen` then sends
  `YOUTUBE_COMMENTS_HIDDEN_NOTE`. Diagnostics: `AskService.report()` (windows, ancestors/rows around the first time
  stamp and comment author with classes, S/C/h flags, scroll actions, swipes sent/finished) goes into
  `ScreenCapture.debug`; the panel preview has **Copy details** — ask the owner to paste it before changing the
  YouTube automation again. The share path's hidden-WebView transcript now reports why it failed
  (`transcriptProblem`, incl. the page title/URL it landed on) into the tool result.
  2.14 — real labels from the owner's Fold (screenshot of "What Pocket AI read", app was still on ≤2.10): transcript
  rows expose one contentDescription "5 minutes, 42 seconds two legs on…"; the player exposes "5 minutes 58 seconds
  elapsed of 14 minutes 36 seconds", "5:58", "/ 14:36", "5 minutes 58 seconds of 14 minutes 36 seconds", then a
  "Transcript" header. The player labels were parsed as transcript times; `leadingTime` now rejects "elapsed…"/"of…"/
  "/…", and `transcriptList` takes the smallest ancestor of the first row holding ≥3 transcript segments (not the
  nearest scrollable, which could be the whole page). Test `youTubeLabelsFromTheFold` uses these strings.
  2.15 (device test: Reddit app → "Looking at Reddit" but 0 text): `pickWindow()` walks every app window (not ours)
  and reads the active one unless another has ≥2× its text (or it has <50 chars); later steps reuse that window id.
  The report (`report()`, now for every app, with text per window) backs "Details"/"Copy details". Reddit app
  (`ScreenText.REDDIT_APP`): `redditTitleCandidates` + the "r/sub" line → `WebTools.findRedditThread` (Reddit
  search.json in the subreddit, then site-wide; `PageText.sameTitle` = longest shared run) → `fetch(link)` like a
  shared link; the capture text is that thread (with `Reddit.GUIDE`) plus the screen.
  2.16 (owner: "make it easier to hide it, hold down to hide"): Android's accessibility button can't be customised,
  so `screen/FloatingButton.kt` draws our own bubble as a `TYPE_ACCESSIBILITY_OVERLAY` window from the service (no
  overlay permission): tap = ask, drag = move + snap to edge (`ui` prefs floatingButtonX/Y), hold = menu (hide 1 hour
  / until turned back on / cancel). Hidden while AskActivity is visible. On/off: Settings switch, Quick Settings tile
  `screen/ButtonTile.kt` (StatusBarManager.requestAddTileService on API 33+).
  2.17: "hide" means tuck into the side, not remove: hold → slides ~64% past the nearest edge (tab, alpha 0.6,
  `ui` pref floatingButtonTucked, kept across apps); tap the tab → slides back; drag → untucks and snaps; hold the
  tab → the hide menu (1 hour / until turned back on).
  2.18 (owner: "you don't need two buttons", keep only the new one): `flagRequestAccessibilityButton` removed. With it
  (targetSdk > Q) Android treats the service as an "invisible toggle": it is on only while assigned to a shortcut, and
  AccessibilityManagerService re-adds an enabled button-requesting service to the button targets, so Android's own
  button always came back. Now it's a normal toggle (on/off switch in Accessibility settings, no shortcut needed) and
  the floating bubble is the only button. Old installs keep the shortcut: tapping Android's button would now switch
  the service off, so Settings shows how to turn the shortcut off while `AskService.systemButtonAssigned`
  (Secure `accessibility_button_targets`) says it's still there.
  2.19 — **the YouTube-app automation (2.5–2.14: tapping "...more"/"Show transcript", pausing, sweeping the
  transcript/comments lists, swipes) was removed**: after ten versions it still read only ~20 s of transcript and no
  comment text, while sharing a YouTube link to Pocket AI read the whole transcript on the owner's Fold (Nov 9 2026,
  CNBC clip). Now the button in YouTube only reads the screen, finds the video by its title
  (`ChatViewModel.readYouTubeVideo` = `WebTools.findYouTubeVideo` + `fetch`, ≤50 s) and attaches that page like the
  Reddit-app path; not found → `YOUTUBE_NOTE`. Comments can't be read at all (app hides the text, the link reader has
  none): `YOUTUBE_COMMENTS_HIDDEN_NOTE` and the YouTube guide tell the model to say so and not offer a link route; the
  panel no longer suggests "What do the comments say?". If comments are wanted again: YouTube Data API
  `commentThreads.list` with a free Google API key (offered to the owner, not built). The old automation is in git
  history up to 2.18; `ScreenText` still has its tested parsing helpers (leadingTime, transcriptSegments, formatComment).
- `phone/PhoneTools.kt` (2.20, on a test branch first): tools `set_alarm` / `set_timer` (Clock app via
  `AlarmClock` intents with `EXTRA_SKIP_UI`, SET_ALARM permission, no prompt; Android blocks opening another app
  from the background, so then a "Tap to set alarm" notification is posted instead) and `add_calendar_event` /
  `list_calendar_events` (CalendarContract; writes to the Google account's primary calendar, default reminder 30 min;
  reads `Instances` so repeating events show). READ/WRITE_CALENDAR is asked mid-reply through
  `phone/PhonePermissions.kt` (`PermissionRequester()` in both activities; refused when no Pocket AI screen is
  visible). Each action is a `ToolEvent` card (kinds alarm/timer/calendar/agenda): tap opens Clock/Calendar,
  calendar cards have Undo (`ChatViewModel.undoPhoneAction`). Setting "Phone actions" (`AppSettings.phoneTools`).
  The model gets no clock time (caching), so the tools take `in_minutes` and results state the current time.
  Untested on a device.
- `screen/ScreenOcr.kt` (2.20 test): when a screen gives <150 weighted chars to accessibility (the Reddit app gave
  0 on the Fold in 2.15–2.19), the button screenshots the app's window (`takeScreenshotOfWindow`, API 34+;
  `canTakeScreenshot` in ask_service.xml) and reads it with ML Kit text recognition (Play services' Chinese model,
  also reads English; `com.google.mlkit.vision.DEPENDENCIES=ocr_chinese`). The OCR blocks replace the pieces, so the
  Reddit-app path (title → whole thread) still runs. Logged as "ocr: …" in Details. Untested on a device.
- `data/Rates.kt` — NT Media per-model usage multipliers (from its price list; its API doesn't expose
  them). `ReplyUsage.multiplier`/`billed` = tokens × multiplier, shown in chat, usage dialog/screen, model menu.
- `data/Settings.kt` — `AppSettings` (saved API profiles, search engine/keys), stored as one AES-GCM
  Android-Keystore-encrypted JSON blob. `data/Chats.kt` — chats as JSON files, `ReplyUsage`, photo import.
- `ui/` — `AppRoot` (fold-aware: chat list as a permanent side panel ≥600dp, drawer when folded), `ChatList`
  (search titles + text, Pinned, date groups, ⋮ Pin/Rename/Share/Delete), `ChatScreen` (long-press own message →
  Copy / Edit and resend; reply actions Copy, Share, Read aloud, Regenerate; follow-up chips only under the first
  reply of a link chat; mic = voice typing via `RecognizerIntent`; jump-to-newest button), `Links` (links open in
  their own app via `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`, else a Chrome Custom Tab; share sheet; chat export),
  `SettingsScreen` (NovelAI-style saved APIs + "Save this API setting?" prompt + Load models),
  `UsageScreen` (per-reply and overall token breakdown; filter chips per API *name* — `ReplyUsage.apiName`
  since 1.10; the owner wants names only, no key hints), `Markdown` (small renderer; table columns measured with
  `TextMeasurer` so "11:35" never wraps). `Speaker.kt` — read aloud with the phone's TTS (needs the
  `TTS_SERVICE` `<queries>` entry), CJK text → zh-HK voice.

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
  sentence-by-sentence translation; only the page's main content; no added facts. 2.3: the owner found 2.2's
  labelled layout ("TL;DR:", "The post:", "The vibe:", upvotes after every point) weird; now plain opening
  sentences + bullets, no paragraph labels, at most a heading like "What people are saying" / "Key moments".
  2.5: the owner asked for summaries "how you'd do it, like you as a chatbot" — all layout rules were dropped. The
  prompt now just says: explain it like a thoughtful friend in a chat, shape that fits, keep specifics, no
  templates or "TL;DR:" labels, say if something couldn't be read. Don't reintroduce templates.
  2.20 exception, at the owner's request: **videos are summarized in the order they're said** (what it is, then
  start → end, each part led by its linked time, who says what), not regrouped by theme with bold labels — the
  owner called the topic-grouped version of a 33-min Cantonese talk show "crap". Lives in `youtubeGuide`.
- 2.6: the owner asked that the AI "follow other instructions similar to you" (Claude). `DEFAULT_SYSTEM_PROMPT` and the
  added language/summary/web rules were rewritten in that plain style: answer first, length matches the question,
  prose over formatting, honest about uncertainty, opinions when asked, no filler or closing offers. Still no
  assistant name. Saved copies of older defaults are upgraded on load (`OLD_DEFAULT_PROMPTS`); edited ones aren't.
- Token policy: don't waste, don't skimp. Chat text is always re-sent in full; raw web results only for the
  latest answer; photos/shared pages only for the last 4 user messages; system prompt has date only so
  provider prompt caching works. Each reply records tokens, steps, searches, pages, seconds.
- Search: DeepSeek Search (`POST https://api.deepseek.com/v1/tools/search`, results have a 600-800 char
  `summary`; ~$0.75 per 1,000 searches) is the recommended engine; falls back to DuckDuckGo on failure.
  DeepSeek's *built-in* `web_search` tool only exists in its Responses API with `deepseek-v4-pro`; not used.
- Only the OpenAI Chat Completions format is implemented. The NovelAI app also supports Anthropic format
  (`/v1/messages`) — a possible addition, which would also allow Claude's server-side web search via MWAPI.
- YouTube caption downloads are blocked by YouTube; don't try to impersonate YouTube app clients. 2.2 reads the
  transcript panel in a hidden WebView instead (see YouTube above).
- The in-app browser (1.4–2.1; GeckoView + uBlock Origin + Bypass Paywalls Clean in 2.0–2.1) was removed in 2.2 at
  the owner's request ("I don't need the browser", for size). Links from chats open in their app or a Custom Tab.
- Summaries (2.2): general rules (TL;DR first, shape by content type, phone-friendly, no wide tables, no "I'll open
  that page" narration) live in the system prompt; per-type guides (Reddit, YouTube) travel inside the tool result
  so ordinary chats don't pay for them. Text a model writes before a tool call is removed from the screen.
  Chats started from "Summarize this …: <link>" are renamed after the page title.
- OpenCode Go (`https://opencode.ai/inference/go/v1`; old `/zen/go/v1` also answers) rejects requests
  without `x-opencode-session` (400 MissingSessionID). `ChatClient` sends the chat id there for opencode.ai
  hosts, and a `PocketAI/<version> (Android)` User-Agent everywhere. OpenCode says Go is designed for coding
  agents and monitors traffic; don't impersonate other clients.
  OpenCode addresses: `/inference/go/v1` = Go subscription (needs the session header, models at
  `/inference/go/v1/models`); `/inference/openai/v1` = pay-as-you-go Console balance (no session header,
  but its `/models` is 404 — the list is at `/inference/v1/models`). Usage carries `cost` (USD) and
  `opencode.tokens.cache_read` (read as cached tokens). Docs: https://opencode.ai/v2/docs/console/api/inference
- Never commit API keys or the signing key.
