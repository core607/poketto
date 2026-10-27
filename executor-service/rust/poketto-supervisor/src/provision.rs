//! Fixed sandbox launch plans and bounded exports for signed OPEN/ATTACH requests.
use crate::{
    lease::{CopyVolume, Description, Lease},
    pool::Pool,
};
use rustix::fs::{Gid, OFlags, chown, fchown};
use sandcrab_core::{
    mailbox::Mailbox,
    unit::{Maintenance, stop_launch},
};
use sandcrab_daemon::runtime::{self, InitError, Runtime};
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, DirBuilder, OpenOptions},
    io::{self, Read, Write},
    os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
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
    /// The copy the plan mounts.
    pub owner: Description,
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
/// Programs bound read-only into every repository sandbox from the selected release.
const PROGRAMS: [&str; 3] = ["poketto", "poketto-shell", "poketto-syncfs"];
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
        // Cleanup above stays available with damaged tools; serving requires intact ones.
        config.launcher = config.launcher.canonicalize()?;
        sandcrab_launch::trusted_executable(&config.launcher).map_err(io::Error::other)?;
        sandcrab_core::fs::protected_directory(&config.tools_root)?;
        sandcrab_launch::verify_tools(&config.tools_root).map_err(io::Error::other)?;
        let programs = config.launcher.parent().ok_or_else(invalid)?.join("bin");
        sandcrab_launch::verify_programs(&programs, &PROGRAMS).map_err(io::Error::other)?;
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
            exec_uid: owner.exec_uid,
            exec_gid: owner.exec_gid,
            commit: owner.commit.clone(),
            transient_files: crate::baseline::STAGING,
            owned_files: records.iter().map(|(p, _)| p.clone()).collect(),
        };
        Ok(Plan {
            description,
            owner,
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

/// Mount the plan's copy for `runtime`: create and initialize it from a bundle export or a
/// seed, or attach an existing one. Initialization runs in its own sandbox unit.
pub fn prepare(
    runtime: &mut Runtime,
    plan: &Plan,
    factory: &Factory,
    source: Option<&Source>,
    cancelled: &AtomicBool,
    on_start: impl FnOnce() -> io::Result<()>,
) -> Result<Arc<Mailbox>, InitError> {
    let initialize = source.is_some();
    let description = runtime.description().clone();
    let owner = &plan.owner;
    Runtime::validate(&description)?;
    if cancelled.load(Ordering::SeqCst) {
        return Err(InitError::Code("SESSION_CLOSED"));
    }
    if initialize {
        factory.pool.create(owner)?;
    }
    let ownership = Lease::claim(
        owner,
        &description.mount,
        &description.unit,
        &description.cgroup,
        !initialize,
    )
    .map_err(|error| {
        if error.kind() == io::ErrorKind::WouldBlock {
            InitError::Code("COPY_BUSY")
        } else if !initialize {
            InitError::Code("COPY_UNAVAILABLE")
        } else {
            InitError::Io(error)
        }
    })?;
    runtime.volume = Some(Box::new(CopyVolume {
        lease: ownership,
        owner: owner.clone(),
    }));
    if matches!(source, Some(Source::Seed)) {
        let target = owner.pool_root.join("copies").join(&owner.copy_id);
        if !factory.pool.seeds()?.populate(
            &target,
            &owner.workspace_id,
            &owner.scope,
            &owner.commit,
        )? {
            runtime.discard_uninitialized = true;
            return Err(InitError::Code("SEED_ABSENT"));
        }
    }

    copy(runtime).lease.prepare(owner)?;
    {
        let shared = runtime.files();
        let mut files = shared.state.lock().unwrap();
        files.mounted = true;
        files.prepare_artifacts()?;
    }
    let bridge = Arc::new(Mailbox::create(
        &description.mount.join("bridge"),
        owner.exec_gid,
        crate::BRIDGE_OPERATIONS,
    )?);
    runtime.bridge = Some(bridge.clone());
    if cancelled.load(Ordering::SeqCst) {
        return Err(InitError::Code("SESSION_CLOSED"));
    }
    factory.write_records(plan)?;
    if let Some(Source::Export(export)) = source {
        factory
            .copy_export(&description.mount, export, cancelled)
            .map_err(|error| match error.kind() {
                io::ErrorKind::InvalidData => InitError::Code("INVALID_EXPORT"),
                io::ErrorKind::Interrupted => InitError::Code("SESSION_CLOSED"),
                _ => InitError::Io(error),
            })?;
        on_start()?;
        if cancelled.load(Ordering::SeqCst) {
            return Err(InitError::Code("SESSION_CLOSED"));
        }
        let launch = plan.initialize.as_ref().ok_or_else(invalid)?.clone();
        runtime.maintenance = Some(Maintenance {
            launch: launch.clone(),
            child: None,
        });
        let args = &launch.command;
        let child = Command::new(&args[0])
            .args(&args[1..])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;
        runtime.maintenance.as_mut().unwrap().child = Some(child);
        let result = sandcrab_core::raw::collect(
            runtime
                .maintenance
                .as_mut()
                .unwrap()
                .child
                .as_mut()
                .unwrap(),
            Duration::from_millis(factory.config.init_timeout_millis),
            cancelled,
            || stop_launch(&launch),
        );
        runtime.stop_maintenance()?;
        if !result?.success() {
            return Err(InitError::Code(if cancelled.load(Ordering::SeqCst) {
                "SESSION_CLOSED"
            } else {
                "INITIALIZATION_FAILED"
            }));
        }
        runtime.sync_copy()?;
        copy(runtime).lease.mark_initialized()?;
        if !cancelled.load(Ordering::SeqCst) {
            let original = owner.pool_root.join("copies").join(&owner.copy_id);
            let _ = factory.pool.seeds().and_then(|store| {
                store.capture(&original, &owner.workspace_id, &owner.scope, &owner.commit)
            });
        }
    } else if matches!(source, Some(Source::Seed)) {
        runtime.sync_copy()?;
        copy(runtime).lease.mark_initialized()?;
    }
    if cancelled.load(Ordering::SeqCst) {
        return Err(InitError::Code("SESSION_CLOSED"));
    }
    Ok(bridge)
}
/// The copy a prepared runtime owns.
fn copy(runtime: &mut Runtime) -> &mut CopyVolume {
    runtime
        .volume
        .as_mut()
        .and_then(|volume| volume.as_any_mut().downcast_mut())
        .expect("prepare installs the copy before using it")
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

#[cfg(test)]
mod tests {
    use super::*;
    const LEASE: &str = "10000000-0000-4000-8000-000000000001";
    const COPY: &str = "20000000-0000-4000-8000-000000000002";
    const OWNER: &str = "30000000-0000-4000-8000-000000000003";
    const EXPORT: &str = "40000000-0000-4000-8000-000000000004";
    struct Fixture {
        root: PathBuf,
        factory: Factory,
    }
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir()
                .join(format!("poketto-provision-{}", crate::new_uuid().unwrap()));
            for name in ["runtime/records", "runtime/sessions", "exports", "pool"] {
                fs::create_dir_all(root.join(name)).unwrap();
            }
            let uid = rustix::process::getuid().as_raw();
            let gid = rustix::process::getgid().as_raw();
            let config = Config {
                runtime_root: root.join("runtime"),
                export_root: root.join("exports"),
                copy_root: root.join("pool"),
                pool_bytes: 1 << 30,
                disk_bytes: 1 << 20,
                disk_inodes: 1024,
                tools_root: "/opt/poketto-executor/tools".into(),
                launcher: "/opt/poketto-executor/poketto-launch".into(),
                exec_user: "poketto-exec".into(),
                exec_uid: uid,
                exec_gid: gid,
                app_uid: uid,
                app_gid: gid,
                unit_prefix: "poketto-exec-worker-".into(),
                supervisor_unit: "poketto-executor.service".into(),
                resource_slice: "poketto-executor.slice".into(),
                memory_bytes: 536870912,
                tasks_max: 64,
                cpu_quota_percent: 100,
                temporary_bytes: 8388608,
                temporary_inodes: 1024,
                max_bundle_bytes: 64,
                init_timeout_millis: 60000,
            };
            let factory = Factory {
                pool: Pool {
                    root: config.copy_root.clone(),
                    maximum_bytes: config.pool_bytes,
                    copy_bytes: config.disk_bytes,
                    copy_inodes: config.disk_inodes,
                },
                config,
                resource_pool: "/sys/fs/cgroup/poketto-executor.slice".into(),
            };
            Self { root, factory }
        }
        fn owner(&self, scope: &str, commit: &str) -> Description {
            self.factory.owner(COPY, OWNER, OWNER, scope, commit)
        }
        fn mount(&self) -> PathBuf {
            self.root.join("runtime/sessions").join(LEASE)
        }
        fn export(&self, bytes: &[u8]) -> Export {
            fs::write(
                self.root.join("exports").join(format!("{EXPORT}.bundle")),
                bytes,
            )
            .unwrap();
            Export {
                id: EXPORT.into(),
                bytes: bytes.len() as u64,
                digest: format!("{:x}", Sha256::digest(bytes)),
            }
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }
    fn property<'a>(command: &'a [String], name: &str) -> Option<&'a str> {
        command
            .windows(2)
            .find(|pair| pair[0] == "-p" && pair[1].starts_with(name))
            .map(|pair| &pair[1][name.len()..])
    }
    fn readable(policy: &Value) -> Vec<String> {
        policy["filesystem"]["allowRead"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_str().unwrap().to_owned())
            .collect()
    }
    fn text(path: PathBuf) -> String {
        path.to_string_lossy().into_owned()
    }
    #[test]
    fn a_plan_rejects_identifiers_scopes_and_commits_outside_the_lease_contract() {
        let fixture = Fixture::new();
        let commit = "a".repeat(40);
        assert!(
            fixture
                .factory
                .plan(LEASE, fixture.owner("full", &commit), true)
                .is_ok()
        );
        for (lease, scope, commit) in [
            ("not-a-lease", "full", commit.clone()),
            (LEASE, "private", commit.clone()),
            (LEASE, "full", "A".repeat(40)),
            (LEASE, "full", "a".repeat(39)),
        ] {
            assert!(
                fixture
                    .factory
                    .plan(lease, fixture.owner(scope, &commit), true)
                    .is_err(),
                "{lease} {scope} {commit}"
            );
        }
    }
    #[test]
    fn the_shell_unit_is_bounded_bound_to_the_supervisor_and_never_time_limited() {
        let fixture = Fixture::new();
        let plan = fixture
            .factory
            .plan(LEASE, fixture.owner("full", &"a".repeat(40)), false)
            .unwrap();
        let command = &plan.description.command;
        assert_eq!(command[0], "systemd-run");
        assert_eq!(
            command[command.len() - 5..],
            [
                "/usr/bin/env",
                "-i",
                "PATH=/usr/bin:/bin",
                "/opt/poketto-executor/poketto-launch",
                &plan.records[0].0.to_string_lossy(),
            ]
        );
        assert!(plan.description.unit.starts_with("poketto-exec-worker-"));
        assert_eq!(property(command, "User="), Some("poketto-exec"));
        assert_eq!(property(command, "MemorySwapMax="), Some("0"));
        assert_eq!(property(command, "NoNewPrivileges="), Some("yes"));
        assert_eq!(
            property(command, "BindsTo="),
            Some("poketto-executor.service")
        );
        assert_eq!(property(command, "KillMode="), Some("control-group"));
        assert_eq!(property(command, "RuntimeMaxSec="), None);
        let slice = command.iter().position(|v| v == "--slice").unwrap();
        assert_eq!(command[slice + 1], "poketto-executor.slice");
        assert!(plan.initialize.is_none());
        assert_eq!(plan.records.len(), 2);
        assert_eq!(plan.description.transient_files, crate::baseline::STAGING);
        assert_eq!(plan.description.mount, fixture.mount());
        assert_eq!(
            plan.description.owned_files,
            plan.records
                .iter()
                .map(|(p, _)| p.clone())
                .collect::<Vec<_>>()
        );
    }
    #[test]
    fn each_mode_reads_only_its_own_bundle_and_writes_only_its_own_directories() {
        let fixture = Fixture::new();
        let commit = "a".repeat(40);
        let plan = fixture
            .factory
            .plan(LEASE, fixture.owner("full", &commit), true)
            .unwrap();
        let mount = fixture.mount();
        let snapshot = text(mount.join("snapshot.bundle"));
        let shell = &plan.records[1].1;
        let initialize = &plan.records[3].1;
        assert_eq!(plan.records[0].1["mode"], "shell");
        assert_eq!(plan.records[2].1["mode"], "initialize");
        assert!(!readable(shell).contains(&snapshot));
        assert!(readable(initialize).contains(&snapshot));
        for policy in [shell, initialize] {
            assert_eq!(policy["filesystem"]["denyRead"], json!(["/", "/sys"]));
            assert_eq!(policy["filesystem"]["denyWrite"], json!([]));
            assert_eq!(policy["enableWeakerNestedSandbox"], false);
            assert_eq!(
                policy["network"],
                json!({"allowedDomains":[],"deniedDomains":[],"allowAllUnixSockets":false})
            );
            assert_eq!(
                policy["filesystem"]["allowWrite"],
                json!([
                    mount.join("work"),
                    mount.join("home"),
                    mount.join("bridge/requests"),
                    "/tmp"
                ])
            );
            assert!(!readable(policy).contains(&text(mount.clone())));
        }
        let init = plan.initialize.unwrap();
        assert!(property(&init.command, "RuntimeMaxSec=").is_some());
        let (baseline, records) = fixture.factory.baseline_plan(&mount, &commit).unwrap();
        assert!(property(&baseline.command, "RuntimeMaxSec=").is_some());
        assert_eq!(records[0].1["mode"], "baseline");
        let readable = readable(&records[1].1);
        assert!(readable.contains(&text(mount.join("baseline.bundle"))));
        assert!(!readable.contains(&snapshot));
    }
    #[test]
    fn a_baseline_plan_only_targets_a_session_mount_and_a_full_commit() {
        let fixture = Fixture::new();
        let commit = "a".repeat(40);
        let sessions = fixture.root.join("runtime/sessions");
        assert!(
            fixture
                .factory
                .baseline_plan(&fixture.mount(), &commit)
                .is_ok()
        );
        assert!(
            fixture
                .factory
                .baseline_plan(&fixture.mount(), "abc")
                .is_err()
        );
        for mount in [
            fixture.root.join("runtime/records").join(LEASE),
            sessions.join("other"),
        ] {
            assert!(fixture.factory.baseline_plan(&mount, &commit).is_err());
        }
    }
    #[test]
    fn launch_records_are_created_once_and_readable_only_by_the_execution_group() {
        let fixture = Fixture::new();
        let plan = fixture
            .factory
            .plan(LEASE, fixture.owner("full", &"a".repeat(40)), false)
            .unwrap();
        fixture.factory.write_records(&plan).unwrap();
        for (path, value) in &plan.records {
            let info = fs::symlink_metadata(path).unwrap();
            assert_eq!(info.mode() & 0o777, 0o440);
            assert_eq!(info.gid(), fixture.factory.config.exec_gid);
            let written: Value = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
            assert_eq!(&written, value);
        }
        assert!(fixture.factory.write_records(&plan).is_err());
    }
    #[test]
    fn an_export_is_copied_only_when_its_size_digest_and_bound_match() {
        let fixture = Fixture::new();
        let mount = fixture.mount();
        fs::create_dir_all(&mount).unwrap();
        let cancelled = AtomicBool::new(false);
        let export = fixture.export(b"bundle bytes");
        let mut wrong = export.clone();
        wrong.digest = "0".repeat(64);
        let error = fixture
            .factory
            .copy_export(&mount, &wrong, &cancelled)
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        fs::remove_file(mount.join("snapshot.bundle")).unwrap();
        let mut short = export.clone();
        short.bytes -= 1;
        assert!(
            fixture
                .factory
                .copy_export(&mount, &short, &cancelled)
                .is_err()
        );
        let mut large = fixture.export(&[b'x'; 65]);
        assert!(
            fixture
                .factory
                .copy_export(&mount, &large, &cancelled)
                .is_err()
        );
        large.id = "../escape".into();
        assert!(
            fixture
                .factory
                .copy_export(&mount, &large, &cancelled)
                .is_err()
        );
        let export = fixture.export(b"bundle bytes");
        cancelled.store(true, Ordering::SeqCst);
        let error = fixture
            .factory
            .copy_export(&mount, &export, &cancelled)
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::Interrupted);
        fs::remove_file(mount.join("snapshot.bundle")).unwrap();
        cancelled.store(false, Ordering::SeqCst);
        fixture
            .factory
            .copy_baseline(&mount, &export, &cancelled)
            .unwrap();
        let copied = mount.join("baseline.bundle");
        assert_eq!(fs::read(&copied).unwrap(), b"bundle bytes");
        assert_eq!(fs::metadata(&copied).unwrap().mode() & 0o777, 0o440);
        assert!(
            fixture
                .factory
                .copy_baseline(&mount, &export, &cancelled)
                .is_err()
        );
    }
}
