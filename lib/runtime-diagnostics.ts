/**
 * An in-memory transport trace for authorized compatibility checks. It never
 * records request URLs, headers, bodies, cookie names/values, response bodies,
 * or account identifiers.
 */

export type RuntimeDiagnosticPhase =
  | "cas-login"
  | "cas-mfa"
  | "jwxt-sso"
  | "jwxt-app-init"
  | "jwxt-api"
  | "jwxt-mobile";

export type RuntimeDiagnosticEvent = "request" | "response" | "transport-error" | "envelope";

export type RuntimeDiagnosticOperation =
  | "cas-auth"
  | "student-info"
  | "schedule"
  | "current-week";

export interface RuntimeDiagnosticEntry {
  readonly at: string;
  readonly phase: RuntimeDiagnosticPhase;
  readonly event: RuntimeDiagnosticEvent;
  readonly method: "GET" | "POST";
  readonly status?: number;
  readonly content: "html" | "json" | "empty" | "other";
  readonly redirected?: boolean;
  readonly errorKind?: string;
  readonly operation?: RuntimeDiagnosticOperation;
  readonly envelopeCode?: string;
}

const MAX_ENTRIES = 80;
const entries: RuntimeDiagnosticEntry[] = [];
let activeOperation: RuntimeDiagnosticOperation | undefined;

function contentClass(contentType: string | string[] | undefined): RuntimeDiagnosticEntry["content"] {
  const value = Array.isArray(contentType) ? contentType.join(",") : contentType ?? "";
  if (!value) return "empty";
  if (value.includes("json")) return "json";
  if (value.includes("html")) return "html";
  return "other";
}

/** Classifies an internal request without retaining its URL or parameters. */
export function classifyRuntimeRequest(url: string): RuntimeDiagnosticPhase | null {
  try {
    const parsed = new URL(url);
    if (parsed.hostname === "cer.ysu.edu.cn") {
      return parsed.pathname.includes("reAuth") || parsed.pathname.includes("dynamicCode")
        ? "cas-mfa"
        : "cas-login";
    }
    if (parsed.hostname !== "jwxt.ysu.edu.cn") return null;
    if (parsed.pathname.includes("/appShow.do")) return "jwxt-app-init";
    if (parsed.pathname.includes("/emaphome/portal/")) return "jwxt-sso";
    if (parsed.pathname.includes("/jwmobile/")) return "jwxt-mobile";
    return "jwxt-api";
  } catch {
    return null;
  }
}

function add(entry: RuntimeDiagnosticEntry): void {
  entries.push({ ...entry, operation: activeOperation });
  if (entries.length > MAX_ENTRIES) entries.splice(0, entries.length - MAX_ENTRIES);
}

export function recordRuntimeRequest(phase: RuntimeDiagnosticPhase, method: "GET" | "POST"): void {
  add({ at: new Date().toISOString(), phase, event: "request", method, content: "empty" });
}

export function recordRuntimeResponse(
  phase: RuntimeDiagnosticPhase,
  method: "GET" | "POST",
  response: { status: number; headers: Readonly<Record<string, string | string[]>>; url: string },
  requestedUrl: string,
): void {
  let redirected = false;
  try {
    redirected = new URL(response.url).hostname !== new URL(requestedUrl).hostname;
  } catch {
    // A malformed final URL is not useful for this safe trace.
  }
  add({
    at: new Date().toISOString(),
    phase,
    event: "response",
    method,
    status: response.status,
    content: contentClass(response.headers["content-type"]),
    redirected,
  });
}

export function recordRuntimeTransportError(
  phase: RuntimeDiagnosticPhase,
  method: "GET" | "POST",
  error: unknown,
): void {
  const errorKind = error instanceof Error && error.name ? error.name.slice(0, 80) : "UnknownError";
  add({
    at: new Date().toISOString(),
    phase,
    event: "transport-error",
    method,
    content: "empty",
    errorKind,
  });
}

/** Records only the top-level JSON envelope code, never a server message or body. */
export function recordRuntimeEnvelope(code: unknown): void {
  const envelopeCode = typeof code === "string" || typeof code === "number"
    ? String(code).slice(0, 40)
    : "non-scalar";
  add({
    at: new Date().toISOString(),
    phase: "jwxt-api",
    event: "envelope",
    method: "POST",
    content: "json",
    envelopeCode,
  });
}

export function setRuntimeDiagnosticOperation(operation: RuntimeDiagnosticOperation | undefined): void {
  activeOperation = operation;
}

export function clearRuntimeDiagnostics(): void {
  entries.length = 0;
  activeOperation = undefined;
}

export function getRuntimeDiagnostics(): readonly RuntimeDiagnosticEntry[] {
  return [...entries];
}
