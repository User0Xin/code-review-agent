"""独立验收真实生产 jar 的任务详情、重试和中断；外部平台/模型仅用本机替身。"""
import argparse
from contextlib import closing, contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import sqlite3
import tempfile
import threading
import time
from urllib.parse import urlsplit

from jar_check import request, running, seed
from platform_fixture import ALPHA, BETA, EXAMPLES, Fixture
from run import ROOT, free_port, mutation
from playwright.sync_api import expect, sync_playwright


BODY = '第一条已发布发现\n<script>window.__unsafe = true</script>\n' + '长正文' * 80


def browser_retry(options, base, token, repo, original):
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, executable_path=options.browser)
        try:
            page = browser.new_page()
            page.set_default_timeout(12000)
            page.goto(base + '/reviews', wait_until='networkidle')
            el = page.get_by_test_id
            el('workbench-token').fill(token)
            el('workbench-unlock').click()
            el('repo-select').select_option(str(repo))
            el(f'review-details-{original}').click()
            expect(el('review-retry')).to_be_enabled()
            posts = []
            page.on('request', lambda req: posts.append(req.url)
                    if req.method == 'POST' and req.url.endswith('/jobs') else None)
            returned = mutation(page, 'POST', f'/api/repositories/{repo}/jobs',
                                lambda: el('review-retry').evaluate('(b) => { b.click(); b.click(); }'))
            expect(el('review-detail')).to_have_attribute('data-job-id', str(returned['id']))
            assert len(posts) == 1, 'double click submitted twice'
            return returned['id']
        finally:
            browser.close()


def browser_check(options, base, token, alpha, beta, zero, first, second):
    """页面数据来自实际 jar；仅延迟真实响应，以可重复地检查切换竞态。"""
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, executable_path=options.browser)
        try:
            page = browser.new_page(viewport={'width': 1440, 'height': 900})
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.set_default_timeout(12000)
            page.goto(base + '/reviews', wait_until='networkidle')
            el = page.get_by_test_id
            el('workbench-token').fill(token)
            el('workbench-unlock').click()
            el('repo-select').select_option(str(beta))
            el(f'review-details-{first}').click()
            panel = el('review-detail')
            expect(panel).to_have_attribute('data-job-id', str(first))
            expect(panel).to_contain_text(BODY)
            assert page.evaluate('window.__unsafe === undefined')
            for width, height in [(1440, 900), (320, 844)]:
                page.set_viewport_size({'width': width, 'height': height})
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), 'detail overflow'
                page.screenshot(path=str(options.artifacts / f'details-{width}.png'), full_page=True)
            el('review-detail-close').click()
            el(f'review-details-{second}').focus()
            page.keyboard.press('Enter')
            expect(panel).to_have_attribute('data-job-id', str(second))
            expect(panel).not_to_contain_text(BODY)
            expect(el('review-retry')).to_have_count(0)
            el('repo-select').select_option(str(alpha))
            el(f'review-details-{zero}').click()
            expect(panel).to_contain_text('没有新增发现')
            # Hold the actual beta response, navigate elsewhere, then deliver it late.
            held = []
            pattern = f'**/api/repositories/{beta}/jobs/{first}'
            def delay(route):
                held.append((route, route.fetch()))
            page.route(pattern, delay)
            el('repo-select').select_option(str(beta))
            el(f'review-details-{first}').click()
            expect(el('review-detail-loading')).to_be_visible()
            deadline = time.monotonic() + 10
            while not held and time.monotonic() < deadline:
                page.wait_for_timeout(50)
            assert held, 'detail response was not captured'
            el('repo-select').select_option(str(alpha))
            el(f'review-details-{zero}').click()
            expect(panel).to_contain_text('没有新增发现')
            for route, response in held:
                route.fulfill(response=response)
            page.unroute(pattern, delay)
            page.wait_for_load_state('networkidle')
            expect(panel).to_have_attribute('data-repository-id', str(alpha))
            expect(panel).to_have_attribute('data-job-id', str(zero))
            expect(panel).to_contain_text('没有新增发现')
            expect(panel).not_to_contain_text(BODY)
            # Repeat within one mounted repository component: task 1 must not overwrite task 2.
            held.clear()
            page.route(pattern, delay)
            el('repo-select').select_option(str(beta))
            el(f'review-details-{first}').click()
            expect(el('review-detail-loading')).to_be_visible()
            deadline = time.monotonic() + 10
            while not held and time.monotonic() < deadline:
                page.wait_for_timeout(50)
            assert held
            el(f'review-details-{second}').click()
            expect(panel).to_contain_text('SECOND')
            for route, response in held:
                route.fulfill(response=response)
            page.unroute(pattern, delay)
            page.wait_for_load_state('networkidle')
            expect(panel).to_have_attribute('data-job-id', str(second))
            expect(panel).to_contain_text('SECOND')
            expect(panel).not_to_contain_text(BODY)
            assert not errors, errors
        finally:
            browser.close()


