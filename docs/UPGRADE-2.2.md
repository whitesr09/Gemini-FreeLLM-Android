# FreeLLM AI 2.2 (6)

## Reply readability

All message surfaces now have 16dp of internal padding on every side. Assistant text, streamed text, attachments and the Thinking indicator stay inside the rounded corners. Code blocks and tables retain their own inner spacing.

## Auto model routing

Select **Auto · choose for me** from the chat model picker. Auto uses only configured profiles that you allow. Expand a provider to toggle **Allow in Auto**; choosing a specific model turns Auto off.

Auto refreshes configured catalogs when selected and at app startup, without blocking the first chat on discovery. It ranks the configured and discovered text models using recent successful requests, favorites, the selected model, and simple coding/speed name hints. Ranking is heuristic: catalog presence is not proof of access or a universal quality benchmark. Models that expose only other API protocols may fail and trigger fallback. With images, only each explicitly configured vision model is eligible because catalog IDs do not reliably prove vision support.

Each request tries at most six candidates, prioritizing a different provider before another model at the same provider. If there is no first text within 25 seconds, Auto cancels that request and tries another candidate. Once text starts, normal stream timeouts apply and Auto does not splice in another model's output. Interrupted output remains available to continue or retry. Stop cancels both generation and pending fallback.

Failures receive session-local cooldowns: rate limits 1 minute; key rejection or exhausted quota 15 minutes; other failures 30 seconds. Authentication, quota and rate-limit failures temporarily exclude the provider, while model-specific failures exclude that model. Credential/endpoint changes reset availability. Backoff between fallback attempts is 400ms–2s. No repeated probes are sent before every message. The actual model and fallback attempt are shown above the reply.

All enabled Auto providers may receive conversation history and agent context, and normal API charges apply. A canceled remote request can still be billed. Catalog pricing, last-request success and account quota remain distinct. Auto does not know free-tier expiration dates or guarantee the objectively best model.

## Agent studio

Open **Chat options → Agent · skills & memory**.

- Persona: tone and role, up to 4,000 characters.
- Instructions: standing preferences, up to 8,000 characters.
- Memory: explicit facts/project context that you edit, up to 8,000 characters. No silent extraction or automatic storage of private facts.
- Skills: up to 20 named prompt skills, with per-skill enable/disable, edit and remove controls. Each skill holds up to 16,000 characters; active context is capped at 48,000 characters.
- Import text by pasting, choosing a UTF-8 file (including SKILL.md/JSON/YAML), or loading a direct HTTPS raw text link. Review the imported text before keeping the skill and saving the agent. Importing JSON/YAML preserves it as instructions; it does not install a platform-specific plugin.
- Binary files, oversized inputs, malformed UTF-8 and HTML pages are rejected. URL imports are bounded and cancelable, require HTTPS, send no provider credentials and do not follow redirects.
- The master switch pauses customization without deleting it. Back navigation confirms discarding unsaved changes. Saved configuration uses the existing encrypted settings store.
- Native OpenAI system messages, Gemini systemInstruction and Anthropic system fields carry the same context across manual model switches and Auto fallback.

This is a prompt-based assistant workspace. Skills cannot execute shell scripts, install packages, run tools, change app code or guarantee a model's compliance. Source-specific executable plugins, authenticated websites and repository/archive installation are not implemented; export their instructions as text instead.

## Canvas and motion

Canvas gains bounded UTF-8 file import with replacement confirmation, case-insensitive find/replace, literal replacement text, replacement confirmation, undo after replacement/import, a focus mode, and Add tests/Document AI actions. Undo now always captures the first edit, and backgrounding the app flushes pending canvas edits. File export and per-chat autosave remain available. AI actions prepare an editable chat draft.

Thinking animation reads animated state in the drawing phase, avoiding recomposition for every dot animation frame. The app requests the highest same-resolution display refresh rate up to 120Hz when battery saver is off. Android and the device retain final control. This does not promise 120fps: physical-device frame timing remains unverified.

## Validation boundaries

Routing/prompt/storage/import tests use synthetic fixtures. UI checks use Robolectric native graphics and a synthetic Keystore; these do not verify hardware Keystore, physical-device performance or live provider entitlements. See delivery notes for observed build/test results.
