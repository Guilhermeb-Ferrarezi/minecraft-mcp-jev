#!/usr/bin/env node
// Servidor MCP (stdio) que expõe o mod JevBridge como ferramentas para uma IA.
// Cada ferramenta é um repasse fino para um método do protocolo do mod; a
// lógica (pathfinding, mineração, checagens) mora no mod, na thread do jogo.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { ModConnection, ModError, settingsFromEnv } from "./connection.js";

const conn = new ModConnection(settingsFromEnv());

const server = new McpServer({ name: "jevbridge", version: "0.1.0" });

type ToolResult = { content: { type: "text"; text: string }[]; isError?: boolean };

function text(value: unknown): ToolResult {
  return { content: [{ type: "text", text: JSON.stringify(value, null, 2) }] };
}

/**
 * Chama o mod e formata a resposta. Falhas de ação (status "failed") voltam
 * como resultado normal, porque a IA precisa ler o motivo e decidir; só erro de
 * protocolo/conexão vira isError.
 */
async function forward(method: string, params: Record<string, unknown> = {}, timeoutMs = 15000): Promise<ToolResult> {
  try {
    const result = await conn.call(method, params, timeoutMs);
    return text(result);
  } catch (err) {
    const code = err instanceof ModError ? err.code : "error";
    const message = err instanceof Error ? err.message : String(err);
    return { content: [{ type: "text", text: JSON.stringify({ error: code, message }, null, 2) }], isError: true };
  }
}

/** Espera a ação terminar no mod: timeout da ação + folga. */
function actionTimeout(seconds: number | undefined, fallback: number): number {
  return ((seconds ?? fallback) + 10) * 1000;
}

const coord = z.number().int();
const readOnly = { readOnlyHint: true, destructiveHint: false, openWorldHint: false };
const acts = { readOnlyHint: false, destructiveHint: false, openWorldHint: false };

server.registerTool(
  "get_state",
  {
    title: "Estado do jogador",
    description:
      "Posição (position = pés; blockPosition = bloco onde os pés estão), vida, fome, item na mão, dimensão, bioma, " +
      "hora do dia, e o bloco/entidade na mira. Chame primeiro para se orientar. " +
      "Coordenadas: Y é altura; yaw 0 = sul (+Z), 90 = oeste (-X), 180 = norte, 270 = leste.",
    annotations: readOnly,
  },
  () => forward("get_state"),
);

server.registerTool(
  "get_inventory",
  {
    title: "Inventário",
    description: "Itens do inventário. Slots 0-8 são a hotbar (use select_slot), 9-35 inventário, 36-39 armadura.",
    annotations: readOnly,
  },
  () => forward("get_inventory"),
);

server.registerTool(
  "get_block",
  {
    title: "Bloco numa posição",
    description: "Nome e propriedades do bloco em (x, y, z).",
    inputSchema: { x: coord, y: coord, z: coord },
    annotations: readOnly,
  },
  (args) => forward("get_block", args),
);

server.registerTool(
  "get_blocks_around",
  {
    title: "Blocos ao redor",
    description:
      "Todos os blocos não-ar num cubo em volta do jogador. Raio máximo 6 (cubo de 13x13x13) — a resposta cresce rápido; " +
      "para procurar algo específico prefira find_blocks.",
    inputSchema: { radius: z.number().int().min(1).max(6).optional().describe("padrão 3") },
    annotations: readOnly,
  },
  (args) => forward("get_blocks", args),
);

server.registerTool(
  "find_blocks",
  {
    title: "Procurar blocos",
    description:
      "Procura blocos cujo id ou nome contenha o texto (sem diferenciar maiúsculas), do mais perto ao mais longe. " +
      "No GTNH os minérios do GregTech aparecem pelo nome de exibição (ex.: 'Magnetite'), não pelo id. " +
      "Ex.: query 'log', 'iron', 'magnetite', 'crafting'.",
    inputSchema: {
      query: z.string().min(1),
      radius: z.number().int().min(1).max(32).optional().describe("horizontal, padrão 16"),
      verticalRadius: z.number().int().min(1).max(32).optional().describe("padrão 8"),
      limit: z.number().int().min(1).max(50).optional().describe("padrão 10"),
    },
    annotations: readOnly,
  },
  (args) => forward("find_blocks", args, 30000),
);

server.registerTool(
  "get_entities",
  {
    title: "Entidades por perto",
    description: "Mobs, jogadores e itens no chão, do mais perto ao mais longe, com id (para attack), vida e se é hostil.",
    inputSchema: {
      radius: z.number().min(1).max(64).optional().describe("padrão 16"),
      limit: z.number().int().min(1).max(100).optional().describe("padrão 30"),
    },
    annotations: readOnly,
  },
  (args) => forward("get_entities", args),
);

server.registerTool(
  "look",
  {
    title: "Virar a câmera",
    description: "Define yaw (0 sul, 90 oeste, 180 norte, 270 leste) e pitch (-90 cima, 0 horizonte, 90 baixo).",
    inputSchema: { yaw: z.number(), pitch: z.number().min(-90).max(90) },
    annotations: acts,
  },
  (args) => forward("look", args),
);

server.registerTool(
  "look_at",
  {
    title: "Olhar para um ponto",
    description: "Vira a câmera para o ponto (x, y, z). Use x+0.5 / z+0.5 para o centro de um bloco.",
    inputSchema: { x: z.number(), y: z.number(), z: z.number() },
    annotations: acts,
  },
  (args) => forward("look_at", args),
);

