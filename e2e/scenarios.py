# SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
# SPDX-License-Identifier: MPL-2.0

"""The scenarios. Each one gets its own new database and a freshly unpacked engine.

A check that fails is a FAIL and fails the run. A NOTE records behaviour without judging it:
the OIE #296 scenario.
"""

import hashlib
import os
import re
import time
import zipfile

import content as c
from databases import PLUGIN_TABLES
from oie import Api


class Report:
    def __init__(self, scenario):
        self.scenario = scenario
        self.passed = 0
        self.failed = []
        self.notes = []

    def check(self, name, ok, detail=""):
        if ok:
            self.passed += 1
        else:
            self.failed.append(name)
        print(f"{'PASS' if ok else 'FAIL'} [{self.scenario}] {name}" + (f"  [{detail}]" if detail and not ok else ""),
              flush=True)
        return ok

    def note(self, name, value):
        self.notes.append(f"{name}: {value}")
        print(f"NOTE [{self.scenario}] {name}: {value}", flush=True)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def check_server_jar(r, engine, zip_path):
    """The installed server jar must be the zip's, and the only one (an upgrade must not leave the old one)."""
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if re.search(r"simple-channel-history-server-[^/]*\.jar$", n))
        want = sha256(z.read(name))
    files = engine.extension_files(c.PLUGIN_PATH) or []
    jars = [f for f in files if f.startswith("simple-channel-history-server-")]
    got = None
    if len(jars) == 1:
        with open(os.path.join(engine.home, "extensions", c.PLUGIN_PATH, jars[0]), "rb") as f:
            got = sha256(f.read())
    r.check(f"installed server jar is {os.path.basename(name)} and the only one",
            jars == [os.path.basename(name)] and got == want, f"found {jars}")


def install_and_restart(r, engine, api, zip_path):
    ok, detail = c.install(api, zip_path)
    r.check(f"install {os.path.basename(zip_path)}", ok, detail)
    api = engine.restart()
    check_server_jar(r, engine, zip_path)
    return api


def check_logs(r, engine):
    errors = engine.plugin_log_lines("ERROR")
    r.check("no ERROR lines from the plugin in mirth.log", not errors, " | ".join(errors[:3]))
    # simple-channel-history #27: Derby and Oracle logged a migrator warning on every startup after the first.
    warnings = engine.plugin_log_lines("WARN")
    r.check("no WARN lines from the plugin in mirth.log", not warnings, " | ".join(warnings[:3]))


def check_tables_created_once(r, engine):
    """The migrator runs on every startup. Its create scripts must run on the first one only."""
    lines = engine.plugin_log_lines("INFO")
    for description in ("History tables", "Deleted item tables"):
        count = sum(1 for line in lines if f"HistoryMigrator: {description} created" in line)
        r.check(f"'{description} created' logged once across {engine.starts} engine starts", count == 1,
                f"logged {count} times")


def check_revert_event(r, api, label, kind, item_id, revision):
    """The plugin's event for a revert names the revision reverted to, the user who reverted, and their address."""
    key = "channel" if kind == "channel" else "Code Template ID"
    # The engine writes events from a queue, so the revert's event can arrive a moment after the revert returns.
    deadline = time.time() + 15
    while True:
        event = next((e for e in c.plugin_events(api) if "Reverted to revision" in e and item_id in (e.get(key) or "")), None)
        if event is not None or time.time() > deadline:
            break
        time.sleep(1)
    me = c.current_user_id(api)
    r.check(f"{label}'s revert event names revision {revision}, user {me} and an address",
            event is not None and event["Reverted to revision"] == revision and event["userId"] == me
            and bool(event["ipAddress"]), str(event))


# ---------- the full history checks

