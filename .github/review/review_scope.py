"""Select runtime changes without executing or interpreting reviewed source."""

from pathlib import PurePosixPath


def is_core(path):
    p = PurePosixPath(path)
    parts = tuple(part.lower() for part in p.parts)
    name = p.name.lower()
    if any(part in {"test", "tests", "integrationtest", "testfixtures", "__tests__", "fixtures", "__fixtures__", "evidence",
                    "docs", "notes", "screenshots", "acceptance"} for part in parts):
        return False
    if parts and parts[0] in {"executor-native", "executor-spike"}:
        return False
    if (name.startswith("test_") or name in {"run_tests.py", "conftest.py", "pytest.ini", "tox.ini",
                                            "native_probe.py", "resource_pool_probe.py"}
            or ".test." in name or ".spec." in name or name.startswith(("vitest.config.", "jest.config.", "playwright.config."))
            or ".tests" in name):
        return False
    if p.suffix.lower() in {".md", ".mdx", ".txt", ".rst", ".png", ".jpg", ".jpeg", ".gif",
                            ".webp", ".ico", ".svg", ".pdf", ".mp3", ".mp4", ".woff", ".woff2"}:
        return False
    return (bool(parts and parts[0] in {"src", "frontend", "executor-service", "deploy", "gradle"})
            or path.startswith((".github/workflows/", ".github/review/"))
            or name in {"dockerfile", "gradlew", "gradlew.bat", ".dockerignore", ".gitmodules", ".gitattributes"}
            or name.startswith(("dockerfile.", ".env"))
            or p.suffix.lower() in {".java", ".kt", ".kts", ".py", ".sh", ".ps1", ".bat", ".cmd",
                                    ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".css", ".scss",
                                    ".sql", ".yaml", ".yml", ".json", ".toml", ".properties", ".lock"})


def core_diff(directory, start, end, budget, git):
    # Disable rename detection so both sides of a move across the scope boundary are classified.
    raw = git(["diff", "--name-only", "-z", "--no-renames", start, end, "--"], budget, directory)
    paths = [p.decode("utf-8", errors="strict") for p in raw.split(b"\0") if p]
    selected = [p for p in paths if is_core(p)]
    data = bytearray()
    for offset in range(0, len(selected), 100):
        data.extend(git(["--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv", "--no-color",
                         "--no-renames", "--full-index", "--src-prefix=a/", "--dst-prefix=b/",
                         start, end, "--", *selected[offset:offset + 100]], budget, directory))
        if len(data) > 8_000_000:
            raise ValueError("Core diff exceeds the review byte bound.")
    return bytes(data), selected
