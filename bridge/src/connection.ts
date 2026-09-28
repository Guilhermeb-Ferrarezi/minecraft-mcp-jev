// Conexão TCP com o mod (protocolo em docs/PROTOCOL.md): JSON por linha,
// primeiro "hello" com token, depois pedido/resposta casados por id e
// eventos assíncronos (chat, morte) guardados num buffer para get_events.
import net from "node:net";
import fs from "node:fs";

export interface BridgeSettings {
  host: string;
  port: number;
  token: string;
}

export interface BridgeEvent {
  event: string;
  data: unknown;
  receivedAt: string;
}

export class ModError extends Error {
  constructor(
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

interface Pending {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

const MAX_EVENTS = 200;

/**
 * Lê host/porta/token do ambiente. JEV_BRIDGE_CONFIG pode apontar direto para
 * o config/jevbridge.properties da instância do GTNH, assim o token nunca
 * precisa ser copiado à mão.
 */
export function settingsFromEnv(env: NodeJS.ProcessEnv = process.env): BridgeSettings {
  const props: Record<string, string> = {};
  if (env.JEV_BRIDGE_CONFIG) {
    const text = fs.readFileSync(env.JEV_BRIDGE_CONFIG, "utf8");
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*([^#!][^=:\s]*)\s*[=:]\s*(.*)$/);
      if (m) props[m[1]] = m[2].trim();
    }
  }
  const token = env.JEV_BRIDGE_TOKEN ?? props.token;
  if (!token) {
    throw new Error(
      "Defina JEV_BRIDGE_CONFIG (caminho do config/jevbridge.properties da instância) ou JEV_BRIDGE_TOKEN.",
    );
  }
  const host = env.JEV_BRIDGE_HOST ?? "127.0.0.1";
  const port = Number(env.JEV_BRIDGE_PORT ?? props.port ?? 25599);
  return { host, port, token };
}

export class ModConnection {
  private socket: net.Socket | null = null;
  private connecting: Promise<void> | null = null;
  private buffer = "";
  private nextId = 1;
  private pending = new Map<number, Pending>();
  private events: BridgeEvent[] = [];
  private droppedEvents = 0;
  public info: Record<string, unknown> | null = null;

  constructor(private readonly settings: BridgeSettings) {}

  /** Envia um pedido e espera a resposta. Conecta (ou reconecta) sob demanda. */
  async call(method: string, params: Record<string, unknown> = {}, timeoutMs = 15000): Promise<unknown> {
    await this.ensureConnected();
    return this.send(method, params, timeoutMs);
  }

  /** Devolve e limpa os eventos acumulados desde a última chamada. */
  drainEvents(): { events: BridgeEvent[]; dropped: number } {
    const out = { events: this.events, dropped: this.droppedEvents };
    this.events = [];
    this.droppedEvents = 0;
    return out;
  }

  close(): void {
    this.socket?.destroy();
    this.socket = null;
  }

  private ensureConnected(): Promise<void> {
    if (this.socket && !this.socket.destroyed) return Promise.resolve();
    if (!this.connecting) {
      this.connecting = this.open().finally(() => {
        this.connecting = null;
      });
    }
    return this.connecting;
  }

  private async open(): Promise<void> {
    const { host, port, token } = this.settings;
    const socket = await new Promise<net.Socket>((resolve, reject) => {
      const s = net.createConnection({ host, port });
      s.once("connect", () => resolve(s));
      s.once("error", (err) =>
        reject(
          new ModError(
            "not_connected",
            `Não consegui conectar no mod em ${host}:${port} (${err.message}). O Minecraft está aberto com o JevBridge?`,
          ),
        ),
      );
    });
    socket.setNoDelay(true);
    socket.setEncoding("utf8");
    this.buffer = "";
    this.socket = socket;
    socket.on("data", (chunk: string) => this.onData(chunk));
    socket.on("close", () => this.onClose(socket));
    socket.on("error", () => {
      /* "close" vem logo depois e trata */
    });
    try {
      this.info = (await this.send("hello", { token, client: "jevbridge-mcp", protocol: 1 }, 5000)) as Record<
        string,
        unknown
      >;
    } catch (err) {
      socket.destroy();
      throw err;
    }
  }

  private send(method: string, params: Record<string, unknown>, timeoutMs: number): Promise<unknown> {
    const socket = this.socket;
    if (!socket || socket.destroyed) {
      return Promise.reject(new ModError("not_connected", "sem conexão com o mod"));
    }
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new ModError("timeout", `o mod não respondeu ${method} em ${Math.round(timeoutMs / 1000)}s`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      socket.write(JSON.stringify({ id, method, params }) + "\n");
    });
  }

  private onData(chunk: string): void {
    this.buffer += chunk;
    let nl: number;
    while ((nl = this.buffer.indexOf("\n")) >= 0) {
      const line = this.buffer.slice(0, nl).trim();
      this.buffer = this.buffer.slice(nl + 1);
      if (!line) continue;
      let msg: any;
      try {
        msg = JSON.parse(line);
      } catch {
        continue;
      }
      if (typeof msg.event === "string") {
        this.events.push({ event: msg.event, data: msg.data, receivedAt: new Date().toISOString() });
        if (this.events.length > MAX_EVENTS) {
          this.events.shift();
          this.droppedEvents++;
        }
        continue;
      }
      const p = typeof msg.id === "number" ? this.pending.get(msg.id) : undefined;
      if (!p) continue;
      this.pending.delete(msg.id);
      clearTimeout(p.timer);
      if (msg.ok) p.resolve(msg.result);
      else p.reject(new ModError(msg.error?.code ?? "error", msg.error?.message ?? "erro desconhecido"));
    }
  }

  private onClose(socket: net.Socket): void {
    if (this.socket === socket) this.socket = null;
    for (const [id, p] of this.pending) {
      clearTimeout(p.timer);
      p.reject(new ModError("disconnected", "a conexão com o Minecraft caiu"));
      this.pending.delete(id);
    }
  }
}