def history_setup(r, api):
    st = {}
    # Channel A: three saves (one ~120 KB), revert to the first, prune to the newest two. Each save
    # carries settings and tags the way the channel editor sends them; the revert must keep them.
    cid, m = c.new_id(), c.markers("A")
    settings = {"enabled": False, "prune_days": (30, 15),
                "tags": [(c.new_id(), "SCH e2e Production"), (c.new_id(), "SCH e2e Lab")]}
    # The fixture's archiveEnabled=false differs from the engine default (true), so a copy that dropped it would show.
    expected = ("false", ("30", "15", "false", "false"), ["SCH e2e Lab", "SCH e2e Production"])
    st["A"] = {"id": cid, "m": m, "settings": expected}
    r.check("create channel A", c.create_channel(api, cid, "SCH e2e A", m["v1"], settings))
    for k in ("big", "v3"):
        r.check(f"update channel A ({k})", c.update_channel(api, cid, "SCH e2e A", m[k], settings))
    # Control for the revert check below: the saves really set them.
    r.check("channel A is disabled, prunes at 30/15 days and has two tags",
            c.channel_settings(api, cid) == expected, str(c.channel_settings(api, cid)))
    ids = c.history_ids(api, "channel", cid)
    r.check("channel A has 3 history rows, newest first", len(ids) == 3 and
            [int(i) for i in ids] == sorted((int(i) for i in ids), reverse=True), str(ids))
    if len(ids) == 3:
        for hid, k in zip(ids, ("v3", "big", "v1")):
            text = c.content(api, "channel", cid, hid)
            r.check(f"channel A content of the {k} revision", m[k] in text, f"length {len(text)}")
        first = c.history(api, "channel", cid)[-1]["revision"]
        r.check("revert channel A to its first revision", c.revert(api, "channel", cid, ids[-1]))
        check_revert_event(r, api, "channel A", "channel", cid, first)
        after = c.channel_settings(api, cid)
        r.check("the revert kept channel A disabled, its pruning and its tags", after == expected,
                f"{expected} became {after}")
        desc = c.channel_description(api, cid)
        r.check("channel A description is v1 plus the revert note",
                desc.startswith(m["v1"]) and "BEGIN REVERT HISTORY" in desc, desc[:120])
        ids = c.history_ids(api, "channel", cid)
        r.check("channel A has 4 history rows after the revert", len(ids) == 4, str(ids))
        r.check("prune channel A to the newest 2 deletes 2", c.prune(api, "channel", cid, ids[1]) == 2)
        r.check("channel A keeps the newest 2 rows", c.history_ids(api, "channel", cid) == ids[:2])
        st["A"]["kept"] = ids[:2]

    # Channel E: the empty case. After a revert it must still have no pruning settings and no tags.
    eid = c.new_id()
    empty = {"enabled": True, "prune_days": None, "tags": []}
    r.check("create channel E with no pruning and no tags", c.create_channel(api, eid, "SCH e2e E", "E v1", empty))
    r.check("update channel E", c.update_channel(api, eid, "SCH e2e E", "E v2", empty))
    r.check("channel E is enabled, with no pruning and no tags", c.channel_settings(api, eid) == ("true", None, []),
            str(c.channel_settings(api, eid)))
    e_ids = c.history_ids(api, "channel", eid)
    r.check("revert channel E to its first revision", len(e_ids) == 2 and c.revert(api, "channel", eid, e_ids[-1]))
    r.check("the revert left channel E enabled, with no pruning and no tags",
            c.channel_settings(api, eid) == ("true", None, []), str(c.channel_settings(api, eid)))

    # A prune's cut-off must be one of the channel's own rows. Before 2.8.1 any number was taken: channel
    # E's newer row deleted channel A's history up to it, and a large number deleted all of it.
    if "kept" in st["A"]:
        for label, wrong in (("channel E's revision", e_ids[0] if e_ids else "0"), ("a number past every row", "999999999")):
            status, text = c.prune_status(api, "channel", cid, wrong)
            r.check(f"prune channel A at {label} is refused", status == 400, f"HTTP {status}: {text[:150]}")
        r.check("channel A still has its newest 2 rows after the refused prunes",
                c.history_ids(api, "channel", cid) == st["A"]["kept"], str(c.history_ids(api, "channel", cid)))

    # Code template T in library L, saved through the bulk update like the Administrators do.
    lid, tid, m = c.new_id(), c.new_id(), c.markers("T")
    st["L"], st["T"] = lid, {"id": tid, "m": m}
    ok, detail = c.bulk_update(api, c.libraries(api) + [c.new_library(lid, "SCH e2e")])
    r.check("create library L", ok, detail)
    for k in ("v1", "big", "v3"):
        ok, detail = c.save_template(api, lid, tid, "schE2eT", m[k])
        r.check(f"save code template T ({k}) through the bulk update", ok, detail)
    ids = c.history_ids(api, "template", tid)
    r.check("code template T has 3 history rows", len(ids) == 3, str(ids))
    if len(ids) == 3:
        for hid, k in zip(ids, ("v3", "big", "v1")):
            text = c.content(api, "template", tid, hid)
            r.check(f"code template T content of the {k} revision", m[k] in text, f"length {len(text)}")
        first = c.history(api, "template", tid)[-1]["revision"]
        r.check("revert code template T to its first revision", c.revert(api, "template", tid, ids[-1]))
        check_revert_event(r, api, "code template T", "template", tid, first)
        ids = c.history_ids(api, "template", tid)
        r.check("code template T has 4 history rows after the revert", len(ids) == 4, str(ids))
        newest = c.content(api, "template", tid, ids[0])
        r.check("code template T's newest revision is the v1 code", m["v1"] in newest and m["v3"] not in newest)
        r.check("prune code template T to the newest 2 deletes 2", c.prune(api, "template", tid, ids[1]) == 2)
        r.check("code template T keeps the newest 2 rows", c.history_ids(api, "template", tid) == ids[:2])
        st["T"]["kept"] = ids[:2]
        status, text = c.prune_status(api, "template", tid, "999999999")
        r.check("prune code template T at a number past every row is refused", status == 400, f"HTTP {status}: {text[:150]}")
        r.check("code template T still has its newest 2 rows after the refused prune",
                c.history_ids(api, "template", tid) == ids[:2])

    # Code template P through the single-template PUT, for comparison, then filed into L.
    pid = c.new_id()
    st["P"] = pid
    for k in ("v1", "v3"):
        r.check(f"save code template P ({k}) through PUT /codeTemplates/{{id}}", c.put_template(api, pid, "schE2eP", k))
    libs = c.libraries(api)
    c.add_template_ref(c.find_library(libs, lid), pid)
    ok, detail = c.bulk_update(api, libs)
    r.check("file code template P into library L", ok, detail)
    r.check("code template P has 2 history rows (the PUT path records history too)",
            len(c.history_ids(api, "template", pid)) == 2)

    # Channels B and C deleted the two ways the Administrators delete; template U removed through the bulk update.
    st["B"] = {"id": c.new_id(), "name": "SCH e2e B ñ日本"}
    st["C"] = {"id": c.new_id(), "name": "SCH e2e C ñ日本"}
    for key in ("B", "C"):
        r.check(f"create channel {key}", c.create_channel(api, st[key]["id"], st[key]["name"], "to be deleted"))
    c.delete_channel(api, st["B"]["id"])
    c.delete_channels(api, [st["C"]["id"]])
    for key in ("B", "C"):
        r.check(f"channel {key}'s history is removed with the channel", c.history_ids(api, "channel", st[key]["id"]) == [])
    uid = c.new_id()
    st["U"] = {"id": uid, "name": "schE2eU"}
    ok, detail = c.save_template(api, lid, uid, "schE2eU", "U")
    r.check("save code template U", ok, detail)
    ok, detail = c.remove_template(api, lid, uid)
    r.check("remove code template U through the bulk update", ok, detail)
    r.check("code template U is gone from the engine", not c.template_exists(api, uid))
    r.check("code template U's history is removed with it", c.history_ids(api, "template", uid) == [])
    return st


