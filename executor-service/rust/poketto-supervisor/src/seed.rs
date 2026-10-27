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

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    const WORKSPACE: &str = "30000000-0000-4000-8000-000000000003";
    /// A root-owned pool root whose ancestors other accounts cannot write; tests run as root.
    struct Root(PathBuf);
    impl Root {
        fn new() -> Self {
            assert!(
                rustix::process::geteuid().is_root(),
                "seed tests need root-owned directories"
            );
            let path = Path::new("/var/lib/poketto-seed-tests").join(crate::new_uuid().unwrap());
            DirBuilder::new()
                .recursive(true)
                .mode(0o755)
                .create(&path)
                .unwrap();
            for directory in [path.parent().unwrap(), &path] {
                fs::set_permissions(directory, fs::Permissions::from_mode(0o755)).unwrap();
            }
            create_private(&path.join("seeds")).unwrap();
            Self(path)
        }
        fn seed(&self, commit: &str, age: Duration) -> PathBuf {
            let parent = self.0.join("seeds").join(WORKSPACE).join("full");
            for directory in [parent.parent().unwrap(), &parent] {
                create_private(directory).unwrap();
            }
            let path = parent.join(commit);
            create_private(&path).unwrap();
            let time = SystemTime::now() - age;
            File::open(&path)
                .unwrap()
                .set_times(FileTimes::new().set_accessed(time).set_modified(time))
                .unwrap();
            path
        }
    }
    impl Drop for Root {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    fn commit(digit: char) -> String {
        digit.to_string().repeat(40)
    }
    #[test]
    fn a_claim_validates_its_key_and_refreshes_an_existing_seed() {
        let root = Root::new();
        let store = Store::new(&root.0).unwrap();
        assert!(
            store
                .claim(WORKSPACE, "full", &commit('a'))
                .unwrap()
                .is_none()
        );
        for (workspace, scope, commit) in [
            ("not-a-workspace", "full", commit('a')),
            (WORKSPACE, "private", commit('a')),
            (WORKSPACE, "full", "A".repeat(40)),
        ] {
            assert!(store.claim(workspace, scope, &commit).is_err());
        }
        let path = root.seed(&commit('a'), GRACE * 2);
        let before = fs::metadata(&path).unwrap().modified().unwrap();
        assert_eq!(
            store.claim(WORKSPACE, "full", &commit('a')).unwrap(),
            Some(path.clone())
        );
        assert!(fs::metadata(&path).unwrap().modified().unwrap() > before);
    }
    #[test]
    fn a_seed_other_accounts_could_read_is_refused() {
        let root = Root::new();
        let store = Store::new(&root.0).unwrap();
        let path = root.seed(&commit('a'), Duration::ZERO);
        fs::set_permissions(&path, fs::Permissions::from_mode(0o750)).unwrap();
        assert!(store.claim(WORKSPACE, "full", &commit('a')).is_err());
        assert!(
            store
                .populate(&root.0.join("copy"), WORKSPACE, "full", &commit('a'))
                .is_err()
        );
    }
    #[test]
    fn pruning_keeps_the_two_newest_and_every_seed_inside_the_grace_period() {
        let root = Root::new();
        let store = Store::new(&root.0).unwrap();
        let newest = root.seed(&commit('a'), GRACE / 4);
        let second = root.seed(&commit('b'), GRACE / 2);
        let third = root.seed(&commit('c'), GRACE * 3 / 4);
        let expired = root.seed(&commit('d'), GRACE * 2);
        let oldest = root.seed(&commit('e'), GRACE * 3);
        let parent = newest.parent().unwrap().to_owned();
        store.prune(&parent, SystemTime::now()).unwrap();
        assert!(newest.exists() && second.exists() && third.exists());
        assert!(!expired.exists() && !oldest.exists());
        store
            .prune(&parent, SystemTime::now() + GRACE * 10)
            .unwrap();
        assert!(newest.exists() && second.exists());
        assert!(!third.exists());
    }
    #[test]
    fn interrupted_captures_are_collected_and_unexpected_entries_stop_collection() {
        let root = Root::new();
        let store = Store::new(&root.0).unwrap();
        let seed = root.seed(&commit('a'), Duration::ZERO);
        let stage = seed
            .parent()
            .unwrap()
            .join(format!(".creating-{}", crate::new_uuid().unwrap()));
        create_private(&stage).unwrap();
        fs::write(stage.join("partial"), b"incomplete").unwrap();
        store.collect_staging().unwrap();
        assert!(!stage.exists());
        assert!(seed.exists());
        create_private(&seed.parent().unwrap().join("unexpected")).unwrap();
        assert!(store.collect_staging().is_err());
    }
    #[test]
    fn population_needs_a_protected_target_and_an_existing_seed() {
        let root = Root::new();
        let store = Store::new(&root.0).unwrap();
        let target = root.0.join("copy");
        create_private(&target).unwrap();
        assert!(
            !store
                .populate(&target, WORKSPACE, "full", &commit('a'))
                .unwrap()
        );
        assert!(
            store
                .populate(Path::new("relative/copy"), WORKSPACE, "full", &commit('a'))
                .is_err()
        );
        assert!(
            store
                .populate(&root.0.join("absent"), WORKSPACE, "full", &commit('a'))
                .is_err()
        );
    }
    #[test]
    fn capturing_an_existing_seed_leaves_it_unchanged() {
        let root = Root::new();
        let store = Store::new(&root.0).unwrap();
        let seed = root.seed(&commit('a'), Duration::ZERO);
        fs::write(seed.join("marker"), b"original").unwrap();
        let source = root.0.join("copy");
        create_private(&source).unwrap();
        store
            .capture(&source, WORKSPACE, "full", &commit('a'))
            .unwrap();
        assert_eq!(fs::read(seed.join("marker")).unwrap(), b"original");
        assert_eq!(fs::read_dir(seed.parent().unwrap()).unwrap().count(), 1);
    }
}
