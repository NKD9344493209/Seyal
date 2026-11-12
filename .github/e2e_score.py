#!/usr/bin/env python3
"""Scores the end-to-end run: each command has things that must be true of how it went.

python3 e2e_score.py index.json sessions/ timings.tsv e2e-expect.txt > e2e-score.md

e2e-expect.txt: one line per command, "command text => check; check; …". Checks:
  done              the task ended DONE
  tool:NAME         NAME was called and worked
  tool:A|B          one of them was
  no:NAME           NAME was never called successfully
  says:REGEX        the final result matches (case-insensitive)
  asks              the user was asked something (ask_user)
  noai              no AI call was needed
  under:SECONDS     it took less than this
  nothing           (for group messages) no skill ran
A command with no line in the file only has to end DONE.
"""
import json, os, re, sys


def load(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return default


def main():
    index = load(sys.argv[1], [])
    dirname, timings_path, expect_path = sys.argv[2], sys.argv[3], sys.argv[4]
    expect = {}
    if os.path.exists(expect_path):
        for line in open(expect_path, encoding="utf-8"):
            if "=>" in line and not line.startswith("#"):
                c, checks = line.rstrip("\n").split("=>", 1)
                expect[c.strip()] = [x.strip() for x in checks.split(";") if x.strip()]
    sessions = {}
    for e in index:
        s = load(os.path.join(dirname, e["id"] + ".json"), {})
        users = [" ".join(p.get("text", "") for p in m.get("parts", []) if p.get("type") == "text") for m in s.get("messages", []) if m.get("role") == "user"]
        for u in users:
            sessions.setdefault(u.split("\n")[0].strip(), (e, s))
    rows, passed = [], 0
    for line in open(timings_path, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) != 3:
            continue
        cmd, secs, state = parts
        checks = expect.get(cmd, ["done"])
        e, s = sessions.get(cmd, ({}, {}))
        states = {c["call_id"]: c for c in s.get("calls", [])}
        ok_tools = set()
        for m in s.get("messages", []):
            for p in m.get("parts", []):
                if p.get("type") == "call" and states.get(p.get("id"), {}).get("status") == "DONE":
                    ok_tools.add(p.get("name"))
        result = e.get("result") or ""
        failed = []
        for c in checks:
            if c == "done":
                good = state.startswith("DONE")
            elif c == "nothing":
                good = state.startswith("no skill ran")
            elif c == "asks":
                good = "ask_user" in ok_tools
            elif c == "noai":
                good = (s.get("llm_calls", 0) or 0) == 0
            elif c.startswith("tool:"):
                good = any(t in ok_tools for t in c[5:].split("|"))
            elif c.startswith("no:"):
                good = c[3:] not in ok_tools
            elif c.startswith("says:"):
                good = re.search(c[5:], result, re.I) is not None
            elif c.startswith("under:"):
                good = secs.isdigit() and int(secs) < int(c[6:])
            else:
                good = True
            if not good:
                failed.append(c)
        if not failed:
            passed += 1
        rows.append((cmd, secs, "PASS" if not failed else "FAIL: " + ", ".join(failed)))
    total = len(rows)
    print(f"# Score: {passed}/{total} passed\n")
    print("| Command | s | Verdict |")
    print("|---|---|---|")
    for cmd, secs, verdict in rows:
        print(f"| {cmd[:70]} | {secs} | {verdict} |")
    print()


if __name__ == "__main__":
    main()
