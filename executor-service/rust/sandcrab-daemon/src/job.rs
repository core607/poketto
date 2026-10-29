//! Finite jobs: one launcher record in a fresh unit, its bounded output, then confirmed
//! containment. A job is admitted under its request's lease, so revocation, lease expiry and
//! shutdown cancel it like any other lease.
use crate::{IDENTITY, Service, Session, now, string};
use rustix::{
    event::{PollFd, PollFlags, Timespec, poll},
    fs::{OFlags, fcntl_getfl, fcntl_setfl},
};
use sandcrab_core::unit::{Launch, checked, empty_group};
use serde_json::Value;
use std::{
    collections::HashSet,
    fs::File,
    io::{self, Read},
    os::fd::OwnedFd,
    process::{Child, Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

/// Why a job's unit ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum End {
    Normal,
    Timeout,
    OutputLimit,
    Cancelled,
    ResourceLimit,
}
pub struct Finished {
    pub exit_code: i32,
    /// Standard output and standard error, together at most the job's output limit.
    pub output: [Vec<u8>; 2],
    pub truncated: bool,
    pub end: End,
}
fn unavailable() -> io::Error {
    io::Error::other("Job unit unavailable")
}
fn systemctl(args: &[&str], unit: &str, seconds: u64) -> io::Result<std::process::Output> {
    let mut command = vec!["systemctl".to_string()];
    command.extend(args.iter().map(|v| v.to_string()));
    command.push(unit.into());
    checked(&command, Duration::from_secs(seconds))
}

/// Run `launch` once. The timeout, cancellation or output beyond `limit` bytes over both
/// streams kills the unit. The first result is the job's; the second confirms that the unit
/// stopped and left no process, and is an error whenever that is unknown.
/// The synchronous observer receives retained raw output while the job runs; it must not
/// block or interpret progress as proof of completion or containment.
pub fn run(
    launch: &Launch,
    cancelled: &AtomicBool,
    timeout: Duration,
    limit: usize,
    on_output: impl FnMut(usize, &[u8]),
) -> (io::Result<Finished>, io::Result<()>) {
    run_input(launch, cancelled, timeout, limit, Stdio::null(), on_output)
}

/// The caller owns the input producer and must stop it on cancellation. Passing a
/// pipe lets a bounded unit charge staged file pages to its own cgroup.
pub fn run_input(
    launch: &Launch,
    cancelled: &AtomicBool,
    timeout: Duration,
    limit: usize,
    input: Stdio,
    mut on_output: impl FnMut(usize, &[u8]),
) -> (io::Result<Finished>, io::Result<()>) {
    let (program, args) = match launch.command.split_first() {
        Some(parts) => parts,
        None => return (Err(unavailable()), Ok(())),
    };
    let child = Command::new(program)
        .args(args)
        .stdin(input)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn();
    let (finished, mut child) = match child {
        Ok(mut child) => (
            watch(
                launch,
                &mut child,
                cancelled,
                timeout,
                limit,
                &mut on_output,
            ),
            Some(child),
        ),
        Err(error) => (Err(error), None),
    };
    let _ = systemctl(&["stop"], &launch.unit, 15);
    let mut contained = Ok(());
    if let Some(child) = &mut child
        && child.try_wait().ok().flatten().is_none()
    {
        let _ = child.kill();
        contained = child.wait().map(|_| ());
    }
    let contained = contained.and_then(|()| empty_group(&launch.cgroup));
    let _ = systemctl(&["reset-failed"], &launch.unit, 5);
    (finished, contained)
}
fn watch(
    launch: &Launch,
    child: &mut Child,
    cancelled: &AtomicBool,
    timeout: Duration,
    limit: usize,
    on_output: &mut impl FnMut(usize, &[u8]),
) -> io::Result<Finished> {
    let mut streams = [
        File::from(OwnedFd::from(child.stdout.take().ok_or_else(unavailable)?)),
        File::from(OwnedFd::from(child.stderr.take().ok_or_else(unavailable)?)),
    ];
    for stream in &streams {
        fcntl_setfl(stream, fcntl_getfl(stream)? | OFlags::NONBLOCK)?;
    }
    let started = Instant::now();
    let mut open = [true, true];
    let mut output = [Vec::new(), Vec::new()];
    let mut truncated = false;
    let mut end = End::Normal;
    let mut next_kill = started;
    let mut kill = |reason: End, end: &mut End| {
        if *end == End::Normal {
            *end = reason;
        }
        // systemd-run can still be registering the unit. Neither a failed kill nor a
        // successful kill of an activating unit proves its future processes are gone.
        if Instant::now() >= next_kill {
            let _ = systemctl(
                &["kill", "--kill-who=all", "--signal=KILL"],
                &launch.unit,
                5,
            );
            next_kill = Instant::now() + Duration::from_millis(100);
        }
    };
    while open[0] || open[1] {
        if end != End::Normal {
            kill(end, &mut end);
        } else if cancelled.load(Ordering::SeqCst) {
            kill(End::Cancelled, &mut end);
        } else if started.elapsed() > timeout {
            kill(End::Timeout, &mut end);
        }
        // A killed unit closes its pipes promptly; a launcher that does not is abandoned.
        if started.elapsed() > timeout + Duration::from_secs(15) {
            return Err(unavailable());
        }
        let ready = {
            let indices: Vec<usize> = (0..2).filter(|index| open[*index]).collect();
            let mut fds: Vec<_> = indices
                .iter()
                .map(|index| PollFd::new(&streams[*index], PollFlags::IN))
                .collect();
            poll(
                &mut fds,
                Some(&Timespec::try_from(Duration::from_millis(50)).unwrap()),
            )?;
            let mut ready = [false, false];
            for (index, fd) in indices.iter().zip(&fds) {
                ready[*index] = fd
                    .revents()
                    .intersects(PollFlags::IN | PollFlags::ERR | PollFlags::HUP);
            }
            ready
        };
        for index in 0..2 {
            if !ready[index] {
                continue;
            }
            let mut buffer = [0; 8192];
            match streams[index].read(&mut buffer) {
                Ok(0) => open[index] = false,
                Ok(n) => {
                    let available = limit - output[0].len() - output[1].len();
                    output[index].extend_from_slice(&buffer[..n.min(available)]);
                    if available != 0 {
                        on_output(index, &buffer[..n.min(available)]);
                    }
                    if n > available {
                        truncated = true;
                        kill(End::OutputLimit, &mut end);
                    }
                }
                Err(e)
                    if matches!(
                        e.kind(),
                        io::ErrorKind::WouldBlock | io::ErrorKind::Interrupted
                    ) => {}
                Err(e) => return Err(e),
            }
        }
    }
    let deadline = Instant::now() + Duration::from_secs(5);
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            return Err(unavailable());
        }
        std::thread::sleep(Duration::from_millis(10));
    };
    let shown = systemctl(
        &["show", "-p", "Result", "-p", "ExecMainStatus"],
        &launch.unit,
        5,
    )?;
    let shown = String::from_utf8_lossy(&shown.stdout);
    let property = |name: &str| {
        shown
            .lines()
            .find_map(|line| line.strip_prefix(name)?.strip_prefix('='))
    };
    if end == End::Normal {
        match property("Result") {
            Some("timeout") => end = End::Timeout,
            Some("oom-kill") => end = End::ResourceLimit,
            _ => {}
        }
    }
    let mut exit_code = property("ExecMainStatus")
        .and_then(|v| v.parse().ok())
        .or(status.code())
        .unwrap_or(-1);
    if end != End::Normal && exit_code == 0 {
        exit_code = if end == End::Timeout { 124 } else { 137 };
    }
    Ok(Finished {
        exit_code,
        output,
        truncated,
        end,
    })
}

