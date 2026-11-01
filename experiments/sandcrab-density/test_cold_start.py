import os
import unittest

if os.name == 'posix':
    from cold_start import parse_event, program, timeline


@unittest.skipUnless(os.name == 'posix', 'Native trace imports Linux host APIs')
class ColdStartTests(unittest.TestCase):
    def test_timeline_excludes_version_checks_and_requires_actual_workload_exec(self):
        events = [parse_event(line) for line in [
            '105\texec\t1\t11\t/usr/bin/busctl\t0\n',
            '110\tgroup\t23\t/fixture/session.slice\n',
            '120\texec\t2\t11\t/usr/bin/systemd-run\t0\n',
            '125\texec\t3\t24\t/tools/bwrap\t1\n',
            '135\texec\t4\t24\t/tools/bwrap\t2\n',
            '145\texec\t5\t24\t/bin/bash\t0\n',
        ]]
        observed = timeline(list(reversed(events)), 100, 150, 23)
        self.assertEqual(observed['status'], 'COMPLETE')
        self.assertEqual(observed['nanosecondsFromSubmit']['bwrapSandboxExec'], 35)
        self.assertEqual(observed['nanosecondsFromSubmit']['bwrapVersionCheckExec'], 25)
        incomplete = timeline(events[:-1], 100, 150, 23)
        self.assertEqual(incomplete['status'], 'INCOMPLETE')
        self.assertEqual(incomplete['missing'], ['bashExec'])

    def test_unowned_namespace_and_ambiguous_event_are_rejected(self):
        with self.assertRaises(ValueError):
            program('arbitrary; source', 1)
        with self.assertRaises(ValueError):
            parse_event('100\texec\t1\n')


if __name__ == '__main__':
    unittest.main()
