use crate::MAX_FRAME;
use rustix::event::{PollFd, PollFlags, Timespec, poll};
use rustix::fs::OFlags;
use serde_json::{Value, json};
use std::fmt;
use std::fs::{self, File, OpenOptions, TryLockError};
use std::io::{self, Read, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::Path;
use std::thread;
use std::time::{Duration, Instant};

#[derive(Debug)]
pub enum Error {
    Unavailable,
    Io(io::Error),
    Json(serde_json::Error),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Unavailable => write!(f, "BridgeUnavailable:None"),
            Self::Io(error) => match error.raw_os_error() {
                Some(errno) => write!(f, "OSError:{errno}"),
                None => write!(f, "OSError:None"),
            },
            Self::Json(error) => {
                // Parsing diagnostics can contain reply content. Only the error kind crosses stderr.
                let _ = error;
                write!(f, "ValueError:None")
            }
        }
    }
}

impl From<io::Error> for Error {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<serde_json::Error> for Error {
    fn from(value: serde_json::Error) -> Self {
        Self::Json(value)
    }
}

fn remaining(deadline: Instant) -> Result<Duration, Error> {
    deadline
        .checked_duration_since(Instant::now())
        .filter(|duration| !duration.is_zero())
        .ok_or(Error::Unavailable)
}

fn pause(deadline: Instant) -> Result<(), Error> {
    thread::sleep(remaining(deadline)?.min(Duration::from_millis(20)));
    Ok(())
}

fn open_read(path: &Path) -> io::Result<File> {
    OpenOptions::new()
        .read(true)
        .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
        .open(path)
}

fn send(root: &Path, value: &Value, deadline: Instant) -> Result<(), Error> {
    let raw = serde_json::to_vec(value)?;
    if raw.len() > MAX_FRAME {
        return Err(Error::Unavailable);
    }
    let mut frame = Vec::with_capacity(raw.len() + 4);
    frame.extend_from_slice(&(raw.len() as u32).to_be_bytes());
    frame.extend_from_slice(&raw);
    let lock = open_read(&root.join("lock"))?;
    loop {
        match lock.try_lock() {
            Ok(()) => break,
            Err(TryLockError::WouldBlock) => pause(deadline)?,
            Err(TryLockError::Error(error)) => return Err(error.into()),
        }
    }
    let mut output = OpenOptions::new()
        .write(true)
        .custom_flags((OFlags::NOFOLLOW | OFlags::NONBLOCK).bits() as i32)
        .open(root.join("requests"))?;
    let mut unsent = frame.as_slice();
    while !unsent.is_empty() {
        let wait = remaining(deadline)?.min(Duration::from_millis(50));
        let timeout = Timespec {
            tv_sec: 0,
            tv_nsec: wait.as_nanos() as _,
        };
        let ready = poll(&mut [PollFd::new(&output, PollFlags::OUT)], Some(&timeout))
            .map_err(io::Error::from)?;
        if ready == 0 {
            continue;
        }
        match output.write(unsent) {
            Ok(0) => return Err(Error::Unavailable),
            Ok(written) => unsent = &unsent[written..],
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {}
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

fn request_id() -> Result<String, Error> {
    let mut bytes = [0_u8; 16];
    File::open("/dev/urandom")?.read_exact(&mut bytes)?;
    bytes[6] = bytes[6] & 0x0f | 0x40;
    bytes[8] = bytes[8] & 0x3f | 0x80;
    let encoded: String = bytes.iter().map(|byte| format!("{byte:02x}")).collect();
    Ok(format!(
        "{}-{}-{}-{}-{}",
        &encoded[..8],
        &encoded[8..12],
        &encoded[12..16],
        &encoded[16..20],
        &encoded[20..]
    ))
}

pub fn call(root: &Path, operation: &str, arguments: Value) -> Result<Value, Error> {
    let request_id = request_id()?;
    let execution = std::env::var("POKETTO_EXECUTION_ID").unwrap_or_default();
    let deadline = Instant::now() + Duration::from_secs(55);
    if execution.is_empty() || fs::read(root.join("state"))? != execution.as_bytes() {
        return Err(Error::Unavailable);
    }
    send(
        root,
        &json!({"requestId": request_id, "executionId": execution, "operation": operation, "arguments": arguments}),
        deadline,
    )?;
    let result = root.join("responses").join(format!("{request_id}.json"));
    loop {
        if fs::read(root.join("state"))? != execution.as_bytes() {
            return Err(Error::Unavailable);
        }
        let input = match open_read(&result) {
            Ok(input) => input,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                pause(deadline)?;
                continue;
            }
            Err(error) => return Err(error.into()),
        };
        let info = input.metadata()?;
        if !info.is_file() || info.len() > MAX_FRAME as u64 || info.mode() & 0o022 != 0 {
            return Err(Error::Unavailable);
        }
        let mut raw = Vec::new();
        input.take(MAX_FRAME as u64 + 1).read_to_end(&mut raw)?;
        if raw.len() > MAX_FRAME {
            return Err(Error::Unavailable);
        }
        let response: Value = serde_json::from_slice(&raw)?;
        if !response.is_object() || !response.get("ok").is_some_and(Value::is_boolean) {
            return Err(Error::Unavailable);
        }
        // Once a host result is known, an unsuccessful ack cannot turn it into an unknown write.
        let _ = send(
            root,
            &json!({"requestId": request_id, "executionId": execution, "operation": "ack"}),
            deadline,
        );
        return Ok(response);
    }
}

pub fn write_result(output: &mut impl Write, value: &Value) -> io::Result<()> {
    match value {
        Value::Array(items) => {
            output.write_all(b"[")?;
            for (index, item) in items.iter().enumerate() {
                if index > 0 {
                    output.write_all(b", ")?;
                }
                write_result(output, item)?;
            }
            output.write_all(b"]")
        }
        Value::Object(items) => {
            output.write_all(b"{")?;
            for (index, (key, item)) in items.iter().enumerate() {
                if index > 0 {
                    output.write_all(b", ")?;
                }
                serde_json::to_writer(&mut *output, key)?;
                output.write_all(b": ")?;
                write_result(output, item)?;
            }
            output.write_all(b"}")
        }
        _ => serde_json::to_writer(output, value).map_err(io::Error::other),
    }
}
