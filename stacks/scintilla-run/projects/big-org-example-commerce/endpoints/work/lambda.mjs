import readline from 'node:readline';

const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

for await (const line of lines) {
  if (!line.trim()) continue;
  const request = JSON.parse(line);
  const response = {
    protocol: request.protocol,
    invocationId: request.invocationId,
    result: {
      status: 'ok',
      payload: {
        ok: true,
        scenario: 'commerce',
        stack: 'scintilla-run',
        traceparent: request.traceparent ?? null,
        integrations: ['ores-otel', 'ores-forms', 'opto-sync', 'ores-chat', 'ores-convo', 'ores-rate-limit', 'ores-middleware', 'ores-redis-lru-cache', 'api-docs', 'ores-sops'],
      },
    },
  };
  process.stdout.write(`${JSON.stringify(response)}\n`);
}
