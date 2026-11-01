# Sandcrab density experiment

The [lightweight-service proposal](../../notes/proposed/2026-11-02-sandcrab-lightweight-service.md)
owns the footprint, readiness, density and breakdown requirements. This directory
contains the seeded workload, stage scorer and native HTTP client used to construct
the independent benchmark. Component tests do not establish sandbox capacity.

## Workload and scoring

[protocol.json](protocol.json) fixes the measurement parameters before native runs.
Memory budgets use decimal GB; receipts must include the effective page-rounded
kernel byte limits. A stage passes only when every repetition meets the thresholds.
The first failed repetition ends the search for that budget; retain it and identify
unattempted stages. If all scheduled stages pass, capacity is a lower bound.

[workload.py](workload.py) defines the command probabilities and synthetic files.
The corpus has 20,000 Markdown documents and a separate 32-file Git repository.
The seed fixes their bytes and Git commit. Each agent has an independent seeded
sequence of uniform think intervals and commands. Report the observed command mix
as well as the configured probabilities. Corpus creation refuses an occupied root
and ignores ambient Git paths and configuration so the fixture cannot redirect
writes to another repository.

[scoring.py](scoring.py) judges requests by submission time, including slow
completions after the measurement window. Refusals and transport failures remain
in the success-rate denominator and latency population. A missing OOM observation
does not mean zero OOM kills. Use unrounded nanoseconds for the inclusive one-second
threshold. Record admission failures separately from CPU or memory saturation;
a configured sandbox ceiling is not a measurement of physical host capacity.

[client.py](client.py) uses bounded JSON over the public Unix HTTP interface and
checks the service's peer UID before sending request bytes. It sends each request
once. A lost command acknowledgement remains uncertain; observing its original ID
does not authorize another command submission. New connections avoid commands
racing the server's idle-connection deadline; connection and polling costs belong
in the reported client latency.

## Component checks

From the repository root, with Python 3.10 or newer, Git, bash and ripgrep available:

```sh
python3 -m unittest discover -s experiments/sandcrab-density -p 'test_*.py' -v
```

Linux runs the real command mix and Unix-peer/transport checks. On Windows those
checks skip; a Windows pass is not Linux evidence. Native fixture execution,
host-resource collection, readiness runs and the cold-start/page-cache breakdown
must be verified before publishing a complete W0 result.
