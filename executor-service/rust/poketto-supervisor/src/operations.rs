//! Poketto's copy operations: open or attach a copy, install a Git baseline, discard a copy
//! and apply a host-planned move. The daemon has verified and registered each request.
use crate::{baseline, moves, provision, provision::Factory};
use sandcrab_core::{hex, transfer, uuid};
use sandcrab_daemon::{IDENTITY, Service, Session, now, runtime, string};
use serde_json::{Value, json};
use std::{
    collections::HashSet,
    io,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

pub fn open(
    service: &Service,
    factory: Option<&Factory>,
    payload: &Value,
) -> Result<Value, &'static str> {
    let lease = payload["leaseId"].as_str().unwrap();
    {
        let state = service.state.lock().unwrap();
        Service::authorize(&state, payload)?;
        if service.shutdown_started.load(Ordering::SeqCst)
            || state
                .closed_leases
                .get(lease)
                .is_some_and(|until| *until > now())
        {
            return Err("SESSION_CLOSED");
        }
    }
    let initialize = payload["operation"] == "OPEN";
    let data = payload["data"].as_object().unwrap();
    let copy = string(&payload["data"], "copyId")?;
    let scope = string(&payload["data"], "scope")?;
    let commit = string(&payload["data"], "commit")?;
    if !uuid(copy) || !["full", "public"].contains(&scope) || !hex(commit, 40) {
        return Err("INVALID_REQUEST");
    }
    let seeded = initialize && data.contains_key("seed");
    if seeded && (data.len() != 4 || data["seed"] != true) {
        return Err("INVALID_REQUEST");
    }
    let factory = factory.ok_or("SESSION_NOT_READY")?;
    let source = if seeded {
        Some(provision::Source::Seed)
    } else if initialize {
        if data.len() != 6
            || ![
                "copyId",
                "scope",
                "commit",
                "exportId",
                "bundleSha256",
                "bundleBytes",
            ]
            .iter()
            .all(|k| data.contains_key(*k))
        {
            return Err("INVALID_REQUEST");
        }
        let id = string(&payload["data"], "exportId")?;
        let digest = string(&payload["data"], "bundleSha256")?;
        let bytes = data["bundleBytes"]
            .as_u64()
            .filter(|n| *n > 0 && *n <= factory.config.max_bundle_bytes)
            .ok_or("INVALID_REQUEST")?;
        if !uuid(id) || !hex(digest, 64) {
            return Err("INVALID_REQUEST");
        }
        Some(provision::Source::Export(provision::Export {
            id: id.into(),
            bytes,
            digest: digest.into(),
        }))
    } else {
        if data.len() != 3 {
            return Err("INVALID_REQUEST");
        }
        None
    };
    let lease = payload["leaseId"].as_str().unwrap();
    let identity = IDENTITY
        .iter()
        .map(|name| payload[*name].as_str().unwrap().to_owned())
        .collect::<Vec<_>>();
    {
        let state = service.state.lock().unwrap();
        Service::authorize(&state, payload)?;
    }
    if seeded {
        let found = factory
            .pool
            .seeds()
            .and_then(|store| store.claim(&identity[2], scope, commit))
            .map_err(|_| "EXECUTOR_FAILED")?;
        if found.is_none() {
            return Err("SEED_ABSENT");
        }
    }
    let owner = factory.owner(copy, &identity[1], &identity[2], scope, commit);
    let plan = factory
        .plan(lease, owner, initialize && !seeded)
        .map_err(|_| "EXECUTOR_FAILED")?;
    let runtime = Arc::new(Mutex::new(runtime::Runtime::pending(
        plan.description.clone(),
        crate::REPOSITORY,
    )));
    let mut backend = runtime.lock().unwrap();
    let cancelled = Arc::new(AtomicBool::new(false));
    {
        let mut state = service.state.lock().unwrap();
        Service::authorize(&state, payload)?;
        if service.shutdown_started.load(Ordering::SeqCst)
            || state
                .closed_leases
                .get(lease)
                .is_some_and(|until| *until > now())
        {
            return Err("SESSION_CLOSED");
        }
        if service.fenced.load(Ordering::SeqCst) {
            return Err("EXECUTOR_FAILED");
        }
        if state.sessions.contains_key(lease)
            || state
                .sessions
                .values()
                .any(|s| s.identity == identity && s.status != "CLOSED")
        {
            return Err("SESSION_EXISTS");
        }
        if state
            .sessions
            .values()
            .filter(|s| s.status != "CLOSED")
            .count()
            >= service.max_sessions
        {
            return Err("SESSION_CAPACITY");
        }
        state.sessions.insert(
            lease.into(),
            Session {
                identity,
                deadline: payload["expiresAt"].as_u64().unwrap() as f64,
                cancelled: cancelled.clone(),
                reason: "session_closed".into(),
                commit: commit.into(),
                git_commit: commit.into(),
                scope: scope.into(),
                files: Some(backend.files()),
                executions: HashSet::new(),
                runtime: Some(runtime.clone()),
                live_unit: false,
                idle_since: Instant::now(),
                status: "INITIALIZING",
                execution: String::new(),
                bridge: None,
            },
        );
    }
    let result = provision::prepare(
        &mut backend,
        &plan,
        factory,
        source.as_ref(),
        &cancelled,
        || {
            service.reserve_initialization(
                lease,
                Duration::from_millis(factory.config.init_timeout_millis),
            )
        },
    );
    let result = result.and_then(|bridge| {
        baseline::installed(&backend)
            .map(|git| (bridge, git))
            .map_err(runtime::InitError::Io)
    });
    {
        let mut state = service.state.lock().unwrap();
        if !backend.has_unit() {
            state.starting.remove(lease);
        }
        let session = state.sessions.get_mut(lease).unwrap();
        session.live_unit = backend.has_unit();
        if let Ok((bridge, git)) = &result {
            session.bridge = Some(bridge.clone());
            if let Some(git) = git {
                session.git_commit = git.clone();
            }
            if !cancelled.load(Ordering::SeqCst) {
                session.status = "READY";
            }
        } else {
            Service::mark_cancelled(session, "sandbox_failed");
        }
    }
    drop(backend);
    service.finish_cancelled(lease);
    match result {
        Ok(_) => {
            let state = service.state.lock().unwrap();
            let session = state.sessions.get(lease).ok_or("SESSION_CLOSED")?;
            if session.cancelled.load(Ordering::SeqCst) {
                Err("SESSION_CLOSED")
            } else {
                Ok(Service::response(session, &payload["leaseId"]))
            }
        }
        Err(runtime::InitError::Code(code)) => Err(code),
        Err(runtime::InitError::Io(error)) => Err(if error.kind() == io::ErrorKind::Interrupted {
            "SESSION_CLOSED"
        } else {
            "EXECUTOR_FAILED"
        }),
    }
}
pub fn baseline(
    service: &Service,
    factory: Option<&Factory>,
    payload: &Value,
) -> Result<Value, &'static str> {
    let data = payload["data"].as_object().unwrap();
    if data.len() != 5
        || ![
            "executionId",
            "exportId",
            "bundleSha256",
            "bundleBytes",
            "commit",
        ]
        .iter()
        .all(|key| data.contains_key(*key))
    {
        return Err("INVALID_REQUEST");
    }
    let execution = string(&payload["data"], "executionId")?;
    let id = string(&payload["data"], "exportId")?;
    let digest = string(&payload["data"], "bundleSha256")?;
    let commit = string(&payload["data"], "commit")?;
    let factory = factory.ok_or("SESSION_NOT_READY")?;
    let bytes = data["bundleBytes"]
        .as_u64()
        .filter(|v| *v > 0 && *v <= factory.config.max_bundle_bytes)
        .ok_or("INVALID_REQUEST")?;
    if !(execution.is_empty() || uuid(execution))
        || !uuid(id)
        || !hex(digest, 64)
        || !hex(commit, 40)
    {
        return Err("INVALID_REQUEST");
    }
    let lease = payload["leaseId"].as_str().unwrap();
    let (runtime, shared, cancelled) = {
        let mut state = service.state.lock().unwrap();
        let session = Service::live(&mut state, payload)?;
        if session.scope != "full" {
            return Err("READ_ONLY_SCOPE");
        }
        (
            session.runtime.clone().ok_or("SESSION_NOT_READY")?,
            session.files.clone().ok_or("SESSION_NOT_READY")?,
            session.cancelled.clone(),
        )
    };
    let mut files = shared.state.try_lock().map_err(|_| "SESSION_BUSY")?;
    // A running command owns Runtime until it finishes. Its save callback must never wait on it.
    let operation = {
        let mut state = service.state.lock().unwrap();
        let session = Service::live(&mut state, payload)?;
        if session.status == "RUNNING"
            && session.execution == execution
            && shared.unit_active.load(Ordering::SeqCst)
        {
            None
        } else if session.status == "READY" && execution.is_empty() {
            let guard = runtime.try_lock().map_err(|_| "SESSION_BUSY")?;
            session.status = "UPDATING";
            Some(guard)
        } else {
            return Err("SESSION_BUSY");
        }
    };
    let result = baseline::install(
        &mut files,
        &shared,
        factory,
        &provision::Export {
            id: id.into(),
            digest: digest.into(),
            bytes,
        },
        commit,
        &cancelled,
    );
    let fenced = files.fenced;
    {
        let mut state = service.state.lock().unwrap();
        let session = state.sessions.get_mut(lease).unwrap();
        if result.is_ok() {
            session.git_commit = commit.into();
        }
        if fenced {
            service.fenced.store(true, Ordering::SeqCst);
        }
        if cancelled.load(Ordering::SeqCst) {
            Service::mark_cancelled(session, "sandbox_failed");
        } else if operation.is_some() {
            session.status = "READY";
        }
    }
    drop(files);
    drop(operation);
    service.finish_cancelled(lease);
    result?;
    let mut state = service.state.lock().unwrap();
    let session = Service::live(&mut state, payload)?;
    Ok(Service::response(session, &payload["leaseId"]))
}
pub fn discard(
    service: &Service,
    factory: Option<&Factory>,
    payload: &Value,
) -> Result<Value, &'static str> {
    let data = &payload["data"];
    let object = data.as_object().unwrap();
    if object.len() != 3
        || !["copyId", "scope", "commit"]
            .iter()
            .all(|key| object.contains_key(*key))
    {
        return Err("INVALID_REQUEST");
    }
    let copy = string(data, "copyId")?;
    let scope = string(data, "scope")?;
    let commit = string(data, "commit")?;
    if !uuid(copy) || !["full", "public"].contains(&scope) || !hex(commit, 40) {
        return Err("INVALID_REQUEST");
    }
    Service::authorize(&service.state.lock().unwrap(), payload)?;
    let factory = factory.ok_or("COPY_UNAVAILABLE")?;
    let owner = factory.owner(
        copy,
        payload["accountId"].as_str().unwrap(),
        payload["workspaceId"].as_str().unwrap(),
        scope,
        commit,
    );
    match factory.pool.discard(&owner) {
        Ok(discarded) => Ok(
            json!({"ok":true,"leaseId":payload["leaseId"],"copyId":copy,"commit":commit,"state":if discarded { "DISCARDED" } else { "ABSENT" }}),
        ),
        Err(error) if error.kind() == io::ErrorKind::WouldBlock => Err("COPY_BUSY"),
        Err(_) => Ok(json!({"ok":false,"code":"COPY_UNAVAILABLE","reason":"IDENTITY_MISMATCH"})),
    }
}

