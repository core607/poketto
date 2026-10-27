#![forbid(unsafe_code)]
//! Signed leases and privileged lifecycle ownership: request verification and replay,
//! renewal, closure, revocation, execution, host calls, captures, installs and artifacts.
//! Product operations, such as how a lease's volume is provisioned, arrive through an
//! [`Extension`].

mod admission;
pub mod files;
pub mod http;
pub mod install;
pub mod job;
pub mod runtime;
pub mod server;
use sandcrab_core::{artifacts, mailbox, transfer, uuid};

use base64::{
    Engine, alphabet,
    engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig},
};
use ed25519_dalek::{Signature, VerifyingKey};
use sandcrab_core::parse;
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    io::{self, Write},
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub const MAX_FRAME: usize = 1_048_576;
const FIELDS: [&str; 13] = [
    "version",
    "workerBootId",
    "appBootId",
    "operation",
    "requestId",
    "issuedAt",
    "expiresAt",
    "principalId",
    "accountId",
    "workspaceId",
    "serverSessionHash",
    "leaseId",
    "data",
];
/// Signed lease operations the daemon serves itself; an extension may serve fewer and adds its own.
pub const LEASE_OPERATIONS: &[&str] = &[
    "RENEW",
    "CLOSE",
    "REVOKE",
    "BRIDGE_POLL",
    "BRIDGE_COMPLETE",
    "EXEC",
    "ARTIFACT_CREATE",
    "ARTIFACT_READ",
    "ARTIFACT_REMOVE",
    "SNAPSHOT",
    "MATERIALIZE_BEGIN",
    "MATERIALIZE_CHUNK",
    "MATERIALIZE_COMMIT",
    "MATERIALIZE_ABORT",
];
/// The caller identity a lease is bound to, in the order of [`Session::identity`].
pub const IDENTITY: [&str; 5] = [
    "principalId",
    "accountId",
    "workspaceId",
    "serverSessionHash",
    "appBootId",
];
const ENCODING: GeneralPurpose = GeneralPurpose::new(
    &alphabet::URL_SAFE,
    GeneralPurposeConfig::new()
        .with_decode_padding_mode(DecodePaddingMode::Indifferent)
        .with_decode_allow_trailing_bits(true),
);

/// Product operations served beside the daemon's lease lifecycle. The daemon verifies the
/// signature and the envelope and registers the request for replay before calling
/// [`Extension::handle`].
pub trait Extension: Send + Sync {
    /// Signed operations the extension accepts in addition to the daemon's own.
    fn operations(&self) -> &'static [&'static str];
    /// The daemon's own signed operations this service accepts; a service of finite jobs
    /// keeps only `REVOKE`.
    fn lease_operations(&self) -> &'static [&'static str] {
        LEASE_OPERATIONS
    }
    /// Protocol fields of the unsigned HELLO reply, between its version and worker fields.
    fn hello(&self) -> serde_json::Map<String, Value>;
    /// The directory below each lease mount that captures, installs and artifacts address.
    fn tree(&self) -> transfer::Tree;
    /// Host-call operations a sandbox may send through its mailbox.
    fn bridge_operations(&self) -> &'static [&'static str];
    /// Serve one request whose operation is in [`Extension::operations`].
    fn handle(&self, service: &Service, payload: &Value) -> Result<Value, &'static str>;
}

pub fn now() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs_f64()
}
#[cfg(feature = "fixture")]
pub fn cpu() -> f64 {
    let time = rustix::time::clock_gettime(rustix::time::ClockId::ProcessCPUTime);
    time.tv_sec as f64 + time.tv_nsec as f64 / 1e9
}
fn failed(code: &str) -> Value {
    json!({"ok": false, "code": code})
}
pub fn string<'a>(v: &'a Value, key: &str) -> Result<&'a str, &'static str> {
    v.get(key).and_then(Value::as_str).ok_or("INVALID_REQUEST")
}
fn decode(v: &Value) -> Result<Vec<u8>, &'static str> {
    let text = v.as_str().ok_or("INVALID_REQUEST")?;
    if text.is_empty()
        || !text
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    {
        return Err("INVALID_REQUEST");
    }
    ENCODING.decode(text).map_err(|_| "INVALID_REQUEST")
}

