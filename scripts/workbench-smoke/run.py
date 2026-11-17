"""运行单个生产 jar + Playwright 验收；不访问真实 GitHub 或模型服务。"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
from contextlib import contextmanager
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from playwright.sync_api import expect, sync_playwright
from platform_fixture import ALPHA, BETA, EXAMPLES, serve_fixture

ROOT = Path(__file__).resolve().parents[2]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def fetch(url, token):
    req = Request(url, headers={"Authorization": f"Bearer {token}"})
    with urlopen(req, timeout=2) as response:
        return response.read()


@contextmanager
def service(command, cwd, env, check_url, token, log):
    with log.open("w", encoding="utf-8") as stream:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline and process.poll() is None:
                try:
                    fetch(check_url, token)
                    break
                except OSError:
                    time.sleep(.25)
            else:
                last = log.read_text(encoding="utf-8", errors="replace").splitlines()[-15:]
                raise RuntimeError(f"Local service did not start: {check_url}\n" + "\n".join(last))
            yield
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def mutation(page, method, path, action):
    with page.expect_response(lambda r: urlsplit(r.url).path == path and r.request.method == method) as event:
        action()
    response = event.value
    if not response.ok:
        raise AssertionError(f"{method} {path}: HTTP {response.status}: {response.text()[:250]}")
    return response.json()["data"]


def check_layout(page, width, height, name, artifacts):
    page.set_viewport_size({"width": width, "height": height})
    expect(page.get_by_test_id("repo-select")).to_be_visible()
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"), f"{name}: horizontal overflow"
    if artifacts:
        page.screenshot(path=str(artifacts / (name + ".png")), full_page=True)


def run_browser(options, fixture, fixture_port, ui, api, token):
    failures = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, executable_path=options.browser)
        try:
            page = browser.new_page(viewport={"width": 1440, "height": 900})
            page.set_default_timeout(12000)
            page.on("pageerror", lambda err: failures.append(str(err)))
            page.goto(ui + "/repositories", wait_until="networkidle")
            el = page.get_by_test_id
            el("workbench-token").fill(token)
            el("workbench-unlock").click()
            ids = {}
            for x in EXAMPLES:
                if ids:
                    el("repo-new").click()
                    expect(page.get_by_role("dialog", name="添加被审查仓库")).to_be_visible()
                    page.keyboard.press("Escape")
                    expect(el("repo-select")).to_have_value(str(ids[ALPHA.name]))
                    el("repo-new").click()
                el("repo-url").fill(f"https://127.0.0.1:{fixture_port}/{x.full_name}")
                el("repo-standards").fill(x.standard)
                el("repo-specs").fill(x.spec)
                saved = mutation(page, "POST", "/api/repositories", el("repo-save").click)
                ids[x.name] = saved["id"]
                el("repo-select").select_option(str(saved["id"]))
                assert saved["standardPaths"] == [x.standard] and saved["specPaths"] == [x.spec]
                mutation(page, "PUT", f"/api/repositories/{saved['id']}/skills/review",
                         lambda: el("skill-file-review").set_input_files(
                             {"name": "SKILL.md", "mimeType": "text/markdown", "buffer": x.skill.encode()}))
            page.reload(wait_until="networkidle")
            for x in EXAMPLES:
                el("repo-select").select_option(str(ids[x.name]))
                expect(el("repo-standards")).to_have_value(x.standard)
                expect(el("repo-specs")).to_have_value(x.spec)
                expect(el("skill-toggle-review")).to_be_checked()
            check_layout(page, 1440, 900, "repositories-desktop", options.artifacts)
            check_layout(page, 320, 844, "repositories-mobile", options.artifacts)
            page.goto(ui + "/reviews", wait_until="networkidle")
            for x, final in ((ALPHA, "SUCCEEDED"), (BETA, "FAILED")):
                repo = ids[x.name]
                el("repo-select").select_option(str(repo))
                el("review-number").fill(str(x.number))
                job = mutation(page, "POST", f"/api/repositories/{repo}/jobs", el("review-trigger").click)
                assert job["number"] == x.number and job["repositoryId"] == repo
                jobs = el("review-jobs")
                if x is ALPHA:
                    assert fixture.model_entered.wait(timeout=15), "Model did not receive first review"
                    expect(jobs.locator('[data-status="RUNNING"]')).to_have_text("运行中")
                    fixture.finish_alpha.set()
                marker = jobs.locator(f'[data-status="{final}"]')
                label = "已完成" if x is ALPHA else "失败"
                expect(marker).to_have_text(label, timeout=25000)
                records = json.loads(fetch(f"{api}/api/repositories/{repo}/jobs", token))["data"]
                assert [(r["number"], r["status"]) for r in records] == [(x.number, final)], records
                page.reload(wait_until="networkidle")
                el("repo-select").select_option(str(repo))
                expect(marker).to_have_text(label)
                expect(el("review-jobs")).not_to_contain_text(f"#{BETA.number if x is ALPHA else ALPHA.number}")
            check_layout(page, 320, 844, "reviews-mobile", options.artifacts)
            check_layout(page, 1440, 900, "reviews-desktop", options.artifacts)
            page.goto(ui + "/repositories", wait_until="networkidle")
            el("repo-select").select_option(str(ids[BETA.name]))
            skill_path = f"/api/repositories/{ids[BETA.name]}/skills/review"
            mutation(page, "PUT", skill_path, el("skill-toggle-review").click)
            expect(el("skill-toggle-review")).not_to_be_checked()
            el("skill-restore-review").click()
            mutation(page, "DELETE", skill_path, page.get_by_role("button", name="确认恢复").click)
            expect(el("skill-restore-review")).to_have_count(0)
            el("repo-select").select_option(str(ids[ALPHA.name]))
            expect(el("skill-toggle-review")).to_be_checked()
            expect(el("skill-restore-review")).to_be_visible()
            fixture.verify_inputs()
            assert not failures, failures
            return ids
        finally:
            fixture.finish_alpha.set()
            browser.close()


def verify_restart(api, token, ids):
    def get(path):
        return json.loads(fetch(api + "/api/repositories" + path, token))["data"]

    assert {item["id"] for item in get("")} == set(ids.values())
    for x, status in ((ALPHA, "SUCCEEDED"), (BETA, "FAILED")):
        path = f"/{ids[x.name]}"
        profile = get(path)
        assert profile["remoteId"] == x.repository_id and profile["fullName"] == x.full_name
        assert profile["standardPaths"] == [x.standard] and profile["specPaths"] == [x.spec]
        skill = get(path + "/skills/review")
        if x is ALPHA:
            assert skill["custom"] and skill["enabled"] and skill["content"] == x.skill
        else:
            assert not skill["custom"] and "name: merge-request-review" in skill["content"]
        jobs = get(path + "/jobs")
        assert [(j["number"], j["status"], j["headSha"], j["source"]) for j in jobs] == [
            (x.number, status, x.head_ref, "manual")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/code-review-agent-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--browser", help="系统 Chrome/Chromium 路径；省略则使用 Playwright Chromium")
    parser.add_argument("--artifacts", type=Path, help="可选：保存四张视口截图")
    options = parser.parse_args()
    if not options.jar.is_file():
        parser.error("先用 JDK 21 执行 mvn -Pproduction clean package；详见同目录 README.md")
    if options.artifacts:
        options.artifacts.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(32)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    with tempfile.TemporaryDirectory(prefix="review-smoke-") as temp, serve_fixture() as (fixture, port):
        tmp = Path(temp)
        api_port = free_port()
        api = f"http://127.0.0.1:{api_port}"
        upstream = f"http://127.0.0.1:{port}"
        args = [java, "-jar", str(options.jar.resolve()), "--server.address=127.0.0.1", f"--server.port={api_port}",
                f"--review.github.base-url={upstream}", "--review.github.token=fixture-only",
                "--review.gitlab.webhook-enabled=false", "--review.gitlab.base-url=http://127.0.0.1:1",
                "--review.gitlab.token=fixture-only", f"--spring.ai.openai.base-url={upstream}",
                f"--spring.ai.openai.chat.base-url={upstream}", "--spring.ai.openai.api-key=fixture-only",
                "--spring.ai.openai.chat.api-key=fixture-only", "--spring.ai.openai.chat.options.model=smoke",
                "--spring.ai.retry.max-attempts=1", f"--spring.datasource.url=jdbc:sqlite:{tmp.as_posix()}/review.db"]
        env = {**os.environ, "REVIEW_WORKBENCH_TOKEN": token}
        with service(args, ROOT, env, api + "/api/repositories", token, tmp / "spring.log"):
            ids = run_browser(options, fixture, port, api, api, token)
        with service(args, ROOT, env, api + "/api/repositories", token, tmp / "spring-restart.log"):
            verify_restart(api, token, ids)
    print("PASS: alpha SUCCEEDED, beta FAILED; isolated skills + pinned documents; browser reload; "
          "desktop/mobile; settings, effective skills and history persisted across Spring restart.")


if __name__ == "__main__":
    main()
