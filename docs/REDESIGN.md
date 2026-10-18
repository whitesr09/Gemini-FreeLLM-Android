# FreeLLM AI redesign audit

Reference: user supplied UI/UX master prompt (760 numbered points) and three mobile chat screenshots.

## Baseline inventory and issue matrix

| Component | Before | Implementation direction |
| --- | --- | --- |
| MainActivity | Composable owns ephemeral chat, coroutine, plaintext settings | Lifecycle ViewModel, atomic local history, encrypted credentials, cancellable jobs |
| ChatScreen | Single history, delete as navigation, generic bubbles | Searchable drawer, pinned/archived conversations, new chat, open assistant layout |
| Composer | Separate outlined field and button, no stop | Floating multiline composer, attachment tray, send/stop, speech input |
| Message content | Plain text, fixed Gemini label, no actions | Provider/model identity, Markdown, code copy, edit, retry, regenerate, share |
| SettingsDialog | Cramped dialog, only unified key and URL | Full-screen grouped settings, provider profiles, discovery/test, theme previews |
| AppTheme | Dynamic colors override identity; window side effects in composition | Semantic violet/neutral light/dark/AMOLED schemes, tokens, SideEffect system bars |
| TextPrimitives | References missing LocalAppColors | Remove unused broken abstraction |
| Network | Hardcoded auto, blocking execute, errors can expose exception text | Three wire protocols, SSE, model selection, cancellation, sanitized errors |
| PDF parser | Broken Kotlin string literals, simplistic extraction | Bounded extraction with explicit unsupported/scanned-document failure |
| Office parser | Unbounded draining of non-XML ZIP entries | Enforce expanded byte limits for every entry |
| Tests | Fake references missing classes; no tests configured | Deterministic protocol/transport/serialization and parser checks |

## Scope and capability policy

The master document includes design principles and conditional backend capabilities. Apply the principles to shipped screens. Do not create nonfunctional Projects, Remote, Scheduled, image/video generation or subscription controls just to match reference screenshots. Model capabilities vary: attachments require a vision-capable model; document attachments are extracted locally. Native image/video generation needs a dedicated API integration and is tracked as a remaining capability, not represented as working UI.

## Master-prompt coverage by section

| Sections | Implementation / remaining work |
| --- | --- |
| A–C, B, N, O, W, X | Shared spacing, shape, touch and typography tokens; semantic neutral/violet surfaces; system/light/dark/AMOLED; one Material icon family. No bitmap branding or heavy blur. Full contrast and launcher-brand audit outstanding. |
| D–F, Y | Model picker, drawer, new chat, search, pin/rename/archive/delete, editable suggestions, persisted drafts/history. Modal back handling and preserved ViewModel on rotation. Two-pane tablet layout and database pagination remain. |
| G–I | Open assistant messages, restrained user surface, message timestamps, copy, edit, retry/regenerate; incremental SSE updates; stable list keys; follow/jump-to-latest; IME-safe composer and stop. Lightweight Markdown supports fenced code, headings, links, inline styling, lists/quotes and horizontal table text. Full CommonMark tables/nested layout remain. |
| J | Files prepared on IO dispatcher; 8 MB source cap; sampled image decoding; maximum four attachments; removable cards; bounded PDF/Office extraction and error feedback. Device memory profiling remains. |
| K–L | Image/video generation is not implemented by the existing backend/client. No false generation, media playback or save controls. Needs separate capability-specific API integration. |
| M, Z | Full-screen settings, per-provider profiles, direct/unified credentials, model catalog and connection probe, URL validation, remote HTTP notice, encrypted credentials, settings screenshot protection, sanitized errors, backup disabled and confirmed export. Stored keys remain masked. |
| P–R | Native drawer/sheet/dialog/ripple/focus behavior; no decorative loops; immediate copy feedback; no fake progress. Streaming text coalesced to at most 20 updates/sec. Native motion honors system animation settings. Custom animation/profiling not claimed. |
| S, U | 48dp controls, icon descriptions, headings, live error/status semantics, scalable text, width-constrained reading, scrollable settings and empty state. Full TalkBack, narrow/landscape/large-font matrix pending device availability. |
| T, V | Lifecycle ViewModel, per-request IDs, cancellable transport, off-main storage/parsing/image work, immutable state and lazy keyed lists, finite response/document sizes, distinct empty/loading/error/cancel states. No response can land in a different chat. Large database/power/jank profiling remains. |
| AA–AC | Build/unit/lint/release checks and protocol fixtures, explicit limitations and artifact reporting. Full 760-point completion is not claimed; conditional unsupported capabilities and empirical audits are identified here. |