/// The result of a job's handler: `Err` when containment was not confirmed.
pub type Outcome = io::Result<Result<Value, &'static str>>;

impl Service {
    /// Admit a finite job under the request's lease and run it. `run` receives the lease's
    /// cancellation flag. A job whose containment `run` cannot confirm keeps its admission
    /// slot until the supervisor restarts and cleans up. Refusals are `WORKER_STOPPING`,
    /// `AUTH_REVOKED`, `JOB_BUSY` for a lease already running, `JOB_CAPACITY`, and
    /// `JOB_EXPIRED` for a job cancelled or past its lease, whatever it returned.
    pub fn job(
        &self,
        payload: &Value,
        run: impl FnOnce(&AtomicBool) -> Outcome,
    ) -> Result<Value, &'static str> {
        let lease = string(payload, "leaseId")?.to_owned();
        let deadline = payload["expiresAt"].as_u64().ok_or("INVALID_REQUEST")? as f64;
        let cancelled = Arc::new(AtomicBool::new(false));
        {
            let mut state = self.state.lock().unwrap();
            if self.shutdown_started.load(Ordering::SeqCst) {
                return Err("WORKER_STOPPING");
            }
            Self::authorize(&state, payload)?;
            if state.sessions.contains_key(&lease) {
                return Err("JOB_BUSY");
            }
            if state
                .sessions
                .values()
                .filter(|s| s.status != "CLOSED")
                .count()
                >= self.max_sessions
            {
                return Err("JOB_CAPACITY");
            }
            let identity = IDENTITY
                .iter()
                .map(|name| string(payload, name).map(str::to_owned))
                .collect::<Result<_, _>>()?;
            state.sessions.insert(
                lease.clone(),
                Session {
                    job: true,
                    identity,
                    deadline,
                    cancelled: cancelled.clone(),
                    reason: "session_closed".into(),
                    commit: String::new(),
                    git_commit: String::new(),
                    scope: String::new(),
                    files: None,
                    executions: HashSet::new(),
                    runtime: None,
                    live_unit: false,
                    idle_since: Instant::now(),
                    status: "RUNNING",
                    execution: String::new(),
                    bridge: None,
                },
            );
        }
        let outcome = run(&cancelled);
        let mut state = self.state.lock().unwrap();
        let Ok(result) = outcome else {
            if let Some(session) = state.sessions.get_mut(&lease) {
                session.status = "CLOSING";
            }
            return Err("EXECUTOR_FAILED");
        };
        state.sessions.remove(&lease);
        if cancelled.load(Ordering::SeqCst) || now() >= deadline {
            return Err("JOB_EXPIRED");
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Extension, Settings};
    use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
    use ed25519_dalek::{Signer, SigningKey};
    use sandcrab_core::transfer;
    use serde_json::json;
    use std::{
        sync::{Mutex, mpsc},
        thread,
    };

