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
  "respawn",
  {
    title: "Renascer",
    description:
      "Renasce depois de morrer (o botão Respawn da tela de morte). O jogador volta no ponto de spawn, sem o " +
      "inventário (que fica no chão onde morreu). Responde wasDead=false se não estava morto.",
    annotations: acts,
  },
  () => forward("respawn"),
);

server.registerTool(
  "close_screen",
  {
    title: "Fechar tela",
    description:
      "Fecha a tela aberta no jogo (inventário, bancada, baú, menu do ESC). Use quando uma ação falhar com gui_open. " +
      "Responde closed = nome da tela fechada, ou null se não havia nenhuma.",
    annotations: acts,
  },
  () => forward("close_screen"),
);

server.registerTool(
  "move_to_hotbar",
  {
    title: "Trazer item pra hotbar",
    description:
      "Traz um item do inventário (slots 9-35) para a hotbar, para poder segurá-lo com select_slot. " +
      "Responde slot (0-8). hotbarSlot opcional; sem ele usa o primeiro slot vazio da hotbar.",
    inputSchema: {
      item: z.string().min(1).describe("modid:nome[:meta] ou nome de exibição"),
      hotbarSlot: z.number().int().min(0).max(8).optional(),
    },
    annotations: acts,
  },
  (args) => forward("move_to_hotbar", args),
);

server.registerTool(
  "use_block",
  {
    title: "Usar bloco",
    description:
      "Clique direito num bloco ao alcance: abre bancada, fornalha, baú (a tela abre no tick seguinte). " +
      "Depois de abrir uma bancada, craft aceita grade 3x3.",
    inputSchema: { x: coord, y: coord, z: coord },
    annotations: acts,
  },
  (args) => forward("use_block", args),
);

server.registerTool(
  "craft",
  {
    title: "Craftar",
    description:
      "Crafta montando a grade como um jogador. grid = 4 casas (2x2, a grade do inventário, sem precisar de " +
      "bancada) ou 9 (3x3, precisa de bancada aberta com use_block), em ordem de linha: nome do item " +
      "('minecraft:log', 'minecraft:planks:0' ou o nome de exibição) ou null. times = quantas vezes. Use " +
      "get_recipes antes: no GTNH as receitas mudam. Erros: missing_items, no_recipe, needs_table, grid_not_empty.",
    inputSchema: {
      grid: z.array(z.string().nullable()).min(4).max(9),
      times: z.number().int().min(1).max(64).optional(),
    },
    annotations: acts,
  },
  (args) => forward("craft", args, 20000),
);

server.registerTool(
  "leave_world",
  {
    title: "Sair do mundo",
    description: "Sai do mundo ou do servidor e volta ao menu principal (salva no single player).",
    annotations: acts,
  },
  () => forward("leave_world"),
);

server.registerTool(
  "list_servers",
  {
    title: "Servidores salvos",
    description: "Servidores da lista do Multiplayer (nome e endereço). Use antes de join_server.",
    annotations: readOnly,
  },
  () => forward("list_servers"),
);

server.registerTool(
  "join_server",
  {
    title: "Entrar num servidor",
    description:
      "Conecta num servidor da lista pelo nome ou endereço, a partir do menu. Responde na hora (connecting); " +
      "chame get_state até parar de dar not_in_world.",
    inputSchema: { name: z.string().min(1) },
    annotations: acts,
  },
  (args) => forward("join_server", args),
);

server.registerTool(
  "list_worlds",
  {
    title: "Mundos do single player",
    description:
      "Lista os mundos salvos (folder, name, modo de jogo) e se o jogador já está num mundo. Funciona no menu " +
      "principal. Use antes de open_world.",
    annotations: readOnly,
  },
  () => forward("list_worlds"),
);

server.registerTool(
  "open_world",
  {
    title: "Abrir mundo",
    description:
      "Abre um mundo do single player pelo nome ou pasta (de list_worlds), sem passar pelo menu. Responde na hora " +
      "com status loading; o carregamento do GTNH leva de 30 s a alguns minutos — chame get_state até parar de dar " +
      "not_in_world. Falha com already_in_world se já estiver jogando.",
    inputSchema: { name: z.string().min(1).describe("nome ou pasta do mundo") },
    annotations: acts,
  },
  (args) => forward("open_world", args),
);

