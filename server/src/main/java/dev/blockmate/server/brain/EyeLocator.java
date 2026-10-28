package dev.blockmate.server.brain;

import java.util.List;
import java.util.Optional;

/**
 * 两次投末影之眼算要塞在哪（任务单 23，guide-3 §3.3 第 1 条）。纯算术，不碰游戏。
 * MC 的水平角 yaw：0 = 朝南（+z），90 = 朝西（-x），180 = 朝北（-z），270 = 朝东（+x）；方向向量 (dx, dz) = (-sin yaw, cos yaw)。
 */
public final class EyeLocator {

    /** 一次投掷：在哪投的、眼飞向哪（yaw 度）、是不是基本没动（near = 要塞就在脚下附近）。 */
    public record Throw(double x, double z, double yawDeg, boolean near) {
    }

    public record Estimate(double x, double z, double distance, String direction) {
    }

    /** 两次投掷至少隔这么远，交点才靠谱（手册：< 100 格误差大）。 */
    public static final double MIN_SEPARATION = 100.0;
    /** 两条线几乎平行（sin 夹角 < 0.05，约 3 度）就算不出来。 */
    public static final double MIN_SIN = 0.05;

    private EyeLocator() {
    }

    public static double[] direction(double yawDeg) {
        double r = Math.toRadians(yawDeg);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    /** 两条射线的交点；两点太近、方向太平行、交点在身后都返回空。 */
    public static Optional<double[]> intersect(Throw a, Throw b) {
        double sep = Math.hypot(a.x() - b.x(), a.z() - b.z());
        if (sep < MIN_SEPARATION) {
            return Optional.empty();
        }
        double[] da = direction(a.yawDeg());
        double[] db = direction(b.yawDeg());
        double cross = da[0] * db[1] - da[1] * db[0];
        if (Math.abs(cross) < MIN_SIN) {
            return Optional.empty();
        }
        double ex = b.x() - a.x();
        double ez = b.z() - a.z();
        double t = (ex * db[1] - ez * db[0]) / cross;
        double u = (ex * da[1] - ez * da[0]) / cross;
        if (t < 0 || u < 0) {
            return Optional.empty();
        }
        return Optional.of(new double[]{a.x() + da[0] * t, a.z() + da[1] * t});
    }

    /** 从最近的几次投掷里挑一对算得出来的（后投的优先）。 */
    public static Optional<double[]> estimate(List<Throw> throwsList) {
        for (int i = throwsList.size() - 1; i >= 0; i--) {
            for (int j = i - 1; j >= 0; j--) {
                Optional<double[]> p = intersect(throwsList.get(j), throwsList.get(i));
                if (p.isPresent()) {
                    return p;
                }
            }
        }
        return Optional.empty();
    }

    public static Estimate describeFrom(double[] p, double fromX, double fromZ) {
        double dx = p[0] - fromX;
        double dz = p[1] - fromZ;
        return new Estimate(p[0], p[1], Math.hypot(dx, dz), Landmarks.direction(dx, dz));
    }

    /** 最后两次离得够不够远（给"再走远点投一次"用）。 */
    public static boolean lastTwoFarEnough(List<Throw> throwsList) {
        if (throwsList.size() < 2) {
            return false;
        }
        Throw a = throwsList.get(throwsList.size() - 2);
        Throw b = throwsList.get(throwsList.size() - 1);
        return Math.hypot(a.x() - b.x(), a.z() - b.z()) >= MIN_SEPARATION;
    }
}
