include!(env!("ORES_PAGES_RS"));

#[tokio::main]
async fn main() {
    let addr = std::env::var("BIND_ADDR").unwrap_or_else(|_| "127.0.0.1:3110".to_owned());
    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .expect("bind comparison server");
    eprintln!("ORES_COMPARISON_READY {addr}");
    axum::serve(listener, ores_pages_router::<()>())
        .await
        .expect("serve comparison project");
}
