#![forbid(unsafe_code)]

fn main() -> Result<(), Box<dyn std::error::Error>> {
    poketto_supervisor::run()
}
