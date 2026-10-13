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
import zipfile

from oie import Api, ApiError


def free_ports(count):
    """count different ports that are free now. The sockets stay open until all are chosen, so the
    system can't hand out the same port twice."""
    sockets = [socket.socket() for _ in range(count)]
    try:
        for s in sockets:
            s.bind(("127.0.0.1", 0))
        return [s.getsockname()[1] for s in sockets]
    finally:
        for s in sockets:
            s.close()


def free_port():
    return free_ports(1)[0]


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
        self.console = None
        self.last_start_lost_port = False
        self.port = free_port()
        self.starts = 0
        # Stock OIE logs only ERROR. The plugin's migrator and repository log at INFO and WARN,
        # and the tests read those lines, so the plugin's package is turned up here.
        with open(os.path.join(home, "conf", "log4j2.properties"), "a", encoding="utf-8") as f:
            f.write("\nlogger.schE2e.name = com.diridium\nlogger.schE2e.level = INFO\n")

    def configure(self, database_settings):
        values = {
            "https.host": "127.0.0.1",
            "http.host": "127.0.0.1",
        }
        values.update(database_settings)
        set_properties(os.path.join(self.home, "conf", "mirth.properties"), values)
        self._set_ports()

    def _set_ports(self):
        self.port, http_port = free_ports(2)
        set_properties(os.path.join(self.home, "conf", "mirth.properties"),
                       {"https.port": str(self.port), "http.port": str(http_port)})

    def start(self):
        try:
            return self._start()
        except ApiError:
            # free_ports only finds ports that were free a moment ago. If something took one before the
            # engine bound it, try once more on new ports.
            if not self.last_start_lost_port:
                raise
            self.stop()
            self._set_ports()
            return self._start()

    def _start(self):
        env = dict(os.environ)
        if self.java_home:
            env["JAVA_HOME"] = self.java_home
            # The launcher (install4j) tries this first, and the java on PATH before JAVA_HOME, so
            # without it the engine could run on whichever java is first on the PATH.
            env["INSTALL4J_JAVA_HOME_OVERRIDE"] = self.java_home
        self.starts += 1
        self.console = open(os.path.join(self.home, f"console-{self.starts}.log"), "w", encoding="utf-8")
        log_start = len(self.log())
        self.last_start_lost_port = False
        self.process = subprocess.Popen(["./oieserver"], cwd=self.home, env=env,
                                        stdout=self.console, stderr=subprocess.STDOUT)
        api = Api(self.port)
        try:
            api.login(alive=lambda: self.process.poll() is None and not self._startup_failed(log_start))
        except ApiError:
            self.last_start_lost_port = "port is already in use" in self.log()[log_start:]
            raise
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
        if self.console:
            self.console.close()
            self.console = None

    def restart(self):
        self.stop()
        return self.start()

    def log(self):
        """All of mirth.log: the parts rolled over at 500 KB (mirth.log.<n>.zip, oldest first), then the live file."""
        logs = os.path.join(self.home, "logs")
        if not os.path.isdir(logs):
            return ""
        parts = []
        rolled = [f for f in os.listdir(logs) if re.fullmatch(r"mirth\.log\.\d+\.zip", f)]
        for name in sorted(rolled, key=lambda f: int(f.split(".")[2])):
            with zipfile.ZipFile(os.path.join(logs, name)) as z:
                parts += [z.read(member).decode("utf-8", "replace") for member in z.namelist()]
        path = os.path.join(logs, "mirth.log")
        if os.path.exists(path):
            with open(path, encoding="utf-8", errors="replace") as f:
                parts.append(f.read())
        return "".join(parts)

    # mirth.log's layout: "%-5p %d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %c: %m".
    LINE = re.compile(r"^(\S+)\s+\S+ \S+ \[.*?\] (\S+): (.*)$")
    # The plugin's classes sit directly in com.diridium. Other Diridium plugins, such as rbac in the
    # rbac scenario, use subpackages (com.diridium.rbac), and their lines are not this plugin's.
    PLUGIN_LOGGER = re.compile(r"^com\.diridium\.[A-Z]")
    OTHER_DIRIDIUM_LOGGER = re.compile(r"^com\.diridium\.[a-z]")
    PLUGIN_NAMES = ("simple-channel-history", "Simple Channel History")

    def plugin_log_lines(self, level):
        """Lines the plugin logged at the given level, and lines the engine logged about the plugin."""
        found = []
        for line in self.log().splitlines():
            m = self.LINE.match(line)
            if not m or m.group(1) != level:
                continue
            logger, message = m.group(2), m.group(3)
            about_plugin = (not self.OTHER_DIRIDIUM_LOGGER.match(logger)
                            and any(name in message for name in self.PLUGIN_NAMES))
            if self.PLUGIN_LOGGER.match(logger) or about_plugin:
                found.append(line)
        return found

    def extension_files(self, name):
        folder = os.path.join(self.home, "extensions", name)
        return sorted(os.listdir(folder)) if os.path.isdir(folder) else None
