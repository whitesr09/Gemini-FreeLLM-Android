# freellm-inspect

Zara's inspection bridge to the FreeLLM app. It reads what the app leaves
behind — source code, backup exports, diagnostics reports — and finds bugs,
risky patterns and improvements. It is **not** remote control: it cannot see
the live screen on a phone or press buttons in the app.

## What it checks

| Command | Input | Finds |
|---|---|---|
| `code <repo-root>` | Kotlin sources | Duplicate Lazy-list keys keyed by provider (the model-picker crash class), `painterResource` on layer-list drawables (the 2.2.3 Appearance crash class), unguarded PackageManager component calls, hardcoded secret candidates, TODOs, `!!` hotspots (app code only) |
| `backup <backup.zip>` | App backup export (Settings → Backup & Restore → Export) | Empty chats, duplicate chat ids, interrupted messages, orphan/missing attachments, credential presence (contents never read) |
| `diagnostics <file>` | Diagnostics JSON or the Copy/Share report text | Repeated crashes grouped by exception + top frame, repeated provider errors |
| `report <repo-root> [backup.zip] [diagnostics]` | Everything above | One Markdown report |

## Privacy rules (enforced in code)

- `providers.enc` is never opened. Only its presence/size is reported.
- Secret findings show the file, line and rule only. Values are never printed.
- Message bodies are never printed — only counts and chat titles.

## Usage

```sh
python3 tools/freellm-inspect/freellm_inspect.py code .
python3 tools/freellm-inspect/freellm_inspect.py backup ~/Downloads/FreeLLM-backup-*.zip
python3 tools/freellm-inspect/freellm_inspect.py diagnostics diagnostics.txt
python3 tools/freellm-inspect/freellm_inspect.py report . backup.zip diagnostics.txt --out report.md
```

Add `--json` to any command for machine-readable output. Python 3.8+,
standard library only — no installs.

## Optional: run it in CI

This repo's workflows can only be edited by the owner (the GitHub App lacks
the workflows permission), so this step is manual. Add a job that runs the
`report` command and uploads `report.md` as an artifact:

```yaml
inspect:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - run: python3 tools/freellm-inspect/freellm_inspect.py report . --out report.md
    - uses: actions/upload-artifact@v4
      with:
        name: inspect-report
        path: report.md
```

## How Zara uses it

Send her a backup export (zip) and/or a diagnostics copy, or just say
"inspect the app" and she runs the code checks against the repo herself.
The report tells her what to fix, in severity order, with a suggestion per
finding.
