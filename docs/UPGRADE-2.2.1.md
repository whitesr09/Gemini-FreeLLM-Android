# FreeLLM AI 2.2.1 (7): one global Auto switch

## Use it

Tap the model name in chat, open **Auto · all providers**, and turn on **Auto**. No individual provider or model selection is required. Auto discovers configured providers' catalogs, ranks eligible models, and sends short availability checks until one responds (at most six per check). The same switch turns Auto off and restores the saved manual model. API checks can incur normal provider charges.

The combined, searchable pool shows discovered and manually configured model IDs, provider names, last-check status/time, measured response time and cooldowns. Provider inclusion controls are optional. Manual model selection remains available in a separate tab. The header says **Auto · all providers** while the global mode is active; each reply identifies the model actually used.

## Fixes and recovery

- Wait briefly for catalog discovery before the first Auto message; an unavailable configured ID no longer prevents discovery of alternatives.
- A model's generic HTTP429 rate limit does not disable its entire provider. Other models remain eligible.
- HTTP403 model-permission failures are scoped to that model. HTTP401 credentials and explicit account-balance/quota failures temporarily exclude the provider.
- Preserve conversation history, attachments and agent instructions across fallback.
- If a stream fails after producing text, retain that partial reply under its original model and continue in a separate model-labeled reply. A continuation instruction asks the replacement to avoid repetition; model compliance is not guaranteed.
- Bound each operation to six candidates, with 400ms–2s backoff. Availability probes have a 15-second timeout. Chat requests allow 25 seconds for first text and then use the normal stream timeout.
- Stop cancels generation and pending retries. Turning Auto off cancels an active availability check. Canceled work is not recorded as model failure.
- Invalidate stale catalog results after connection changes; clear canceled catalog loading indicators.
- Reuse one routing implementation for probes and chat fallback; remove the duplicate bulk-check flow.

## Ranking and limits

Ranking uses recent successful access, favorites, task-name hints and measured latency. It is a heuristic, not a universal model-quality benchmark. Every configured provider can contribute discovered chat candidates; unsupported endpoints/capabilities still produce honest failures. Image conversations use only models explicitly configured as vision-capable.

The app does not know future free-tier expiration dates. It reacts when the API reports quota/balance/rate errors. “Usable” means a recent request succeeded; remaining quota can still be unknown. Checks and sending can share data with different enabled providers and can incur charges. Exhausting the retry budget produces a clear error rather than an endless loop; the user can retry after correcting settings or waiting for cooldowns.

The design was recorded before implementing the clarified single-switch behavior in `docs/AUTO-MODE-DESIGN.md`. Device refresh-rate performance, hardware Keystore and live account entitlements require separate verification; synthetic tests cannot establish them.
