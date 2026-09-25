import bmscl.{type Context, type Request, type Response, Context}
import bmscl/log

pub fn handle(_req: Request, ctx: Context) -> Response {
  let Context(log: logger, ..) = ctx
  log.write(logger, "info", "realtime-chat history invoked")
  bmscl.text(200, "history: authoritative state is external to the invocation actor")
}
