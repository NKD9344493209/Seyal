"""Reads Seyal's session files (pulled from the emulator) for the end-to-end test.

python3 e2e_state.py count  index.json                      -> number of top-level sessions
python3 e2e_state.py newest index.json sessions/            -> "<status> <waiting kinds> busy|idle <id>" of the newest top-level session
python3 e2e_state.py report index.json sessions/ timings.tsv > e2e-report.md
"""
import json
import os
import sys


def load(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return default


def top_level(index):
    return [e for e in index if not str(e.get("source", "")).startswith("child")]


def session(dirname, sid):
    return load(os.path.join(dirname, f"{sid}.json"), {})


def main():
    cmd = sys.argv[1]
    index = load(sys.argv[2], [])  # newest first
    tops = top_level(index)
    if cmd == "count":
        print(len(tops))
    elif cmd == "newest":
        if not tops:
            print("NONE - idle -")
            return
        e = tops[0]
        s = session(sys.argv[3], e["id"])
        calls = s.get("calls", [])
        waits = sorted({c.get("status") for c in calls if str(c.get("status", "")).startswith("WAITING")})
        busy = s.get("status") == "RUNNING"
        print(e.get("status", "?"), ",".join(waits) or "-", "busy" if busy else "idle", e["id"])
    elif cmd == "anywaiting":
        print("yes" if any(e.get("status") == "WAITING" for e in index[:12]) else "no")
    elif cmd == "calls":
        # AI calls made so far in the newest top-level session (to see a follow-up being worked on)
        print(session(sys.argv[3], tops[0]["id"]).get("llm_calls", 0) if tops else 0)
    elif cmd == "report":
        dirname = sys.argv[3]
        timings = {}
        if len(sys.argv) > 4 and os.path.exists(sys.argv[4]):
            for line in open(sys.argv[4]):
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 3:
                    timings[parts[0][:60]] = (parts[1], parts[2])
        ordered = list(reversed(tops))
        print("# Seyal end-to-end report\n")
        print("| # | Command | Status | Total s | Steps | AI calls | AI s | Result |")
        print("|---|---|---|---|---|---|---|---|")
        details = []
        for i, e in enumerate(ordered, 1):
            s = session(dirname, e["id"])
            cmd_text = e.get("title", "")
            secs = timings.get(cmd_text[:60], ("?", ""))[0]
            ai_s = round((s.get("llm_ms") or 0) / 1000, 1)
            result = (e.get("result") or "").replace("|", "/").replace("\n", " ")[:90]
            print(f"| {i} | {cmd_text[:60]} | {e.get('status')} | {secs} | {s.get('steps', 0)} | {s.get('llm_calls', 0)} | {ai_s} | {result} |")
            details.append((i, e, s))
        print()
        for i, e, s in details:
            print(f"## {i}. {e.get('title','')}")
            print(f"- status **{e.get('status')}** · source {e.get('source')} · {s.get('steps',0)} steps · {s.get('llm_calls',0)} AI calls · {round((s.get('llm_ms') or 0)/1000,1)} s in AI · {s.get('tokens',0)} tokens")
            states = {c["call_id"]: c for c in s.get("calls", [])}
            n = 0
            for m in s.get("messages", []):
                if m.get("role") == "user":
                    t = " ".join(p.get("text", "") for p in m.get("parts", []) if p.get("type") == "text")
                    print(f"- **user:** {t[:300]}")
                elif m.get("role") == "assistant":
                    for p in m.get("parts", []):
                        if p.get("type") == "text" and p.get("text", "").strip():
                            txt = p["text"].strip().replace("\n", "\n    > ")
                            print(f"- assistant:\n    > {txt}")
                        elif p.get("type") == "call":
                            n += 1
                            st = states.get(p.get("id"), {})
                            detail = st.get("line") or st.get("error") or ""
                            ms = (st.get("ended_at", 0) or 0) - (st.get("started_at", 0) or 0)
                            args = json.dumps(p.get("args", {}), ensure_ascii=False)[:240]
                            print(f"  - {n}. **{p.get('name')}** [{st.get('status','?')}] {round(ms/1000,1) if ms > 0 else ''}s — {detail}")
                            print(f"    - args: `{args}`")
                elif m.get("role") == "tool":
                    for p in m.get("parts", []):
                        if p.get("is_error"):
                            print(f"    - error: {json.dumps(p.get('output', {}), ensure_ascii=False)[:200]}")
            if e.get("result"):
                print(f"- **result:** {e['result']}")
            print()


if __name__ == "__main__":
    main()
