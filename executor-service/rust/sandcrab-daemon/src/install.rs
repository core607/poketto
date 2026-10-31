//! The protected verification key belongs to the signed request protocol.
use ed25519_dalek::{VerifyingKey, pkcs8::DecodePublicKey};
use rustix::fs::OFlags;
use sandcrab_core::fs::protected_directory;
use std::{
    fs::OpenOptions,
    io::{self, Read},
    os::unix::fs::{MetadataExt, OpenOptionsExt},
    path::Path,
};

fn invalid() -> io::Error {
    io::Error::other("Invalid daemon installation")
}

/// The Ed25519 request key from a root-owned file in a protected directory.
pub fn load_key(path: &Path) -> io::Result<VerifyingKey> {
    protected_directory(path.parent().ok_or_else(invalid)?)?;
    let file = OpenOptions::new()
        .read(true)
        .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
        .open(path)?;
    let info = file.metadata()?;
    if !info.is_file()
        || info.uid() != 0
        || info.mode() & 0o022 != 0
        || info.len() > 16384
        || info.nlink() != 1
    {
        return Err(invalid());
    }
    let mut bytes = Vec::new();
    file.take(16385).read_to_end(&mut bytes)?;
    if bytes.len() > 16384 {
        return Err(invalid());
    }
    let pem = std::str::from_utf8(&bytes).map_err(|_| invalid())?;
    VerifyingKey::from_public_key_pem(pem).map_err(|_| invalid())
}