def history_verify(r, api, st):
    for key, kind in (("A", "channel"), ("T", "template")):
        item = st.get(key, {})
        if "kept" not in item:
            r.check(f"{kind} {key} was set up", False, "setup did not get far enough")
            continue
        ids = c.history_ids(api, kind, item["id"])
        r.check(f"{kind} {key} still has its 2 rows after the restart", ids == item["kept"], f"{ids} vs {item['kept']}")
        for hid, k in zip(ids, ("v1", "v3")):
            r.check(f"{kind} {key} content after the restart ({k})", item["m"][k] in c.content(api, kind, item["id"], hid))
    if "A" in st:
        after = c.channel_settings(api, st["A"]["id"])
        r.check("channel A still disabled, pruning and tagged after the restart", after == st["A"]["settings"],
                f"{st['A']['settings']} became {after}")
        desc = c.channel_description(api, st["A"]["id"])
        r.check("channel A description after the restart is v1 plus the revert note",
                desc.startswith(st["A"]["m"]["v1"]) and "BEGIN REVERT HISTORY" in desc)
    if "P" in st:
        r.check("code template P still has 2 rows after the restart", len(c.history_ids(api, "template", st["P"])) == 2)
    if "A" in st:
        check_json_list(r, api, "channel A's history", "/history", {"channelId": st["A"]["id"]},
                        "com.diridium.RevisionInfo", ("hash", "revision", "committerName", "time"), 2)
    # Before the purges below: two deleted channels (an array) and one deleted template (a single object).
    check_json_list(r, api, "deleted channels", "/deletedChannels", None, "com.diridium.DeletedItemInfo",
                    ("id", "itemId", "name", "deletedBy", "dateDeleted"), 2)
    check_json_list(r, api, "deleted code templates", "/deletedCodeTemplates", None, "com.diridium.DeletedItemInfo",
                    ("id", "itemId", "name", "deletedBy", "dateDeleted"), 1)
    for key, kind in (("B", "channel"), ("C", "channel"), ("U", "template")):
        if key not in st:
            continue
        item = st[key]
        rows = [row for row in c.deleted(api, kind) if row.get("itemId") == item["id"]]
        r.check(f"deleted {kind} {key} is listed after the restart",
                len(rows) == 1 and rows[0].get("name") == item["name"] and rows[0].get("deletedBy") == "admin", str(rows))
        if len(rows) != 1:
            continue
        snapshot = c.deleted_content(api, kind, rows[0]["id"])
        r.check(f"deleted {kind} {key} snapshot holds its XML", item["id"] in snapshot and item["name"] in snapshot,
                f"length {len(snapshot)}")
        r.check(f"purge deleted {kind} {key}", c.purge(api, kind, rows[0]["id"]))
        r.check(f"deleted {kind} {key} is gone after the purge",
                all(row.get("itemId") != item["id"] for row in c.deleted(api, kind)))
        r.check(f"purging deleted {kind} {key} again reports false (nothing was purged)",
                not c.purge(api, kind, rows[0]["id"]))


