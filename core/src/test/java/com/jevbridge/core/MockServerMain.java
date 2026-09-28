package com.jevbridge.core;

/**
 * Sobe o núcleo com o mundo falso, a 20 ticks/s, para testar o servidor MCP
 * sem abrir o Minecraft: {@code gradle runMock --args="25599 meu-token"}.
 */
public final class MockServerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : BridgeConfig.DEFAULT_PORT;
        String token = args.length > 1 ? args[1] : "dev-token-please-change";
        final FakeGame game = new FakeGame();
        game.set(3, FakeGame.GROUND + 1, 2, "minecraft:log");
        game.set(3, FakeGame.GROUND + 2, 2, "minecraft:log");
        game.entities.add(new EntityInfo(42, "Zombie", "Zombie", 6.5, FakeGame.GROUND + 1, -3.5, 20, true, false, 1.8f));
        final BridgeCore core = new BridgeCore(game, new BridgeLog() {
            @Override
            public void info(String message) {
                System.out.println(message);
            }

            @Override
            public void warn(String message, Throwable error) {
                System.err.println(message);
                if (error != null) {
                    error.printStackTrace();
                }
            }
        }, "mock");
        core.register(new FakeRecipes("search_items"));
        core.register(new FakeRecipes("get_recipes"));
        core.register(new FakeRecipes("get_usages"));
        core.start(BridgeConfig.forTest(port, token, false));
        System.out.println("mock pronto na porta " + core.port());
        while (true) {
            game.physicsTick();
            core.tick();
            Thread.sleep(50);
        }
    }
}
