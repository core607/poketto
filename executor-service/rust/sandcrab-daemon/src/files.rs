//! File operations serialize with shell preparation and release, never with the command wait.
use crate::runtime;
use std::{
    any::Any,
    fs, io,
    path::{Path, PathBuf},
    sync::{
        Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

pub struct Shared {
    pub state: Mutex<Files>,
    pub unit_active: AtomicBool,
    pub helper_active: AtomicBool,
}
/// An extension's staged transfer, released with the command's other file state.
pub trait Staged: Send {
    fn close(&mut self) -> io::Result<()>;
    fn as_any_mut(&mut self) -> &mut dyn Any;
}
pub struct Files {
    description: runtime::Description,
    tree: crate::transfer::Tree,
    pub mounted: bool,
    /// A helper unit running on the mounted volume, such as an extension's installer.
    pub maintenance: Option<sandcrab_core::unit::Maintenance>,
    /// Launch records the helper owns; removed when it stops.
    pub records: Vec<PathBuf>,
    pub fenced: bool,
    pub capture: Option<crate::transfer::Snapshot>,
    pub incoming: Option<crate::transfer::Incoming>,
    pub staged: Option<Box<dyn Staged>>,
    pub artifacts: Option<crate::artifacts::Store>,
}
impl Shared {
    pub fn new(
        description: runtime::Description,
        tree: crate::transfer::Tree,
        mounted: bool,
    ) -> Self {
        Self {
            state: Mutex::new(Files {
                description,
                tree,
                mounted,
                maintenance: None,
                records: Vec::new(),
                fenced: false,
                capture: None,
                incoming: None,
                staged: None,
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
    pub fn description(&self) -> &runtime::Description {
        &self.description
    }
    pub fn prepare_artifacts(&mut self) -> io::Result<()> {
        self.artifacts = Some(
            crate::artifacts::Store::new(&self.description.mount, self.tree)
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
        if let Some(staged) = &mut self.staged {
            staged.close()?;
            self.staged = None;
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
                self.tree,
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
            incoming.install(description.exec_uid, description.exec_gid, cancelled)
        })
    }
    /// Remove what an interrupted helper may have left below the mount.
    pub fn clean_staging(&self) -> io::Result<()> {
        for name in self.description.transient_files {
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
}
/// Run `action` while the lease's cgroup is frozen.
pub fn quiescent<T>(
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