class DetailsFixture(Fixture):
    def __init__(self):
        super().__init__()
        self.mode = 'partial'
        self.calls = 0
        self.inputs = []
        self.comments = {35: '历史发现'}
        self.blocked = threading.Event()
        self.release = threading.Event()

    def response(self, method, target, body):
        path = urlsplit(target).path
        if path == '/v1/chat/completions':
            self.calls += 1
            messages = {m['role']: m['content'] for m in body['messages']}
            payload = json.loads(messages['user'])['input']
            self.inputs.append(payload)
            if payload['headSha'] == ALPHA.head_ref:
                drafts = []
            else:
                drafts = [dict(category='缺陷', path='src/' + 'long/' * 25 + 'Example.java',
                               commentBody=BODY, citation='<b>引用应为文本</b>'),
                          dict(category='缺陷', path='second.java', commentBody='SECOND')]
                if self.mode == 'interrupt':
                    for draft in drafts:
                        draft['commentBody'] += '（中断轮次）'
            answer = json.dumps({'drafts': drafts, 'resolvedDiscussionIds': []})
            return 200, {'id': 'details', 'object': 'chat.completion', 'created': 1, 'model': 'smoke',
                         'choices': [{'index': 0, 'finish_reason': 'stop',
                                      'message': {'role': 'assistant', 'content': answer}}]}
        if '/issues/comments/' in path:
            key = int(path.rsplit('/', 1)[1])
            if method == 'GET':
                return (200, {'id': key, 'body': self.comments[key]}) if key in self.comments else (404, {})
            if method == 'DELETE':
                self.comments.pop(key, None)
                return 200, {}
        if method == 'POST' and path.endswith('/comments'):
            if body['body'].startswith('SECOND'):
                if self.mode == 'partial':
                    return 403, {'message': 'fixture-secret-must-not-leak'}
                if self.mode == 'interrupt':
                    self.blocked.set()
                    self.release.wait(40)
            key = max([100, *self.comments]) + 1
            self.comments[key] = body['body']
            return 201, {'id': key}
        return super().response(method, target, body)


