# FreeLLM AI 2.1 — improvement checklist

This release implements the changes below. These are individual improvements across larger features, not 140 independent major features. Validation results and remaining limits are listed at the end. No universal model access, free-tier entitlement, provider latency reduction or absence of bugs is promised.

## Chat and motion

1. Remove the duplicate visible “Generating…” status.
2. Replace the plain waiting text with a three-dot thinking wave.
3. Show elapsed waiting time after ten seconds.
4. Expose one stable “Thinking” accessibility announcement.
5. Keep animated dots out of the screen-reader text stream.
6. Respect Android's disabled-animation setting for thinking and text reveal.
7. Add a user-controlled Reduce motion preference.
8. Smooth small streamed text bursts with an 80 ms catch-up animation.
9. Display completed responses immediately, without an artificial typing delay.
10. Bypass character animation for large responses to limit repeated work.
11. Avoid splitting UTF-16 surrogate pairs during reveal.
12. Replace the redundant generation label with a small streaming cursor.
13. Detect user dragging separately from programmatic chat scrolling.
14. Resume automatic following when the user reaches the bottom.
15. Follow the latest response when sending a new turn.
16. Fade the jump-to-latest control in and out.
17. Add a compact conversation-spacing option.
18. Hide timestamps by default for quieter message controls.
19. Offer optional message timestamps in Settings.
20. Show total reply time with timestamps.
21. Record time to the first answer text for request timing.
22. Add optional haptic feedback on send.
23. Hide the footer disclaimer while the keyboard is visible to reclaim space.
24. Disable send for a blank draft with no attachments.
25. Show the latest message preview in history rows.
26. Show a draft preview when a conversation has unsent text.
27. Close the keyboard when starting a new chat from the app bar.
28. Render Markdown tables as columns and rows instead of raw pipe text.
29. Give Markdown tables distinct header and alternating row surfaces.
30. Allow wide tables to scroll horizontally.
31. Decode escaped pipes inside table cells.
32. Render Markdown horizontal rules as dividers.
33. Add line wrapping to code blocks.
34. Add an Open in canvas action to code blocks.

## Provider and model selection

35. Expand the chat model picker to nearly full height.
36. Give each provider an expandable section.
37. Expose configured and unconfigured provider sections in one place.
38. Load catalogs directly from a provider section.
39. Search model IDs and display names in the expanded section.
40. Show catalog model counts.
41. Select a model directly from chat.
42. Persist the selected model under its provider profile.
43. Preserve the current conversation and draft when switching models.
44. Mark the active provider.
45. Mark the active model.
46. Favorite individual models.
47. Persist favorites across restarts.
48. Sort favorite models before other models.
49. Filter a provider's list to favorites.
50. Enter a manual model ID without opening Settings.
51. Keep the configured model selectable even if absent from the catalog.
52. Refresh a catalog explicitly.
53. Cache catalogs in memory for five minutes.
54. Show catalog loading progress.
55. Show catalog fetch time.
56. Show actionable catalog errors with manual-entry fallback.
57. Open connection settings for the chosen provider.
58. Display advertised context-window sizes when returned.
59. Display advertised free/paid pricing when returned.
60. Label model access unverified until tested.
61. Record the last successful chat or access check per model.
62. Distinguish key/access rejection from quota exhaustion.
63. Distinguish temporary rate limits from balance/quota exhaustion.
64. Distinguish missing models/endpoints from other failures.
65. Timestamp access results instead of presenting them as live guarantees.
66. Offer a short model-access test, with cost/quota confirmation.
67. Exclude chat history from access-test prompts.
68. Prevent concurrent access checks.
69. Explain that free-tier expiry and remaining quota are unknown without provider data.
70. Add a dedicated DeepSeek connection preset.
71. Explain the difference between direct DeepSeek keys and gateway keys.
72. Add Mistral and xAI presets.
73. Add an Ollama/local preset and phone-versus-emulator connection guidance.
74. Normalize a pasted inference/catalog endpoint to its base URL.
75. Preserve custom gateway path prefixes when normalizing URLs.
76. Paginate Gemini model discovery.
77. Paginate catalogs using the Anthropic-style has_more/last_id contract.
78. Detect repeated catalog page cursors.
79. Bound catalog page and model counts.
80. Clear cached catalogs and access results when a key or endpoint changes.
81. Add opt-in reduced thinking for supported Gemini 2.5 Flash and Gemini 3 Flash/Pro model IDs.
82. Exclude Gemini thought parts from visible answer text.
83. Merge adjacent same-role turns for cross-provider conversation continuation.
84. Exclude stopped empty replies from future conversation context.
85. Complete a stream at its terminal event without waiting for socket closure.
86. Conflate queued streaming updates instead of launching one coroutine per update.
87. Debounce draft persistence while typing.
88. Serialize credential/settings writes to prevent overlapping saves.

## Coding canvas

89. Add a full-screen coding canvas from the chat menu.
90. Persist one canvas per conversation.
91. Open generated fenced code in the canvas.
92. Edit the canvas file name and language.
93. Edit code in a monospace text field with autocorrect disabled.
94. Save the canvas on close and after a short editing pause.
95. Add bounded, grouped undo and redo history.
96. Search and highlight matching code text with a match count.
97. Toggle code wrapping in the editor.
98. Copy code to the clipboard with confirmation.
99. Export code through Android's document picker.
100. Confirm destructive canvas clearing and support undo afterward.
101. Prepare Explain, Review, Fix and Optimize prompts for user review.
102. Keep generated code inert: the canvas does not execute downloaded code.

