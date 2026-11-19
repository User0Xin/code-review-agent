"""Compiled Vue UI regression with controlled API responses, not a production-jar test.

Run npm --prefix web run build first, then python web/tests/review_details_browser.py.
Only this script's temporary HTTP server and isolated headless browser are started.
"""
import argparse
from copy import deepcopy
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
from threading import Thread
import time

from playwright.sync_api import expect, sync_playwright

WEB = Path(__file__).resolve().parents[1]
STAMP = "2026-11-19T08:00:00Z"
TOKEN = "local-browser-fixture-only"
REPOS = [
    {"id": 7, "platform": "github", "remoteId": 555, "fullName": "sample/alpha",
     "url": "https://github.com/sample/alpha", "standardPaths": [], "specPaths": [], "skills": []},
    {"id": 9, "platform": "gitlab", "remoteId": 555, "fullName": "group/beta",
     "url": "https://gitlab.example.test/root/group/beta", "standardPaths": [], "specPaths": [], "skills": []},
]


def change_url(repo):
    return repo["url"] + ("/pull/" if repo["platform"] == "github" else "/-/merge_requests/") + "42"


def job(job_id, repo=7, status="FAILED"):
    return {"id": job_id, "repositoryId": repo, "number": 42, "headSha": "a" * 40,
            "status": status, "source": "manual", "message": None, "createdAt": STAMP,
            "finishedAt": None if status in ("QUEUED", "RUNNING") else STAMP}


def detail(job_id=101, repo=REPOS[0], status="FAILED"):
    return {
        "job": job(job_id, repo["id"], status),
        "repository": {key: repo[key] for key in ("id", "platform", "fullName", "url")},
        "changeUrl": change_url(repo),
        "diff": {"headSha": "a" * 40, "baseSha": "b" * 40, "startSha": "c" * 40},
        "result": {"availability": "RECORDED", "reviewRecordId": job_id + 1000,
                   "outcome": "FAILED" if status == "FAILED" else "SUCCESS", "occurredAt": STAMP,
                   "publishedCount": 1, "specSkipped": False,
                   "specDocumentPaths": ["docs/" + "long-path-" * 32 + "/spec.md"],
                   "standardDocumentPaths": ["CODING_STANDARD.md"],
                   "findings": [{"id": 210, "category": "缺陷", "path": "src/" + "Long" * 40 + ".java",
                                 "newLine": 24, "oldLine": 18, "citation": "依据第一行\n依据第二行",
                                 "commentBody": '<img src=x onerror="window.injected=true">\n这是实际发布的正文。',
                                 "discussionUrl": change_url(repo) + ("#note_28" if repo["platform"] == "gitlab" else "#issuecomment-28")}]},
        "failure": {"stage": "PUBLISH", "reason": "有 1 条没能写回", "interrupted": False} if status == "FAILED" else None,
        "retry": {"eligible": status == "FAILED", "activeJobId": None},
    }


def fixtures():
    data = {(7, 101): detail(), (9, 101): detail(repo=REPOS[1])}
    for job_id in range(102, 113):
        data[7, job_id] = detail(job_id)
    zero = data[7, 102]
    zero["job"] = job(102, status="SUCCEEDED")
    zero["result"].update(publishedCount=0, findings=[], outcome="SUCCESS", specSkipped=True,
                          specDocumentPaths=[], standardDocumentPaths=[])
    zero["failure"] = None
    zero["retry"]["eligible"] = False
    for job_id, availability, status in ((103, "LEGACY_UNLINKED", "FAILED"), (104, "MISSING", "FAILED"),
                                          (105, "IN_PROGRESS", "RUNNING"), (106, "NOT_STARTED", "QUEUED")):
        item = data[7, job_id]
        item["job"] = job(job_id, status=status)
        item["result"].update(availability=availability, reviewRecordId=None, outcome=None, occurredAt=None,
                              publishedCount=None, specSkipped=None, specDocumentPaths=None, standardDocumentPaths=None)
        if job_id in (103, 106):
            item["result"]["findings"] = []
        if job_id == 104:
            item["failure"] = {"stage": "INTERRUPTED", "reason": "服务重启，任务已中断", "interrupted": True}
        elif job_id == 103:
            item["failure"] = {"stage": "UNKNOWN", "reason": "失败原因未记录，请核对远端评论与服务状态", "interrupted": False}
        else:
            item["failure"] = None
        item["retry"]["eligible"] = status == "FAILED"
    data[7, 106]["diff"] = {"headSha": None, "baseSha": None, "startSha": None}
    data[7, 107]["job"] = job(107, status="SUCCEEDED")
    data[7, 107]["result"]["outcome"] = "SUCCESS"
    data[7, 107]["failure"] = None
    data[7, 107]["retry"]["eligible"] = False
    data[7, 110]["result"].update(publishedCount=0, findings=[])
    data[7, 110]["failure"]["stage"] = "MODEL"
    data[7, 111]["retry"]["activeJobId"] = 105
    data[7, 112]["changeUrl"] = "https://evil.example.test/steal"
    data[7, 112]["result"]["findings"][0]["discussionUrl"] = "javascript:alert(1)"
    return data


