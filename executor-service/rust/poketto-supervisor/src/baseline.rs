//! Git baselines: an acknowledged commit is fetched from a bundle by a sandboxed installer,
//! and only its completion records `.git-baseline` in the copy.
use crate::{lease::CopyVolume, provision};
use rustix::fs::OFlags;
use sandcrab_core::unit::{Maintenance, frozen, stop_launch, verify_resource_pool};
use sandcrab_daemon::{
    files::{Files, Shared},
    runtime::Runtime,
};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Write},
    os::unix::fs::OpenOptionsExt,
    path::Path,
    process::{Command, Stdio},
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};

/// Files below the mount that an interrupted installation may leave behind.
pub const STAGING: &[&str] = &["baseline.bundle", ".git-baseline.pending"];
/// The installer's exit status when the bundle lacks objects the commit needs.
const MISSING_OBJECTS: i32 = 10;

#[derive(Debug, PartialEq, Eq)]
enum Install {
    Complete,
    MissingObjects,
}
fn unavailable() -> io::Error {
    io::Error::other("Baseline unavailable")
}

/// Install `commit` from the verified export, freezing a running command meanwhile.
pub fn install(
    files: &mut Files,
    shared: &Shared,
    factory: &provision::Factory,
    export: &provision::Export,
    commit: &str,
    cancelled: &AtomicBool,
) -> Result<(), &'static str> {
    files.usable().map_err(|_| "SESSION_CLOSED")?;
    verify_resource_pool(&files.description().resource_pool).map_err(|_| "BASELINE_UNAVAILABLE")?;
    let cgroup = files.description().cgroup.clone();
    let mut action = || run_installer(files, shared, factory, export, commit, cancelled);
    let result = if shared.unit_active.load(Ordering::SeqCst) {
        frozen(&cgroup, cancelled, action)
    } else {
        action()
    };
    if let Err(error) = files.stop_helper(shared) {
        files.fenced = true;
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
fn run_installer(
    files: &mut Files,
    shared: &Shared,
    factory: &provision::Factory,
    export: &provision::Export,
    commit: &str,
    cancelled: &AtomicBool,
) -> io::Result<Install> {
    if cancelled.load(Ordering::SeqCst) {
        return Err(unavailable());
    }
    let mount = files.mount().to_owned();
    files.clean_staging()?;
    factory.copy_baseline(&mount, export, cancelled)?;
    let (launch, records) = factory.baseline_plan(&mount, commit)?;
    files.records = records.iter().map(|(path, _)| path.clone()).collect();
    factory.write_launch_records(&records)?;
    if cancelled.load(Ordering::SeqCst) {
        return Err(unavailable());
    }
    files.maintenance = Some(Maintenance {
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
    files.maintenance.as_mut().unwrap().child = Some(child);
    let result = sandcrab_core::raw::collect(
        files.maintenance.as_mut().unwrap().child.as_mut().unwrap(),
        Duration::from_millis(factory.config.init_timeout_millis),
        cancelled,
        || stop_launch(&launch),
    );
    // Confirm containment before changing root metadata or permitting the parent to continue.
    if files.stop_helper(shared).is_err() {
        files.fenced = true;
        cancelled.store(true, Ordering::SeqCst);
        return Err(unavailable());
    }
    let outcome = result?;
    if !outcome.interrupted && outcome.exit_code == Some(MISSING_OBJECTS) {
        return Ok(Install::MissingObjects);
    }
    if !outcome.success() || cancelled.load(Ordering::SeqCst) {
        return Err(unavailable());
    }
    record(&mount, commit)?;
    Ok(Install::Complete)
}
/// Durably replace `.git-baseline` with `commit` through a private pending file.
fn record(mount: &Path, commit: &str) -> io::Result<()> {
    let pending = mount.join(".git-baseline.pending");
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .custom_flags(OFlags::NOFOLLOW.bits() as i32)
        .open(&pending)?;
    file.write_all(commit.as_bytes())?;
    file.sync_all()?;
    fs::rename(&pending, mount.join(".git-baseline"))?;
    File::open(mount)?.sync_all()
}
/// The commit last installed in the runtime's copy, if a baseline was ever installed.
pub fn installed(runtime: &Runtime) -> io::Result<Option<String>> {
    runtime
        .volume
        .as_ref()
        .and_then(|volume| volume.as_any().downcast_ref::<CopyVolume>())
        .ok_or_else(unavailable)?
        .git_commit()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    struct Directory(std::path::PathBuf);
    impl Directory {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "poketto-baseline-{}",
                sandcrab_core::new_uuid().unwrap()
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }
    }
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    #[test]
    fn recording_replaces_the_baseline_privately_and_leaves_no_pending_file() {
        let directory = Directory::new();
        fs::write(directory.0.join(".git-baseline"), "b".repeat(40)).unwrap();
        record(&directory.0, &"a".repeat(40)).unwrap();
        let path = directory.0.join(".git-baseline");
        assert_eq!(fs::read_to_string(&path).unwrap(), "a".repeat(40));
        assert_eq!(
            fs::metadata(&path).unwrap().permissions().mode() & 0o777,
            0o600
        );
        assert!(!directory.0.join(".git-baseline.pending").exists());
    }
    #[test]
    fn a_leftover_pending_file_is_never_followed_or_reused() {
        let directory = Directory::new();
        let outside = Directory::new();
        std::os::unix::fs::symlink(
            outside.0.join("target"),
            directory.0.join(".git-baseline.pending"),
        )
        .unwrap();
        assert!(record(&directory.0, &"a".repeat(40)).is_err());
        assert!(!outside.0.join("target").exists());
        assert!(!directory.0.join(".git-baseline").exists());
    }
    #[test]
    fn staging_names_cover_every_file_an_installation_creates() {
        assert!(STAGING.contains(&"baseline.bundle"));
        assert!(STAGING.contains(&".git-baseline.pending"));
        assert!(!STAGING.contains(&".git-baseline"));
    }
}
