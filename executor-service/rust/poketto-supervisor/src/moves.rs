//! Host-planned moves keep local fingerprints, rollback evidence and protected completion receipts.
use base64::{Engine, engine::general_purpose::STANDARD};
use rustix::fs::OFlags;
use sandcrab_core::transfer::{self, Error, Incoming};
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    io::{self, Read, Write},
    os::unix::fs::{DirBuilderExt, FileExt, MetadataExt, OpenOptionsExt},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
};
type Result<T> = std::result::Result<T, Error>;
fn rejected() -> Error {
    Error {
        reason: "CAPTURE_UNAVAILABLE",
        capacity: false,
        fatal: false,
    }
}
fn fatal() -> Error {
    Error {
        fatal: true,
        ..rejected()
    }
}
fn cancelled(flag: &AtomicBool) -> Result<()> {
    if flag.load(Ordering::SeqCst) {
        Err(rejected())
    } else {
        Ok(())
    }
}
fn sha(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
fn path(value: &str) -> Result<()> {
    transfer::selected(crate::REPOSITORY, &json!([value]), &json!([])).map(|_| ())
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Original {
    sha256: String,
    bytes: u64,
    optional: bool,
}
pub struct IncomingMove {
    transfer: Incoming,
    root: PathBuf,
    local: Option<LocalMove>,
    receipt: Option<PathBuf>,
    result: Option<Value>,
}
impl IncomingMove {
    pub fn begin(root: &Path, data: &Value) -> Result<Self> {
        Ok(Self {
            transfer: Incoming::move_plan(root, crate::REPOSITORY, data)?,
            root: root.into(),
            local: None,
            receipt: None,
            result: None,
        })
    }
    pub fn id(&self) -> &str {
        &self.transfer.id
    }
    pub fn received(&self) -> u64 {
        self.transfer.received
    }
    pub fn append(&mut self, data: &Value) -> Result<()> {
        self.transfer.append(data)
    }
    fn prepare(&mut self, flag: &AtomicBool) -> Result<()> {
        if self.local.is_some() {
            return Ok(());
        }
        let bytes = self.transfer.plan_bytes(flag)?;
        let value = crate::parse(&bytes).map_err(|_| rejected())?;
        let object = value.as_object().ok_or_else(rejected)?;
        let keys = [
            "operationId",
            "source",
            "destination",
            "originals",
            "relocations",
            "replacements",
        ];
        if object.len() != keys.len() || !keys.iter().all(|k| object.contains_key(*k)) {
            return Err(rejected());
        }
        let operation = value["operationId"]
            .as_str()
            .filter(|s| crate::uuid(s))
            .ok_or_else(rejected)?;
        let local = LocalMove::new(&self.root, &value)?;
        self.receipt = Some(self.root.join(format!("move-result-{operation}.json")));
        self.local = Some(local);
        Ok(())
    }
    fn receipt(&self) -> Result<Option<Value>> {
        let mut file = match OpenOptions::new()
            .read(true)
            .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
            .open(self.receipt.as_ref().ok_or_else(rejected)?)
        {
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            other => other?,
        };
        let info = file.metadata()?;
        if !info.is_file()
            || info.nlink() != 1
            || info.len() != 4096
            || info.uid() != rustix::process::geteuid().as_raw()
            || info.mode() & 0o077 != 0
        {
            return Err(rejected());
        }
        let mut bytes = Vec::new();
        Read::by_ref(&mut file).take(4097).read_to_end(&mut bytes)?;
        let value = crate::parse(&bytes).map_err(|_| rejected())?;
        let object = value.as_object().ok_or_else(rejected)?;
        if object.len() != 3
            || !["sha256", "state", "result"]
                .iter()
                .all(|k| object.contains_key(*k))
            || value["sha256"] != self.transfer.plan_digest()
            || !["prepared", "complete"]
                .iter()
                .any(|v| value["state"] == *v)
            || !value["result"].is_object()
        {
            return Err(rejected());
        }
        Ok(Some(value))
    }
    fn receipt_bytes(&self, state: &str, result: Value) -> Result<Vec<u8>> {
        let mut bytes = serde_json::to_vec(
            &json!({"sha256":self.transfer.plan_digest(),"state":state,"result":result}),
        )
        .map_err(|_| rejected())?;
        if bytes.len() > 4096 {
            return Err(rejected());
        }
        bytes.resize(4096, b' ');
        Ok(bytes)
    }
    fn reserve(&self) -> Result<()> {
        if self.receipt()?.is_some() {
            return Ok(());
        }
        let mut count = 0;
        for entry in fs::read_dir(&self.root)? {
            let name = entry?.file_name();
            let name = name.to_string_lossy();
            if name.starts_with("move-result-") && name.ends_with(".json") {
                count += 1;
            }
        }
        if count >= 256 {
            return Err(rejected());
        }
        let path = self.receipt.as_ref().ok_or_else(rejected)?;
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(OFlags::NOFOLLOW.bits() as i32)
            .open(path)?;
        if let Err(error) = file.write_all(&self.receipt_bytes("prepared", json!({}))?) {
            let _ = fs::remove_file(path);
            return Err(error.into());
        }
        Ok(())
    }
    pub fn check(&mut self, flag: &AtomicBool) -> Result<Value> {
        self.prepare(flag)?;
        if self.receipt()?.is_some_and(|v| v["state"] == "complete") {
            return Ok(json!({"ready":true}));
        }
        let local = self.local.as_ref().unwrap();
        local.check(flag)?;
        let space = rustix::fs::statvfs(&self.root)?;
        let required = local
            .replacements
            .values()
            .map(|v| (v.len() as u64).div_ceil(space.f_frsize) * space.f_frsize)
            .sum::<u64>()
            + space.f_frsize;
        if required > space.f_bavail.saturating_mul(space.f_frsize)
            || local.replacements.len() as u64 + 1 > space.f_favail
        {
            return Err(rejected());
        }
        self.reserve()?;
        Ok(json!({"ready":true}))
    }
    pub fn install(&mut self, uid: u32, gid: u32, flag: &AtomicBool) -> Result<Value> {
        if let Some(result) = &self.result {
            return Ok(result.clone());
        }
        self.prepare(flag)?;
        if let Some(receipt) = self.receipt()?
            && receipt["state"] == "complete"
        {
            let result = receipt["result"].clone();
            self.result = Some(result.clone());
            return Ok(result);
        }
        self.reserve()?;
        let result = self.local.as_mut().unwrap().install(uid, gid, flag)?;
        let completed = self.receipt_bytes("complete", result.clone())?;
        let completed = (|| -> io::Result<()> {
            let file = OpenOptions::new()
                .write(true)
                .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
                .open(self.receipt.as_ref().unwrap())?;
            let info = file.metadata()?;
            if !info.is_file()
                || info.nlink() != 1
                || info.len() != 4096
                || info.uid() != rustix::process::geteuid().as_raw()
                || info.mode() & 0o077 != 0
            {
                return Err(io::Error::other("Move receipt unavailable"));
            }
            file.write_all_at(&completed, 0)?;
            file.sync_all()
        })();
        if completed.is_err() {
            return Err(fatal());
        }
        self.result = Some(result.clone());
        Ok(result)
    }
    pub fn close(&mut self) -> io::Result<()> {
        self.transfer.close()?;
        if let Some(path) = &self.receipt
            && self
                .receipt()
                .ok()
                .flatten()
                .is_some_and(|v| v["state"] == "prepared")
        {
            // A damaged receipt cannot authorize replay or prevent lease containment.
            let _ = fs::remove_file(path);
        }
        self.local = None;
        Ok(())
    }
}
struct LocalMove {
    root: PathBuf,
    source: String,
    destination: String,
    originals: BTreeMap<String, Original>,
    relocations: BTreeMap<String, String>,
    replacements: BTreeMap<String, Vec<u8>>,
    paths: BTreeSet<String>,
    result: Option<Value>,
}
type Fingerprints = BTreeMap<String, (Option<String>, u32)>;
impl LocalMove {
    fn new(root: &Path, value: &Value) -> Result<Self> {
        let source = value["source"].as_str().ok_or_else(rejected)?.to_owned();
        let destination = value["destination"]
            .as_str()
            .ok_or_else(rejected)?
            .to_owned();
        path(&source)?;
        path(&destination)?;
        for name in [&source, &destination] {
            if ["public", "private"].contains(&name.to_lowercase().as_str())
                || name.split('/').next().unwrap().to_lowercase() == ".poketto"
            {
                return Err(rejected());
            }
        }
        if source == destination
            || source.starts_with(&(destination.clone() + "/"))
            || destination.starts_with(&(source.clone() + "/"))
        {
            return Err(rejected());
        }
        let originals: BTreeMap<String, Original> =
            serde_json::from_value(value["originals"].clone()).map_err(|_| rejected())?;
        let relocations: BTreeMap<String, String> =
            serde_json::from_value(value["relocations"].clone()).map_err(|_| rejected())?;
        let encoded = value["replacements"].as_object().ok_or_else(rejected)?;
        let mut replacements = BTreeMap::new();
        let mut total = 0;
        for (path, content) in encoded {
            let content = STANDARD
                .decode(content.as_str().ok_or_else(rejected)?)
                .map_err(|_| rejected())?;
            total += content.len();
            if total > 32 * 1024 * 1024 {
                return Err(rejected());
            }
            replacements.insert(path.clone(), content);
        }
        let paths: BTreeSet<String> = originals
            .keys()
            .chain(relocations.keys())
            .chain(relocations.values())
            .chain(replacements.keys())
            .cloned()
            .collect();
        if relocations.is_empty() || paths.len() > 16384 {
            return Err(rejected());
        }
        for name in &paths {
            path(name)?;
        }
        if originals
            .values()
            .any(|v| v.bytes > 1024 * 1024 * 1024 || !crate::hex(&v.sha256, 64))
        {
            return Err(rejected());
        }
        let mut destinations = BTreeSet::new();
        for (before, after) in &relocations {
            if !originals.contains_key(before)
                || !(before == &source || before.starts_with(&(source.clone() + "/")))
                || after != &(destination.clone() + &before[source.len()..])
                || originals.contains_key(after)
                || !destinations.insert(after.clone())
            {
                return Err(rejected());
            }
        }
        if !originals
            .keys()
            .all(|v| relocations.contains_key(v) || replacements.contains_key(v))
            || !replacements
                .keys()
                .all(|v| originals.contains_key(v) || destinations.contains(v))
        {
            return Err(rejected());
        }
        for name in replacements.keys() {
            let original = relocations
                .iter()
                .find_map(|(from, to)| (to == name).then_some(from))
                .unwrap_or(name);
            if originals.get(original).ok_or_else(rejected)?.optional {
                return Err(rejected());
            }
        }
        Ok(Self {
            root: root.into(),
            source,
            destination,
            originals,
            relocations,
            replacements,
            paths,
            result: None,
        })
    }
    fn repository(&self) -> Result<PathBuf> {
        crate::REPOSITORY.open(&self.root)?;
        Ok(self.root.join("work/repository"))
    }
    fn inspect(&self, path: &str, flag: &AtomicBool) -> Result<(Option<String>, u32)> {
        let mut parent = crate::REPOSITORY.open(&self.root)?;
        let parts: Vec<_> = path.split('/').collect();
        for part in &parts[..parts.len() - 1] {
            parent = match transfer::dir(&parent, part) {
                Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok((None, 0o600)),
                other => other?,
            };
        }
        transfer::current(&parent, parts.last().unwrap(), flag)
    }
    fn exists(&self, path: &str) -> Result<bool> {
        let mut parent = crate::REPOSITORY.open(&self.root)?;
        let parts: Vec<_> = path.split('/').collect();
        for part in &parts[..parts.len() - 1] {
            parent = match transfer::dir(&parent, part) {
                Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(false),
                other => other?,
            };
        }
        match fs::symlink_metadata(self.root.join("work/repository").join(path)) {
            Ok(_) => Ok(true),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(e) => Err(e.into()),
        }
    }
    fn source_entries(&self, repository: &Path, flag: &AtomicBool) -> Result<BTreeSet<String>> {
        if !self.exists(&self.source)? {
            return Ok(BTreeSet::new());
        }
        let mut pending = vec![repository.join(&self.source)];
        let mut files = BTreeSet::new();
        let mut count = 0;
        while let Some(path) = pending.pop() {
            cancelled(flag)?;
            count += 1;
            if count > 32768 {
                return Err(rejected());
            }
            let info = fs::symlink_metadata(&path)?;
            let relative = path
                .strip_prefix(repository)
                .map_err(|_| rejected())?
                .to_str()
                .ok_or_else(rejected)?
                .to_owned();
            if info.is_dir() {
                let prefix = relative + "/";
                if !self
                    .relocations
                    .range(prefix.clone()..)
                    .next()
                    .is_some_and(|(name, _)| name.starts_with(&prefix))
                {
                    return Err(rejected());
                }
                for entry in fs::read_dir(&path)? {
                    pending.push(entry?.path());
                }
            } else if info.is_file() && info.nlink() == 1 {
                files.insert(relative);
            } else {
                return Err(rejected());
            }
        }
        Ok(files)
    }
    fn check(&self, flag: &AtomicBool) -> Result<Fingerprints> {
        let repository = self.repository()?;
        if self.exists(&self.destination)? {
            return Err(rejected());
        }
        let mut actual = BTreeMap::new();
        for path in &self.paths {
            cancelled(flag)?;
            let value = self.inspect(path, flag)?;
            match self.originals.get(path) {
                None if value.0.is_some() => return Err(rejected()),
                Some(expected)
                    if value.0.as_ref() != Some(&expected.sha256)
                        && !(expected.optional && value.0.is_none()) =>
                {
                    return Err(rejected());
                }
                _ => (),
            }
            actual.insert(path.clone(), value);
        }
        if !self
            .source_entries(&repository, flag)?
            .iter()
            .all(|v| self.relocations.contains_key(v))
        {
            return Err(rejected());
        }
        Ok(actual)
    }
    fn already_applied(&self, flag: &AtomicBool) -> Result<bool> {
        let mut after: BTreeMap<&str, Option<&str>> =
            self.paths.iter().map(|v| (v.as_str(), None)).collect();
        let mut optional = BTreeSet::new();
        for (source, target) in &self.relocations {
            after.insert(target, Some(&self.originals[source].sha256));
            if self.originals[source].optional {
                optional.insert(target.as_str());
            }
        }
        let digests: BTreeMap<&str, String> = self
            .replacements
            .iter()
            .map(|(path, bytes)| (path.as_str(), sha(bytes)))
            .collect();
        for (path, digest) in &digests {
            after.insert(path, Some(digest));
            optional.remove(path);
        }
        for (path, expected) in after {
            let (current, _) = self.inspect(path, flag)?;
            if current.as_deref() != expected && !(current.is_none() && optional.contains(path)) {
                return Ok(false);
            }
        }
        Ok(true)
    }
    fn install(&mut self, uid: u32, gid: u32, flag: &AtomicBool) -> Result<Value> {
        self.install_with(uid, gid, flag, |from, to| fs::rename(from, to))
    }
    fn install_with(
        &mut self,
        uid: u32,
        gid: u32,
        flag: &AtomicBool,
        mut rename: impl FnMut(&Path, &Path) -> io::Result<()>,
    ) -> Result<Value> {
        if let Some(result) = &self.result {
            return Ok(result.clone());
        }
        let repository = self.repository()?;
        if self.already_applied(flag)? {
            let result = json!({"changedPaths":self.paths.len(),"alreadyApplied":true});
            self.result = Some(result.clone());
            return Ok(result);
        }
        let actual = self.check(flag)?;
        let stage = self
            .root
            .join(format!("move-install-{}", crate::new_uuid()?));
        fs::DirBuilder::new().mode(0o700).create(&stage)?;
        let mut backups = BTreeMap::new();
        let mut incoming = BTreeMap::new();
        let mut created = Vec::new();
        let mut moved = false;
        let operation = (|| -> Result<Value> {
            for (index, (path, bytes)) in self.replacements.iter().enumerate() {
                cancelled(flag)?;
                let original = self
                    .relocations
                    .iter()
                    .find_map(|(from, to)| (to == path).then_some(from))
                    .unwrap_or(path);
                if actual[original].0.is_some() {
                    let backup = stage.join(format!("before-{index}"));
                    fs::hard_link(repository.join(original), &backup)?;
                    backups.insert(original.clone(), backup);
                }
                let temporary = stage.join(format!("text-{index}"));
                let mut file = OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .mode(0o600)
                    .open(&temporary)?;
                file.write_all(bytes)?;
                rustix::fs::fchmod(
                    &file,
                    rustix::fs::Mode::from_bits_truncate(actual[original].1),
                )?;
                rustix::fs::fchown(
                    &file,
                    Some(rustix::fs::Uid::from_raw(uid)),
                    Some(rustix::fs::Gid::from_raw(gid)),
                )?;
                incoming.insert(path.clone(), temporary);
            }
            if self.exists(&self.source)? {
                ensure_parent(&repository, &self.destination, uid, gid, &mut created)?;
                cancelled(flag)?;
                rename(
                    &repository.join(&self.source),
                    &repository.join(&self.destination),
                )?;
                moved = true;
            }
            for (path, temporary) in &incoming {
                cancelled(flag)?;
                ensure_parent(&repository, path, uid, gid, &mut created)?;
                rename(temporary, &repository.join(path))?;
            }
            Ok(json!({"changedPaths":self.paths.len(),"alreadyApplied":false}))
        })();
        if let Err(error) = operation {
            let rollback = (|| -> io::Result<()> {
                if moved {
                    rename(
                        &repository.join(&self.destination),
                        &repository.join(&self.source),
                    )?;
                }
                for (path, backup) in &backups {
                    rename(backup, &repository.join(path))?;
                }
                for parent in created.iter().rev() {
                    match fs::remove_dir(parent) {
                        Ok(()) => (),
                        Err(e) if e.kind() == io::ErrorKind::NotFound => (),
                        Err(e) => return Err(e),
                    }
                }
                Ok(())
            })();
            if rollback.is_err() {
                return Err(fatal());
            }
            fs::remove_dir_all(&stage)?;
            return Err(error);
        }
        let result = operation.unwrap();
        if fs::remove_dir_all(&stage).is_err() {
            return Err(fatal());
        }
        self.result = Some(result.clone());
        Ok(result)
    }
}
fn ensure_parent(
    repository: &Path,
    path: &str,
    uid: u32,
    gid: u32,
    created: &mut Vec<PathBuf>,
) -> Result<()> {
    let mut parent = repository.to_owned();
    let parts: Vec<_> = path.split('/').collect();
    for part in &parts[..parts.len() - 1] {
        parent.push(part);
        match fs::symlink_metadata(&parent) {
            Ok(info) if info.is_dir() => (),
            Ok(_) => return Err(rejected()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                fs::DirBuilder::new().mode(0o700).create(&parent)?;
                created.push(parent.clone());
                rustix::fs::chown(
                    &parent,
                    Some(rustix::fs::Uid::from_raw(uid)),
                    Some(rustix::fs::Gid::from_raw(gid)),
                )?;
            }
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

impl sandcrab_daemon::files::Staged for IncomingMove {
    fn close(&mut self) -> io::Result<()> {
        IncomingMove::close(self)
    }
    fn as_any_mut(&mut self) -> &mut dyn std::any::Any {
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Fixture {
        root: PathBuf,
        repository: PathBuf,
        before: BTreeMap<String, Vec<u8>>,
    }
    impl Fixture {
        fn new() -> Self {
            let root =
                std::env::temp_dir().join(format!("poketto-move-{}", crate::new_uuid().unwrap()));
            let repository = root.join("work/repository");
            let before = BTreeMap::from([
                ("private/box/note.md".into(), b"[ref](../ref.md)".to_vec()),
                ("private/box/raw.bin".into(), vec![0, 255, 1]),
                ("private/ref.md".into(), b"[note](box/note.md)".to_vec()),
                (".poketto/assets.json".into(), b"before-index".to_vec()),
            ]);
            for (path, bytes) in &before {
                let path = repository.join(path);
                fs::create_dir_all(path.parent().unwrap()).unwrap();
                fs::write(path, bytes).unwrap();
            }
            fs::write(repository.join("private/scratch.md"), b"unselected").unwrap();
            Self {
                root,
                repository,
                before,
            }
        }
        fn plan(&self, destination: &str) -> Value {
            let mut originals = serde_json::Map::new();
            let mut relocations = serde_json::Map::new();
            for (path, bytes) in &self.before {
                originals.insert(
                    path.clone(),
                    json!({"sha256":sha(bytes),"bytes":bytes.len(),"optional":false}),
                );
                if let Some(suffix) = path.strip_prefix("private/box/") {
                    relocations.insert(path.clone(), json!(format!("{destination}/{suffix}")));
                }
            }
            originals.insert(
                "private/box/paper.pdf".into(),
                json!({"sha256":sha(b"PDF"),"bytes":3,"optional":true}),
            );
            relocations.insert(
                "private/box/paper.pdf".into(),
                json!(format!("{destination}/paper.pdf")),
            );
            let replacements = BTreeMap::from([
                (
                    format!("{destination}/note.md"),
                    STANDARD.encode(b"[ref](../../ref.md)"),
                ),
                (
                    "private/ref.md".into(),
                    STANDARD.encode(b"[note](deeper/box/note.md)"),
                ),
                (
                    ".poketto/assets.json".into(),
                    STANDARD.encode(b"after-index"),
                ),
            ]);
            json!({"operationId":crate::new_uuid().unwrap(),"source":"private/box","destination":destination,"originals":originals,"relocations":relocations,"replacements":replacements})
        }
        fn identity(&self) -> (u32, u32) {
            let info = fs::metadata(&self.root).unwrap();
            (info.uid(), info.gid())
        }
        fn incoming(&self, raw: &[u8]) -> IncomingMove {
            let mut incoming =
                IncomingMove::begin(&self.root, &json!({"bytes":raw.len(),"sha256":sha(raw)}))
                    .unwrap();
            for (index, part) in raw.chunks(65536).enumerate() {
                incoming
                    .append(&json!({"offset":index*65536,"data":STANDARD.encode(part)}))
                    .unwrap();
            }
            incoming
        }
        fn restored(&self) {
            for (path, bytes) in &self.before {
                assert_eq!(fs::read(self.repository.join(path)).unwrap(), *bytes);
                assert_eq!(fs::metadata(self.repository.join(path)).unwrap().nlink(), 1);
            }
            assert_eq!(
                fs::read(self.repository.join("private/scratch.md")).unwrap(),
                b"unselected"
            );
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.root).unwrap();
        }
    }
    #[test]
    fn directory_move_preserves_binary_inode_optional_absence_and_unselected_work() {
        let fixture = Fixture::new();
        let (uid, gid) = fixture.identity();
        let inode = fs::metadata(fixture.repository.join("private/box/raw.bin"))
            .unwrap()
            .ino();
        let value = fixture.plan("private/deeper/box");
        let mut local = LocalMove::new(&fixture.root, &value).unwrap();
        let flag = AtomicBool::new(false);
        local.check(&flag).unwrap();
        assert_eq!(
            local.install(uid, gid, &flag).unwrap()["alreadyApplied"],
            false
        );
        assert!(!fixture.repository.join("private/box").exists());
        assert_eq!(
            fs::metadata(fixture.repository.join("private/deeper/box/raw.bin"))
                .unwrap()
                .ino(),
            inode
        );
        assert!(
            !fixture
                .repository
                .join("private/deeper/box/paper.pdf")
                .exists()
        );
        assert_eq!(
            fs::read(fixture.repository.join("private/deeper/box/note.md")).unwrap(),
            b"[ref](../../ref.md)"
        );
        assert_eq!(
            fs::read(fixture.repository.join(".poketto/assets.json")).unwrap(),
            b"after-index"
        );
        assert_eq!(
            fs::read(fixture.repository.join("private/scratch.md")).unwrap(),
            b"unselected"
        );
        assert_eq!(
            LocalMove::new(&fixture.root, &value)
                .unwrap()
                .install(uid, gid, &flag)
                .unwrap()["alreadyApplied"],
            true
        );
        assert!(!fs::read_dir(&fixture.root).unwrap().any(|v| {
            v.unwrap()
                .file_name()
                .to_string_lossy()
                .starts_with("move-install-")
        }));
    }
    #[test]
    fn changed_files_and_untracked_directories_refuse_before_installation() {
        let fixture = Fixture::new();
        let mut local = LocalMove::new(&fixture.root, &fixture.plan("private/deeper/box")).unwrap();
        let flag = AtomicBool::new(false);
        let (uid, gid) = fixture.identity();
        local.check(&flag).unwrap();
        fs::write(fixture.repository.join("private/box/note.md"), b"new edit").unwrap();
        assert!(local.install(uid, gid, &flag).is_err());
        assert_eq!(
            fs::read(fixture.repository.join("private/box/note.md")).unwrap(),
            b"new edit"
        );
        fs::write(
            fixture.repository.join("private/box/note.md"),
            &fixture.before["private/box/note.md"],
        )
        .unwrap();
        fs::create_dir(fixture.repository.join("private/box/untracked")).unwrap();
        assert!(local.check(&flag).is_err());
        assert!(!fixture.repository.join("private/deeper").exists());
    }
    #[test]
    fn installation_error_restores_directory_references_and_link_counts() {
        let fixture = Fixture::new();
        let flag = AtomicBool::new(false);
        let (uid, gid) = fixture.identity();
        let mut local = LocalMove::new(&fixture.root, &fixture.plan("private/deeper/box")).unwrap();
        let mut failed = false;
        let result = local.install_with(uid, gid, &flag, |from, to| {
            if to == fixture.repository.join("private/ref.md") && !failed {
                failed = true;
                Err(io::Error::other("Injected final replacement failure"))
            } else {
                fs::rename(from, to)
            }
        });
        assert!(failed && result.is_err() && !result.err().unwrap().fatal);
        fixture.restored();
        assert!(!fixture.repository.join("private/deeper").exists());
    }
    #[test]
    fn failed_rollback_retains_private_evidence_and_returns_fatal_failure() {
        let fixture = Fixture::new();
        let flag = AtomicBool::new(false);
        let (uid, gid) = fixture.identity();
        let mut local = LocalMove::new(&fixture.root, &fixture.plan("private/deeper/box")).unwrap();
        let result = local.install_with(uid, gid, &flag, |from, to| {
            if to == fixture.repository.join(".poketto/assets.json")
                || to == fixture.repository.join("private/box")
            {
                Err(io::Error::other(
                    "Injected replacement and rollback failures",
                ))
            } else {
                fs::rename(from, to)
            }
        });
        assert!(result.err().unwrap().fatal);
        let retained: Vec<_> = fs::read_dir(&fixture.root)
            .unwrap()
            .map(|v| v.unwrap().path())
            .filter(|v| {
                v.file_name()
                    .unwrap()
                    .to_string_lossy()
                    .starts_with("move-install-")
            })
            .collect();
        assert_eq!(retained.len(), 1);
        assert_eq!(fs::metadata(&retained[0]).unwrap().mode() & 0o777, 0o700);
        assert!(
            fs::read_dir(&retained[0])
                .unwrap()
                .any(|v| fs::read(v.unwrap().path()).unwrap()
                    == fixture.before["private/box/note.md"])
        );
    }
    #[test]
    fn completed_receipt_preserves_later_edits_and_rejects_same_id_with_different_plan() {
        let fixture = Fixture::new();
        let flag = AtomicBool::new(false);
        let (uid, gid) = fixture.identity();
        let value = fixture.plan("private/deeper/box");
        let raw = serde_json::to_vec(&value).unwrap();
        let mut incoming = fixture.incoming(&raw);
        incoming.check(&flag).unwrap();
        let installed = incoming.install(uid, gid, &flag).unwrap();
        incoming.close().unwrap();
        let note = fixture.repository.join("private/deeper/box/note.md");
        fs::write(&note, b"edit after acknowledgement").unwrap();
        let mut repeated = fixture.incoming(&raw);
        assert_eq!(repeated.check(&flag).unwrap()["ready"], true);
        assert_eq!(repeated.install(uid, gid, &flag).unwrap(), installed);
        assert_eq!(fs::read(&note).unwrap(), b"edit after acknowledgement");
        repeated.close().unwrap();
        let mut changed = raw.clone();
        changed.push(b' ');
        let mut reused = fixture.incoming(&changed);
        assert!(reused.check(&flag).is_err());
        let receipt = fixture.root.join(format!(
            "move-result-{}.json",
            value["operationId"].as_str().unwrap()
        ));
        assert_eq!(fs::metadata(receipt).unwrap().mode() & 0o777, 0o600);
    }
    #[test]
    fn case_only_move_materialized_optional_media_and_plan_rejections() {
        let fixture = Fixture::new();
        fs::write(fixture.repository.join("private/box/paper.pdf"), b"PDF").unwrap();
        let inode = fs::metadata(fixture.repository.join("private/box/paper.pdf"))
            .unwrap()
            .ino();
        let (uid, gid) = fixture.identity();
        let value = fixture.plan("private/BOX");
        LocalMove::new(&fixture.root, &value)
            .unwrap()
            .install(uid, gid, &AtomicBool::new(false))
            .unwrap();
        assert_eq!(
            fs::metadata(fixture.repository.join("private/BOX/paper.pdf"))
                .unwrap()
                .ino(),
            inode
        );
        for (field, text) in [
            ("source", "private"),
            ("source", ".poketto/data"),
            ("destination", "private/box/sub"),
        ] {
            let mut bad = value.clone();
            bad[field] = text.into();
            assert!(LocalMove::new(&fixture.root, &bad).is_err());
        }
        let raw = b"{\"operationId\":\"a\",\"operationId\":\"b\"}";
        let mut duplicate = fixture.incoming(raw);
        assert!(duplicate.check(&AtomicBool::new(false)).is_err());
    }
}
