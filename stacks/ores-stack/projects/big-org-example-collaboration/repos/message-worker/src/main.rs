use axum::{routing::{get, post}, Json, Router};
use serde_json::{json, Value};

async fn health() -> Json<Value> {
    Json(json!({"ok": true}))
}

async fn work() -> Json<Value> {
    Json(json!({
        "ok": true,
        "repo": "message-worker",
        "scenario": "big-org-example-collaboration",
        "stack": "ores-stack"
    }))
}

#[tokio::main]
async fn main() {
    let app = Router::new()
        .route("/healthz", get(health))
        .route("/v1/messages", post(work));
    let bind = std::env::var("BIND_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:32202".to_owned());
    let listener = tokio::net::TcpListener::bind(&bind).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
