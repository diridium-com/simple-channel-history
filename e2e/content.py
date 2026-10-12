# SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
# SPDX-License-Identifier: MPL-2.0

"""Test content and the calls that create it, the way the Administrators do.

Channels are saved with POST /channels and PUT /channels/{id}, as both Administrators do.
Code templates are saved and removed through POST /codeTemplateLibraries/_bulkUpdate, as both
Administrators do. That endpoint deletes every library the request leaves out, so every call
here sends the engine's full current library list.
"""

import json
import os
import uuid
import xml.etree.ElementTree as ET

from oie import ApiError

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
CHANNEL_FIXTURE = os.path.join(REPO, "client", "src", "test", "resources", "channel-for-diffing-version1.xml")
TEMPLATE_FIXTURE = os.path.join(HERE, "fixtures", "code-template.xml")
EXT = "/extensions/simple-channel-history"
PLUGIN_PATH = "simple-channel-history"


def new_id():
    return str(uuid.uuid4())


def markers(tag):
    """Unique, XML-safe text per revision. 'big' is about 120 KB with multi-byte characters: past
    Oracle's 32 KB string bind limit and MySQL's 64 KB TEXT limit."""
    return {
        "v1": f"SCHE2E-{tag}-v1 ñ日本",
        "big": f"SCHE2E-{tag}-big " + ("abcdefghij ñ日本 " * 8000),
        "v3": f"SCHE2E-{tag}-v3",
    }


def _set(parent, tag, text):
    el = parent.find(tag)
    if el is None:
        el = ET.SubElement(parent, tag)
    el.text = text
    return el


def _xml(el):
    return ET.tostring(el, encoding="unicode")


def _value(root):
    """The scalar in a <boolean>, <int> or <string> response."""
    return (root.text or "").strip() if root is not None else ""


def _rows(root):
    """A <list> of plugin objects as dicts of their child elements' text."""
    if root is None:
        return []
    return [{child.tag: child.text for child in item} for item in root]


# ---------- channels

def channel_xml(cid, name, description, settings=None):
    """The channel fixture. settings, when given, is sent the way the channel editor sends it, in
    exportData: {"enabled": bool, "prune_days": (metadata, content) or None, "tags": [(tag id, name)]}."""
    root = ET.parse(CHANNEL_FIXTURE).getroot()
    _set(root, "id", cid)
    _set(root, "name", name)
    _set(root, "description", description)
    if settings is not None:
        export = root.find("exportData")
        metadata = export.find("metadata")
        _set(metadata, "enabled", "true" if settings["enabled"] else "false")
        old = metadata.find("pruningSettings")
        if old is not None:
            metadata.remove(old)
        if settings["prune_days"] is not None:          # None: no pruning settings at all
            pruning = ET.SubElement(metadata, "pruningSettings")
            for tag, value in (("pruneMetaDataDays", settings["prune_days"][0]), ("pruneContentDays", settings["prune_days"][1]),
                               ("archiveEnabled", "false"), ("pruneErroredMessages", "false")):
                ET.SubElement(pruning, tag).text = str(value)
        old = export.find("channelTags")
        if old is not None:
            export.remove(old)
        tags = ET.SubElement(export, "channelTags")
        for tag_id, tag_name in settings["tags"]:
            tag = ET.SubElement(tags, "channelTag")
            ET.SubElement(tag, "id").text = tag_id
            ET.SubElement(tag, "name").text = tag_name
            ET.SubElement(ET.SubElement(tag, "channelIds"), "string").text = cid
            color = ET.SubElement(tag, "backgroundColor")
            for part, value in (("red", 40), ("green", 120), ("blue", 200), ("alpha", 255)):
                ET.SubElement(color, part).text = str(value)
    return _xml(root)


def channel_settings(api, cid):
    """The channel's settings and tags as the engine holds them:
    (enabled, (metadata days, content days, archive enabled, prune errored) or None, [tag names])."""
    enabled, prune = None, None
    for entry in api.xml("GET", "/server/channelMetadata"):
        if entry.findtext("string") == cid:
            metadata = [child for child in entry if child.tag != "string"][0]
            pruning = metadata.find("pruningSettings")
            enabled = metadata.findtext("enabled")
            prune = (tuple(pruning.findtext(f) for f in ("pruneMetaDataDays", "pruneContentDays", "archiveEnabled",
                                                          "pruneErroredMessages")) if pruning is not None else None)
    tags = sorted(tag.findtext("name") for tag in api.xml("GET", "/server/channelTags")
                  if cid in [s.text for s in tag.iter("string")])
    return enabled, prune, tags


