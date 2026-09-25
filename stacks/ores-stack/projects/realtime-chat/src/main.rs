use axum::{routing::{get, post}, Json, Router};
use serde_json::{json, Value};
use std::{env, net::SocketAddr};

async fn publish(Json(body): Json<Value>) -> Json<Value> {
    Json(json!({"ok": true, "accepted": true, "body": body, "services": ["ores-chat", "ores-convo", "opto-sync"]}))
}

async fn history() -> Json<Value> {
    Json(json!({"ok": true, "messages": [], "cache": "ores-redis-lru-cache"}))
}

#[tokio::main]
async fn main() {
    let app = Router::new()
        .route("/v1/chat/messages", post(publish))
        .route("/v1/chat/history", get(history));
    let port = env::var("PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(8080);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
