#![forbid(unsafe_code)]
//! Poketto's game supervisor: the Sandcrab daemon serving each rule step as a finite job,
//! under an execution account, slice and unit prefix apart from the repository supervisor's,
//! without repository copies or host calls.
mod config;
mod game;
mod jobs;

use sandcrab_daemon::{Service, Settings, emit, install, server};
use serde_json::json;
use signal_hook::{
    consts::{SIGINT, SIGTERM},
    flag,
};
use std::{
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::Duration,
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if rustix::process::geteuid().as_raw() != 0 {
        return Err("The game supervisor requires root; rule execution never uses root".into());
    }
    let cleanup = match args.as_slice() {
        [flag, _] if flag == "--config" => false,
        [flag, _, cleanup] if flag == "--config" && cleanup == "--cleanup" => true,
        _ => return Err("Usage: --config PATH [--cleanup]".into()),
    };
    let terminated = Arc::new(AtomicBool::new(false));
    let _signals = [
        flag::register(SIGINT, terminated.clone())?,
        flag::register(SIGTERM, terminated.clone())?,
    ];
    let config = config::Config::load(Path::new(&args[1]))?;
    let lock = jobs::prepare_runtime(&config)?;
    if cleanup {
        return Ok(());
    }
    let (socket, public_key) = (config.socket.clone(), config.public_key.clone());
    let (app_uid, app_gid, connections) = (config.app_uid, config.app_gid, config.max_connections);
    let mut settings = Settings::new(sandcrab_core::new_uuid()?, config.max_requests);
    settings.lease_seconds = config.lease_seconds;
    settings.renew_after_seconds = config.renew_after_seconds;
    settings.max_sessions = config.max_sessions;
    settings.max_live_units = config.max_sessions;
    settings.max_starting_units = config.max_sessions;
    settings.max_timeout_millis = config.max_timeout_millis;
    settings.runtime_root = Some(config.runtime_root.clone());
    // Tools are verified before the key is read or the socket is opened.
    let games = jobs::Games::new(config)?;
    let key = install::load_key(&public_key)?;
    let service = Arc::new(Service::new(settings, key, Some(lock), Box::new(games))?);
    if terminated.load(Ordering::SeqCst) {
        return Ok(());
    }
    let server = server::Server::start(
        socket,
        service,
        connections,
        false,
        rustix::fs::Uid::from_raw(app_uid),
        Some(app_gid),
    )?;
    emit(json!({"ready":true,"fixtureOnly":false}));
    while !terminated.load(Ordering::SeqCst) && !server.failed() {
        thread::sleep(Duration::from_millis(100));
    }
    let failed = server.failed();
    server.shutdown()?;
    if failed {
        return Err("Game supervisor server failed".into());
    }
    Ok(())
}