/// The move staged in the lease's file state, if any.
fn staged_move(files: &mut sandcrab_daemon::files::Files) -> Option<&mut moves::IncomingMove> {
    files
        .staged
        .as_mut()
        .and_then(|staged| staged.as_any_mut().downcast_mut())
}
/// MOVE_* requests stage a host plan, check it and install it under the frozen lease.
pub fn file_move(service: &Service, payload: &Value) -> Result<Value, &'static str> {
    let operation = payload["operation"].as_str().unwrap();
    let data = &payload["data"];
    let fields: &[&str] = match operation {
        "MOVE_BEGIN" => &["executionId", "bytes", "sha256"],
        "MOVE_CHUNK" => &["executionId", "transferId", "offset", "data"],
        _ => &["executionId", "transferId"],
    };
    service.execution_files(
        payload,
        fields,
        false,
        |files, cancelled| {
            if operation == "MOVE_BEGIN" {
                if files.incoming.is_some() || files.staged.is_some() {
                    return Err("MATERIALIZE_IN_PROGRESS");
                }
                let root = files.mount().to_owned();
                return Ok(moves::IncomingMove::begin(&root, data).map(|incoming| {
                    let result = json!({"transferId":incoming.id()});
                    files.staged = Some(Box::new(incoming));
                    result
                }));
            }
            let description = files.description().clone();
            let Some(incoming) = staged_move(files).filter(|v| data["transferId"] == v.id()) else {
                return Err("MOVE_NOT_FOUND");
            };
            let id = incoming.id().to_owned();
            Ok(match operation {
                "MOVE_CHUNK" => incoming
                    .append(data)
                    .map(|_| json!({"transferId":id,"receivedBytes":incoming.received()})),
                "MOVE_CHECK" => {
                    sandcrab_daemon::files::quiescent(&description.cgroup, cancelled, || {
                        incoming.check(cancelled)
                    })
                    .map(|checked| json!({"transferId":id,"checked":checked}))
                }
                "MOVE_COMMIT" => {
                    sandcrab_daemon::files::quiescent(&description.cgroup, cancelled, || {
                        incoming.install(description.exec_uid, description.exec_gid, cancelled)
                    })
                    .map(|installed| json!({"transferId":id,"installed":installed}))
                }
                _ => {
                    let closed = incoming.close().map_err(transfer::Error::from);
                    if closed.is_ok() {
                        files.staged = None;
                    }
                    closed.map(|_| json!({"transferId":id}))
                }
            })
        },
        |_| Err("MOVE_REJECTED"),
    )
}
