//! Fixed sandbox launch plans and bounded exports for signed OPEN/ATTACH requests.
use crate::{lease::Description, pool::Pool, runtime};
use rustix::fs::{Gid, OFlags, chown, fchown};
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, DirBuilder, OpenOptions},
    io::{self, Read, Write},
    os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config {
    pub runtime_root: PathBuf,
    pub export_root: PathBuf,
    pub copy_root: PathBuf,
    pub pool_bytes: u64,
    pub disk_bytes: u64,
    pub disk_inodes: u64,
    pub tools_root: PathBuf,
    pub launcher: PathBuf,
    pub exec_user: String,
    pub exec_uid: u32,
    pub exec_gid: u32,
    pub app_uid: u32,
    pub app_gid: u32,
    pub unit_prefix: String,
    pub supervisor_unit: String,
    pub resource_slice: String,
    pub memory_bytes: u64,
    pub tasks_max: u64,
    pub cpu_quota_percent: u64,
    pub temporary_bytes: u64,
    pub temporary_inodes: u64,
    pub max_bundle_bytes: u64,
    pub init_timeout_millis: u64,
}
pub struct Factory {
    pub config: Config,
    pub pool: Pool,
    pub resource_pool: PathBuf,
}
pub struct Plan {
    pub description: runtime::Description,
    pub records: Vec<(PathBuf, Value)>,
    pub initialize: Option<sandcrab_core::unit::Launch>,
}
#[derive(Clone)]
pub struct Export {
    pub id: String,
    pub bytes: u64,
    pub digest: String,
}
pub enum Source {
    Export(Export),
    Seed,
}
fn invalid() -> io::Error {
    io::Error::other("Provisioning unavailable")
}
fn checked(args: &[String]) -> io::Result<std::process::Output> {
    sandcrab_core::unit::checked(args, Duration::from_secs(15))
}
fn name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 240
        && value.as_bytes()[0].is_ascii_alphanumeric()
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b))
}
fn directory(path: &Path, gid: u32, mode: u32) -> io::Result<()> {
    match DirBuilder::new().mode(mode).create(path) {
        Ok(()) => (),
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => (),
        Err(e) => return Err(e),
    }
    sandcrab_core::fs::protected_directory(path)?;
    chown(path, None, Some(Gid::from_raw(gid)))?;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
}
impl Factory {
    pub fn new(mut config: Config) -> io::Result<Self> {
        if !name(&config.exec_user)
            || config.exec_uid == 0
            || config.exec_uid == config.app_uid
            || config.exec_gid == config.app_gid
            || !name(&config.unit_prefix)
            || !config.unit_prefix.starts_with("poketto-exec-")
            || !config.unit_prefix.ends_with('-')
            || !name(&config.supervisor_unit)
            || !config.supervisor_unit.ends_with(".service")
            || !name(&config.resource_slice)
            || !config.resource_slice.ends_with(".slice")
            || ["system.slice", "user.slice", "machine.slice"]
                .contains(&config.resource_slice.as_str())
            || config.memory_bytes == 0
            || config.tasks_max == 0
            || config.cpu_quota_percent == 0
            || config.temporary_bytes == 0
            || config.temporary_inodes == 0
            || config.max_bundle_bytes == 0
            || config.max_bundle_bytes >= 1 << 63
            || !(1..=1 << 40).contains(&config.init_timeout_millis)
        {
            return Err(invalid());
        }
        let ids = checked(&["id".into(), "-u".into(), config.exec_user.clone()])?;
        if std::str::from_utf8(&ids.stdout)
            .ok()
            .and_then(|v| v.trim().parse::<u32>().ok())
            != Some(config.exec_uid)
        {
            return Err(invalid());
        }
        let ids = checked(&["id".into(), "-g".into(), config.exec_user.clone()])?;
        if std::str::from_utf8(&ids.stdout)
            .ok()
            .and_then(|v| v.trim().parse::<u32>().ok())
            != Some(config.exec_gid)
        {
            return Err(invalid());
        }
        let ids = checked(&["id".into(), "-G".into(), config.exec_user.clone()])?;
        if std::str::from_utf8(&ids.stdout)
            .map_err(|_| invalid())?
            .split_whitespace()
            .any(|n| n.parse::<u32>().ok() == Some(config.app_gid))
        {
            return Err(invalid());
        }
        runtime_directories(&config)?;
        cleanup_runtime(&config)?;
        config.launcher = config.launcher.canonicalize()?;
        runtime::verify_trusted_file(&config.launcher, false)?;
        sandcrab_core::fs::protected_directory(&config.tools_root)?;
        let programs = config.launcher.parent().ok_or_else(invalid)?.join("bin");
        for program in ["poketto", "poketto-shell", "poketto-syncfs"] {
            runtime::verify_program(&programs.join(program))?;
        }
        if fs::read_dir(&programs)?.count() != 3 {
            return Err(invalid());
        }
        sandcrab_core::fs::protected_directory(config.export_root.parent().ok_or_else(invalid)?)?;
        let info = fs::symlink_metadata(&config.export_root)?;
        if !info.is_dir() || info.uid() != config.app_uid || info.mode() & 0o077 != 0 {
            return Err(invalid());
        }
        let pool = Pool::new(
            config.copy_root.clone(),
            config.pool_bytes,
            config.disk_bytes,
            config.disk_inodes,
        )?;
        let group = checked(&[
            "systemctl".into(),
            "show".into(),
            config.resource_slice.clone(),
            "--property=ControlGroup".into(),
            "--value".into(),
        ])?;
        let group = std::str::from_utf8(&group.stdout)
            .map_err(|_| invalid())?
            .trim();
        if !group.starts_with('/')
            || !sandcrab_core::fs::normal(Path::new(group))
            || group.rsplit('/').next() != Some(config.resource_slice.as_str())
        {
            return Err(invalid());
        }
        let resource_pool = Path::new("/sys/fs/cgroup").join(group.trim_start_matches('/'));
        sandcrab_core::unit::verify_resource_pool(&resource_pool)?;
        Ok(Self {
            config,
            pool,
            resource_pool,
        })
    }
    fn launch(
        &self,
        mount: &Path,
        mode: &str,
        commit: &str,
    ) -> io::Result<(sandcrab_core::unit::Launch, Vec<(PathBuf, Value)>)> {
        let config = &self.config;
        let operation = crate::new_uuid()?;
        let record = config
            .runtime_root
            .join("records")
            .join(format!("{operation}.json"));
        let settings = config
            .runtime_root
            .join("records")
            .join(format!("{operation}.sandbox.json"));
        let mut read = vec![
            "/usr".to_string(),
            "/bin".into(),
            "/lib".into(),
            "/lib64".into(),
            "/dev".into(),
            "/proc".into(),
            "/etc/ld.so.cache".into(),
            config.tools_root.to_string_lossy().into(),
            mount.join("bootstrap").to_string_lossy().into(),
            config
                .launcher
                .parent()
                .unwrap()
                .join("bin")
                .to_string_lossy()
                .into(),
            mount.join("bridge/lock").to_string_lossy().into(),
            mount.join("bridge/state").to_string_lossy().into(),
            mount.join("bridge/responses").to_string_lossy().into(),
        ];
        if mode == "initialize" {
            read.push(mount.join("snapshot.bundle").to_string_lossy().into());
        } else if mode == "baseline" {
            read.push(mount.join("baseline.bundle").to_string_lossy().into());
        }
        let policy = json!({"network":{"allowedDomains":[],"deniedDomains":[],"allowAllUnixSockets":false},
            "filesystem":{"denyRead":["/","/sys"],"allowRead":read,"denyWrite":[],"allowWrite":[mount.join("work"),mount.join("home"),mount.join("bridge/requests"),PathBuf::from("/tmp")]},"enableWeakerNestedSandbox":false});
        let record_value = json!({"mode":mode,"commit":commit,"root":mount,"tools":config.tools_root,"settings":settings});
        let unit = format!("{}{}", config.unit_prefix, operation);
        let mut command = vec![
            "systemd-run".into(),
            "--quiet".into(),
            "--wait".into(),
            "--pipe".into(),
            "--unit".into(),
            unit.clone(),
            "--slice".into(),
            config.resource_slice.clone(),
            "-p".into(),
            format!("User={}", config.exec_user),
            "-p".into(),
            format!("CPUQuota={}%", config.cpu_quota_percent),
            "-p".into(),
            format!("MemoryMax={}", config.memory_bytes),
            "-p".into(),
            "MemorySwapMax=0".into(),
            "-p".into(),
            format!("TasksMax={}", config.tasks_max),
            "-p".into(),
            "KillMode=control-group".into(),
            "-p".into(),
            "TimeoutStopSec=1".into(),
            "-p".into(),
            "SendSIGKILL=yes".into(),
            "-p".into(),
            "NoNewPrivileges=yes".into(),
            "-p".into(),
            "UMask=0077".into(),
            "-p".into(),
            format!(
                "TemporaryFileSystem=/tmp:rw,size={},nr_inodes={},mode=1777,nosuid,nodev",
                config.temporary_bytes, config.temporary_inodes
            ),
            "-p".into(),
            format!(
                "BindReadOnlyPaths={} {} {} {}",
                config.tools_root.display(),
                config.launcher.display(),
                record.display(),
                settings.display()
            ),
            "-p".into(),
            format!("BindPaths={}", mount.display()),
            "-p".into(),
            format!("BindsTo={}", config.supervisor_unit),
            "-p".into(),
            format!("After={}", config.supervisor_unit),
            "/usr/bin/env".into(),
            "-i".into(),
            "PATH=/usr/bin:/bin".into(),
            "/usr/bin/python3".into(),
            config.launcher.to_string_lossy().into(),
            record.to_string_lossy().into(),
        ];
        if mode == "initialize" || mode == "baseline" {
            let offset = command
                .iter()
                .position(|arg| arg == "/usr/bin/env")
                .ok_or_else(invalid)?;
            command.splice(
                offset..offset,
                [
                    "-p".into(),
                    format!(
                        "RuntimeMaxSec={}.{:03}",
                        config.init_timeout_millis / 1000,
                        config.init_timeout_millis % 1000
                    ),
                ],
            );
        }
        Ok((
            sandcrab_core::unit::Launch {
                cgroup: self.resource_pool.join(format!("{unit}.service")),
                unit,
                command,
            },
            vec![(record, record_value), (settings, policy)],
        ))
    }
    pub fn owner(
        &self,
        copy_id: &str,
        account: &str,
        workspace: &str,
        scope: &str,
        commit: &str,
    ) -> Description {
        Description {
            pool_root: self.config.copy_root.clone(),
            maximum_bytes: self.config.pool_bytes,
            copy_id: copy_id.into(),
            account_id: account.into(),
            workspace_id: workspace.into(),
            scope: scope.into(),
            commit: commit.into(),
            exec_uid: self.config.exec_uid,
            exec_gid: self.config.exec_gid,
        }
    }
    pub fn plan(&self, lease_id: &str, owner: Description, initialize: bool) -> io::Result<Plan> {
        if [
            lease_id,
            owner.copy_id.as_str(),
            owner.account_id.as_str(),
            owner.workspace_id.as_str(),
        ]
        .iter()
        .any(|id| !crate::uuid(id))
            || !crate::hex(&owner.commit, 40)
            || !["full", "public"].contains(&owner.scope.as_str())
        {
            return Err(invalid());
        }
        let commit = &owner.commit;
        let mount = self.config.runtime_root.join("sessions").join(lease_id);
        let (shell, mut records) = self.launch(&mount, "shell", commit)?;
        let init = if initialize {
            let (launch, more) = self.launch(&mount, "initialize", commit)?;
            records.extend(more);
            Some(launch)
        } else {
            None
        };
        let description = runtime::Description {
            command: shell.command,
            unit: shell.unit,
            cgroup: shell.cgroup,
            resource_pool: self.resource_pool.clone(),
            mount,
            sync_program: self
                .config
                .launcher
                .parent()
                .unwrap()
                .join("bin/poketto-syncfs"),
            owned_files: records.iter().map(|(p, _)| p.clone()).collect(),
            lease: owner,
        };
        Ok(Plan {
            description,
            records,
            initialize: init,
        })
    }
    pub fn write_records(&self, plan: &Plan) -> io::Result<()> {
        self.write_launch_records(&plan.records)
    }
    pub fn write_launch_records(&self, records: &[(PathBuf, Value)]) -> io::Result<()> {
        for (path, value) in records {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o440)
                .custom_flags(OFlags::NOFOLLOW.bits() as i32)
                .open(path)?;
            file.write_all(&serde_json::to_vec(value)?)?;
            fchown(&file, None, Some(Gid::from_raw(self.config.exec_gid)))?;
            file.set_permissions(fs::Permissions::from_mode(0o440))?;
        }
        Ok(())
    }
    pub fn baseline_plan(
        &self,
        mount: &Path,
        commit: &str,
    ) -> io::Result<(sandcrab_core::unit::Launch, Vec<(PathBuf, Value)>)> {
        if !crate::hex(commit, 40)
            || mount.parent() != Some(self.config.runtime_root.join("sessions").as_path())
            || !mount
                .file_name()
                .and_then(|v| v.to_str())
                .is_some_and(crate::uuid)
        {
            return Err(invalid());
        }
        self.launch(mount, "baseline", commit)
    }
    pub fn copy_export(
        &self,
        mount: &Path,
        export: &Export,
        cancelled: &AtomicBool,
    ) -> io::Result<()> {
        self.copy_bundle(mount, export, cancelled, "snapshot.bundle")
    }
    pub fn copy_baseline(
        &self,
        mount: &Path,
        export: &Export,
        cancelled: &AtomicBool,
    ) -> io::Result<()> {
        self.copy_bundle(mount, export, cancelled, "baseline.bundle")
    }
    fn copy_bundle(
        &self,
        mount: &Path,
        export: &Export,
        cancelled: &AtomicBool,
        filename: &str,
    ) -> io::Result<()> {
        if !crate::uuid(&export.id)
            || !crate::hex(&export.digest, 64)
            || export.bytes == 0
            || export.bytes > self.config.max_bundle_bytes
        {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        let file = OpenOptions::new()
            .read(true)
            .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
            .open(
                self.config
                    .export_root
                    .join(format!("{}.bundle", export.id)),
            )?;
        let info = file.metadata()?;
        if !info.is_file() || info.uid() != self.config.app_uid || info.len() != export.bytes {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        let mut input = file;
        let mut digest = Sha256::new();
        let mut total = 0u64;
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o440)
            .custom_flags(OFlags::NOFOLLOW.bits() as i32)
            .open(mount.join(filename))?;
        let mut buffer = [0; 65536];
        loop {
            if cancelled.load(Ordering::SeqCst) {
                return Err(io::Error::from(io::ErrorKind::Interrupted));
            }
            let count = input.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            total += count as u64;
            if total > export.bytes {
                return Err(io::Error::from(io::ErrorKind::InvalidData));
            }
            digest.update(&buffer[..count]);
            output.write_all(&buffer[..count])?;
        }
        output.sync_all()?;
        fchown(&output, None, Some(Gid::from_raw(self.config.exec_gid)))?;
        output.set_permissions(fs::Permissions::from_mode(0o440))?;
        let actual = digest
            .finalize()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>();
        if total != export.bytes || actual != export.digest {
            return Err(io::Error::from(io::ErrorKind::InvalidData));
        }
        Ok(())
    }
}

