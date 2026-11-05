package com.droidenx.clouseau.core;

/** A merged source's time offset (D-17) as text: {@code +3m 12s}, {@code -1h 0m 0.250s}. */
public final class TimeOffset {

    private TimeOffset() {}

    /**
     * Formats {@code millis} with a sign and the units from the largest one needed down to
     * seconds: {@code +3m 12s}, {@code -2h 0m 5s}, {@code +0.250s}. Zero is {@code 0s}.
     */
    public static String format(long millis) {
        if (millis == 0) return "0s";
        String sign = millis < 0 ? "-" : "+";
        long a = Math.abs(millis == Long.MIN_VALUE ? Long.MAX_VALUE : millis);
        long h = a / 3_600_000, m = a / 60_000 % 60, s = a / 1_000 % 60, ms = a % 1_000;
        StringBuilder sb = new StringBuilder(sign);
        if (h > 0) sb.append(h).append("h ");
        if (h > 0 || m > 0) sb.append(m).append("m ");
        sb.append(s);
        if (ms > 0) sb.append('.').append(String.format("%03d", ms));
        return sb.append('s').toString();
    }
}
