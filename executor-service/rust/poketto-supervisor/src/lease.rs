//! An attached copy keeps its exclusive lock until containment and unmount succeed.
use rustix::fs::{FlockOperation, Gid, OFlags, Uid, chown, flock};
use sandcrab_core::fs::{mounted, normal, protected_directory, protected_file};
use serde::Deserialize;
use serde_json::Value;
use std::{
    fs::{self, DirBuilder, File, OpenOptions},
    io,
    os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    time::Duration,
};

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Description {
    pub pool_root: PathBuf,
    pub maximum_bytes: u64,
    pub copy_id: String,
    pub account_id: String,
    pub workspace_id: String,
    pub scope: String,
    pub commit: String,
    pub exec_uid: u32,
    pub exec_gid: u32,
}
pub struct Lease {
    source: PathBuf,
    mount: PathBuf,
    lock: Option<File>,
    mounted: bool,
}
/// A claimed copy as the daemon's volume; `owner` identifies it when an uninitialized copy
/// is discarded.
pub struct CopyVolume {
    pub lease: Lease,
    pub owner: Description,
}
impl sandcrab_daemon::runtime::Volume for CopyVolume {
    fn sync_path(&self) -> &Path {
        self.lease.sync_path()
    }
    fn released(&self) -> bool {
        self.lease.released()
    }
    fn release(&mut self, discard: bool) -> io::Result<()> {
        if discard {
            self.lease.discard_uninitialized(&self.owner)
        } else {
            self.lease.release()
        }
    }
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
    fn as_any_mut(&mut self) -> &mut dyn std::any::Any {
        self
    }
}
impl CopyVolume {
    pub fn git_commit(&self) -> io::Result<Option<String>> {
        self.lease.git_commit()
    }
}
fn invalid() -> io::Error {
    io::Error::other("Copy lease unavailable")
}
fn checked(args: &[String]) -> io::Result<std::process::Output> {
    sandcrab_core::unit::checked(args, Duration::from_secs(15))
}
pub fn runtime_lock(root: &Path) -> io::Result<File> {
    protected_directory(root)?;
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
        .open(root.join(".supervisor.lock"))?;
    let info = file.metadata()?;
    if !info.is_file() || info.uid() != 0 || info.mode() & 0o077 != 0 {
        return Err(invalid());
    }
    flock(&file, FlockOperation::NonBlockingLockExclusive)?;
    Ok(file)
}
pub(crate) fn identity(value: &Value, expected: &Description) -> io::Result<u64> {
    let object = value.as_object().ok_or_else(invalid)?;
    let fields = [
        ("copyId", &expected.copy_id),
        ("accountId", &expected.account_id),
        ("workspaceId", &expected.workspace_id),
        ("scope", &expected.scope),
        ("commit", &expected.commit),
    ];
    if object.len() != 6 || fields.iter().any(|(key, text)| value[*key] != **text) {
        return Err(invalid());
    }
    value["projectId"]
        .as_u64()
        .filter(|n| *n > 0 && *n < 1 << 31)
        .ok_or_else(invalid)
}
pub(crate) fn project_attributes(text: &str, project: u64) -> io::Result<()> {
    let attribute = |name: &str| {
        text.lines()
            .filter_map(|line| line.split_once('='))
            .find_map(|(key, value)| (key.trim() == name).then_some(value.trim()))
    };
    let actual = attribute("fsxattr.projid")
        .and_then(|v| v.parse::<u64>().ok())
        .ok_or_else(invalid)?;
    let flags = attribute("fsxattr.xflags")
        .and_then(|v| v.split_whitespace().next())
        .and_then(|v| v.strip_prefix("0x"))
        .and_then(|v| u32::from_str_radix(v, 16).ok())
        .ok_or_else(invalid)?;
    if actual != project || flags & 0x200 == 0 {
        return Err(invalid());
    }
    Ok(())
}
impl Lease {
    pub fn claim(
        description: &Description,
        mount: &Path,
        unit: &str,
        cgroup: &Path,
        require_initialized: bool,
    ) -> io::Result<Self> {
        for id in [
            &description.copy_id,
            &description.account_id,
            &description.workspace_id,
        ] {
            if !crate::uuid(id) {
                return Err(invalid());
            }
        }
        if !["full", "public"].contains(&description.scope.as_str())
            || description.commit.len() != 40
            || !description
                .commit
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            || description.exec_uid == 0
            || description.maximum_bytes == 0
            || !normal(mount)
        {
            return Err(invalid());
        }
        protected_directory(&description.pool_root)?;
        protected_directory(mount.parent().ok_or_else(invalid)?)?;
        let output = checked(&[
            "findmnt".into(),
            "-J".into(),
            "-T".into(),
            description.pool_root.to_string_lossy().into(),
            "-o".into(),
            "TARGET,FSTYPE,OPTIONS".into(),
        ])?;
        if output.stdout.len() > 65536 {
            return Err(invalid());
        }
        let filesystem = crate::parse(&output.stdout).map_err(|_| invalid())?;
        let filesystem = &filesystem["filesystems"][0];
        let options = filesystem["options"]
            .as_str()
            .ok_or_else(invalid)?
            .split(',')
            .collect::<Vec<_>>();
        if filesystem["target"] != description.pool_root.to_string_lossy().as_ref()
            || filesystem["fstype"] != "xfs"
            || !(options.contains(&"prjquota") || options.contains(&"pquota"))
            || options.contains(&"pqnoenforce")
        {
            return Err(invalid());
        }
        let stat = rustix::fs::statvfs(&description.pool_root)?;
        if stat
            .f_blocks
            .checked_mul(stat.f_frsize)
            .is_none_or(|n| n > description.maximum_bytes)
        {
            return Err(invalid());
        }
        let source = description
            .pool_root
            .join("copies")
            .join(&description.copy_id);
        protected_directory(&source)?;
        let metadata = crate::parse(&protected_file(&source.join(".copy.json"), 4096)?)
            .map_err(|_| invalid())?;
        let project = identity(&metadata, description)?;
        if require_initialized && protected_file(&source.join(".initialized"), 16)? != b"1\n" {
            return Err(invalid());
        }
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
            .open(source.join(".lease.lock"))?;
        let info = lock.metadata()?;
        if !info.is_file() || info.uid() != 0 || info.mode() & 0o077 != 0 {
            return Err(invalid());
        }
        flock(&lock, FlockOperation::NonBlockingLockExclusive)?;
        let attributes = checked(&[
            "xfs_io".into(),
            "-r".into(),
            "-c".into(),
            "stat".into(),
            source.to_string_lossy().into(),
        ])?;
        if attributes.stdout.len() > 8192 {
            return Err(invalid());
        }
        project_attributes(
            std::str::from_utf8(&attributes.stdout).map_err(|_| invalid())?,
            project,
        )?;
        // A prior supervisor may have died and released its lock before systemd finished
        // stopping its unit. The lock precedes cleanup; no copy bytes change first.
        if cgroup.exists() {
            checked(&["systemctl".into(), "stop".into(), unit.into()])?;
            sandcrab_core::unit::empty_group(cgroup)?;
            let _ = checked(&["systemctl".into(), "reset-failed".into(), unit.into()]);
        }
        let lease = Self {
            source,
            mount: mount.into(),
            lock: Some(lock),
            mounted: mounted(mount)?,
        };
        if lease.mounted {
            lease.verify_mount()?;
        }
        Ok(lease)
    }
    pub fn prepare(&mut self, description: &Description) -> io::Result<()> {
        let mount = &self.mount;
        if self.mounted {
            self.verify_mount()?;
            checked(&["umount".into(), mount.to_string_lossy().into()])?;
            self.mounted = false;
        }
        if mount.exists() {
            protected_directory(mount)?;
            fs::remove_dir(mount)?;
        }
        for name in ["bootstrap", "bridge", "artifacts", "tmp"] {
            let path = self.source.join(name);
            match fs::symlink_metadata(&path) {
                Ok(info) if info.is_dir() => fs::remove_dir_all(path)?,
                Ok(_) => return Err(invalid()),
                Err(error) if error.kind() == io::ErrorKind::NotFound => (),
                Err(error) => return Err(error),
            }
        }
        for name in crate::baseline::STAGING {
            match fs::remove_file(self.source.join(name)) {
                Ok(()) => (),
                Err(e) if e.kind() == io::ErrorKind::NotFound => (),
                Err(e) => return Err(e),
            }
        }
        cleanup_transfer_stages(&self.source, description.exec_uid)?;
        DirBuilder::new().mode(0o750).create(mount)?;
        chown(mount, None, Some(Gid::from_raw(description.exec_gid)))?;
        fs::set_permissions(mount, fs::Permissions::from_mode(0o750))?;
        checked(&[
            "mount".into(),
            "--bind".into(),
            self.source.to_string_lossy().into(),
            mount.to_string_lossy().into(),
        ])?;
        self.mounted = true;
        chown(mount, None, Some(Gid::from_raw(description.exec_gid)))?;
        fs::set_permissions(mount, fs::Permissions::from_mode(0o750))?;
        DirBuilder::new()
            .mode(0o555)
            .create(mount.join("bootstrap"))?;
        fs::set_permissions(mount.join("bootstrap"), fs::Permissions::from_mode(0o555))?;
        for name in ["work", "home", "tmp"] {
            let path = mount.join(name);
            match fs::create_dir(&path) {
                Ok(()) => (),
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => (),
                Err(e) => return Err(e),
            }
            if !fs::symlink_metadata(&path)?.is_dir() {
                return Err(invalid());
            }
            chown(
                &path,
                Some(Uid::from_raw(description.exec_uid)),
                Some(Gid::from_raw(description.exec_gid)),
            )?;
            fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
        }
        Ok(())
    }
    fn verify_mount(&self) -> io::Result<()> {
        let source = fs::symlink_metadata(&self.source)?;
        let target = fs::symlink_metadata(&self.mount)?;
        if !target.is_dir() || target.dev() != source.dev() || target.ino() != source.ino() {
            return Err(invalid());
        }
        Ok(())
    }
    pub fn sync_path(&self) -> &Path {
        if self.mounted {
            &self.mount
        } else {
            &self.source
        }
    }
    pub fn mark_initialized(&self) -> io::Result<()> {
        use std::io::Write;
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(self.source.join(".initialized"))?;
        file.write_all(b"1\n")?;
        file.sync_all()?;
        File::open(&self.source)?.sync_all()
    }
    pub fn git_commit(&self) -> io::Result<Option<String>> {
        match protected_file(&self.source.join(".git-baseline"), 40) {
            Ok(bytes) => {
                let text = String::from_utf8(bytes).map_err(|_| invalid())?;
                if !crate::hex(&text, 40) {
                    return Err(invalid());
                }
                Ok(Some(text))
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(error),
        }
    }
    pub fn discard_uninitialized(&mut self, description: &Description) -> io::Result<()> {
        if self.lock.is_none() || self.mounted {
            return Err(invalid());
        }
        let _projects = crate::pool::projects_lock(&description.pool_root)?;
        match fs::symlink_metadata(self.source.join(".initialized")) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => (),
            _ => return Err(invalid()),
        }
        let metadata = crate::parse(&protected_file(&self.source.join(".copy.json"), 4096)?)
            .map_err(|_| invalid())?;
        identity(&metadata, description)?;
        protected_directory(&self.source)?;
        fs::remove_dir_all(&self.source)?;
        File::open(self.source.parent().ok_or_else(invalid)?)?.sync_all()?;
        self.lock.take();
        Ok(())
    }
    pub fn released(&self) -> bool {
        self.lock.is_none()
    }
    pub fn release(&mut self) -> io::Result<()> {
        if self.lock.is_none() {
            return Ok(());
        }
        if self.mounted {
            self.verify_mount()?;
            checked(&["umount".into(), self.mount.to_string_lossy().into()])?;
            self.mounted = false;
        }
        if self.mount.exists() {
            protected_directory(&self.mount)?;
            fs::remove_dir(&self.mount)?;
        }
        self.lock.take();
        Ok(())
    }
}