## Diagnostics and AI troubleshooting

103. Add an error log reachable from Settings.
104. Capture handled chat, catalog, model-access, media, attachment and storage failures.
105. Capture Java uncaught exceptions and preserve Android's crash handling.
106. Keep at most 100 events for seven days.
107. Store structured classifications and app stack locations without raw exception messages.
108. Exclude keys, chat text, attachments and raw server responses from diagnostics.
109. Filter all errors, crashes and other errors.
110. Expand individual technical details.
111. Copy or share a sanitized report.
112. Confirm deletion of local diagnostics.
113. Prepare a new AI diagnosis conversation from the newest 20 events.
114. Let the user review the diagnostic prompt before sending it.
115. State that AI suggestions require an app update to change installed code.

## Image and video creation

116. Add a Create screen from the chat menu.
117. Support compatible Images API generation requests.
118. Support Gemini inline image-generation responses.
119. Accept a separate media model ID without changing the chat model.
120. Confirm provider/model/cost before submitting a media prompt.
121. Keep existing chat history out of media prompts.
122. Decode bounded base64 image responses.
123. Download returned HTTPS image URLs without provider credentials or redirects.
124. Validate image readability before marking a creation ready.
125. Preview generated images locally.
126. Support compatible Videos API creation with multipart requests.
127. Poll asynchronous video status with visible progress.
128. Persist remote video IDs before polling.
129. Resume video checks after navigation or process restart.
130. Require the original provider endpoint when resuming a job.
131. Stop local waiting without claiming to cancel provider billing/jobs.
132. Bound polling duration and media download size.
133. Keep a local gallery of up to 30 creations.
134. Export images and videos through Android's document picker.
135. Play downloaded videos through an installed player with temporary URI access.
136. Confirm local creation deletion.
137. Restrict file sharing to the generated-media directory.
138. Validate remote job IDs before inserting them into API paths.
139. Serialize media-index writes.
140. Retain failed/paused jobs for inspection rather than silently resubmitting paid requests.

## Protocol references

- DeepSeek: https://api-docs.deepseek.com/
- Gemini thinking: https://ai.google.dev/gemini-api/docs/thinking
- Gemini images: https://ai.google.dev/gemini-api/docs/image-generation
- Images API: https://developers.openai.com/api/docs/guides/image-generation
- Videos API: https://developers.openai.com/api/docs/guides/video-generation

## Validation and limits

Validation completed for version **2.1 (5)**:

- **50 unique tests passed across focused runs:** 43 protocol, parsing, persistence and privacy tests; 7 Compose/Robolectric UI workflows. Unchanged passes were reused. The image workflow also passed with a JPEG fixture to verify actual-format export metadata.
- UI checks covered startup/drawer/version, provider picker, catalog model switching with draft preservation, thinking/stop, canvas editing, diagnostics-to-draft, and confirmed image generation using synthetic API responses.
- `lintDebug`: **0 errors, 18 warnings** (SDK/dependency update suggestions, existing launcher polish and preference style suggestions).
- Debug and unsigned release APK builds passed.
- Debug APK v2 signature verified; signer matches the previously delivered 2.0.1 APK for an in-place update.
- APK manifest confirms package `com.nshd.geminifreellm`, version2.1/code5. DEX inspection confirms the new feature labels and absence of the duplicate “Generating…” string and test-only Keystore implementation.
- Native JVM renders of the thinking state and history drawer were inspected. They use synthetic chat data and are not physical-device screenshots.

Early compilation and test-fixture failures were resolved, including the missing Compose opt-in, synthetic-provider constructor, screenshot capture method, test data isolation and ambiguous hidden-drawer selectors. Final selected checks have no remaining failures.

Real-device installation, hardware Keystore, TalkBack, font-scale/landscape/GPU/frame-time profiling and live provider/media accounts remain unverified. This sandbox has no `/dev/kvm`; its earlier software emulator failed in Android system services. The installed CodeRabbit emulate v0.0.1 catalog has no compatible inference or media adapter, so tests use MockWebServer plus JVM Android rendering.

A successful model catalog request does not verify inference access. Account quota and free-tier expiry generally are not in model catalogs. Availability badges reflect the most recent request in this app session; provider dashboards remain authoritative.

Media generation requires a compatible route, a media-capable model and account access. This release does not implement Gemini Veo jobs, editing/upload-based media workflows, or every gateway's proprietary media API. Video creation uses one 4-second 720×1280 request. Stopping local waiting does not undo a submitted provider request or charges. Canvas is an editor and AI prompt workspace, not an arbitrary-code execution environment.

There is no measured guarantee of faster provider inference or smooth frame timing on a real phone. Fast Flash mode trades reasoning for latency only on the explicitly supported model IDs. Robolectric UI tests use an in-memory test Keystore and cannot verify hardware-backed Android encryption, TalkBack, real keyboard/device behavior or GPU performance.
