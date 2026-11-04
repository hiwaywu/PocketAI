"""Tiny fake OpenAI-compatible API for testing Pocket AI on the emulator (http://10.0.2.2:8765)."""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = []


def chunk(delta, finish=None):
    return "data: " + json.dumps({"choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}) + "\n\n"


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.endswith("/models"):
            body = json.dumps({"data": [{"id": "mock-large"}, {"id": "mock-mini"}, {"id": "mock-vision"}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        req = json.loads(self.rfile.read(n))
        msgs = req["messages"]
        LOG.append({"model": req.get("model"), "auth": self.headers.get("Authorization", "")[:12],
                    "roles": [m["role"] for m in msgs], "tools": bool(req.get("tools")), "bytes": n,
                    "system_tail": msgs[0]["content"][-140:]})
        with open("mock_requests.log", "a", encoding="utf-8") as f:
            f.write(json.dumps(LOG[-1]) + "\n")
        last = msgs[-1]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def send(s):
            self.wfile.write(s.encode())
            self.wfile.flush()
            time.sleep(0.03)

        if last["role"] == "tool":
            result = last["content"]
            lines = [l for l in result.splitlines() if l.strip()][:6]
            text = "## Results from the web\n\nHere is what the search returned:\n\n" + "\n".join("- " + l for l in lines)
            text += "\n\n**Done.** This came from a *real* web search run on the phone."
            for i in range(0, len(text), 12):
                send(chunk({"content": text[i:i + 12]}))
        else:
            content = last["content"]
            user_text = content if isinstance(content, str) else " ".join(
                p.get("text", "") for p in content if p.get("type") == "text")
            n_images = 0 if isinstance(content, str) else sum(1 for p in content if p.get("type") == "image_url")
            if req.get("tools") and "nba" in user_text.lower():
                send(chunk({"tool_calls": [{"index": 0, "id": "call_1", "type": "function",
                                            "function": {"name": "web_search", "arguments": ""}}]}))
                args = json.dumps({"query": "NBA scores November 2 2026"})
                for i in range(0, len(args), 10):
                    send(chunk({"tool_calls": [{"index": 0, "function": {"arguments": args[i:i + 10]}}]}))
                send(chunk({}, "tool_calls"))
            else:
                text = f"You said: **{user_text.strip()[-200:]}**\n\nImages attached: {n_images}\n\n```kotlin\nprintln(\"hello\")\n```"
                for i in range(0, len(text), 8):
                    send(chunk({"content": text[i:i + 8]}))
        self.wfile.write(("data: " + json.dumps({"choices": [], "usage": {"prompt_tokens": 1200, "completion_tokens": 80}}) + "\n\n").encode())
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


ThreadingHTTPServer(("0.0.0.0", 8765), H).serve_forever()
