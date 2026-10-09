package com.david.gps3dar;

/** Lux hysteresis avoids flashing when driving through shadows or past headlights. */
public final class AutoDarkMode {
    private boolean ambientDark;
    private Boolean candidate;
    private long candidateSince;
    public boolean update(long now, boolean night, Double lux) {
        if (lux != null && Double.isFinite(lux) && lux >= 0) {
            boolean next = ambientDark ? lux < 65 : lux < 25;
            if (next == ambientDark) { candidate = null; }
            else {
                if (candidate == null || candidate != next) { candidate = next; candidateSince = now; }
                if (now - candidateSince >= (next ? 12000 : 20000)) { ambientDark = next; candidate = null; }
            }
        }
        return night || ambientDark;
    }
    /** Solar elevation from the date and GPS coordinates; works without a network. */
    public static double solarElevation(long utcMillis, double lat, double lon) {
        double days = utcMillis / 86400000.0 - 10957.5;
        double mean = Math.toRadians((357.529 + 0.98560028 * days) % 360);
        double longitude = Math.toRadians((280.459 + 0.98564736 * days
            + 1.915 * Math.sin(mean) + 0.020 * Math.sin(2 * mean)) % 360);
        double tilt = Math.toRadians(23.439 - 0.00000036 * days);
        double ra = Math.atan2(Math.cos(tilt) * Math.sin(longitude), Math.cos(longitude));
        double dec = Math.asin(Math.sin(tilt) * Math.sin(longitude));
        double sidereal = Math.toRadians((280.46061837 + 360.98564736629 * days + lon) % 360);
        double phi = Math.toRadians(lat);
        return Math.toDegrees(Math.asin(Math.sin(phi) * Math.sin(dec)
            + Math.cos(phi) * Math.cos(dec) * Math.cos(sidereal - ra)));
    }
}
