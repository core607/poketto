//! Each GAME request is one finite job: a fresh tmpfs holding the rule runtime and the
//! request, one launcher unit in game mode, then unmounting before admission is released.
use crate::{config::Config, game};
use rustix::fs::{Gid, OFlags, Uid, chown};
use sandcrab_core::{
    fs::{mounted, protected_directory},
    new_uuid, transfer,
    unit::{Launch, checked, empty_group, verify_resource_pool},
    uuid,
};
use sandcrab_daemon::{Extension, Service, job};
use serde_json::{Value, json};
use std::{
    fs::{self, DirBuilder, OpenOptions},
    io::{self, Read, Write},
    os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::atomic::AtomicBool,
    time::Duration,
};

/// The rule runtime each job imports, from beside the supervisor binary.
const ASSETS: [&str; 2] = ["game-entry.mjs", "game-runtime.mjs"];
/// The job's own tmpfs; `/tmp` is the unit's separate temporary file system.
const MOUNT_OPTIONS: &str = "size=2097152,nr_inodes=128,mode=0750,nosuid,nodev,noexec";

fn invalid() -> io::Error {
    io::Error::other("Game runtime unavailable")
}
fn seconds(seconds: u64) -> Duration {
    Duration::from_secs(seconds)
}
fn command(args: &[&str]) -> io::Result<std::process::Output> {
    checked(
        &args.iter().map(|v| v.to_string()).collect::<Vec<_>>(),
        seconds(15),
    )
}
fn owned(path: &Path, gid: u32, mode: u32) -> io::Result<()> {
    chown(path, Some(Uid::ROOT), Some(Gid::from_raw(gid)))?;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
}
fn create(path: &Path, bytes: &[u8], gid: u32, mode: u32) -> io::Result<()> {
    OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .custom_flags(OFlags::NOFOLLOW.bits() as i32)
        .open(path)?
        .write_all(bytes)?;
    owned(path, gid, mode)
}
/// A root-owned file that no other account can replace, in a directory no other account can change.
fn asset(path: &Path) -> io::Result<Vec<u8>> {
    protected_directory(path.parent().ok_or_else(invalid)?)?;
    let file = OpenOptions::new()
        .read(true)
        .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
        .open(path)?;
    let info = file.metadata()?;
    if !info.is_file() || info.uid() != 0 || info.mode() & 0o022 != 0 || info.len() > 1 << 20 {
        return Err(invalid());
    }
    let mut bytes = Vec::new();
    file.take(1 << 20).read_to_end(&mut bytes)?;
    Ok(bytes)
}

/// The launcher policy of a game job: the tools, the runtime and the request are readable,
/// only `/tmp` is writable, and there is no network, repository or host-call mailbox.
pub fn policy(tools: &Path, mount: &Path) -> Value {
    json!({"network":{"allowedDomains":[],"deniedDomains":[],"allowAllUnixSockets":false},
        "filesystem":{"denyRead":["/","/sys"],"allowRead":["/usr","/bin","/lib","/lib64","/dev","/proc",
            "/etc/ld.so.cache",tools,mount.join("bootstrap"),mount.join("input.json")],
            "allowWrite":["/tmp"],"denyWrite":[]},"enableWeakerNestedSandbox":false})
}

