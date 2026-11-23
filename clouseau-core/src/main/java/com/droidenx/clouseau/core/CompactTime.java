package com.droidenx.clouseau.core;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typing a time for the time range: digits formatted as they're typed ({@link #mask}) and a
 * partial time filled out to the millisecond ({@link #complete}).
 *
 * <p>The mask reads digits date first, {@code yyyy-MM-dd HH:mm:ss.SSS}, so {@code 20261123030700}
 * becomes {@code 2026-11-23 03:07:00}. A time alone is typed with its colons, {@code 10:00}.
 */
public final class CompactTime {

    private CompactTime() {}

    /** The full form: the mask writes a digit for each letter and copies the rest. */
    private static final String FULL = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final int MAX_DIGITS = 17;

    /**
     * A date-first prefix, with or without its separators: four digits of year, then any of
     * month, day, hour, minute, second and milliseconds. A space or {@code T} may separate date and
     * time, and a comma the milliseconds.
     */
    private static final Pattern DATE_FIRST = Pattern.compile(
            "\\d{4}(-?\\d{1,2}(-?\\d{1,2}([ T]?\\d{1,2}(:?\\d{1,2}(:?\\d{1,2}([.,]?\\d{1,3})?)?)?)?)?)?[-:. T,]?");

    /** {@code HH:mm}, {@code HH:mm:ss} or {@code HH:mm:ss.S…}, a time alone. */
    private static final Pattern TIME_ONLY = Pattern.compile("(\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,3}))?)?");

    /** A date-first value cut at a field boundary: {@code yyyy-MM}, a date, or a date and time. */
    private static final Pattern DATE_PARTS = Pattern.compile(
            "(\\d{4})-(\\d{2})(?:-(\\d{2})(?: (\\d{2})(?::(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,3}))?)?)?)?)?");

    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * {@code text} with the separators of {@code yyyy-MM-dd HH:mm:ss.SSS} put between its digits,
     * or null when it isn't a date-first value (a time alone, {@code +5m}, more than 17 digits,
     * or anything else), which is then left as typed. A separator goes in only once a digit
     * follows it, so {@code 2026} stays {@code 2026} and {@code 20261} becomes {@code 2026-1}.
     */
    public static String mask(String text) {
        if (text == null) return null;
        String digits = text.replaceAll("\\D", "");
        if (digits.length() < 4 || digits.length() > MAX_DIGITS) return null;
        if (!DATE_FIRST.matcher(text).matches() && !digits.equals(text)) return null;
        return maskDigits(digits);
    }

    /**
     * An edit's result, {@code after}, as the field should show it, or null to keep it as typed.
     * It's {@link #mask}ed, and if {@code before} was a date-first value, an edit that leaves
     * only digits and separators is masked again too, wherever it was made: deleting a digit
     * from the middle of {@code 2026-11-23} gives {@code 2021-12-3}, not {@code 202-11-23}.
     */
    public static String reformat(String before, String after) {
        String masked = mask(after);
        if (masked != null || mask(before) == null || !after.matches("[\\d\\-:. T,]*")) return masked;
        String digits = after.replaceAll("\\D", "");
        if (digits.length() > MAX_DIGITS) return null;
        return digits.length() < 4 ? digits : maskDigits(digits);
    }

    /** The full form's separators around {@code digits}, which must start a date. */
    private static String maskDigits(String digits) {
        StringBuilder sb = new StringBuilder(digits.length() + 6);
        int d = 0;
        for (int i = 0; i < FULL.length() && d < digits.length(); i++) {
            char c = FULL.charAt(i);
            if (Character.isLetter(c)) sb.append(digits.charAt(d++));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Where the caret goes in {@code masked} to stay after its first {@code digits} digits. */
    public static int caretAfterDigits(String masked, int digits) {
        if (digits <= 0) return 0;
        int seen = 0;
        for (int i = 0; i < masked.length(); i++) {
            if (Character.isDigit(masked.charAt(i)) && ++seen == digits) return i + 1;
        }
        return masked.length();
    }

    /**
     * {@code text} filled out to the millisecond, or null if it can't be: it must end at a whole
     * field ({@code 2026-11}, a date, {@code 2026-11-23 03}, {@code 03:07}…) and be a real date
     * and time. The start of a range fills with the earliest instant of what's typed, the end
     * with the last, so a day, minute or month typed as the end is included whole:
     * <ul>
     *   <li>{@code 2026-11-23}: from {@code 2026-11-23 00:00:00.000}, to {@code 2026-11-23 23:59:59.999}</li>
     *   <li>{@code 2026-02}: from {@code 2026-02-01 00:00:00.000}, to {@code 2026-02-28 23:59:59.999}</li>
     *   <li>{@code 10:15}: from {@code 10:15:00.000}, to {@code 10:15:59.999} (a time alone stays one)</li>
     *   <li>{@code 10:15:00.1}: from {@code 10:15:00.100}, to {@code 10:15:00.199}</li>
     * </ul>
     * Text that's already complete comes back unchanged.
     */
    public static String complete(String text, boolean end) {
        if (text == null) return null;
        Matcher t = TIME_ONLY.matcher(text);
        if (t.matches()) {
            return completeTime(t.group(1), t.group(2), t.group(3), t.group(4), end);
        }
        Matcher m = DATE_PARTS.matcher(text);
        if (!m.matches()) return null;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) return null;
        String day = m.group(3);
        if (day == null) {
            YearMonth ym = YearMonth.of(year, month);
            LocalDate date = end ? ym.atEndOfMonth() : ym.atDay(1);
            return date + (end ? " 23:59:59.999" : " 00:00:00.000");
        }
        String date = m.group(1) + "-" + m.group(2) + "-" + day;
        try {
            LocalDate.parse(date, STRICT_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
        if (m.group(4) == null) return date + (end ? " 23:59:59.999" : " 00:00:00.000");
        String hour = m.group(4);
        if (m.group(5) == null) {
            if (Integer.parseInt(hour) > 23) return null;
            return date + " " + hour + (end ? ":59:59.999" : ":00:00.000");
        }
        String time = completeTime(hour, m.group(5), m.group(6), m.group(7), end);
        return time == null ? null : date + " " + time;
    }

    /** {@code HH:mm[:ss[.S…]]} filled out to {@code HH:mm:ss.SSS}, or null if it isn't a time. */
    private static String completeTime(String h, String m, String s, String ms, boolean end) {
        String sec = s != null ? s : end ? "59" : "00";
        String given = ms != null ? ms : "";
        String milli = given + (end ? "9" : "0").repeat(3 - given.length());
        String time = h + ":" + m + ":" + sec + "." + milli;
        try {
            LocalTime.parse(time);
        } catch (DateTimeParseException e) {
            return null;
        }
        return time;
    }
}
