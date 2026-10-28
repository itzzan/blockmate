package dev.blockmate.server.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 23：两次投眼算要塞——方向向量、交点、太近 / 平行 / 身后都算不出。 */
class EyeLocatorTest {

    @Test
    void 方向向量按MC的yaw() {
        double[] south = EyeLocator.direction(0);
        assertEquals(0, south[0], 1e-9);
        assertEquals(1, south[1], 1e-9);
        double[] west = EyeLocator.direction(90);
        assertEquals(-1, west[0], 1e-9);
        assertEquals(0, west[1], 1e-6);
        double[] east = EyeLocator.direction(-90);
        assertEquals(1, east[0], 1e-9);
    }

    @Test
    void 两次相隔够远就有交点() {
        // 要塞在 (0, 500)。在 (-200, 0) 投，眼飞向东南偏东；在 (200, 0) 投，眼飞向西南偏西
        double yawA = DragonYaw(0 - (-200), 500 - 0);
        double yawB = DragonYaw(0 - 200, 500 - 0);
        EyeLocator.Throw a = new EyeLocator.Throw(-200, 0, yawA, false);
        EyeLocator.Throw b = new EyeLocator.Throw(200, 0, yawB, false);
        double[] p = EyeLocator.intersect(a, b).orElseThrow();
        assertEquals(0, p[0], 1.0);
        assertEquals(500, p[1], 1.0);
        EyeLocator.Estimate est = EyeLocator.describeFrom(p, 200, 0);
        assertEquals(539, Math.round(est.distance()), 2);
        assertEquals("南", est.direction(), "从 (200, 0) 看 (0, 500) 偏南");
        assertEquals(0, EyeLocator.estimate(List.of(a, b)).orElseThrow()[0], 1.0);
    }

    private static double DragonYaw(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    @Test
    void 太近_平行_身后都算不出() {
        EyeLocator.Throw a = new EyeLocator.Throw(0, 0, 0, false);
        assertTrue(EyeLocator.intersect(a, new EyeLocator.Throw(50, 0, -10, false)).isEmpty(), "不到 100 格");
        assertTrue(EyeLocator.intersect(a, new EyeLocator.Throw(150, 0, 0.5, false)).isEmpty(), "几乎平行");
        assertTrue(EyeLocator.intersect(a, new EyeLocator.Throw(150, 0, 180, false)).isEmpty(), "交点在身后");
        Optional<double[]> ok = EyeLocator.intersect(a, new EyeLocator.Throw(150, 0, 45, false));
        assertTrue(ok.isPresent());
        assertTrue(EyeLocator.lastTwoFarEnough(List.of(a, new EyeLocator.Throw(150, 0, 45, false))));
        assertTrue(!EyeLocator.lastTwoFarEnough(List.of(a, new EyeLocator.Throw(10, 0, 45, false))));
        assertTrue(!EyeLocator.lastTwoFarEnough(List.of(a)));
    }
}