/// Service limits and, for fixtures, preconfigured leases.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    #[cfg(feature = "fixture")]
    pub public_key: String,
    pub worker_boot_id: String,
    pub sessions: Vec<Value>,
    pub max_requests: usize,
    #[serde(default = "default_lease_seconds")]
    pub lease_seconds: u64,
    #[serde(default = "default_renew_after_seconds")]
    pub renew_after_seconds: u64,
    #[cfg(feature = "fixture")]
    pub max_connections: usize,
    #[serde(default)]
    pub runtime_root: Option<PathBuf>,
    #[serde(default = "default_sessions")]
    pub max_sessions: usize,
    #[serde(default = "default_timeout")]
    pub max_timeout_millis: u64,
    #[serde(default = "default_executions")]
    pub max_executions_per_session: usize,
    #[serde(default = "default_live_units")]
    pub max_live_units: usize,
    #[serde(default = "default_starting_units")]
    pub max_starting_units: usize,
    #[serde(default = "default_idle_seconds")]
    pub idle_unit_seconds: u64,
    #[serde(default)]
    pub revoked_keys: Vec<(String, String)>,
    #[serde(default)]
    pub revoked_accounts: Vec<(String, String)>,
}
impl Settings {
    /// Installed settings with the default limits, which callers then override.
    pub fn new(worker_boot_id: String, max_requests: usize) -> Self {
        Self {
            #[cfg(feature = "fixture")]
            public_key: String::new(),
            worker_boot_id,
            sessions: vec![],
            max_requests,
            lease_seconds: default_lease_seconds(),
            renew_after_seconds: default_renew_after_seconds(),
            #[cfg(feature = "fixture")]
            max_connections: 1,
            runtime_root: None,
            max_sessions: default_sessions(),
            max_timeout_millis: default_timeout(),
            max_executions_per_session: default_executions(),
            max_live_units: default_live_units(),
            max_starting_units: default_starting_units(),
            idle_unit_seconds: default_idle_seconds(),
            revoked_keys: vec![],
            revoked_accounts: vec![],
        }
    }
}
fn default_lease_seconds() -> u64 {
    15
}
fn default_renew_after_seconds() -> u64 {
    5
}
fn default_sessions() -> usize {
    600
}
fn default_timeout() -> u64 {
    60_000
}
fn default_executions() -> usize {
    1000
}
fn default_starting_units() -> usize {
    1
}
fn default_idle_seconds() -> u64 {
    300
}
fn default_live_units() -> usize {
    224
}
/// One lease. `commit`, `git_commit` and `scope` are labels the extension sets; the daemon
/// compares `commit` with each EXEC request and returns the labels unchanged. A finite job's
/// lease (`job`) is closed and removed by the handler that runs it, never by cancellation.
pub struct Session {
    pub job: bool,
    pub identity: Vec<String>,
    pub deadline: f64,
    pub cancelled: Arc<AtomicBool>,
    pub reason: String,
    pub commit: String,
    pub git_commit: String,
    pub scope: String,
    pub files: Option<Arc<files::Shared>>,
    pub executions: HashSet<String>,
    pub runtime: Option<Arc<Mutex<runtime::Runtime>>>,
    pub live_unit: bool,
    pub idle_since: Instant,
    pub status: &'static str,
    pub execution: String,
    pub bridge: Option<Arc<mailbox::Mailbox>>,
}
struct Replay {
    digest: [u8; 32],
    lease: String,
    answer: Option<Value>,
    expires: u64,
}
#[derive(Default)]
pub struct State {
    pub sessions: HashMap<String, Session>,
    requests: HashMap<String, Replay>,
    revoked_keys: HashMap<(String, String), f64>,
    revoked_accounts: HashMap<(String, String), f64>,
    pub starting: HashSet<String>,
    pub closed_leases: HashMap<String, f64>,
}
pub struct Service {
    key: VerifyingKey,
    boot: String,
    pub state: Mutex<State>,
    max_requests: usize,
    lease_seconds: u64,
    renew_after_seconds: u64,
    max_timeout_millis: u64,
    max_executions_per_session: usize,
    pub fenced: AtomicBool,
    max_live_units: usize,
    max_starting_units: usize,
    idle_unit_seconds: u64,
    _root_lock: Option<std::fs::File>,
    pub extension: Box<dyn Extension>,
    pub max_sessions: usize,
    pub shutdown_started: AtomicBool,
}
impl Service {
    /// Start from verified settings; fixture leases have bridges but no sandbox runtime.
    pub fn new(
        settings: Settings,
        key: VerifyingKey,
        root_lock: Option<std::fs::File>,
        extension: Box<dyn Extension>,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        assert!(uuid(&settings.worker_boot_id));
        assert!((1..=1 << 40).contains(&settings.max_requests));
        assert!((1..=settings.max_sessions).contains(&settings.max_live_units));
        assert!((1..=settings.max_sessions).contains(&settings.max_starting_units));
        assert!((1..=86400).contains(&settings.idle_unit_seconds));
        assert!(
            (1..=1 << 40).contains(&settings.max_sessions)
                && settings.sessions.len() <= settings.max_sessions
        );
        assert!((1..=1 << 40).contains(&settings.max_timeout_millis));
        assert!((1..=1 << 40).contains(&settings.max_executions_per_session));
        assert!(
            settings.lease_seconds > 0
                && settings.renew_after_seconds > 0
                && settings.renew_after_seconds < settings.lease_seconds
        );
        let mut state = State::default();
        let until = now() + settings.lease_seconds as f64 + 2.0;
        state.revoked_keys = settings
            .revoked_keys
            .into_iter()
            .map(|entry| (entry, until))
            .collect();
        state.revoked_accounts = settings
            .revoked_accounts
            .into_iter()
            .map(|entry| (entry, until))
            .collect();
        for value in settings.sessions {
            let lease = string(&value, "leaseId")?;
            assert!(uuid(lease));
            let identity: Vec<String> = IDENTITY
                .into_iter()
                .map(|name| string(&value, name).map(str::to_owned))
                .collect::<Result<_, _>>()?;
            let execution = value["executionId"].as_str().unwrap_or("").to_owned();
            assert!(execution.is_empty() || uuid(&execution));
            let bridge = if let Some(path) = value["bridgePath"].as_str() {
                assert!(PathBuf::from(path).is_absolute());
                let gid = value["execGid"].as_u64().ok_or("bridge gid")?.try_into()?;
                let bridge = Arc::new(mailbox::Mailbox::create(
                    std::path::Path::new(path),
                    gid,
                    extension.bridge_operations(),
                )?);
                bridge.reset(&execution)?;
                Some(bridge)
            } else {
                None
            };
            let commit = value["commit"]
                .as_str()
                .unwrap_or("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            state.sessions.insert(
                lease.into(),
                Session {
                    job: false,
                    identity,
                    deadline: value["deadline"].as_f64().ok_or("deadline")?,
                    cancelled: Arc::new(AtomicBool::new(false)),
                    reason: "session_closed".into(),
                    commit: commit.into(),
                    git_commit: value["gitCommit"].as_str().unwrap_or(commit).into(),
                    scope: "full".into(),
                    files: None,
                    executions: HashSet::new(),
                    runtime: None,
                    live_unit: false,
                    idle_since: Instant::now(),
                    status: if execution.is_empty() {
                        "READY"
                    } else {
                        "RUNNING"
                    },
                    execution,
                    bridge,
                },
            );
        }
        Ok(Self {
            key,
            boot: settings.worker_boot_id,
            state: Mutex::new(state),
            max_requests: settings.max_requests,
            lease_seconds: settings.lease_seconds,
            renew_after_seconds: settings.renew_after_seconds,
            max_timeout_millis: settings.max_timeout_millis,
            max_executions_per_session: settings.max_executions_per_session,
            fenced: AtomicBool::new(false),
            max_live_units: settings.max_live_units,
            max_starting_units: settings.max_starting_units,
            idle_unit_seconds: settings.idle_unit_seconds,
            _root_lock: root_lock,
            extension,
            max_sessions: settings.max_sessions,
            shutdown_started: AtomicBool::new(false),
        })
    }
    /// `snapshot` is the snapshot route, which serves `SNAPSHOT` and nothing else.
    fn validate(
        &self,
        envelope: &Value,
        snapshot: bool,
    ) -> Result<(Value, [u8; 32]), &'static str> {
        let object = envelope.as_object().ok_or("INVALID_REQUEST")?;
        if object.len() != 2 || !object.contains_key("payload") || !object.contains_key("signature")
        {
            return Err("INVALID_REQUEST");
        }
        let raw = decode(&envelope["payload"])?;
        let signature = decode(&envelope["signature"])?;
        let signature = Signature::from_slice(&signature).map_err(|_| "INVALID_SIGNATURE")?;
        self.key
            .verify_strict(&raw, &signature)
            .map_err(|_| "INVALID_SIGNATURE")?;
        let payload = parse(&raw)?;
        let object = payload.as_object().ok_or("INVALID_REQUEST")?;
        if object.len() != 13 || !FIELDS.iter().all(|name| object.contains_key(*name)) {
            return Err("INVALID_REQUEST");
        }
        // Preserve the existing Python equality check for wire version 1, including 1.0/true.
        if payload["version"].as_f64() != Some(1.0) && payload["version"] != Value::Bool(true) {
            return Err("INVALID_REQUEST");
        }
        if payload["workerBootId"] != self.boot {
            return Err("WORKER_RESTARTED");
        }
        for name in [
            "appBootId",
            "requestId",
            "principalId",
            "accountId",
            "workspaceId",
            "leaseId",
        ] {
            if !uuid(string(&payload, name)?) {
                return Err("INVALID_REQUEST");
            }
        }
        let hash = string(&payload, "serverSessionHash")?;
        if hash.len() != 64
            || !hash
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err("INVALID_REQUEST");
        }
        let issued = payload["issuedAt"]
            .as_u64()
            .filter(|v| *v <= 1 << 53)
            .ok_or("INVALID_REQUEST")?;
        let expires = payload["expiresAt"]
            .as_u64()
            .filter(|v| *v <= 1 << 53)
            .ok_or("INVALID_REQUEST")?;
        let clock = now();
        if issued as f64 > clock + 2.0
            || expires as f64 <= clock
            || expires <= issued
            || expires - issued > self.lease_seconds
        {
            return Err("LEASE_EXPIRED");
        }
        let operation = string(&payload, "operation")?;
        if !(self.extension.lease_operations().contains(&operation)
            || self.extension.operations().contains(&operation))
            || !payload["data"].is_object()
            || (operation == "SNAPSHOT") != snapshot
        {
            return Err("INVALID_REQUEST");
        }
        Ok((payload, Sha256::digest(&raw).into()))
    }
    /// The unsigned handshake: protocol versions, boot identity and lease timing.
    fn hello(&self) -> Value {
        let mut reply = serde_json::Map::new();
        reply.insert("ok".into(), true.into());
        reply.insert("version".into(), 1.into());
        reply.extend(self.extension.hello());
        reply.insert("workerBootId".into(), self.boot.clone().into());
        reply.insert("maxFrameBytes".into(), MAX_FRAME.into());
        reply.insert("leaseSeconds".into(), self.lease_seconds.into());
        reply.insert("renewAfterSeconds".into(), self.renew_after_seconds.into());
        reply.into()
    }
    /// One signed request, answered once and replayed for the same request ID. On the
    /// snapshot route a successful answer is the manifest of a capture the caller then streams
    /// with [`Service::stream_snapshot`].
    fn handle(&self, envelope: Value, snapshot: bool) -> Value {
        let (payload, digest) = match self.validate(&envelope, snapshot) {
            Ok(v) => v,
            Err(code) => return failed(code),
        };
        let request = payload["requestId"].as_str().unwrap();
        let expires = payload["expiresAt"].as_u64().unwrap();
        {
            let mut state = self.state.lock().unwrap();
            if let Some(previous) = state.requests.get(request) {
                if previous.digest != digest {
                    return json!({"ok": false, "code": "REPLAY_CONFLICT", "requestId": request});
                }
                return previous.answer.clone().unwrap_or_else(
                    || json!({"ok": false, "code": "REQUEST_IN_PROGRESS", "requestId": request}),
                );
            }
            if state.requests.len() >= self.max_requests {
                return json!({"ok": false, "code": "REQUEST_CAPACITY", "requestId": request});
            }
            state.requests.insert(
                request.into(),
                Replay {
                    digest,
                    lease: payload["leaseId"].as_str().unwrap().into(),
                    answer: None,
                    expires,
                },
            );
        }
        let mut answer = self.dispatch(&payload).unwrap_or_else(failed);
        self.finish_cancelled(payload["leaseId"].as_str().unwrap());
        answer["requestId"] = request.into();
        self.state
            .lock()
            .unwrap()
            .requests
            .get_mut(request)
            .unwrap()
            .answer = Some(answer.clone());
        answer
    }
    fn dispatch(&self, payload: &Value) -> Result<Value, &'static str> {
        match payload["operation"].as_str().unwrap() {
            "RENEW" => self.renew(payload),
            "CLOSE" => self.close_lease(payload),
            "REVOKE" => self.revoke(payload),
            "EXEC" => self.execute(payload),
            "ARTIFACT_CREATE" | "ARTIFACT_READ" | "ARTIFACT_REMOVE" => self.artifact(payload),
            "SNAPSHOT" | "MATERIALIZE_BEGIN" | "MATERIALIZE_CHUNK" | "MATERIALIZE_COMMIT"
            | "MATERIALIZE_ABORT" => self.file_operation(payload),
            "BRIDGE_POLL" | "BRIDGE_COMPLETE" => self.bridge_request(payload),
            _ => self.extension.handle(self, payload),
        }
    }
    pub fn authorize(state: &State, payload: &Value) -> Result<(), &'static str> {
        let workspace = payload["workspaceId"].as_str().unwrap().to_owned();
        let clock = now();
        if state
            .revoked_keys
            .get(&(
                workspace.clone(),
                payload["principalId"].as_str().unwrap().into(),
            ))
            .is_some_and(|until| *until > clock)
            || state
                .revoked_accounts
                .get(&(workspace, payload["accountId"].as_str().unwrap().into()))
                .is_some_and(|until| *until > clock)
        {
            Err("AUTH_REVOKED")
        } else {
            Ok(())
        }
    }
    pub fn live<'a>(
        state: &'a mut State,
        payload: &Value,
    ) -> Result<&'a mut Session, &'static str> {
        Self::authorize(state, payload)?;
        let session = state
            .sessions
            .get_mut(payload["leaseId"].as_str().unwrap())
            .ok_or("SESSION_NOT_FOUND")?;
        if !IDENTITY
            .iter()
            .zip(&session.identity)
            .all(|(name, value)| payload[*name] == *value)
        {
            return Err("SESSION_NOT_FOUND");
        }
        if session.cancelled.load(Ordering::SeqCst) || session.deadline <= now() {
            Self::mark_cancelled(session, "lease_expired");
            return Err("LEASE_EXPIRED");
        }
        Ok(session)
    }
    pub fn response(session: &Session, lease: &Value) -> Value {
        json!({"ok":true,"leaseId":lease,"state":session.status,"commit":session.commit,"gitCommit":session.git_commit})
    }
    pub fn mark_cancelled(session: &mut Session, reason: &str) {
        if session.status == "CLOSED" {
            return;
        }
        if !session.cancelled.swap(true, Ordering::SeqCst) {
            session.reason = reason.into();
        }
        session.status = "CLOSING";
    }
    fn file_operation(&self, payload: &Value) -> Result<Value, &'static str> {
        let operation = payload["operation"].as_str().unwrap();
        let data = &payload["data"];
        let fields: &[&str] = match operation {
            "SNAPSHOT" => &["executionId", "kind", "writes", "deletes"],
            "MATERIALIZE_BEGIN" => &[
                "executionId",
                "path",
                "bytes",
                "sha256",
                "expectedSha256",
                "delete",
                "allowIdentical",
            ],
            "MATERIALIZE_CHUNK" => &["executionId", "transferId", "offset", "data"],
            _ => &["executionId", "transferId"],
        };
        let capture = operation == "SNAPSHOT";
        self.execution_files(
            payload,
            fields,
            capture,
            |files, cancelled| {
                Ok(match operation {
                    "SNAPSHOT" => {
                        // `text` captures listed UTF-8 files and reports listed deletions;
                        // `optional` and `binary` capture one path, which `optional` may lack.
                        let single = || -> Result<Value, &'static str> {
                            match (data["writes"].as_array(), data["deletes"].as_array()) {
                                (Some(writes), Some(deletes))
                                    if writes.len() == 1 && deletes.is_empty() =>
                                {
                                    Ok(json!({"path": writes[0]}))
                                }
                                _ => Err("INVALID_REQUEST"),
                            }
                        };
                        let (kind, request) = match data["kind"].as_str() {
                            Some("text") => ("CAPTURE_BEGIN", data.clone()),
                            Some("optional") => ("CAPTURE_OPTIONAL", single()?),
                            Some("binary") => ("CAPTURE_BINARY", single()?),
                            _ => return Err("INVALID_REQUEST"),
                        };
                        if files.capture.is_some() {
                            return Err("CAPTURE_IN_PROGRESS");
                        }
                        files.begin_capture(kind, &request, cancelled)
                    }
                    "MATERIALIZE_BEGIN" => {
                        if files.incoming.is_some() || files.staged.is_some() {
                            return Err("MATERIALIZE_IN_PROGRESS");
                        }
                        let root = files.mount().to_owned();
                        transfer::Incoming::begin(&root, self.extension.tree(), data).map(|incoming| {
                            let result = json!({"transferId":incoming.id});
                            files.incoming = Some(incoming);
                            result
                        })
                    }
                    _ => {
                        if !files
                            .incoming
                            .as_ref()
                            .is_some_and(|v| data["transferId"] == v.id)
                        {
                            return Err("MATERIALIZE_NOT_FOUND");
                        }
                        let id = files.incoming.as_ref().unwrap().id.clone();
                        if operation == "MATERIALIZE_CHUNK" {
                            files.incoming.as_mut().unwrap().append(data).map(|_| json!({"transferId":id,"receivedBytes":files.incoming.as_ref().unwrap().received}))
                        } else if operation == "MATERIALIZE_COMMIT" {
                            files
                                .install(cancelled)
                                .map(|installed| json!({"transferId":id,"installed":installed}))
                        } else {
                            let closed = files
                                .incoming
                                .as_mut()
                                .unwrap()
                                .close()
                                .map_err(transfer::Error::from);
                            if closed.is_ok() {
                                files.incoming = None;
                            }
                            closed.map(|_| json!({"transferId":id}))
                        }
                    }
                })
            },
            |error| {
                if capture {
                    Ok(json!({"ok":false,"code":"CAPTURE_REJECTED","reason":error.reason}))
                } else {
                    Err(if error.capacity {
                        "MATERIALIZE_CAPACITY"
                    } else {
                        "MATERIALIZE_REJECTED"
                    })
                }
            },
        )
    }
    /// Run a file operation of the lease's current execution while holding its file state.
    /// `action` may refuse with a code before touching files; a fatal transfer error cancels
    /// the lease and answers `MOVE_INSTALL_FAILED`; `reject` maps any other transfer error.
    /// Only captures may address the READY lease's live unit outside an execution.
    pub fn execution_files(
        &self,
        payload: &Value,
        fields: &[&str],
        capture: bool,
        action: impl FnOnce(
            &mut files::Files,
            &Arc<AtomicBool>,
        ) -> Result<Result<Value, transfer::Error>, &'static str>,
        reject: impl FnOnce(transfer::Error) -> Result<Value, &'static str>,
    ) -> Result<Value, &'static str> {
        let data = &payload["data"];
        let object = data.as_object().unwrap();
        if object.len() != fields.len() || !fields.iter().all(|k| object.contains_key(*k)) {
            return Err("INVALID_REQUEST");
        }
        let lease = payload["leaseId"].as_str().unwrap();
        let permitted = |session: &Session| {
            session.status == "RUNNING"
                && !session.execution.is_empty()
                && data["executionId"] == session.execution
                || capture
                    && session.status == "READY"
                    && data["executionId"] == ""
                    && session.live_unit
        };
        let (shared, cancelled) = {
            let mut state = self.state.lock().unwrap();
            let session = Self::live(&mut state, payload)?;
            if !permitted(session) {
                return Err("EXECUTION_MISMATCH");
            }
            (
                session.files.clone().ok_or("SESSION_NOT_READY")?,
                session.cancelled.clone(),
            )
        };
        let mut files = shared.state.lock().unwrap();
        {
            let mut state = self.state.lock().unwrap();
            let session = Self::live(&mut state, payload)?;
            if !permitted(session) {
                return Err("EXECUTION_MISMATCH");
            }
        }
        if !shared.unit_active.load(Ordering::SeqCst)
            || files.usable().is_err()
            || cancelled.load(Ordering::SeqCst)
        {
            return Err("SESSION_NOT_READY");
        }
        let result = action(&mut files, &cancelled)?;
        let fatal = result.as_ref().is_err_and(|e| e.fatal);
        if fatal {
            cancelled.store(true, Ordering::SeqCst);
        }
        drop(files);
        if cancelled.load(Ordering::SeqCst) {
            if let Some(session) = self.state.lock().unwrap().sessions.get_mut(lease) {
                if session.status != "CLOSING" {
                    session.reason = "sandbox_failed".into();
                }
                Self::mark_cancelled(session, "sandbox_failed");
            }
            self.finish_cancelled(lease);
        }
        if fatal {
            return Err("MOVE_INSTALL_FAILED");
        }
        let mut state = self.state.lock().unwrap();
        let session = Self::live(&mut state, payload)?;
        if data["executionId"] != session.execution {
            return Err("EXECUTION_MISMATCH");
        }
        match result {
            Ok(value) => {
                let mut response = Self::response(session, &payload["leaseId"]);
                response
                    .as_object_mut()
                    .unwrap()
                    .extend(value.as_object().unwrap().clone());
                Ok(response)
            }
            Err(error) => reject(error),
        }
    }
    fn artifact(&self, payload: &Value) -> Result<Value, &'static str> {
        let operation = payload["operation"].as_str().unwrap();
        let data = &payload["data"];
        let fields: &[&str] = match operation {
            "ARTIFACT_CREATE" => &["executionId", "path", "mediaType"],
            "ARTIFACT_READ" => &["artifactId", "offset", "limit"],
            _ => &["artifactId"],
        };
        let object = data.as_object().unwrap();
        if object.len() != fields.len() || !fields.iter().all(|k| object.contains_key(*k)) {
            return Err("INVALID_REQUEST");
        }
        if operation != "ARTIFACT_CREATE" && !uuid(string(data, "artifactId")?) {
            return Err("INVALID_REQUEST");
        }
        let lease = payload["leaseId"].as_str().unwrap();
        let (shared, cancelled) = {
            let mut state = self.state.lock().unwrap();
            let session = Self::live(&mut state, payload)?;
            if !["READY", "RUNNING"].contains(&session.status) {
                return Err("SESSION_NOT_READY");
            }
            (
                session.files.clone().ok_or("SESSION_NOT_READY")?,
                session.cancelled.clone(),
            )
        };
        let mut files = shared.state.lock().unwrap();
        {
            let mut state = self.state.lock().unwrap();
            let session = Self::live(&mut state, payload)?;
            if !["READY", "RUNNING"].contains(&session.status) || files.artifacts.is_none() {
                return Err("SESSION_NOT_READY");
            }
            if operation == "ARTIFACT_CREATE"
                && (session.status != "RUNNING"
                    || !shared.unit_active.load(Ordering::SeqCst)
                    || data["executionId"] != session.execution)
            {
                return Err("EXECUTION_MISMATCH");
            }
        }
        let result = match operation {
            "ARTIFACT_CREATE" => files
                .create_artifact(data, &cancelled)
                .map(|artifact| json!({"artifact":artifact})),
            "ARTIFACT_READ" => files.artifacts.as_mut().unwrap().read(
                data["artifactId"].as_str().unwrap(),
                &data["offset"],
                &data["limit"],
            ),
            _ => files
                .artifacts
                .as_mut()
                .unwrap()
                .remove(data["artifactId"].as_str().unwrap())
                .map(|_| json!({"removed":true}))
                .map_err(|_| "ARTIFACT_UNAVAILABLE"),
        };
        drop(files);
        if cancelled.load(Ordering::SeqCst) {
            if let Some(session) = self.state.lock().unwrap().sessions.get_mut(lease) {
                if session.status != "CLOSING" {
                    session.reason = "sandbox_failed".into();
                }
                Self::mark_cancelled(session, "sandbox_failed");
            }
            self.finish_cancelled(lease);
        }
        let mut state = self.state.lock().unwrap();
        let session = Self::live(&mut state, payload)?;
        let value = result?;
        let mut response = Self::response(session, &payload["leaseId"]);
        response
            .as_object_mut()
            .unwrap()
            .extend(value.as_object().unwrap().clone());
        Ok(response)
    }
    pub fn reserve_initialization(&self, lease: &str, budget: Duration) -> io::Result<()> {
        let deadline = std::time::Instant::now() + budget;
        loop {
            match self.reserve_start(lease, deadline.min(Instant::now() + Duration::from_secs(2))) {
                Ok(()) => return Ok(()),
                Err(error)
                    if error.kind() == io::ErrorKind::WouldBlock
                        && std::time::Instant::now() < deadline =>
                {
                    thread::sleep(Duration::from_millis(20))
                }
                Err(error) => return Err(error),
            }
        }
    }
    fn execute(&self, payload: &Value) -> Result<Value, &'static str> {
        let data = payload["data"].as_object().unwrap();
        if data.len() != 4
            || !["executionId", "commit", "command", "timeoutMillis"]
                .iter()
                .all(|name| data.contains_key(*name))
        {
            return Err("INVALID_REQUEST");
        }
        let execution = string(&payload["data"], "executionId")?;
        if !uuid(execution) {
            return Err("INVALID_REQUEST");
        }
        let room_deadline = self.await_unit_stop(payload)?;
        let lease = payload["leaseId"].as_str().unwrap();
        let (runtime, cancelled) = {
            let mut state = self.state.lock().unwrap();
            let session = Self::live(&mut state, payload)?;
            if session.executions.contains(execution) {
                return Err("EXECUTION_ALREADY_STARTED");
            }
            if data["commit"] != session.commit {
                return Err("SESSION_COMMIT_MISMATCH");
            }
            let command = string(&payload["data"], "command")?;
            if command.is_empty() || command.len() > 65536 || command.contains('\0') {
                return Err("INVALID_REQUEST");
            }
            if !data["timeoutMillis"]
                .as_u64()
                .is_some_and(|v| v > 0 && v <= self.max_timeout_millis)
            {
                return Err("INVALID_REQUEST");
            }
            if session.status != "READY" {
                return Err("SESSION_BUSY");
            }
            if session.executions.len() >= self.max_executions_per_session {
                return Err("EXECUTION_CAPACITY");
            }
            if self.fenced.load(Ordering::SeqCst) {
                return Err("EXECUTOR_FAILED");
            }
            let runtime = session.runtime.clone().ok_or("SESSION_NOT_READY")?;
            session.executions.insert(execution.into());
            session.execution = execution.into();
            session.status = "RUNNING";
            (runtime, session.cancelled.clone())
        };
        let (result, refused, reusable) = {
            let mut backend = runtime.lock().unwrap();
            let result = backend.execute(
                runtime::Request {
                    execution,
                    command: data["command"].as_str().unwrap(),
                    timeout: Duration::from_millis(data["timeoutMillis"].as_u64().unwrap()),
                },
                &cancelled,
                || self.state.lock().unwrap().sessions[lease].reason.clone(),
                || self.reserve_start(lease, room_deadline),
                || {
                    self.state.lock().unwrap().starting.remove(lease);
                },
            );
            let mut state = self.state.lock().unwrap();
            if result.is_ok() || !backend.has_unit() {
                state.starting.remove(lease);
            }
            let session = state.sessions.get_mut(lease).unwrap();
            session.live_unit = backend.has_unit();
            let refused = result
                .as_ref()
                .is_err_and(|error| error.kind() == io::ErrorKind::WouldBlock);
            let mut reusable = false;
            if refused {
                session.executions.remove(execution);
                if session.deadline <= now() {
                    Self::mark_cancelled(session, "lease_expired");
                }
                if !cancelled.load(Ordering::SeqCst) {
                    session.status = "READY";
                    session.execution.clear();
                    reusable = true;
                }
            }
            if result.is_err()
                && !refused
                && !(cancelled.load(Ordering::SeqCst)
                    && result
                        .as_ref()
                        .is_err_and(|e| e.kind() == io::ErrorKind::Interrupted))
            {
                self.fenced.store(true, Ordering::SeqCst);
                if session.status != "CLOSING" {
                    session.reason = "sandbox_failed".into();
                }
                Self::mark_cancelled(session, "sandbox_failed");
            }
            if cancelled.load(Ordering::SeqCst) {
                if session.status == "RUNNING"
                    && let Ok(value) = &result
                    && let Some(reason @ ("resource_limit" | "sandbox_failed")) =
                        value["terminationReason"].as_str()
                {
                    session.reason = reason.into();
                }
                Self::mark_cancelled(session, "sandbox_failed");
            } else {
                session.status = "READY";
                session.execution.clear();
                if !refused {
                    session.idle_since = Instant::now();
                }
            }
            (result, refused, reusable)
        };
        self.finish_cancelled(lease);
        if refused {
            let mut response = json!({"ok":false,"code":"EXECUTION_CAPACITY"});
            if reusable {
                response["reason"] = "STARTUP_LIMIT".into();
            }
            return Ok(response);
        }
        let mut result = result.map_err(|error| {
            if error.kind() == io::ErrorKind::Interrupted && cancelled.load(Ordering::SeqCst) {
                "SESSION_CLOSED"
            } else {
                "EXECUTOR_FAILED"
            }
        })?;
        let state = self.state.lock().unwrap();
        let session = &state.sessions[lease];
        if session.cancelled.load(Ordering::SeqCst) {
            if let Some(handles) = result["artifacts"].as_object() {
                let names = handles.keys().cloned().collect::<Vec<_>>();
                for name in names {
                    result["artifactErrors"][name] = "ARTIFACT_UNAVAILABLE".into();
                }
            }
            result["artifacts"] = json!({});
        }
        result["commit"] = session.commit.clone().into();
        let mut answer = Self::response(session, &payload["leaseId"]);
        answer["result"] = result;
        Ok(answer)
    }
    /// Write the capture a snapshot answer names as one archive and release it, whether or not
    /// the caller read it all. `head` receives the archive length before any byte is written.
    /// `Ok(false)` means the capture no longer exists, as for a replayed snapshot request.
    fn stream_snapshot(
        &self,
        answer: &Value,
        head: impl FnOnce(u64) -> io::Result<()>,
        out: &mut impl Write,
    ) -> io::Result<bool> {
        let files = {
            let state = self.state.lock().unwrap();
            let Some(files) = answer["leaseId"]
                .as_str()
                .and_then(|lease| state.sessions.get(lease))
                .and_then(|session| session.files.clone())
            else {
                return Ok(false);
            };
            files
        };
        let mut files = files.state.lock().unwrap();
        if !files
            .capture
            .as_ref()
            .is_some_and(|capture| answer["captureId"] == capture.id)
        {
            return Ok(false);
        }
        let manifest = serde_json::to_vec(answer).map_err(io::Error::other)?;
        let capture = files.capture.as_mut().unwrap();
        let written =
            head(capture.tar_length(&manifest)).and_then(|()| capture.write_tar(&manifest, out));
        capture.close()?;
        files.capture = None;
        written.map(|()| true)
    }
    pub fn finish_cancelled(&self, lease: &str) {
        let (bridge, runtime) = {
            let state = self.state.lock().unwrap();
            let Some(session) = state.sessions.get(lease) else {
                return;
            };
            if !session.cancelled.load(Ordering::SeqCst)
                || session.status == "CLOSED"
                || session.job
            {
                return;
            }
            (session.bridge.clone(), session.runtime.clone())
        };
        if bridge.is_some_and(|bridge| bridge.close().is_err()) {
            self.fenced.store(true, Ordering::SeqCst);
            return;
        }
        if let Some(runtime) = runtime {
            let Ok(mut backend) = runtime.try_lock() else {
                return;
            };
            if backend.close().is_err() {
                self.fenced.store(true, Ordering::SeqCst);
                return;
            }
        }
        let mut state = self.state.lock().unwrap();
        state.starting.remove(lease);
        if let Some(session) = state.sessions.get_mut(lease) {
            session.status = "CLOSED";
            session.live_unit = false;
        }
    }
    fn renew(&self, payload: &Value) -> Result<Value, &'static str> {
        let mut state = self.state.lock().unwrap();
        let session = Self::live(&mut state, payload)?;
        if !payload["data"].as_object().unwrap().is_empty() {
            return Err("INVALID_REQUEST");
        }
        session.deadline = session
            .deadline
            .max(payload["expiresAt"].as_u64().unwrap() as f64);
        Ok(Self::response(session, &payload["leaseId"]))
    }
    fn close_lease(&self, payload: &Value) -> Result<Value, &'static str> {
        let data = payload["data"].as_object().unwrap();
        let reason = match data.get("reason") {
            None => "session_closed",
            Some(value) => value.as_str().ok_or("INVALID_REQUEST")?,
        };
        if data.keys().any(|key| key != "reason")
            || !["cancelled", "session_closed", "client_shutdown"].contains(&reason)
        {
            return Err("INVALID_REQUEST");
        }
        let lease = payload["leaseId"].as_str().unwrap();
        {
            let mut state = self.state.lock().unwrap();
            if state.sessions.get(lease).is_some_and(|s| {
                !IDENTITY
                    .iter()
                    .zip(&s.identity)
                    .all(|(key, value)| payload[*key] == *value)
            }) {
                return Err("SESSION_NOT_FOUND");
            }
            state
                .closed_leases
                .insert(lease.into(), now() + self.lease_seconds as f64 + 2.0);
            let Some(session) = state.sessions.get_mut(lease) else {
                return Ok(json!({"ok":true,"leaseId":lease,"state":"CLOSED","commit":null}));
            };
            if !IDENTITY
                .iter()
                .zip(&session.identity)
                .all(|(key, value)| payload[*key] == *value)
            {
                return Err("SESSION_NOT_FOUND");
            }
            Self::mark_cancelled(session, reason);
        }
        self.finish_cancelled(lease);
        let state = self.state.lock().unwrap();
        Ok(Self::response(&state.sessions[lease], &payload["leaseId"]))
    }
    fn revoke(&self, payload: &Value) -> Result<Value, &'static str> {
        let data = payload["data"].as_object().unwrap();
        if data.len() != 2 || !data.contains_key("keyIds") || !data.contains_key("accountIds") {
            return Err("INVALID_REQUEST");
        }
        let identities = |name: &str| -> Result<HashSet<String>, &'static str> {
            let values = data[name]
                .as_array()
                .filter(|v| v.len() <= 1000)
                .ok_or("INVALID_REQUEST")?;
            values
                .iter()
                .map(|value| {
                    value
                        .as_str()
                        .filter(|v| uuid(v))
                        .map(str::to_owned)
                        .ok_or("INVALID_REQUEST")
                })
                .collect()
        };
        let keys = identities("keyIds")?;
        let accounts = identities("accountIds")?;
        let workspace = payload["workspaceId"].as_str().unwrap();
        let closing = {
            let mut state = self.state.lock().unwrap();
            let until = now() + self.lease_seconds as f64 + 2.0;
            for key in &keys {
                state
                    .revoked_keys
                    .insert((workspace.into(), key.clone()), until);
            }
            for account in &accounts {
                state
                    .revoked_accounts
                    .insert((workspace.into(), account.clone()), until);
            }
            state
                .sessions
                .iter_mut()
                .filter_map(|(lease, session)| {
                    if session.identity[2] == workspace
                        && (keys.contains(&session.identity[0])
                            || accounts.contains(&session.identity[1]))
                    {
                        Self::mark_cancelled(session, "revoked");
                        Some(lease.clone())
                    } else {
                        None
                    }
                })
                .collect::<Vec<_>>()
        };
        for lease in &closing {
            self.finish_cancelled(lease);
        }
        let state = self.state.lock().unwrap();
        let pending = closing.iter().any(|lease| {
            state
                .sessions
                .get(lease)
                .is_some_and(|s| s.status != "CLOSED")
        });
        Ok(
            json!({"ok":true,"state":if pending {"CLOSING"} else {"CLOSED"},"closedCount":closing.len()}),
        )
    }
    fn bridge_request(&self, payload: &Value) -> Result<Value, &'static str> {
        let polling = payload["operation"] == "BRIDGE_POLL";
        let data = payload["data"].as_object().unwrap();
        if polling
            && (data.len() != 1
                || !data
                    .get("executionId")
                    .and_then(Value::as_str)
                    .is_some_and(uuid))
        {
            return Err("INVALID_REQUEST");
        }
        let deadline = std::time::Instant::now() + Duration::from_secs(1);
        if polling {
            while std::time::Instant::now() < deadline {
                let waiting = {
                    let state = self.state.lock().unwrap();
                    state
                        .sessions
                        .get(payload["leaseId"].as_str().unwrap())
                        .is_some_and(|s| {
                            ["READY", "UPDATING"].contains(&s.status)
                                && IDENTITY
                                    .iter()
                                    .zip(&s.identity)
                                    .all(|(key, value)| payload[*key] == *value)
                        })
                };
                if !waiting {
                    break;
                }
                thread::sleep(Duration::from_millis(20));
            }
        }
        let (bridge, execution) = {
            let mut state = self.state.lock().unwrap();
            let session = Self::live(&mut state, payload)?;
            let bridge = session.bridge.clone().ok_or("SESSION_NOT_READY")?;
            let stopping = polling && session.status == "UPDATING" && session.execution.is_empty();
            if !["READY", "RUNNING"].contains(&session.status) && !stopping {
                return Err("SESSION_NOT_READY");
            }
            if polling && (session.status != "RUNNING" || data["executionId"] != session.execution)
            {
                let mut reply = Self::response(session, &payload["leaseId"]);
                reply["executionId"] = data["executionId"].clone();
                reply["bridgeRequest"] = Value::Null;
                return Ok(reply);
            }
            if !polling {
                if data.len() != 3
                    || !data.contains_key("bridgeRequestId")
                    || !data.contains_key("executionId")
                    || !data.get("response").is_some_and(Value::is_object)
                {
                    return Err("INVALID_REQUEST");
                }
                if !data["bridgeRequestId"].as_str().is_some_and(uuid) {
                    return Err("INVALID_REQUEST");
                }
                if session.execution.is_empty() || data["executionId"] != session.execution {
                    return Err("EXECUTION_MISMATCH");
                }
            }
            (bridge, session.execution.clone())
        };
        let request = if polling {
            loop {
                let answer = bridge.poll(
                    deadline.saturating_duration_since(Instant::now()),
                    &execution,
                );
                if !matches!(&answer, Ok(None)) || Instant::now() >= deadline {
                    break answer;
                }
                // A command can be RUNNING before its mailbox reset; reuse this poll's budget.
                thread::sleep(
                    Duration::from_millis(20)
                        .min(deadline.saturating_duration_since(Instant::now())),
                );
            }
        } else {
            bridge
                .complete(data["bridgeRequestId"].as_str().unwrap(), &data["response"])
                .map(|_| None)
        };
        let request = match request {
            Ok(value) => value,
            Err(_) => {
                let _=self.close_lease(&json!({"data":{},"leaseId":payload["leaseId"],"principalId":payload["principalId"],"accountId":payload["accountId"],"workspaceId":payload["workspaceId"],"serverSessionHash":payload["serverSessionHash"],"appBootId":payload["appBootId"]}));
                return Err("BRIDGE_UNAVAILABLE");
            }
        };
        let mut state = self.state.lock().unwrap();
        let session = Self::live(&mut state, payload)?;
        let mut reply = Self::response(session, &payload["leaseId"]);
        reply["bridgeRequest"] = if session.execution == execution {
            request.unwrap_or(Value::Null)
        } else {
            Value::Null
        };
        reply["executionId"] = execution.into();
        Ok(reply)
    }
    fn sweep(&self) {
        let clock = now();
        let closing = {
            let mut state = self.state.lock().unwrap();
            state
                .requests
                .retain(|_, v| v.answer.is_none() || v.expires as f64 + 2.0 > clock);
            state.revoked_keys.retain(|_, until| *until > clock);
            state.revoked_accounts.retain(|_, until| *until > clock);
            state.closed_leases.retain(|_, until| *until > clock);
            for session in state.sessions.values_mut() {
                if session.deadline <= clock {
                    Self::mark_cancelled(session, "lease_expired");
                }
            }
            state
                .sessions
                .iter()
                .filter(|(_, s)| s.cancelled.load(Ordering::SeqCst) && s.status != "CLOSED")
                .map(|(lease, _)| lease.clone())
                .collect::<Vec<_>>()
        };
        for lease in closing {
            self.finish_cancelled(&lease);
        }
        self.sweep_idle();
        let stores = self
            .state
            .lock()
            .unwrap()
            .sessions
            .iter()
            .filter_map(|(lease, session)| {
                session.files.clone().map(|files| (lease.clone(), files))
            })
            .collect::<Vec<_>>();
        let mut failed = Vec::new();
        for (lease, shared) in stores {
            if let Ok(mut files) = shared.state.try_lock()
                && files
                    .artifacts
                    .as_mut()
                    .is_some_and(|store| store.reap().is_err())
            {
                failed.push(lease);
            }
        }
        {
            let mut state = self.state.lock().unwrap();
            for lease in &failed {
                if let Some(session) = state.sessions.get_mut(lease) {
                    Self::mark_cancelled(session, "sandbox_failed");
                }
            }
        }
        for lease in failed {
            self.finish_cancelled(&lease);
        }
        let mut state = self.state.lock().unwrap();
        let pending: HashSet<String> = state
            .requests
            .values()
            .filter(|request| request.answer.is_none())
            .map(|request| request.lease.clone())
            .collect();
        // Handlers still read the lease after containment, until their replay result is published.
        state.sessions.retain(|lease, s| {
            s.status != "CLOSED" || s.deadline + 2.0 > clock || pending.contains(lease)
        });
    }
    fn shutdown(&self) -> io::Result<()> {
        self.shutdown_started.store(true, Ordering::SeqCst);
        let closing = {
            let mut state = self.state.lock().unwrap();
            for session in state.sessions.values_mut() {
                Self::mark_cancelled(session, "client_shutdown");
            }
            state.sessions.keys().cloned().collect::<Vec<_>>()
        };
        let deadline = std::time::Instant::now() + Duration::from_secs(25);
        loop {
            for lease in &closing {
                self.finish_cancelled(lease);
            }
            if self
                .state
                .lock()
                .unwrap()
                .sessions
                .values()
                .all(|s| s.status == "CLOSED")
            {
                return Ok(());
            }
            if std::time::Instant::now() >= deadline {
                return Err(io::Error::other("Containment not confirmed"));
            }
            thread::sleep(Duration::from_millis(20));
        }
    }
}

