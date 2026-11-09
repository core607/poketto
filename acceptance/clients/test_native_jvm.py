"""Reject absent NMT evidence and retain reserved versus committed categories."""
import unittest
from pathlib import Path
import tempfile
import zipfile

from native_jvm import check_ancestor_memory, nmt_summary, verify_classpath_jar


class NativeMemoryEvidence(unittest.TestCase):
    def test_real_root_has_no_limit_but_namespace_and_descendant_limits_still_bind(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            child = root / 'controller'
            child.mkdir()
            (child / 'memory.max').write_text('100')
            (child / 'memory.current').write_text('25')
            check_ancestor_memory(child, 70, root)
            with self.assertRaises(AssertionError):
                check_ancestor_memory(child, 80, root)
            (root / 'memory.max').write_text('100')
            (root / 'memory.current').write_text('50')
            with self.assertRaises(AssertionError):
                check_ancestor_memory(child, 70, root)
            (child / 'memory.max').unlink()
            with self.assertRaises(FileNotFoundError):
                check_ancestor_memory(child, 1, root)

    def test_disabled_and_truncated_output_are_not_zero_memory(self):
        for text in ('123:\nNative memory tracking is not enabled', 'Total: reserved=100KB',
                     'Total: reserved=100KB, committed=20KB\n- Shared class space (reserved=30KB, committed=10KB'):
            with self.assertRaises(ValueError):
                nmt_summary(text)

    def test_categories_preserve_reservation_and_actual_commit_distinction(self):
        observed = nmt_summary('''123:
Total: reserved=1048576KB, committed=262144KB
-                 Java Heap (reserved=786432KB, committed=131072KB)
                            (mmap: reserved=786432KB, committed=131072KB)
-                    Thread (reserved=32768KB, committed=2048KB)
                            (thread #32)
-        Shared class space (reserved=16384KB, committed=8192KB, readonly=4096KB)
''')
        self.assertEqual(observed['reservedBytes'], 1073741824)
        self.assertEqual(observed['committedBytes'], 268435456)
        self.assertEqual(observed['categories']['Java Heap']['committedBytes'], 134217728)
        self.assertEqual(observed['categories']['Thread']['committedBytes'], 2097152)
        self.assertEqual(observed['categories']['Shared class space']['readonlyBytes'], 4194304)
        self.assertEqual(set(observed['categories']), {'Java Heap', 'Thread', 'Shared class space'})

    def test_changed_or_extra_class_bytes_cannot_enter_the_cache_comparison(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory).resolve()
            (runtime / 'classes').mkdir()
            (runtime / 'classes/Main.class').write_bytes(b'verified-class')
            for contents in (b'changed-class', b'verified-class'):
                jar = runtime / ('changed.jar' if contents.startswith(b'changed') else 'extra.jar')
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr('Main.class', contents)
                    if contents.startswith(b'verified'):
                        archive.writestr('Injected.class', b'extra')
                with self.assertRaises(AssertionError):
                    verify_classpath_jar(runtime, jar)

    def test_package_roots_remain_discoverable_to_spring_scanners(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory).resolve()
            (runtime / 'classes/example').mkdir(parents=True)
            (runtime / 'classes/example/Main.class').write_bytes(b'class')
            jar = runtime / 'classes.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('example/Main.class', b'class')
            with self.assertRaises(AssertionError):
                verify_classpath_jar(runtime, jar)
            with zipfile.ZipFile(jar, 'a') as archive:
                archive.writestr('example/', b'')
            verify_classpath_jar(runtime, jar)


if __name__ == '__main__':
    unittest.main()
