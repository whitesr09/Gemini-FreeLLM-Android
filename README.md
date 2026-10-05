# Gemini FreeLLM Android

A modern, lightweight Android chat client for a self-hosted [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi) server.

**Android app → FreeLLMAPI → configured provider (for example Google AI Studio/Gemini)**

## Features

- Clean Material 3 interface with light, dark, and system themes
- Dynamic Android 12+ colors when available
- Responsive chat layout with keyboard/navigation-bar handling
- Safe, non-blocking network requests
- Useful connection and HTTP error messages
- Reuses a single OkHttp client with sensible timeouts/retry behavior
- API key and server URL are stored locally on the device
- No API keys are committed to the repository
- GitHub Actions builds a debug APK automatically

## Local FreeLLMAPI setup

Default Base URL:

`http://127.0.0.1:3001/v1`

Enter the **FreeLLMAPI Unified API Key** in Settings. Do not put your Google AI Studio key into this app; keep provider keys inside FreeLLMAPI.

## Security note

The app stores the Unified API key locally for convenience. This repository intentionally contains no real API keys. For a public production release, use a secure Android credential store and preferably put the API behind HTTPS/authentication rather than exposing an unsecured local HTTP endpoint.

## Build

Pushes to `main` trigger GitHub Actions. The generated debug APK is uploaded as the `Gemini-FreeLLM-debug` workflow artifact.
