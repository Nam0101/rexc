//! Host lookup with a MagicDNS fallback.
//!
//! On Android with "Use Tailscale DNS" off (the default for many people), the
//! OS resolver has never heard of `*.ts.net`, while the tailnet itself is
//! routed. Tailscale answers those names on every node at 100.100.100.100, so a
//! tailnet name the system cannot resolve is asked there directly. TLS still
//! verifies the certificate against the name, never the address.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use tokio::net::UdpSocket;

use crate::endpoint::is_tailnet;
use crate::{ClientError, Result};

const MAGIC_DNS: SocketAddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(100, 100, 100, 100)), 53);
const QUERY_TIMEOUT: Duration = Duration::from_secs(3);
const HEADER_LEN: usize = 12;
const TYPE_A: u16 = 1;

/// Every address `host` resolves to, system resolver first.
pub async fn resolve(host: &str, port: u16) -> Result<Vec<SocketAddr>> {
    let system = match tokio::net::lookup_host((host, port)).await {
        Ok(addrs) => {
            let addrs: Vec<SocketAddr> = addrs.collect();
            if !addrs.is_empty() {
                return Ok(addrs);
            }
            std::io::Error::new(std::io::ErrorKind::NotFound, "no addresses")
        }
        Err(e) => e,
    };
    if !is_tailnet(host) {
        return Err(ClientError::Io(system));
    }
    match magic_dns_a(host).await {
        Ok(ips) => Ok(ips.into_iter().map(|ip| SocketAddr::new(IpAddr::V4(ip), port)).collect()),
        Err(magic) => Err(ClientError::Address(format!("can't resolve {host}: {system}; MagicDNS at {MAGIC_DNS}: {magic}"))),
    }
}

async fn magic_dns_a(host: &str) -> std::io::Result<Vec<Ipv4Addr>> {
    let id = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.subsec_nanos() as u16).unwrap_or(0x5245);
    let query = a_query(id, host)?;
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).await?;
    socket.send_to(&query, MAGIC_DNS).await?;
    let mut buf = [0u8; 512];
    let (n, _) = tokio::time::timeout(QUERY_TIMEOUT, socket.recv_from(&mut buf))
        .await
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "no answer"))??;
    a_records(&buf[..n], id)
}

/// A recursive A query for `host`, RFC 1035 wire format.
fn a_query(id: u16, host: &str) -> std::io::Result<Vec<u8>> {
    let mut q = Vec::with_capacity(HEADER_LEN + host.len() + 6);
    q.extend_from_slice(&id.to_be_bytes());
    q.extend_from_slice(&[0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]); // RD=1, one question
    for label in host.trim_end_matches('.').split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, format!("bad label in {host:?}")));
        }
        q.push(label.len() as u8);
        q.extend_from_slice(label.as_bytes());
    }
    q.extend_from_slice(&[0, 0, TYPE_A as u8, 0, 1]); // root, QTYPE A, QCLASS IN
    Ok(q)
}

/// The A records in a response to `id`; other record types are skipped.
fn a_records(msg: &[u8], id: u16) -> std::io::Result<Vec<Ipv4Addr>> {
    let bad = |what: &str| std::io::Error::new(std::io::ErrorKind::InvalidData, what.to_string());
    if msg.len() < HEADER_LEN || msg[0..2] != id.to_be_bytes() {
        return Err(bad("not our answer"));
    }
    if msg[3] & 0x0F != 0 {
        return Err(bad(&format!("rcode {}", msg[3] & 0x0F)));
    }
    let questions = u16::from_be_bytes([msg[4], msg[5]]) as usize;
    let answers = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    let mut i = HEADER_LEN;
    for _ in 0..questions {
        i = skip_name(msg, i).ok_or_else(|| bad("truncated question"))? + 4;
    }
    let mut out = Vec::new();
    for _ in 0..answers {
        i = skip_name(msg, i).ok_or_else(|| bad("truncated answer"))?;
        let fixed = msg.get(i..i + 10).ok_or_else(|| bad("truncated answer"))?;
        let rtype = u16::from_be_bytes([fixed[0], fixed[1]]);
        let len = u16::from_be_bytes([fixed[8], fixed[9]]) as usize;
        i += 10;
        let data = msg.get(i..i + len).ok_or_else(|| bad("truncated rdata"))?;
        if rtype == TYPE_A && len == 4 {
            out.push(Ipv4Addr::new(data[0], data[1], data[2], data[3]));
        }
        i += len;
    }
    if out.is_empty() { Err(bad("no A record")) } else { Ok(out) }
}

/// The index just past the name at `i`; a compression pointer ends a name.
fn skip_name(msg: &[u8], mut i: usize) -> Option<usize> {
    loop {
        let len = *msg.get(i)? as usize;
        if len == 0 {
            return Some(i + 1);
        }
        if len & 0xC0 == 0xC0 {
            return Some(i + 2);
        }
        i += 1 + len;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_a_query_in_wire_format() {
        let q = a_query(0xBEEF, "mini.tail1234.ts.net").unwrap();
        assert_eq!(&q[..12], &[0xBE, 0xEF, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
        assert_eq!(&q[12..], b"\x04mini\x08tail1234\x02ts\x03net\0\0\x01\0\x01");
        assert!(a_query(1, "bad..name").is_err());
    }

    #[test]
    fn reads_a_records_and_skips_cnames() {
        let mut m = vec![0xBE, 0xEF, 0x81, 0x80, 0, 1, 0, 2, 0, 0, 0, 0];
        m.extend_from_slice(b"\x04mini\x02ts\x03net\0\0\x01\0\x01");
        // CNAME pointing back at the question name, then an A record.
        m.extend_from_slice(&[0xC0, 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xC0, 12]);
        m.extend_from_slice(&[0xC0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 100, 123, 228, 123]);
        assert_eq!(a_records(&m, 0xBEEF).unwrap(), vec![Ipv4Addr::new(100, 123, 228, 123)]);
        assert!(a_records(&m, 0x0001).is_err(), "another query's answer");
        let mut nx = m.clone();
        nx[3] = 0x83;
        assert!(a_records(&nx, 0xBEEF).unwrap_err().to_string().contains("rcode 3"));
    }
}
