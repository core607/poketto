#![forbid(unsafe_code)]
use std::path::Path;

fn main() {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.len() != 1 {
        eprintln!("Repository path required");
        std::process::exit(1);
    }
    if let Err(error) = sandcrab_shell::run(Path::new(&args[0]), "POKETTO_EXECUTION_ID") {
        eprintln!("{error}");
        std::process::exit(1);
    }
}
