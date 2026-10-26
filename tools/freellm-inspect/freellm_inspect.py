#!/usr/bin/env python3
"""freellm-inspect: inspect the Gemini-FreeLLM Android app from the outside.

This is Zara's inspection bridge to the app. It reads what the app leaves behind
-- source code, backup exports, diagnostics reports -- and finds bugs, risky
patterns and improvements. It never talks to a running phone and never prints
secrets: API keys, providers.enc contents and message bodies are never echoed.

Usage:
    freellm_inspect.py code <repo-root> [--json]
    freellm_inspect.py backup <backup.zip> [--json]
    freellm_inspect.py diagnostics <diagnostics.json|report.txt> [--json]
    freellm_inspect.py report <repo-root> [backup.zip] [diagnostics] [--out report.md]

Exit code is 0 unless the input itself is unreadable (bad zip, bad json, ...).
Findings are reported, never fatal.
"""

import json
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path

SEVERITY_ORDER = {"critical": 0, "warning": 1, "info": 2}


def finding(severity, rule, where, message, suggestion=""):
    return {
        "severity": severity,
        "rule": rule,
        "where": where,
        "message": message,
        "suggestion": suggestion,
    }


def sort_findings(findings):
    return sorted(
        findings, key=lambda f: (SEVERITY_ORDER[f["severity"]], f["where"], f["rule"])
    )


# ---------------------------------------------------------------- code checks

# Patterns for secrets. Only the rule name and location are ever reported,
# never the matched value.
SECRET_PATTERNS = [
    ("openai-key", re.compile(r"sk-[A-Za-z0-9]{8,}")),
    ("google-key", re.compile(r"AIza[0-9A-Za-z_\-]{10,}")),
    ("experiential-key", re.compile(r"xpl_[A-Za-z0-9]+")),
    ("github-pat", re.compile(r"ghp_[A-Za-z0-9]+")),
    ("hardcoded-apikey", re.compile(r'apiKey\s*=\s*"[^"]+"')),
]

LAZY_RE = re.compile(r"\bitems(?:Indexed)?\s*\(")
COMPONENT_RE = re.compile(r"\b(get|set)ComponentEnabledSetting\s*\(")
PAINTER_LITERAL_RE = re.compile(r"painterResource\s*\(\s*R\.drawable\.(\w+)\s*\)")
PAINTER_VAR_RE = re.compile(r"painterResource\s*\(\s*(\w+)\s*\)")
TODO_RE = re.compile(r"\b(TODO|FIXME|XXX)\b[:\s]*(.*)")
BANG_RE = re.compile(r"!!")


def drawable_variables(text):
    """Map local variable names to the drawable they carry.

    Covers `val x = R.drawable.y` and Triple lists consumed by a destructured
    forEach, e.g. listOf(Triple("Gold", R.drawable.z, ...)).forEach { (_, d, _) -> }.
    Returns {varname: set(drawable_names)}.
    """
    mapping = {}
    for m in re.finditer(r"(\w+)\s*=\s*R\.drawable\.(\w+)", text):
        mapping.setdefault(m.group(1), set()).add(m.group(2))
    triples = re.findall(
        r"Triple\(\s*\"[^\"]*\"\s*,\s*R\.drawable\.(\w+)\s*,", text)
    destr = re.search(r"forEach\s*\{\s*\(([^)]*)\)\s*->", text)
    if triples and destr:
        vars_ = [v.strip() for v in destr.group(1).split(",")]
        if len(vars_) >= 2:
            for name in triples:
                mapping.setdefault(vars_[1], set()).add(name)
    return mapping


def kotlin_files(root):
    root = Path(root)
    for path in sorted(root.rglob("*.kt")):
        parts = path.parts
        if "build" in parts or ".git" in parts:
            continue
        yield path


def layer_list_drawables(repo_root):
    """Drawable names whose XML is a <layer-list> (adaptive-icon foregrounds)."""
    names = set()
    for resdir in (Path(repo_root) / "app" / "src" / "main" / "res").rglob("*.xml"):
        try:
            text = resdir.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if "<layer-list" in text:
            names.add(resdir.stem)
    return names


def function_spans(lines):
    """Yield (start_idx, end_idx) line ranges for top-level-ish `fun` bodies."""
    spans = []
    for i, line in enumerate(lines):
        if re.search(r"\bfun\s+\w+", line):
            depth = 0
            started = False
            for j in range(i, len(lines)):
                depth += lines[j].count("{") - lines[j].count("}")
                if "{" in lines[j]:
                    started = True
                if started and depth <= 0:
                    spans.append((i, j))
                    break
            else:
                spans.append((i, len(lines) - 1))
    return spans


