package com.jevbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* em grade de blocos, independente de versão: só consulta o mundo pelo
 * {@link GameAdapter}. Movimentos: andar (4 direções + diagonais), subir 1
 * bloco pulando e descer até 3 blocos. Não quebra nem coloca blocos, não nada
 * em rotas longas e não usa escadas de mão — é o suficiente para andar por
 * terreno natural, não para parkour.
 *
 * <p>Se o alvo não é alcançável dentro do limite de nós (ou está fora dos
 * chunks carregados), devolve um caminho parcial até o nó explorado mais
 * próximo do alvo. Quem segue o caminho replaneja ao chegar no fim.
 */
public final class Pathfinder {

    public static final class Result {
        public final List<int[]> path;
        public final boolean complete;

        Result(List<int[]> path, boolean complete) {
            this.path = path;
            this.complete = complete;
        }
    }

    private static final int MAX_FALL = 3;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final GameAdapter world;
    private final int maxNodes;

    public Pathfinder(GameAdapter world, int maxNodes) {
        this.world = world;
        this.maxNodes = maxNodes;
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        final double g, f;
        final Node parent;

        Node(int x, int y, int z, double g, double h, Node parent) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.f = g + h;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** O jogador cabe em pé aqui (2 blocos livres, chão firme ou água) sem se machucar? */
    public boolean standable(int x, int y, int z) {
        if (!world.isLoaded(x, z)) {
            return false;
        }
        if (!world.isPassable(x, y, z) || !world.isPassable(x, y + 1, z)) {
            return false;
        }
        if (world.isDangerous(x, y, z) || world.isDangerous(x, y + 1, z)) {
            return false;
        }
        if (world.isWater(x, y, z)) {
            return true;
        }
        return world.isSolid(x, y - 1, z) && !world.isDangerous(x, y - 1, z);
    }

    /**
     * @param range distância (em blocos, a partir dos pés) que conta como chegada;
     *              0 exige chegar no bloco exato.
     */
    public Result find(int sx, int sy, int sz, int tx, int ty, int tz, double range) {
        PriorityQueue<Node> open = new PriorityQueue<Node>();
        Map<Long, Double> bestG = new HashMap<Long, Double>();
        Node start = new Node(sx, sy, sz, 0, h(sx, sy, sz, tx, ty, tz), null);
        open.add(start);
        bestG.put(key(sx, sy, sz), 0.0);
        Node closest = start;
        double closestH = start.f;
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            Double known = bestG.get(key(cur.x, cur.y, cur.z));
            if (known != null && known < cur.g) {
                continue;
            }
            expanded++;
            double hc = h(cur.x, cur.y, cur.z, tx, ty, tz);
            if (hc <= range + 1e-6) {
                return new Result(unwind(cur), true);
            }
            if (hc < closestH) {
                closestH = hc;
                closest = cur;
            }
            for (int[] d : CARDINAL) {
                expandCardinal(cur, d[0], d[1], tx, ty, tz, open, bestG);
            }
            for (int[] d : DIAGONAL) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                // Só corta a quina se os dois lados estão livres, senão o jogador enrosca.
                if (standable(nx, cur.y, nz)
                        && world.isPassable(cur.x + d[0], cur.y, cur.z) && world.isPassable(cur.x + d[0], cur.y + 1, cur.z)
                        && world.isPassable(cur.x, cur.y, cur.z + d[1]) && world.isPassable(cur.x, cur.y + 1, cur.z + d[1])) {
                    push(cur, nx, cur.y, nz, 1.414, tx, ty, tz, open, bestG);
                }
            }
        }
        if (closest == start) {
            return new Result(Collections.<int[]>emptyList(), false);
        }
        return new Result(unwind(closest), false);
    }

    private void expandCardinal(Node cur, int dx, int dz, int tx, int ty, int tz,
                                PriorityQueue<Node> open, Map<Long, Double> bestG) {
        int nx = cur.x + dx;
        int nz = cur.z + dz;
        if (standable(nx, cur.y, nz)) {
            push(cur, nx, cur.y, nz, world.isWater(nx, cur.y, nz) ? 2.0 : 1.0, tx, ty, tz, open, bestG);
            return;
        }
        // Subir 1 bloco: precisa de espaço para a cabeça no pulo.
        if (standable(nx, cur.y + 1, nz) && world.isPassable(cur.x, cur.y + 2, cur.z)) {
            push(cur, nx, cur.y + 1, nz, 2.0, tx, ty, tz, open, bestG);
            return;
        }
        // Descer: a coluna à frente precisa estar livre até o chão.
        if (!world.isPassable(nx, cur.y, nz) || !world.isPassable(nx, cur.y + 1, nz)) {
            return;
        }
        for (int drop = 1; drop <= MAX_FALL; drop++) {
            int ny = cur.y - drop;
            if (standable(nx, ny, nz)) {
                push(cur, nx, ny, nz, 1.0 + drop * 0.5, tx, ty, tz, open, bestG);
                return;
            }
            if (!world.isPassable(nx, ny, nz) || world.isDangerous(nx, ny, nz)) {
                return;
            }
        }
    }

    private void push(Node cur, int x, int y, int z, double cost, int tx, int ty, int tz,
                      PriorityQueue<Node> open, Map<Long, Double> bestG) {
        double g = cur.g + cost;
        long k = key(x, y, z);
        Double known = bestG.get(k);
        if (known != null && known <= g) {
            return;
        }
        bestG.put(k, g);
        open.add(new Node(x, y, z, g, h(x, y, z, tx, ty, tz), cur));
    }

    private static double h(int x, int y, int z, int tx, int ty, int tz) {
        return Geometry.dist(x, y, z, tx, ty, tz);
    }

    private static List<int[]> unwind(Node n) {
        List<int[]> out = new ArrayList<int[]>();
        for (Node c = n; c != null; c = c.parent) {
            out.add(new int[]{c.x, c.y, c.z});
        }
        Collections.reverse(out);
        return out;
    }
}
