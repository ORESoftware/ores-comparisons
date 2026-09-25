namespace OresComparisons.RpcGraphql;

enum RpcEventKind {
  created: "created",
  updated: "updated",
  deleted: "deleted",
}

model RpcResource {
  id: string;
  tenant_id: string;
  resource_key: string;
  display_name: string;
  revision: int32;
  active: boolean;
}

model RpcEvent {
  id: string;
  tenant_id: string;
  resource_id: string;
  kind: RpcEventKind;
  payload_json: string;
  sequence: int32;
}

model GetResourceRequest {
  tenant_id: string;
  resource_key: string;
}

model GetResourceResponse {
  resource: RpcResource;
}

interface ResourceService {
  op get_resource(request: GetResourceRequest): GetResourceResponse;
}
