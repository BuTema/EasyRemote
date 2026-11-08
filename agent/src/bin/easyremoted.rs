// The background agent. On Windows it is a GUI-subsystem binary so that no console window appears
// when it starts at login; errors go to agent.log in the config directory.
#![cfg_attr(windows, windows_subsystem = "windows")]

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mock = args.iter().any(|a| a == "--mock");
    let foreground = args.iter().any(|a| a == "--foreground" || a == "-f") || mock;
    if args.iter().any(|a| a == "--help" || a == "-h") {
        println!("easyremoted [--foreground] [--mock]\n  --foreground  also log to the terminal\n  --mock        do not touch the PC, just log the events (for testing)");
        return;
    }
    if let Err(e) = easyremote::daemon::run(mock, foreground) {
        easyremote::log::write(&format!("fatal: {e}"));
        eprintln!("easyremoted: {e}");
        std::process::exit(1);
    }
}
