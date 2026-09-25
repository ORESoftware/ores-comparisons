use axum::{routing::{get, post}, Json, Router};
use serde_json::{json, Value};

async fn health() -> Json<Value> {
    Json(json!({"ok": true}))
}

async fn work() -> Json<Value> {
    Json(json!({
        "ok": true,
        "repo": "catalog-service",
        "scenario": "big-org-example-commerce",
        "stack": "ores-stack"
    }))
}

#[tokio::main]
async fn main() {
    let app = Router::new()
        .route("/healthz", get(health))
        .route("/v1/catalog", post(work));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
