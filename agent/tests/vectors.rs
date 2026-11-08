//! Wire-format vectors shared with the Android app's ProtocolTest.kt.
//! The proof value was computed independently with Python's hmac module.

use easyremote::pairing;
use easyremote::proto::{encode, Action, Button, Cmd, Key, Media};

#[test]
fn pairing_proof_vector() {
    let proof = pairing::proof(
        "ABCD-EFGH-IJKL-MNOP",
        "00112233445566778899aabbccddeeff",
        "e8cc7844b35ddb322b2f2d219da198c05a2822e9dae76769b81c777224a73d2a",
    );
    assert_eq!(proof, "c599c464693b3e72311dc72500340f4791b413f89a34a7fc483f33b500d57b78");
}

#[test]
fn frame_vectors() {
    assert_eq!(encode(&Cmd::Move { dx: 10, dy: -4 }), vec![1, 0, 10, 0xff, 0xfc]);
    assert_eq!(encode(&Cmd::Scroll { dx: 0, dy: -120 }), vec![2, 0, 0, 0xff, 0x88]);
    assert_eq!(encode(&Cmd::Button(Button::Right, Action::Click)), vec![3, 1, 2]);
    assert_eq!(encode(&Cmd::Key(Key::Escape, Action::Click)), vec![4, 5, 2]);
    assert_eq!(encode(&Cmd::Volume(-1)), vec![5, 0xff]);
    assert_eq!(encode(&Cmd::Mute), vec![6]);
    assert_eq!(encode(&Cmd::Media(Media::PlayPause)), vec![7, 1]);
}
