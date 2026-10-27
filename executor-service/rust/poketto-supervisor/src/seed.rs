//! Supervisor-private pristine snapshots. Live copies never depend on a seed's lifetime.
use rustix::fs::{FlockOperation, OFlags, flock};
use sandcrab_core::fs::{normal, protected_directory};
use std::{
    fs::{self, DirBuilder, File, FileTimes, OpenOptions},
    io,
    os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt},
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
const GRACE: Duration = Duration::from_secs(600);
pub struct Store {
    root: PathBuf,
    base: PathBuf,
}
fn invalid() -> io::Error {
    io::Error::other("Seed unavailable")
}
fn private_directory(path: &Path) -> io::Result<()> {
    protected_directory(path)?;
    if fs::symlink_metadata(path)?.mode() & 0o077 != 0 {
        return Err(invalid());
    }
    Ok(())
}
fn create_private(path: &Path) -> io::Result<()> {
    match DirBuilder::new().mode(0o700).create(path) {
        Ok(()) => (),
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => (),
        Err(e) => return Err(e),
    }
    private_directory(path)
}
fn sync(path: &Path) -> io::Result<()> {
    File::open(path)?.sync_all()
}
impl Store {
    pub fn new(root: &Path) -> io::Result<Self> {
        protected_directory(root)?;
        let base = root.join("seeds");
        private_directory(&base)?;
        Ok(Self {
            root: root.into(),
            base,
        })
    }
    fn lock(&self) -> io::Result<File> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
            .open(self.root.join(".seeds.lock"))?;
        let info = file.metadata()?;
        if !info.is_file() || info.uid() != 0 || info.mode() & 0o077 != 0 {
            return Err(invalid());
        }
        flock(&file, FlockOperation::LockExclusive)?;
        Ok(file)
    }
    fn path(&self, workspace: &str, scope: &str, commit: &str) -> io::Result<PathBuf> {
        if !crate::uuid(workspace)
            || !["full", "public"].contains(&scope)
            || !crate::hex(commit, 40)
        {
            return Err(invalid());
        }
        Ok(self.base.join(workspace).join(scope).join(commit))
    }
    fn existing(&self, workspace: &str, scope: &str, commit: &str) -> io::Result<Option<PathBuf>> {
        let path = self.path(workspace, scope, commit)?;
        for directory in [
            self.base.join(workspace),
            self.base.join(workspace).join(scope),
            path.clone(),
        ] {
            match private_directory(&directory) {
                Ok(()) => (),
                Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
                Err(e) => return Err(e),
            }
        }
        Ok(Some(path))
    }
    pub fn claim(&self, workspace: &str, scope: &str, commit: &str) -> io::Result<Option<PathBuf>> {
        let _lock = self.lock()?;
        let seed = self.existing(workspace, scope, commit)?;
        if let Some(path) = &seed {
            let directory = OpenOptions::new()
                .read(true)
                .custom_flags((OFlags::DIRECTORY | OFlags::NOFOLLOW).bits() as i32)
                .open(path)?;
            let now = SystemTime::now();
            directory.set_times(FileTimes::new().set_accessed(now).set_modified(now))?;
        }
        Ok(seed)
    }
    fn reflink(sources: &Path, target: &Path) -> io::Result<()> {
        sandcrab_core::unit::checked(
            &[
                "cp".into(),
                "-a".into(),
                "--reflink=auto".into(),
                "--".into(),
                sources.join("snapshot.bundle").to_string_lossy().into(),
                sources.join("work").to_string_lossy().into(),
                target.to_string_lossy().into(),
            ],
            Duration::from_secs(60),
        )?;
        Ok(())
    }
    pub fn populate(
        &self,
        target: &Path,
        workspace: &str,
        scope: &str,
        commit: &str,
    ) -> io::Result<bool> {
        if !normal(target) {
            return Err(invalid());
        }
        protected_directory(target)?;
        let Some(seed) = self.claim(workspace, scope, commit)? else {
            return Ok(false);
        };
        // The refreshed time excludes pruning during this bounded copy operation.
        Self::reflink(&seed, target)?;
        sync(target)?;
        Ok(true)
    }
    pub fn capture(
        &self,
        source: &Path,
        workspace: &str,
        scope: &str,
        commit: &str,
    ) -> io::Result<()> {
        protected_directory(source)?;
        let target = self.path(workspace, scope, commit)?;
        if self.existing(workspace, scope, commit)?.is_some() {
            return Ok(());
        }
        let parent = target.parent().ok_or_else(invalid)?;
        create_private(parent.parent().ok_or_else(invalid)?)?;
        create_private(parent)?;
        let stage = parent.join(format!(".creating-{}", crate::new_uuid()?));
        create_private(&stage)?;
        let result = (|| {
            // No command has run in source. Copy outside the publication lock.
            Self::reflink(source, &stage)?;
            sync(&stage)?;
            let _lock = self.lock()?;
            if self.existing(workspace, scope, commit)?.is_none() {
                fs::rename(&stage, &target)?;
                sync(parent)?;
            }
            self.prune(parent, SystemTime::now())
        })();
        if stage.exists() {
            private_directory(&stage)?;
            fs::remove_dir_all(&stage)?;
            sync(parent)?;
        }
        result
    }
    fn prune(&self, parent: &Path, now: SystemTime) -> io::Result<()> {
        private_directory(parent)?;
        let mut seeds = Vec::new();
        for entry in fs::read_dir(parent)? {
            let entry = entry?;
            let name = entry.file_name();
            let name = name.to_str().ok_or_else(invalid)?;
            if crate::hex(name, 40) {
                private_directory(&entry.path())?;
                seeds.push((entry.metadata()?.modified()?, entry.path()));
            }
        }
        seeds.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        let horizon = now.checked_sub(GRACE).unwrap_or(UNIX_EPOCH);
        let mut changed = false;
        for (modified, path) in seeds.into_iter().skip(2) {
            if modified < horizon {
                fs::remove_dir_all(path)?;
                changed = true;
            }
        }
        if changed {
            sync(parent)?;
        }
        Ok(())
    }
    pub fn collect_staging(&self) -> io::Result<()> {
        let _lock = self.lock()?;
        for workspace in fs::read_dir(&self.base)? {
            let workspace = workspace?;
            let name = workspace.file_name();
            let name = name.to_str().ok_or_else(invalid)?;
            if !crate::uuid(name) {
                return Err(invalid());
            }
            private_directory(&workspace.path())?;
            for scope in fs::read_dir(workspace.path())? {
                let scope = scope?;
                let name = scope.file_name();
                let name = name.to_str().ok_or_else(invalid)?;
                if !["full", "public"].contains(&name) {
                    return Err(invalid());
                }
                private_directory(&scope.path())?;
                let mut changed = false;
                for entry in fs::read_dir(scope.path())? {
                    let entry = entry?;
                    let name = entry.file_name();
                    let name = name.to_str().ok_or_else(invalid)?;
                    if let Some(id) = name.strip_prefix(".creating-") {
                        if !crate::uuid(id) {
                            return Err(invalid());
                        }
                        private_directory(&entry.path())?;
                        fs::remove_dir_all(entry.path())?;
                        changed = true;
                    } else if !crate::hex(name, 40) {
                        return Err(invalid());
                    }
                }
                if changed {
                    sync(&scope.path())?;
                }
            }
        }
        Ok(())
    }
}