/// Create the runtime root and its job and record directories, lock the root and remove
/// whatever a previous supervisor left.
pub fn prepare_runtime(config: &Config) -> io::Result<fs::File> {
    let root = &config.runtime_root;
    if !root.exists() {
        let ancestor = root
            .ancestors()
            .skip(1)
            .find(|v| v.exists())
            .ok_or_else(invalid)?;
        protected_directory(ancestor)?;
        DirBuilder::new().recursive(true).mode(0o750).create(root)?;
    }
    for (path, mode) in [
        (root.clone(), 0o751),
        (root.join("sessions"), 0o750),
        (root.join("records"), 0o750),
    ] {
        match DirBuilder::new().mode(0o750).create(&path) {
            Err(e) if e.kind() != io::ErrorKind::AlreadyExists => return Err(e),
            _ => {}
        }
        let info = fs::symlink_metadata(&path)?;
        if !info.is_dir() || info.uid() != 0 || info.mode() & 0o022 != 0 {
            return Err(invalid());
        }
        owned(&path, config.exec_gid, mode)?;
    }
    let lock = sandcrab_daemon::install::runtime_lock(root)?;
    cleanup(config)?;
    Ok(lock)
}
/// Stop every unit with the configured prefix and confirm its cgroup is empty, then unmount
/// job directories and remove launch records.
fn cleanup(config: &Config) -> io::Result<()> {
    let pattern = format!("{}*", config.unit_prefix);
    let units = command(&[
        "systemctl",
        "list-units",
        "--all",
        "--plain",
        "--no-legend",
        &pattern,
    ])?;
    for line in String::from_utf8_lossy(&units.stdout).lines() {
        let name = line.split_whitespace().next().unwrap_or_default();
        let id = name
            .strip_prefix(&config.unit_prefix)
            .and_then(|v| v.strip_suffix(".service"));
        if !id.is_some_and(uuid) {
            return Err(invalid());
        }
        command(&["systemctl", "stop", name])?;
        let group = command(&["systemctl", "show", name, "-p", "ControlGroup", "--value"])?;
        let group = String::from_utf8_lossy(&group.stdout).trim().to_owned();
        if !group.is_empty() {
            if !sandcrab_core::fs::normal(Path::new(&group)) {
                return Err(invalid());
            }
            empty_group(&Path::new("/sys/fs/cgroup").join(group.trim_start_matches('/')))?;
        }
        let _ = command(&["systemctl", "reset-failed", name]);
    }
    for entry in fs::read_dir(config.runtime_root.join("sessions"))? {
        let entry = entry?;
        if !entry.file_name().to_str().is_some_and(uuid) {
            return Err(invalid());
        }
        release(&entry.path(), &[])?;
    }
    for entry in fs::read_dir(config.runtime_root.join("records"))? {
        let entry = entry?;
        let name = entry.file_name();
        let id = name.to_str().and_then(|v| {
            v.strip_suffix(".sandbox.json")
                .or_else(|| v.strip_suffix(".json"))
        });
        if !id.is_some_and(uuid) || !entry.file_type()?.is_file() {
            return Err(invalid());
        }
        fs::remove_file(entry.path())?;
    }
    Ok(())
}
/// Remove a job's records, unmount its directory without following a link and remove it.
fn release(mount: &Path, records: &[PathBuf]) -> io::Result<()> {
    for record in records {
        match fs::remove_file(record) {
            Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(e),
            _ => {}
        }
    }
    match fs::symlink_metadata(mount) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e),
        Ok(info) if !info.is_dir() => return Err(invalid()),
        Ok(_) => {}
    }
    if mounted(mount)? {
        command(&["umount", &mount.to_string_lossy()])?;
    }
    // Never recurse into a directory a job could write, even after a failed unmount.
    fs::remove_dir(mount)
}

