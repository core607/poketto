#![forbid(unsafe_code)]
//! Poketto's repository supervisor: the Sandcrab daemon with Poketto's copy operations.
//! Copies are XFS project volumes provisioned from Git bundle exports or seeds; Git
//! baselines and host-planned moves change them under signed leases.

mod baseline;
mod lease;
mod moves;
mod operations;
mod pool;
mod production;
mod provision;
mod seed;

use sandcrab_core::{hex, new_uuid, parse, transfer, uuid};
use sandcrab_daemon::{Extension, Service};
use serde_json::{Value, json};

/// Captures, installs, artifacts and moves address the copy's Git work tree and never its metadata.
const REPOSITORY: transfer::Tree = transfer::Tree {
    directories: &["work", "repository"],
    reserved: ".git",
};
/// Bridge requests the sandbox CLI may send; the application authorizes each one.
const BRIDGE_OPERATIONS: &[&str] = &[
    "status",
    "edit",
    "create",
    "save",
    "recover",
    "sync",
    "media_fetch",
    "media_import",
    "media_link",
    "media_list",
    "artifact_create",
    "artifact_remove",
    "move",
    "export",
];
const OPERATIONS: &[&str] = &[
    "OPEN",
    "ATTACH",
    "BASELINE",
    "DISCARD",
    "MOVE_BEGIN",
    "MOVE_CHUNK",
    "MOVE_CHECK",
    "MOVE_COMMIT",
    "MOVE_ABORT",
];

/// Copy operations; without a factory, fixtures serve leases but cannot provision copies.
struct Repository {
    factory: Option<provision::Factory>,
}
impl Extension for Repository {
    fn operations(&self) -> &'static [&'static str] {
        OPERATIONS
    }
    fn hello(&self) -> serde_json::Map<String, Value> {
        let disk = if self.factory.is_some() { 1 } else { 0 };
        let Value::Object(fields) = json!({"codeActProtocol":2,"artifactProtocol":1,"moveProtocol":1,
            "exportProtocol":1,"diskCopyProtocol":disk,"gitBaselineProtocol":1,"workspaceSyncProtocol":1,
            "leaseSandboxProtocol":1,"seedProtocol":1})
        else {
            unreachable!()
        };
        fields
    }
    fn tree(&self) -> transfer::Tree {
        REPOSITORY
    }
    fn bridge_operations(&self) -> &'static [&'static str] {
        BRIDGE_OPERATIONS
    }
    fn handle(&self, service: &Service, payload: &Value) -> Result<Value, &'static str> {
        let factory = self.factory.as_ref();
        match payload["operation"].as_str().unwrap() {
            "OPEN" | "ATTACH" => operations::open(service, factory, payload),
            "BASELINE" => operations::baseline(service, factory, payload),
            "DISCARD" => operations::discard(service, factory, payload),
            _ => operations::file_move(service, payload),
        }
    }
}

/// Run the installed root service or its independent cleanup entry.
pub fn run() -> Result<(), Box<dyn std::error::Error>> {
    production::run(&std::env::args().skip(1).collect::<Vec<_>>())
}

/// Generated fixtures run in a separate verification binary, never through the installed entry.
#[cfg(feature = "fixture")]
pub fn fixture_main() -> Result<(), Box<dyn std::error::Error>> {
    use sandcrab_daemon::{MAX_FRAME, Settings, cpu, emit, server};
    use std::{
        io::{self, BufRead},
        path::PathBuf,
        sync::{Arc, atomic::Ordering},
        thread,
        time::Duration,
    };
    let args: Vec<_> = std::env::args().collect();
    assert!(args.len() == 3 && ["threads", "pool"].contains(&args[2].as_str()));
    let socket = PathBuf::from(&args[1]);
    assert!(!socket.exists());
    let mut input = io::stdin().lock().lines();
    let line = input.next().ok_or("fixture missing")??;
    assert!(line.len() <= MAX_FRAME);
    let mut value = parse(line.as_bytes())?;
    let object = value.as_object_mut().ok_or("fixture object")?;
    if object.remove("fixtureOnly") != Some(Value::Bool(true)) {
        return Err("Explicit fixture mode required".into());
    }
    let backend = object
        .remove("backend")
        .map(serde_json::from_value::<provision::Config>)
        .transpose()?;
    let fixture: Settings = serde_json::from_value(value)?;
    assert!((1..=256).contains(&fixture.max_connections));
    let key = sandcrab_daemon::fixture_key(&fixture.public_key)?;
    let root_lock = fixture
        .runtime_root
        .as_ref()
        .map(|path| sandcrab_daemon::install::runtime_lock(path))
        .transpose()?;
    if let Some(config) = &backend
        && fixture.runtime_root.as_ref() != Some(&config.runtime_root)
    {
        return Err("Backend runtime root mismatch".into());
    }
    let factory = backend.map(provision::Factory::new).transpose()?;
    let maximum = fixture.max_connections;
    let service = Arc::new(Service::new(
        fixture,
        key,
        root_lock,
        Box::new(Repository { factory }),
    )?);
    let server = server::Server::start(
        socket,
        service.clone(),
        maximum,
        args[2] == "pool",
        rustix::process::getuid(),
        None,
    )?;
    let active = server.active.clone();
    emit(json!({"ready": true, "fixtureOnly": true}));
    let mut started = None;
    for command in input {
        match command?.as_str() {
            "start" => {
                started = Some(cpu());
                emit(json!({"started": true}));
            }
            "stats" => {
                for _ in 0..100 {
                    if active.load(Ordering::SeqCst) == 0 {
                        break;
                    }
                    thread::sleep(Duration::from_millis(1));
                }
                let used = cpu() - started.ok_or("not started")?;
                emit(
                    json!({"cpuSeconds": used, "requests": service.replay_entries(), "active": active.load(Ordering::SeqCst)}),
                );
            }
            "stop" => break,
            _ => return Err("invalid control command".into()),
        }
    }
    server.shutdown()?;
    emit(json!({"stopped": true}));
    Ok(())
}
