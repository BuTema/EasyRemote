//! Process-level glue: build state, bind the port, advertise, serve until Ctrl-C.

use crate::config;
use crate::discovery;
use crate::input::{self, InputBackend, InputHandle};
use crate::server::{self, Shared};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::TcpListener;

/// Asks a running [`run_agent`] to stop (used by the Windows tray's Quit).
pub static SHUTDOWN: tokio::sync::Notify = tokio::sync::Notify::const_new();

pub fn request_shutdown() {
    SHUTDOWN.notify_one();
}

/// Run the agent until it receives Ctrl-C / SIGTERM. On Windows it also shows the tray icon.
pub fn run(mock: bool, stderr_log: bool) -> Result<(), String> {
    #[cfg(windows)]
    {
        crate::tray::run(mock, stderr_log)
    }
    #[cfg(not(windows))]
    {
        run_agent(mock, stderr_log)
    }
}

/// The agent itself: network, input, discovery. Runs until Ctrl-C / SIGTERM / [`request_shutdown`].
pub fn run_agent(mock: bool, stderr_log: bool) -> Result<(), String> {
    let dir = config::dir();
    std::fs::create_dir_all(&dir).map_err(|e| format!("cannot create {}: {e}", dir.display()))?;
    crate::log::init(&dir, stderr_log);

    info!("EasyRemote agent {} (build {}) starting", env!("CARGO_PKG_VERSION"), crate::build_id());
    let cfg = config::load_config(&dir).map_err(|e| e.to_string())?;
    let identity = config::load_identity(&dir).map_err(|e| e.to_string())?;

    let backend: Box<dyn InputBackend> = if mock {
        Box::new(input::MockBackend::logging())
    } else {
        input::create_real()?
    };
    // real input gets pointer smoothing; the mock backend shows the raw events
    let handle = if mock { InputHandle::spawn(backend) } else { InputHandle::spawn_smoothed(backend) };

    // current-thread runtime: one OS thread for all networking = minimal memory and wake-ups
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| e.to_string())?;

    rt.block_on(async move {
        let addr: SocketAddr = ([0, 0, 0, 0], cfg.port).into();
        let listener = TcpListener::bind(addr)
            .await
            .map_err(|e| format!("cannot listen on port {}: {e} (is another EasyRemote agent already running?)", cfg.port))?;
        let acceptor = server::tls_acceptor(&identity)?;

        // Keep the mDNS daemon alive for the lifetime of the process.
        let _mdns = match discovery::advertise(&cfg.name, cfg.port, &identity.fingerprint) {
            Ok(d) => Some(d),
            Err(e) => {
                info!("mDNS advertisement failed ({e}); phones can still connect by IP address");
                None
            }
        };

        info!(
            "EasyRemote agent '{}' listening on port {} (fingerprint {}…){}",
            cfg.name,
            cfg.port,
            &identity.fingerprint[..16],
            if mock { " [mock input]" } else { "" }
        );

        let shared = Arc::new(Shared {
            dir,
            name: cfg.name.clone(),
            fingerprint: identity.fingerprint.clone(),
            input: handle,
        });

        tokio::select! {
            _ = server::serve(listener, acceptor, shared) => {}
            _ = shutdown_signal() => { info!("shutting down"); }
        }
        Ok(())
    })
}

async fn shutdown_signal() {
    tokio::select! {
        _ = os_signal() => {}
        _ = SHUTDOWN.notified() => {}
    }
}

async fn os_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        if let Ok(mut term) = signal(SignalKind::terminate()) {
            tokio::select! {
                _ = tokio::signal::ctrl_c() => {}
                _ = term.recv() => {}
            }
            return;
        }
    }
    let _ = tokio::signal::ctrl_c().await;
}
