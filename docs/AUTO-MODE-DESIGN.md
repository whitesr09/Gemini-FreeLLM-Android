# Global Auto mode: implementation plan

## Contract

A single persisted Auto toggle controls selection across the configured provider pool. Turning it on discovers catalogs and probes ranked eligible candidates until one responds. Users do not need to pick a provider or model. Turning it off cancels discovery/probing owned by Auto and returns to their saved manual model. During a chat request, Stop cancels generation, continuation and retries; model-setting changes apply to later messages.

The pool lists every discovered model plus manually configured IDs. Non-chat models, unconfigured credentials, user-excluded providers and recent failures remain visible with an explanation but are not selected. Capability information must not be invented. For images, only explicitly configured vision models are eligible unless reliable capability metadata is available.

## Selection

Rank by recent successful access, task suitability, user favorites, and measured response latency. Catalog pricing and account entitlement are distinct. Display unknown availability/quota honestly. A successful probe is evidence at that time, not a guarantee of future requests or objectively best quality.

Catalog fetches are cached and bounded. Probes are sequential, time-limited, cancelable and limited per run. No exhaustive probe storm is triggered on every message. Sending the actual chat rechecks availability and falls back when needed. Chat and agent instructions can reach any enabled provider; API usage can incur charges.

## Failure rules

- Rate limit: cool down that model and try a different candidate, including another model from the same provider. Do not assume all models share a quota bucket.
- Explicit account balance/key rejection: exclude that provider temporarily.
- Model permission/not found: exclude that model, not the whole provider.
- Network/no-first-token timeout: bounded fallback with backoff.
- Partial output followed by failure: keep the partial reply labeled incomplete; create a separate reply from the replacement model with history and a continuation instruction. Never splice unlabeled output from different models.
- Exhausted candidates/budget: show a clear error and keep draft/history recoverable.
- User cancellation: stop all work owned by that operation; never classify cancellation as a provider failure.

## State and ownership

Only one Auto availability check or chat generation runs at once. Scan results are scoped to the provider endpoint and credentials that produced them. Changing credentials invalidates cached checks. Catalog/scan completion must not overwrite new settings or a newer operation. Auto remains the global selection after a successful request; the actual provider/model is shown on each reply.

## Verification gates

Synthetic HTTP/UI regressions cover: combined catalogs, persisted toggle, discovery before the first send, unavailable configured ID with a usable catalog model, same-provider rate/permission fallback, cross-provider quota fallback, partial-output continuation, all-candidates failure, cancellation, and manual selection. Existing protocol/storage/canvas/agent tests remain required where affected. Run lint and debug/release builds; verify APK version, signature continuity and artifact hash.

Use evidence from tests and diagnostics for cleanup; do not remove working features speculatively. Physical-device frame timing and live account entitlement need separate verification. No claim of zero bugs or universal best-model selection.
