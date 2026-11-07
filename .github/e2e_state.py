"""Reads Seyal's runs.json (pulled from the emulator) for the end-to-end test.

python3 e2e_state.py count  runs.json   -> number of runs
python3 e2e_state.py newest runs.json   -> "<status> <waiting kinds>" of the newest run
python3 e2e_state.py report runs.json timings.tsv > e2e-report.md
"""
import json
import sys


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return []


def main():
    cmd, path = sys.argv[1], sys.argv[2]
    runs = load(path)  # newest first
    if cmd == "count":
        print(len(runs))
    elif cmd == "newest":
        if not runs:
            print("NONE -")
            return
        r = runs[0]
        waits = sorted({s.get("status") for s in r.get("steps", []) if s.get("status", "").startswith("WAITING")})
        running = any(s.get("status") in ("RUNNING", "PENDING") for s in r.get("steps", []))
        print(r.get("status", "?"), ",".join(waits) or "-", "busy" if running else "idle")
    elif cmd == "report":
        timings = {}
        if len(sys.argv) > 3:
            for line in open(sys.argv[3]):
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 3:
                    timings[parts[0]] = (parts[1], parts[2])
        print("# Seyal end-to-end report\n")
        print("| # | Command | Mode | Status | Total s | Plan s | Summary |")
        print("|---|---|---|---|---|---|---|")
        ordered = list(reversed(runs))
        for i, r in enumerate(ordered, 1):
            secs = timings.get(r.get("command", ""), ("?", ""))[0]
            plan_s = round((r.get('plan_ms') or 0) / 1000, 1)
            print(f"| {i} | {r.get('command','')[:60]} | {r.get('mode','')} | {r.get('status','')} | {secs} | {plan_s} | {(r.get('summary') or '').replace('|','/')} |")
        print()
        for i, r in enumerate(ordered, 1):
            print(f"## {i}. {r.get('command','')}")
            print(f"- mode **{r.get('mode')}**, status **{r.get('status')}**, title: {r.get('title')}")
            if r.get("question"):
                print(f"- question: {r['question']}  options: {r.get('options')}")
            for s in r.get("steps", []):
                detail = s.get("line") or s.get("error") or ""
                args = json.dumps(s.get("args", {}), ensure_ascii=False)[:220]
                print(f"  - `{s.get('id')}` **{s.get('tool')}** [{s.get('status')}] after {s.get('depends_on')} — {detail}")
                print(f"    - args: `{args}`")
            if r.get("final_text"):
                text = r["final_text"].strip().replace("\n", "\n    > ")
                print(f"- result:\n    > {text}")
            print()


if __name__ == "__main__":
    main()
