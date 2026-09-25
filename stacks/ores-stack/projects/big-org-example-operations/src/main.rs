use ores_stack_pub_lib_core::{assert_module, Capability, Http, TypedModule};
use std::io::{Read, Write};
use std::net::TcpListener;

struct WorkModule;

impl TypedModule for WorkModule {
    type Kind = Http;
    type Input = String;
    type Output = String;
    type Error = String;
    type Context = ();

    const NAME: &'static str = "big-org-example-operations-work";
    const CAPABILITIES: &'static [Capability] = &[
        Capability::Clock,
        Capability::HttpClient,
        Capability::DatabaseRead,
        Capability::DatabaseWrite,
        Capability::KeyValueRead,
        Capability::KeyValueWrite,
        Capability::QueuePublish,
        Capability::SecretsRead,
    ];

    async fn handle(&self, _context: &(), input: String) -> Result<String, String> {
        Ok(format!("{{\"ok\":true,\"scenario\":\"operations\",\"input\":{input:?}}}"))
    }
}

fn main() -> std::io::Result<()> {
    assert_module::<WorkModule>();
    let bind = std::env::var("BIND_ADDR").unwrap_or_else(|_| "127.0.0.1:8080".into());
    let listener = TcpListener::bind(&bind)?;
    eprintln!("big-org-example-operations listening on {bind}");

    for stream in listener.incoming() {
        let mut stream = stream?;
        let mut request = [0_u8; 4096];
        let _ = stream.read(&mut request);
        let body = r#"{"ok":true,"scenario":"operations","stack":"ores-stack"}"#;
        write!(stream, "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}", body.len(), body)?;
    }
    Ok(())
}