def _boolean_json(status, text):
    # The channel create and update endpoints produce only JSON or plain text: {"boolean":true}.
    return status == 200 and text.replace(" ", "") == '{"boolean":true}'


def create_channel(api, cid, name, description, settings=None):
    return _boolean_json(*api.request("POST", "/channels", channel_xml(cid, name, description, settings),
                                      accept="application/json"))


def update_channel(api, cid, name, description, settings=None):
    return _boolean_json(*api.request("PUT", f"/channels/{cid}", channel_xml(cid, name, description, settings),
                                      accept="application/json", query={"override": "true"}))


def channel_description(api, cid):
    root = api.xml("GET", f"/channels/{cid}")
    return root.findtext("description") or ""


def delete_channel(api, cid):
    """Single delete, as the web Administrator does."""
    api.xml("DELETE", f"/channels/{cid}")


def delete_channels(api, cids):
    """Bulk delete, as the Swing Administrator does."""
    api.xml("DELETE", "/channels", query={"channelId": list(cids)})


# ---------- code templates and libraries

def template(tid, name, marker, revision=None):
    root = ET.parse(TEMPLATE_FIXTURE).getroot()
    _set(root, "id", tid)
    _set(root, "name", name)
    if revision is not None:
        _set(root, "revision", str(revision))
    code = root.find("properties/code")
    code.text = f"/* {marker} */\n{code.text}"
    return root


def template_revision(api, tid):
    status, text = api.request("GET", f"/codeTemplates/{tid}")
    if status != 200 or not text.strip():
        return None
    return int(ET.fromstring(text).findtext("revision"))


def template_exists(api, tid):
    status, text = api.request("GET", f"/codeTemplates/{tid}")
    return status == 200 and bool(text.strip())


def libraries(api):
    """The engine's current libraries, holding code template IDs only, as the Administrators keep them."""
    root = api.xml("GET", "/codeTemplateLibraries", query={"includeCodeTemplates": "false"})
    return list(root) if root is not None else []


def library_ids(api):
    return [lib.findtext("id") for lib in libraries(api)]


def new_library(lid, name, template_ids=()):
    lib = ET.Element("codeTemplateLibrary", {"version": "4.6.0"})
    for tag, text in (("id", lid), ("name", name), ("revision", "1"),
                      ("description", "simple-channel-history e2e"), ("includeNewChannels", "false")):
        ET.SubElement(lib, tag).text = text
    ET.SubElement(lib, "enabledChannelIds")
    ET.SubElement(lib, "disabledChannelIds")
    ET.SubElement(lib, "codeTemplates")
    for tid in template_ids:
        add_template_ref(lib, tid)
    return lib


def find_library(libs, lid):
    return next(lib for lib in libs if lib.findtext("id") == lid)


def add_template_ref(lib, tid):
    templates = lib.find("codeTemplates")
    if templates is None:
        templates = ET.SubElement(lib, "codeTemplates")
    ref = ET.SubElement(templates, "codeTemplate", {"version": "4.6.0"})
    ET.SubElement(ref, "id").text = tid


def remove_template_ref(lib, tid):
    templates = lib.find("codeTemplates")
    for ref in list(templates):
        if ref.findtext("id") == tid:
            templates.remove(ref)


def bulk_update(api, libs, updated=(), removed_library_ids=(), removed_template_ids=(), override=False):
    """POST /codeTemplateLibraries/_bulkUpdate. Returns (ok, detail)."""
    def as_list(elements):
        return "<list>" + "".join(_xml(e) for e in elements) + "</list>"

    def as_set(ids):
        return "<set>" + "".join(f"<string>{i}</string>" for i in ids) + "</set>"

    parts = [("libraries", None, "application/xml", as_list(libs)),
             ("removedLibraryIds", None, "application/xml", as_set(removed_library_ids)),
             ("updatedCodeTemplates", None, "application/xml", as_list(updated)),
             ("removedCodeTemplateIds", None, "application/xml", as_set(removed_template_ids))]
    status, text = api.multipart("/codeTemplateLibraries/_bulkUpdate", parts,
                                 query={"override": "true" if override else "false"})
    if status != 200:
        return False, f"HTTP {status}: {text[:300]}"
    root = ET.fromstring(text)
    ok = (root.findtext("overrideNeeded") == "false" and root.findtext("librariesSuccess") == "true"
          and all(s.text == "true" for s in root.iter("success")))
    return ok, "" if ok else text[:500]