/// Fixture statistics and keys; absent from installed binaries.
#[cfg(feature = "fixture")]
impl Service {
    pub fn replay_entries(&self) -> usize {
        self.state.lock().unwrap().requests.len()
    }
}
#[cfg(feature = "fixture")]
pub fn fixture_key(text: &str) -> Result<VerifyingKey, Box<dyn std::error::Error>> {
    let key: [u8; 32] = ENCODING.decode(text)?.try_into().map_err(|_| "key size")?;
    Ok(VerifyingKey::from_bytes(&key)?)
}
pub fn emit(value: Value) {
    println!("{value}");
    io::stdout().flush().unwrap();
}

/// Generated fixtures run in a separate verification binary, never through the installed entry.
#[cfg(test)]
mod tests {
    use super::*;
    /// An extension that adds no operation.
    #[cfg(feature = "fixture")]
    struct Bare;
    #[cfg(feature = "fixture")]
    impl Extension for Bare {
        fn operations(&self) -> &'static [&'static str] {
            &[]
        }
        fn hello(&self) -> serde_json::Map<String, Value> {
            serde_json::Map::new()
        }
        fn tree(&self) -> transfer::Tree {
            transfer::Tree {
                directories: &["work"],
                reserved: ".git",
            }
        }
        fn bridge_operations(&self) -> &'static [&'static str] {
            &[]
        }
        fn handle(&self, _: &Service, _: &Value) -> Result<Value, &'static str> {
            Err("INVALID_REQUEST")
        }
    }
    #[cfg(feature = "fixture")]
    #[test]
    fn a_pending_close_keeps_only_its_lease_until_reply_publication() {
        let first = "10000000-0000-0000-0000-000000000001";
        let other = "10000000-0000-0000-0000-000000000002";
        let identity = "20000000-0000-0000-0000-000000000001";
        let request = "30000000-0000-0000-0000-000000000001";
        let row = |lease: &str| {
            json!({"leaseId":lease,"principalId":identity,"accountId":identity,
                "workspaceId":identity,"appBootId":identity,"serverSessionHash":"b".repeat(64),
                "deadline":now()-60.0})
        };
        let key = ed25519_dalek::SigningKey::from_bytes(&[7; 32]);
        let settings = serde_json::from_value(json!({
            "publicKey":ENCODING.encode(key.verifying_key().as_bytes()),
            "workerBootId":identity,"maxRequests":2,"maxConnections":1,
            "sessions":[row(first),row(other)]
        }))
        .unwrap();
        let service = Service::new(settings, key.verifying_key(), None, Box::new(Bare)).unwrap();
        let payload = |lease: &str| {
            let mut value = row(lease);
            value["data"] = json!({});
            value
        };
        service.state.lock().unwrap().requests.insert(
            request.into(),
            Replay {
                digest: [0; 32],
                lease: first.into(),
                answer: None,
                expires: (now() + 15.0) as u64,
            },
        );
        let answer = service.close_lease(&payload(first)).unwrap();
        service.close_lease(&payload(other)).unwrap();
        // Reproduce a sweep between containment and the caller publishing its result.
        service.sweep();
        {
            let state = service.state.lock().unwrap();
            assert!(state.sessions.contains_key(first));
            assert!(!state.sessions.contains_key(other));
        }
        service
            .state
            .lock()
            .unwrap()
            .requests
            .get_mut(request)
            .unwrap()
            .answer = Some(answer);
        service.sweep();
        assert!(!service.state.lock().unwrap().sessions.contains_key(first));
    }
    #[test]
    fn duplicate_keys_are_rejected_recursively() {
        for source in [
            r#"{"a":1,"a":2}"#,
            r#"{"a":{"b":1,"b":2}}"#,
            r#"[{"x":0,"x":0}]"#,
        ] {
            assert!(parse(source.as_bytes()).is_err());
        }
        assert_eq!(
            parse(br#"{"a":[null,true,1,1.25,"ok"]}"#).unwrap()["a"][3],
            1.25
        );
    }
    #[test]
    fn canonical_uuid_is_required() {
        assert!(uuid("00000000-0000-0000-0000-000000000000"));
        assert!(!uuid("AAAAAAAA-0000-0000-0000-000000000000"));
        assert!(!uuid("00000000000000000000000000000000"));
    }
    #[test]
    fn decode_matches_unpadded_url_alphabet() {
        assert_eq!(decode(&json!("YQ")).unwrap(), b"a");
        assert_eq!(decode(&json!("YR")).unwrap(), b"a");
        for value in [
            json!(""),
            json!("YQ=="),
            json!("+a"),
            json!(false),
            json!("a"),
        ] {
            assert!(decode(&value).is_err());
        }
    }
}