fn cleanup_transfer_stages(source: &Path, exec_uid: u32) -> io::Result<()> {
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        let incoming = name.strip_prefix("incoming-");
        let Some(id) = incoming.or_else(|| name.strip_prefix("outgoing-")) else {
            continue;
        };
        if !crate::uuid(id) {
            continue;
        }
        let info = fs::symlink_metadata(entry.path())?;
        // An incoming stage may have acquired its final owner/mode immediately before a crash.
        let owner = info.uid() == 0 || incoming.is_some() && info.uid() == exec_uid;
        let mode = if incoming.is_some() {
            info.mode() & 0o7066 == 0 && info.mode() & 0o600 == 0o600
        } else {
            info.mode() & 0o7777 == 0o600
        };
        if !info.is_file() || info.nlink() != 1 || !owner || !mode {
            return Err(invalid());
        }
        fs::remove_file(entry.path())?;
    }
    File::open(source)?.sync_all()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn both_project_identity_and_inheritance_are_required() {
        assert!(
            project_attributes(
                "fsxattr.projid = 17\nfsxattr.xflags = 0x200 [proj-inherit]\n",
                17
            )
            .is_ok()
        );
        assert!(project_attributes("fsxattr.projid = 18\nfsxattr.xflags = 0x200\n", 17).is_err());
        assert!(project_attributes("fsxattr.projid = 17\nfsxattr.xflags = 0x0\n", 17).is_err());
        assert!(project_attributes("fsxattr.projid = 17\n", 17).is_err());
    }
    #[test]
    fn copy_identity_is_exact_and_project_id_is_bounded() {
        let expected:Description=serde_json::from_value(json!({"poolRoot":"/var/lib/pool","maximumBytes":1024,
            "copyId":"11111111-1111-1111-1111-111111111111","accountId":"22222222-2222-2222-2222-222222222222",
            "workspaceId":"33333333-3333-3333-3333-333333333333","scope":"full","commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","execUid":101,"execGid":101})).unwrap();
        let mut value = json!({"copyId":expected.copy_id,"accountId":expected.account_id,"workspaceId":expected.workspace_id,
            "scope":"full","commit":expected.commit,"projectId":17});
        assert_eq!(identity(&value, &expected).unwrap(), 17);
        value["scope"] = "public".into();
        assert!(identity(&value, &expected).is_err());
        value["scope"] = "full".into();
        value["projectId"] = true.into();
        assert!(identity(&value, &expected).is_err());
        value["projectId"] = (1_u64 << 31).into();
        assert!(identity(&value, &expected).is_err());
    }
    #[test]
    fn operator_paths_have_no_parent_or_shell_components() {
        assert!(normal(Path::new("/var/lib/fixture/copies")));
        assert!(!normal(Path::new("/var/lib/../etc")));
        assert!(!normal(Path::new("/var/lib/with space")));
        assert!(!normal(Path::new("/var/lib/./copy")));
        assert!(!normal(Path::new("relative")));
    }
}

