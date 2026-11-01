"""Offline evidence rejection checks; guest isolation and product behavior require native-smoke."""
import base64
import copy
import hashlib
import io
import json
import unittest
import zipfile

from native_content_checks import (ARTIFACT_BYTES, AUTHOR_MARKER, GUEST, LOCAL_MARKER, PREPARE,
                                   PRIVATE_MARKER, PUBLIC, PUBLIC_MARKER, RESTORE, artifact_bytes,
                                   png, script, verify_archive)


class Pages:
    def __init__(self, content, change=lambda page: None):
        self.content, self.change, self.offsets = content, change, []
        self.metadata = {'artifactId': '00000000-0000-0000-0000-000000000001', 'name': 'proof.bin',
                         'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest(),
                         'mediaType': 'application/octet-stream', 'truncated': False}

    def rpc(self, method, request):
        assert method == 'tools/call' and request['name'] == 'get_artifact'
        args = request['arguments']
        assert args['artifactId'] == self.metadata['artifactId'] and args['format'] == 'bytes'
        offset, limit = args['offset'], args['limit']
        self.offsets.append(offset)
        part = self.content[offset:offset + limit]
        page = dict(self.metadata, offset=offset, nextOffset=(offset + len(part) if offset + len(part) < len(self.content) else None))
        page['size'] = page.pop('bytes')
        self.change(page)
        return {'content': [{'type': 'text', 'text': json.dumps(page)}, {'type': 'resource', 'resource': {
            'uri': 'poketto-artifact:///' + self.metadata['artifactId'] + '?offset=' + str(offset),
            'mimeType': 'application/octet-stream', 'blob': base64.b64encode(part).decode()}}]}


def archive(public=True, public_text=PUBLIC_MARKER, image=None, name='note.md'):
    result = io.BytesIO()
    with zipfile.ZipFile(result, 'w') as output:
        output.writestr(name, public_text)
        output.writestr('media/proof.png', png() if image is None else image)
        if not public:
            output.writestr('private/note.md', PRIVATE_MARKER)
    return result.getvalue()


class ContentEvidenceTests(unittest.TestCase):
    def test_artifact_bytes_cross_page_boundary_and_require_complete_digest(self):
        pages = Pages(ARTIFACT_BYTES)
        self.assertEqual(artifact_bytes(pages, pages.metadata), ARTIFACT_BYTES)
        self.assertEqual(pages.offsets, [0, 65536])
        corrupted = Pages(ARTIFACT_BYTES)
        corrupted.content = b'x' + ARTIFACT_BYTES[1:]
        with self.assertRaisesRegex(AssertionError, 'digest mismatch'):
            artifact_bytes(corrupted, corrupted.metadata)

    def test_changed_identity_offset_or_cursor_cannot_look_like_a_complete_artifact(self):
        for key, value in [('artifactId', 'other'), ('sha256', '0' * 64), ('mediaType', 'image/png'),
                           ('size', 1), ('name', 'other'), ('truncated', True), ('offset', 1), ('nextOffset', None)]:
            with self.subTest(field=key):
                pages = Pages(ARTIFACT_BYTES, lambda page: page.update({key: value}))
                with self.assertRaises(AssertionError):
                    artifact_bytes(pages, pages.metadata)

    def test_artifact_size_and_truncation_are_rejected_before_network_access(self):
        for patch in [{'bytes': 0}, {'bytes': 2 * 1024 * 1024 + 1}, {'truncated': True}]:
            pages = Pages(b'proof')
            metadata = copy.copy(pages.metadata)
            metadata.update(patch)
            with self.assertRaises(AssertionError):
                artifact_bytes(pages, metadata)
            self.assertEqual(pages.offsets, [])

    def test_export_evidence_requires_saved_content_and_exact_original_bytes(self):
        verify_archive(archive(), public=True)
        verify_archive(archive(public=False), public=False)
        for invalid in [archive(public_text=LOCAL_MARKER), archive(public_text=PUBLIC_MARKER + PRIVATE_MARKER),
                        archive(public_text=PUBLIC_MARKER + AUTHOR_MARKER), archive(image=b'wrong original'),
                        archive(name='../note.md'), archive(name='.git/note.md'), archive(name='/note.md')]:
            with self.subTest(sha256=hashlib.sha256(invalid).hexdigest()):
                with self.assertRaises(AssertionError):
                    verify_archive(invalid, public=True)

    def test_every_guest_program_compiles_with_literal_unicode_and_newlines(self):
        for source in (PREPARE, PUBLIC, RESTORE):
            command = script(source, {'fixture': "line one\n引号 '\" $() `\nline two"})
            program = command.split('\n', 1)[1].rsplit('\nNATIVE_CONTENT_CHECK\n', 1)[0]
            compile(program, '<guest>', 'exec')
            self.assertIn(GUEST, program)
            self.assertLessEqual(len(command), 16384)
        with self.assertRaisesRegex(AssertionError, 'command bound'):
            script('pass', {'oversized': 'x' * 16384})


if __name__ == '__main__':
    unittest.main()
