namespace Ores.Comparisons;

model IntegrationEnvironment {
  ores_otel_exporter_otlp_endpoint?: string;
  ores_otel_service_name?: string;
  ores_forms_api_url?: string;
  opto_sync_api_url?: string;
  ores_chat_api_url?: string;
  ores_convo_api_url?: string;
  ores_rate_limit_api_url?: string;
  ores_middleware_profile?: string;
  ores_redis_lru_api_url?: string;
  api_docs_route_map?: string;
  sops_age_key_file?: string;
  sops_age_recipients?: string;
  ores_sops_manifest?: string;
}
