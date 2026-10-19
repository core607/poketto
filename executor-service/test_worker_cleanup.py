from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import threading
import unittest
from unittest.mock import Mock, patch

import test_worker as fixture


class WorkerCleanupTests(unittest.TestCase):
    def setUp(self):
        self.harness = fixture.ProtocolTests()
        self.harness.setUp()
        self.service = self.harness.service
        self.backend = self.harness.backend
        self.addCleanup(self.service.shutdown)

    def opened(self, scope, principal=None):
        self.harness.identity = {**self.harness.identity, 'leaseId': fixture.uid(),
                                 'serverSessionHash': scope * 64,
                                 'principalId': principal or fixture.uid()}
        request = self.harness.opened()
        return request, self.service.sessions[request['leaseId']]

    def request(self, opened, operation, data=None):
        return {**opened, 'requestId': fixture.uid(), 'operation': operation, 'data': data or {}}

    @contextmanager
    def holding_close(self, session):
        entered, release = threading.Event(), threading.Event()
        original = self.backend.close

        def close(value):
            if value is session:
                entered.set()
                self.assertTrue(release.wait(8), 'The held cleanup was not released')
            original(value)

        with patch.object(self.backend, 'close', side_effect=close) as closing:
            with ThreadPoolExecutor(max_workers=3) as pool:
                try:
                    yield pool, entered, closing
                finally:
                    release.set()

    def test_slow_close_keeps_other_controls_live_and_retains_its_capacity(self):
        first, session = self.opened('a')
        second, other = self.opened('b')
        other.bridge = Mock()
        third = {**second, 'leaseId': fixture.uid(), 'requestId': fixture.uid(), 'serverSessionHash': 'c' * 64}
        with self.holding_close(session) as (pool, entered, closing):
            stopped = pool.submit(self.harness.send, self.request(first, 'CLOSE'))
            self.assertTrue(entered.wait(2))
            renewed = pool.submit(self.harness.send, self.request(second, 'RENEW')).result(2)
            self.assertTrue(renewed['ok'])
            with patch('worker.BRIDGE_POLL_SECONDS', 0.05):
                polled = pool.submit(self.harness.send, self.request(second, 'BRIDGE_POLL', {'executionId': fixture.uid()})).result(2)
            self.assertTrue(polled['ok'])
            self.assertIsNone(polled['bridgeRequest'])
            self.assertEqual('SESSION_CAPACITY', pool.submit(self.harness.send, third).result(2)['code'])
            repeated = pool.submit(self.harness.send, self.request(first, 'CLOSE')).result(2)
            self.assertEqual('CLOSING', repeated['state'])
            self.assertFalse(stopped.done())
            self.assertEqual(1, closing.call_count)
        self.assertEqual('CLOSED', stopped.result()['state'])
        self.assertTrue(self.harness.send({**third, 'requestId': fixture.uid()})['ok'])

    def test_revocation_marks_every_target_before_waiting_for_cleanup(self):
        self.service.config['maxSessions'] = 3
        first, session = self.opened('a')
        second, other = self.opened('b')
        third, another = self.opened('c', first['principalId'])
        request = self.request(second, 'REVOKE', {'keyIds': [first['principalId']], 'accountIds': []})
        with self.holding_close(session) as (pool, entered, _):
            revoked = pool.submit(self.harness.send, request)
            self.assertTrue(entered.wait(2))
            self.assertTrue(another.cancelled.is_set())
            self.assertFalse(other.cancelled.is_set())
            self.assertTrue(pool.submit(self.harness.send, self.request(second, 'RENEW')).result(2)['ok'])
        self.assertEqual('CLOSED', revoked.result()['state'])
        self.assertEqual(2, revoked.result()['closedCount'])
        self.assertEqual('AUTH_REVOKED', self.harness.send(self.request(third, 'RENEW'))['code'])

    def expiry_during_cleanup(self, sweep):
        first, session = self.opened('a')
        second, _ = self.opened('b')
        session.deadline = self.harness.now - 1
        with self.holding_close(session) as (pool, entered, _):
            expired = pool.submit(self.service.sweep) if sweep else pool.submit(
                self.harness.send, self.request(first, 'RENEW'))
            self.assertTrue(entered.wait(2))
            self.assertTrue(pool.submit(self.harness.send, self.request(second, 'RENEW')).result(2)['ok'])
        self.assertEqual('CLOSED', session.state)
        if not sweep:
            self.assertEqual('LEASE_EXPIRED', expired.result()['code'])

    def test_expiry_on_request_does_not_block_another_renewal(self):
        self.expiry_during_cleanup(False)

    def test_expiry_sweep_does_not_block_another_renewal(self):
        self.expiry_during_cleanup(True)

    def test_cancelled_command_finishes_cleanup_without_blocking_other_renewals(self):
        first, session = self.opened('a')
        execution = self.harness.execution()
        second, _ = self.opened('b')
        command = threading.Event()
        self.backend.wait = command
        self.addCleanup(command.set)
        with self.holding_close(session) as (pool, entered, closing):
            running = pool.submit(self.harness.send, execution)
            try:
                self.assertTrue(self.backend.entered.wait(2))
                self.assertEqual('CLOSING', self.harness.send(self.request(first, 'CLOSE'))['state'])
            finally:
                command.set()
            self.assertTrue(entered.wait(2))
            self.assertTrue(pool.submit(self.harness.send, self.request(second, 'RENEW')).result(2)['ok'])
            self.assertEqual(1, closing.call_count)
        self.assertEqual('CLOSED', running.result()['state'])
        self.assertEqual([session.id], self.backend.closed)

    def test_shutdown_fences_admission_and_marks_all_leases_before_cleanup(self):
        self.service.config['maxSessions'] = 3
        first, session = self.opened('a')
        second, other = self.opened('b')
        late = {**second, 'leaseId': fixture.uid(), 'requestId': fixture.uid(), 'serverSessionHash': 'c' * 64}
        with self.holding_close(session) as (pool, entered, _):
            stopped = pool.submit(self.service.shutdown)
            self.assertTrue(entered.wait(2))
            self.assertTrue(other.cancelled.is_set())
            self.assertEqual('SESSION_CLOSED', pool.submit(self.harness.send, late).result(2)['code'])
        stopped.result()
        self.assertEqual(2, len(self.backend.opened))
        self.assertEqual('CLOSED', session.state)
        self.assertEqual('CLOSED', other.state)

    def test_unconfirmed_cleanup_keeps_admission_until_a_fresh_close_confirms_it(self):
        self.service.config['maxSessions'] = 1
        first, session = self.opened('a')
        next_open = {**first, 'leaseId': fixture.uid(), 'requestId': fixture.uid(), 'serverSessionHash': 'b' * 64}
        with patch.object(self.backend, 'close', side_effect=OSError('Containment not confirmed')):
            self.assertEqual('EXECUTOR_FAILED', self.harness.send(self.request(first, 'CLOSE'))['code'])
        self.assertEqual('CLOSING', session.state)
        self.assertEqual('SESSION_CAPACITY', self.harness.send(next_open)['code'])
        self.assertEqual('CLOSED', self.harness.send(self.request(first, 'CLOSE'))['state'])
        self.assertTrue(self.harness.send({**next_open, 'requestId': fixture.uid()})['ok'])

    def test_failed_operation_does_not_repeat_failed_cleanup_in_its_finalizer(self):
        _, session = self.opened('a')
        with patch.object(self.backend, 'execute', side_effect=OSError('Command channel failed')):
            with patch.object(self.backend, 'close', side_effect=OSError('Containment not confirmed')) as closing:
                self.assertEqual('EXECUTOR_FAILED', self.harness.send(self.harness.execution())['code'])
                closing.assert_called_once()
        self.assertEqual('CLOSING', session.state)


if __name__ == '__main__':
    unittest.main()
