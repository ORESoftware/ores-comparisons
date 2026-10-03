import bmscl

pub type AppContext {
  AppContext(platform: bmscl.Context, label: String)
}

pub fn build_context(ctx: bmscl.Context) -> AppContext {
  AppContext(platform: ctx, label: "primary")
}

pub fn run(request: bmscl.Request, ctx: AppContext) -> bmscl.Response {
  let _ = request
  let AppContext(platform: platform, label: label) = ctx
  let _ = platform
  let _ = label
  bmscl.text(200, "ok")
}

pub fn export() -> bmscl.Module(bmscl.Request, bmscl.Response) {
  bmscl.module_with_context(bmscl.Lambda, build_context, run)
}