class StaticHandler(SimpleHTTPRequestHandler):
    # Windows registry MIME defaults can classify .js as text/plain.
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map,
                      ".js": "application/javascript", ".css": "text/css"}

    def do_GET(self):
        if self.path in ("/reviews", "/repositories", "/reviews/", "/repositories/"):
            self.path = "/index.html"
        super().do_GET()

    def log_message(self, *args):
        pass


def reply(route, data=None, status=200, message=None):
    route.fulfill(status=status, content_type="application/json",
                  body=json.dumps({"code": 200 if status < 300 else status, "msg": message, "data": data}))


class Api:
    def __init__(self):
        self.details = fixtures()
        self.errors = {(7, 108): (404, "评审任务不存在或不属于所选仓库"),
                       (7, 109): (500, "无法读取评审详情，请稍后重试")}
        self.pause = set()
        self.held = {}
        self.hold_post = False
        self.posts = []
        self.next_job = job(201, status="QUEUED")
        self.post_error = None

    def handle(self, route):
        request = route.request
        path = request.url.split("/api", 1)[1]
        if request.headers.get("authorization") != "Bearer " + TOKEN:
            return reply(route, status=401, message="工作台令牌无效或已过期，请重新输入。")
        if path == "/repositories":
            return reply(route, REPOS)
        match = re.fullmatch(r"/repositories/(\d+)/jobs(?:/(\d+))?", path)
        assert match, path
        repo = int(match[1])
        if request.method == "POST":
            self.posts.append((repo, request.post_data_json))
            if self.hold_post:
                self.held["post"] = route
            else:
                self.finish_post(route)
            return
        if match[2] is None:
            return reply(route, [item["job"] for (repository, _), item in self.details.items() if repository == repo])
        key = repo, int(match[2])
        if key in self.pause:
            self.held[key] = route
        else:
            self.finish_detail(route, key)

    def finish_detail(self, route, key):
        if key in self.errors:
            status, message = self.errors[key]
            reply(route, status=status, message=message)
        else:
            reply(route, deepcopy(self.details[key]))

    def finish_post(self, route):
        if self.post_error:
            status, message = self.post_error
            return reply(route, status=status, message=message)
        result = deepcopy(self.next_job)
        if (result["repositoryId"], result["id"]) not in self.details:
            item = detail(result["id"], status=result["status"])
            item["job"] = result
            item["result"].update(availability="NOT_STARTED", publishedCount=None, findings=[],
                                  reviewRecordId=None, outcome=None, occurredAt=None)
            self.details[result["repositoryId"], result["id"]] = item
        reply(route, result, 202)


