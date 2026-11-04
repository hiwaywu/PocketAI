# Pocket AI

An Android chatbot for any OpenAI-compatible API (MWAPI, OpenAI, Gemini, OpenRouter, DeepSeek, Grok, Groq, …),
with web search, an in-app browser, photo upload and "Share to Pocket AI" from other apps.
Built for the Galaxy Z Fold 7: chat and browser sit side by side when unfolded.

## Features

- **Saved APIs** – enter an API address and key, then save them under a name. Keep as many as you like and
  switch between them from Settings or the model menu in the chat's title bar.
- **Load models** – asks the provider which models your key can use (`GET /v1/models`) so you can pick any of them.
  Switch models any time from the chat title bar.
- **AI web tools** – the model can call `web_search` (DuckDuckGo, or Brave/Tavily with a key) and `fetch_url`
  (reads pages; JavaScript-heavy pages are rendered in a hidden browser). Sources are shown under the answer.
- **YouTube** – shared or pasted YouTube links are read through the video's captions when available.
- **In-app browser** – browse yourself, then tap *Summarize page* or *Ask about page*.
- **Share to Pocket AI** – share a link from Firefox, Chrome, YouTube, etc. and get a summary; share photos from
  the gallery to ask about them.
- **Photos** – attach up to 4 photos or take one with the camera (needs a vision-capable model).
- Streaming replies, Stop/Retry, saved chats, Markdown, token counter, dark mode / Material You colors.

## Build

Requires JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk` (signed with the local debug key for sideloading).

## Privacy

Settings, including API keys, are stored encrypted with an Android Keystore key and are only sent to the API address
you enter. Chats are stored only on the phone. No keys are included in this repository.