def enclosing_function(spans, idx):
    for start, end in spans:
        if start <= idx <= end:
            return (start, end)
    return None


def check_code(repo_root):
    findings = []
    layerlists = layer_list_drawables(repo_root)
    for path in kotlin_files(repo_root):
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        lines = text.splitlines()
        spans = function_spans(lines)
        var_drawables = drawable_variables(text)
        rel = str(path.relative_to(repo_root)) if str(path).startswith(str(repo_root)) else str(path)
        in_test = "src/test" in rel or "src/androidTest" in rel
        for idx, line in enumerate(lines):
            where = f"{rel}:{idx + 1}"
            # Lazy lists: keys must be unique per item, never a shared provider.
            for m in LAZY_RE.finditer(line):
                window = "\n".join(lines[idx:idx + 5])
                key_m = re.search(r"key\s*=\s*\{([^}]*)\}", window)
                if key_m:
                    key_expr = key_m.group(1)
                    if ".provider" in key_expr and ".id" not in key_expr:
                        findings.append(finding(
                            "critical", "lazy-key-provider", where,
                            "Lazy list keyed by provider, not by item id. Two items sharing "
                            "a provider produce duplicate keys and crash while scrolling.",
                            "Key by a stable per-item id, e.g. key = { \"profile/${it.id}\" }."))
                else:
                    findings.append(finding(
                        "warning", "lazy-no-key", where,
                        "Lazy list without an explicit key. Reordering or duplicate items "
                        "can corrupt state or crash.",
                        "Add key = { <stable unique id> }."))
            # Icon previews must be bitmaps, not layer-lists (2.2.3 crash class).
            # Handles both painterResource(R.drawable.x) and painterResource(var)
            # where var carries a drawable through a Triple/destructured loop.
            bad_drawables = set()
            for m in PAINTER_LITERAL_RE.finditer(line):
                if m.group(1) in layerlists:
                    bad_drawables.add(m.group(1))
            for m in PAINTER_VAR_RE.finditer(line):
                for name in var_drawables.get(m.group(1), set()):
                    if name in layerlists:
                        bad_drawables.add(name)
            for name in sorted(bad_drawables):
                findings.append(finding(
                    "critical", "painter-layer-list", where,
                    f"painterResource loads layer-list drawable '{name}'. "
                    "Layer-list drawables crash Compose image loading; this crashed "
                    "the Appearance screen in 2.2.3.",
                    "Preview the raw bitmap instead (ic_logo / ic_logo_glow / ic_logo_blue)."))
            # Component toggles must be guarded: a missing alias must not crash Settings.
            if COMPONENT_RE.search(line):
                span = enclosing_function(spans, idx)
                body = "\n".join(lines[span[0]:span[1] + 1]) if span else ""
                if "try {" not in body and "runCatching" not in body:
                    findings.append(finding(
                        "warning", "component-no-guard", where,
                        "PackageManager component call without a try/catch. A missing "
                        "component throws IllegalArgumentException.",
                        "Wrap in try/catch and fall back to the default icon."))
            if not in_test:
                for rule, pattern in SECRET_PATTERNS:
                    if pattern.search(line):
                        findings.append(finding(
                            "critical", "secret-candidate", where,
                            f"Possible hardcoded secret ({rule}). Value redacted, never shown.",
                            "Move it to the encrypted providers store or a build secret."))
                        break
            todo_m = TODO_RE.search(line)
            if todo_m:
                findings.append(finding(
                    "info", "todo", where,
                    f"{todo_m.group(1)}: {todo_m.group(2).strip()[:100]}"))
        bangs = len(BANG_RE.findall(text))
        if bangs >= 10 and not in_test:
            findings.append(finding(
                "info", "bang-heavy", rel,
                f"{bangs} uses of !! in this file. Each one is a potential NPE crash.",
                "Replace with safe calls or explicit null handling where it matters."))
    return sort_findings(findings)


# ---------------------------------------------------------------- backup

