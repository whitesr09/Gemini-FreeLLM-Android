# FreeLLM AI for Android

A lightweight Android client for a self-hosted [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi) gateway.

**Android app → FreeLLMAPI → configured provider**

## Current build

- Application ID: `com.nshd.geminifreellm`
- Version: `2.0` (`versionCode 3`)
- Min Android: API 26
- Target/compile: API 35
- Kotlin + Jetpack Compose + Room + OkHttp
- Light, dark, AMOLED and system themes
- Android Keystore-backed API-key storage
- Streaming and non-streaming OpenAI-compatible chat
- Local chat persistence
- Image/PDF/document attachment processing with bounded file handling
- Image/video generation when the configured FreeLLMAPI server advertises/supports those capabilities
- Connection diagnostics and capability test
- Debug and unsigned release artifacts in CI; signed release artifacts when repository signing secrets are configured

## Server configuration

The app currently defaults to the project’s HTTPS-hosted FreeLLMAPI endpoint:

`https://nshd-freellm-api.onrender.com/v1`

You can replace it with your own FreeLLMAPI base URL in Settings.

For local development, HTTP may be used explicitly (for example `http://127.0.0.1:3001/v1`), but production credentials should not be sent over cleartext HTTP.

The app sends prompts and supported attachment-derived content to the configured FreeLLMAPI server. **“Free” does not mean private**: the server operator controls the upstream provider configuration and can determine how requests are processed. Do not enter credentials or sensitive material unless you trust the configured server.

Provider keys remain server-side; the Android app uses the FreeLLMAPI unified key.

## Build

The GitHub Actions workflow runs:

1. lint
2. unit tests
3. debug APK build
4. release APK build
5. release AAB build
6. artifact validation/upload

Signed release artifacts are built only when all four signing secrets are present:

- `ANDROID_SIGNING_KEYSTORE_B64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

Never commit a keystore or signing credentials.

## AI repair workflow

`.github/workflows/ai-auto-repair.yml` is intentionally proposal-only and read-only. It can collect failed workflow metadata and produce a diagnostic report, but it cannot write repository files, expose secrets, merge code, or publish a release.

That boundary is deliberate: repository contents and CI logs are untrusted input and should never be allowed to redefine automation permissions.

## Backend contract

FreeLLMAPI exposes OpenAI-compatible `/v1/chat/completions`, `/v1/models`, `/v1/images/generations`, and `/v1/videos/generations` surfaces. Video generation is server-side/provider-dependent and can take up to five minutes; the client therefore treats unsupported media capabilities as runtime server conditions rather than pretending every model supports them.

See the upstream API documentation:
https://github.com/tashfeenahmed/freellmapi/blob/main/docs/en/api/01-rest-api.md
