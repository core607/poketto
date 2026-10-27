//! Native command execution under a fixed supervisor-owned sandbox launch description.
use crate::{
    command::{Channel, EndReason},
    mailbox::Mailbox,
};
use sandcrab_core::unit::{Maintenance, checked, empty_group, stop_launch, verify_resource_pool};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    fs, io,
    os::unix::fs::MetadataExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Description {
    pub command: Vec<String>,
    pub unit: String,
    pub cgroup: PathBuf,
    pub resource_pool: PathBuf,
    pub mount: PathBuf,
    pub sync_program: PathBuf,
    pub lease: crate::lease::Description,
    #[serde(default)]
    pub owned_files: Vec<PathBuf>,
}
pub enum InitError {
    Code(&'static str),
    Io(io::Error),
}
impl From<io::Error> for InitError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

pub struct Request<'a> {
    pub execution: &'a str,
    pub command: &'a str,
    pub timeout: Duration,
}
pub struct Runtime {
    description: Description,
    channel: Option<Channel>,
    bridge: Option<Arc<Mailbox>>,
    launch_attempted: bool,
    lease: Option<crate::lease::Lease>,
    maintenance: Option<Maintenance>,
    discard_uninitialized: bool,
    files: Arc<crate::files::Shared>,
}
fn unavailable() -> io::Error {
    io::Error::other("Execution runtime unavailable")
}
pub(crate) fn verify_program(path: &Path) -> io::Result<()> {
    verify_trusted_file(path, true)
}
pub(crate) fn verify_trusted_file(path: &Path, executable: bool) -> io::Result<()> {
    if !path.is_absolute() {
        return Err(unavailable());
    }
    for ancestor in path.ancestors() {
        let metadata = fs::symlink_metadata(ancestor)?;
        if metadata.file_type().is_symlink() || metadata.uid() != 0 || metadata.mode() & 0o022 != 0
        {
            return Err(unavailable());
        }
    }
    if !path.is_file() || executable && fs::metadata(path)?.mode() & 0o111 == 0 {
        return Err(unavailable());
    }
    Ok(())
}
impl Runtime {
    fn validate(description: &Description) -> io::Result<()> {
        if description.command.first().map(String::as_str) != Some("systemd-run")
            || !description.unit.starts_with("poketto-exec-")
            || description.unit.len() > 220
            || !description
                .unit
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b))
            || description.cgroup
                != description
                    .resource_pool
                    .join(format!("{}.service", description.unit))
            || !description.resource_pool.starts_with("/sys/fs/cgroup")
            || !description.mount.is_absolute()
        {
            return Err(unavailable());
        }
        verify_program(&description.sync_program)?;
        Self::verify_pool(description)?;
        Ok(())
    }
    pub fn claim(description: &Description) -> io::Result<crate::lease::Lease> {
        Self::validate(description)?;
        crate::lease::Lease::acquire(
            &description.lease,
            &description.mount,
            &description.unit,
            &description.cgroup,
        )
    }
    pub fn new(
        description: Description,
        bridge: Arc<Mailbox>,
        lease: crate::lease::Lease,
    ) -> io::Result<Self> {
        let runtime = Self {
            files: Arc::new(crate::files::Shared::new(description.clone(), true)),
            description,
            channel: None,
            bridge: Some(bridge),
            launch_attempted: false,
            lease: Some(lease),
            maintenance: None,
            discard_uninitialized: false,
        };
        runtime.files.state.lock().unwrap().prepare_artifacts()?;
        Ok(runtime)
    }
    pub fn pending(description: Description) -> Self {
        Self {
            files: Arc::new(crate::files::Shared::new(description.clone(), false)),
            description,
            channel: None,
            bridge: None,
            launch_attempted: false,
            lease: None,
            maintenance: None,
            discard_uninitialized: false,
        }
    }
    fn verify_pool(description: &Description) -> io::Result<()> {
        verify_resource_pool(&description.resource_pool)
    }
    pub fn files(&self) -> Arc<crate::files::Shared> {
        self.files.clone()
    }
    pub fn execute(
        &mut self,
        request: Request<'_>,
        cancelled: &AtomicBool,
        reason: impl Fn() -> String,
        on_start: impl FnOnce() -> io::Result<()>,
        on_ready: impl FnOnce(),
    ) -> io::Result<Value> {
        let Request {
            execution,
            command,
            timeout,
        } = request;
        let files = self.files.clone();
        let mut file_guard = files.state.lock().unwrap();
        file_guard.usable()?;
        Self::verify_pool(&self.description)?;
        if cancelled.load(Ordering::SeqCst) {
            return Err(io::ErrorKind::Interrupted.into());
        }
        let bridge = self.bridge.clone().ok_or_else(unavailable)?;
        let preparing = Instant::now();
        if let Some(channel) = &mut self.channel
            && channel.process().try_wait()?.is_some()
        {
            self.stop_locked()?;
        }
        let fresh = self.channel.is_none();
        if fresh {
            file_guard.release_command_files()?;
            on_start()?;
            bridge.reset(execution)?;
            let args = &self.description.command;
            if cancelled.load(Ordering::SeqCst) {
                return Err(io::ErrorKind::Interrupted.into());
            }
            self.launch_attempted = true;
            files.unit_active.store(true, Ordering::SeqCst);
            let child = Command::new(&args[0])
                .args(&args[1..])
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()?;
            self.channel = Some(Channel::new(child)?);
        } else {
            sandcrab_core::unit::frozen(&self.description.cgroup, cancelled, || {
                bridge.reset(execution)?;
                file_guard.release_command_files()
            })?;
        }
        drop(file_guard);
        let prepare_ms = preparing.elapsed().as_millis() as u64;
        let running = Instant::now();
        let channel = self.channel.as_mut().ok_or_else(unavailable)?;
        let _was_ready = channel.ready();
        let result = channel.execute(execution, command, timeout, cancelled, on_ready);
        let run_ms = running.elapsed().as_millis() as u64;
        let termination = match result.reason {
            None => "normal".into(),
            Some(EndReason::Cancelled) => reason(),
            Some(EndReason::Timeout) => "timeout".into(),
            Some(EndReason::OutputLimit) => "output_limit".into(),
            Some(EndReason::SandboxFailed) => {
                let status = checked(
                    &[
                        "systemctl".into(),
                        "show".into(),
                        self.description.unit.clone(),
                        "--property=Result".into(),
                        "--value".into(),
                    ],
                    Duration::from_secs(5),
                )?;
                if status.stdout == b"oom-kill\n" {
                    "resource_limit".into()
                } else {
                    "sandbox_failed".into()
                }
            }
        };
        if result.reason == Some(EndReason::SandboxFailed) {
            cancelled.store(true, Ordering::SeqCst);
        }
        let mut file_guard = files.state.lock().unwrap();
        let releasing = Instant::now();
        if result.shell_exited || cancelled.load(Ordering::SeqCst) {
            self.stop_locked()?;
            bridge.reset("")?;
        } else {
            sandcrab_core::unit::frozen(&self.description.cgroup, cancelled, || bridge.reset(""))?;
        }
        file_guard.release_command_files()?;
        let release_ms = releasing.elapsed().as_millis() as u64;
        let mut response = json!({"commit":self.description.lease.commit,"exitCode":result.exit_code,
            "terminationReason":termination,"timedOut":result.reason==Some(EndReason::Timeout),"freshSandbox":fresh,
            "artifacts":{},"artifactErrors":{},"timings":{"roomMs":0,"lockMs":0,"prepareMs":prepare_ms,
                "runMs":run_ms,"readyMs":result.ready_ms,"releaseMs":release_ms,"flushMs":0}});
        if let Some(ms) = result.command_ms {
            response["timings"]["commandMs"] = ms.into();
        }
        for (index, name) in ["stdout", "stderr"].iter().enumerate() {
            let bytes = &result.captured.output[index];
            response[*name] = String::from_utf8_lossy(&bytes[..bytes.len().min(16384)])
                .into_owned()
                .into();
            response[format!("{name}Truncated")] =
                (result.captured.truncated[index] || bytes.len() > 16384).into();
            if bytes.len() > 16384 {
                let artifact = if cancelled.load(Ordering::SeqCst) {
                    Err("ARTIFACT_UNAVAILABLE")
                } else {
                    file_guard
                        .artifacts
                        .as_mut()
                        .ok_or("ARTIFACT_UNAVAILABLE")
                        .and_then(|store| {
                            store.put(
                                &format!("{name}.txt"),
                                bytes,
                                result.captured.truncated[index],
                            )
                        })
                };
                match artifact {
                    Ok(value) => response["artifacts"][*name] = value,
                    Err(code) => response["artifactErrors"][*name] = code.into(),
                }
            }
        }
        let flushing = Instant::now();
        verify_program(&self.description.sync_program)?;
        checked(
            &[
                self.description.sync_program.to_string_lossy().into(),
                self.description.mount.to_string_lossy().into(),
            ],
            Duration::from_secs(15),
        )?;
        response["timings"]["flushMs"] = (flushing.elapsed().as_millis() as u64).into();
        drop(file_guard);
        Ok(response)
    }
    fn stop_locked(&mut self) -> io::Result<()> {
        if !self.launch_attempted {
            return Ok(());
        }
        let stopped = checked(
            &[
                "systemctl".into(),
                "stop".into(),
                self.description.unit.clone(),
            ],
            Duration::from_secs(15),
        );
        if let Err(error) = stopped {
            // A greeted shell may have completed and its successful transient unit unloaded.
            // An unconfirmed startup never takes this path: it can still create its unit later.
            if !self.channel.as_ref().is_some_and(Channel::ready) {
                return Err(error);
            }
            let state = checked(
                &[
                    "systemctl".into(),
                    "show".into(),
                    self.description.unit.clone(),
                    "--property=LoadState".into(),
                    "--value".into(),
                ],
                Duration::from_secs(5),
            )?;
            if state.stdout != b"not-found\n" {
                return Err(error);
            }
        }
        empty_group(&self.description.cgroup)?;
        let _ = checked(
            &[
                "systemctl".into(),
                "reset-failed".into(),
                self.description.unit.clone(),
            ],
            Duration::from_secs(5),
        );
        self.channel.take();
        self.launch_attempted = false;
        self.files.unit_active.store(false, Ordering::SeqCst);
        Ok(())
    }
    pub fn has_unit(&self) -> bool {
        self.launch_attempted
            || self.maintenance.is_some()
            || self.files.helper_active.load(Ordering::SeqCst)
    }
    pub fn stop_for_idle(&mut self) -> io::Result<()> {
        let files = self.files.clone();
        let mut state = files.state.lock().unwrap();
        self.stop_locked()?;
        state.release_command_files()?;
        if let Some(bridge) = &self.bridge {
            bridge.reset("")?;
        }
        Ok(())
    }
    pub fn close(&mut self) -> io::Result<()> {
        let files = self.files.clone();
        let mut file_guard = files.state.lock().unwrap();
        file_guard.stop_helper(&files)?;
        self.stop_maintenance()?;
        self.stop_locked()?;
        file_guard.release_command_files()?;
        file_guard.close_artifacts()?;
        if let Some(bridge) = &self.bridge {
            bridge.close()?;
        }
        if self
            .lease
            .as_ref()
            .is_none_or(crate::lease::Lease::released)
        {
            file_guard.mounted = false;
            return self.remove_owned_files();
        }
        self.sync_copy()?;
        self.remove_owned_files()?;
        if self.discard_uninitialized {
            self.lease
                .as_mut()
                .unwrap()
                .discard_uninitialized(&self.description.lease)?;
        } else {
            self.lease.as_mut().unwrap().release()?;
        }
        file_guard.mounted = false;
        Ok(())
    }
    fn sync_copy(&self) -> io::Result<()> {
        verify_program(&self.description.sync_program)?;
        let path = self.lease.as_ref().ok_or_else(unavailable)?.sync_path();
        checked(
            &[
                self.description.sync_program.to_string_lossy().into(),
                path.to_string_lossy().into(),
            ],
            Duration::from_secs(15),
        )?;
        Ok(())
    }
    fn remove_owned_files(&self) -> io::Result<()> {
        remove_owned_records(&self.description, &self.description.owned_files)
    }
    fn stop_maintenance(&mut self) -> io::Result<()> {
        if let Some(maintenance) = &mut self.maintenance {
            maintenance.stop()?;
            self.maintenance = None;
        }
        Ok(())
    }
    pub fn prepare_dynamic(
        &mut self,
        plan: &crate::provision::Plan,
        factory: &crate::provision::Factory,
        source: Option<&crate::provision::Source>,
        cancelled: &AtomicBool,
        on_start: impl FnOnce() -> io::Result<()>,
    ) -> Result<Arc<Mailbox>, InitError> {
        let initialize = source.is_some();
        Self::validate(&self.description)?;
        if cancelled.load(Ordering::SeqCst) {
            return Err(InitError::Code("SESSION_CLOSED"));
        }
        if initialize {
            factory.pool.create(&self.description.lease)?;
        }
        let ownership = crate::lease::Lease::claim(
            &self.description.lease,
            &self.description.mount,
            &self.description.unit,
            &self.description.cgroup,
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
        self.lease = Some(ownership);
        if matches!(source, Some(crate::provision::Source::Seed)) {
            let owner = &self.description.lease;
            let target = owner.pool_root.join("copies").join(&owner.copy_id);
            if !factory.pool.seeds()?.populate(
                &target,
                &owner.workspace_id,
                &owner.scope,
                &owner.commit,
            )? {
                self.discard_uninitialized = true;
                return Err(InitError::Code("SEED_ABSENT"));
            }
        }

        self.lease
            .as_mut()
            .unwrap()
            .prepare(&self.description.lease)?;
        {
            let mut files = self.files.state.lock().unwrap();
            files.mounted = true;
            files.prepare_artifacts()?;
        }
        let bridge = Arc::new(Mailbox::create(
            &self.description.mount.join("bridge"),
            self.description.lease.exec_gid,
            crate::BRIDGE_OPERATIONS,
        )?);
        self.bridge = Some(bridge.clone());
        if cancelled.load(Ordering::SeqCst) {
            return Err(InitError::Code("SESSION_CLOSED"));
        }
        factory.write_records(plan)?;
        if let Some(crate::provision::Source::Export(export)) = source {
            factory
                .copy_export(&self.description.mount, export, cancelled)
                .map_err(|error| match error.kind() {
                    io::ErrorKind::InvalidData => InitError::Code("INVALID_EXPORT"),
                    io::ErrorKind::Interrupted => InitError::Code("SESSION_CLOSED"),
                    _ => InitError::Io(error),
                })?;
            on_start()?;
            if cancelled.load(Ordering::SeqCst) {
                return Err(InitError::Code("SESSION_CLOSED"));
            }
            let launch = plan.initialize.as_ref().ok_or_else(unavailable)?.clone();
            self.maintenance = Some(Maintenance {
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
            self.maintenance.as_mut().unwrap().child = Some(child);
            let result = crate::raw::collect(
                self.maintenance.as_mut().unwrap().child.as_mut().unwrap(),
                Duration::from_millis(factory.config.init_timeout_millis),
                cancelled,
                || stop_launch(&launch),
            );
            self.stop_maintenance()?;
            if !result?.success() {
                return Err(InitError::Code(if cancelled.load(Ordering::SeqCst) {
                    "SESSION_CLOSED"
                } else {
                    "INITIALIZATION_FAILED"
                }));
            }
            self.sync_copy()?;
            self.lease.as_ref().unwrap().mark_initialized()?;
            if !cancelled.load(Ordering::SeqCst) {
                let owner = &self.description.lease;
                let original = owner.pool_root.join("copies").join(&owner.copy_id);
                let _ = factory.pool.seeds().and_then(|store| {
                    store.capture(&original, &owner.workspace_id, &owner.scope, &owner.commit)
                });
            }
        } else if matches!(source, Some(crate::provision::Source::Seed)) {
            self.sync_copy()?;
            self.lease.as_ref().unwrap().mark_initialized()?;
        }
        if cancelled.load(Ordering::SeqCst) {
            return Err(InitError::Code("SESSION_CLOSED"));
        }
        Ok(bridge)
    }
    pub fn git_commit(&self) -> io::Result<Option<String>> {
        self.lease.as_ref().ok_or_else(unavailable)?.git_commit()
    }
}

pub(crate) fn remove_owned_records(
    description: &Description,
    owned_files: &[PathBuf],
) -> io::Result<()> {
    let records = description
        .mount
        .parent()
        .and_then(Path::parent)
        .ok_or_else(unavailable)?
        .join("records");
    for path in owned_files {
        if path.parent() != Some(records.as_path()) {
            return Err(unavailable());
        }
        let filename = path
            .file_name()
            .and_then(|v| v.to_str())
            .ok_or_else(unavailable)?;
        let id = filename
            .strip_suffix(".sandbox.json")
            .or_else(|| filename.strip_suffix(".json"))
            .ok_or_else(unavailable)?;
        if !crate::uuid(id) {
            return Err(unavailable());
        }
        match fs::symlink_metadata(path) {
            Ok(info) if info.is_file() && info.uid() == 0 && info.mode() & 0o022 == 0 => {
                fs::remove_file(path)?
            }
            Ok(_) => return Err(unavailable()),
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}
