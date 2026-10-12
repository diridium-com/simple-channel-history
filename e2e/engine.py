# SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
# SPDX-License-Identifier: MPL-2.0

"""Runs the released OIE engine, unpacked from its distribution tarball, as a local process."""

import os
import re
import shutil
import signal
import socket
import subprocess
import tarfile
import time

from oie import Api


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def unpack(tarball, dest):
    """Unpacks the distribution once; each scenario copies this pristine tree."""
    os.makedirs(dest)
    with tarfile.open(tarball) as tar:
        tar.extractall(dest, filter="data")
    return os.path.join(dest, "oie")


def set_properties(path, values):
    """Sets 'key = value' lines in a .properties file, uncommenting a '#key =' line if that is all there is."""
    with open(path, encoding="utf-8", newline="") as f:
        lines = f.read().split("\n")
    for key, value in values.items():
        for i, line in enumerate(lines):
            stripped = line.lstrip("#").strip()
            if stripped.startswith(key + " =") or stripped == key + " =":
                lines[i] = f"{key} = {value}"
                break
        else:
            lines.append(f"{key} = {value}")
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines))


class Engine:
    def __init__(self, pristine, home, java_home=None):
        shutil.copytree(pristine, home, symlinks=True)
        self.home = home
        self.java_home = java_home
        self.process = None
        self.port = free_port()
        self.starts = 0
        # Stock OIE logs only ERROR. The plugin's migrator and repository log at INFO and WARN,
        # and the tests read those lines, so the plugin's package is turned up here.
        with open(os.path.join(home, "conf", "log4j2.properties"), "a", encoding="utf-8") as f:
            f.write("\nlogger.schE2e.name = com.diridium\nlogger.schE2e.level = INFO\n")

    def configure(self, database_settings):
        values = {
            "https.port": str(self.port),
            "http.port": str(free_port()),
            "https.host": "127.0.0.1",
            "http.host": "127.0.0.1",
        }
        values.update(database_settings)
        set_properties(os.path.join(self.home, "conf", "mirth.properties"), values)

    def start(self):
        env = dict(os.environ)
        if self.java_home:
            env["JAVA_HOME"] = self.java_home
            env["INSTALL4J_JAVA_HOME_OVERRIDE"] = self.java_home
        self.starts += 1
        console = open(os.path.join(self.home, f"console-{self.starts}.log"), "w", encoding="utf-8")
        log_start = len(self.log())
        self.process = subprocess.Popen(["./oieserver"], cwd=self.home, env=env,
                                        stdout=console, stderr=subprocess.STDOUT)
        api = Api(self.port)
        api.login(alive=lambda: self.process.poll() is None and not self._startup_failed(log_start))
        return api

    # Lines com.mirth.connect.server.Mirth logs when it gives up on startup. The JVM keeps running
    # after them, so without this check a broken start waits out the whole login timeout.
    FATAL = ("Failed to migrate database schema", "aborting startup", "port is already in use")

    def _startup_failed(self, log_start):
        new = self.log()[log_start:]
        return any(marker in new for marker in self.FATAL)

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=180)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.process = None

    def restart(self):
        self.stop()
        return self.start()

    def log(self):
        path = os.path.join(self.home, "logs", "mirth.log")
        if not os.path.exists(path):
            return ""
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()

    # The plugin's classes sit directly in com.diridium. Other Diridium plugins, such as rbac in the
    # rbac scenario, use subpackages (com.diridium.rbac), and their lines are not this plugin's.
    PLUGIN_LOGGER = re.compile(r"\bcom\.diridium\.[A-Z]")

    def plugin_log_lines(self, level):
        """Lines the plugin logged at the given level."""
        return [line for line in self.log().splitlines()
                if line.startswith(level) and self.PLUGIN_LOGGER.search(line)]

    def extension_files(self, name):
        folder = os.path.join(self.home, "extensions", name)
        return sorted(os.listdir(folder)) if os.path.isdir(folder) else None
