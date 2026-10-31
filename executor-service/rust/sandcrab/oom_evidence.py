"""Evidence required by the owned native OOM fixture; exit signals alone are insufficient."""
import re


class PendingKernelEvidence(AssertionError):
    """Counters may precede journald; only an absent scope may be observed again."""


def reject_global_oom(lines):
    assert not any('oom-kill:' in line and 'constraint=CONSTRAINT_NONE' in line
                   for line in lines.splitlines()), 'Host-wide OOM invalidates the fixture'


def kernel_victims(lines, group, *, required_pid=None, forbidden_pid=None):
    if not group.startswith('/') or group == '/' or '..' in group.split('/'):
        raise ValueError('An exact owned cgroup is required')
    victims = []
    reject_global_oom(lines)
    for line in lines.splitlines():
        if 'oom-kill:' not in line:
            continue
        fields = dict(re.findall(r'([a-z_]+)=([^, ]+)', line))
        if fields.get('oom_memcg') != group:
            continue
        assert fields.get('constraint') == 'CONSTRAINT_MEMCG', 'Expected a memcg OOM'
        task = fields.get('task_memcg', '')
        assert task == group or task.startswith(group + '/'), 'Victim escaped the owned cgroup'
        pid = fields.get('pid', '')
        assert pid.isdecimal() and int(pid) > 0, 'Kernel victim identity is missing'
        victims.append({'pid': int(pid), 'task': fields.get('task'), 'taskCgroup': task})
    if not victims:
        raise PendingKernelEvidence('No kernel OOM belongs to the exact expected cgroup')
    pids = {victim['pid'] for victim in victims}
    if required_pid is not None:
        assert required_pid in pids, 'The required victim was not killed by this memcg OOM'
    if forbidden_pid is not None:
        assert forbidden_pid not in pids, 'The protected main process was a kernel victim'
    return victims


def increased(before, after, local_before, local_after):
    assert after.get('oom_kill', 0) > before.get('oom_kill', 0), 'No hierarchical OOM victim was observed'
    if local_after is None:
        return  # A stopped service may lose its cgroup; exact kernel scope is still required separately.
    assert local_after.get('oom', 0) > local_before.get('oom', 0), 'No local cgroup OOM was observed'


def unit_policy(values, score, *, policy='stop'):
    assert values.get('OOMPolicy') == policy, 'Unexpected service OOM policy'
    assert values.get('OOMScoreAdjust') == '-900' and score == '-900', 'Supervision protection was not applied'
    assert values.get('KillMode') == 'control-group', 'Service descendants must stop together'


def process_topology(main, relays, guests, service_group, pool_group):
    assert main['cgroup'] == service_group and main['oomScoreAdjust'] == -900
    assert relays, 'No real systemd-run relay was observed in the supervisor service'
    for relay in relays:
        assert relay['exe'] == 'systemd-run'
        assert relay['cgroup'] == service_group and relay['oomScoreAdjust'] == -900
    assert guests, 'No independent sandbox process was observed'
    for guest in guests:
        assert guest['cgroup'].startswith(pool_group + '/')
        assert guest['cgroup'] != service_group
        assert guest['oomScoreAdjust'] == 0, 'Independent units inherited supervisor protection'