    /// Each job reports its start, waits for the outcome the test sends and reports whether
    /// it saw cancellation.
    struct Jobs {
        entered: Mutex<mpsc::Sender<()>>,
        release: Mutex<mpsc::Receiver<Outcome>>,
        observed: Mutex<mpsc::Sender<bool>>,
    }
    impl Extension for Jobs {
        fn operations(&self) -> &'static [&'static str] {
            &["JOB"]
        }
        fn lease_operations(&self) -> &'static [&'static str] {
            &["REVOKE"]
        }
        fn hello(&self) -> serde_json::Map<String, Value> {
            let Value::Object(fields) = json!({"jobProtocol":1}) else {
                unreachable!()
            };
            fields
        }
        fn tree(&self) -> transfer::Tree {
            transfer::Tree {
                directories: &[],
                reserved: "",
            }
        }
        fn bridge_operations(&self) -> &'static [&'static str] {
            &[]
        }
        fn handle(&self, service: &Service, payload: &Value) -> Result<Value, &'static str> {
            service.job(payload, |cancelled| {
                self.entered.lock().unwrap().send(()).unwrap();
                let outcome = self.release.lock().unwrap().recv().unwrap();
                self.observed
                    .lock()
                    .unwrap()
                    .send(cancelled.load(Ordering::SeqCst))
                    .unwrap();
                outcome
            })
        }
    }
    struct Fixture {
        service: Arc<Service>,
        key: SigningKey,
        boot: String,
        entered: mpsc::Receiver<()>,
        release: mpsc::Sender<Outcome>,
        observed: mpsc::Receiver<bool>,
    }
    fn id() -> String {
        sandcrab_core::new_uuid().unwrap()
    }
    impl Fixture {
        fn new(sessions: usize) -> Self {
            let (entered, entered_receiver) = mpsc::channel();
            let (release_sender, release) = mpsc::channel();
            let (observed, observed_receiver) = mpsc::channel();
            let key = SigningKey::from_bytes(&[7; 32]);
            let mut settings = Settings::new(id(), 64);
            settings.max_sessions = sessions;
            settings.max_live_units = sessions;
            settings.max_starting_units = sessions;
            let jobs = Jobs {
                entered: Mutex::new(entered),
                release: Mutex::new(release),
                observed: Mutex::new(observed),
            };
            let service = Arc::new(
                Service::new(settings, key.verifying_key(), None, Box::new(jobs)).unwrap(),
            );
            let hello = service.hello();
            Self {
                boot: hello["workerBootId"].as_str().unwrap().into(),
                service,
                key,
                entered: entered_receiver,
                release: release_sender,
                observed: observed_receiver,
            }
        }
        fn sign(&self, payload: &Value) -> Value {
            let raw = serde_json::to_vec(payload).unwrap();
            json!({"payload":URL_SAFE_NO_PAD.encode(&raw),
                "signature":URL_SAFE_NO_PAD.encode(self.key.sign(&raw).to_bytes())})
        }
        /// A signed request; `identity` may set the principal, account and workspace.
        fn envelope(&self, operation: &str, data: Value, identity: &Value) -> Value {
            let clock = now() as u64;
            let mut payload = json!({"version":1,"workerBootId":self.boot,"appBootId":id(),
                "operation":operation,"requestId":id(),"issuedAt":clock,"expiresAt":clock + 10,
                "principalId":id(),"accountId":id(),"workspaceId":id(),"serverSessionHash":"0".repeat(64),
                "leaseId":id(),"data":data});
            for name in ["principalId", "accountId", "workspaceId"] {
                if let Some(value) = identity.get(name) {
                    payload[name] = value.clone();
                }
            }
            self.sign(&payload)
        }
        fn payload(envelope: &Value) -> Value {
            let raw = URL_SAFE_NO_PAD
                .decode(envelope["payload"].as_str().unwrap())
                .unwrap();
            serde_json::from_slice(&raw).unwrap()
        }
        fn job(&self, identity: &Value) -> Value {
            self.envelope("JOB", json!({}), identity)
        }
        fn revoke(&self, identity: &Value) -> Value {
            let data = json!({"keyIds":[identity["principalId"]],"accountIds":[]});
            self.envelope("REVOKE", data, identity)
        }
        fn start(&self, envelope: Value) -> thread::JoinHandle<Value> {
            let service = self.service.clone();
            let handle = thread::spawn(move || service.handle(envelope, false));
            self.entered.recv_timeout(Duration::from_secs(3)).unwrap();
            handle
        }
        fn finish(&self, outcome: Outcome) -> bool {
            self.release.send(outcome).unwrap();
            self.observed.recv_timeout(Duration::from_secs(3)).unwrap()
        }
        fn jobs(&self) -> usize {
            self.service.state.lock().unwrap().sessions.len()
        }
    }

    #[test]
    fn a_replayed_job_returns_its_one_result_and_releases_admission() {
        let fixture = Fixture::new(1);
        let hello = fixture.service.hello();
        assert_eq!(hello["jobProtocol"], 1);
        let request = fixture.job(&json!({}));
        fixture.release.send(Ok(Ok(json!({"step":1})))).unwrap();
        let first = fixture.service.handle(request.clone(), false);
        assert_eq!(first["step"], 1);
        assert_eq!(fixture.service.handle(request, false), first);
        assert!(fixture.entered.try_recv().is_ok());
        assert!(fixture.entered.try_recv().is_err());
        assert_eq!(fixture.jobs(), 0);
    }
    #[test]
    fn lease_operations_outside_the_extensions_set_are_invalid() {
        let fixture = Fixture::new(1);
        for operation in [
            "EXEC",
            "RENEW",
            "CLOSE",
            "BRIDGE_POLL",
            "MATERIALIZE_BEGIN",
            "OPEN",
        ] {
            let reply = fixture
                .service
                .handle(fixture.envelope(operation, json!({}), &json!({})), false);
            assert_eq!(reply["code"], "INVALID_REQUEST", "{operation}");
        }
        assert!(fixture.entered.try_recv().is_err());
    }
    #[test]
    fn revocation_cancels_a_running_job_and_refuses_new_ones() {
        let fixture = Fixture::new(2);
        let request = fixture.job(&json!({}));
        let identity = Fixture::payload(&request);
        let running = fixture.start(request);
        let revoked = fixture.service.handle(fixture.revoke(&identity), false);
        assert_eq!(revoked["state"], "CLOSING");
        assert!(fixture.finish(Ok(Ok(json!({})))));
        assert_eq!(running.join().unwrap()["code"], "JOB_EXPIRED");
        let refused = fixture.service.handle(fixture.job(&identity), false);
        assert_eq!(refused["code"], "AUTH_REVOKED");
        let revoked = fixture.service.handle(fixture.revoke(&identity), false);
        assert_eq!(revoked["state"], "CLOSED");
    }
    #[test]
    fn capacity_is_finite_and_an_unconfirmed_cleanup_keeps_its_slot() {
        let fixture = Fixture::new(1);
        let running = fixture.start(fixture.job(&json!({})));
        let refused = fixture.service.handle(fixture.job(&json!({})), false);
        assert_eq!(refused["code"], "JOB_CAPACITY");
        assert!(!fixture.finish(Ok(Ok(json!({"done":true})))));
        assert_eq!(running.join().unwrap()["done"], true);
        let request = fixture.job(&json!({}));
        let identity = Fixture::payload(&request);
        let leaked = fixture.start(request);
        fixture.finish(Err(io::Error::other("descendants remain")));
        assert_eq!(leaked.join().unwrap()["code"], "EXECUTOR_FAILED");
        assert_eq!(fixture.jobs(), 1);
        let refused = fixture.service.handle(fixture.job(&json!({})), false);
        assert_eq!(refused["code"], "JOB_CAPACITY");
        // Revocation never reports an unconfirmed job as closed.
        let revoked = fixture.service.handle(fixture.revoke(&identity), false);
        assert_eq!(revoked["state"], "CLOSING");
    }
    #[test]
    fn a_lease_runs_one_job_at_a_time() {
        let fixture = Fixture::new(2);
        let request = fixture.job(&json!({}));
        let lease = Fixture::payload(&request)["leaseId"].clone();
        let running = fixture.start(request);
        let mut payload = Fixture::payload(&fixture.job(&json!({})));
        payload["leaseId"] = lease;
        let second = fixture.sign(&payload);
        assert_eq!(fixture.service.handle(second, false)["code"], "JOB_BUSY");
        fixture.finish(Ok(Ok(json!({"done":true}))));
        assert_eq!(running.join().unwrap()["done"], true);
    }
    #[test]
    fn shutdown_refuses_new_jobs_and_waits_for_running_ones() {
        let fixture = Fixture::new(2);
        let running = fixture.start(fixture.job(&json!({})));
        let service = fixture.service.clone();
        let stopping = thread::spawn(move || service.shutdown());
        while !fixture.service.shutdown_started.load(Ordering::SeqCst) {
            thread::sleep(Duration::from_millis(5));
        }
        let refused = fixture.service.handle(fixture.job(&json!({})), false);
        assert_eq!(refused["code"], "WORKER_STOPPING");
        thread::sleep(Duration::from_millis(50));
        assert!(!stopping.is_finished());
        assert!(fixture.finish(Ok(Ok(json!({})))));
        assert_eq!(running.join().unwrap()["code"], "JOB_EXPIRED");
        assert!(stopping.join().unwrap().is_ok());
    }
}