@contextmanager
def fixture_server():
    fixture = DetailsFixture()

    class Handler(BaseHTTPRequestHandler):
        def dispatch(self):
            try:
                length = int(self.headers.get('Content-Length', 0))
                body = json.loads(self.rfile.read(length)) if length else None
                status, response = fixture.response(self.command, self.path, body)
            except Exception as error:
                fixture.unexpected.append(str(error))
                status, response = 500, {'message': 'fixture error'}
            raw = isinstance(response, str)
            data = (response if raw else json.dumps(response)).encode()
            try:
                self.send_response(status)
                self.send_header('Content-Type', 'text/plain' if raw else 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                # The interruption scenario deliberately kills the client before this reply.
                pass

        do_GET = do_POST = do_DELETE = dispatch

        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield fixture, server.server_port
        finally:
            fixture.release.set()
            server.shutdown()
            thread.join()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/code-review-agent-0.0.1-SNAPSHOT.jar')
    parser.add_argument('--artifacts', type=Path, default=ROOT / 'target/details-check')
    parser.add_argument('--browser')
    parser.add_argument('--skip-browser', action='store_true', help='仅用于后端定位；不代表浏览器验收通过')
    options = parser.parse_args()
    options.artifacts.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(24)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    with tempfile.TemporaryDirectory(prefix='details-check-') as temp, fixture_server() as (fixture, port):
        database = Path(temp) / 'details.db'
        seed(database)
        # Upgrade an actual pre-feature job table; no task association may be inferred.
        with closing(sqlite3.connect(database)) as db:
            db.executescript('''CREATE TABLE workbench_job (
                id INTEGER PRIMARY KEY AUTOINCREMENT, repository_id INTEGER NOT NULL,
                change_number INTEGER NOT NULL, head_sha TEXT NOT NULL, base_sha TEXT NOT NULL,
                start_sha TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL,
                message TEXT, created_at TEXT NOT NULL, finished_at TEXT);
                INSERT INTO workbench_job VALUES (9001,1,41,'legacy','base','start','FAILED','manual',
                'fixture-secret-must-not-leak','2026-01-01T00:00:00Z','2026-01-01T00:00:01Z');''')
            db.execute('ALTER TABLE review_record ADD COLUMN head_sha TEXT')
            db.execute('UPDATE review_record SET project_id=?,merge_request_iid=?,head_sha=?',
                       (-ALPHA.repository_id, ALPHA.number, ALPHA.head_ref))
            db.execute('UPDATE finding SET project_id=?,merge_request_iid=?,head_sha=?',
                       (-ALPHA.repository_id, ALPHA.number, ALPHA.head_ref))
            db.execute('UPDATE workbench_job SET head_sha=?,base_sha=?,start_sha=?',
                       (ALPHA.head_ref, ALPHA.rules_ref, ALPHA.rules_ref))
            db.commit()
        base, upstream = f'http://127.0.0.1:{free_port()}', f'http://127.0.0.1:{port}'
        args = [java, '-jar', str(options.jar.resolve()), '--server.address=127.0.0.1',
                f'--server.port={urlsplit(base).port}', f'--spring.datasource.url=jdbc:sqlite:{database.as_posix()}',
                f'--review.workbench.token={token}', f'--review.github.base-url={upstream}',
                '--review.github.token=fixture-only', '--review.gitlab.webhook-enabled=false',
                '--review.gitlab.base-url=http://127.0.0.1:1',
                f'--spring.ai.openai.base-url={upstream}', f'--spring.ai.openai.chat.base-url={upstream}',
                '--spring.ai.openai.api-key=fixture-only', '--spring.ai.openai.chat.api-key=fixture-only',
                '--spring.ai.openai.chat.options.model=smoke', '--spring.ai.retry.max-attempts=1']

        def api(path, method='GET', data=None, expected=200):
            status, headers, raw = request(base + path, token, method, data)
            value = json.loads(raw)
            assert status == expected and value['code'] == (200 if expected == 202 else expected), (path, status, value)
            assert 'fixture-secret' not in raw.decode() and 'failureDetail' not in raw.decode()
            assert headers['Cache-Control'] == 'no-store'
            return value['data']

        def detail(repo, job):
            return api(f'/api/repositories/{repo}/jobs/{job}')

        def start(repo, number):
            return api(f'/api/repositories/{repo}/jobs', 'POST', {'number': number}, 202)['id']

        def finished(repo, job):
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                result = detail(repo, job)
                if result['job']['status'] in ('FAILED', 'SUCCEEDED'):
                    return result
                time.sleep(.1)
            raise AssertionError('job did not finish')

        with running(args, os.environ.copy(), base, options.artifacts / 'first.log'):
            ids = {}
            for x in EXAMPLES:
                ids[x.name] = api('/api/repositories', 'POST',
                                 {'url': f'https://127.0.0.1:{port}/{x.full_name}',
                                  'standardPaths': [x.standard], 'specPaths': [x.spec]})['id']
            alpha, beta = ids['alpha'], ids['beta']
            legacy = detail(alpha, 9001)
            assert legacy['result']['availability'] == 'LEGACY_UNLINKED'
            assert legacy['result']['publishedCount'] is None and legacy['result']['findings'] == []
            zero = start(alpha, ALPHA.number)
            assert finished(alpha, zero)['result']['publishedCount'] == 0
            first = start(beta, BETA.number)
            partial = finished(beta, first)
            assert partial['job']['status'] == 'FAILED', partial
            assert partial['failure']['stage'] == 'PUBLISH', partial
            assert partial['result']['publishedCount'] == 1, partial
            assert [f['commentBody'] for f in partial['result']['findings']] == [BODY]
            assert partial['result']['findings'][0]['discussionUrl'].endswith('#issuecomment-101')
            api(f'/api/repositories/{alpha}/jobs/{first}', expected=404)
            api(f'/api/repositories/{beta}/jobs/0', expected=400)
            api(f'/api/repositories/{beta}/jobs/not-a-number', expected=400)
            for credential in (None, 'wrong'):
                code, _, raw = request(base + f'/api/repositories/{alpha}/jobs/{first}', credential)
                assert code == 401 and json.loads(raw)['code'] == 401
            assert start(beta, BETA.number) == first, 'cooldown created a new task'
            time.sleep(3.1)
            fixture.mode = 'success'
            second = start(beta, BETA.number) if options.skip_browser else browser_retry(options, base, token, beta, first)
            second_result = finished(beta, second)
            assert second != first and second_result['job']['status'] == 'SUCCEEDED', second_result
            assert second_result['result']['publishedCount'] == 1, second_result
            assert [f['commentBody'] for f in second_result['result']['findings']] == ['SECOND']
            assert detail(beta, first) == partial, 'old task mutated during retry'
            assert list(fixture.comments.values()).count(BODY) == 1
            assert fixture.calls == 3, fixture.calls
            saved = {job: detail(repo, job) for repo, job in [(alpha, zero), (beta, first), (beta, second)]}
            if not options.skip_browser:
                browser_check(options, base, token, alpha, beta, zero, first, second)
        with running(args, os.environ.copy(), base, options.artifacts / 'restart.log') as process:
            for repo, job in [(alpha, zero), (beta, first), (beta, second)]:
                assert detail(repo, job) == saved[job]
            assert fixture.calls == 3, 'restart replayed jobs'
            BETA.head_ref = 'c' * 40
            BETA.spec = 'docs/current-config.md'
            api(f'/api/repositories/{beta}', 'PUT',
                {'url': f'https://127.0.0.1:{port}/{BETA.full_name}',
                 'standardPaths': [BETA.standard], 'specPaths': [BETA.spec]})
            fixture.mode = 'interrupt'
            interrupted = start(beta, BETA.number)
            assert fixture.blocked.wait(20), 'second publication did not block'
            assert fixture.inputs[-1]['headSha'] == BETA.head_ref
            assert fixture.inputs[-1]['spec']['documents'][0]['path'] == BETA.spec
            assert detail(beta, first)['result'] == saved[first]['result'], 'configuration rewrote history'
            with closing(sqlite3.connect(database)) as db:
                assert db.execute('SELECT count(*) FROM finding WHERE workbench_job_id=?',
                                  (interrupted,)).fetchone()[0] == 1
                assert db.execute('SELECT count(*) FROM review_record WHERE workbench_job_id=?',
                                  (interrupted,)).fetchone()[0] == 0
            live = detail(beta, interrupted)
            assert live['job']['status'] == 'RUNNING' and len(live['result']['findings']) == 1
            assert live['result']['publishedCount'] is None
            process.kill()
            process.wait(15)
        fixture.release.set()
        calls = fixture.calls
        with running(args, os.environ.copy(), base, options.artifacts / 'interrupted-restart.log'):
            recovered = detail(beta, interrupted)
            assert recovered['job']['status'] == 'FAILED'
            assert recovered['failure']['stage'] == 'INTERRUPTED' and recovered['failure']['interrupted']
            assert recovered['result']['availability'] == 'MISSING'
            assert recovered['result']['publishedCount'] is None and len(recovered['result']['findings']) == 1
            time.sleep(2)
            assert fixture.calls == calls, 'interrupted review automatically replayed'
        # A separate crash window: the record exists but the task has not finished.
        with closing(sqlite3.connect(database)) as db:
            db.execute("UPDATE workbench_job SET status='RUNNING', finished_at=NULL WHERE id=?", (second,))
            db.commit()
        with running(args, os.environ.copy(), base, options.artifacts / 'recorded-restart.log'):
            recovered = detail(beta, second)
            assert recovered['failure']['stage'] == 'INTERRUPTED'
            assert recovered['result'] == saved[second]['result']
            assert fixture.calls == calls, 'recorded result was replayed'
        assert not fixture.unexpected, fixture.unexpected
    print('PASS: real jar details, zero vs partial, same-SHA retry isolation, cooldown, history dedup, '
          'safe errors, legacy upgrade, restart persistence, interrupted partial findings and recorded-result recovery. '
          + ('Browser SKIPPED.' if options.skip_browser else 'Browser PASS: retry, desktop/320px and delayed responses.'))


if __name__ == '__main__':
    main()