/// The game service: jobs and revocation only.
pub struct Games {
    config: Config,
    resource_pool: PathBuf,
    assets: Vec<(&'static str, Vec<u8>)>,
}
impl Games {
    /// Verify the slice, the launcher, its tools and the rule runtime before serving.
    pub fn new(mut config: Config) -> io::Result<Self> {
        let resource_pool =
            sandcrab_daemon::install::resource_pool(&config.profile.resource_slice)?;
        config.profile.launcher = config.profile.launcher.canonicalize()?;
        sandcrab_launch::trusted_executable(&config.profile.launcher).map_err(io::Error::other)?;
        protected_directory(&config.profile.tools)?;
        sandcrab_launch::verify_tools(&config.profile.tools).map_err(io::Error::other)?;
        let directory = config.profile.launcher.parent().ok_or_else(invalid)?;
        let assets = ASSETS
            .iter()
            .map(|name| asset(&directory.join(name)).map(|bytes| (*name, bytes)))
            .collect::<io::Result<_>>()?;
        Ok(Self {
            config,
            resource_pool,
            assets,
        })
    }
    fn prepare(
        &self,
        mount: &Path,
        input: &[u8],
        unit: &str,
        records: &[PathBuf; 2],
    ) -> io::Result<Launch> {
        let gid = self.config.exec_gid;
        command(&[
            "mount",
            "-t",
            "tmpfs",
            "-o",
            MOUNT_OPTIONS,
            "tmpfs",
            &mount.to_string_lossy(),
        ])?;
        owned(mount, gid, 0o750)?;
        let bootstrap = mount.join("bootstrap");
        DirBuilder::new().mode(0o555).create(&bootstrap)?;
        fs::set_permissions(&bootstrap, fs::Permissions::from_mode(0o555))?;
        for (name, bytes) in &self.assets {
            create(&bootstrap.join(name), bytes, 0, 0o444)?;
        }
        create(&mount.join("input.json"), input, gid, 0o440)?;
        let tools = &self.config.profile.tools;
        let [record, settings] = records;
        let launch = json!({"mode":"game","root":mount,"tools":tools,"settings":settings});
        create(record, launch.to_string().as_bytes(), gid, 0o440)?;
        create(
            settings,
            policy(tools, mount).to_string().as_bytes(),
            gid,
            0o440,
        )?;
        Ok(Launch {
            command: self.config.profile.command(
                unit,
                record,
                settings,
                mount,
                Some(self.config.max_timeout_millis),
            ),
            unit: unit.into(),
            cgroup: self.resource_pool.join(format!("{unit}.service")),
        })
    }
    fn run(&self, lease: &str, input: &[u8], cancelled: &AtomicBool) -> job::Outcome {
        let Ok(operation) = new_uuid() else {
            return Ok(Err("EXECUTOR_FAILED"));
        };
        if verify_resource_pool(&self.resource_pool).is_err() {
            return Ok(Err("EXECUTOR_FAILED"));
        }
        let mount = self.config.runtime_root.join("sessions").join(lease);
        if DirBuilder::new().mode(0o750).create(&mount).is_err() {
            return Ok(Err("EXECUTOR_FAILED"));
        }
        let records = ["json", "sandbox.json"].map(|suffix| {
            self.config
                .runtime_root
                .join("records")
                .join(format!("{operation}.{suffix}"))
        });
        let unit = format!("{}{operation}", self.config.unit_prefix);
        let (finished, contained) = match self.prepare(&mount, input, &unit, &records) {
            Ok(launch) => job::run(
                &launch,
                cancelled,
                Duration::from_millis(self.config.max_timeout_millis),
                game::MAX_OUTPUT,
            ),
            Err(error) => (Err(error), Ok(())),
        };
        contained.and_then(|()| release(&mount, &records))?;
        Ok(finished
            .map_err(|_| "EXECUTOR_FAILED")
            .and_then(game::output))
    }
}
impl Extension for Games {
    fn operations(&self) -> &'static [&'static str] {
        &["GAME"]
    }
    fn lease_operations(&self) -> &'static [&'static str] {
        &["REVOKE"]
    }
    fn hello(&self) -> serde_json::Map<String, Value> {
        let Value::Object(fields) = json!({"gameProtocol":1,"codeActProtocol":0}) else {
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
        let input = game::input(&payload["data"])?;
        let lease = payload["leaseId"].as_str().ok_or("INVALID_REQUEST")?;
        service
            .job(payload, |cancelled| self.run(lease, &input, cancelled))
            .map(|result| json!({"ok":true,"result":result}))
            .map_err(|code| match code {
                "JOB_BUSY" => "GAME_BUSY",
                "JOB_CAPACITY" => "GAME_CAPACITY",
                "JOB_EXPIRED" => "GAME_EXPIRED",
                other => other,
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn the_job_policy_is_the_one_the_launcher_tests_translate() {
        let expected: Value = sandcrab_core::parse(include_bytes!(
            "../../poketto-launch/testdata/policies.json"
        ))
        .unwrap();
        let mount = Path::new("/run/poketto/sessions/00000000-0000-4000-8000-000000000001");
        assert_eq!(
            policy(Path::new("/opt/poketto/tools"), mount),
            expected["game"]
        );
    }
}
