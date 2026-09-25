import bmscl.{type Context, type Request, type Response, Context}
import bmscl/log

pub fn handle(_req: Request, ctx: Context) -> Response {
  let Context(log: logger, ..) = ctx
  log.write(logger, "info", "form-service submit invoked")
  bmscl.text(202, "accepted: ores-forms -> ores-middleware -> opto-sync")
}
