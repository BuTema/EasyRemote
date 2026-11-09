//! TLS + WebSocket server: handshake (pair / auth), then binary input frames.

use crate::config::{self, Identity};
use crate::input::InputHandle;
use crate::pairing::{self, PairError};
use crate::proto::{self, PROTO_VERSION};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc::unbounded_channel;
use tokio::time::{timeout, Instant};
use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use tokio_rustls::rustls::ServerConfig;
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::tungstenite::Message;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const IDLE_TIMEOUT: Duration = Duration::from_secs(45);
const STATE_POLL: Duration = Duration::from_secs(2);
const MAX_CONNECTIONS: usize = 8;
const MAX_FRAME: usize = 4096;

pub struct Shared {
    pub dir: PathBuf,
    pub name: String,
    pub fingerprint: String,
    pub input: InputHandle,
}

pub fn tls_acceptor(id: &Identity) -> Result<TlsAcceptor, String> {
    let cert = CertificateDer::from(id.cert_der.clone());
    let key = PrivateKeyDer::from(PrivatePkcs8KeyDer::from(id.key_der.clone()));
    let provider = Arc::new(tokio_rustls::rustls::crypto::ring::default_provider());
    let cfg = ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)
        .map_err(|e| e.to_string())?;
    Ok(TlsAcceptor::from(Arc::new(cfg)))
}

pub async fn serve(listener: TcpListener, acceptor: TlsAcceptor, shared: Arc<Shared>) {
    let active = Arc::new(AtomicUsize::new(0));
    loop {
        let (tcp, peer) = match listener.accept().await {
            Ok(x) => x,
            Err(e) => {
                info!("accept failed: {e}");
                tokio::time::sleep(Duration::from_millis(200)).await;
                continue;
            }
        };
        if active.load(Ordering::Relaxed) >= MAX_CONNECTIONS {
            drop(tcp);
            continue;
        }
        active.fetch_add(1, Ordering::Relaxed);
        let acceptor = acceptor.clone();
        let shared = shared.clone();
        let active = active.clone();
        tokio::spawn(async move {
            let _ = tcp.set_nodelay(true);
            let result = handle(tcp, acceptor, &shared).await;
            if let Err(e) = result {
                // A phone that leaves the screen or loses Wi-Fi just drops the socket: not worth logging.
                let routine = e.contains("close_notify") || e.contains("reset by peer") || e.contains("Broken pipe");
                if !routine {
                    info!("connection {peer}: {e}");
                }
            }
            shared.input.release_all();
            active.fetch_sub(1, Ordering::Relaxed);
        });
    }
}

#[derive(Deserialize)]
struct ClientMsg {
    t: String,
    #[serde(default)]
    v: u32,
    #[serde(default)]
    device: String,
    #[serde(default)]
    proof: String,
    #[serde(default)]
    id: String,
    #[serde(default)]
    token: String,
}

fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            c if (c as u32) < 0x20 => o.push(' '),
            c => o.push(c),
        }
    }
    o
}

