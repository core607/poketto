#![forbid(unsafe_code)]

use rustix::fs::{Mode, OFlags, open, syncfs};
use std::path::Path;

fn main() {
    let mut arguments = std::env::args_os().skip(1);
    let Some(path) = arguments.next() else {
        std::process::exit(2);
    };
    if arguments.next().is_some() {
        std::process::exit(2);
    }
    let result = open(
        Path::new(&path),
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )
    .and_then(syncfs);
    if let Err(error) = result {
        eprintln!("Filesystem synchronization failed: {error}");
        std::process::exit(1);
    }
}
