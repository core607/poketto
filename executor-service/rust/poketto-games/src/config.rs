//! Installed configuration: a runtime root, account, slice and unit prefix of its own, apart
//! from the repository supervisor's, and bounded job limits.
use sandcrab_core::{fs, unit::Profile};
use sandcrab_daemon::install::{execution_account, unit_name};
use serde_json::Value;
use std::{
    io,
    path::{Path, PathBuf},
};
fn invalid() -> io::Error {
    io::Error::other("Invalid game supervisor configuration")
}
fn text<'a>(value: &'a Value, key: &str) -> io::Result<&'a str> {
    value[key].as_str().ok_or_else(invalid)
}
fn path(value: &Value, key: &str) -> io::Result<PathBuf> {
    let text = text(value, key)?;
    let path = PathBuf::from(text);
    if !fs::normal(&path)
        || !text
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_./-".contains(&b))
    {
        return Err(invalid());
    }
    Ok(path)
}
fn number(value: &Value, key: &str, minimum: u64, maximum: u64) -> io::Result<u64> {
    value[key]
        .as_u64()
        .filter(|n| value[key].is_u64() && (minimum..=maximum).contains(n))
        .ok_or_else(invalid)
}
fn slice(name: &str) -> bool {
    unit_name(name)
        && name.ends_with(".slice")
        && !["system.slice", "user.slice", "machine.slice"].contains(&name)
}
pub struct Config {
    pub runtime_root: PathBuf,
    pub socket: PathBuf,
    pub public_key: PathBuf,
    pub profile: Profile,
    pub exec_gid: u32,
    pub app_uid: u32,
    pub app_gid: u32,
    pub unit_prefix: String,
    pub lease_seconds: u64,
    pub renew_after_seconds: u64,
    pub max_requests: usize,
    pub max_connections: usize,
    pub max_sessions: usize,
    pub max_timeout_millis: u64,
}
impl Config {
    pub fn load(file: &Path) -> io::Result<Self> {
        fs::protected_directory(file.parent().ok_or_else(invalid)?)?;
        let value =
            sandcrab_core::parse(&fs::protected_file(file, 65536)?).map_err(|_| invalid())?;
        let mut config = Self::from_value(&value)?;
        let repository = text(&value, "repositoryExecUser")?;
        let (uid, gid) =
            execution_account(&config.profile.exec_user, config.app_uid, config.app_gid)?;
        if uid == execution_account(repository, config.app_uid, config.app_gid)?.0 {
            return Err(invalid());
        }
        config.exec_gid = gid;
        Ok(config)
    }
    /// Every check except the execution accounts, which need the host's user database.
    fn from_value(value: &Value) -> io::Result<Self> {
        let runtime_root = path(value, "runtimeRoot")?;
        let repository_root = path(value, "repositoryRuntimeRoot")?;
        let socket = path(value, "socketPath")?;
        if runtime_root.starts_with(&repository_root)
            || repository_root.starts_with(&runtime_root)
            || socket.parent() != Some(runtime_root.as_path())
        {
            return Err(invalid());
        }
        let resource_slice = text(value, "resourceSlice")?;
        let supervisor_unit = text(value, "supervisorUnit")?;
        let unit_prefix = text(value, "unitPrefix")?;
        let exec_user = text(value, "execUser")?;
        if !slice(resource_slice)
            || resource_slice == text(value, "repositoryResourceSlice")?
            || !unit_name(supervisor_unit)
            || !supervisor_unit.ends_with(".service")
            || !unit_name(exec_user)
            || !unit_prefix
                .strip_prefix("poketto-game-")
                .and_then(|v| v.strip_suffix('-'))
                .is_some_and(|v| {
                    !v.is_empty()
                        && v.bytes()
                            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
                })
        {
            return Err(invalid());
        }
        let bounded = |key| number(value, key, 1, 1 << 40);
        let id = |key| -> io::Result<u32> {
            value[key]
                .as_u64()
                .and_then(|v| u32::try_from(v).ok())
                .ok_or_else(invalid)
        };
        let lease_seconds = number(value, "leaseSeconds", 6, 30)?;
        let renew_after_seconds = number(value, "renewAfterSeconds", 1, 5)?;
        Ok(Self {
            public_key: path(value, "publicKey")?,
            profile: Profile {
                tools: path(value, "toolsRoot")?,
                // The launcher from the supervisor's own release; configuration cannot select another.
                launcher: std::env::current_exe()?.with_file_name("poketto-launch"),
                exec_user: exec_user.into(),
                resource_slice: resource_slice.into(),
                supervisor_unit: supervisor_unit.into(),
                cpu_quota_percent: bounded("cpuQuotaPercent")?,
                memory_bytes: bounded("memoryBytes")?,
                // Node's game runtime and the isolation helpers count against this limit.
                tasks_max: number(value, "tasksMax", 48, 256)?,
                temporary_bytes: bounded("temporaryBytes")?,
                temporary_inodes: bounded("temporaryInodes")?,
            },
            exec_gid: 0,
            app_uid: id("appUid")?,
            app_gid: id("appGid")?,
            unit_prefix: unit_prefix.into(),
            lease_seconds,
            renew_after_seconds,
            max_requests: bounded("maxRequests")? as usize,
            max_connections: bounded("maxConnections")? as usize,
            max_sessions: number(value, "maxSessions", 1, 16)? as usize,
            max_timeout_millis: number(value, "maxTimeoutMillis", 100, 5000)?,
            runtime_root,
            socket,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    /// The values of game-config.example.json.
    fn example() -> Value {
        sandcrab_core::parse(br#"{"runtimeRoot":"/run/poketto-games","socketPath":"/run/poketto-games/control.sock","publicKey":"/etc/poketto-games/public.pem","toolsRoot":"/opt/poketto-executor/tools","launcher":"/opt/poketto-games/poketto-launch","execUser":"poketto-game","repositoryExecUser":"poketto-exec","repositoryRuntimeRoot":"/run/poketto-executor","repositoryResourceSlice":"poketto-executor.slice","appUid":1001,"appGid":1001,"unitPrefix":"poketto-game-worker-","supervisorUnit":"poketto-games.service","resourceSlice":"pokettogames.slice","leaseSeconds":15,"renewAfterSeconds":5,"maxRequests":256,"maxConnections":8,"maxSessions":2,"memoryBytes":134217728,"tasksMax":48,"cpuQuotaPercent":50,"temporaryBytes":2097152,"temporaryInodes":128,"maxTimeoutMillis":5000}"#).unwrap()
    }
    #[test]
    fn the_example_is_valid_and_separation_and_bounds_are_enforced() {
        let config = Config::from_value(&example()).unwrap();
        assert_eq!(config.max_sessions, 2);
        assert_eq!(
            config.profile.launcher.file_name().unwrap(),
            "poketto-launch"
        );
        for (key, value) in [
            ("runtimeRoot", json!("/run/poketto-executor/games")),
            ("runtimeRoot", json!("/run")),
            ("socketPath", json!("/run/control.sock")),
            ("resourceSlice", json!("poketto-executor.slice")),
            ("resourceSlice", json!("system.slice")),
            ("unitPrefix", json!("poketto-exec-games-")),
            ("unitPrefix", json!("poketto-game-")),
            ("tasksMax", json!(47)),
            ("maxSessions", json!(17)),
            ("maxTimeoutMillis", json!(5001)),
            ("leaseSeconds", json!(5)),
            ("renewAfterSeconds", json!(6)),
            ("memoryBytes", json!(0)),
            ("memoryBytes", json!(true)),
            ("toolsRoot", json!("relative")),
            ("toolsRoot", json!("/opt/a b")),
            ("toolsRoot", json!("/opt/../etc")),
        ] {
            let mut changed = example();
            changed[key] = value;
            assert!(Config::from_value(&changed).is_err(), "{key}");
        }
    }
}
