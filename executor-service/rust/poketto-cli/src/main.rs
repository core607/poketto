#![forbid(unsafe_code)]

mod arguments;
mod bridge;

use std::io::{self, Write};

pub const MAX_FRAME: usize = 512 * 1024;

fn run() -> i32 {
    let invocation = match arguments::parse(std::env::args().skip(1).collect()) {
        Ok(Some(invocation)) => invocation,
        Ok(None) => return 0,
        Err(message) => {
            eprintln!("poketto: error: {message}");
            return 2;
        }
    };
    let Some(root) = std::env::var_os("POKETTO_BRIDGE").filter(|value| !value.is_empty()) else {
        eprintln!("poketto: error: this command requires an admitted Poketto execution session");
        return 2;
    };
    match bridge::call(root.as_ref(), &invocation.operation, invocation.arguments) {
        Ok(response) => {
            // Keep the single-line Python JSON formatting and the host's object-key order.
            let mut output = io::stdout().lock();
            if bridge::write_result(&mut output, &response)
                .and_then(|()| writeln!(output))
                .is_err()
            {
                return 1;
            }
            i32::from(response["ok"] != true)
        }
        Err(error) => {
            eprintln!(
                "Poketto bridge unavailable or outcome unknown ({error}); inspect host status before retrying a write."
            );
            1
        }
    }
}

fn main() {
    std::process::exit(run());
}