/** Métodos do NEI só existem se o NEI estiver instalado no cliente. */
async function forwardNei(method: string, params: Record<string, unknown>): Promise<ToolResult> {
  const r = await forward(method, params, 60000);
  if (r.isError && r.content[0].text.includes('"unknown_method"')) {
    return {
      content: [{ type: "text", text: JSON.stringify({ error: "nei_unavailable", message: "o NEI não está instalado neste cliente" }) }],
      isError: true,
    };
  }
  return r;
}

server.registerTool(
  "search_items",
  {
    title: "Procurar itens (NEI)",
    description:
      "Procura itens existentes no modpack pelo nome (ou id), como a busca do NEI. Use para descobrir o nome exato " +
      "antes de get_recipes. Ex.: 'pickaxe', 'steel ingot', 'macerator'.",
    inputSchema: {
      query: z.string().min(1),
      limit: z.number().int().min(1).max(50).optional().describe("padrão 20"),
    },
    annotations: readOnly,
  },
  (args) => forwardNei("search_items", args),
);

server.registerTool(
  "get_recipes",
  {
    title: "Como fazer um item (NEI)",
    description:
      "Receitas que PRODUZEM o item — o mesmo que apertar R no NEI: bancada, fornalha, máquinas do GregTech (com " +
      "euPerTick e durationTicks) e de outros mods. No GTNH as receitas são muito diferentes do vanilla: consulte " +
      "antes de planejar. item = nome de exibição ('Iron Pickaxe') ou id ('minecraft:iron_pickaxe[:meta]'). " +
      "byHandler lista todas as formas de fazer; handler filtra por uma (ex.: 'crafting', 'furnace', 'macerator').",
    inputSchema: {
      item: z.string().min(1),
      limit: z.number().int().min(1).max(20).optional().describe("padrão 5"),
      handler: z.string().optional(),
    },
    annotations: readOnly,
  },
  (args) => forwardNei("get_recipes", args),
);

server.registerTool(
  "get_usages",
  {
    title: "Para que serve um item (NEI)",
    description: "Receitas que USAM o item como ingrediente — o mesmo que apertar U no NEI.",
    inputSchema: {
      item: z.string().min(1),
      limit: z.number().int().min(1).max(20).optional().describe("padrão 5"),
      handler: z.string().optional(),
    },
    annotations: readOnly,
  },
  (args) => forwardNei("get_usages", args),
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
      "para chegar perto de um bloco sólido que quer minerar). Responde quando chega (status done, com distance = " +
      "distância final até o alvo) ou falha (failed com reason unreachable / timeout). Com o jogo pausado (ESC) a " +
      "caminhada congela e o tempo não conta.",
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
      "Quebra o bloco em (x, y, z). Precisa estar ao alcance (~4,5 blocos do olho); senão responde too_far. Antes " +
      "de quebrar troca sozinho para o item da hotbar que faz o bloco dropar e quebra mais rápido (no GTNH, pedra e " +
      "minério com a ferramenta errada não dropam nada). A resposta traz tool (slot/item usado) e canHarvest; " +
      "canHarvest=false quer dizer que nada da hotbar serve e o bloco provavelmente não dropou.",
    inputSchema: {
      x: coord,
      y: coord,
      z: coord,
      autoTool: z.boolean().optional().describe("trocar sozinho para a melhor ferramenta da hotbar; padrão true"),
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
      "Usa o item da mão no ar por até N ticks (20 ticks = 1 s): comer/beber (~32 ticks), puxar arco, jogar pérola. " +
      "Nunca ativa o bloco ou a entidade na mira (não abre bancada/baú por engano). Termina sozinho quando o uso " +
      "acaba (finished=true, ex.: comeu tudo); falha com empty_hand, not_usable ou gui_open.",
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
