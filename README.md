# FreeLLM AI for Android

A native Android AI chat workspace with a calm, ChatGPT-inspired layout and your choice of provider. Built with Kotlin and Jetpack Compose. No subscription to this app is required; each provider controls its own availability, pricing and quotas.

## Included

- Searchable local chat history, pinned/archived chats, rename and confirmed deletion
- Persistent drafts and conversations; generation survives screen rotation
- Streaming replies, stop, retry, regenerate and edit previous messages
- Selectable text, Markdown headings/lists/links, horizontally scrolling code blocks with copy
- Image and document attachments, bounded local processing and removable attachment cards
- Speech dictation using an installed Android speech-recognition activity (not a live voice call)
- System, light, dark and AMOLED themes; keyboard and system-bar insets; 48dp controls
- Separate provider settings, encrypted API keys, model discovery and connection checks
- Text conversation sharing with a privacy confirmation

## Connect an AI

Open **Settings**, choose a provider, enter its API key and exact model ID, then **Save**. **Test connection & find models** calls the provider's model catalog; it does not send a chat. If discovery is unavailable, enter the model ID manually.

| Connection | Base URL | Key |
| --- | --- | --- |
| FreeLLMAPI | `http://127.0.0.1:3001/v1` or your server address | Your server's unified key |
| OpenAI | `https://api.openai.com/v1` | OpenAI API key |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta` | Google AI Studio API key |
| Anthropic | `https://api.anthropic.com/v1` | Anthropic API key |
| OpenRouter | `https://openrouter.ai/api/v1` | OpenRouter unified key |
| Groq | `https://api.groq.com/openai/v1` | Groq API key |
| Other / local | Provider's OpenAI-compatible API base URL | Provider key, or empty if server permits |

OpenAI-compatible gateways must support `/chat/completions`; model discovery additionally needs `/models`. Gemini and Anthropic use their native protocols. Proprietary protocols require a dedicated adapter. OpenAI Responses-only models are not supported by the chat-completions adapter.

For FreeLLMAPI, keep upstream provider keys on your server and enter its **unified** key in the app. For a direct connection, enter that provider's key in its profile. FreeLLMAPI and custom servers may permit an empty key; the app allows this without claiming the server will accept it.

On a phone, `127.0.0.1` means the phone itself. For a computer on your network, use its LAN address. The Android emulator can reach a host service through `10.0.2.2`. Prefer HTTPS for remote endpoints. The app warns for unencrypted HTTP and never forwards credentials across redirects.

Enable **Image input** only for a vision-capable model. Switching to a text-only profile will require a new chat or re-enabling vision if the conversation contains images. Turn **Stream responses** off for servers that only support complete JSON replies.

## Privacy

Keys are encrypted with a device-bound Android Keystore key. Existing plaintext settings are migrated only after encrypted storage succeeds. Chat history and prepared attachments are stored in private app storage; Android backup is disabled. Settings prevent screenshots/recent-app captures while keys are being edited. No prompt or key logging is added.

Messages and attachments are sent to the selected API server. History is not end-to-end encrypted; the app's local files rely on Android's storage protection. Sharing exports message/document text but excludes credentials and image files. Clearing app data or uninstalling removes local history and keys.

## Build & validate

Requirements: JDK 17, Android SDK 35 and build tools 35.0.0. Gradle 8.13 is pinned by the wrapper.

```sh
export ANDROID_HOME=/path/to/android/sdk
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk` (configure your own signing for distribution).

Tests use synthetic HTTP responses and cover all three protocols, streaming completion, cancellation, safe failures, model discovery, credential/header routing, document context, serialization and archive limits. They do not verify live account entitlements or third-party service availability.

## Boundaries

- Image/video generation, projects, scheduled jobs and live voice calls are not implemented. No nonfunctional controls advertise them.
- Up to four attachments per message, 8 MB per input file; image preparation reduces resolution to at most 1600px. Document text is limited to 120,000 characters, and spreadsheets to 400 rows per sheet.
- PDF support is limited to readable text in simple PDFs; scanned/encrypted/complex PDFs require conversion to text. Office extraction reads DOCX/PPTX/XLSX text, not full document formatting.
- Markdown is a lightweight renderer, not a complete CommonMark implementation or syntax highlighter.
- History search is local and in memory. Database-backed pagination, import, advanced performance profiling and a full TalkBack/device matrix remain follow-up work.

See [redesign audit and coverage](docs/REDESIGN.md) for implementation decisions and remaining verification.
