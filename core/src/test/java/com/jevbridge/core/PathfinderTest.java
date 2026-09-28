package com.jevbridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class PathfinderTest {
    private static final int Y = FakeGame.GROUND + 1;

    private static int[] last(List<int[]> path) {
        return path.get(path.size() - 1);
    }

    @Test
    public void flatGroundReachesTarget() {
        FakeGame g = new FakeGame();
        Pathfinder.Result r = new Pathfinder(g, 5000).find(0, Y, 0, 10, Y, 5, 0);
        assertTrue(r.complete);
        assertEquals(10, last(r.path)[0]);
        assertEquals(5, last(r.path)[2]);
    }

    @Test
    public void climbsOneBlockStep() {
        FakeGame g = new FakeGame();
        for (int x = 5; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                g.set(x, Y, z, "minecraft:dirt");
            }
        }
        Pathfinder.Result r = new Pathfinder(g, 5000).find(0, Y, 0, 10, Y + 1, 0, 0);
        assertTrue(r.complete);
        assertEquals(Y + 1, last(r.path)[1]);
    }

    @Test
    public void goesAroundTwoHighWall() {
        FakeGame g = new FakeGame();
        for (int z = -5; z <= 5; z++) {
            g.set(3, Y, z, "minecraft:stone");
            g.set(3, Y + 1, z, "minecraft:stone");
        }
        Pathfinder.Result r = new Pathfinder(g, 5000).find(0, Y, 0, 6, Y, 0, 0);
        assertTrue(r.complete);
        for (int[] n : r.path) {
            assertFalse("atravessou a parede", n[0] == 3 && Math.abs(n[2]) <= 5);
        }
    }

    @Test
    public void avoidsLava() {
        FakeGame g = new FakeGame();
        for (int z = -3; z <= 3; z++) {
            g.set(2, Y - 1, z, "minecraft:lava");
        }
        Pathfinder.Result r = new Pathfinder(g, 5000).find(0, Y, 0, 4, Y, 0, 0);
        assertTrue(r.complete);
        for (int[] n : r.path) {
            assertFalse("pisou na lava", n[0] == 2 && Math.abs(n[2]) <= 3);
        }
    }

    @Test
    public void enclosedTargetGivesPartialPath() {
        FakeGame g = new FakeGame();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    for (int dy = 0; dy < 3; dy++) {
                        g.set(10 + dx, Y + dy, dz, "minecraft:stone");
                    }
                }
            }
        }
        Pathfinder.Result r = new Pathfinder(g, 3000).find(0, Y, 0, 10, Y, 0, 0);
        assertFalse(r.complete);
        assertTrue(r.path.size() > 1);
    }

    @Test
    public void rangeStopsNearTarget() {
        FakeGame g = new FakeGame();
        Pathfinder.Result r = new Pathfinder(g, 5000).find(0, Y, 0, 10, Y, 0, 3);
        assertTrue(r.complete);
        int[] end = last(r.path);
        assertTrue(Geometry.dist(end[0], end[1], end[2], 10, Y, 0) <= 3);
    }
}
