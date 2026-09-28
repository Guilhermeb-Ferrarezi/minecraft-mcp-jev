# Protocolo JevBridge (v1)

Conexão TCP em `127.0.0.1:25599` (porta e bind em `config/jevbridge.properties`).
Cada mensagem é **um objeto JSON por linha** (UTF-8, terminado em `\n`, até 1 MiB).

## Autenticação

A primeira mensagem precisa ser `hello` com o token do arquivo de config; qualquer
outra coisa (ou token errado) recebe `unauthorized` e a conexão é fechada.
Um novo `hello` válido derruba o cliente anterior — só um controla o jogador.

```json
→ {"id":1,"method":"hello","params":{"token":"…","client":"meu-agente","protocol":1}}
← {"id":1,"ok":true,"result":{"protocol":1,"minecraftVersion":"1.7.10","loader":"forge","modVersion":"…","methods":["hello","status",…]}}
```

## Pedido / resposta

```json
→ {"id":7,"method":"walk_to","params":{"x":120,"y":64,"z":-30,"range":2}}
← {"id":7,"ok":true,"result":{"status":"done","position":{"x":119.4,"y":64,"z":-28.6}}}
← {"id":8,"ok":false,"error":{"code":"bad_params","message":"parâmetro obrigatório: z"}}
```

- `ok:false` = erro de protocolo: `unauthorized`, `bad_json`, `unknown_method`,
  `bad_params`, `not_in_world`, `not_loaded`, `forbidden`, `internal`.
- Ações (`walk_to`, `mine_block`, `place_block`, `use_item`, `move`) duram vários
  ticks e **só respondem ao terminar**, sempre com `ok:true` e um `status`:
  `done`, `failed` (com `reason`), `timeout` ou `cancelled`. Falhar faz parte do
  jogo — quem chama lê o `reason` e decide.
- Só uma ação por vez: uma nova cancela a anterior (que responde `cancelled`).
  `stop` cancela sem começar outra.
- Tudo roda na thread do jogo, no fim de cada tick (20/s).

## Métodos

| método | params | resultado |
|---|---|---|
| `status` | — | `inWorld`, `busyWith` |
| `get_state` | — | posição (pés), `blockPosition`, yaw/pitch, vida, fome, item na mão, dimensão, bioma, hora, mira |
| `get_inventory` | — | `selectedSlot`, `items[]` (slot 0-8 hotbar, 9-35, 36-39 armadura) |
| `get_block` | `x,y,z` | bloco |
| `get_blocks` | `radius` ≤ 6 | blocos não-ar num cubo |
| `find_blocks` | `query`, `radius` ≤ 32, `verticalRadius` ≤ 32, `limit` ≤ 50 | blocos cujo id ou nome contém `query`, por distância |
| `get_entities` | `radius` ≤ 64, `limit` | entidades por distância (`id`, `type`, `health`, `hostile`) |
| `look` | `yaw`, `pitch` | — |
| `look_at` | `x,y,z` | — |
| `select_slot` | `slot` 0-8 | `heldItem` |
| `chat` | `message` ≤ 100 | — (`/comandos` só com `allowCommands=true`) |
| `attack` | `entityId` | `done` ou `failed` (`too_far`, `not_found`) |
| `stop` | — | `stopped` |
| `walk_to` | `x,y,z`, `range`=1, `sprint`, `timeoutSeconds` ≤ 300 | ação; `unreachable` quando não há caminho |
| `mine_block` | `x,y,z`, `timeoutSeconds` ≤ 120 | ação: mira e segura M1 até o bloco sumir (o jogo decide velocidade e drop); `too_far`, `unbreakable`, `liquid`, `obstructed` (bloco sólido na frente, vem em `blockInTheWay`), `gui_open` |
| `place_block` | `x,y,z` | ação; `empty_hand`, `occupied`, `inside_player`, `no_support`, `too_far` |
| `use_item` | `ticks` ≤ 200 | ação (segura botão direito) |
| `move` | `forward`, `strafe`, `jump`, `sneak`, `sprint`, `ticks` ≤ 200 | ação |

### Extensões (só se o mod opcional estiver instalado; aparecem em `methods` do hello)

Rodam fora da thread do jogo (consultas pesadas, não travam o jogo) e funcionam
mesmo fora de um mundo. Erro `not_ready` enquanto o NEI carrega a lista de itens.

| método | params | resultado |
|---|---|---|
| `search_items` | `query`, `limit` ≤ 50 | itens do modpack cujo nome/id contém `query` |
| `get_recipes` | `item`, `limit` ≤ 20, `handler` | receitas que produzem o item (tecla R do NEI) |
| `get_usages` | `item`, `limit` ≤ 20, `handler` | receitas que usam o item (tecla U do NEI) |

`item` é o nome de exibição ("Iron Pickaxe"; sem nome exato, o mais curto que
contém o texto) ou o id (`minecraft:iron_pickaxe[:meta]`). Cada receita:
`handler`, `ingredients[]` (somados, com `alternatives` do OreDictionary),
`outputs[]`, `otherStacks[]` (ex.: combustível) e, em máquinas do GregTech,
`euPerTick` e `durationTicks`. `byHandler` lista todas as formas de fazer o item.

Blocos: `{"x","y","z","name","meta","displayName","solid","liquid","unbreakable"}`.
Em 1.7.10 o tipo depende de `name` + `meta`; `displayName` vem do `getPickBlock`
(lê TileEntity), então minérios do GregTech aparecem como "Magnetite Ore" etc.

## Eventos

Sem `id`, a qualquer momento: `{"event":"chat","data":{"text":"…"}}`,
`death`, `joined_world`, `left_world`.

## Convenções

- Coordenadas de bloco inteiras; `y` de destino é a altura dos pés.
- yaw 0 = sul (+Z), 90 = oeste (−X), 180 = norte, 270 = leste; pitch −90 cima, 90 baixo.
- Faces: 0 baixo, 1 cima, 2 norte, 3 sul, 4 oeste, 5 leste.
