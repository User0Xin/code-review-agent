"""只在 loopback 上提供合成 GitHub / OpenAI 响应；记录模型实际收到的输入。"""
import json
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit


class Example:
    def __init__(self, name, number, repository_id, digit):
        self.name, self.number, self.repository_id = name, number, repository_id
        self.full_name = f"smoke/{name}"
        self.standard = f"rules/{name}.md"
        self.spec = f"docs/{name}.md"
        self.rule_text = f"{name}: standard at rules branch"
        self.spec_text = f"{name}: spec at pull request head"
        self.skill = f"---\nname: review-{name}\ndescription: Local smoke fixture\n---\n\n# Review skill for {name}\n"
        self.rules_ref = digit * 40
        self.head_ref = ("a" if name == "alpha" else "b") * 40


ALPHA = Example("alpha", 41, 101, "1")
BETA = Example("beta", 52, 102, "2")
EXAMPLES = (ALPHA, BETA)


class Fixture:
    def __init__(self):
        self.model_inputs = {}
        self.notes = []
        self.unexpected = []
        self.model_entered = threading.Event()
        self.finish_alpha = threading.Event()

    def response(self, method, target, body):
        uri = urlsplit(target)
        path = unquote(uri.path)
        if method == "POST" and path == "/v1/chat/completions":
            messages = {m["role"]: m["content"] for m in body["messages"]}
            request = json.loads(messages["user"])
            if request["task"] != "review":
                raise ValueError(f"Unexpected model task: {request['task']}")
            payload = request["input"]
            example = next(x for x in EXAMPLES if payload["headSha"] == x.head_ref)
            self.model_inputs[example.name] = (messages["system"], payload)
            if example is ALPHA:
                self.model_entered.set()
                if not self.finish_alpha.wait(timeout=25):
                    raise TimeoutError("Browser did not observe the active job")
                answer = json.dumps({"drafts": [], "resolvedDiscussionIds": []})
            else:
                # Valid transport but invalid review JSON exercises the service's normal failure path.
                answer = "fixture deliberately returned invalid JSON"
            return 200, {"id": "smoke", "object": "chat.completion", "created": 1, "model": "smoke",
                         "choices": [{"index": 0, "finish_reason": "stop",
                                      "message": {"role": "assistant", "content": answer}}]}
        for x in EXAMPLES:
            prefix = f"/repos/{x.full_name}"
            if not (path == prefix or path.startswith(prefix + "/")):
                continue
            route = path[len(prefix):]
            if method == "GET":
                if not route:
                    return 200, {"id": x.repository_id, "full_name": x.full_name, "default_branch": "main"}
                if route == f"/pulls/{x.number}":
                    return 200, {"head": {"sha": x.head_ref}, "base": {"sha": x.rules_ref},
                                 "body": f"{x.name} change", "changed_files": 1}
                if route == f"/pulls/{x.number}/files":
                    return 200, [{"filename": "demo.txt", "status": "modified",
                                  "patch": "@@ -1 +1 @@\n-before\n+after", "additions": 1, "deletions": 1}]
                if route == "/commits/main":
                    return 200, {"sha": x.rules_ref}
                if route in (f"/git/trees/{x.head_ref}", f"/git/trees/{x.rules_ref}"):
                    return 200, {"truncated": False, "tree": [
                        {"type": "blob", "path": f} for f in (x.standard, x.spec, "docs/unused.md")]}
                if route.startswith("/contents/"):
                    key = (route.removeprefix("/contents/"), parse_qs(uri.query).get("ref", [""])[0])
                    allowed = {(x.standard, x.rules_ref): x.rule_text, (x.spec, x.head_ref): x.spec_text}
                    if key in allowed:
                        return 200, allowed[key]
            if method == "POST" and route == f"/issues/{x.number}/comments":
                self.notes.append((x.name, body["body"]))
                return 201, {"id": 10 + len(self.notes)}
        raise ValueError(f"Unexpected fixture request: {method} {path}")

    def verify_inputs(self):
        assert set(self.model_inputs) == {x.name for x in EXAMPLES}, self.model_inputs.keys()
        for x in EXAMPLES:
            system, payload = self.model_inputs[x.name]
            assert system == x.skill, f"{x.name}: wrong effective skill"
            assert payload["codingStandards"]["documents"] == [{"path": x.standard, "content": x.rule_text}]
            assert payload["spec"]["documents"] == [{"path": x.spec, "content": x.spec_text}]
            assert payload["headSha"] == x.head_ref and payload["mergeRequestIid"] == x.number
        assert not self.unexpected, self.unexpected
        assert [name for name, _ in self.notes] == ["beta"], "The failed review should write one local failure note"


@contextmanager
def serve_fixture():
    fixture = Fixture()

    class Handler(BaseHTTPRequestHandler):
        def dispatch(self):
            try:
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length)) if length else None
                status, response = fixture.response(self.command, self.path, body)
            except Exception as error:
                fixture.unexpected.append(str(error))
                status, response = 404, {"error": "Unknown local fixture request"}
            raw = isinstance(response, str)
            data = (response if raw else json.dumps(response)).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", ("text/plain" if raw else "application/json") + "; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        do_GET = do_POST = dispatch

        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield fixture, server.server_port
        finally:
            fixture.finish_alpha.set()
            server.shutdown()
            thread.join()