def check_json_list(r, api, label, path, query, class_name, fields, count):
    """The web administrator reads the plugin's lists as JSON and takes the rows from under the class
    name (makeApi in webadmin/web/schi-core.js). XStream sends one row as an object, several as an array."""
    status, body = c.json_list(api, path, query)
    holder = (body or {}).get("list")
    rows = holder.get(class_name) if isinstance(holder, dict) else None
    rows = [rows] if isinstance(rows, dict) else rows
    ok = (status == 200 and isinstance(rows, list) and len(rows) == count
          and all(all(field in row for field in fields) for row in rows))
    r.check(f"{label} as JSON: {count} row(s) under {class_name}, each with {', '.join(fields)}", ok,
            f"HTTP {status}: {str(body)[:200]}")


def check_rows(r, db, dbname, expected):
    counts = db.plugin_row_counts(dbname)
    if counts is None:
        r.note("rows in the database", "not read for Derby (embedded in the engine)")
        return
    r.check(f"rows in the {db.kind} database itself ({', '.join(PLUGIN_TABLES)}) = {expected}",
            counts == expected, f"got {counts}")


def issue_296(r, api):
    """OIE #296: a second Administrator with a stale code template view imports a channel that bundles a
    library. Swing sends every library it has cached plus the imported one, with override on and nothing
    marked removed (ChannelPanel import -> CodeTemplatePanel.attemptUpdate(..., true, ...)). The engine
    then deletes libraries created since that cache was loaded. Recorded, not judged."""
    stale = c.libraries(api)
    la, ta = c.new_id(), c.new_id()
    ok, detail = c.bulk_update(api, c.libraries(api) + [c.new_library(la, "SCH e2e 296 new")])
    r.check("296: another user creates library LA", ok, detail)
    for k in ("first", "second"):
        ok, detail = c.save_template(api, la, ta, "schE2e296A", k)
        r.check(f"296: and saves template TA in it ({k})", ok, detail)
    r.check("296: TA has 2 history rows", len(c.history_ids(api, "template", ta)) == 2)

    li, ti, chan = c.new_id(), c.new_id(), c.new_id()
    r.check("296: the stale Administrator imports channel D", c.create_channel(api, chan, "SCH e2e 296 D", "imported"))
    ok, detail = c.bulk_update(api, stale + [c.new_library(li, "SCH e2e 296 imported", [ti])],
                               updated=[c.template(ti, "schE2e296I", "imported")], override=True)
    r.check("296: and its bundled library is saved the way Swing saves it", ok, detail)

    r.note("296: library LA still on the engine", la in c.library_ids(api))
    r.note("296: template TA still on the engine", c.template_exists(api, ta))
    r.note("296: TA history rows the plugin still has", len(c.history_ids(api, "template", ta)))
    r.note("296: TA listed in Deleted Items", any(row.get("itemId") == ta for row in c.deleted(api, "template")))
    r.note("296: imported template TI history rows", len(c.history_ids(api, "template", ti)))


