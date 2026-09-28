package com.jevbridge.core;

import com.google.gson.JsonObject;

import java.util.List;

/** As ações de vários ticks. Toda a lógica aqui é independente de versão. */
final class Actions {
    private Actions() {
    }

    static JsonObject position(PlayerSnapshot p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", Math.round(p.x * 100) / 100.0);
        o.addProperty("y", Math.round(p.y * 100) / 100.0);
        o.addProperty("z", Math.round(p.z * 100) / 100.0);
        return o;
    }

    static double eyeDistance(PlayerSnapshot p, double x, double y, double z) {
        return Geometry.dist(p.x, p.y + p.eyeHeight, p.z, x, y, z);
    }

    static void lookAt(GameAdapter game, PlayerSnapshot p, double x, double y, double z) {
        double ey = p.y + p.eyeHeight;
        game.setLook(Geometry.yawTo(p.x, p.z, x, z), Geometry.pitchTo(p.x, ey, p.z, x, y, z));
    }

    /** Segura uma combinação de movimento por N ticks. */
    static final class Move extends Action {
        private final InputState input;
        private final int duration;

        Move(Request r, InputState input, int duration) {
            super(r, duration + 20);
            this.input = input;
            this.duration = duration;
        }

        @Override
        protected JsonObject tick(GameAdapter game) {
            if (ticks >= duration) {
                JsonObject o = done();
                o.add("position", position(game.player()));
                return o;
            }
            game.setInput(input);
            return null;
        }
    }

    /** Segura o botão direito por N ticks (comer, beber, arco). */
    static final class UseItem extends Action {
        private final int duration;

        UseItem(Request r, int duration) {
            super(r, duration + 20);
            this.duration = duration;
        }

        @Override
        protected JsonObject tick(GameAdapter game) {
            if (ticks >= duration) {
                game.setUseHeld(false);
                JsonObject o = done();
                o.add("heldItem", Json.item(game.player().heldItem));
                return o;
            }
            game.setUseHeld(true);
            return null;
        }
    }

    /** Anda até (x,y,z) com A* e replanejamento. */
    static final class WalkTo extends Action {
        private static final int MAX_REPLANS = 25;
        private static final int STUCK_TICKS = 50;

        private final int tx, ty, tz;
        private final double range;
        private final boolean sprint;
        private final Pathfinder pathfinder;
        private List<int[]> path;
        private boolean pathComplete;
        private int index;
        private int replans;
        private double checkX, checkY, checkZ;
        private int checkTick;
        private double closest = Double.MAX_VALUE;

        WalkTo(Request r, GameAdapter game, int tx, int ty, int tz, double range, boolean sprint, int timeoutTicks) {
            super(r, timeoutTicks);
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            this.range = range;
            this.sprint = sprint;
            this.pathfinder = new Pathfinder(game, 8000);
        }

        private double distance(PlayerSnapshot p) {
            return Geometry.dist(p.x, p.y, p.z, tx + 0.5, ty, tz + 0.5);
        }

        @Override
        protected JsonObject tick(GameAdapter game) {
            PlayerSnapshot p = game.player();
            if (p.dead) {
                return failed("dead", "o jogador morreu no caminho");
            }
            double dist = distance(p);
            closest = Math.min(closest, dist);
            if (dist <= range + 0.5) {
                JsonObject o = done();
                o.add("position", position(p));
                return o;
            }

            if (ticks == 0 || ticks - checkTick >= STUCK_TICKS) {
                boolean moved = Geometry.dist(p.x, p.y, p.z, checkX, checkY, checkZ) > 0.5;
                if (ticks > 0 && !moved) {
                    path = null; // sem progresso: replaneja a partir de onde está
                }
                checkX = p.x;
                checkY = p.y;
                checkZ = p.z;
                checkTick = ticks;
            }

            if (path == null || index >= path.size()) {
                if (!p.onGround && !p.inWater) {
                    game.setInput(InputState.NONE); // espera cair antes de replanejar
                    return null;
                }
                if (replans++ >= MAX_REPLANS) {
                    return unreachable(p, "desisti depois de " + MAX_REPLANS + " replanejamentos");
                }
                int sx = Geometry.floor(p.x);
                int sy = Geometry.floor(p.y + 0.01);
                int sz = Geometry.floor(p.z);
                Pathfinder.Result res = pathfinder.find(sx, sy, sz, tx, ty, tz, range);
                if (res.path.size() <= 1) {
                    return unreachable(p, res.complete ? null : "nenhum caminho a partir daqui");
                }
                path = res.path;
                pathComplete = res.complete;
                index = 1; // o nó 0 é onde o jogador já está
            }

            int[] wp = path.get(index);
            double cx = wp[0] + 0.5;
            double cz = wp[2] + 0.5;
            double hd = Math.sqrt((cx - p.x) * (cx - p.x) + (cz - p.z) * (cz - p.z));
            if (hd < 0.35 && Math.abs(p.y - wp[1]) < 1.1) {
                index++;
                if (index >= path.size()) {
                    if (pathComplete) {
                        // Chegou ao fim de um caminho completo mas fora do raio: a
                        // checagem de chegada no próximo tick decide, ou replaneja.
                        path = null;
                    }
                    game.setInput(InputState.NONE);
                    return null;
                }
                wp = path.get(index);
                cx = wp[0] + 0.5;
                cz = wp[2] + 0.5;
                hd = Math.sqrt((cx - p.x) * (cx - p.x) + (cz - p.z) * (cz - p.z));
            }

            int feetY = Geometry.floor(p.y + 0.01);
            boolean goingUp = wp[1] > feetY;
            boolean jump = (goingUp && hd < 1.4 && p.onGround)
                    || (p.collidedHorizontally && p.onGround)
                    || (p.inWater && wp[1] >= feetY);
            game.setLook(Geometry.yawTo(p.x, p.z, cx, cz), p.pitch);
            game.setInput(new InputState(1f, 0f, jump, false, sprint && !p.inWater && hd > 1.5));
            return null;
        }

