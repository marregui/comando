#!/usr/bin/env python3
"""PostToolUse Mermaid check.

After a Write or Edit of a Markdown file, render every ```mermaid block with mmdc.
A block that fails to parse blocks the turn with the parser's message, so the
diagram is fixed before anyone reads the file. A file with no Mermaid costs one read.
"""
import glob
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

PARSE_ERROR = re.compile(r"Parse error|Lexical error|Syntax error|Expecting ")
BLOCK = re.compile(r"^```mermaid[^\n]*\n(.*?)^```", re.M | re.S)
SUFFIXES = (".md", ".markdown", ".mmd")


def find_mmdc():
    found = shutil.which("mmdc")
    if found:
        return found
    candidates = sorted(glob.glob(os.path.expanduser("~/.nvm/versions/node/*/bin/mmdc")))
    return candidates[-1] if candidates else None


def blocks_of(path, text):
    if path.suffix == ".mmd":
        return [(1, text)]
    return [(text.count("\n", 0, m.start()) + 2, m.group(1)) for m in BLOCK.finditer(text)]


def render(mmdc, source, workdir):
    src = os.path.join(workdir, "block.mmd")
    with open(src, "w") as f:
        f.write(source)
    env = dict(os.environ, PATH=os.path.dirname(mmdc) + os.pathsep + os.environ.get("PATH", ""))
    proc = subprocess.run([mmdc, "-q", "-i", src, "-o", os.path.join(workdir, "block.svg")],
                          capture_output=True, text=True, timeout=45, env=env)
    if proc.returncode == 0:
        return None
    lines = [l for l in (proc.stderr + proc.stdout).splitlines()
             if l.strip() and not l.lstrip().startswith("at ")]
    return "\n".join(lines[:8])


def main():
    payload = json.load(sys.stdin)
    path = pathlib.Path(payload.get("tool_input", {}).get("file_path", ""))
    if path.suffix.lower() not in SUFFIXES or not path.is_file():
        return
    text = path.read_text(errors="replace")
    blocks = blocks_of(path, text)
    if not blocks:
        return
    mmdc = find_mmdc()
    if mmdc is None:
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": f"{path} has Mermaid blocks but mmdc is not installed; "
                                 "they were not checked (npm i -g @mermaid-js/mermaid-cli)."}}))
        return
    failures, unchecked = [], []
    with tempfile.TemporaryDirectory() as workdir:
        for line, source in blocks:
            try:
                error = render(mmdc, source, workdir)
            except subprocess.TimeoutExpired:
                error = "mmdc timed out after 45 s"
            if error and PARSE_ERROR.search(error):
                failures.append(f"block starting at line {line}:\n{error}")
            elif error:
                unchecked.append(f"block starting at line {line}: {error.splitlines()[0]}")
    if failures:
        print(json.dumps({
            "decision": "block",
            "reason": f"Mermaid parse failure in {path} — fix the diagram and re-check:\n\n"
                      + "\n\n".join(failures)}))
    elif unchecked:
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": f"Mermaid in {path} could not be checked (mmdc failed, not a parse error):\n"
                                 + "\n".join(unchecked)}}))


if __name__ == "__main__":
    main()
