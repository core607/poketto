# Sandcrab transport benchmark

Phase 0 of the [sandcrab proposal](../../notes/proposed/2026-10-26-sandcrab-sandbox-service.md) compares one signed supervisor frame with one HTTP/1.1 request over a Unix socket, and a chunked 50 MiB upload with a streamed one. The [recorded result](../../acceptance/evidence/2026-10-27-sandcrab-transport-benchmark.json) states its machine and limitations.

[bench.py](bench.py) starts the protocol fixture from `./gradlew buildExecutorSupervisorFixture` and the minimal server in [httpd](httpd/src/main.rs), then measures both from one Python client. It is experiment code: nothing in the application or the worker uses it.

Build the server with the pinned Rust image from [Dockerfile.tests](../../executor-service/Dockerfile.tests) (`cargo build --release` in `httpd`), then run, in a Linux container with Python 3.12 and `cryptography`:

```bash
python bench.py /path/to/poketto-supervisor-fixture /path/to/bench-httpd
```

`N` sets the number of measured requests per scenario (default 2000, after 200 warm-up requests).