def check_backup(zip_path):
    findings = []
    try:
        zf = zipfile.ZipFile(zip_path)
    except (zipfile.BadZipFile, OSError) as e:
        return None, [finding("critical", "backup-unreadable", str(zip_path),
                              f"Could not open backup: {e}")]
    names = set(zf.namelist())
    if "manifest.json" not in names or "conversations.json" not in names:
        return None, [finding("critical", "backup-invalid", str(zip_path),
                              "Not a FreeLLM backup: manifest.json or conversations.json missing.")]
    manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
    if manifest.get("version") != 1:
        findings.append(finding("warning", "backup-version", "manifest.json",
                                f"Backup version {manifest.get('version')} is not the expected 1."))
    if "providers.enc" in names:
        info = zf.getinfo("providers.enc")
        findings.append(finding("info", "credentials-present", "providers.enc",
                                f"Encrypted credentials present ({info.file_size} bytes). "
                                "Contents are never read or printed."))
    else:
        findings.append(finding("info", "credentials-absent", "providers.enc",
                                "No encrypted credentials in this backup; keys must be re-entered."))
    try:
        history = json.loads(zf.read("conversations.json").decode("utf-8"))
        chats = history.get("chats", [])
    except (ValueError, KeyError) as e:
        return None, [finding("critical", "backup-invalid", "conversations.json",
                              f"Chat history is not valid JSON: {e}")]
    stats = {"chats": len(chats), "messages": 0, "attachments": 0,
             "empty_chats": 0, "interrupted": 0, "empty_messages": 0}
    seen_ids = Counter()
    referenced = set()
    for chat in chats:
        seen_ids[chat.get("id")] += 1
        messages = chat.get("messages", [])
        stats["messages"] += len(messages)
        if not messages:
            stats["empty_chats"] += 1
            findings.append(finding("info", "empty-chat", f"chat {chat.get('id')}",
                                    f"Chat '{chat.get('title', 'New chat')}' has no messages."))
        for m in messages:
            if m.get("interrupted"):
                stats["interrupted"] += 1
            if not (m.get("text") or "").strip():
                stats["empty_messages"] += 1
        for att in chat.get("attachments", []):
            stats["attachments"] += 1
            if att.get("name"):
                referenced.add(att["name"])
        for m in messages:
            for att in m.get("attachments", []):
                stats["attachments"] += 1
                if att.get("name"):
                    referenced.add(att["name"])
    for chat_id, count in seen_ids.items():
        if count > 1:
            findings.append(finding("warning", "duplicate-chat-id", f"chat {chat_id}",
                                    f"Chat id appears {count} times. Duplicate ids corrupt "
                                    "history restores and list keys."))
    zip_attachments = {n[len("attachments/"):] for n in names if n.startswith("attachments/")}
    for orphan in sorted(zip_attachments - referenced):
        findings.append(finding("info", "orphan-attachment", f"attachments/{orphan}",
                                "Attachment file in the backup is not referenced by any chat."))
    for missing in sorted(referenced - zip_attachments):
        findings.append(finding("warning", "missing-attachment", missing,
                                "Chat references an attachment that is not in the backup."))
    if stats["interrupted"]:
        findings.append(finding("warning", "interrupted-messages", "conversations.json",
                                f"{stats['interrupted']} messages were interrupted mid-reply. "
                                "Check for generation or connectivity failures around them."))
    stats["zip_attachments"] = len(zip_attachments)
    return stats, sort_findings(findings)


# ---------------------------------------------------------------- diagnostics

DATE_RE = re.compile(
    r"^(\w{3} \w{3} \d{1,2} [\d:]+ [A-Z]+[+-]\d{2}:\d{2} \d{4}) · (.+?) · (.*)$")


def parse_diagnostics_file(path):
    text = Path(path).read_text(encoding="utf-8", errors="ignore").strip()
    if text.startswith("["):
        events = json.loads(text)
        return [{
            "time": e.get("time", 0), "category": e.get("category", ""),
            "provider": e.get("provider", ""), "summary": e.get("summary", ""),
            "details": e.get("details", ""),
        } for e in events]
    if text.startswith("{"):
        obj = json.loads(text)
        return [obj] if "category" in obj else []
    # Report text: header line, then blank-line separated entries.
    entries = []
    blocks = [b.strip() for b in text.split("\n\n") if b.strip()]
    for block in blocks:
        lines = block.splitlines()
        if not lines or lines[0].startswith("FreeLLM AI"):
            lines = lines[1:]
            if not lines:
                continue
        m = DATE_RE.match(lines[0])
        if not m:
            continue
        entries.append({
            "time": 0, "raw_date": m.group(1), "category": m.group(2).strip(),
            "provider": m.group(3).strip(),
            "summary": lines[1] if len(lines) > 1 else "",
            "details": "\n".join(lines[2:]),
        })
    return entries


