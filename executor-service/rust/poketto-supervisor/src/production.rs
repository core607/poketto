//! Installed CLI configuration, protected public key and application-owned socket access.
use crate::{Repository, provision};
use rustix::process::{Resource, getrlimit, setrlimit};
use sandcrab_core::install::{execution_account, runtime_lock, unit_name as name};
use sandcrab_daemon::{Service, Settings, install::load_key, server};
use serde::Deserialize;
use serde_json::{Value, json};
use signal_hook::{
    consts::{SIGINT, SIGTERM},
    flag,
};
use std::{
    fs::{DirBuilder, File},
    io,
    os::unix::fs::DirBuilderExt,
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::Duration,
};
fn invalid() -> io::Error {
    io::Error::other("Invalid supervisor installation")
}
fn number(value: &Value, key: &str) -> io::Result<u64> {
    value[key]
        .as_u64()
        .filter(|n| (1..=1 << 40).contains(n))
        .ok_or_else(invalid)
}
fn text<'a>(value: &'a Value, key: &str) -> io::Result<&'a str> {
    value[key].as_str().ok_or_else(invalid)
}
fn path(value: &Value, key: &str) -> io::Result<PathBuf> {
    let path = PathBuf::from(text(value, key)?);
    if !sandcrab_core::fs::normal(&path) {
        return Err(invalid());
    }
    Ok(path)
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Limits {
    lease_seconds: u64,
    renew_after_seconds: u64,
    max_requests: usize,
    max_connections: usize,
    max_sessions: usize,
    max_executions_per_session: usize,
    max_timeout_millis: u64,
    idle_unit_seconds: u64,
    max_live_units: Option<usize>,
    max_starting_units: Option<usize>,
}
impl Limits {
    fn validate(&self) -> io::Result<()> {
        if !(1..=1 << 40).contains(&self.lease_seconds)
            || self.renew_after_seconds == 0
            || self.renew_after_seconds >= self.lease_seconds
            || !(1..=1 << 40).contains(&self.max_requests)
            || !(1..=1 << 40).contains(&self.max_connections)
            || !(1..=1 << 40).contains(&self.max_sessions)
            || !(1..=1 << 40).contains(&self.max_executions_per_session)
            || !(1..=1 << 40).contains(&self.max_timeout_millis)
            || !(1..=86400).contains(&self.idle_unit_seconds)
            || !(1..=self.max_sessions).contains(&self.max_live_units.unwrap_or(self.max_sessions))
            || !(1..=self.max_sessions)
                .contains(&self.max_starting_units.unwrap_or(self.max_sessions))
        {
            return Err(invalid());
        }
        Ok(())
    }
    fn settings(&self, runtime_root: PathBuf) -> io::Result<Settings> {
        Ok(Settings {
            #[cfg(feature = "fixture")]
            public_key: String::new(),
            worker_boot_id: crate::new_uuid()?,
            sessions: vec![],
            max_requests: self.max_requests,
            #[cfg(feature = "fixture")]
            max_connections: self.max_connections,
            runtime_root: Some(runtime_root),
            max_sessions: self.max_sessions,
            max_timeout_millis: self.max_timeout_millis,
            max_executions_per_session: self.max_executions_per_session,
            max_live_units: self.max_live_units.unwrap_or(self.max_sessions),
            max_starting_units: self.max_starting_units.unwrap_or(self.max_sessions),
            idle_unit_seconds: self.idle_unit_seconds,
            lease_seconds: self.lease_seconds,
            renew_after_seconds: self.renew_after_seconds,
            revoked_keys: vec![],
            revoked_accounts: vec![],
        })
    }
}
struct Config {
    backend: provision::Config,
    socket: PathBuf,
    public_key: PathBuf,
    limits: Limits,
}
impl Config {
    fn load(file: &Path) -> io::Result<Self> {
        sandcrab_core::fs::protected_directory(file.parent().ok_or_else(invalid)?)?;
        let value = crate::parse(&sandcrab_core::fs::protected_file(file, 65536)?)
            .map_err(|_| invalid())?;
        Self::from_value(&value)
    }
    fn from_value(value: &Value) -> io::Result<Self> {
        let limits: Limits = serde_json::from_value(value.clone()).map_err(|_| invalid())?;
        limits.validate()?;
        let runtime_root = path(value, "runtimeRoot")?;
        let copy_root = path(value, "copyRoot")?;
        let socket = path(value, "socketPath")?;
        let public_key = path(value, "publicKey")?;
        if socket.parent() != Some(runtime_root.as_path())
            || copy_root.starts_with(&runtime_root)
            || runtime_root.starts_with(&copy_root)
        {
            return Err(invalid());
        }
        let exec_user = text(value, "execUser")?.to_owned();
        let prefix = text(value, "unitPrefix")?;
        let suffix = prefix
            .strip_prefix("poketto-exec-")
            .and_then(|v| v.strip_suffix('-'))
            .filter(|v| !v.is_empty())
            .ok_or_else(invalid)?;
        let resource_slice = text(value, "resourceSlice")?;
        let supervisor_unit = text(value, "supervisorUnit")?;
        if !name(&exec_user)
            || !suffix
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
            || !name(resource_slice)
            || !resource_slice.ends_with(".slice")
            || ["system.slice", "user.slice", "machine.slice"].contains(&resource_slice)
            || !name(supervisor_unit)
            || !supervisor_unit.ends_with(".service")
        {
            return Err(invalid());
        }
        let app_uid = value["appUid"]
            .as_u64()
            .and_then(|v| u32::try_from(v).ok())
            .ok_or_else(invalid)?;
        let app_gid = value["appGid"]
            .as_u64()
            .and_then(|v| u32::try_from(v).ok())
            .ok_or_else(invalid)?;
        let (exec_uid, exec_gid) = execution_account(&exec_user, app_uid, app_gid)?;
        let backend = provision::Config {
            runtime_root,
            export_root: path(value, "exportRoot")?,
            copy_root,
            pool_bytes: number(value, "poolBytes")?,
            disk_bytes: number(value, "diskBytes")?,
            disk_inodes: number(value, "diskInodes")?,
            tools_root: path(value, "toolsRoot")?,
            // The launcher from the supervisor's own release; configuration cannot select another.
            launcher: std::env::current_exe()?.with_file_name("poketto-launch"),
            exec_user,
            exec_uid,
            exec_gid,
            app_uid,
            app_gid,
            unit_prefix: prefix.into(),
            supervisor_unit: supervisor_unit.into(),
            resource_slice: resource_slice.into(),
            memory_bytes: number(value, "memoryBytes")?,
            tasks_max: number(value, "tasksMax")?,
            cpu_quota_percent: number(value, "cpuQuotaPercent")?,
            temporary_bytes: number(value, "temporaryBytes")?,
            temporary_inodes: number(value, "temporaryInodes")?,
            max_bundle_bytes: number(value, "maxBundleBytes")?,
            init_timeout_millis: number(value, "initTimeoutMillis")?,
        };
        Ok(Self {
            backend,
            socket,
            public_key,
            limits,
        })
    }
}
fn root_lock(config: &provision::Config) -> io::Result<File> {
    if !config.runtime_root.exists() {
        let ancestor = config
            .runtime_root
            .ancestors()
            .skip(1)
            .find(|v| v.exists())
            .ok_or_else(invalid)?;
        sandcrab_core::fs::protected_directory(ancestor)?;
        DirBuilder::new()
            .recursive(true)
            .mode(0o750)
            .create(&config.runtime_root)?;
    }
    runtime_lock(&config.runtime_root)
}
pub fn run(args: &[String]) -> Result<(), Box<dyn std::error::Error>> {
    if rustix::process::geteuid().as_raw() != 0 {
        return Err("Root supervisor required".into());
    }
    let cleanup = match args {
        [flag, _] if flag == "--config" => false,
        [flag, _, cleanup] if flag == "--config" && cleanup == "--cleanup" => true,
        _ => return Err("Usage: --config PATH [--cleanup]".into()),
    };
    let terminated = Arc::new(AtomicBool::new(false));
    let _signals = [
        flag::register(SIGINT, terminated.clone())?,
        flag::register(SIGTERM, terminated.clone())?,
    ];
    let config = Config::load(Path::new(&args[1]))?;
    let root_lock = root_lock(&config.backend)?;
    if cleanup {
        provision::runtime_directories(&config.backend)?;
        provision::cleanup_runtime(&config.backend)?;
        return Ok(());
    }
    let factory = provision::Factory::new(config.backend.clone())?;
    let key = load_key(&config.public_key)?;
    let settings = config
        .limits
        .settings(config.backend.runtime_root.clone())?;
    let service = Arc::new(Service::new(
        settings,
        key,
        Some(root_lock),
        Box::new(Repository {
            factory: Some(factory),
        }),
    )?);
    let mut limit = getrlimit(Resource::Nofile);
    limit.current = limit.maximum;
    setrlimit(Resource::Nofile, limit)?;
    if terminated.load(Ordering::SeqCst) {
        return Ok(());
    }
    let server = server::Server::start(
        config.socket,
        service,
        config.limits.max_connections,
        false,
        rustix::fs::Uid::from_raw(config.backend.app_uid),
        Some(config.backend.app_gid),
    )?;
    sandcrab_daemon::emit(json!({"ready":true,"fixtureOnly":false}));
    while !terminated.load(Ordering::SeqCst) && !server.failed() {
        thread::sleep(Duration::from_millis(100));
    }
    let failed = server.failed();
    server.shutdown()?;
    if failed {
        return Err(invalid().into());
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn configured_limits_reject_invalid_or_unbounded_values() {
        let base = json!({"leaseSeconds":9,"renewAfterSeconds":3,"maxRequests":4096,"maxConnections":256,"maxSessions":600,"maxExecutionsPerSession":1000,"maxTimeoutMillis":60000,"idleUnitSeconds":300,"maxLiveUnits":224,"maxStartingUnits":1});
        assert!(
            serde_json::from_value::<Limits>(base.clone())
                .unwrap()
                .validate()
                .is_ok()
        );
        for (key, value) in [
            ("renewAfterSeconds", json!(9)),
            ("maxRequests", json!((1_u64 << 40) + 1)),
            ("maxConnections", json!((1_u64 << 40) + 1)),
            ("maxSessions", json!((1_u64 << 40) + 1)),
            ("maxLiveUnits", json!(601)),
            ("maxStartingUnits", json!(0)),
            ("maxTimeoutMillis", json!((1_u64 << 40) + 1)),
        ] {
            let mut invalid = base.clone();
            invalid[key] = value;
            assert!(
                serde_json::from_value::<Limits>(invalid)
                    .unwrap()
                    .validate()
                    .is_err(),
                "{key}"
            );
        }
        for requests in [4097, 8192, 1_u64 << 40] {
            let mut valid = base.clone();
            valid["maxRequests"] = json!(requests);
            assert!(
                serde_json::from_value::<Limits>(valid)
                    .unwrap()
                    .validate()
                    .is_ok()
            );
        }
        let mut invalid = base;
        invalid["leaseSeconds"] = true.into();
        assert!(serde_json::from_value::<Limits>(invalid).is_err());
    }
}