server.registerTool(
  "select_slot",
  {
    title: "Selecionar slot da hotbar",
    description: "Troca o item na mão para o slot 0-8 da hotbar.",
    inputSchema: { slot: z.number().int().min(0).max(8) },
    annotations: acts,
  },
  (args) => forward("select_slot", args),
);

server.registerTool(
  "chat",
  {
    title: "Mandar mensagem no chat",
    description:
      "Envia uma mensagem no chat (máx. 100 caracteres). Comandos com '/' só funcionam se allowCommands=true no " +
      "config/jevbridge.properties.",
    inputSchema: { message: z.string().min(1).max(100) },
    annotations: { ...acts, openWorldHint: true },
  },
  (args) => forward("chat", args),
);

server.registerTool(
  "attack",
  {
    title: "Atacar entidade",
    description:
      "Um golpe na entidade (id vindo de get_entities). Precisa estar a até ~3,5 blocos; senão responde too_far — " +
      "use walk_to com range 2 antes. Para matar, chame de novo até a vida chegar a 0.",
    inputSchema: { entityId: z.number().int() },
    annotations: { ...acts, destructiveHint: true },
  },
  (args) => forward("attack", args),
);

server.registerTool(
  "stop",
  {
    title: "Parar",
    description: "Cancela a ação em andamento (andar, minerar...) e solta todos os controles.",
    annotations: acts,
  },
  () => forward("stop"),
);

server.registerTool(
  "walk_to",
  {
    title: "Andar até",
    description:
      "Anda até (x, y, z) com pathfinding: contorna obstáculos, sobe degraus de 1 bloco, desce até 3, evita lava. " +
      "Não quebra nem coloca blocos, não sobe escadas de mão e não atravessa paredes. y é a altura dos PÉS no destino " +
      "(o bloco de ar em cima do chão). range = a quantos blocos do alvo já conta como chegada (padrão 1; use 2-3 " +
      "para chegar perto de um bloco sólido que quer minerar). Responde quando chega ou falha " +
      "(status done / failed com reason unreachable / timeout).",
    inputSchema: {
      x: coord,
      y: coord,
      z: coord,
      range: z.number().min(0).max(16).optional(),
      sprint: z.boolean().optional().describe("correr (gasta fome); padrão false"),
      timeoutSeconds: z.number().min(1).max(300).optional().describe("padrão 60"),
    },
    annotations: acts,
  },
  (args) => forward("walk_to", args, actionTimeout(args.timeoutSeconds, 60)),
);

server.registerTool(
  "mine_block",
  {
    title: "Quebrar bloco",
    description:
      "Quebra o bloco em (x, y, z) com o item da mão. Precisa estar ao alcance (~4,5 blocos do olho); senão responde " +
      "too_far. No GTNH, sem a ferramenta certa o bloco pode demorar muito ou não dropar nada: equipe a ferramenta " +
      "com select_slot antes.",
    inputSchema: {
      x: coord,
      y: coord,
      z: coord,
      timeoutSeconds: z.number().min(1).max(120).optional().describe("padrão 30"),
    },
    annotations: { ...acts, destructiveHint: true },
  },
  (args) => forward("mine_block", args, actionTimeout(args.timeoutSeconds, 30)),
);

server.registerTool(
  "place_block",
  {
    title: "Colocar bloco",
    description:
      "Coloca o bloco da mão em (x, y, z), que precisa estar vazio e encostado em algum bloco sólido. Selecione o " +
      "bloco com select_slot antes. Não coloca dentro do próprio jogador.",
    inputSchema: { x: coord, y: coord, z: coord },
    annotations: acts,
  },
  (args) => forward("place_block", args, 15000),
);

server.registerTool(
  "use_item",
  {
    title: "Usar item da mão",
    description:
      "Segura o botão direito por N ticks (20 ticks = 1 s) mirando para onde está olhando: comer/beber (~32 ticks), " +
      "puxar arco, usar item. Para interagir com um bloco, use look_at nele antes.",
    inputSchema: { ticks: z.number().int().min(1).max(200).optional().describe("padrão 40") },
    annotations: acts,
  },
  (args) => forward("use_item", args, 25000),
);

server.registerTool(
  "move",
  {
    title: "Movimento manual",
    description:
      "Controle direto por N ticks: forward (-1..1), strafe (-1 direita..1 esquerda), jump, sneak, sprint. Na direção " +
      "para onde está olhando. Útil para ajustes finos; para ir a um lugar prefira walk_to.",
    inputSchema: {
      forward: z.number().min(-1).max(1).optional(),
      strafe: z.number().min(-1).max(1).optional(),
      jump: z.boolean().optional(),
      sneak: z.boolean().optional(),
      sprint: z.boolean().optional(),
      ticks: z.number().int().min(1).max(200).optional().describe("padrão 10"),
    },
    annotations: acts,
  },
  (args) => forward("move", args, 25000),
);

server.registerTool(
  "get_events",
  {
    title: "Eventos recentes",
    description:
      "Mensagens de chat, mortes e entrada/saída de mundo recebidas desde a última chamada (e limpa a lista). " +
      "Chame de tempos em tempos para não perder o que falaram com você.",
    annotations: readOnly,
  },
  async () => {
    try {
      // Garante a conexão: eventos só chegam com o socket aberto.
      await conn.call("status");
    } catch {
      /* segue e devolve o que tiver */
    }
    return text(conn.drainEvents());
  },
);

async function main(): Promise<void> {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // stdout é o canal MCP; log só em stderr.
  console.error("jevbridge-mcp pronto (stdio)");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
