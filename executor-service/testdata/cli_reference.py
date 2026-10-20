#!/usr/bin/python3
"""Synchronous command-side bridge client. Never retries an uncertain mutation."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import select
import stat
import struct
import sys
import time
import uuid

MAX_FRAME = 512 * 1024


def read_text_input(text, filename=None, stdin=False):
    """Preserve UTF-8 and final newlines; bound input before sending a host mutation."""
    if text is not None:
        return text
    if stdin:
        raw = sys.stdin.buffer.read(MAX_FRAME + 1)
    else:
        with open(filename, 'rb') as stream:
            raw = stream.read(MAX_FRAME + 1)
    if len(raw) > MAX_FRAME:
        raise ValueError('text input exceeds 512 KiB; use smaller exact edits')
    return raw.decode('utf-8', errors='strict')


class BridgeUnavailable(Exception):
    pass


def _remaining(deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise BridgeUnavailable('Bridge deadline exceeded; write outcome may be unknown')
    return remaining


def _send(root, value, deadline):
    raw = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')
    if len(raw) > MAX_FRAME:
        raise BridgeUnavailable('Bridge request exceeds 512 KiB')
    frame = memoryview(struct.pack('!I', len(raw)) + raw)
    lock = os.open(root / 'lock', os.O_RDONLY | os.O_NOFOLLOW)
    try:
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                time.sleep(min(0.02, _remaining(deadline)))
        output = os.open(root / 'requests', os.O_WRONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
        try:
            while frame:
                _, writable, _ = select.select([], [output], [], min(0.05, _remaining(deadline)))
                if writable:
                    try:
                        frame = frame[os.write(output, frame):]
                    except BlockingIOError:
                        continue
        finally:
            os.close(output)
    finally:
        os.close(lock)


def call(root, operation, arguments, timeout=55):
    root = Path(root)
    request_id = str(uuid.uuid4())
    execution_id = os.environ.get('POKETTO_EXECUTION_ID', '')
    deadline = time.monotonic() + timeout
    if not execution_id or (root / 'state').read_bytes() != execution_id.encode():
        raise BridgeUnavailable('Execution bridge is not active for this command')
    _send(root, {'requestId': request_id, 'executionId': execution_id, 'operation': operation, 'arguments': arguments}, deadline)
    result = root / 'responses' / (request_id + '.json')
    while True:
        if (root / 'state').read_bytes() != execution_id.encode():
            raise BridgeUnavailable('Execution bridge closed; write outcome may be unknown')
        try:
            fd = os.open(result, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        except FileNotFoundError:
            time.sleep(min(0.02, _remaining(deadline)))
            continue
        with os.fdopen(fd, 'rb') as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FRAME or info.st_mode & 0o022:
                raise BridgeUnavailable('Invalid bridge response')
            raw = stream.read(MAX_FRAME + 1)
        if len(raw) > MAX_FRAME:
            raise BridgeUnavailable('Bridge response exceeds its bound')
        response = json.loads(raw)
        if not isinstance(response, dict) or type(response.get('ok')) is not bool:
            raise BridgeUnavailable('Invalid bridge result')
        # A reply is already known; failed cleanup acknowledgement must not change its outcome.
        try:
            _send(root, {'requestId': request_id, 'executionId': execution_id, 'operation': 'ack'}, deadline)
        except (OSError, BridgeUnavailable):
            pass
        return response


def main():
    parser = argparse.ArgumentParser(
        prog='poketto',
        description='Host operations use repository-relative paths, independent of the shell working directory. '
                    'Shell state and /tmp persist within the live lease sandbox; repository files also survive sandbox resets. '
                    'Use python3 for Python scripts.',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""Common workflow:
  Text: poketto create private/article.md --stdin <<'MARKDOWN'
        ... Markdown, quotes and final newlines are preserved ...
        MARKDOWN
  Edit: poketto edit PATH --old 'exact original' --new-stdin <<'REPLACEMENT'
        ... replacement text ...
        REPLACEMENT
  Media here: poketto media import FILE --as private/PATH --type image/png --key KEY
  Media elsewhere: use MCP put_asset with a download URL, or request its temporary
        upload URL and PUT the file from the client that owns it. Do not transcribe Base64.
  Link receipt: poketto media link private/PATH --asset ID --revision REV
  Persist: poketto save private/article.md .poketto/assets.json
  Confirm: poketto status; inspect saved text/media through the remote read path.
  Remote changed: poketto sync preserves local edits; resolve reported conflicts.
Uploads and local edits do not publish. Public paths require publication permission.
stdin avoids shell quoting; it does not lift repo_exec's 16384-character command
limit or the 512 KiB bridge frame limit. Use --text-file/--old-file/--new-file for
UTF-8 files already in the execution environment, or split a long edit into exact matches.
""")
    commands = parser.add_subparsers(dest='operation', required=True)
    commands.add_parser('status', help='Read the working-copy ID, host-owned baseline and scope')
    edit = commands.add_parser('edit', help='Replace one exact text match in a local file; does not save or publish')
    edit.add_argument('path')
    old = edit.add_mutually_exclusive_group(required=True)
    old.add_argument('--old', help='Exact nonempty original text; ambiguous or stale matches fail')
    old.add_argument('--old-file', help='Read exact original text from a UTF-8 file in the current shell directory')
    replacement = edit.add_mutually_exclusive_group(required=True)
    replacement.add_argument('--new', help='Replacement text; an empty value deletes the matched text')
    replacement.add_argument('--new-file', help='Read replacement text from a UTF-8 file in the current shell directory')
    replacement.add_argument('--new-stdin', action='store_true', help='Read replacement text from UTF-8 stdin, preserving final newlines')
    create = commands.add_parser('create', help='Create a local text file only when its path is absent; does not save or publish')
    create.add_argument('path')
    initial = create.add_mutually_exclusive_group(required=True)
    initial.add_argument('--text', help='Initial text as a command argument')
    initial.add_argument('--text-file', help='Read initial text from a UTF-8 file in the current shell directory')
    initial.add_argument('--stdin', action='store_true', help='Read initial text from UTF-8 stdin; ideal for a quoted heredoc')
    recover = commands.add_parser('recover', help='Resume a pending sync or recover a save or move using its retained commit and local completion receipt')
    recover.add_argument('--skip-local', action='store_true', help='Keep local files untouched and release a pending sync or confirmed move; completed local updates remain')
    commands.add_parser('sync', help='Merge the current remote workspace into local edits without saving or publishing')
    move = commands.add_parser('move', help='Atomically move saved content and repair references; unselected edits stay local')
    move.add_argument('source')
    move.add_argument('destination')
    exported = commands.add_parser('export', help='Export latest saved documents and media as ZIP; unsaved edits are excluded and publication is unchanged')
    exported.add_argument('paths', nargs='+', help='Selected files or folders; . selects the visible workspace')
    exported.add_argument('--output', required=True, help='Repository-relative ZIP destination; a different existing file is preserved')
    exported.add_argument('--public', action='store_true', dest='public_only', help='Use approved public content only; public-read sessions always use this scope')
    artifacts = commands.add_parser('artifact', help='Create or remove an expiring artifact for the current execution lease')
    artifact_commands = artifacts.add_subparsers(dest='artifact_operation', required=True)
    created = artifact_commands.add_parser('create', help='Capture a local file for get_artifact; does not save or publish')
    created.add_argument('path')
    created.add_argument('--type', dest='media_type', default='application/octet-stream')
    removed = artifact_commands.add_parser('remove', help='Release a session artifact before it expires')
    removed.add_argument('artifact_id')
    media = commands.add_parser('media', help='Import or fetch indexed original media')
    media_commands = media.add_subparsers(dest='media_operation', required=True)
    listing = media_commands.add_parser('list', help='List indexed media paths without fetching originals')
    listing.add_argument('--prefix', default='', help='Literal logical-path prefix, such as private/music/')
    listing.add_argument('--offset', type=int, default=0)
    listing.add_argument('--limit', type=int, default=100)
    listing.add_argument('--index-version', help='Use the returned version when continuing a page; changed indexes fail explicitly')
    listing.add_argument('--commit', help='Read a historical index in a full-read session')
    fetch = media_commands.add_parser('fetch', help='Fetch an indexed original into the repository worktree')
    fetch.add_argument('path')
    fetch.add_argument('--commit')
    fetch.add_argument('--output')
    imported = media_commands.add_parser('import', help='Store a local original and update its unsaved logical index entry')
    imported.add_argument('file', help='Existing regular file, relative to the repository root; absolute and /tmp paths are not accepted')
    imported.add_argument('--as', dest='logical_path', required=True, help='Repository-relative logical path stored in the media index')
    imported.add_argument('--type', dest='media_type', default='application/octet-stream')
    imported.add_argument('--key', required=True, help='Stable 16-128 character idempotency key for these original bytes')
    imported.add_argument('--replace', action='store_true', help='Replace an existing logical index entry; originals remain immutable')
    linked = media_commands.add_parser('link', help='Link an existing workspace original into the unsaved media index; does not upload or save')
    linked.add_argument('path', help='Repository-relative logical path stored in the media index')
    linked.add_argument('--asset', required=True, help='Existing managed asset ID returned by upload')
    linked.add_argument('--revision', required=True, help='Exact immutable revision returned by upload')
    linked.add_argument('--replace', action='store_true', help='Replace an existing logical index entry; originals remain immutable')
    save = commands.add_parser('save', help='Commit explicitly selected text files through the host')
    save.add_argument('paths', nargs='*')
    save.add_argument('--delete', action='append', default=[])
    args = parser.parse_args()
    arguments = {'writes': args.paths, 'deletes': args.delete} if args.operation == 'save' else {}
    try:
        if args.operation == 'edit':
            arguments = {'path': args.path, 'oldText': read_text_input(args.old, args.old_file),
                         'newText': read_text_input(args.new, args.new_file, args.new_stdin)}
        if args.operation == 'create':
            arguments = {'path': args.path, 'text': read_text_input(args.text, args.text_file, args.stdin)}
    except (OSError, ValueError) as error:
        parser.error(f'cannot read text input: {error}')
    if args.operation == 'recover' and args.skip_local:
        arguments = {'skipLocal': True}
    if args.operation == 'move':
        arguments = {'source': args.source, 'destination': args.destination}
    if args.operation == 'export':
        arguments = {'paths': args.paths, 'output': args.output, 'publicOnly': args.public_only}
    operation = args.operation
    if operation == 'artifact':
        operation = 'artifact_' + args.artifact_operation
        arguments = ({'path': args.path, 'mediaType': args.media_type} if args.artifact_operation == 'create'
                     else {'artifactId': args.artifact_id})
    if operation == 'media':
        if args.media_operation == 'list':
            operation = 'media_list'
            arguments = {'prefix': args.prefix, 'offset': args.offset, 'limit': args.limit, 'indexVersion': args.index_version, 'commit': args.commit}
        elif args.media_operation == 'fetch':
            operation = 'media_fetch'
            arguments = {'path': args.path, 'commit': args.commit, 'output': args.output}
        elif args.media_operation == 'link':
            operation = 'media_link'
            arguments = {'path': args.path, 'assetId': args.asset, 'revision': args.revision, 'replace': args.replace}
        else:
            operation = 'media_import'
            arguments = {'file': args.file, 'path': args.logical_path, 'mediaType': args.media_type, 'key': args.key, 'replace': args.replace}
    if args.operation == 'save' and not args.paths and not args.delete:
        parser.error('save requires selected files or explicit --delete paths')
    envelope = {'requestId': str(uuid.uuid4()), 'operation': operation, 'arguments': arguments}
    if len(json.dumps(envelope, ensure_ascii=False, separators=(',', ':')).encode('utf-8')) > MAX_FRAME:
        parser.error('encoded request exceeds 512 KiB; split the text into smaller exact edits')
    root = os.environ.get('POKETTO_BRIDGE')
    if not root:
        parser.error('this command requires an admitted Poketto execution session')
    try:
        result = call(root, operation, arguments)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result['ok'] else 1
    except (BridgeUnavailable, OSError, ValueError) as error:
        code = f'{type(error).__name__}:{getattr(error, "errno", None)}'
        print(f'Poketto bridge unavailable or outcome unknown ({code}); inspect host status before retrying a write.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
