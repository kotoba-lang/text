#!/usr/bin/env python3
"""Measurements for the wiki-standards-crawl bot (world-standards corpus).

Decision-free, same contract as wiki_news_evidence.py: syncs the dedicated
worktree (~/.gftd/worktrees/wiki-standards-crawl, an app-hyakka checkout) to
origin/main, runs the repo's own growth-evidence collector (kbb +
wiki_growth_evidence.cljk), and reports standards-body coverage: which
standards bodies (ISO/IEC/IEEE/ITU/IETF...) are configured, which source
classes, and whether live-plane items land for standards. REFUSED banner on
any failure so the bot is told it is blind, never that the standards plane is
complete.

2026-09-13: collector is kbb cutover — wiki_growth_evidence.cljk via
`kbb --backend sci` with kotoba-lang/text on the classpath.
"""
import os
import re
import subprocess
import sys
import urllib.request

WORKTREE = os.environ.get(
    "HYAKKA_BOT_WORKTREE",
    os.path.expanduser("~/.gftd/worktrees/wiki-standards-crawl"))
KBB = os.environ.get("HYAKKA_KBB", "/opt/homebrew/bin/kbb")
TEXT_SRC = os.environ.get(
    "HYAKKA_TEXT_SRC",
    "~/github/com-junkawasaki/orgs/kotoba-lang/text/src")
DAYS = os.environ.get("HYAKKA_EVIDENCE_DAYS", "14")
WIKI_ROOT = os.environ.get("HYAKKA_WIKI_ROOT", "https://wiki.yataverse.com")

CORPUS = "world-standards"
BODIES = ["iso", "iec", "ieee", "itu", "ietf", "ansi", "jis", "astm", "ul"]


def refuse(why):
    print("REFUSED — no evidence was gathered this run.")
    print(why)
    print()
    print("Do not propose anything. A standards proposal built on an unread "
          "tree is a proposal built on nothing. Report this refusal and stop.")
    sys.exit(0)


def git(*args):
    return subprocess.run(("git", "-C", WORKTREE) + args,
                          capture_output=True, text=True, timeout=300)


def main():
    if not (os.path.isdir(os.path.join(WORKTREE, ".git"))
            or os.path.exists(os.path.join(WORKTREE, ".git"))):
        refuse(f"no git worktree at {WORKTREE}")
    fetch = git("fetch", "--quiet", "origin", "main")
    if fetch.returncode != 0:
        refuse(f"git fetch failed: {fetch.stderr.strip()[:400]}")
    reset = git("checkout", "--quiet", "--detach", "FETCH_HEAD")
    if reset.returncode != 0:
        refuse(f"could not move to origin/main: {reset.stderr.strip()[:400]}")
    head = git("rev-parse", "--short", "HEAD").stdout.strip()
    collector = os.path.join(WORKTREE, "scripts", "wiki_growth_evidence.cljk")
    if not os.path.exists(collector):
        refuse(f"collector missing on main checkout: {collector}")
    if not os.path.isdir(TEXT_SRC):
        refuse(f"kotoba-lang/text src missing: {TEXT_SRC}")
    proc = subprocess.run(
        [KBB, "--backend", "sci", "--classpath", f"src:{TEXT_SRC}",
         collector, "--summary", "--root", ".", "--days", DAYS],
        cwd=WORKTREE, capture_output=True, text=True, timeout=600)
    if proc.returncode == 2:
        refuse("the evidence collector refused:\n" + proc.stderr.strip()[:800])
    if proc.returncode != 0:
        refuse(f"the evidence collector exited {proc.returncode}:\n"
               + (proc.stderr.strip() or proc.stdout.strip())[:800])
    if "SCANNED\t" not in proc.stdout:
        refuse("the collector produced no SCANNED line")

    # corpus-specific coverage: which standards bodies have configured sources
    cfg = os.path.join(WORKTREE, "config", "knowledge-ingest.edn")
    try:
        config = open(cfg, encoding="utf-8").read()
    except OSError as e:
        refuse(f"cannot read ingest config: {e}")
    ids = re.findall(r':id "([^"]+)"', config)
    by_body = {}
    for bid in BODIES:
        hits = [i for i in ids
                if bid in i.lower() and ("standard" in i.lower()
                                         or "spec" in i.lower()
                                         or "rfc" in i.lower()
                                         or bid == i.lower().split("-")[0])]
        by_body[bid] = len(hits)
    n_standard = sum(1 for i in ids
                     if any(k in i.lower() for k in
                            ("standard", "rfc", "spec-")))

    # live plane check: does a standards item exist on the live wiki?
    live = "unmeasured"
    try:
        req = urllib.request.Request(
            WIKI_ROOT + "/search?q=ISO%20standard",
            headers={"Accept": "text/html", "User-Agent": "wiki-standards-crawl/0.1"})
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode("utf-8", "replace")
        m = re.search(r"Search results \((\d+) results\)", body)
        live = m.group(1) if m else "unparseable"
    except Exception as e:  # noqa: BLE001 — live check is advisory only
        live = f"unmeasured ({type(e).__name__})"

    print(f"worktree\t{WORKTREE}")
    print(f"head\t{head}")
    print(proc.stdout.strip())
    print(f"## STANDARDS COVERAGE — corpus {CORPUS}")
    print(f"standards-sources-configured\t{n_standard}")
    for bid, n in by_body.items():
        print(f"body-{bid}-sources\t{n}")
    print(f"live-plane-iso-search-results\t{live}")


if __name__ == "__main__":
    main()