def run(options, origin):
    api = Api()
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, executable_path=options.browser)
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        page.set_default_timeout(7000)
        failures = []
        page.on("pageerror", lambda err: failures.append(str(err)))
        page.route("**/api/**", api.handle)
        el = page.get_by_test_id
        panel = el("review-detail")

        def settle():
            page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")

        def wait_held(key):
            deadline = time.monotonic() + 5
            while key not in api.held and time.monotonic() < deadline:
                page.wait_for_timeout(20)
            assert key in api.held, f"Request not captured: {key}"

        def open_job(job_id):
            el(f"review-details-{job_id}").click()
            expect(panel).to_have_attribute("data-job-id", str(job_id))
            expect(el("review-detail-loading")).to_have_count(0)

        def screenshot(name, width):
            page.set_viewport_size({"width": width, "height": 900})
            expect(panel).to_be_visible()
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"), name
            panel.screenshot(path=str(options.artifacts / (name + ".png")))

        try:
            page.goto(origin + "/reviews", wait_until="networkidle")
            el("workbench-token").fill(TOKEN)
            el("workbench-unlock").click()
            expect(el("review-details-101")).to_be_visible()
            # Keyboard entry, title focus, Escape return, and live partial-write text.
            el("review-details-101").focus()
            page.keyboard.press("Enter")
            expect(el("review-detail-title")).to_be_focused()
            expect(panel).to_contain_text("部分评论已发布，本轮已发布 1 条发现")
            expect(panel).to_contain_text("新侧 24 行")
            expect(panel).to_contain_text("旧侧 18 行")
            expect(panel).to_contain_text('<img src=x onerror="window.injected=true">')
            expect(panel.locator("img")).to_have_count(0)
            assert page.evaluate("window.injected") is None
            expect(panel.get_by_role("link", name="查看已发布评论")).to_have_attribute("href", change_url(REPOS[0]) + "#issuecomment-28")
            page.keyboard.press("Tab")
            expect(el("review-detail-refresh")).to_be_focused()
            screenshot("partial-desktop", 1440)
            screenshot("partial-mobile-320", 320)
            el("review-detail-close").focus()
            page.keyboard.press("Escape")
            expect(panel).to_have_count(0)
            expect(el("review-details-101")).to_be_focused()

            open_job(102)
            expect(panel).to_contain_text("审查完成，本轮没有新增发现")
            expect(panel).to_contain_text("未选中 spec，本轮跳过 spec 审查")
            expect(panel).to_contain_text("已确定无编码规范文档")
            expect(el("review-retry")).to_have_count(0)
            open_job(103)
            expect(panel).to_contain_text("旧任务未保存轮次关联，详细结果和精确数量未知")
            expect(panel).to_contain_text("已记录 0 条，实际已发布总数未知")
            expect(panel).to_contain_text("是否跳过 spec 未记录")
            expect(panel).not_to_contain_text("本轮没有新增发现")
            screenshot("legacy-mobile-320", 320)
            open_job(104)
            expect(panel).to_contain_text("已记录 1 条，实际已发布总数未知")
            expect(panel).to_contain_text("恢复标记结束")
            expect(panel).to_contain_text("并非进程实际中断时刻")
            expect(panel).to_contain_text("远端可能已收到部分评论")
            screenshot("interrupted-mobile-320", 320)
            open_job(105)
            expect(panel).to_contain_text("任务正在运行")
            expect(el("review-findings")).to_be_visible()
            expect(el("review-retry")).to_have_count(0)
            open_job(106)
            expect(panel).to_contain_text("任务正在排队")
            expect(panel).to_contain_text("未取得")
            expect(panel).not_to_contain_text("本轮没有新增发现")
            open_job(107)
            expect(panel).to_contain_text("本轮确认已发布 1 条发现")
            expect(el("review-retry")).to_have_count(0)
            open_job(108)
            expect(el("review-detail-error")).to_contain_text("不存在或不属于所选仓库")
            expect(el("review-findings")).to_have_count(0)
            open_job(109)
            expect(el("review-detail-error")).to_contain_text("无法读取评审详情")
            screenshot("error-mobile-320", 320)
            del api.errors[7, 109]
            el("review-detail-refresh").click()
            expect(el("review-findings")).to_be_visible()
            api.errors[7, 109] = (500, "读取失败")
            el("review-detail-refresh").click()
            expect(el("review-detail-error")).to_contain_text("刷新失败，以下保留上次读取的内容")
            expect(el("review-findings")).to_be_visible()
            api.errors[7, 109] = (404, "任务已归并")
            el("review-detail-refresh").click()
            expect(el("review-detail-error")).to_contain_text("不存在或不属于所选仓库")
            expect(el("review-findings")).to_have_count(0)
            open_job(110)
            expect(panel).to_contain_text("本轮确认已发布 0 条发现")
            expect(panel).not_to_contain_text("审查完成")
            open_job(112)
            expect(panel.get_by_role("link")).to_have_count(0)

            # Existing activity only navigates, without another POST.
            open_job(111)
            before = len(api.posts)
            el("review-active-job").click()
            expect(panel).to_have_attribute("data-job-id", "105")
            expect(panel).to_contain_text("任务正在运行")
            assert len(api.posts) == before

            # Successful retry follows the actual returned task; rapid clicks send once.
            open_job(101)
            api.hold_post = True
            before = len(api.posts)
            el("review-retry").evaluate("button => { button.click(); button.click(); button.click(); }")
            wait_held("post")
            expect(el("review-retry")).to_be_disabled()
            assert len(api.posts) == before + 1
            screenshot("retry-loading-mobile-320", 320)
            api.finish_post(api.held.pop("post"))
            api.hold_post = False
            expect(panel).to_have_attribute("data-job-id", "201")
            expect(el("review-retry-notice")).to_contain_text("#201")
            expect(el("review-details-101")).to_be_visible()
            open_job(101)
            api.next_job = job(105, status="RUNNING")
            el("review-retry").click()
            expect(panel).to_have_attribute("data-job-id", "105")
            expect(el("review-retry-notice")).to_contain_text("复用已有任务")
            open_job(101)
            api.next_job = job(101)
            el("review-retry").click()
            expect(el("review-retry-notice")).to_contain_text("本次未创建新任务，请在该任务结束三秒后再试")
            expect(panel).to_have_attribute("data-job-id", "101")
            for code in (409, 502, 503):
                api.post_error = code, f"安全提交错误 {code}"
                el("review-retry").click()
                expect(el("review-retry-error")).to_contain_text(str(code))
                expect(panel).to_have_attribute("data-job-id", "101")
                expect(el("review-findings")).to_be_visible()
            api.post_error = None

            # GET arrival order: another task, another repository, and stale failure.
            api.pause.add((7, 101))
            el("review-details-101").click()
            wait_held((7, 101))
            expect(el("review-detail-loading")).to_be_visible()
            open_job(102)
            reply(api.held.pop((7, 101)), detail())
            settle()
            expect(panel).to_have_attribute("data-job-id", "102")
            expect(panel).to_contain_text("审查完成，本轮没有新增发现")
            el("review-details-101").click()
            wait_held((7, 101))
            expect(panel).not_to_contain_text("审查完成")
            screenshot("first-load-mobile-320", 320)
            el("repo-select").select_option("9")
            open_job(101)
            reply(api.held.pop((7, 101)), status=500, message="STALE DETAIL ERROR")
            settle()
            expect(panel).to_have_attribute("data-repository-id", "9")
            expect(panel).not_to_contain_text("STALE")
            expect(panel.get_by_role("link", name="查看已发布评论")).to_have_attribute("href", change_url(REPOS[1]) + "#note_28")
            api.pause.clear()
            el("repo-select").select_option("7")
            open_job(101)

            # Old POST must not change notices, selection, rows or a new pending button.
            api.hold_post = True
            api.next_job = job(299, status="QUEUED")
            el("review-retry").click()
            wait_held("post")
            open_job(102)
            api.finish_post(api.held.pop("post"))
            settle()
            expect(panel).to_have_attribute("data-job-id", "102")
            expect(el("review-retry-notice")).to_have_count(0)
            open_job(101)
            el("review-retry").click()
            wait_held("post")
            el("repo-select").select_option("9")
            open_job(101)
            reply(api.held.pop("post"), status=503, message="STALE RETRY ERROR")
            settle()
            expect(panel).to_have_attribute("data-repository-id", "9")
            expect(el("review-retry-error")).to_have_count(0)
            expect(el("review-retry")).to_be_enabled()
            # A stale completion cannot unlock the current task's separate POST.
            el("repo-select").select_option("7")
            open_job(101)
            el("review-retry").click()
            wait_held("post")
            old_request = api.held.pop("post")
            open_job(110)
            el("review-retry").click()
            wait_held("post")
            reply(old_request, status=503, message="STALE SECOND REQUEST")
            settle()
            expect(el("review-retry")).to_be_disabled()
            expect(el("review-retry-error")).to_have_count(0)
            api.next_job = job(110)
            api.finish_post(api.held.pop("post"))
            expect(el("review-retry")).to_be_enabled()
            expect(panel).to_have_attribute("data-job-id", "110")
            api.hold_post = False

            # Reject a response carrying another repository's task, even for the same ID.
            bad = deepcopy(api.details[7, 107])
            api.details[7, 107]["job"]["repositoryId"] = 9
            open_job(107)
            expect(el("review-detail-error")).to_contain_text("不存在或不属于所选仓库")
            expect(el("review-findings")).to_have_count(0)
            api.details[7, 107] = bad

            # 401 clears the whole protected view; late success after locking stays out.
            el("repo-select").select_option("7")
            open_job(101)
            api.hold_post = True
            el("review-retry").click()
            wait_held("post")
            page.get_by_role("button", name="锁定工作台").click()
            expect(el("workbench-token")).to_be_visible()
            api.finish_post(api.held.pop("post"))
            settle()
            expect(panel).to_have_count(0)
            el("workbench-token").fill(TOKEN)
            el("workbench-unlock").click()
            open_job(101)
            el("review-retry").click()
            wait_held("post")
            old_session_request = api.held.pop("post")
            page.get_by_role("button", name="锁定工作台").click()
            el("workbench-token").fill(TOKEN)
            el("workbench-unlock").click()
            open_job(102)
            reply(old_session_request, status=401, message="OLD SESSION EXPIRED")
            settle()
            expect(panel).to_have_attribute("data-job-id", "102")
            expect(el("workbench-token")).to_have_count(0)
            api.errors[7, 101] = (401, "令牌已失效")
            el("review-details-101").click()
            expect(el("workbench-token")).to_be_visible()
            expect(panel).to_have_count(0)
            assert not failures, failures
            print("PASS: compiled UI states, safe links/text, 320px + desktop, keyboard focus, "
                  "retry new/reused/cooldown/errors, click lock, stale GET/POST isolation, logout/401.")
            print(f"Evidence: {options.artifacts.resolve()}")
        except Exception:
            page.screenshot(path=str(options.artifacts / "failure.png"), full_page=True)
            (options.artifacts / "failure.txt").write_text(
                json.dumps({"pageErrors": failures, "body": page.locator("body").inner_text()}, ensure_ascii=False),
                encoding="utf-8")
            raise
        finally:
            for route in list(api.held.values()):
                route.abort()
            browser.close()