#[cfg(test)]
mod stage_tests {
    use super::*;
    use std::os::unix::fs::symlink;
    struct Fixture {
        root: PathBuf,
    }
    impl Fixture {
        fn new() -> Self {
            let root =
                std::env::temp_dir().join(format!("poketto-stages-{}", crate::new_uuid().unwrap()));
            DirBuilder::new().mode(0o700).create(&root).unwrap();
            Self { root }
        }
        fn stage(&self, prefix: &str) -> PathBuf {
            let path = self
                .root
                .join(format!("{prefix}-{}", crate::new_uuid().unwrap()));
            OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&path)
                .unwrap();
            path
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.root).unwrap();
        }
    }
    #[test]
    fn stale_transfer_cleanup_preserves_work_receipts_and_rollback_evidence() {
        let fixture = Fixture::new();
        let incoming = fixture.stage("incoming");
        let outgoing = fixture.stage("outgoing");
        let assigned = fixture.stage("incoming");
        chown(&assigned, Some(rustix::fs::Uid::from_raw(42)), None).unwrap();
        fs::set_permissions(&assigned, fs::Permissions::from_mode(0o711)).unwrap();
        fs::write(fixture.root.join("work-file"), b"unsaved").unwrap();
        fs::write(fixture.root.join("incoming-operator-note"), b"retain").unwrap();
        let receipt = fixture
            .root
            .join(format!("move-result-{}.json", crate::new_uuid().unwrap()));
        fs::write(&receipt, b"protected receipt sentinel").unwrap();
        let evidence = fixture
            .root
            .join(format!("move-install-{}", crate::new_uuid().unwrap()));
        DirBuilder::new().mode(0o700).create(&evidence).unwrap();
        fs::write(evidence.join("before"), b"rollback bytes").unwrap();
        cleanup_transfer_stages(&fixture.root, 42).unwrap();
        assert!(!incoming.exists() && !outgoing.exists() && !assigned.exists());
        assert_eq!(
            fs::read(fixture.root.join("work-file")).unwrap(),
            b"unsaved"
        );
        assert_eq!(
            fs::read(fixture.root.join("incoming-operator-note")).unwrap(),
            b"retain"
        );
        assert!(receipt.exists());
        assert_eq!(
            fs::read(evidence.join("before")).unwrap(),
            b"rollback bytes"
        );
    }
    #[test]
    fn stale_cleanup_refuses_unsafe_links_permissions_and_foreign_owners() {
        for kind in ["symlink", "hardlink", "permissions", "owner"] {
            let fixture = Fixture::new();
            let sentinel = fixture.root.join("outside-sentinel");
            fs::write(&sentinel, b"outside").unwrap();
            fs::set_permissions(&sentinel, fs::Permissions::from_mode(0o600)).unwrap();
            let path = fixture
                .root
                .join(format!("outgoing-{}", crate::new_uuid().unwrap()));
            match kind {
                "symlink" => symlink(&sentinel, &path).unwrap(),
                "hardlink" => fs::hard_link(&sentinel, &path).unwrap(),
                _ => {
                    OpenOptions::new()
                        .write(true)
                        .create_new(true)
                        .mode(0o600)
                        .open(&path)
                        .unwrap();
                    if kind == "permissions" {
                        fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).unwrap();
                    } else {
                        chown(&path, Some(rustix::fs::Uid::from_raw(43)), None).unwrap();
                    }
                }
            }
            assert!(
                cleanup_transfer_stages(&fixture.root, 42).is_err(),
                "{kind}"
            );
            assert_eq!(fs::read(&sentinel).unwrap(), b"outside");
        }
    }
}
