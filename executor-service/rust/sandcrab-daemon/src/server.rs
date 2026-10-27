//! Shared HTTP server; only the configured application UID may reach the signed service.
use crate::{MAX_FRAME, Service, failed, http, parse};
use rustix::fs::{Gid, Uid, chown};
use serde_json::Value;
use std::{
    fs, io,
    os::unix::{
        fs::{FileTypeExt, MetadataExt, PermissionsExt},
        net::{UnixListener, UnixStream},
    },
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant},
};
fn invalid() -> io::Error {
    io::Error::other("Server lifecycle unavailable")
}
fn response_bytes(response: &Value) -> Vec<u8> {
    let bytes = serde_json::to_vec(response).unwrap();
    if bytes.len() > MAX_FRAME {
        br#"{"ok":false,"code":"RESPONSE_LIMIT"}"#.to_vec()
    } else {
        bytes
    }
}
fn exchange(stream: &mut UnixStream, service: &Service, app_uid: Uid) -> io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(5)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;
    let peer = rustix::net::sockopt::socket_peercred(&*stream).map(|c| c.uid);
    let (status, response) = if peer.ok() != Some(app_uid) {
        (403, failed("PEER_REJECTED"))
    } else {
        route(stream, service)
    };
    http::write(stream, status, &response_bytes(&response))
}
/// `GET /v1/hello` answers the handshake; `POST /v1/requests` carries one signed request.
fn route(stream: &mut UnixStream, service: &Service) -> (u16, Value) {
    let request = match http::read(stream, MAX_FRAME) {
        Ok(request) => request,
        Err(code) => return (400, failed(code)),
    };
    match (request.method.as_str(), request.path.as_str()) {
        ("GET", "/v1/hello") => (200, service.hello()),
        ("POST", "/v1/requests") => match parse(&request.body) {
            Ok(envelope) => (200, service.handle(envelope)),
            Err(_) => (400, failed("INVALID_BODY")),
        },
        _ => (404, failed("NOT_FOUND")),
    }
}
struct Work {
    stream: UnixStream,
    service: Arc<Service>,
    active: Arc<AtomicUsize>,
    app_uid: Uid,
}
impl Work {
    fn run(mut self) {
        let _ = exchange(&mut self.stream, &self.service, self.app_uid);
    }
}
impl Drop for Work {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::SeqCst);
    }
}
pub struct Server {
    pub active: Arc<AtomicUsize>,
    service: Arc<Service>,
    socket: PathBuf,
    identity: (u64, u64),
    stop: Arc<AtomicBool>,
    failed: Arc<AtomicBool>,
    accepting: thread::JoinHandle<()>,
    sweep: thread::JoinHandle<()>,
    workers: Vec<thread::JoinHandle<()>>,
}
impl Server {
    pub fn start(
        socket: PathBuf,
        service: Arc<Service>,
        maximum: usize,
        pool: bool,
        app_uid: Uid,
        app_gid: Option<u32>,
    ) -> io::Result<Self> {
        match fs::symlink_metadata(&socket) {
            Ok(info)
                if info.file_type().is_socket()
                    && info.uid() == rustix::process::geteuid().as_raw() =>
            {
                fs::remove_file(&socket)?
            }
            Ok(_) => return Err(invalid()),
            Err(e) if e.kind() == io::ErrorKind::NotFound => (),
            Err(e) => return Err(e),
        }
        let listener = UnixListener::bind(&socket)?;
        fs::set_permissions(&socket, fs::Permissions::from_mode(0o600))?;
        if let Some(gid) = app_gid {
            chown(&socket, Some(Uid::from_raw(0)), Some(Gid::from_raw(gid)))?;
            fs::set_permissions(&socket, fs::Permissions::from_mode(0o660))?;
        }
        rustix::net::listen(&listener, 256)?;
        let info = fs::symlink_metadata(&socket)?;
        let identity = (info.dev(), info.ino());
        let active = Arc::new(AtomicUsize::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let failed = Arc::new(AtomicBool::new(false));
        let (send, recv) = mpsc::sync_channel::<Work>(if pool { maximum } else { 0 });
        let recv = Arc::new(Mutex::new(recv));
        let mut workers = Vec::new();
        if pool {
            for _ in 0..32.min(maximum) {
                let recv = recv.clone();
                workers.push(thread::Builder::new().spawn(move || {
                    loop {
                        let job = recv.lock().unwrap().recv();
                        match job {
                            Ok(job) => job.run(),
                            Err(_) => break,
                        }
                    }
                })?);
            }
        }
        let accepting = {
            let (service, active, stop, failed) = (
                service.clone(),
                active.clone(),
                stop.clone(),
                failed.clone(),
            );
            thread::Builder::new().spawn(move || {
                loop {
                    let stream = match listener.accept() {
                        Ok((stream, _)) => stream,
                        Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                        Err(_) => {
                            failed.store(true, Ordering::SeqCst);
                            break;
                        }
                    };
                    if stop.load(Ordering::SeqCst) {
                        break;
                    }
                    if active
                        .try_update(Ordering::SeqCst, Ordering::SeqCst, |v| {
                            (v < maximum).then_some(v + 1)
                        })
                        .is_err()
                    {
                        continue;
                    }
                    let job = Work {
                        stream,
                        service: service.clone(),
                        active: active.clone(),
                        app_uid,
                    };
                    if pool {
                        let _ = send.try_send(job);
                    } else {
                        let _ = thread::Builder::new().spawn(move || job.run());
                    }
                }
            })?
        };
        let sweep = {
            let (service, stop) = (service.clone(), stop.clone());
            thread::Builder::new().spawn(move || {
                while !stop.load(Ordering::SeqCst) {
                    thread::sleep(Duration::from_millis(250));
                    service.sweep();
                }
            })?
        };
        Ok(Self {
            active,
            service,
            socket,
            identity,
            stop,
            failed,
            accepting,
            sweep,
            workers,
        })
    }
    pub fn failed(&self) -> bool {
        self.failed.load(Ordering::SeqCst)
    }
    pub fn shutdown(self) -> io::Result<()> {
        self.stop.store(true, Ordering::SeqCst);
        let contained = self.service.shutdown();
        let _ = UnixStream::connect(&self.socket);
        self.accepting.join().map_err(|_| invalid())?;
        for worker in self.workers {
            worker.join().map_err(|_| invalid())?;
        }
        self.sweep.join().map_err(|_| invalid())?;
        let deadline = Instant::now() + Duration::from_secs(6);
        while self.active.load(Ordering::SeqCst) != 0 && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
        }
        contained?;
        if self.active.load(Ordering::SeqCst) != 0 {
            return Err(invalid());
        }
        let info = fs::symlink_metadata(&self.socket)?;
        if !info.file_type().is_socket() || (info.dev(), info.ino()) != self.identity {
            return Err(invalid());
        }
        fs::remove_file(&self.socket)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn response_frame_ceiling_is_enforced_before_send() {
        assert_eq!(response_bytes(&json!({"ok":true})), br#"{"ok":true}"#);
        let response = json!({"content":"x".repeat(MAX_FRAME)});
        assert_eq!(
            response_bytes(&response),
            br#"{"ok":false,"code":"RESPONSE_LIMIT"}"#
        );
    }
}