        private JsonObject unreachable(PlayerSnapshot p, String message) {
            JsonObject o = failed("unreachable", message);
            o.add("position", position(p));
            o.addProperty("remainingDistance", Math.round(distance(p) * 10) / 10.0);
            return o;
        }

        @Override
        protected void describeProgress(JsonObject out) {
            out.addProperty("closestDistance", Math.round(closest * 10) / 10.0);
        }
    }

    /** Quebra o bloco em (x,y,z) segurando o "botão esquerdo" até ele sumir. */
    static final class MineBlock extends Action {
        private final int x, y, z;
        private int face = -1;
        private String original;

        MineBlock(Request r, int x, int y, int z, int timeoutTicks) {
            super(r, timeoutTicks);
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        protected JsonObject tick(GameAdapter game) {
            PlayerSnapshot p = game.player();
            BlockInfo b = game.blockAt(x, y, z);
            if (b == null) {
                return failed("not_loaded", "chunk não carregado");
            }
            if (face < 0) {
                if (b.isAir()) {
                    JsonObject o = done();
                    o.addProperty("alreadyEmpty", true);
                    return o;
                }
                if (b.liquid) {
                    return failed("liquid", "não dá pra minerar líquido; coloque um bloco em cima");
                }
                if (b.hardness < 0) {
                    return failed("unbreakable", b.displayName + " é inquebrável");
                }
                double d = eyeDistance(p, x + 0.5, y + 0.5, z + 0.5);
                if (d > game.reach() + 0.5) {
                    JsonObject o = failed("too_far", "chegue mais perto antes (walk_to com range 2)");
                    o.addProperty("distance", Math.round(d * 10) / 10.0);
                    return o;
                }
                face = Geometry.bestFace(game, x, y, z, p.x, p.y + p.eyeHeight, p.z);
                original = b.displayName;
            } else if (b.isAir() || !b.displayName.equals(original)) {
                JsonObject o = done();
                o.addProperty("broke", original);
                o.addProperty("ticks", ticks);
                return o;
            }
            double[] c = Geometry.faceCenter(x, y, z, face);
            lookAt(game, p, c[0], c[1], c[2]);
            game.mineTick(x, y, z, face);
            return null;
        }
    }

    /** Coloca o item da mão em (x,y,z), clicando na face de um vizinho sólido. */
    static final class PlaceBlock extends Action {
        private static final int[] NEIGHBOR_ORDER = {0, 2, 3, 4, 5, 1}; // prefere apoiar no chão

        private final int x, y, z;
        private boolean clicked;
        private int clickedAt;

        PlaceBlock(Request r, int x, int y, int z) {
            super(r, 40);
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        protected JsonObject tick(GameAdapter game) {
            PlayerSnapshot p = game.player();
            if (clicked) {
                if (!game.isPassable(x, y, z)) {
                    JsonObject o = done();
                    o.add("block", Json.block(game.blockAt(x, y, z)));
                    return o;
                }
                if (ticks - clickedAt > 6) {
                    return failed("not_placed", "o clique não colocou bloco (item na mão não é bloco? servidor recusou?)");
                }
                return null;
            }
            if (p.heldItem == null) {
                return failed("empty_hand", "selecione um bloco na hotbar antes (select_slot)");
            }
            if (!game.isLoaded(x, z)) {
                return failed("not_loaded", "chunk não carregado");
            }
            if (!game.isPassable(x, y, z)) {
                JsonObject o = failed("occupied", "já tem bloco nessa posição");
                o.add("block", Json.block(game.blockAt(x, y, z)));
                return o;
            }
            if (intersectsPlayer(p)) {
                return failed("inside_player", "o bloco ficaria dentro do jogador; afaste-se ou pule antes");
            }
            for (int f : NEIGHBOR_ORDER) {
                int[] d = Geometry.FACE_DIR[f];
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (!game.isSolid(nx, ny, nz)) {
                    continue;
                }
                int clickFace = Geometry.opposite(f);
                double[] hit = Geometry.faceCenter(nx, ny, nz, clickFace);
                if (eyeDistance(p, hit[0], hit[1], hit[2]) > game.reach()) {
                    continue;
                }
                lookAt(game, p, hit[0], hit[1], hit[2]);
                game.useOnBlock(nx, ny, nz, clickFace, hit[0], hit[1], hit[2]);
                clicked = true;
                clickedAt = ticks;
                return null;
            }
            double d = eyeDistance(p, x + 0.5, y + 0.5, z + 0.5);
            if (d > game.reach()) {
                JsonObject o = failed("too_far", "chegue mais perto antes (walk_to com range 2)");
                o.addProperty("distance", Math.round(d * 10) / 10.0);
                return o;
            }
            return failed("no_support", "nenhum bloco sólido vizinho para apoiar");
        }

        private boolean intersectsPlayer(PlayerSnapshot p) {
            return p.x + 0.3 > x && p.x - 0.3 < x + 1
                    && p.z + 0.3 > z && p.z - 0.3 < z + 1
                    && p.y + 1.8 > y && p.y < y + 1;
        }
    }
}
