//! Where a Rex server is, from whatever the user typed, and how to open a
//! WebSocket to it.
//!
//! Address rules (so adding a host is one field): a `.ts.net` name, a
//! `100.64/10` address or a Tailscale IPv6 address is a tailnet host, reached
//! with TLS, on 443 unless a port is given; anything else is plaintext on Rex's
//! own port 7297. An explicit scheme wins. `unix:/path` or an absolute path is a local
//! socket, which the desktop tools use.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio_tungstenite::WebSocketStream;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;

use crate::{ClientError, Result};

pub const PLAIN_PORT: u16 = 7297;
pub const TLS_PORT: u16 = 443;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

pub trait Io: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Io for T {}
pub type Ws = WebSocketStream<Box<dyn Io>>;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum Endpoint {
    Unix(PathBuf),
    Tcp { host: String, port: u16, tls: bool },
}

impl Endpoint {
    pub fn parse(raw: &str) -> Result<Self> {
        let mut text = raw.trim().to_string();
        if text.is_empty() {
            return Err(ClientError::Address("empty address".into()));
        }
        if let Some(path) = text.strip_prefix("unix:") {
            let path = path.trim_start_matches("//");
            return Ok(Self::Unix(PathBuf::from(percent_decode(path))));
        }
        if text.starts_with('/') || text.starts_with('~') {
            return Ok(Self::Unix(PathBuf::from(expand_home(&text))));
        }
        let mut scheme: Option<&str> = None;
        let lower = text.to_ascii_lowercase();
        for s in ["https", "http", "wss", "ws"] {
            let prefix = format!("{s}://");
            if lower.starts_with(&prefix) {
                scheme = Some(s);
                text = text[prefix.len()..].to_string();
                break;
            }
        }
        let text = text.split(['/', '?', '#']).next().unwrap_or_default().trim().to_string();
        if text.is_empty() {
            return Err(ClientError::Address("no host in address".into()));
        }
        let (host, port_text) = split_host_port(&text).ok_or_else(|| ClientError::Address(format!("can't read host and port in {raw:?}")))?;
        let explicit_port = match port_text {
            Some(p) => Some(p.parse::<u16>().ok().filter(|p| *p > 0).ok_or_else(|| ClientError::Address(format!("bad port {p:?}")))?),
            None => None,
        };
        let tailnet = is_tailnet(&host);
        let tls = match (scheme, explicit_port) {
            (Some(s), _) => s == "https" || s == "wss",
            // Rex's tailnet servers speak TLS on whatever port they use; `http://` says otherwise.
            (None, Some(p)) => tailnet || p == TLS_PORT,
            (None, None) => tailnet,
        };
        let port = explicit_port.unwrap_or(if tls { TLS_PORT } else { PLAIN_PORT });
        Ok(Self::Tcp { host: host.trim_matches(['[', ']']).to_string(), port, tls })
    }

    pub fn is_tailnet(&self) -> bool {
        matches!(self, Self::Tcp { host, .. } if is_tailnet(host))
    }

    /// What to show for this endpoint.
    pub fn display(&self) -> String {
        match self {
            Self::Unix(p) => p.display().to_string(),
            Self::Tcp { host, port, tls } => {
                let default = if *tls { TLS_PORT } else { PLAIN_PORT };
                let scheme = if *tls { "https" } else { "http" };
                if *port == default { format!("{scheme}://{}", bracket(host)) } else { format!("{scheme}://{}:{port}", bracket(host)) }
            }
        }
    }

    fn url(&self, path: &str) -> String {
        match self {
            Self::Unix(_) => format!("ws://localhost{path}"),
            Self::Tcp { host, port, tls } => format!("{}://{}:{port}{path}", if *tls { "wss" } else { "ws" }, bracket(host)),
        }
    }

    /// Open a WebSocket at `path` asking for `subprotocol`.
    pub async fn websocket(&self, path: &str, subprotocol: &str) -> Result<Ws> {
        let fut = async {
            let io: Box<dyn Io> = match self {
                Self::Unix(p) => Box::new(tokio::net::UnixStream::connect(p).await?),
                Self::Tcp { host, port, tls } => {
                    let tcp = connect_any(&crate::dns::resolve(host, *port).await?).await?;
                    tcp.set_nodelay(true)?;
                    if *tls {
                        let connector = tls_connector();
                        let name = rustls::pki_types::ServerName::try_from(host.clone()).map_err(|e| ClientError::Tls(e.to_string()))?;
                        Box::new(connector.connect(name, tcp).await?)
                    } else {
                        Box::new(tcp)
                    }
                }
            };
            let mut request = self.url(path).into_client_request()?;
            request.headers_mut().insert("Sec-WebSocket-Protocol", HeaderValue::from_str(subprotocol).map_err(|e| ClientError::Protocol(e.to_string()))?);
            let config = WebSocketConfig::default().max_message_size(Some(64 << 20)).max_frame_size(Some(16 << 20));
            let (ws, response) = tokio_tungstenite::client_async_with_config(request, io, Some(config)).await.map_err(|e| match e {
                tokio_tungstenite::tungstenite::Error::Http(r) => {
                    let body = r.body().as_ref().map(|b| String::from_utf8_lossy(b).into_owned()).unwrap_or_default();
                    ClientError::Refused(format!("HTTP {} {}", r.status(), body.trim()))
                }
                other => ClientError::WebSocket(other),
            })?;
            let agreed = response.headers().get("Sec-WebSocket-Protocol").and_then(|v| v.to_str().ok()).unwrap_or_default();
            if agreed != subprotocol {
                return Err(ClientError::Protocol(format!("server didn't accept subprotocol {subprotocol}")));
            }
            Ok(ws)
        };
        tokio::time::timeout(CONNECT_TIMEOUT, fut).await.map_err(|_| ClientError::Timeout)?
    }
}

