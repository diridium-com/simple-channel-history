# SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
# SPDX-License-Identifier: MPL-2.0

"""One database server per run, in Docker, with a new database (or Oracle user) per scenario.

Containers are named E2E_NAME_PREFIX-<kind>-db (default prefix sch-e2e) and carry the label
sch-e2e=1 plus any k=v pairs in E2E_DOCKER_LABELS (comma separated). The port is published
on 127.0.0.1 and picked by Docker. stop() removes only the container this run started.
"""

import os
import re
import subprocess
import time

PASSWORD = "Sch-e2e-Pass-1"   # meets SQL Server's complexity rule; throwaway test databases only
PLUGIN_TABLES = ("channel_history", "code_template_history", "deleted_channel", "deleted_code_template")

# Pinned by digest, so a run tests the same database build until someone moves the pin. The tag
# says what each digest is: PostgreSQL 18.3, MySQL 8.4.11, SQL Server 2022 and Oracle Free 23 as of
# 2026-10-12.
IMAGES = {
    "postgres": "postgres:18.3-alpine@sha256:54451ecb8ab38c24c3ec123f2fd501303a3a1856a5c66e98cecf2460d5e1e9d7",
    "mysql": "mysql:8.4@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242",
    "sqlserver": "mcr.microsoft.com/mssql/server:2022-latest@sha256:4402d880dd4c34bfa7d8705e56a86cd6c88da80a1f6bbbe741f999e76264a090",
    "oracle": "gvenzl/oracle-free:23-slim@sha256:6d61d267a3b978c24c5ac1790e62e927416a0aec446bd86e4b3a1527562757bd",
}
KINDS = ("derby", "postgres", "mysql", "sqlserver", "sqlserver-ms", "oracle")


def docker(*args, check=True, timeout=600):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args[:3])} failed: {result.stderr.strip()[:500]}")
    return result


