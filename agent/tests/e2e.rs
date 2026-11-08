//! End-to-end: a fake "phone" talks to the real server over TLS + WebSocket with certificate pinning.

use easyremote::config;
use easyremote::input::{InputHandle, MockBackend};
use easyremote::pairing;
use easyremote::proto::{self, Action, Button, Cmd, Key, Media};
use easyremote::server::{self, Shared};
use futures_util::{SinkExt, StreamExt};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};
use tokio_rustls::rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use tokio_rustls::rustls::crypto::ring::default_provider;
use tokio_rustls::rustls::crypto::{verify_tls12_signature, verify_tls13_signature};
use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use tokio_rustls::rustls::{ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme};
use tokio_rustls::TlsConnector;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::WebSocketStream;

type Ws = WebSocketStream<tokio_rustls::client::TlsStream<TcpStream>>;

#[derive(Debug)]
struct Pinned {
    fingerprint: String,
}

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        if config::fingerprint(end_entity.as_ref()) == self.fingerprint {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(TlsError::General("fingerprint mismatch".into()))
        }
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls12_signature(m, c, d, &default_provider().signature_verification_algorithms)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(m, c, d, &default_provider().signature_verification_algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        default_provider().signature_verification_algorithms.supported_schemes()
    }
}

struct Env {
    addr: std::net::SocketAddr,
    dir: PathBuf,
    fingerprint: String,
    events: Arc<Mutex<Vec<String>>>,
}

async fn start() -> Env {
    let dir = std::env::temp_dir().join(format!("easyremote-e2e-{}", config::random_nonce()));
    std::fs::create_dir_all(&dir).unwrap();
    let identity = config::load_identity(&dir).unwrap();
    let acceptor = server::tls_acceptor(&identity).unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let (mock, events) = MockBackend::new();
    let shared = Arc::new(Shared {
        dir: dir.clone(),
        name: "Test PC".into(),
        fingerprint: identity.fingerprint.clone(),
        input: InputHandle::spawn(Box::new(mock)),
    });
    tokio::spawn(server::serve(listener, acceptor, shared));
    Env { addr, dir, fingerprint: identity.fingerprint, events }
}

async fn connect(env: &Env, pin: &str) -> Result<(Ws, String), String> {
    let cfg = ClientConfig::builder_with_provider(Arc::new(default_provider()))
        .with_safe_default_protocol_versions()
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Pinned { fingerprint: pin.to_string() }))
        .with_no_client_auth();
    let tcp = TcpStream::connect(env.addr).await.map_err(|e| e.to_string())?;
    let tls = TlsConnector::from(Arc::new(cfg))
        .connect(ServerName::try_from("easyremote.local").unwrap(), tcp)
        .await
        .map_err(|e| e.to_string())?;
    let (mut ws, _) = tokio_tungstenite::client_async("wss://easyremote.local/", tls)
        .await
        .map_err(|e| e.to_string())?;
    let hello = next_json(&mut ws).await;
    assert_eq!(hello["t"], "hello");
    assert_eq!(hello["v"], 1);
    assert_eq!(hello["name"], "Test PC");
    let nonce = hello["nonce"].as_str().unwrap().to_string();
    Ok((ws, nonce))
}

async fn next_json(ws: &mut Ws) -> serde_json::Value {
    loop {
        let m = tokio::time::timeout(Duration::from_secs(5), ws.next())
            .await
            .expect("timeout waiting for server")
            .expect("stream ended")
            .expect("ws error");
        if let Message::Text(t) = m {
            return serde_json::from_str(&t).unwrap();
        }
    }
}

async fn send_json(ws: &mut Ws, v: serde_json::Value) {
    ws.send(Message::text(v.to_string())).await.unwrap();
}

async fn send_cmd(ws: &mut Ws, c: Cmd) {
    ws.send(Message::binary(proto::encode(&c))).await.unwrap();
}

