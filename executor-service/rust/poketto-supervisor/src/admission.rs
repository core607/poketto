//! Startup admission precedes bounded LRU eviction; kernel work never holds service metadata.
use crate::{Service, now, runtime::Runtime};
use serde_json::Value;
use std::{
    io,
    sync::{Arc, Mutex, atomic::Ordering},
    thread,
    time::{Duration, Instant},
};
impl Service {
    pub(crate) fn await_unit_stop(&self, payload: &Value) -> Result<Instant, &'static str> {
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            let updating = {
                let mut state = self.state.lock().unwrap();
                let session = Self::live(&mut state, payload)?;
                session.status == "UPDATING" && session.execution.is_empty()
            };
            if !updating || Instant::now() >= deadline {
                return Ok(deadline);
            }
            thread::sleep(Duration::from_millis(20));
        }
    }
    pub(crate) fn reserve_start(&self, lease: &str, deadline: Instant) -> io::Result<()> {
        {
            let mut state = self.state.lock().unwrap();
            if state.sessions[lease].deadline <= now() {
                Self::mark_cancelled(state.sessions.get_mut(lease).unwrap(), "lease_expired");
            }
            if state.sessions[lease].cancelled.load(Ordering::SeqCst) {
                return Err(io::ErrorKind::Interrupted.into());
            }
            if self.fenced.load(Ordering::SeqCst) {
                return Err(io::Error::other("Execution authority unavailable"));
            }
            if state.starting.len() >= self.max_starting_units {
                return Err(io::ErrorKind::WouldBlock.into());
            }
            state.starting.insert(lease.into());
        }
        let result: io::Result<()> = (|| {
            loop {
                let (mut candidates, stopping) = {
                    let mut state = self.state.lock().unwrap();
                    if state.sessions[lease].deadline <= now() {
                        Self::mark_cancelled(
                            state.sessions.get_mut(lease).unwrap(),
                            "lease_expired",
                        );
                    }
                    if state.sessions[lease].cancelled.load(Ordering::SeqCst) {
                        return Err(io::ErrorKind::Interrupted.into());
                    }
                    if self.fenced.load(Ordering::SeqCst) {
                        return Err(io::Error::other("Execution authority unavailable"));
                    }
                    if Instant::now() >= deadline {
                        return Err(io::ErrorKind::WouldBlock.into());
                    }
                    if state.sessions.values().filter(|s| s.live_unit).count() < self.max_live_units
                    {
                        state.sessions.get_mut(lease).unwrap().live_unit = true;
                        return Ok(());
                    }
                    let candidates = state
                        .sessions
                        .iter()
                        .filter(|(id, s)| {
                            id.as_str() != lease && s.live_unit && s.status == "READY"
                        })
                        .filter_map(|(id, s)| {
                            s.runtime
                                .clone()
                                .map(|runtime| (s.idle_since, id.clone(), runtime))
                        })
                        .collect::<Vec<_>>();
                    let stopping = state.sessions.iter().any(|(id, s)| {
                        id.as_str() != lease && s.live_unit && s.status == "UPDATING"
                    });
                    (candidates, stopping)
                };
                candidates.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(&b.1)));
                let mut selected = false;
                for (_, id, runtime) in candidates {
                    if Instant::now() >= deadline {
                        break;
                    }
                    if self.try_stop_idle(&id, &runtime, false) {
                        selected = true;
                        break;
                    }
                }
                if selected {
                    continue;
                }
                if !stopping {
                    return Err(io::ErrorKind::WouldBlock.into());
                }
                thread::sleep(Duration::from_millis(50));
            }
        })();
        if result.is_err() {
            self.state.lock().unwrap().starting.remove(lease);
        }
        result
    }
    fn try_stop_idle(
        &self,
        lease: &str,
        runtime: &Arc<Mutex<Runtime>>,
        require_expired: bool,
    ) -> bool {
        let Ok(mut backend) = runtime.try_lock() else {
            return false;
        };
        let cancelled = {
            let mut state = self.state.lock().unwrap();
            let Some(session) = state.sessions.get_mut(lease) else {
                return false;
            };
            if session.status != "READY"
                || !session.execution.is_empty()
                || !session.live_unit
                || session.cancelled.load(Ordering::SeqCst)
                || !session
                    .runtime
                    .as_ref()
                    .is_some_and(|current| Arc::ptr_eq(current, runtime))
                || require_expired
                    && session.idle_since.elapsed() < Duration::from_secs(self.idle_unit_seconds)
            {
                return false;
            }
            session.status = "UPDATING";
            session.cancelled.clone()
        };
        let result = backend.stop_for_idle();
        {
            let mut state = self.state.lock().unwrap();
            let session = state.sessions.get_mut(lease).unwrap();
            session.live_unit = backend.has_unit();
            if result.is_err() {
                self.fenced.store(true, Ordering::SeqCst);
                Self::mark_cancelled(session, "sandbox_failed");
            } else if !cancelled.load(Ordering::SeqCst) {
                session.status = "READY";
            }
        }
        drop(backend);
        if cancelled.load(Ordering::SeqCst) {
            self.finish_cancelled(lease);
        }
        true
    }
    pub(crate) fn sweep_idle(&self) {
        let candidates = self
            .state
            .lock()
            .unwrap()
            .sessions
            .iter()
            .filter(|(_, s)| {
                s.status == "READY"
                    && s.live_unit
                    && s.idle_since.elapsed() >= Duration::from_secs(self.idle_unit_seconds)
            })
            .filter_map(|(id, s)| s.runtime.clone().map(|runtime| (id.clone(), runtime)))
            .collect::<Vec<_>>();
        for (id, runtime) in candidates {
            self.try_stop_idle(&id, &runtime, true);
        }
    }
}