/// The first address that accepts a connection; the last error if none does.
async fn connect_any(addrs: &[std::net::SocketAddr]) -> Result<TcpStream> {
    let mut last = None;
    for addr in addrs {
        match TcpStream::connect(addr).await {
            Ok(tcp) => return Ok(tcp),
            Err(e) => last = Some(e),
        }
    }
    Err(ClientError::Io(last.unwrap_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "no addresses"))))
}

fn tls_connector() -> tokio_rustls::TlsConnector {
    static CONFIG: std::sync::OnceLock<Arc<rustls::ClientConfig>> = std::sync::OnceLock::new();
    let config = CONFIG.get_or_init(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        Arc::new(rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth())
    });
    tokio_rustls::TlsConnector::from(config.clone())
}

fn bracket(host: &str) -> String {
    if host.contains(':') { format!("[{host}]") } else { host.to_string() }
}

/// A MagicDNS name, a CGNAT `100.64/10` address, or a Tailscale IPv6 address.
pub fn is_tailnet(host: &str) -> bool {
    let name = host.trim().trim_matches(['[', ']']).to_ascii_lowercase();
    if name.ends_with(".ts.net") || name.starts_with("fd7a:115c:a1e0") {
        return true;
    }
    let octets: Vec<u8> = name.split('.').filter_map(|p| p.parse().ok()).collect();
    octets.len() == 4 && name.split('.').count() == 4 && octets[0] == 100 && (64..=127).contains(&octets[1])
}

fn split_host_port(text: &str) -> Option<(String, Option<String>)> {
    if let Some(rest) = text.strip_prefix('[') {
        let close = rest.find(']')?;
        let host = rest[..close].to_string();
        return match &rest[close + 1..] {
            "" => Some((host, None)),
            r if r.starts_with(':') => Some((host, Some(r[1..].to_string()))),
            _ => None,
        };
    }
    let colons = text.matches(':').count();
    match colons {
        0 => Some((text.to_string(), None)),
        1 => {
            let (h, p) = text.split_once(':')?;
            (!p.is_empty() && p.bytes().all(|b| b.is_ascii_digit())).then(|| (h.to_string(), Some(p.to_string())))
        }
        _ => Some((text.to_string(), None)), // bare IPv6
    }
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(v) = u8::from_str_radix(std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("zz"), 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn expand_home(p: &str) -> String {
    match (p.strip_prefix('~'), std::env::var("HOME")) {
        (Some(rest), Ok(home)) => format!("{home}{rest}"),
        _ => p.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tcp(host: &str, port: u16, tls: bool) -> Endpoint {
        Endpoint::Tcp { host: host.into(), port, tls }
    }

    #[test]
    fn addresses_follow_the_rules() {
        assert_eq!(Endpoint::parse("192.0.2.10").unwrap(), tcp("192.0.2.10", 7297, false));
        assert_eq!(Endpoint::parse("192.0.2.10:7300").unwrap(), tcp("192.0.2.10", 7300, false));
        assert_eq!(Endpoint::parse("my-mac.tail1234.ts.net").unwrap(), tcp("my-mac.tail1234.ts.net", 443, true));
        assert_eq!(Endpoint::parse("https://my-mac.tail1234.ts.net/").unwrap(), tcp("my-mac.tail1234.ts.net", 443, true));
        assert_eq!(Endpoint::parse("100.64.0.7").unwrap(), tcp("100.64.0.7", 443, true));
        assert_eq!(Endpoint::parse("100.64.0.7:7297").unwrap(), tcp("100.64.0.7", 7297, true));
        assert_eq!(Endpoint::parse("box.tail1234.ts.net:7297").unwrap(), tcp("box.tail1234.ts.net", 7297, true));
        assert_eq!(Endpoint::parse("http://100.64.0.7:7297").unwrap(), tcp("100.64.0.7", 7297, false));
        assert_eq!(Endpoint::parse("http://box:443").unwrap(), tcp("box", 443, false));
        assert_eq!(Endpoint::parse("[fd7a:115c:a1e0::1]").unwrap(), tcp("fd7a:115c:a1e0::1", 443, true));
        assert_eq!(Endpoint::parse("unix:///tmp/a%20b.sock").unwrap(), Endpoint::Unix("/tmp/a b.sock".into()));
        assert_eq!(Endpoint::parse("/tmp/x.sock").unwrap(), Endpoint::Unix("/tmp/x.sock".into()));
        assert!(Endpoint::parse("box:abc").is_err());
        assert!(Endpoint::parse("").is_err());
        assert_eq!(tcp("box", 7297, false).display(), "http://box");
        assert_eq!(tcp("box", 7300, false).display(), "http://box:7300");
        assert_eq!(tcp("fd7a:115c:a1e0::1", 443, true).display(), "https://[fd7a:115c:a1e0::1]");
        assert_eq!(tcp("box", 443, true).url("/api/control"), "wss://box:443/api/control");
    }

    #[test]
    fn tailnet_detection() {
        assert!(is_tailnet("100.64.0.1"));
        assert!(is_tailnet("100.127.255.254"));
        assert!(!is_tailnet("100.128.0.1"));
        assert!(!is_tailnet("100.63.0.1"));
        assert!(is_tailnet("Host.Tail1234.TS.NET"));
        assert!(!is_tailnet("example.com"));
    }
}
