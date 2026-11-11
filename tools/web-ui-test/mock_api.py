"""Fake coprocessor API + static web page for browser tests. Usage: python3 mock_api.py <coprocessor-web dir>"""
import json, sys, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
WEB = sys.argv[1]
TOKEN = "mock-token"
XSS = '<img src=x onerror="window.__xss=1">'
state = {"persons": [{"person_id": 9, "name": "Nha", "num_images": 3, "added_at_ms": 1762750000000},
                     {"person_id": 10, "name": XSS, "num_images": 1, "added_at_ms": 1762750000000}],
         "strangers": [{"stranger_id": 3, "name": "Person_03", "appearances": 5, "num_embeddings": 4,
                        "first_seen_ms": 1762840000000, "last_seen_ms": 1762843600000},
                       {"stranger_id": 4, "name": "Person_04", "appearances": 1, "num_embeddings": 1,
                        "first_seen_ms": 1762841000000, "last_seen_ms": 1762841000000},
                       {"stranger_id": 5, "name": "Person_05", "appearances": 2, "num_embeddings": 2,
                        "first_seen_ms": 1762842000000, "last_seen_ms": 1762842000000}],
         "threshold": 0.7, "log": []}
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, obj, ctype="application/json"):
        b = obj if isinstance(obj, bytes) else json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def body(self):
        n = int(self.headers.get("Content-Length", "0")); return json.loads(self.rfile.read(n) or b"null")
    def handle_any(self, m):
        p = self.path
        if m == "GET" and p in ("/", "/app.js", "/app.css"):
            f = {"/": "index.html"}.get(p, p[1:]); t = {"html": "text/html", "js": "text/javascript", "css": "text/css"}[f.split(".")[-1]]
            return self.send(200, open(os.path.join(WEB, f), "rb").read(), t)
        if p == "/__log": return self.send(200, state["log"])
        if self.headers.get("Authorization") != "Bearer " + TOKEN: return self.send(401, {"error": "unauthorized", "message": ""})
        b = self.body() if m in ("POST", "PUT") else None
        state["log"].append([m, p, b])
        if p == "/api/persons": return self.send(200, {"persons": state["persons"], "count": len(state["persons"])})
        if p == "/api/strangers" and m == "GET": return self.send(200, {"strangers": state["strangers"], "count": len(state["strangers"]), "max_clusters": 200})
        if p == "/api/strangers" and m == "DELETE":
            n = len(state["strangers"]); state["strangers"] = []; return self.send(200, {"deleted": n})
        if p == "/api/settings/strangers":
            if m == "PUT": state["threshold"] = b["threshold"]
            return self.send(200, {"threshold": state["threshold"], "max_clusters": 200, "max_embeddings_per_cluster": 10, "min_face_px": 40})
        if p.startswith("/api/strangers/"):
            sid = int(p.split("/")[3]); s = next((x for x in state["strangers"] if x["stranger_id"] == sid), None)
            if not s: return self.send(404, {"error": "not_found", "message": "no"})
            state["strangers"].remove(s)
            if m == "DELETE": return self.send(200, {"deleted": sid})
            if "person_id" in b:
                person = next(x for x in state["persons"] if x["person_id"] == b["person_id"]); created = False
            else:
                person = next((x for x in state["persons"] if x["name"] == b["name"]), None); created = person is None
                if created:
                    person = {"person_id": 20, "name": b["name"], "num_images": 0, "added_at_ms": 1762850000000}; state["persons"].append(person)
            person["num_images"] += s["num_embeddings"]
            return self.send(200, {"stranger_id": sid, "stranger_name": s["name"], "person_id": person["person_id"], "name": person["name"], "created": created, "embeddings_moved": s["num_embeddings"]})
        return self.send(200, {})
    def do_GET(self): self.handle_any("GET")
    def do_POST(self): self.handle_any("POST")
    def do_PUT(self): self.handle_any("PUT")
    def do_DELETE(self): self.handle_any("DELETE")
ThreadingHTTPServer(("127.0.0.1", 8765), H).serve_forever()
