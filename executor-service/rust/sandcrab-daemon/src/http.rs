//! The daemon's HTTP/1.1 surface: one bounded request per connection with a declared length,
//! answered with a JSON body and closed. Chunked bodies, pipelining and upgrades are refused.
use std::io::{self, Read, Write};

/// The request line and headers together.
pub const MAX_HEAD: usize = 8192;
const MAX_HEADERS: usize = 32;

pub struct Request {
    pub method: String,
    pub path: String,
    pub body: Vec<u8>,
}

fn token(text: &str) -> bool {
    !text.is_empty()
        && text
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b))
}

/// Read one request whose body is at most `max_body` bytes. Every malformed, oversized or
/// unsupported request is `INVALID_HTTP`.
pub fn read(stream: &mut impl Read, max_body: usize) -> Result<Request, &'static str> {
    const INVALID: &str = "INVALID_HTTP";
    let mut buffer = Vec::with_capacity(1024);
    let end = loop {
        if let Some(end) = buffer.windows(4).position(|w| w == b"\r\n\r\n") {
            break end;
        }
        if buffer.len() >= MAX_HEAD {
            return Err(INVALID);
        }
        let mut chunk = [0; 1024];
        let read = stream
            .read(&mut chunk[..(MAX_HEAD + 4 - buffer.len()).min(1024)])
            .map_err(|_| INVALID)?;
        if read == 0 {
            return Err(INVALID);
        }
        buffer.extend_from_slice(&chunk[..read]);
    };
    if end > MAX_HEAD {
        return Err(INVALID);
    }
    let head = std::str::from_utf8(&buffer[..end]).map_err(|_| INVALID)?;
    let mut lines = head.split("\r\n");
    let mut parts = lines.next().ok_or(INVALID)?.split(' ');
    let (method, path, version) = (
        parts.next().ok_or(INVALID)?,
        parts.next().ok_or(INVALID)?,
        parts.next().ok_or(INVALID)?,
    );
    if parts.next().is_some()
        || !["GET", "POST"].contains(&method)
        || !path.starts_with('/')
        || !path.bytes().all(|b| b.is_ascii_graphic())
        || version != "HTTP/1.1"
    {
        return Err(INVALID);
    }
    let mut length = None;
    let mut count = 0;
    for line in lines {
        count += 1;
        let (name, value) = line.split_once(':').ok_or(INVALID)?;
        let value = value.trim_matches(|c| c == ' ' || c == '\t');
        if count > MAX_HEADERS || !token(name) || value.bytes().any(|b| b.is_ascii_control()) {
            return Err(INVALID);
        }
        match name.to_ascii_lowercase().as_str() {
            "content-length" => {
                if length.is_some()
                    || value.is_empty()
                    || !value.bytes().all(|b| b.is_ascii_digit())
                {
                    return Err(INVALID);
                }
                length = Some(value.parse::<usize>().map_err(|_| INVALID)?);
            }
            "transfer-encoding" | "upgrade" | "expect" => return Err(INVALID),
            _ => {}
        }
    }
    let length = length.unwrap_or(0);
    if length > max_body || (method == "POST") != (length > 0) {
        return Err(INVALID);
    }
    let mut body = buffer[end + 4..].to_vec();
    if body.len() > length {
        return Err(INVALID);
    }
    let start = body.len();
    body.resize(length, 0);
    stream.read_exact(&mut body[start..]).map_err(|_| INVALID)?;
    Ok(Request {
        method: method.into(),
        path: path.into(),
        body,
    })
}

/// Write a complete JSON response and announce that the connection closes.
pub fn write(stream: &mut impl Write, status: u16, body: &[u8]) -> io::Result<()> {
    let reason = match status {
        200 => "OK",
        400 => "Bad Request",
        403 => "Forbidden",
        404 => "Not Found",
        _ => "Error",
    };
    let head = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    stream.write_all(head.as_bytes())?;
    stream.write_all(body)?;
    stream.flush()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn parse(raw: &[u8]) -> Result<Request, &'static str> {
        read(&mut &raw[..], 64)
    }
    #[test]
    fn a_declared_body_and_a_bodiless_get_are_read() {
        let request =
            parse(b"POST /v1/requests HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}")
                .ok()
                .unwrap();
        assert_eq!(
            (request.method.as_str(), request.path.as_str()),
            ("POST", "/v1/requests")
        );
        assert_eq!(request.body, b"{}");
        let hello = parse(b"GET /v1/hello HTTP/1.1\r\ncontent-length: 0\r\n\r\n")
            .ok()
            .unwrap();
        assert!(hello.body.is_empty());
    }
    #[test]
    fn malformed_unbounded_or_unsupported_requests_are_refused() {
        let long_header = format!("GET / HTTP/1.1\r\nX: {}\r\n\r\n", "a".repeat(MAX_HEAD));
        let many_headers = format!(
            "GET / HTTP/1.1\r\n{}\r\n",
            "X: a\r\n".repeat(MAX_HEADERS + 1)
        );
        for raw in [
            &b"POST /v1/requests HTTP/1.1\r\nContent-Length: 65\r\n\r\n"[..],
            b"POST /v1/requests HTTP/1.1\r\n\r\n",
            b"POST /v1/requests HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
            b"POST /v1/requests HTTP/1.1\r\nContent-Length: +2\r\n\r\n{}",
            b"POST /v1/requests HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n",
            b"POST /v1/requests HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}GET",
            b"POST /v1/requests HTTP/1.1\r\nContent-Length: 4\r\n\r\n{}",
            b"GET /v1/hello HTTP/1.1\r\nContent-Length: 1\r\n\r\nx",
            b"GET /v1/hello HTTP/1.0\r\n\r\n",
            b"DELETE /v1/hello HTTP/1.1\r\n\r\n",
            b"GET v1/hello HTTP/1.1\r\n\r\n",
            b"GET /v1/hello HTTP/1.1\r\nNo colon\r\n\r\n",
            b"GET /v1/hello HTTP/1.1\r\nBad Name: a\r\n\r\n",
            b"GET /v1/hello HTTP/1.1\r\n",
            b"",
            long_header.as_bytes(),
            many_headers.as_bytes(),
        ] {
            assert_eq!(
                parse(raw).err(),
                Some("INVALID_HTTP"),
                "{}",
                String::from_utf8_lossy(raw)
            );
        }
    }
    #[test]
    fn responses_declare_their_length_and_close() {
        let mut output = Vec::new();
        write(&mut output, 404, br#"{"ok":false}"#).unwrap();
        assert_eq!(
            output,
            b"HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 12\r\nConnection: close\r\n\r\n{\"ok\":false}"
        );
    }
}
