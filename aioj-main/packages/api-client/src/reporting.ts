import { apiUrl, ApiError } from './index';

interface ReporterOptions {
  appName: string;
  dedupWindowMs?: number;
}

type Kind = 'uncaught' | 'unhandled-rejection' | 'api-5xx' | 'manual';

interface OutgoingReport {
  kind: Kind;
  message: string;
  stack?: string;
  code?: number;
  traceId?: string;
  url: string;
  userAgent: string;
  when: string;
}

const recent = new Map<string, number>();

function fingerprint(kind: Kind, name: string, message: string): string {
  return `${kind}::${name}::${(message || '').slice(0, 120)}`;
}

function shouldSend(kind: Kind, name: string, message: string, windowMs: number): boolean {
  const key = fingerprint(kind, name, message);
  const now = Date.now();
  const last = recent.get(key);
  if (last && now - last < windowMs) return false;
  recent.set(key, now);
  if (recent.size > 200) {
    const cutoff = now - windowMs * 5;
    for (const [k, v] of recent) {
      if (v < cutoff) recent.delete(k);
    }
  }
  return true;
}

async function send(report: OutgoingReport, appName: string): Promise<void> {
  try {
    await fetch(apiUrl('/api/v1/diagnostics/error'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Client-App': appName },
      body: JSON.stringify(report),
      keepalive: true
    });
  } catch {
    // Reporter failures must never break the page.
  }
}

export function installErrorReporter(options: ReporterOptions): void {
  if (typeof window === 'undefined') return;
  const windowMs = options.dedupWindowMs ?? 60_000;

  window.addEventListener('error', (event) => {
    const errorName = event.error instanceof Error ? event.error.name : 'Error';
    const msg = event.message || String(event.error || 'unknown error');
    if (!shouldSend('uncaught', errorName, msg, windowMs)) return;
    void send({
      kind: 'uncaught',
      message: msg.slice(0, 500),
      stack: event.error?.stack?.slice(0, 8000),
      url: window.location.href.slice(0, 500),
      userAgent: window.navigator.userAgent.slice(0, 300),
      when: new Date().toISOString()
    }, options.appName);
  });

  window.addEventListener('unhandledrejection', (event) => {
    const reason: unknown = event.reason;
    const errorName = reason instanceof Error ? reason.name : 'Error';
    const msg = reason instanceof Error
      ? reason.message
      : typeof reason === 'string'
        ? reason
        : 'Unhandled rejection';
    if (!shouldSend('unhandled-rejection', errorName, msg, windowMs)) return;
    const code = reason instanceof ApiError ? reason.code : undefined;
    const traceId = reason instanceof ApiError ? (reason.traceId ?? undefined) : undefined;
    void send({
      kind: 'unhandled-rejection',
      message: msg.slice(0, 500),
      stack: reason instanceof Error ? reason.stack?.slice(0, 8000) : undefined,
      code,
      traceId,
      url: window.location.href.slice(0, 500),
      userAgent: window.navigator.userAgent.slice(0, 300),
      when: new Date().toISOString()
    }, options.appName);
  });
}

export function reportApiError(error: ApiError, appName: string, windowMs = 60_000): void {
  if (error.code < 50000) return;
  if (!shouldSend('api-5xx', error.name, error.message, windowMs)) return;
  void send({
    kind: 'api-5xx',
    message: error.message.slice(0, 500),
    stack: error.stack?.slice(0, 8000),
    code: error.code,
    traceId: error.traceId ?? undefined,
    url: typeof window !== 'undefined' ? window.location.href.slice(0, 500) : '',
    userAgent: typeof window !== 'undefined' ? window.navigator.userAgent.slice(0, 300) : '',
    when: new Date().toISOString()
  }, appName);
}