# ---------- scenarios

def fresh(r, engine, db, dbname, new_zip, **_):
    api = engine.start()
    api = install_and_restart(r, engine, api, new_zip)
    st = history_setup(r, api)
    api = engine.restart()
    history_verify(r, api, st)
    check_rows(r, db, dbname, [5, 4, 0, 0])   # channel A's 2 kept rows + channel E's 3; templates T and P, 2 each
    issue_296(r, api)
    check_tables_created_once(r, engine)
    check_logs(r, engine)


def upgrade(r, engine, db, dbname, new_zip, old_zip, **_):
    api = engine.start()
    api = install_and_restart(r, engine, api, old_zip)
    cid, m = c.new_id(), c.markers("UA")
    r.check("old version: create channel UA", c.create_channel(api, cid, "SCH e2e UA", m["v1"]))
    r.check("old version: update channel UA", c.update_channel(api, cid, "SCH e2e UA", m["big"]))
    lid, tid = c.new_id(), c.new_id()
    ok, detail = c.bulk_update(api, c.libraries(api) + [c.new_library(lid, "SCH e2e upgrade")])
    r.check("old version: create library", ok, detail)
    for k in ("v1", "v3"):
        ok, detail = c.save_template(api, lid, tid, "schE2eUT", k)
        r.check(f"old version: save code template UT ({k})", ok, detail)
    gone = c.new_id()
    r.check("old version: create channel UB", c.create_channel(api, gone, "SCH e2e UB", "to be deleted"))
    c.delete_channel(api, gone)
    before = {"channel": c.history_ids(api, "channel", cid), "template": c.history_ids(api, "template", tid)}
    r.check("old version: 2 channel and 2 template history rows", [len(v) for v in before.values()] == [2, 2], str(before))

    api = install_and_restart(r, engine, api, new_zip)
    r.check("after the upgrade: channel UA history unchanged", c.history_ids(api, "channel", cid) == before["channel"])
    r.check("after the upgrade: code template UT history unchanged", c.history_ids(api, "template", tid) == before["template"])
    ids = before["channel"]
    if len(ids) == 2:
        r.check("after the upgrade: channel UA contents readable",
                m["big"] in c.content(api, "channel", cid, ids[0]) and m["v1"] in c.content(api, "channel", cid, ids[1]))
    r.check("after the upgrade: deleted channel UB still listed",
            any(row.get("itemId") == gone for row in c.deleted(api, "channel")))
    r.check("after the upgrade: a new save is recorded", c.update_channel(api, cid, "SCH e2e UA", m["v3"])
            and len(c.history_ids(api, "channel", cid)) == 3)
    check_tables_created_once(r, engine)
    check_logs(r, engine)


