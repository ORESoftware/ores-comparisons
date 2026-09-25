use std::io::{self, Read};

fn main() {
    let mut input = String::new();
    let _ = io::stdin().read_to_string(&mut input);
    println!(
        "{{\"ok\":true,\"stack\":\"scintilla-run\",\"scenario\":\"cached-rpc\",\"operation\":\"GetCachedComparison\",\"inputBytes\":{}}}",
        input.len()
    );
}
