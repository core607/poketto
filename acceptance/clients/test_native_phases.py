"""Measurement evidence must fail closed when timings or resource deltas are incomplete."""

import copy
import unittest

from native_phase_metrics import EVENT, correlate, duration, resource_delta, timestamp
from native_phases import aggregates, validate_phases


def event(phase='REQUEST', depth=0, start='2026-11-09T00:00:00.100000001Z', elapsed='PT0.1S', invocation=1):
    return {'type': EVENT, 'values': {
        'startTime': start, 'duration': elapsed, 'eventThread': {}, 'stackTrace': None,
        'invocation': invocation, 'phase': phase, 'parentPhase': None if depth == 0 else 'REQUEST',
        'depth': depth, 'threadCpuNanos': -1, 'transferredBytes': -1,
    }}


def request():
    return {'startWallNanos': timestamp('2026-11-09T00:00:00Z'),
            'endWallNanos': timestamp('2026-11-09T00:00:00.3Z')}


class PhaseEvidenceTests(unittest.TestCase):
    def test_nanosecond_timestamp_and_long_duration_do_not_truncate(self):
        self.assertEqual(1, timestamp('2026-11-09T08:00:00.100000001+08:00')
                         - timestamp('2026-11-09T00:00:00.100000000Z'))
        self.assertEqual(3_661_000_000_003, duration('PT1H1M1.000000003S'))

    def test_nested_phases_join_one_http_result_and_background_containment_stays_separate(self):
        nested = event('COMMAND_WAIT', 1, '2026-11-09T00:00:00.110000001Z', 'PT0.05S')
        background = event('SESSION_CONTAINMENT', invocation=2)
        cases = correlate({'recording': {'events': [nested, event(), background]}}, [request()])
        self.assertEqual(['REQUEST', 'COMMAND_WAIT'], [item['phase'] for item in cases[0]['phases']])
        self.assertEqual(-1, cases[0]['phases'][1]['threadCpuNanos'])

    def test_data_loss_unapproved_events_fields_and_stack_traces_are_rejected(self):
        bad = [
            {'type': 'jdk.DataLoss', 'values': {'amount': 1}},
            {'type': 'jdk.InitialEnvironmentVariable', 'values': {}},
            {**event(), 'values': {**event()['values'], 'command': 'private material'}},
            {**event(), 'values': {**event()['values'], 'stackTrace': {'frames': []}}},
        ]
        for extra in bad:
            with self.subTest(extra=extra['type']), self.assertRaises(AssertionError):
                correlate({'recording': {'events': [event(), extra]}}, [request()])

    def test_missing_duplicate_and_uncontained_phases_are_incomplete(self):
        for events in ([], [event(), event()], [event(), event('COMMAND_WAIT', 1, elapsed='PT1S')]):
            with self.subTest(events=len(events)), self.assertRaises(AssertionError):
                correlate({'recording': {'events': events}}, [request()])
        outside = {**request(), 'startWallNanos': timestamp('2026-11-09T00:00:01Z')}
        with self.assertRaises(AssertionError):
            correlate({'recording': {'events': [event()]}}, [outside])

    def test_request_envelopes_alone_do_not_prove_phase_coverage_or_nonexecution(self):
        for case in (
                {'case': 'first-parent', 'phases': [event()['values']]},
                {'case': 'missing-copy', 'phases': [event('COMMAND_WAIT')['values']]}):
            with self.subTest(case=case['case']), self.assertRaises(AssertionError):
                validate_phases([case])

    def test_cgroup_deltas_preserve_refusals_and_distinguish_memory_gauges_from_counters(self):
        group = {'cpu': {'usage_usec': 10, 'throttled_usec': 2},
                 'memory': {'anon': 20, 'file': 100, 'kernel': 8},
                 'memoryEvents': {'oom': 0, 'oom_kill': 0},
                 'pressure': {'cpu.some.totalMicros': 4, 'io.some.totalMicros': 2},
                 'io': {'7:0': {'rbytes': 100, 'wbytes': 200, 'rios': 1, 'wios': 1}},
                 'memoryCurrent': 128, 'limits': {'memory.max': '1000'}}
        before = {'groups': {'application': group}}
        after = copy.deepcopy(before)
        end = after['groups']['application']
        end['cpu']['usage_usec'] = 13
        end['memory']['file'] = 50
        end['io']['7:0']['wbytes'] = 208
        delta = resource_delta(before, after)
        self.assertEqual(3, delta['application']['cpu']['usage_usec'])
        self.assertEqual(8, delta['application']['io']['wbytes'])
        self.assertEqual(50, delta['application']['memoryAfter']['file'])
        samples = [{'case': 'missing-copy', 'outcome': 'MISSING_COPY', 'wallNanos': 12, 'resources': delta}]
        summary = aggregates(samples)['missing-copy']
        self.assertEqual({'MISSING_COPY': 1}, summary['outcomes'])
        self.assertEqual({}, summary['phases'])
        end['cpu']['usage_usec'] = 0
        with self.assertRaises(AssertionError):
            resource_delta(before, after)
        end['cpu']['usage_usec'] = 13
        end['limits']['memory.max'] = '2000'
        with self.assertRaises(AssertionError):
            resource_delta(before, after)
        end['limits']['memory.max'] = '1000'
        group['io'] = end['io'] = None
        missing = resource_delta(before, after)
        self.assertIsNone(missing['application']['io']['wbytes'])
        samples[0]['resources'] = missing
        self.assertIsNone(aggregates(samples)['missing-copy']['resources']['application']['writtenBytes'])


if __name__ == '__main__':
    unittest.main()
