package com.jevbridge.core;

/** Contas de ângulo e faces na convenção do Minecraft (yaw 0 = +Z, yaw 90 = -X). */
public final class Geometry {
    private Geometry() {
    }

    /** Deslocamento de cada face: 0 baixo, 1 cima, 2 norte, 3 sul, 4 oeste, 5 leste. */
    public static final int[][] FACE_DIR = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    public static int opposite(int face) {
        return face ^ 1;
    }

    public static float yawTo(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    public static float pitchTo(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    }

    public static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /**
     * Escolhe a face do bloco que está virada para o olho do jogador e não está
     * encostada num bloco sólido (clicar numa face coberta não funciona bem).
     */
    public static int bestFace(GameAdapter game, int x, int y, int z, double eyeX, double eyeY, double eyeZ) {
        double vx = eyeX - (x + 0.5);
        double vy = eyeY - (y + 0.5);
        double vz = eyeZ - (z + 0.5);
        int best = -1;
        double bestDot = -Double.MAX_VALUE;
        int fallback = 1;
        double fallbackDot = -Double.MAX_VALUE;
        for (int f = 0; f < 6; f++) {
            int[] d = FACE_DIR[f];
            double dot = d[0] * vx + d[1] * vy + d[2] * vz;
            if (dot > fallbackDot) {
                fallbackDot = dot;
                fallback = f;
            }
            if (game.isSolid(x + d[0], y + d[1], z + d[2])) {
                continue;
            }
            if (dot > bestDot) {
                bestDot = dot;
                best = f;
            }
        }
        return best >= 0 ? best : fallback;
    }

    /** Ponto no centro da face, usado como alvo do olhar e do clique. */
    public static double[] faceCenter(int x, int y, int z, int face) {
        int[] d = FACE_DIR[face];
        return new double[]{x + 0.5 + d[0] * 0.5, y + 0.5 + d[1] * 0.5, z + 0.5 + d[2] * 0.5};
    }
}
