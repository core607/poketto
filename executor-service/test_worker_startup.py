from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import test_worker as fixture
from worker import Rejected, load_config


class WorkerStartupTests(unittest.TestCase):
    def setUp(self):
        self.harness = fixture.ProtocolTests()
        self.harness.setUp()
        self.service = self.harness.service
        self.backend = self.harness.backend
        self.service.config.update(maxSessions=4, maxLiveUnits=4, maxStartingUnits=1)
        original_close = self.backend.close

        def close(session):
            original_close(session)
            session.channel, session.unit = None, ''

        self.backend.close = close
        self.addCleanup(self.service.shutdown)

    def opened(self, scope):
        self.harness.identity = {**self.harness.identity, 'leaseId': fixture.uid(),
                                 'serverSessionHash': scope * 64}
        request = self.harness.opened()
        session = self.service.sessions[request['leaseId']]
        session.bridge = Mock()
        return request, session

    def request(self, opened, operation, data=None):
        return {**opened, 'requestId': fixture.uid(), 'operation': operation, 'data': data or {}}

    def execution(self, opened):
        return self.request(opened, 'EXEC', {
            'executionId': fixture.uid(), 'commit': 'c' * 40, 'command': 'true', 'timeoutMillis': 1000})

    @contextmanager
    def holding_start(self, held, hold_command=False, fail=False):
        entered, greet, ready, complete = (threading.Event() for _ in range(4))
        if not hold_command:
            complete.set()

        def execute(session, data, on_start, on_ready):
            if session.channel is None:
                on_start()
                session.channel, session.unit = object(), 'unit-' + session.id
                if session is held:
                    entered.set()
                    self.assertTrue(greet.wait(5), 'Startup was not released')
                    if fail:
                        raise RuntimeError('Startup failed before the greeting')
                    if session.cancelled.is_set():
                        raise Rejected('SESSION_CLOSED')
                on_ready()
            if session is held:
                ready.set()
                self.assertTrue(complete.wait(5), 'The first command was not released')
            self.backend.executed.append(data)
            return {'exitCode': 0, 'terminationReason': 'normal'}

        with patch.object(self.backend, 'execute', side_effect=execute):
            with ThreadPoolExecutor(max_workers=4) as pool:
                try:
                    yield pool, entered, greet, ready, complete
                finally:
                    greet.set()
                    complete.set()

    def test_full_startup_budget_preserves_warm_units_and_allows_their_commands(self):
        self.service.config['maxLiveUnits'] = 2
        warm, warm_session = self.opened('a')
        warm_session.channel, warm_session.unit = object(), 'warm-unit'
        first, starting = self.opened('b')
        second, waiting = self.opened('c')
        retry = self.execution(second)
        with self.holding_start(starting) as (pool, entered, greet, _, _):
            running = pool.submit(self.harness.send, self.execution(first))
            self.assertTrue(entered.wait(2))
            refused = pool.submit(self.harness.send, retry).result(2)
            self.assertEqual('EXECUTION_CAPACITY', refused['code'])
            self.assertEqual('READY', waiting.state)
            self.assertNotIn((waiting.id, retry['data']['executionId']), self.service.executions)
            self.assertFalse(self.backend.stopped)
            self.assertTrue(pool.submit(self.harness.send, self.execution(warm)).result(2)['ok'])
            self.assertTrue(pool.submit(self.harness.send, self.request(first, 'RENEW')).result(2)['ok'])
            self.assertFalse(running.done())
            greet.set()
            self.assertTrue(running.result(2)['ok'])
            self.assertTrue(self.harness.send({**retry, 'requestId': fixture.uid()})['ok'])
        self.assertFalse(self.service.startups)

    def test_greeting_releases_startup_capacity_before_the_first_command_finishes(self):
        first, starting = self.opened('a')
        second, _ = self.opened('b')
        with self.holding_start(starting, hold_command=True) as (pool, entered, greet, ready, _):
            running = pool.submit(self.harness.send, self.execution(first))
            self.assertTrue(entered.wait(2))
            greet.set()
            self.assertTrue(ready.wait(2))
            self.assertFalse(running.done())
            self.assertTrue(pool.submit(self.harness.send, self.execution(second)).result(2)['ok'])
            self.assertFalse(running.done())
        self.assertTrue(running.result()['ok'])

    def test_cancelled_startup_retains_capacity_until_containment(self):
        first, starting = self.opened('a')
        second, _ = self.opened('b')
        with self.holding_start(starting) as (pool, entered, greet, _, _):
            running = pool.submit(self.harness.send, self.execution(first))
            self.assertTrue(entered.wait(2))
            self.assertEqual('CLOSING', self.harness.send(self.request(first, 'CLOSE'))['state'])
            self.assertEqual('EXECUTION_CAPACITY', self.harness.send(self.execution(second))['code'])
            greet.set()
            self.assertFalse(running.result(2)['ok'])
            self.assertEqual('CLOSED', starting.state)
            self.assertTrue(self.harness.send(self.execution(second))['ok'])
        self.assertFalse(self.service.startups)

    def test_failed_containment_keeps_the_startup_slot_until_a_confirmed_close(self):
        first, starting = self.opened('a')
        second, _ = self.opened('b')
        with self.holding_start(starting, fail=True) as (pool, entered, greet, _, _):
            running = pool.submit(self.harness.send, self.execution(first))
            self.assertTrue(entered.wait(2))
            with patch.object(self.backend, 'close', side_effect=RuntimeError('Containment unavailable')):
                greet.set()
                self.assertFalse(running.result(2)['ok'])
                self.assertEqual('CLOSING', starting.state)
                self.assertEqual('EXECUTION_CAPACITY', self.harness.send(self.execution(second))['code'])
            self.assertEqual('CLOSED', self.harness.send(self.request(first, 'CLOSE'))['state'])
            self.assertTrue(self.harness.send(self.execution(second))['ok'])
        self.assertFalse(self.service.startups)

    def test_live_unit_refusal_releases_its_unused_startup_reservation(self):
        self.service.config['maxLiveUnits'] = 1
        first, occupied = self.opened('a')
        occupied.channel, occupied.unit, occupied.state = object(), 'occupied-unit', 'RUNNING'
        second, _ = self.opened('b')
        self.assertEqual('EXECUTION_CAPACITY', self.harness.send(self.execution(second))['code'])
        self.assertFalse(self.service.startups)
        occupied.state = 'READY'
        self.assertTrue(self.harness.send(self.execution(second))['ok'])

    def test_startup_limit_is_an_optional_positive_integer_bounded_by_leases(self):
        config = json.loads(Path(__file__).with_name('config.example.json').read_text())
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'config.json'
            for value in (0, -1, True, '1', config['maxSessions'] + 1):
                with self.subTest(value=value):
                    path.write_text(json.dumps({**config, 'maxStartingUnits': value}))
                    with self.assertRaises(Rejected):
                        load_config(path)
            path.write_text(json.dumps({**config, 'maxStartingUnits': config['maxSessions']}))
            self.assertEqual(config['maxSessions'], load_config(path)['maxStartingUnits'])
            config.pop('maxStartingUnits')
            path.write_text(json.dumps(config))
            self.assertNotIn('maxStartingUnits', load_config(path))


if __name__ == '__main__':
    unittest.main()
