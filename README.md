# Pocket AI

An Android chatbot for any OpenAI-compatible API (MWAPI, OpenAI, Gemini, OpenRouter, DeepSeek, Grok, Groq, …),
with web search, link and video summaries, photo upload and "Share to Pocket AI" from other apps.
Built for the Galaxy Z Fold 7: the chat list stays open beside the chat when unfolded.

## Features

- **Saved APIs** – enter an API address and key, then save them under a name. Keep as many as you like and
  switch between them from Settings or the model menu in the chat's title bar.
- **Load models** – asks the provider which models your key can use (`GET /v1/models`) so you can pick any of them.
  Switch models any time from the chat title bar.
- **AI web tools** – the model can call `web_search` (DeepSeek Search recommended; DuckDuckGo free; Brave/Tavily) and `fetch_url`
  (reads pages, cut down to just the article like Firefox Reader View; JavaScript-heavy pages are rendered in a hidden
  WebView). Sources are shown under the answer.
- **Reddit threads** – the post, the article or video it links to (read automatically) and the top-voted comments
  with their upvotes, so summaries cover what was posted, the mood of the room and the main reactions.
- **YouTube** – title, channel, date, description and chapters, plus the transcript when YouTube shows one
  (read from YouTube's own "Show transcript" panel). Key moments link to that point in the video.
- **Share to Pocket AI** – share a link from Chrome, Reddit, YouTube, etc. and get a summary, with one-tap
  follow-ups ("What's the pushback?", "Is this reliable?"); share photos from the gallery to ask about them.
- **Chat tools** – search, pin, rename and share chats; long-press your message to copy or edit and resend it;
  copy, share or listen to (read aloud) any reply; voice typing; links open in their app or a Chrome tab.
- **Photos** – attach up to 4 photos or take one with the camera (needs a vision-capable model).
- **Token usage** – every reply shows what it cost; tap it for a breakdown (chat history / web results / photos /
  shared pages / output). *Settings drawer → Token usage* shows totals, the most expensive replies, and usage by chat and model.
- **No wasted tokens** – chat text is always kept, but raw web pages are only re-sent for the latest answer, photos and
  shared pages only for the last 4 messages, and the system prompt stays identical so provider caching discounts apply.
- Streaming replies, Stop/Retry, saved chats, Markdown, dark mode / Material You colors.

## Build

Requires JDK 17 and the Android SDK (platform 37.2).

```bash
./gradlew assembleRelease
```

The APK (about 14 MB) is written to `app/build/outputs/apk/release/app-release.apk`; see CLAUDE.md for release signing.

## Privacy

Settings, including API keys, are stored encrypted with an Android Keystore key and are only sent to the API address
you enter. Chats are stored only on the phone. No keys are included in this repository.