def run_auth_contexts(options, origin):
    """R1: old 401 cannot invalidate a different task, repository, or unlock session."""
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, executable_path=options.browser)
        try:
            for method in ("GET", "POST"):
                for switch in ("task", "repository", "session"):
                    for status in (401, 200):
                        api = Api()
                        page = browser.new_page(viewport={"width": 1440, "height": 900})
                        page.set_default_timeout(5000)
                        page.route("**/api/**", api.handle)
                        el = page.get_by_test_id
                        panel = el("review-detail")
                        scenario = f"{method}-{switch}-http{status}"
                        try:
                            page.goto(origin + "/reviews", wait_until="networkidle")
                            el("workbench-token").fill(TOKEN)
                            el("workbench-unlock").click()
                            expect(el("review-details-101")).to_be_visible()
                            if method == "GET":
                                api.pause.add((7, 101))
                                key = (7, 101)
                                el("review-details-101").click()
                            else:
                                el("review-details-101").click()
                                api.hold_post = True
                                key = "post"
                                el("review-retry").click()
                            deadline = time.monotonic() + 5
                            while key not in api.held and time.monotonic() < deadline:
                                page.wait_for_timeout(20)
                            assert key in api.held, scenario
                            old = api.held.pop(key)
                            api.pause.clear()
                            if switch == "repository":
                                el("repo-select").select_option("9")
                                target_id, repo_id = 101, 9
                            else:
                                if switch == "session":
                                    page.get_by_role("button", name="锁定工作台").click()
                                    el("workbench-token").fill(TOKEN)
                                    el("workbench-unlock").click()
                                target_id, repo_id = 102, 7
                            el(f"review-details-{target_id}").click()
                            expect(panel).to_have_attribute("data-job-id", str(target_id))
                            expect(el("review-detail-loading")).to_have_count(0)
                            old.fulfill(status=status, content_type="application/json",
                                        body=json.dumps({"code": 401, "msg": "OLD CONTEXT EXPIRED", "data": None}))
                            page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")
                            expect(panel).to_have_attribute("data-job-id", str(target_id))
                            expect(panel).to_have_attribute("data-repository-id", str(repo_id))
                            expect(el("workbench-token")).to_have_count(0)
                            expect(el("review-detail-error")).to_have_count(0)
                            expect(el("review-retry-error")).to_have_count(0)
                            # A fresh 401 in that same current context must still lock it.
                            if method == "GET":
                                api.errors[repo_id, target_id] = (401, "CURRENT CONTEXT EXPIRED")
                                el("review-detail-refresh").click()
                            else:
                                api.hold_post = False
                                api.post_error = (401, "CURRENT CONTEXT EXPIRED")
                                el("review-number").fill("43")
                                el("review-trigger").click()
                            expect(el("workbench-token")).to_be_visible()
                            expect(panel).to_have_count(0)
                            print("PASS R1:", scenario)
                        except Exception:
                            page.screenshot(path=str(options.artifacts / ("r1-" + scenario + ".png")), full_page=True)
                            print("FAIL R1:", scenario)
                            raise
                        finally:
                            page.close()
        finally:
            browser.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", help="Optional Chromium/Chrome executable")
    parser.add_argument("--auth-only", action="store_true", help="Only run the focused stale-401 regression matrix")
    parser.add_argument("--artifacts", type=Path, default=WEB / "test-artifacts/review-details")
    options = parser.parse_args()
    if not (WEB / "dist/index.html").is_file():
        parser.error("Run npm --prefix web run build first")
    options.artifacts.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(StaticHandler, directory=str(WEB / "dist")))
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        origin = f"http://127.0.0.1:{server.server_port}"
        if not options.auth_only:
            run(options, origin)
        run_auth_contexts(options, origin)
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == "__main__":
    main()
