import bmscl

pub fn handler(
  _request: bmscl.Request,
  _context: bmscl.Context,
) -> bmscl.Response {
  bmscl.text(
    200,
    "{\"ok\":true,\"repo\":\"workspace-web\",\"scenario\":\"big-org-example-collaboration\",\"stack\":\"beamscale\"}",
  )
}

pub fn module() -> bmscl.Module(bmscl.Request, bmscl.Response) {
  bmscl.lambda(handler)
}
