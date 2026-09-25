import readline from 'node:readline';

const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

for await (const line of lines) {
  if (!line.trim()) continue;
  const request = JSON.parse(line);
  process.stdout.write(`${JSON.stringify({
    protocol: request.protocol,
    invocationId: request.invocationId,
    result: {
      status: 'ok',
      payload: {
        ok: true,
        repo: 'message-worker',
        scenario: 'big-org-example-collaboration',
        stack: 'scintilla-run',
        traceparent: request.traceparent ?? null,
      },
    },
  })}\n`);
}
