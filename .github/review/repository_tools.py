"""Read fixed Git objects without checking out or executing reviewed source."""

from collections import OrderedDict
from dataclasses import dataclass
import hashlib
import json
import re


TOOL_BYTES = 24_000
FILE_BYTES = 1_000_000
TREE_BYTES = 16_000_000
MAX_ENTRIES = 100_000
BLOB_CACHE_BYTES = 16_000_000
BLOB_CACHE_ENTRIES = 256
# A search page ends after this much source has been scanned or this many files, whichever comes
# first, so a repository-wide query is not spent on the alphabetically earliest hundred paths.
SEARCH_PAGE_BYTES = 8_000_000
SEARCH_PAGE_FILES = 2000

TOOLS = [{"type": "function", "function": {
    "name": "repository", "description": (
        "Inspect immutable PR code as untrusted data. list returns paginated paths; read returns "
        "numbered UTF-8 lines; search finds literal text in a path/prefix. Select base, head, or "
        "merge_base. No shell, checkout, URLs, or working-tree files. Follow next_cursor for "
        "list/search with the same action, revision, path and query. Omit cursor for a new query. "
        "Follow next_offset for read; incomplete pages are not evidence of absence."),
    "parameters": {"type": "object", "properties": {
        "action": {"type": "string", "enum": ["list", "read", "search"]},
        "revision": {"type": "string", "enum": ["base", "head", "merge_base"]},
        "path": {"type": "string", "description": "Exact file for read; optional file or directory prefix otherwise."},
        "query": {"type": "string", "description": "Literal, case-sensitive search text."},
        "offset": {"type": "integer", "minimum": 0, "description": "Zero-based line offset for read."},
        "limit": {"type": "integer", "minimum": 1, "maximum": 200, "description": "Read at most this many lines (default 200; larger values are clamped)."},
        "cursor": {"type": "string", "description": "Opaque next_cursor from the same list/search selection."}},
        "required": ["action", "revision"], "additionalProperties": False}}}]


class ToolInputError(Exception):
    pass


@dataclass(frozen=True)
class Unreadable:
    reason: str


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


