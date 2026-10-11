# SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
# SPDX-License-Identifier: MPL-2.0

"""Minimal OIE REST client for the end-to-end tests. Standard library only."""

import http.cookiejar
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET


class ApiError(Exception):
    pass


class Api:
    def __init__(self, port, username="admin", password="admin"):
        self.base = f"https://127.0.0.1:{port}/api"
        self.username = username
        self.password = password
        # The test engine generates a self-signed certificate on first start.
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=context),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, method, path, body=None, content_type="application/xml",
                accept="application/xml", query=None, timeout=300):
        """Returns (status, text). Never raises for an HTTP error status."""
        url = self.base + path
        if query:
            url += "?" + urllib.parse.urlencode(query, doseq=True)
        data = body.encode("utf-8") if isinstance(body, str) else body
        # The engine refuses API calls without X-Requested-With (server.api.require-requested-with).
        headers = {"X-Requested-With": "OpenAPI", "Accept": accept}
        if data is not None:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=timeout) as resp:
                return resp.status, resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")

    def login(self, wait=900, alive=lambda: True):
        """Logs in, retrying until the engine answers. alive() returning False stops the wait early."""
        end = time.time() + wait
        form = urllib.parse.urlencode({"username": self.username, "password": self.password})
        while True:
            try:
                status, text = self.request("POST", "/users/_login", form,
                                            content_type="application/x-www-form-urlencoded")
                if status == 200 and "SUCCESS" in text:
                    return
            except OSError:
                pass
            if not alive():
                raise ApiError("the engine exited or gave up on startup before it accepted a login; see its mirth.log")
            if time.time() > end:
                raise ApiError(f"no successful login within {wait} seconds")
            time.sleep(3)

    def xml(self, method, path, body=None, query=None, ok=(200, 201, 204)):
        """Sends XML, returns the parsed response (None for an empty body). Raises on other statuses."""
        status, text = self.request(method, path, body, query=query)
        if status not in ok:
            raise ApiError(f"{method} {path} -> HTTP {status}: {text[:300]}")
        return ET.fromstring(text) if text.strip() else None

    def text(self, path, query=None):
        """GET for an endpoint that returns a String. Returns (status, string)."""
        status, text = self.request("GET", path, query=query)
        if status == 200 and text.lstrip().startswith("<string"):
            return status, ET.fromstring(text).text or ""
        return status, text

    def multipart(self, path, parts, query=None):
        """POST multipart/form-data. parts: list of (name, filename or None, content type, bytes or str)."""
        boundary = "----sch-e2e-" + uuid.uuid4().hex
        chunks = []
        for name, filename, ctype, payload in parts:
            disposition = f'form-data; name="{name}"' + (f'; filename="{filename}"' if filename else "")
            chunks.append(f"--{boundary}\r\nContent-Disposition: {disposition}\r\n"
                          f"Content-Type: {ctype}\r\n\r\n".encode("utf-8"))
            chunks.append(payload.encode("utf-8") if isinstance(payload, str) else payload)
            chunks.append(b"\r\n")
        chunks.append(f"--{boundary}--\r\n".encode("utf-8"))
        return self.request("POST", path, b"".join(chunks),
                            content_type=f"multipart/form-data; boundary={boundary}", query=query)