def save_template(api, lid, tid, name, marker):
    """Creates or updates one template in library lid, as the code template editor saves it."""
    libs = libraries(api)
    lib = find_library(libs, lid)
    refs = lib.find("codeTemplates")
    if refs is None or all(ref.findtext("id") != tid for ref in refs):
        add_template_ref(lib, tid)
    return bulk_update(api, libs, updated=[template(tid, name, marker, template_revision(api, tid))])


def remove_template(api, lid, tid):
    """Removes one template, as the code template editor does: dropped from its library and listed as removed."""
    libs = libraries(api)
    remove_template_ref(find_library(libs, lid), tid)
    return bulk_update(api, libs, removed_template_ids=[tid])


def put_template(api, tid, name, marker):
    """The single-template endpoint. Neither Administrator saves through it; kept for comparison."""
    status, text = api.request("PUT", f"/codeTemplates/{tid}",
                               _xml(template(tid, name, marker, template_revision(api, tid))),
                               query={"override": "true"})
    return status == 200 and "true" in text


# ---------- the plugin's own endpoints

def history(api, kind, item_id):
    path, key = (("/history", "channelId") if kind == "channel" else ("/codeTemplateHistory", "codeTemplateId"))
    return _rows(api.xml("GET", EXT + path, query={key: item_id}))


def history_ids(api, kind, item_id):
    return [row["hash"] for row in history(api, kind, item_id)]


def history_status(api, kind, item_id):
    """The history list's HTTP status, without raising on an error."""
    path, key = (("/history", "channelId") if kind == "channel" else ("/codeTemplateHistory", "codeTemplateId"))
    return api.request("GET", EXT + path, query={key: item_id})[0]


def content(api, kind, item_id, history_id):
    path, key = (("/content", "channelId") if kind == "channel" else ("/codeTemplateContent", "codeTemplateId"))
    status, text = api.text(EXT + path, query={key: item_id, "revision": history_id})
    if status != 200:
        raise ApiError(f"GET {path} -> HTTP {status}: {text[:300]}")
    return text


def revert(api, kind, item_id, history_id):
    path, key = (("/revertChannel", "channelId") if kind == "channel" else ("/revertCodeTemplate", "codeTemplateId"))
    return _value(api.xml("POST", EXT + path, query={key: item_id, "revision": history_id})) == "true"


def prune(api, kind, item_id, keep_from_history_id):
    path, key = (("/pruneChannelHistory", "channelId") if kind == "channel"
                 else ("/pruneCodeTemplateHistory", "codeTemplateId"))
    return int(_value(api.xml("POST", EXT + path, query={key: item_id, "revision": keep_from_history_id})))


def deleted(api, kind):
    return _rows(api.xml("GET", EXT + ("/deletedChannels" if kind == "channel" else "/deletedCodeTemplates")))


def deleted_content(api, kind, row_id):
    path = "/deletedChannelContent" if kind == "channel" else "/deletedCodeTemplateContent"
    status, text = api.text(EXT + path, query={"id": row_id})
    if status != 200:
        raise ApiError(f"GET {path} -> HTTP {status}: {text[:300]}")
    return text


def purge(api, kind, row_id):
    path = "/purgeDeletedChannel" if kind == "channel" else "/purgeDeletedCodeTemplate"
    return _value(api.xml("POST", EXT + path, query={"id": row_id})) == "true"


def json_list(api, path, query=None):
    """One of the plugin's list endpoints as JSON, the way the web administrator reads it.
    Returns (HTTP status, the parsed body or None)."""
    status, text = api.request("GET", EXT + path, accept="application/json", query=query)
    return status, (json.loads(text) if status == 200 and text.strip() else None)


