# SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
# SPDX-License-Identifier: MPL-2.0

"""End-to-end tests: the plugin zip installed into the released OIE engine, on a real database.

    python3 e2e/run.py --db postgres --zip package/target/simple-channel-history-<version>.zip \\
        --tarball oie_unix_4_6_0.tar.gz --work /tmp/sch-e2e-1

Databases: derby, postgres, mysql, sqlserver (jTDS driver), sqlserver-ms (Microsoft driver), oracle.
Needs Java 17 (JAVA_HOME or --java-home) for the engine and Docker for every database but Derby.
--work must not exist yet; the run leaves each engine and its logs there.

Scenarios: fresh (the full history checks plus the OIE #296 record), one upgrade per entry in
upgrade-sources.txt, uninstall, and rbac (the release in rbac-source.txt installed alongside, with
restricted roles). Each downloaded zip is checked against its pinned SHA-256.
Exit status 1 if any check failed.
"""

import argparse
import hashlib
import os
import sys
import time
import traceback
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import scenarios  # noqa: E402
from databases import KINDS, Database  # noqa: E402
from engine import Engine, unpack  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def sources(name):
    """The lines of a sources file: (version, SHA-256, URL) each."""
    found = []
    with open(os.path.join(HERE, name), encoding="utf-8") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                version, digest, url = line.split()
                found.append((version, digest, url))
    return found


def download(url, digest, dest):
    with urllib.request.urlopen(url, timeout=300) as resp:
        data = resp.read()
    got = hashlib.sha256(data).hexdigest()
    if got != digest:
        raise RuntimeError(f"{url}: SHA-256 {got} does not match the pinned {digest}")
    with open(dest, "wb") as f:
        f.write(data)
    return dest


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", required=True, choices=KINDS)
    parser.add_argument("--zip", required=True, help="the plugin zip under test")
    parser.add_argument("--tarball", required=True, help="the OIE distribution, oie_unix_<version>.tar.gz")
    parser.add_argument("--work", required=True, help="a directory that does not exist yet")
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--scenarios", default="all", help="comma separated: fresh, upgrade-<version>, uninstall")
    args = parser.parse_args()

    os.makedirs(args.work)
    plan = [("fresh", scenarios.fresh, {})]
    os.makedirs(os.path.join(args.work, "downloads"))
    for version, digest, url in sources("upgrade-sources.txt"):
        plan.append((f"upgrade-{version}", scenarios.upgrade, {"arg": "old_zip", "digest": digest, "url": url,
                                                               "file": f"simple-channel-history-{version}.zip"}))
    plan.append(("uninstall", scenarios.uninstall, {}))
    for version, digest, url in sources("rbac-source.txt"):
        plan.append(("rbac", scenarios.rbac, {"arg": "rbac_zip", "digest": digest, "url": url,
                                              "file": f"rbac-{version}.zip"}))
    if args.scenarios != "all":
        wanted = set(args.scenarios.split(","))
        plan = [p for p in plan if p[0] in wanted]

    pristine = unpack(args.tarball, os.path.join(args.work, "pristine"))
    db = Database(args.db)
    reports = []
    started = time.time()
    try:
        db.start()
        for name, fn, extra in plan:
            report = scenarios.Report(name)
            reports.append(report)
            print(f"==== {name} on {args.db}", flush=True)
            engine = None
            try:
                kwargs = {"new_zip": os.path.abspath(args.zip)}
                if "url" in extra:
                    kwargs[extra["arg"]] = download(extra["url"], extra["digest"], os.path.join(
                        args.work, "downloads", extra["file"]))
                dbname = "sch_" + name.replace("-", "_").replace(".", "_")
                settings = db.create(dbname)
                engine = Engine(pristine, os.path.join(args.work, name, "oie"), args.java_home)
                engine.configure(settings)
                fn(report, engine, db, dbname, **kwargs)
            except Exception as e:  # a scenario that dies is a failure, and the next one still runs
                traceback.print_exc()
                report.check("scenario ran to the end", False, f"{type(e).__name__}: {e}")
            finally:
                if engine:
                    engine.stop()
    finally:
        db.stop()

    failed = sum(len(r.failed) for r in reports)
    lines = [f"## simple-channel-history e2e: {args.db}", "",
             "| Scenario | Passed | Failed |", "|---|---|---|"]
    lines += [f"| {r.scenario} | {r.passed} | {len(r.failed)} |" for r in reports]
    for r in reports:
        lines += [f"- FAIL [{r.scenario}] {name}" for name in r.failed]
        lines += [f"- NOTE [{r.scenario}] {note}" for note in r.notes]
    lines.append(f"\n{int(time.time() - started)} s")
    print("\n".join(lines), flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