fn err_json(code: &str, msg: &str) -> String {
    format!(r#"{{"t":"error","code":"{}","msg":"{}"}}"#, code, esc(msg))
}

async fn handle(tcp: TcpStream, acceptor: TlsAcceptor, shared: &Shared) -> Result<(), String> {
    let tls = timeout(HANDSHAKE_TIMEOUT, acceptor.accept(tcp))
        .await
        .map_err(|_| "TLS handshake timed out".to_string())?
        .map_err(|e| format!("TLS handshake failed: {e}"))?;

    let mut ws_cfg = WebSocketConfig::default();
    ws_cfg.max_message_size = Some(MAX_FRAME);
    ws_cfg.max_frame_size = Some(MAX_FRAME);
    let ws = timeout(
        HANDSHAKE_TIMEOUT,
        tokio_tungstenite::accept_async_with_config(tls, Some(ws_cfg)),
    )
    .await
    .map_err(|_| "WebSocket handshake timed out".to_string())?
    .map_err(|e| format!("WebSocket handshake failed: {e}"))?;

    let (mut tx, mut rx) = ws.split();
    let nonce = config::random_nonce();
    let os = if cfg!(windows) { "windows" } else if cfg!(target_os = "linux") { "linux" } else { "other" };
    let hello = format!(
        r#"{{"t":"hello","v":{},"name":"{}","os":"{}","nonce":"{}"}}"#,
        PROTO_VERSION,
        esc(&shared.name),
        os,
        nonce
    );
    tx.send(Message::text(hello)).await.map_err(|e| e.to_string())?;

    // ---- first message: pair or auth
    let first = timeout(HANDSHAKE_TIMEOUT, rx.next())
        .await
        .map_err(|_| "no auth message".to_string())?
        .ok_or("closed before auth")?
        .map_err(|e| e.to_string())?;
    let text = match first {
        Message::Text(t) => t.to_string(),
        _ => {
            let _ = tx.send(Message::text(err_json("bad_request", "expected a JSON text message"))).await;
            return Err("first frame was not text".into());
        }
    };
    let msg: ClientMsg = match serde_json::from_str(&text) {
        Ok(m) => m,
        Err(_) => {
            let _ = tx.send(Message::text(err_json("bad_request", "invalid JSON"))).await;
            return Err("invalid first message".into());
        }
    };
    if msg.v != PROTO_VERSION {
        let _ = tx
            .send(Message::text(err_json("version", "unsupported protocol version")))
            .await;
        return Err(format!("client protocol version {}", msg.v));
    }

    match msg.t.as_str() {
        "pair" => match pairing::verify(&shared.dir, &nonce, &shared.fingerprint, &msg.proof) {
            Ok(()) => {
                let (id, token) = config::add_device(&shared.dir, &msg.device).map_err(|e| e.to_string())?;
                info!("paired new device '{}' ({})", msg.device.chars().take(48).collect::<String>(), id);
                let reply = format!(
                    r#"{{"t":"paired","id":"{}","token":"{}","name":"{}"}}"#,
                    id,
                    token,
                    esc(&shared.name)
                );
                tx.send(Message::text(reply)).await.map_err(|e| e.to_string())?;
            }
            Err(PairError::NoWindow) => {
                let _ = tx
                    .send(Message::text(err_json(
                        "no_pairing_window",
                        "run `easyremote pair` on the PC first",
                    )))
                    .await;
                return Err("pair attempt without an open window".into());
            }
            Err(PairError::Failed) => {
                let _ = tx
                    .send(Message::text(err_json("pairing_failed", "wrong pairing code")))
                    .await;
                return Err("pairing proof rejected".into());
            }
        },
        "auth" => match config::verify_device(&shared.dir, &msg.id, &msg.token) {
            Some(dev) => info!("device '{}' connected", dev.name),
            None => {
                // small fixed delay to slow down token guessing
                tokio::time::sleep(Duration::from_millis(300)).await;
                let _ = tx
                    .send(Message::text(err_json("bad_token", "this phone is not paired (any more)")))
                    .await;
                return Err("bad token".into());
            }
        },
        _ => {
            let _ = tx.send(Message::text(err_json("bad_request", "unknown message type"))).await;
            return Err("unknown first message".into());
        }
    }

    tx.send(Message::text(r#"{"t":"ready"}"#)).await.map_err(|e| e.to_string())?;

    // state updates (volume) are produced on the input thread and forwarded here
    let (state_tx, mut state_rx) = unbounded_channel::<String>();
    shared.input.query(state_tx.clone());

    // Keep the phone's volume / mute display in step with the PC even when the change was made on
    // the PC itself (system tray, keyboard keys): ask the input thread every couple of seconds
    // and forward only what changed. Only runs while a phone is connected.
    let mut poll = tokio::time::interval(STATE_POLL);
    poll.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut last_state = String::new();

    let mut deadline = Instant::now() + IDLE_TIMEOUT;
    loop {
        tokio::select! {
            incoming = rx.next() => {
                let Some(incoming) = incoming else { return Ok(()); };
                let incoming = incoming.map_err(|e| e.to_string())?;
                deadline = Instant::now() + IDLE_TIMEOUT;
                match incoming {
                    Message::Binary(data) => match proto::parse(&data) {
                        Ok(cmd) => shared.input.send(cmd, Some(state_tx.clone())),
                        Err(_) => {} // ignore malformed frames
                    },
                    Message::Ping(_) | Message::Pong(_) => {} // tungstenite answers pings itself
                    Message::Close(_) => return Ok(()),
                    _ => {}
                }
            }
            Some(state) = state_rx.recv() => {
                if state != last_state {
                    last_state = state.clone();
                    tx.send(Message::text(state)).await.map_err(|e| e.to_string())?;
                }
            }
            _ = poll.tick() => {
                shared.input.query(state_tx.clone());
            }
            _ = tokio::time::sleep_until(deadline) => {
                return Err("idle timeout".into());
            }
        }
    }
}