def uninstall(r, engine, db, dbname, new_zip, **_):
    api = engine.start()
    api = install_and_restart(r, engine, api, new_zip)
    cid = c.new_id()
    r.check("create channel X", c.create_channel(api, cid, "SCH e2e X", "v1"))
    r.check("update channel X", c.update_channel(api, cid, "SCH e2e X", "v2"))
    gone = c.new_id()
    r.check("create channel Y", c.create_channel(api, gone, "SCH e2e Y", "to be deleted"))
    c.delete_channel(api, gone)
    r.check("channel X has 2 history rows", len(c.history_ids(api, "channel", cid)) == 2)
    # Control for "no deleted channels" after the reinstall: Y's snapshot is there before the uninstall.
    r.check("deleted channel Y is listed before the uninstall",
            any(row.get("itemId") == gone for row in c.deleted(api, "channel")))
    tables = db.plugin_table_count(dbname)
    if tables is not None:
        r.check("the plugin's 4 tables exist", tables == 4, f"found {tables}")
    else:
        r.note("table counts", "not read for Derby (embedded in the engine); the API checks still run")
    # Control for the 'gone' checks below: the same calls must see the plugin while it is installed.
    r.check("the plugin is listed before the uninstall", c.plugin_installed(api))
    r.check("its extension folder exists before the uninstall", engine.extension_files(c.PLUGIN_PATH) is not None)

    ok, detail = c.uninstall(api)
    r.check("uninstall the plugin", ok, detail)
    api = engine.restart()
    r.check("the plugin is gone after the restart", not c.plugin_installed(api))
    r.check("its extension folder is gone", engine.extension_files(c.PLUGIN_PATH) is None)
    tables = db.plugin_table_count(dbname)
    if tables is not None:
        r.check("uninstall dropped the plugin's tables", tables == 0, f"{tables} left")

    api = install_and_restart(r, engine, api, new_zip)
    tables = db.plugin_table_count(dbname)
    if tables is not None:
        r.check("reinstall created the 4 tables again", tables == 4, f"found {tables}")
    r.check("after reinstall: channel X has no history (it was dropped)", c.history_ids(api, "channel", cid) == [])
    r.check("after reinstall: no deleted channels", c.deleted(api, "channel") == [])
    r.check("after reinstall: a new save is recorded",
            c.update_channel(api, cid, "SCH e2e X", "v3") and len(c.history_ids(api, "channel", cid)) == 1)
    check_logs(r, engine)