def prune_status(api, kind, item_id, keep_from_history_id):
    """Prune without raising on an error status. Returns (HTTP status, body)."""
    path, key = (("/pruneChannelHistory", "channelId") if kind == "channel"
                 else ("/pruneCodeTemplateHistory", "codeTemplateId"))
    return api.request("POST", EXT + path, query={key: item_id, "revision": keep_from_history_id})


def deleted_status(api, kind):
    """The Deleted Items list without raising on an error status. Returns (HTTP status, rows or None)."""
    status, text = api.request("GET", EXT + ("/deletedChannels" if kind == "channel" else "/deletedCodeTemplates"))
    return status, (_rows(ET.fromstring(text)) if status == 200 and text.strip() else None)


def deleted_content_status(api, kind, row_id):
    path = "/deletedChannelContent" if kind == "channel" else "/deletedCodeTemplateContent"
    return api.text(EXT + path, query={"id": row_id})


def purge_status(api, kind, row_id):
    path = "/purgeDeletedChannel" if kind == "channel" else "/purgeDeletedCodeTemplate"
    return api.request("POST", EXT + path, query={"id": row_id})


def plugin_events(api, limit=100):
    """The plugin's own entries in the engine's event log, newest first: each one's attributes plus
    'userId' and 'ipAddress'."""
    events = []
    for event in api.xml("GET", "/events", query={"limit": limit}):
        if event.findtext("name") != "Simple Channel History":
            continue
        fields = {entry[0].text: entry[1].text for entry in event.iter("entry")}
        fields.update(id=int(event.findtext("id")), userId=event.findtext("userId"), ipAddress=event.findtext("ipAddress"))
        events.append(fields)
    return sorted(events, key=lambda e: e["id"], reverse=True)


# ---------- users and rbac roles

def current_user_id(api):
    return api.xml("GET", "/users/current").findtext("id")


def create_user(api, username, password):
    """Creates an engine user and sets its password. Returns the user's id."""
    user = ET.Element("user")
    for tag, text in (("username", username), ("firstName", "SCH"), ("lastName", "e2e"),
                      ("email", f"{username}@example.com")):
        ET.SubElement(user, tag).text = text
    api.xml("POST", "/users", _xml(user))
    uid = api.xml("GET", f"/users/{username}").findtext("id")
    status, text = api.request("PUT", f"/users/{uid}/password", password, content_type="text/plain")
    if status not in (200, 204) or "<string>" in text:   # a list of strings is the password rules it broke
        raise ApiError(f"PUT /users/{uid}/password -> HTTP {status}: {text[:300]}")
    return uid


RBAC = "/extensions/rbac"


def rbac_answers(api):
    """rbac is installed and running: its role list answers."""
    return api.request("GET", RBAC + "/roles")[0] == 200


def create_role(api, name, permissions, channel_ids=()):
    """An rbac role. An empty channel_ids leaves the role unrestricted by channel. Returns the role's id."""
    role = ET.Element("com.diridium.rbac.Role")
    ET.SubElement(role, "name").text = name
    ET.SubElement(role, "description").text = "simple-channel-history e2e"
    for tag, values in (("permissions", permissions), ("channelIds", channel_ids)):
        holder = ET.SubElement(role, tag)
        for value in values:
            ET.SubElement(holder, "string").text = value
    ET.SubElement(role, "isAdmin").text = "false"
    return api.xml("POST", RBAC + "/roles", _xml(role)).findtext("id")


def assign_role(api, user_id, role_id):
    api.xml("POST", f"{RBAC}/users/{user_id}/role/{role_id}")


# ---------- extensions

def install(api, zip_path):
    with open(zip_path, "rb") as f:
        payload = f.read()
    status, text = api.multipart("/extensions/_install",
                                 [("file", os.path.basename(zip_path), "application/zip", payload)])
    return status in (200, 204), f"HTTP {status} {text[:200]}"


def uninstall(api):
    # The engine writes the body verbatim into extensions/uninstall, so it is the bare path:
    # an XML-wrapped <string> is written literally and matches no extension folder.
    status, text = api.request("POST", "/extensions/_uninstall", PLUGIN_PATH)
    return status in (200, 204), f"HTTP {status} {text[:200]}"


def plugin_installed(api):
    root = api.xml("GET", "/extensions/plugins")
    return any(el.get("path") == PLUGIN_PATH for el in root.iter("pluginMetaData"))
