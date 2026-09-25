use axum::{routing::{get, post}, Json, Router};
use serde_json::{json, Value};
use std::{env, net::SocketAddr};

async fn health() -> Json<Value> {
    Json(json!({"ok": true, "stack": "ores-stack"}))
}

async fn submit(Json(body): Json<Value>) -> Json<Value> {
    Json(json!({
        "ok": true,
        "accepted": true,
        "body": body,
        "pipeline": ["ores-forms", "ores-middleware", "opto-sync"],
        "otel_configured": env::var("ORES_OTEL_EXPORTER_OTLP_ENDPOINT").is_ok()
    }))
}

#[tokio::main]
async fn main() {
    let app = Router::new()
        .route("/health", get(health))
        .route("/v1/forms/submit", post(submit));
    let port = env::var("PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(8080);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