## Motion and assets

Native Compose navigation, sheets, dialogs and touch feedback provide motion. No decorative infinite animation or bespoke bitmap assets were added. Functional Material icons cover history, new chat, model selection, file attachment, microphone, send/stop, copy, edit, retry, settings, search, pin/archive and deletion. System animation scaling applies to native motion.

## Additional correctness findings resolved

- Fixed OOXML namespace handling, XLSX shared-string path casing and rich-text string grouping.
- Replaced silent spreadsheet truncation with an explicit oversized-sheet error.
- Replaced API-33-only PDF IO and recursive literal matching with bounded API-26-safe scanning.
- Corrected long-reply scrolling to reach the bottom of the final item; retain reading position when switching chats.
- Added partial-response checkpoints, explicit backup/transfer exclusions, a launcher asset and password-keyboard configuration.
- Bound aggregate conversation request size and preserved interrupted replies instead of claiming completion.

## Build verification

Final required suite passed: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` with Java 17, Gradle 8.13, Kotlin 2.2.20, AGP 8.13 and SDK 35. All 27 unit tests passed. Lint reported 0 errors and 17 warnings: SDK/dependency upgrade suggestions, two launcher resource polish suggestions, and two intentional explicit preference-commit checks. The unsigned release package is not ready for store distribution until you configure signing.

The starting revision was also compiled from an isolated `git archive` checkout and failed on the pre-existing PDF syntax/API code and missing `LocalAppColors` reference. Early implementation lint errors were corrected and the final required suite passed.

## Device and visual QA status

Blocked in this sandbox. Hardware acceleration is unavailable (`/dev/kvm` absent). API 35 AOSP ATD software emulation eventually reported boot complete, but Android's watchdog killed `system_server` after a 64-second main-thread stall. APK installation then failed in Android system services. A restart with a timeout override was attempted; the emulator does not support that property override. The emulator and synthetic test API were stopped afterward.

No app launch, screenshot, TalkBack, font-scale, landscape, on-device attachment, process-recreation or performance pass is claimed. A synthetic HTTP fixture was prepared and its health check passed, but on-device integration did not run. Live provider access also remains unverified without real provider credentials.

| Visual/runtime check | Status |
| --- | --- |
| Small/normal/large phone and tablet layout | Requires a usable device |
| Light/dark/AMOLED, keyboard, landscape and large fonts | Implemented; visual verification outstanding |
| TalkBack and touch/focus audit | Semantics and targets implemented; device audit outstanding |
| Native streaming/stop/retry/chat-switch and process recreation | Transport tests passed; device flow outstanding |
| Image/PDF/Office attachment workflow | Parser tests passed; picker/preview device flow outstanding |
| Long conversation, frame timing, low-memory behavior | Bounds/lazy layout/off-main processing implemented; empirical profiling outstanding |
| Generated image/video flows | Not implemented; no unsupported controls exposed |

## Release artifacts

- Installable debug APK: `app/build/outputs/apk/debug/app-debug.apk` (also published in task Outputs as `FreeLLM-AI-2.0-debug.apk`).
- Unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Version 2.0, code 3, Android 8.0/API 26 minimum, target API 35.
- Published debug artifact matches the tested build byte-for-byte and passes APK Signature Scheme v2 verification.