def rbac(r, engine, db, dbname, new_zip, rbac_zip, **_):
    """rbac installed alongside. A role limited to some channels must see and handle only those channels'
    deleted snapshots. Admin is the control. Before 2.8.1 the deleted-channel endpoints ignored channel
    restrictions. What a role without "manage channels" gets is recorded as a NOTE, not checked."""
    api = engine.start()
    for z in (new_zip, rbac_zip):
        ok, detail = c.install(api, z)
        r.check(f"install {os.path.basename(z)}", ok, detail)
    api = engine.restart()
    check_server_jar(r, engine, new_zip)
    r.check("rbac is running", c.rbac_answers(api))

    # Live channels L1 (in the role) and L2 (not); deleted channels D1 (in the role) and D2 (not);
    # deleted code template DT.
    ids = {key: c.new_id() for key in ("L1", "L2", "D1", "D2")}
    for key, cid in ids.items():
        r.check(f"create channel {key}", c.create_channel(api, cid, f"SCH e2e rbac {key}", "rbac"))
    for key in ("D1", "D2"):
        c.delete_channel(api, ids[key])
    lid, tid = c.new_id(), c.new_id()
    ok, detail = c.bulk_update(api, c.libraries(api) + [c.new_library(lid, "SCH e2e rbac")])
    r.check("create a library", ok, detail)
    ok, detail = c.save_template(api, lid, tid, "schE2eRbacDT", "rbac")
    r.check("save code template DT", ok, detail)
    ok, detail = c.remove_template(api, lid, tid)
    r.check("remove code template DT", ok, detail)

    snapshot = {row["itemId"]: row["id"] for row in c.deleted(api, "channel")}
    r.check("admin sees the deleted channels D1 and D2", ids["D1"] in snapshot and ids["D2"] in snapshot, str(snapshot))
    r.check("admin sees the deleted code template DT", any(row.get("itemId") == tid for row in c.deleted(api, "template")))
    if ids["D1"] not in snapshot or ids["D2"] not in snapshot:
        return

    channel_role = c.create_role(api, "SCH e2e two channels", ["viewChannels", "manageChannels"], [ids["L1"], ids["D1"]])
    template_role = c.create_role(api, "SCH e2e code templates only", ["viewCodeTemplates", "manageCodeTemplates"])
    password = "Sch-e2e-" + c.new_id()[:12] + "!"
    users = {}
    for name, role in (("sch-e2e-channels", channel_role), ("sch-e2e-templates", template_role)):
        user_id = c.create_user(api, name, password)
        c.assign_role(api, user_id, role)
        users[name] = Api(engine.port, name, password)
        users[name].login(wait=60)
    restricted, templates_only = users["sch-e2e-channels"], users["sch-e2e-templates"]

    # Control: rbac's restriction is in force where the engine itself checks it (the history endpoints).
    r.check("restricted user: channel L1's history is readable", c.history_status(restricted, "channel", ids["L1"]) == 200)
    status = c.history_status(restricted, "channel", ids["L2"])
    r.check("restricted user: channel L2's history is refused", status == 403, f"HTTP {status}")

    status, rows = c.deleted_status(restricted, "channel")
    seen = {row.get("itemId") for row in rows or []}
    r.check("restricted user: Deleted Items lists D1 and not D2", status == 200 and ids["D1"] in seen
            and ids["D2"] not in seen, f"HTTP {status}: {seen}")
    status, text = c.deleted_content_status(restricted, "channel", snapshot[ids["D2"]])
    r.check("restricted user: D2's snapshot is refused", status == 403, f"HTTP {status}")
    status, text = c.deleted_content_status(restricted, "channel", 999999999)
    r.check("restricted user: a snapshot number that doesn't exist is refused", status == 403, f"HTTP {status}")
    status, text = c.purge_status(restricted, "channel", snapshot[ids["D2"]])
    r.check("restricted user: purging D2's snapshot is refused", status == 403, f"HTTP {status}")
    r.check("D2's snapshot is still there", ids["D2"] in {row.get("itemId") for row in c.deleted(api, "channel")})
    status, text = c.deleted_content_status(restricted, "channel", snapshot[ids["D1"]])
    r.check("restricted user: D1's snapshot is readable", status == 200 and ids["D1"] in text, f"HTTP {status}")
    status, text = c.purge_status(restricted, "channel", snapshot[ids["D1"]])
    r.check("restricted user: purging D1's snapshot works", status == 200 and "true" in text, f"HTTP {status}: {text[:100]}")

    # The plugin's endpoints declare the engine's permissions ("manage channels" here), but rbac 1.1.3 has no
    # mapping for another plugin's operations and allows any operation it doesn't know. So this role, which has
    # no channel permissions, still gets the list. Recorded, not judged: the change belongs in rbac.
    status, rows = c.deleted_status(templates_only, "channel")
    r.note("templates-only user (no channel permissions): the deleted-channel list's HTTP status", status)
    status, rows = c.deleted_status(templates_only, "template")
    r.check("templates-only user: the deleted code template DT is listed",
            status == 200 and any(row.get("itemId") == tid for row in rows or []), f"HTTP {status}")

    status, text = c.deleted_content_status(api, "channel", snapshot[ids["D2"]])
    r.check("admin still reads D2's snapshot", status == 200 and ids["D2"] in text, f"HTTP {status}")
    check_logs(r, engine)
