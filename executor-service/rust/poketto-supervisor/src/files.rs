//! File operations serialize with shell preparation and release, never with the command wait.
use crate::{provision, runtime};
use rustix::fs::OFlags;
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Write},
    os::unix::fs::OpenOptionsExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::{
        Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

pub struct Shared {
    pub state: Mutex<Files>,
    pub unit_active: AtomicBool,
    pub helper_active: AtomicBool,
}
pub struct Files {
    description: runtime::Description,
    pub mounted: bool,
    maintenance: Option<sandcrab_core::unit::Maintenance>,
    records: Vec<PathBuf>,
    pub fenced: bool,
    pub capture: Option<crate::transfer::Snapshot>,
    pub incoming: Option<crate::transfer::Incoming>,
    pub incoming_move: Option<crate::moves::IncomingMove>,
    pub artifacts: Option<crate::artifacts::Store>,
}
impl Shared {
    pub fn new(description: runtime::Description, mounted: bool) -> Self {
        Self {
            state: Mutex::new(Files {
                description,
                mounted,
                maintenance: None,
                records: Vec::new(),
                fenced: false,
                capture: None,
                incoming: None,
                incoming_move: None,
                artifacts: None,
            }),
            unit_active: AtomicBool::new(false),
            helper_active: AtomicBool::new(false),
        }
    }
}
fn unavailable() -> io::Error {
    io::Error::other("File operation unavailable")
}
impl Files {
    pub fn usable(&self) -> io::Result<()> {
        if !self.mounted || self.fenced || self.maintenance.is_some() {
            Err(unavailable())
        } else {
            Ok(())
        }
    }
    pub fn mount(&self) -> &Path {
        &self.description.mount
    }
    pub fn prepare_artifacts(&mut self) -> io::Result<()> {
        self.artifacts = Some(
            crate::artifacts::Store::new(&self.description.mount, crate::REPOSITORY)
                .map_err(io::Error::other)?,
        );
        Ok(())
    }
    pub fn close_artifacts(&mut self) -> io::Result<()> {
        if let Some(store) = &mut self.artifacts {
            store.close()?;
            self.artifacts = None;
        }
        Ok(())
    }
    pub fn create_artifact(
        &mut self,
        data: &serde_json::Value,
        cancelled: &AtomicBool,
    ) -> Result<serde_json::Value, &'static str> {
        let mut result = None;
        let store = self.artifacts.as_mut().ok_or("SESSION_NOT_READY")?;
        sandcrab_core::unit::frozen(&self.description.cgroup, cancelled, || {
            result = Some(store.capture(&data["path"], &data["mediaType"], cancelled));
            Ok(())
        })
        .map_err(|_| "ARTIFACT_UNAVAILABLE")?;
        result.unwrap()
    }
    pub fn release_command_files(&mut self) -> io::Result<()> {
        if let Some(capture) = &mut self.capture {
            capture.close()?;
            self.capture = None;
        }
        if let Some(incoming) = &mut self.incoming {
            incoming.close()?;
            self.incoming = None;
        }
        if let Some(incoming) = &mut self.incoming_move {
            incoming.close()?;
            self.incoming_move = None;
        }
        Ok(())
    }
    pub fn begin_capture(
        &mut self,
        operation: &str,
        data: &serde_json::Value,
        cancelled: &AtomicBool,
    ) -> Result<serde_json::Value, crate::transfer::Error> {
        let snapshot = quiescent(&self.description.cgroup, cancelled, || {
            crate::transfer::Snapshot::capture(
                &self.description.mount,
                crate::REPOSITORY,
                operation,
                data,
                cancelled,
            )
        })?;
        let manifest = snapshot.manifest();
        self.capture = Some(snapshot);
        Ok(manifest)
    }
    pub fn install(
        &mut self,
        cancelled: &AtomicBool,
    ) -> Result<serde_json::Value, crate::transfer::Error> {
        let description = &self.description;
        let incoming = self.incoming.as_mut().unwrap();
        quiescent(&description.cgroup, cancelled, || {
            incoming.install(
                description.lease.exec_uid,
                description.lease.exec_gid,
                cancelled,
            )
        })
    }
    pub fn check_move(
        &mut self,
        cancelled: &AtomicBool,
    ) -> Result<serde_json::Value, crate::transfer::Error> {
        let incoming = self.incoming_move.as_mut().unwrap();
        quiescent(&self.description.cgroup, cancelled, || {
            incoming.check(cancelled)
        })
    }
    pub fn install_move(
        &mut self,
        cancelled: &AtomicBool,
    ) -> Result<serde_json::Value, crate::transfer::Error> {
        let description = &self.description;
        let incoming = self.incoming_move.as_mut().unwrap();
        quiescent(&description.cgroup, cancelled, || {
            incoming.install(
                description.lease.exec_uid,
                description.lease.exec_gid,
                cancelled,
            )
        })
    }
    fn clean_staging(&self) -> io::Result<()> {
        for name in ["baseline.bundle", ".git-baseline.pending"] {
            match fs::remove_file(self.description.mount.join(name)) {
                Ok(()) => (),
                Err(error) if error.kind() == io::ErrorKind::NotFound => (),
                Err(error) => return Err(error),
            }
        }
        Ok(())
    }
    pub fn stop_helper(&mut self, shared: &Shared) -> io::Result<()> {
        if let Some(maintenance) = &mut self.maintenance {
            maintenance.stop()?;
            self.maintenance = None;
            shared.helper_active.store(false, Ordering::SeqCst);
        }
        runtime::remove_owned_records(&self.description, &self.records)?;
        self.records.clear();
        if self.mounted {
            self.clean_staging()?;
        }
        Ok(())
    }
    pub fn baseline(
        &mut self,
        shared: &Shared,
        factory: &provision::Factory,
        export: &provision::Export,
        commit: &str,
        cancelled: &AtomicBool,
    ) -> Result<(), &'static str> {
        self.usable().map_err(|_| "SESSION_CLOSED")?;
        sandcrab_core::unit::verify_resource_pool(&self.description.resource_pool)
            .map_err(|_| "BASELINE_UNAVAILABLE")?;
        let cgroup = self.description.cgroup.clone();
        let mut action = || self.install_baseline(shared, factory, export, commit, cancelled);
        let result = if shared.unit_active.load(Ordering::SeqCst) {
            sandcrab_core::unit::frozen(&cgroup, cancelled, action)
        } else {
            action()
        };
        if let Err(error) = self.stop_helper(shared) {
            self.fenced = true;
            cancelled.store(true, Ordering::SeqCst);
            return Err(if error.kind() == io::ErrorKind::Interrupted {
                "SESSION_CLOSED"
            } else {
                "BASELINE_UNAVAILABLE"
            });
        }
        match result {
            Ok(Install::Complete) => Ok(()),
            Ok(Install::MissingObjects) => Err("BASELINE_MISSING_OBJECTS"),
            Err(error) if error.kind() == io::ErrorKind::InvalidData => Err("INVALID_EXPORT"),
            Err(_) if cancelled.load(Ordering::SeqCst) => Err("SESSION_CLOSED"),
            Err(_) => Err("BASELINE_UNAVAILABLE"),
        }
    }
    fn install_baseline(
        &mut self,
        shared: &Shared,
        factory: &provision::Factory,
        export: &provision::Export,
        commit: &str,
        cancelled: &AtomicBool,
    ) -> io::Result<Install> {
        if cancelled.load(Ordering::SeqCst) {
            return Err(unavailable());
        }
        self.clean_staging()?;
        factory.copy_baseline(&self.description.mount, export, cancelled)?;
        let (launch, records) = factory.baseline_plan(&self.description.mount, commit)?;
        self.records = records.iter().map(|(path, _)| path.clone()).collect();
        factory.write_launch_records(&records)?;
        if cancelled.load(Ordering::SeqCst) {
            return Err(unavailable());
        }
        self.maintenance = Some(sandcrab_core::unit::Maintenance {
            launch: launch.clone(),
            child: None,
        });
        shared.helper_active.store(true, Ordering::SeqCst);
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
            || sandcrab_core::unit::stop_launch(&launch),
        );
        // Confirm containment before changing root metadata or permitting the parent to continue.
        if self.stop_helper(shared).is_err() {
            self.fenced = true;
            cancelled.store(true, Ordering::SeqCst);
            return Err(unavailable());
        }
        let outcome = result?;
        if !outcome.interrupted && outcome.exit_code == Some(10) {
            return Ok(Install::MissingObjects);
        }
        if !outcome.success() || cancelled.load(Ordering::SeqCst) {
            return Err(unavailable());
        }
        let pending = self.description.mount.join(".git-baseline.pending");
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(OFlags::NOFOLLOW.bits() as i32)
            .open(&pending)?;
        file.write_all(commit.as_bytes())?;
        file.sync_all()?;
        fs::rename(&pending, self.description.mount.join(".git-baseline"))?;
        File::open(&self.description.mount)?.sync_all()?;
        Ok(Install::Complete)
    }
}
fn quiescent<T>(
    cgroup: &Path,
    cancelled: &AtomicBool,
    action: impl FnOnce() -> Result<T, crate::transfer::Error>,
) -> Result<T, crate::transfer::Error> {
    let mut result = None;
    sandcrab_core::unit::frozen(cgroup, cancelled, || {
        result = Some(action());
        Ok(())
    })?;
    result.unwrap()
}
enum Install {
    Complete,
    MissingObjects,
}