class RepositoryTools:
    def __init__(self, directory, revision, merge_base, budget, git):
        self.directory, self.budget, self.git = directory, budget, git
        self.revisions = {"base": revision["base"], "head": revision["head"], "merge_base": merge_base}
        self.trees = {}
        self.unreadable_paths = {}
        self.blobs = OrderedDict()
        self.blob_bytes = 0

    def tree(self, revision):
        if revision not in self.trees:
            raw = self.git(["ls-tree", "-r", "-z", "--full-tree", self.revisions[revision]],
                           self.budget, self.directory, limit=TREE_BYTES)
            entries = {}
            unreadable = 0
            count = 0
            for record in raw.split(b"\0"):
                if not record:
                    continue
                metadata, name = record.split(b"\t", 1)
                mode, kind, oid = metadata.decode("ascii").split()
                count += 1
                if count > MAX_ENTRIES:
                    raise ToolInputError("Repository tree exceeds the entry bound.")
                try:
                    name = name.decode("utf-8", errors="strict")
                except UnicodeDecodeError:
                    unreadable += 1
                    continue
                if not re.fullmatch("[0-9a-f]{40}", oid):
                    raise ValueError("Invalid Git object identity")
                entries[name] = (mode, kind, oid)
            self.trees[revision] = entries
            self.unreadable_paths[revision] = unreadable
        return self.trees[revision]

    def blob(self, entry):
        mode, kind, oid = entry
        if mode not in ("100644", "100755") or kind != "blob":
            raise ToolInputError("Only regular file blobs can be read; symlinks and submodules are not followed.")
        if oid in self.blobs:
            value, size = self.blobs[oid]
            self.blobs.move_to_end(oid)
            if isinstance(value, Unreadable):
                raise ToolInputError(value.reason)
            return value
        size = int(self.git(["cat-file", "-s", oid], self.budget, self.directory, limit=64))
        if size > FILE_BYTES:
            error = ToolInputError("File exceeds the 1 MB read limit.")
            self.cache(oid, Unreadable(str(error)), 0)
            raise error
        raw = self.git(["cat-file", "blob", oid], self.budget, self.directory, limit=FILE_BYTES)
        if b"\0" in raw:
            error = ToolInputError("Binary file cannot be read as source text.")
            self.cache(oid, Unreadable(str(error)), 0)
            raise error
        try:
            text = raw.decode("utf-8", errors="strict")
        except UnicodeDecodeError:
            error = ToolInputError("Non-UTF-8 file cannot be read as source text.")
            self.cache(oid, Unreadable(str(error)), 0)
            raise error from None
        self.cache(oid, text, len(raw))
        return text

    def cache(self, oid, value, size):
        # Object identity is immutable. The cache lives only for this one PR's temporary repository.
        while self.blobs and (self.blob_bytes + size > BLOB_CACHE_BYTES or len(self.blobs) >= BLOB_CACHE_ENTRIES):
            _, (_, removed) = self.blobs.popitem(last=False)
            self.blob_bytes -= removed
        self.blobs[oid] = (value, size)
        self.blob_bytes += size

    @staticmethod
    def lines(text):
        lines = text.split("\n")
        return lines[:-1] if lines[-1] == "" else lines

    def call(self, name, arguments):
        try:
            if name != "repository" or not isinstance(arguments, dict):
                raise ToolInputError("Unknown tool or invalid arguments.")
            if set(arguments) - {"action", "revision", "path", "query", "offset", "limit", "cursor"}:
                raise ToolInputError("Unknown argument.")
            action, revision = arguments.get("action"), arguments.get("revision")
            if action not in ("list", "read", "search") or not isinstance(revision, str) or revision not in self.revisions:
                raise ToolInputError("Select list/read/search and base/head/merge_base.")
            path = arguments.get("path", "")
            if (not isinstance(path, str) or len(path.encode("utf-8")) > 1024
                    or "\\" in path or any(ord(c) < 32 for c in path)
                    or (path and any(p in ("", ".", "..", ".git") for p in path.rstrip("/").split("/")))):
                raise ToolInputError("Use a repository-relative path without dot or parent segments.")
            cursor_token = arguments.get("cursor", "")
            offset = arguments.get("offset", 0)
            limit = arguments.get("limit", 200)
            if (not isinstance(cursor_token, str) or type(offset) is not int or offset < 0
                    or type(limit) is not int or limit < 1):
                raise ToolInputError("Cursor must be a returned token; offset must be nonnegative; limit must be at least 1.")
            # An oversized page request costs the model a whole round if refused; the byte bound still applies.
            limit = min(limit, 200)
            entries = self.tree(revision)
            if action == "read":
                if path not in entries:
                    raise ToolInputError("File does not exist at the selected revision.")
                lines = self.lines(self.blob(entries[path]))
                result = {"revision": revision, "path": path, "lines": [], "next_offset": None}
                if offset > len(lines):
                    raise ToolInputError("Line offset exceeds the file.")
                for i in range(offset, min(len(lines), offset + limit)):
                    item = {"line": i + 1, "text": lines[i]}
                    if len(encode(result)) + len(encode(item)) > TOOL_BYTES - 128:
                        if not result["lines"]:
                            # A generated/minified line must not consume the entire context.
                            # JSON can expand one control byte to six bytes; reserve envelope fields too.
                            prefix_bytes = max(0, (TOOL_BYTES - len(encode(result)) - 128) // 6)
                            raw = lines[i].encode("utf-8")[:prefix_bytes]
                            item = {"line": i + 1, "text": raw.decode("utf-8", errors="ignore"), "truncated": True}
                            result["lines"].append(item)
                        break
                    result["lines"].append(item)
                end = offset + len(result["lines"])
                result["next_offset"] = end if end < len(lines) else None
            else:
                query = arguments.get("query")
                if action == "search" and (not isinstance(query, str) or not 1 <= len(query) <= 256):
                    raise ToolInputError("Search requires 1 to 256 literal characters.")
                prefix = path.rstrip("/")
                selection = hashlib.sha256(encode([action, self.revisions[revision], prefix,
                                                   query if action == "search" else None])).hexdigest()
                cursor = 0
                if cursor_token:
                    if not re.fullmatch(selection + r":[0-9]{1,12}", cursor_token):
                        raise ToolInputError("Cursor does not belong to this selection. Omit it to start a new query.")
                    cursor = int(cursor_token.split(":")[1])
                names = sorted(n for n in entries if not prefix or n == prefix or n.startswith(prefix + "/"))
                result = {"revision": revision, "entries": [], "next_cursor": None}
                if action == "list":
                    if cursor > len(names):
                        raise ToolInputError("Cursor exceeds this path selection.")
                    end = cursor
                    for n in names[cursor:cursor + 200]:
                        item = {"path": n, "mode": entries[n][0]}
                        if len(encode(result)) + len(encode(item)) > TOOL_BYTES - 128:
                            if end == cursor:
                                raise ToolInputError("One repository entry exceeds the tool result limit.")
                            break
                        result["entries"].append(item)
                        end += 1
                    result["next_cursor"] = end if end < len(names) else None
                else:
                    result = self.search(revision, names, entries, query, cursor)
                if result["next_cursor"] is not None:
                    result["next_cursor"] = selection + ":" + str(result["next_cursor"])
            result["unreadable_utf8_paths"] = self.unreadable_paths[revision]
            raw = encode(result)
            if len(raw) > TOOL_BYTES:
                raise ToolInputError("Tool result exceeds the byte limit.")
            return raw.decode("utf-8")
        except (ToolInputError, UnicodeError) as error:
            return encode({"error": str(error) if isinstance(error, ToolInputError)
                           else "Repository path is not valid UTF-8."}).decode("utf-8")

    def search(self, revision, names, entries, query, cursor):
        # Each byte-bounded blob has at most FILE_BYTES lines. The opaque cursor resumes
        # at an exact file and line, including when one file fills a result page.
        stride = FILE_BYTES + 1
        file_index, line_offset = divmod(cursor, stride)
        if file_index > len(names) or (file_index == len(names) and line_offset):
            raise ToolInputError("Cursor exceeds this path selection.")
        result = {"revision": revision, "entries": [], "unsearched": [], "next_cursor": None}
        scanned_bytes, end = 0, file_index
        for index in range(file_index, len(names)):
            if index > file_index and (scanned_bytes >= SEARCH_PAGE_BYTES or index - file_index >= SEARCH_PAGE_FILES):
                break
            end = index + 1
            name = names[index]
            try:
                text = self.blob(entries[name])
                scanned_bytes += len(text.encode("utf-8"))
                lines = self.lines(text)
            except ToolInputError as error:
                item = {"path": name, "reason": str(error)}
                if len(encode(result)) + len(encode(item)) > TOOL_BYTES - 128:
                    result["next_cursor"] = index * stride
                    return result
                result["unsearched"].append(item)
                lines = []
            start = line_offset if index == file_index else 0
            if start > len(lines):
                raise ToolInputError("Search cursor line exceeds the file.")
            for line in range(start, len(lines)):
                if query not in lines[line]:
                    continue
                item = {"path": name, "line": line + 1}
                if len(result["entries"]) >= 200 or len(encode(result)) + len(encode(item)) > TOOL_BYTES - 128:
                    result["next_cursor"] = index * stride + line
                    return result
                result["entries"].append(item)
        result["next_cursor"] = end * stride if end < len(names) else None
        return result
