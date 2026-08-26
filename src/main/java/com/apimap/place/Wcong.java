package com.apimap.place;

/**
 * WGS84 → WCONGNAMUL(카카오맵 내부 좌표계) 변환.
 * WCONGNAMUL = 2.5 × WTM(EPSG:5181 계열: GRS80, 원점 38°N/127°E, k0=1, FE=200000, FN=500000)
 */
final class Wcong {

    private static final double A = 6378137.0;
    private static final double F = 1 / 298.257222101;
    private static final double E2 = F * (2 - F);
    private static final double EP2 = E2 / (1 - E2);
    private static final double LON0 = Math.toRadians(127.0);
    private static final double FE = 200000.0;
    private static final double FN = 500000.0;
    private static final double M0 = meridianArc(Math.toRadians(38.0));

    private Wcong() {}

    /** @return {x, y} WCONGNAMUL 좌표 */
    static double[] fromWgs84(double lat, double lng) {
        double phi = Math.toRadians(lat);
        double lam = Math.toRadians(lng);
        double sin = Math.sin(phi), cos = Math.cos(phi), tan = Math.tan(phi);

        double n = A / Math.sqrt(1 - E2 * sin * sin);
        double t = tan * tan;
        double c = EP2 * cos * cos;
        double a = (lam - LON0) * cos;

        double x = FE + n * (a
                + (1 - t + c) * Math.pow(a, 3) / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * Math.pow(a, 5) / 120);
        double y = FN + (meridianArc(phi) - M0 + n * tan * (
                a * a / 2
                + (5 - t + 9 * c + 4 * c * c) * Math.pow(a, 4) / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * Math.pow(a, 6) / 720));
        return new double[] { x * 2.5, y * 2.5 };
    }

    private static double meridianArc(double phi) {
        return A * ((1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * Math.pow(E2, 3) / 256) * phi
                - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * Math.pow(E2, 3) / 1024) * Math.sin(2 * phi)
                + (15 * E2 * E2 / 256 + 45 * Math.pow(E2, 3) / 1024) * Math.sin(4 * phi)
                - (35 * Math.pow(E2, 3) / 3072) * Math.sin(6 * phi));
    }
}