class Database:
    def __init__(self, kind):
        if kind not in KINDS:
            raise ValueError(f"unknown database {kind}; expected one of {', '.join(KINDS)}")
        self.kind = kind
        self.server = "sqlserver" if kind.startswith("sqlserver") else kind
        self.container = None
        self.port = None

    # ---------- container lifecycle

    def start(self):
        if self.kind == "derby":
            return
        prefix = os.environ.get("E2E_NAME_PREFIX", "sch-e2e")
        labels = ["--label", "sch-e2e=1"]
        for pair in filter(None, os.environ.get("E2E_DOCKER_LABELS", "").split(",")):
            labels += ["--label", pair.strip()]
        inner_port, env = {
            "postgres": ("5432", ["-e", "POSTGRES_USER=oie", "-e", f"POSTGRES_PASSWORD={PASSWORD}"]),
            "mysql": ("3306", ["-e", f"MYSQL_ROOT_PASSWORD={PASSWORD}"]),
            "sqlserver": ("1433", ["-e", "ACCEPT_EULA=Y", "-e", f"MSSQL_SA_PASSWORD={PASSWORD}"]),
            "oracle": ("1521", ["-e", f"ORACLE_PASSWORD={PASSWORD}"]),
        }[self.server]
        name = f"{prefix}-{self.kind}-db"
        self.container = docker("run", "-d", "--name", name, *labels, "-p", f"127.0.0.1::{inner_port}",
                                *env, IMAGES[self.server]).stdout.strip()
        mapping = docker("port", self.container, f"{inner_port}/tcp").stdout.splitlines()[0]
        self.port = mapping.rsplit(":", 1)[1]
        self._wait_ready()

    def _wait_ready(self, timeout=900):
        end = time.time() + timeout
        while time.time() < end:
            if self._ready():
                return
            time.sleep(5)
        raise RuntimeError(f"{self.kind} did not become ready in {timeout} seconds")

    def _ready(self):
        if self.server == "postgres":
            # Over TCP: the image first runs a temporary init server on the Unix socket only, and
            # shuts it down before starting the real one, so a socket check can answer too early.
            return docker("exec", self.container, "pg_isready", "-h", "127.0.0.1", "-U", "oie", "-d", "postgres",
                          check=False).returncode == 0
        if self.server == "mysql":
            # The image runs a temporary server on port 0 before the real one on 3306.
            logs = docker("logs", self.container, check=False)
            return re.search(r"ready for connections.*port: 3306", logs.stdout + logs.stderr) is not None
        if self.server == "sqlserver":
            return self._sql("SELECT 1", check=False).returncode == 0
        logs = docker("logs", self.container, check=False)
        return "DATABASE IS READY TO USE" in logs.stdout + logs.stderr

    def stop(self):
        if self.container:
            docker("rm", "-f", "-v", self.container, check=False)
            self.container = None

    # ---------- per-scenario databases

    def create(self, name):
        """Creates an empty database for one scenario; returns the mirth.properties settings for it."""
        if self.kind == "derby":
            return {}
        if self.server == "postgres":
            self._sql(f'CREATE DATABASE "{name}"')
            return {"database": "postgres", "database.url": f"jdbc:postgresql://127.0.0.1:{self.port}/{name}",
                    "database.username": "oie", "database.password": PASSWORD}
        if self.server == "mysql":
            # One user per scenario, with rights on its own database only. The engine decides whether its
            # schema exists with getTables(null, ...), which MySQL Connector/J answers from every database
            # the user can see, so a shared user makes a second scenario's engine skip creating its schema.
            self._sql(f"CREATE DATABASE {name}; CREATE USER '{name}'@'%' IDENTIFIED BY '{PASSWORD}'; "
                      f"GRANT ALL PRIVILEGES ON {name}.* TO '{name}'@'%';")
            return {"database": "mysql", "database.url": f"jdbc:mysql://127.0.0.1:{self.port}/{name}",
                    "database.username": name, "database.password": PASSWORD}
        if self.server == "sqlserver":
            self._sql(f"CREATE DATABASE {name}")
            settings = {"database": "sqlserver", "database.username": "sa", "database.password": PASSWORD}
            if self.kind == "sqlserver":
                # The engine's default SQL Server driver is jTDS.
                settings["database.url"] = f"jdbc:jtds:sqlserver://127.0.0.1:{self.port}/{name}"
            else:
                settings["database.url"] = (f"jdbc:sqlserver://127.0.0.1:{self.port};databaseName={name};"
                                            "encrypt=false")
                settings["database.driver"] = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
            return settings
        # Oracle: one user (schema) per scenario in the image's FREEPDB1 pluggable database.
        self._sql(f"CREATE USER {name} IDENTIFIED BY \"{PASSWORD}\" QUOTA UNLIMITED ON USERS;\n"
                  f"GRANT CONNECT, RESOURCE, CREATE VIEW TO {name};")
        return {"database": "oracle", "database.url": f"jdbc:oracle:thin:@//127.0.0.1:{self.port}/FREEPDB1",
                "database.username": name, "database.password": PASSWORD}

    def plugin_row_counts(self, name):
        """Row counts of the plugin's tables, read from the database itself. None for Derby."""
        if self.kind == "derby":
            return None
        return [self._scalar(name, f"SELECT count(*) FROM {table}") for table in PLUGIN_TABLES]

    def plugin_table_count(self, name):
        """How many of the plugin's tables exist in the scenario's database. None for Derby."""
        if self.kind == "derby":
            return None
        names = ", ".join(f"'{t}'" for t in PLUGIN_TABLES)
        if self.server == "postgres":
            q = f"SELECT count(*) FROM information_schema.tables WHERE table_name IN ({names})"
        elif self.server == "mysql":
            q = f"SELECT count(*) FROM information_schema.tables WHERE table_schema = '{name}' AND table_name IN ({names})"
        elif self.server == "sqlserver":
            q = f"SELECT count(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ({names})"
        else:
            q = f"SELECT count(*) FROM user_tables WHERE table_name IN ({names.upper()})"
        return self._scalar(name, q)

    # ---------- SQL through each image's own client

    def _sql(self, sql, database=None, check=True):
        if self.server == "postgres":
            cmd = ["psql", "-U", "oie", "-d", database or "postgres", "-v", "ON_ERROR_STOP=1", "-tAc", sql]
        elif self.server == "mysql":
            cmd = ["mysql", "-uroot", f"-p{PASSWORD}", "-N", "-e", sql] + ([database] if database else [])
        elif self.server == "sqlserver":
            cmd = ["/opt/mssql-tools18/bin/sqlcmd", "-C", "-b", "-S", "localhost", "-U", "sa", "-P", PASSWORD,
                   "-h", "-1", "-W", "-Q", "SET NOCOUNT ON; " + sql] + (["-d", database] if database else [])
        else:
            login = f"{database}/\"{PASSWORD}\"" if database else f"system/\"{PASSWORD}\""
            script = f"WHENEVER SQLERROR EXIT FAILURE\nSET HEADING OFF FEEDBACK OFF PAGESIZE 0\n{sql}\nEXIT\n"
            result = subprocess.run(["docker", "exec", "-i", self.container, "sqlplus", "-s",
                                     f"{login}@//localhost:1521/FREEPDB1"],
                                    input=script, capture_output=True, text=True, timeout=300)
            if check and result.returncode != 0:
                raise RuntimeError(f"sqlplus failed: {(result.stdout + result.stderr).strip()[:500]}")
            return result
        return docker("exec", self.container, *cmd, check=check, timeout=300)

    def _scalar(self, database, sql):
        if self.server == "oracle":
            sql += ";"
        out = self._sql(sql, database=database).stdout
        numbers = re.findall(r"^\s*(\d+)\s*$", out, re.M)
        if not numbers:
            raise RuntimeError(f"no number in the output of {sql!r}: {out.strip()[:300]}")
        return int(numbers[-1])
