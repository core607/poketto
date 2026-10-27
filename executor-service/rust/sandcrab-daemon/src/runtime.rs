//! Native command execution under a fixed supervisor-owned sandbox launch description.
use sandcrab_core::{
    command::{Channel, EndReason},
    mailbox::Mailbox,
    unit::{Maintenance, checked, empty_group, verify_resource_pool},
};
use serde_json::{Value, json};
use std::{
    any::Any,
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
/// The fixed shell unit of one lease and the files the daemon owns for it.
#[derive(Clone)]
pub struct Description {
    pub command: Vec<String>,
    pub unit: String,
    pub cgroup: PathBuf,
    pub resource_pool: PathBuf,
    pub mount: PathBuf,
    pub sync_program: PathBuf,
    /// The execution account that owns installed files.
    pub exec_uid: u32,
    pub exec_gid: u32,
    /// The revision label EXEC results report.
    pub commit: String,
    /// Names below the mount that a stopped helper may leave behind and that are removed.
    pub transient_files: &'static [&'static str],
    pub owned_files: Vec<PathBuf>,
}
/// Exclusive ownership of the storage mounted for one lease.
pub trait Volume: Send {
    /// The path that filesystem synchronization flushes.
    fn sync_path(&self) -> &Path;
    /// Ownership has already been given up.
    fn released(&self) -> bool;
    /// Unmount and give up ownership; `discard` also removes storage whose initialization
    /// never completed.
    fn release(&mut self, discard: bool) -> io::Result<()>;
    fn as_any(&self) -> &dyn Any;
    fn as_any_mut(&mut self) -> &mut dyn Any;
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
/// A lease's volume, host-call mailbox, helper units and persistent shell. An extension
/// provisions the volume and mailbox before the first command.
pub struct Runtime {
    description: Description,
    channel: Option<Channel>,
    pub bridge: Option<Arc<Mailbox>>,
    launch_attempted: bool,
    pub volume: Option<Box<dyn Volume>>,
    pub maintenance: Option<Maintenance>,
    pub discard_uninitialized: bool,
    files: Arc<crate::files::Shared>,
}
fn unavailable() -> io::Error {
    io::Error::other("Execution runtime unavailable")
}
/// A protected executable, as the launcher requires of its tools.
pub fn verify_program(path: &Path) -> io::Result<()> {
    sandcrab_launch::trusted_executable(path).map_err(io::Error::other)
}
impl Runtime {
    pub fn validate(description: &Description) -> io::Result<()> {
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
    /// A runtime whose volume, mailbox and records the extension has yet to prepare.
    pub fn pending(description: Description, tree: crate::transfer::Tree) -> Self {
        Self {
            files: Arc::new(crate::files::Shared::new(description.clone(), tree, false)),
            description,
            channel: None,
            bridge: None,
            launch_attempted: false,
            volume: None,
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
    pub fn description(&self) -> &Description {
        &self.description
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
        let mut response = json!({"commit":self.description.commit,"exitCode":result.exit_code,
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
        if self.volume.as_deref().is_none_or(Volume::released) {
            file_guard.mounted = false;
            return self.remove_owned_files();
        }
        self.sync_copy()?;
        self.remove_owned_files()?;
        self.volume
            .as_mut()
            .unwrap()
            .release(self.discard_uninitialized)?;
        file_guard.mounted = false;
        Ok(())
    }
    /// Flush the volume through the protected synchronization helper.
    pub fn sync_copy(&self) -> io::Result<()> {
        verify_program(&self.description.sync_program)?;
        let path = self.volume.as_ref().ok_or_else(unavailable)?.sync_path();
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
    pub fn stop_maintenance(&mut self) -> io::Result<()> {
        if let Some(maintenance) = &mut self.maintenance {
            maintenance.stop()?;
            self.maintenance = None;
        }
        Ok(())
    }
}

pub fn remove_owned_records(description: &Description, owned_files: &[PathBuf]) -> io::Result<()> {
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
