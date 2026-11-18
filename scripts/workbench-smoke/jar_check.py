"""只启动生产 jar，检查资源/授权边界、旧 SQLite 升级和重启；仅使用本机平台 fixture。"""
import argparse
from contextlib import closing, contextmanager
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from platform_fixture import ALPHA, BETA, EXAMPLES, serve_fixture

ROOT = Path(__file__).resolve().parents[2]


def request(url, token=None, method="GET", data=None, headers=None):
    headers = {"Accept": "*/*", **(headers or {})}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        data = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    try:
        response = urlopen(Request(url, data=data, headers=headers, method=method), timeout=10)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


@contextmanager
def running(args, env, base, log):
    with log.open("w", encoding="utf-8") as stream:
        process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            deadline = time.monotonic() + 90
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    if request(base + "/")[0] == 200:
                        break
                except OSError:
                    pass
                time.sleep(.25)
            else:
                raise AssertionError("jar startup failed: " + log.read_text(encoding="utf-8", errors="replace")[-4000:])
            yield
        finally:
            process.terminate()
            try:
                process.wait(15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def seed(database):
    with closing(sqlite3.connect(database)) as db:
        db.executescript("""
            CREATE TABLE review_record(id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL,
                merge_request_iid INTEGER NOT NULL, outcome TEXT NOT NULL, reason TEXT, occurred_at TEXT NOT NULL);
            CREATE TABLE finding(id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL,
                merge_request_iid INTEGER NOT NULL, discussion_id INTEGER NOT NULL, path TEXT, new_line INTEGER,
                old_line INTEGER, category TEXT, citation TEXT, comment_body TEXT NOT NULL, head_sha TEXT NOT NULL);
            INSERT INTO review_record VALUES(1001,9,3,'SUCCESS',NULL,'2026-10-15T09:00:00Z');
            INSERT INTO finding VALUES(2001,9,3,71,'main.go',7,NULL,'缺陷','错误处理','历史发现','old-head');
        """)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/code-review-agent-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--artifacts", type=Path, default=ROOT / "target/jar-check")
    options = parser.parse_args()
    options.artifacts.mkdir(parents=True, exist_ok=True)
    assert options.jar.is_file(), "先执行 mvn -Pproduction clean package"
    token, secret = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    with tempfile.TemporaryDirectory(prefix="jar-check-") as temp, serve_fixture() as (fixture, fixture_port):
        database = Path(temp) / "legacy.db"
        seed(database)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        base, upstream = f"http://127.0.0.1:{port}", f"http://127.0.0.1:{fixture_port}"
        args = [java, "-jar", str(options.jar.resolve()), "--server.address=127.0.0.1", f"--server.port={port}",
                f"--spring.datasource.url=jdbc:sqlite:{database.as_posix()}", f"--review.github.base-url={upstream}",
                "--review.github.token=fixture-only", f"--review.github.webhook-secret={secret}",
                f"--review.gitlab.webhook-secret={secret}", "--review.gitlab.base-url=http://127.0.0.1:1",
                f"--spring.ai.openai.base-url={upstream}", f"--spring.ai.openai.chat.base-url={upstream}",
                "--spring.ai.openai.api-key=fixture-only", "--spring.ai.openai.chat.api-key=fixture-only",
                "--spring.ai.openai.chat.options.model=smoke", "--spring.ai.retry.max-attempts=1"]

        def api(path, method="GET", data=None):
            code, _, body = request(base + path, token, method, data)
            assert code in (200, 202), (path, code, body)
            return json.loads(body)["data"]

        with running(args + ["--review.workbench.token="], os.environ.copy(), base, options.artifacts / "unconfigured.log"):
            assert request(base + "/api/repositories")[0] == 503
        with running(args + [f"--review.workbench.token={token}"], os.environ.copy(), base, options.artifacts / "first.log"):
            index = request(base + "/")[2]
            assert b"<html" in index.lower()
            for path in ("/index.html", "/repositories", "/repositories/", "/reviews", "/reviews/"):
                code, headers, body = request(base + path)
                assert code == 200 and body == index and "text/html" in headers["Content-Type"], path
            assets = re.findall(r'(?:src|href)="(/[^\"]+)"', index.decode())
            assert any(path.endswith(".js") for path in assets), assets
            for path in assets:
                code, _, body = request(base + path)
                assert code == 200 and body != index, path
            for path in ("/api/unknown", "/assets/missing.js", "/missing.css", "/unknown",
                         "/repositories/missing.js", "/github/webhooks/missing", "/gitlab/webhooks/missing"):
                code, _, body = request(base + path, token)
                assert code == 404 and body != index, (path, code)
            for credential in (None, "wrong"):
                code, headers, _ = request(base + "/api/repositories", credential)
                assert code == 401 and headers["Cache-Control"] == "no-store"
            for path in ("/github/webhooks/pull-requests", "/gitlab/webhooks/merge-requests"):
                assert request(base + path, method="POST", data={})[0] == 403
            assert request(base + "/reviews", method="POST")[0] == 405
            ids = {}
            fixture.finish_alpha.set()
            for example, status in ((ALPHA, "SUCCEEDED"), (BETA, "FAILED")):
                profile = api("/api/repositories", "POST", {"url": f"https://127.0.0.1:{fixture_port}/{example.full_name}",
                              "standardPaths": [example.standard], "specPaths": [example.spec]})
                ids[example.name] = profile["id"]
                path = f"/api/repositories/{profile['id']}"
                api(path + "/skills/review", "PUT", {"content": example.skill, "enabled": True})
                job = api(path + "/jobs", "POST", {"number": example.number})
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    jobs = api(path + "/jobs")
                    if jobs[0]["status"] in ("SUCCEEDED", "FAILED"):
                        break
                    time.sleep(.1)
                assert len(jobs) == 1 and jobs[0]["status"] == status, jobs
                assert api(path + "/jobs", "POST", {"number": example.number})["id"] == job["id"]
            fixture.verify_inputs()
            payload = {"action": "opened", "number": ALPHA.number,
                       "repository": {"id": ALPHA.repository_id, "full_name": ALPHA.full_name},
                       "pull_request": {"head": {"sha": ALPHA.head_ref}}}
            signature = hmac.new(secret.encode(), json.dumps(payload).encode(), hashlib.sha256).hexdigest()
            assert request(base + "/github/webhooks/pull-requests", method="POST", data=payload,
                           headers={"X-GitHub-Event": "pull_request", "X-Hub-Signature-256": "sha256=" + signature})[0] == 202
            assert len(api(f"/api/repositories/{ids[ALPHA.name]}/jobs")) == 1
        with closing(sqlite3.connect(database)) as db:
            for state in ("QUEUED", "RUNNING"):
                db.execute("INSERT INTO workbench_job(repository_id,change_number,head_sha,base_sha,start_sha,status,source,created_at) "
                           "VALUES(?,99,?,'b','s',?,'manual','2026-10-15T09:00:00Z')", (ids[ALPHA.name], state, state))
            db.commit()
        with running(args + [f"--review.workbench.token={token}"], os.environ.copy(), base, options.artifacts / "restart.log"):
            assert len(api("/api/repositories")) == 2
            for example in EXAMPLES:
                path = f"/api/repositories/{ids[example.name]}"
                assert api(path)["standardPaths"] == [example.standard]
                assert api(path + "/skills/review")["content"] == example.skill
            assert [j["status"] for j in api(f"/api/repositories/{ids[ALPHA.name]}/jobs")] == ["FAILED", "FAILED", "SUCCEEDED"]
            assert [j["status"] for j in api(f"/api/repositories/{ids[BETA.name]}/jobs")] == ["FAILED"]
            with closing(sqlite3.connect(database)) as db:
                assert db.execute("SELECT outcome,occurred_at FROM review_record WHERE id=1001").fetchone() == ("SUCCESS", "2026-10-15T09:00:00Z")
                assert db.execute("SELECT comment_body FROM finding WHERE id=2001").fetchone()[0] == "历史发现"
                assert "failure_detail" in {r[1] for r in db.execute("PRAGMA table_info(review_record)")}
    summary = "PASS: production jar pages/assets, 401/503/404/405, webhook signatures, two repositories + skills + jobs, retry/dedup, old SQLite upgrade + restart/interruption. Local fixture only; no browser acceptance."
    (options.artifacts / "result.txt").write_text(summary + "\n", encoding="utf-8")
    print(summary)


if __name__ == "__main__":
    main()