pub(crate) fn runtime_directories(config: &Config) -> io::Result<()> {
    sandcrab_core::fs::protected_directory(&config.runtime_root)?;
    directory(&config.runtime_root, config.exec_gid, 0o751)?;
    for sub in ["sessions", "records"] {
        directory(&config.runtime_root.join(sub), config.exec_gid, 0o750)?;
    }
    Ok(())
}

pub(crate) fn cleanup_runtime(config: &Config) -> io::Result<()> {
    let prefix = &config.unit_prefix;
    let units = checked(&[
        "systemctl".into(),
        "list-units".into(),
        "--all".into(),
        "--plain".into(),
        "--no-legend".into(),
        format!("{prefix}*"),
    ])?;
    let units = std::str::from_utf8(&units.stdout).map_err(|_| invalid())?;
    for line in units.lines() {
        let unit = line.split_whitespace().next().ok_or_else(invalid)?;
        let id = unit
            .strip_prefix(prefix)
            .and_then(|v| v.strip_suffix(".service"))
            .filter(|v| crate::uuid(v))
            .ok_or_else(invalid)?;
        if id.is_empty() {
            return Err(invalid());
        }
        let metadata = checked(&[
            "systemctl".into(),
            "show".into(),
            unit.into(),
            "--property=Slice,BindsTo,ControlGroup".into(),
        ])?;
        let metadata = std::str::from_utf8(&metadata.stdout).map_err(|_| invalid())?;
        let fields = metadata
            .lines()
            .filter_map(|s| s.split_once('='))
            .collect::<std::collections::HashMap<_, _>>();
        if fields.get("Slice") != Some(&config.resource_slice.as_str())
            || !fields.get("BindsTo").is_some_and(|v| {
                v.split_whitespace()
                    .any(|name| name == config.supervisor_unit)
            })
        {
            return Err(invalid());
        }
        let group = fields
            .get("ControlGroup")
            .filter(|value| !value.is_empty())
            .map(|value| {
                let path = Path::new(value);
                if !sandcrab_core::fs::normal(path)
                    || path.file_name().and_then(|v| v.to_str()) != Some(unit)
                    || path
                        .parent()
                        .and_then(Path::file_name)
                        .and_then(|v| v.to_str())
                        != Some(config.resource_slice.as_str())
                {
                    return Err(invalid());
                }
                Ok(Path::new("/sys/fs/cgroup").join(value.trim_start_matches('/')))
            })
            .transpose()?;
        let stopped = checked(&["systemctl".into(), "stop".into(), unit.into()]);
        if let Some(group) = &group {
            sandcrab_core::unit::empty_group(group)?;
        }
        if stopped.is_err() {
            let load = checked(&[
                "systemctl".into(),
                "show".into(),
                unit.into(),
                "--property=LoadState".into(),
                "--value".into(),
            ])?;
            if load.stdout != b"not-found\n" {
                return Err(invalid());
            }
        }
        let _ = checked(&["systemctl".into(), "reset-failed".into(), unit.into()]);
    }
    let sessions = config.runtime_root.join("sessions");
    for entry in fs::read_dir(&sessions)? {
        let entry = entry?;
        let id = entry.file_name();
        let id = id.to_str().ok_or_else(invalid)?;
        if !crate::uuid(id) {
            return Err(invalid());
        }
        sandcrab_core::fs::protected_directory(&entry.path())?;
        if sandcrab_core::fs::mounted(&entry.path())? {
            checked(&["umount".into(), entry.path().to_string_lossy().into()])?;
        }
        fs::remove_dir(entry.path())?;
    }
    for entry in fs::read_dir(config.runtime_root.join("records"))? {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_str().ok_or_else(invalid)?;
        if !name
            .strip_suffix(".sandbox.json")
            .or_else(|| name.strip_suffix(".json"))
            .is_some_and(crate::uuid)
        {
            return Err(invalid());
        }
        let info = fs::symlink_metadata(entry.path())?;
        if !info.is_file() || info.uid() != 0 || info.mode() & 0o022 != 0 {
            return Err(invalid());
        }
        fs::remove_file(entry.path())?;
    }
    Ok(())
}
