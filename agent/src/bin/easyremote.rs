//! `easyremote` — command-line control for the EasyRemote agent.

use easyremote::{autostart, config, discovery, pairing};

const HELP: &str = "\
EasyRemote — control this PC from your phone

USAGE:
  easyremote pair [--host <ip>]   Show a QR code + code to pair a phone (valid 5 minutes)
  easyremote devices              List paired phones
  easyremote revoke <id|name|all> Remove a paired phone
  easyremote status               Show name, address, fingerprint
  easyremote autostart on|off     Start the agent automatically at login
  easyremote run                  Run the agent in this terminal (same as easyremoted -f)
";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match run(&args) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("error: {e}");
            1
        }
    };
    std::process::exit(code);
}

fn run(args: &[String]) -> Result<(), String> {
    let dir = config::dir();
    let cmd = args.first().map(|s| s.as_str()).unwrap_or("help");
    match cmd {
        "pair" => {
            let cfg = config::load_config(&dir).map_err(|e| e.to_string())?;
            let id = config::load_identity(&dir).map_err(|e| e.to_string())?;
            let host = match args.iter().position(|a| a == "--host") {
                Some(i) => args.get(i + 1).cloned().ok_or("--host needs a value")?,
                None => discovery::local_ip()
                    .map(|ip| ip.to_string())
                    .ok_or("could not detect the LAN address; pass it with --host <ip>")?,
            };
            let secret = pairing::start(&dir).map_err(|e| e.to_string())?;
            let uri = pairing::uri(&host, cfg.port, &id.fingerprint, &secret, &cfg.name);
            println!("{}", pairing::qr(&uri));
            println!("In the EasyRemote app on your phone: Add PC → scan this QR code.");
            println!();
            println!("No camera? Choose \"Enter code\" and type:");
            println!("    Code:  {}", pairing::group(&secret));
            println!("    Address: {}:{}", host, cfg.port);
            println!();
            println!(
                "Valid for {} minutes, one phone. Make sure the agent is running (`easyremote autostart on`).",
                pairing::WINDOW_SECS / 60
            );
            Ok(())
        }
        "devices" => {
            let devices = config::load_devices(&dir);
            if devices.is_empty() {
                println!("No paired phones. Run `easyremote pair`.");
            }
            for d in devices {
                println!("{}  {}", d.id, d.name);
            }
            Ok(())
        }
        "revoke" => {
            let which = args.get(1).ok_or("usage: easyremote revoke <id|name|all>")?;
            let n = config::revoke(&dir, which).map_err(|e| e.to_string())?;
            println!("Removed {} device(s).", n);
            Ok(())
        }
        "status" => {
            let cfg = config::load_config(&dir).map_err(|e| e.to_string())?;
            let id = config::load_identity(&dir).map_err(|e| e.to_string())?;
            println!("Name:         {}", cfg.name);
            println!(
                "Address:      {}:{}",
                discovery::local_ip().map(|i| i.to_string()).unwrap_or_else(|| "?".into()),
                cfg.port
            );
            println!("Fingerprint:  {}", id.fingerprint);
            println!("Paired:       {}", config::load_devices(&dir).len());
            println!("Pairing open: {}", if pairing::is_open(&dir) { "yes" } else { "no" });
            println!("Config dir:   {}", dir.display());
            Ok(())
        }
        "autostart" => match args.get(1).map(|s| s.as_str()) {
            Some("on") => autostart::enable().map(|m| println!("{m}")),
            Some("off") => autostart::disable().map(|m| println!("{m}")),
            _ => Err("usage: easyremote autostart on|off".into()),
        },
        "run" => easyremote::daemon::run(false, true),
        _ => {
            print!("{HELP}");
            Ok(())
        }
    }
}
