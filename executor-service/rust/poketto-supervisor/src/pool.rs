//! Persistent XFS project allocation. Published copies are never allocation staging.
use crate::lease::{Description, protected_directory, protected_file};
use rustix::fs::{FlockOperation, OFlags, flock};
use serde_json::json;
use std::{
    fs::{self, DirBuilder, File, OpenOptions},
    io::{self, Write},
    os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    time::Duration,
};
pub struct Pool {
    pub root: PathBuf,
    pub maximum_bytes: u64,
    pub copy_bytes: u64,
    pub copy_inodes: u64,
}
fn invalid() -> io::Error {
    io::Error::other("Copy pool unavailable")
}
fn sync(path: &Path) -> io::Result<()> {
    File::open(path)?.sync_all()
}
fn checked(args: &[String]) -> io::Result<std::process::Output> {
    crate::runtime::checked(args, Duration::from_secs(15))
}
fn directory(path: &Path, mode: u32) -> io::Result<()> {
    match DirBuilder::new().mode(mode).create(path) {
        Ok(()) => (),
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => (),
        Err(e) => return Err(e),
    }
    protected_directory(path)?;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
}
impl Pool {
    pub fn new(
        root: PathBuf,
        maximum_bytes: u64,
        copy_bytes: u64,
        copy_inodes: u64,
    ) -> io::Result<Self> {
        if copy_bytes < 1024
            || copy_bytes > maximum_bytes
            || copy_inodes == 0
            || copy_inodes >= 1 << 63
        {
            return Err(invalid());
        }
        let value = Self {
            root,
            maximum_bytes,
            copy_bytes,
            copy_inodes,
        };
        value.verify()?;
        directory(&value.root.join("copies"), 0o750)?;
        directory(&value.root.join("seeds"), 0o700)?;
        {
            let _lock = value.projects_lock()?;
            value.collect_unpublished()?;
        }
        value.seeds()?.collect_staging()?;
        Ok(value)
    }
    pub fn verify(&self) -> io::Result<()> {
        protected_directory(&self.root)?;
        let output = checked(&[
            "findmnt".into(),
            "-J".into(),
            "-T".into(),
            self.root.to_string_lossy().into(),
            "-o".into(),
            "TARGET,FSTYPE,OPTIONS".into(),
        ])?;
        if output.stdout.len() > 65536 {
            return Err(invalid());
        }
        let value = crate::parse(&output.stdout).map_err(|_| invalid())?;
        let mount = &value["filesystems"][0];
        let options = mount["options"]
            .as_str()
            .ok_or_else(invalid)?
            .split(',')
            .collect::<Vec<_>>();
        if mount["target"] != self.root.to_string_lossy().as_ref()
            || mount["fstype"] != "xfs"
            || !(options.contains(&"prjquota") || options.contains(&"pquota"))
            || options.contains(&"pqnoenforce")
        {
            return Err(invalid());
        }
        let stat = rustix::fs::statvfs(&self.root)?;
        if stat
            .f_blocks
            .checked_mul(stat.f_frsize)
            .is_none_or(|n| n > self.maximum_bytes)
        {
            return Err(invalid());
        }
        Ok(())
    }
    pub fn seeds(&self) -> io::Result<crate::seed::Store> {
        crate::seed::Store::new(&self.root)
    }
    fn projects_lock(&self) -> io::Result<File> {
        projects_lock(&self.root)
    }
    fn collect_unpublished(&self) -> io::Result<()> {
        let copies = self.root.join("copies");
        for entry in fs::read_dir(&copies)? {
            let entry = entry?;
            let name = entry.file_name();
            let name = name.to_str().ok_or_else(invalid)?;
            if let Some(id) = name.strip_prefix(".creating-") {
                if !crate::uuid(id) {
                    return Err(invalid());
                }
                protected_directory(&entry.path())?;
                if crate::lease::mounted(&entry.path())? {
                    return Err(invalid());
                }
                fs::remove_dir_all(entry.path())?;
                sync(&copies)?;
            }
        }
        Ok(())
    }
    fn allocate_project(&self) -> io::Result<u64> {
        let counter = self.root.join(".next-project");
        let number = match protected_file(&counter, 32) {
            Ok(raw) => parse_counter(&raw)?,
            Err(e) if e.kind() == io::ErrorKind::NotFound => 1,
            Err(e) => return Err(e),
        };
        let temporary = self.root.join(format!(".project-{}", crate::new_uuid()?));
        let result = (|| {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&temporary)?;
            write!(file, "{}", number + 1)?;
            file.sync_all()?;
            fs::rename(&temporary, &counter)?;
            sync(&self.root)?;
            Ok(number)
        })();
        match fs::remove_file(&temporary) {
            Ok(()) => (),
            Err(e) if e.kind() == io::ErrorKind::NotFound => (),
            Err(e) => return Err(e),
        }
        result
    }
    pub fn discard(&self, description: &Description) -> io::Result<bool> {
        if description.pool_root != self.root
            || description.maximum_bytes != self.maximum_bytes
            || [
                &description.copy_id,
                &description.account_id,
                &description.workspace_id,
            ]
            .iter()
            .any(|id| !crate::uuid(id))
            || !["full", "public"].contains(&description.scope.as_str())
            || !crate::hex(&description.commit, 40)
        {
            return Err(invalid());
        }
        self.verify()?;
        let _projects = self.projects_lock()?;
        let target = self.root.join("copies").join(&description.copy_id);
        match fs::symlink_metadata(&target) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
            Err(error) => return Err(error),
            Ok(_) => (),
        }
        protected_directory(&target)?;
        let metadata = crate::parse(&protected_file(&target.join(".copy.json"), 4096)?)
            .map_err(|_| invalid())?;
        let project = crate::lease::identity(&metadata, description)?;
        let attributes = checked(&[
            "xfs_io".into(),
            "-r".into(),
            "-c".into(),
            "stat".into(),
            target.to_string_lossy().into(),
        ])?;
        crate::lease::project_attributes(
            std::str::from_utf8(&attributes.stdout).map_err(|_| invalid())?,
            project,
        )?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
            .open(target.join(".lease.lock"))?;
        let info = lock.metadata()?;
        if !info.is_file() || info.uid() != 0 || info.nlink() != 1 || info.mode() & 0o077 != 0 {
            return Err(invalid());
        }
        flock(&lock, FlockOperation::NonBlockingLockExclusive)?;
        // The retained copy lock excludes commands; remove_dir_all does not follow child symlinks.
        fs::remove_dir_all(&target)?;
        sync(&self.root.join("copies"))?;
        Ok(true)
    }
    pub fn create(&self, description: &Description) -> io::Result<PathBuf> {
        if description.pool_root != self.root
            || description.maximum_bytes != self.maximum_bytes
            || [
                &description.copy_id,
                &description.account_id,
                &description.workspace_id,
            ]
            .iter()
            .any(|id| !crate::uuid(id))
            || !["full", "public"].contains(&description.scope.as_str())
            || !crate::hex(&description.commit, 40)
        {
            return Err(invalid());
        }
        self.verify()?;
        let _lock = self.projects_lock()?;
        self.collect_unpublished()?;
        let project = self.allocate_project()?;
        let copies = self.root.join("copies");
        let destination = copies.join(&description.copy_id);
        if fs::symlink_metadata(&destination).is_ok() {
            return Err(io::Error::from(io::ErrorKind::AlreadyExists));
        }
        let temporary = copies.join(format!(".creating-{}", description.copy_id));
        DirBuilder::new().mode(0o750).create(&temporary)?;
        let result = (|| {
            checked(&[
                "xfs_quota".into(),
                "-x".into(),
                "-c".into(),
                format!("project -s -p {} {project}", temporary.display()),
                self.root.to_string_lossy().into(),
            ])?;
            let blocks = self.copy_bytes / 1024;
            checked(&[
                "xfs_quota".into(),
                "-x".into(),
                "-c".into(),
                format!(
                    "limit -p bsoft={blocks}k bhard={blocks}k isoft={} ihard={} {project}",
                    self.copy_inodes, self.copy_inodes
                ),
                self.root.to_string_lossy().into(),
            ])?;
            let metadata = json!({"copyId":description.copy_id,"accountId":description.account_id,"workspaceId":description.workspace_id,
                "scope":description.scope,"commit":description.commit,"projectId":project});
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(temporary.join(".copy.json"))?;
            file.write_all(&serde_json::to_vec(&metadata)?)?;
            file.sync_all()?;
            sync(&temporary)?;
            fs::rename(&temporary, &destination)?;
            sync(&copies)?;
            Ok(destination)
        })();
        if temporary.exists() {
            // No command sees an unpublished allocation, and the project lock excludes creators.
            fs::remove_dir_all(&temporary)?;
            sync(&copies)?;
        }
        result
    }
}
pub(crate) fn projects_lock(root: &Path) -> io::Result<File> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
        .open(root.join(".projects.lock"))?;
    let info = file.metadata()?;
    if !info.is_file() || info.uid() != 0 || info.mode() & 0o077 != 0 {
        return Err(invalid());
    }
    flock(&file, FlockOperation::LockExclusive)?;
    Ok(file)
}
fn parse_counter(bytes: &[u8]) -> io::Result<u64> {
    if bytes.is_empty() || bytes.len() > 32 || !bytes.iter().all(u8::is_ascii_digit) {
        return Err(invalid());
    }
    std::str::from_utf8(bytes)
        .ok()
        .and_then(|text| text.parse::<u64>().ok())
        .filter(|n| *n > 0 && *n < 1 << 31)
        .ok_or_else(invalid)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn counter_is_canonical_and_never_wraps() {
        assert_eq!(parse_counter(b"1").unwrap(), 1);
        assert_eq!(parse_counter(b"2147483647").unwrap(), 2147483647);
        for bytes in [
            b"0".as_slice(),
            b"2147483648",
            b"-1",
            b"1\n",
            b"",
            b"true",
            b"99999999999999999999999999999999",
        ] {
            assert!(parse_counter(bytes).is_err());
        }
    }
}
