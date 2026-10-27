// Minimal HTTP/1.1 server over a Unix socket for the sandcrab phase 0 benchmark.
use std::io::{BufRead, BufReader, Read, Write};
use std::os::unix::net::{UnixListener, UnixStream};

fn serve(stream: UnixStream, token: String) -> std::io::Result<()> {
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut writer = stream;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 { return Ok(()); }
        let path = line.split_whitespace().nth(1).unwrap_or("").to_string();
        let (mut length, mut authorized, mut close) = (0usize, false, false);
        loop {
            let mut header = String::new();
            reader.read_line(&mut header)?;
            let header = header.trim_end();
            if header.is_empty() { break; }
            let (name, value) = header.split_once(':').unwrap_or((header, ""));
            let value = value.trim();
            match name.to_ascii_lowercase().as_str() {
                "content-length" => length = value.parse().unwrap_or(0),
                "x-token" => authorized = value == token,
                "connection" => close = value.eq_ignore_ascii_case("close"),
                _ => {}
            }
        }
        let body = if path == "/upload" {
            let mut remaining = length;
            let mut buffer = vec![0u8; 1 << 16];
            while remaining > 0 {
                let n = reader.read(&mut buffer[..remaining.min(1 << 16)])?;
                if n == 0 { return Ok(()); }
                remaining -= n;
            }
            format!("{{\"ok\":{authorized},\"bytes\":{length}}}")
        } else {
            let mut raw = vec![0u8; length];
            reader.read_exact(&mut raw)?;
            match serde_json::from_slice::<serde_json::Value>(&raw) {
                Ok(value) if authorized => format!("{{\"ok\":true,\"expiresAt\":{}}}", value["expiresAt"]),
                _ => "{\"ok\":false}".to_string(),
            }
        };
        write!(writer, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}", body.len(), body)?;
        writer.flush()?;
        if close { return Ok(()); }
    }
}

fn main() {
    let mut args = std::env::args().skip(1);
    let (path, token) = (args.next().unwrap(), args.next().unwrap());
    let _ = std::fs::remove_file(&path);
    let listener = UnixListener::bind(&path).unwrap();
    println!("ready");
    for stream in listener.incoming().flatten() {
        let token = token.clone();
        std::thread::spawn(move || { let _ = serve(stream, token); });
    }
}
