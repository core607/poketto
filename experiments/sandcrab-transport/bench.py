"""Sandcrab phase 0: signed supervisor frames versus HTTP/1.1 over a Unix socket.

Runs inside one Linux container. Both servers are Rust; the client is Python for both, so the
client-side costs are comparable. Prints one JSON document.
"""
import base64, http.client, json, os, platform, socket, statistics, struct, subprocess, sys, tempfile, time, uuid
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

N, WARMUP = int(os.environ.get("N", "2000")), 200
FIXTURE, HTTPD = sys.argv[1], sys.argv[2]


def b64(v): return base64.urlsafe_b64encode(v).decode().rstrip("=")
def uid(): return str(uuid.uuid4())


def recv_exact(s, n):
    out = b""
    while len(out) < n:
        chunk = s.recv(n - len(out))
        if not chunk: raise EOFError
        out += chunk
    return out


def stats(samples):
    samples = sorted(samples)
    return {"median_us": round(statistics.median(samples) * 1e6, 1),
            "p95_us": round(samples[int(len(samples) * 0.95)] * 1e6, 1),
            "mean_us": round(statistics.fmean(samples) * 1e6, 1), "n": len(samples)}


class UnixHTTP(http.client.HTTPConnection):
    def __init__(self, path):
        super().__init__("localhost")
        self.path = path
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.connect(self.path)


def main():
    tmp = tempfile.mkdtemp()
    key = Ed25519PrivateKey.generate()
    identity = {n: uid() for n in ("appBootId", "principalId", "accountId", "workspaceId")}
    identity["serverSessionHash"] = "b" * 64
    lease = uid()
    settings = {"fixtureOnly": True, "publicKey": b64(key.public_key().public_bytes_raw()),
                "workerBootId": uid(), "maxRequests": 100000, "maxConnections": 256,
                "sessions": [{**identity, "leaseId": lease, "deadline": time.time() + 3600}]}
    frame_path, http_path, token = tmp + "/frame.sock", tmp + "/http.sock", "t" * 43
    fixture = subprocess.Popen([FIXTURE, frame_path, "threads"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    fixture.stdin.write(json.dumps(settings) + "\n"); fixture.stdin.flush()
    assert json.loads(fixture.stdout.readline())["ready"]
    httpd = subprocess.Popen([HTTPD, http_path, token], stdout=subprocess.PIPE, text=True)
    assert httpd.stdout.readline().strip() == "ready"

    def payload():
        issued = int(time.time())
        return {**identity, "leaseId": lease, "version": 1, "workerBootId": settings["workerBootId"],
                "requestId": uid(), "operation": "RENEW", "issuedAt": issued, "expiresAt": issued + 15, "data": {}}

    def signed_frame():
        raw = json.dumps(payload(), separators=(",", ":")).encode()
        body = json.dumps({"payload": b64(raw), "signature": b64(key.sign(raw))}, separators=(",", ":")).encode()
        with socket.socket(socket.AF_UNIX) as s:
            s.connect(frame_path)
            s.sendall(struct.pack("!I", len(body)) + body)
            n, = struct.unpack("!I", recv_exact(s, 4))
            reply = json.loads(recv_exact(s, n))
        assert reply.get("ok"), reply
        return len(body)

    keep = UnixHTTP(http_path)
    def http_request(conn, close=False):
        body = json.dumps(payload(), separators=(",", ":")).encode()
        headers = {"Content-Type": "application/json", "X-Token": token}
        if close: headers["Connection"] = "close"
        conn.request("POST", "/renew", body, headers)
        reply = json.loads(conn.getresponse().read())
        assert reply["ok"], reply
        return len(body)

    def http_new():
        conn = UnixHTTP(http_path)
        try: return http_request(conn, close=True)
        finally: conn.close()

    def measure(fn):
        for _ in range(WARMUP): fn()
        out = []
        for _ in range(N):
            t = time.perf_counter(); fn(); out.append(time.perf_counter() - t)
        return out

    sizes = {"signed_frame_bytes": signed_frame(), "http_body_bytes": http_request(keep)}
    results = {"signed_frame_new_connection": stats(measure(signed_frame)),
               "http_keepalive": stats(measure(lambda: http_request(keep))),
               "http_new_connection": stats(measure(http_new))}
    sign_samples = []
    raw = json.dumps(payload(), separators=(",", ":")).encode()
    for _ in range(N):
        t = time.perf_counter(); key.sign(raw); sign_samples.append(time.perf_counter() - t)
    results["client_ed25519_sign_only"] = stats(sign_samples)

    # 50 MB: 800 requests of 64 KiB (today's chunk size, without base64) versus one streamed body.
    chunk, total = os.urandom(65536), 800
    def upload(conn, data):
        conn.request("POST", "/upload", data, {"X-Token": token, "Content-Type": "application/octet-stream"})
        reply = json.loads(conn.getresponse().read()); assert reply["ok"] and reply["bytes"] == len(data)
    runs = {"chunked_800x64KiB_keepalive_s": [], "single_stream_50MiB_s": []}
    blob = chunk * total
    for _ in range(5):
        t = time.perf_counter()
        for _ in range(total): upload(keep, chunk)
        runs["chunked_800x64KiB_keepalive_s"].append(time.perf_counter() - t)
        t = time.perf_counter(); upload(keep, blob); runs["single_stream_50MiB_s"].append(time.perf_counter() - t)
    results["upload_50MiB"] = {k: round(statistics.median(v), 4) for k, v in runs.items()}
    results["upload_50MiB"]["signed_frames_estimate_s"] = round(
        total * results["signed_frame_new_connection"]["median_us"] / 1e6, 4)

    fixture.stdin.write("stop\n"); fixture.stdin.flush(); fixture.wait(timeout=10)
    httpd.kill()
    print(json.dumps({"date": time.strftime("%Y-%m-%d"), "cpus": os.cpu_count(), "kernel": platform.release(),
                      "python": platform.python_version(), "warmup": WARMUP, **sizes, "results": results}, indent=2))


main()