async fn wait_events(env: &Env, n: usize) -> Vec<String> {
    for _ in 0..100 {
        {
            let ev = env.events.lock().unwrap();
            if ev.len() >= n {
                return ev.clone();
            }
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    panic!("expected {n} events, got {:?}", env.events.lock().unwrap());
}

/// Run the whole pairing flow and return (id, token).
async fn pair(env: &Env) -> (String, String) {
    let secret = pairing::start(&env.dir).unwrap();
    let (mut ws, nonce) = connect(env, &env.fingerprint).await.unwrap();
    let proof = pairing::proof(&pairing::group(&secret), &nonce, &env.fingerprint); // typed with dashes
    send_json(&mut ws, serde_json::json!({"t":"pair","v":1,"device":"Pixel","proof":proof})).await;
    let r = next_json(&mut ws).await;
    assert_eq!(r["t"], "paired", "{r}");
    assert_eq!(next_json(&mut ws).await["t"], "ready");
    (r["id"].as_str().unwrap().into(), r["token"].as_str().unwrap().into())
}

#[tokio::test(flavor = "multi_thread")]
async fn pair_then_auth_then_control() {
    let env = start().await;
    let (id, token) = pair(&env).await;

    // pairing window is consumed
    assert!(!pairing::is_open(&env.dir));

    let (mut ws, _) = connect(&env, &env.fingerprint).await.unwrap();
    send_json(&mut ws, serde_json::json!({"t":"auth","v":1,"id":id,"token":token})).await;
    assert_eq!(next_json(&mut ws).await["t"], "ready");
    let st = next_json(&mut ws).await;
    assert_eq!(st["t"], "state");
    assert_eq!(st["vol"], 50);

    send_cmd(&mut ws, Cmd::Move { dx: 10, dy: -4 }).await;
    send_cmd(&mut ws, Cmd::Scroll { dx: 0, dy: -120 }).await;
    send_cmd(&mut ws, Cmd::Button(Button::Left, Action::Click)).await;
    send_cmd(&mut ws, Cmd::Button(Button::Right, Action::Click)).await;
    send_cmd(&mut ws, Cmd::Key(Key::Escape, Action::Click)).await;
    send_cmd(&mut ws, Cmd::Media(Media::PlayPause)).await;
    send_cmd(&mut ws, Cmd::Text("hi".into())).await;
    send_cmd(&mut ws, Cmd::Volume(1)).await;

    let ev = wait_events(&env, 11).await;
    assert_eq!(
        ev,
        vec![
            "move 10 -4",
            "scroll 0 -120",
            "button Left down",
            "button Left up",
            "button Right down",
            "button Right up",
            "key Escape down",
            "key Escape up",
            "media PlayPause",
            "text hi",
            "volume 55 false",
        ]
    );
    // volume change is reported back
    let st = next_json(&mut ws).await;
    assert_eq!((st["t"].as_str(), st["vol"].as_i64()), (Some("state"), Some(55)));

    send_cmd(&mut ws, Cmd::Mute).await;
    let st = next_json(&mut ws).await;
    assert_eq!(st["mute"], true);

    // garbage frames must not kill the connection
    ws.send(Message::binary(vec![0x7f, 1, 2, 3])).await.unwrap();
    ws.send(Message::binary(vec![proto::OP_MOVE, 1])).await.unwrap();
    send_cmd(&mut ws, Cmd::Move { dx: 1, dy: 1 }).await;
    let ev = wait_events(&env, 13).await;
    assert_eq!(ev.last().unwrap(), "move 1 1");
}

#[tokio::test(flavor = "multi_thread")]
async fn wrong_pairing_code_is_rejected_and_locks_after_five() {
    let env = start().await;
    let secret = pairing::start(&env.dir).unwrap();
    for i in 0..5 {
        let (mut ws, nonce) = connect(&env, &env.fingerprint).await.unwrap();
        let proof = pairing::proof("AAAAAAAAAAAAAAAA", &nonce, &env.fingerprint);
        send_json(&mut ws, serde_json::json!({"t":"pair","v":1,"device":"x","proof":proof})).await;
        let r = next_json(&mut ws).await;
        assert_eq!(r["t"], "error", "attempt {i}");
        assert_eq!(r["code"], "pairing_failed");
    }
    // window is now closed: even the right secret fails
    assert!(!pairing::is_open(&env.dir));
    let (mut ws, nonce) = connect(&env, &env.fingerprint).await.unwrap();
    let proof = pairing::proof(&secret, &nonce, &env.fingerprint);
    send_json(&mut ws, serde_json::json!({"t":"pair","v":1,"device":"x","proof":proof})).await;
    assert_eq!(next_json(&mut ws).await["code"], "no_pairing_window");
    assert!(config::load_devices(&env.dir).is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn proof_is_bound_to_the_nonce() {
    let env = start().await;
    let secret = pairing::start(&env.dir).unwrap();
    let (mut ws, _nonce) = connect(&env, &env.fingerprint).await.unwrap();
    // replayed proof from a different session
    let proof = pairing::proof(&secret, "some-old-nonce", &env.fingerprint);
    send_json(&mut ws, serde_json::json!({"t":"pair","v":1,"device":"x","proof":proof})).await;
    assert_eq!(next_json(&mut ws).await["code"], "pairing_failed");
}

#[tokio::test(flavor = "multi_thread")]
async fn bad_token_and_revoked_device_are_rejected() {
    let env = start().await;
    let (id, token) = pair(&env).await;

    let (mut ws, _) = connect(&env, &env.fingerprint).await.unwrap();
    send_json(&mut ws, serde_json::json!({"t":"auth","v":1,"id":id,"token":"00"})).await;
    let r = next_json(&mut ws).await;
    assert_eq!((r["t"].as_str(), r["code"].as_str()), (Some("error"), Some("bad_token")));

    assert_eq!(config::revoke(&env.dir, &id).unwrap(), 1);
    let (mut ws, _) = connect(&env, &env.fingerprint).await.unwrap();
    send_json(&mut ws, serde_json::json!({"t":"auth","v":1,"id":id,"token":token})).await;
    assert_eq!(next_json(&mut ws).await["code"], "bad_token");

    // input before auth is never executed
    let (mut ws, _) = connect(&env, &env.fingerprint).await.unwrap();
    ws.send(Message::binary(proto::encode(&Cmd::Move { dx: 5, dy: 5 }))).await.unwrap();
    let _ = tokio::time::timeout(Duration::from_millis(300), ws.next()).await;
    assert!(env.events.lock().unwrap().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn wrong_pinned_fingerprint_fails_tls() {
    let env = start().await;
    let wrong = "00".repeat(32);
    assert!(connect(&env, &wrong).await.is_err());
}

#[tokio::test(flavor = "multi_thread")]
async fn held_buttons_are_released_on_disconnect() {
    let env = start().await;
    let (id, token) = pair(&env).await;
    let (mut ws, _) = connect(&env, &env.fingerprint).await.unwrap();
    send_json(&mut ws, serde_json::json!({"t":"auth","v":1,"id":id,"token":token})).await;
    assert_eq!(next_json(&mut ws).await["t"], "ready");
    send_cmd(&mut ws, Cmd::Button(Button::Left, Action::Press)).await;
    send_cmd(&mut ws, Cmd::Key(Key::Left, Action::Press)).await;
    wait_events(&env, 2).await;
    drop(ws); // phone vanishes without releasing
    let ev = wait_events(&env, 4).await;
    assert!(ev.contains(&"button Left up".to_string()), "{ev:?}");
    assert!(ev.contains(&"key Left up".to_string()), "{ev:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn unsupported_protocol_version() {
    let env = start().await;
    let (mut ws, _) = connect(&env, &env.fingerprint).await.unwrap();
    send_json(&mut ws, serde_json::json!({"t":"auth","v":99,"id":"a","token":"b"})).await;
    assert_eq!(next_json(&mut ws).await["code"], "version");
}