def check_diagnostics(path):
    try:
        events = parse_diagnostics_file(path)
    except (ValueError, OSError) as e:
        return None, [finding("critical", "diagnostics-unreadable", str(path),
                              f"Could not parse diagnostics: {e}")]
    stats = {"events": len(events),
             "crashes": sum(1 for e in events if e["category"] == "Crash")}
    findings = []
    crash_groups = Counter()
    error_groups = Counter()
    for e in events:
        details = e["details"]
        first_frame = ""
        if details:
            frames = [l for l in details.splitlines()[1:] if l.strip()]
            first_frame = frames[0] if frames else ""
        if e["category"] == "Crash":
            exc = details.splitlines()[0] if details else "Unknown"
            crash_groups[(exc, first_frame)] += 1
        else:
            error_groups[(e["category"], e["provider"], e["summary"])] += 1
    for (exc, frame), count in crash_groups.most_common():
        findings.append(finding(
            "critical" if count >= 2 else "warning", "repeat-crash",
            f"{exc} @ {frame}" if frame else exc,
            f"Crashed {count} time(s). Top frame: {frame or 'unknown'}.",
            "Fix the top frame first; it is the shared cause of the whole group."))
    for (category, provider, summary), count in error_groups.most_common(10):
        findings.append(finding(
            "warning" if count >= 3 else "info", "repeat-error",
            f"{category} · {provider}",
            f"{summary} (x{count})"))
    return stats, sort_findings(findings)


# ---------------------------------------------------------------- output

def render_text(title, stats, findings):
    out = [f"== {title} =="]
    if stats:
        out.append("Stats: " + ", ".join(f"{k}={v}" for k, v in stats.items()))
    if not findings:
        out.append("No findings.")
    for f in findings:
        line = f"[{f['severity'].upper()}] {f['rule']} — {f['where']}: {f['message']}"
        if f["suggestion"]:
            line += f" Suggestion: {f['suggestion']}"
        out.append(line)
    return "\n".join(out)


def render_markdown(sections):
    out = ["# FreeLLM inspection report", ""]
    for title, stats, findings in sections:
        out.append(f"## {title}")
        out.append("")
        if stats:
            out.append("Stats: " + ", ".join(f"{k}={v}" for k, v in stats.items()))
            out.append("")
        if not findings:
            out.append("No findings.")
        for f in findings:
            out.append(f"- **[{f['severity']}] {f['rule']}** `{f['where']}`: {f['message']}"
                       + (f" Suggestion: {f['suggestion']}" if f["suggestion"] else ""))
        out.append("")
    out.append("## What this cannot see")
    out.append("")
    out.append("Live UI state, on-screen behaviour, performance and battery use are not "
               "visible from code, backups or diagnostics. A crash that only happens on "
               "one device still needs a screen recording or a fresh diagnostics export.")
    return "\n".join(out)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd, args = argv[1], argv[2:]
    want_json = "--json" in args
    args = [a for a in args if a != "--json"]

    if cmd == "code" and len(args) == 1:
        findings = check_code(args[0])
        print(json.dumps(findings, indent=2) if want_json
              else render_text("code", {}, findings))
        return 0
    if cmd == "backup" and len(args) == 1:
        stats, findings = check_backup(args[0])
        print(json.dumps({"stats": stats, "findings": findings}, indent=2) if want_json
              else render_text("backup", stats or {}, findings))
        return 0 if stats is not None else 1
    if cmd == "diagnostics" and len(args) == 1:
        stats, findings = check_diagnostics(args[0])
        print(json.dumps({"stats": stats, "findings": findings}, indent=2) if want_json
              else render_text("diagnostics", stats or {}, findings))
        return 0 if stats is not None else 1
    if cmd == "report" and 1 <= len(args) <= 3:
        sections = [("code", {}, check_code(args[0]))]
        if len(args) >= 2:
            stats, findings = check_backup(args[1])
            sections.append(("backup", stats or {}, findings))
        if len(args) >= 3:
            stats, findings = check_diagnostics(args[2])
            sections.append(("diagnostics", stats or {}, findings))
        out = render_markdown(sections)
        if "--out" in argv:
            Path(argv[argv.index("--out") + 1]).write_text(out, encoding="utf-8")
            print(f"Report written.")
        else:
            print(out)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
