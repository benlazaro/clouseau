package com.droidenx.clouseau.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code THIRD-PARTY-NOTICES.txt} split into the Licenses dialog's tabs: Clouseau's own
 * license first, then one tab per license family (Apache 2.0, MIT, …), each with the
 * components under it and then the license text, or texts when each component's copyright
 * is part of its own copy (MIT, BSD).
 * <p>
 * The file stays the one source: it ships as-is in the release's {@code legal} folder and
 * {@code checkThirdPartyNotices} reads it. This reads its existing layout: the "Java runtime"
 * and "Components" sections, then each license text under a header between two lines of
 * {@code =}. A component and a license text land in the same tab when their license names
 * fall in the same family ({@link #family}).
 */
final class LicenseNotices {

    /** One shipped library or font: its entry in the "Components" section. */
    record Component(String name, String coordinates, String license, String copyright, String url, String notes) {}

    /** One license text; {@code title} is its header ("MIT License — SLF4J"). */
    record Text(String title, String body) {}

    /** A tab: its label, an optional note above everything (the Java runtime), its components, then its texts. */
    record Tab(String label, String note, List<Component> components, List<Text> texts) {}

    private static final Pattern RULE = Pattern.compile("={40,}");
    private static final Pattern UNDERLINE = Pattern.compile("-{3,}");
    private static final Pattern MIT = Pattern.compile("\\bMIT\\b");
    private static final String DASH = " — ";

    private LicenseNotices() {}

    /**
     * The tabs, Clouseau's first (with the Java runtime note), then the families with the
     * most components first. Empty if {@code notices} has no components or no license texts,
     * so the caller can show the file as it is instead.
     */
    static List<Tab> parse(String clouseauLicense, String notices) {
        String[] lines = notices.replace("\r\n", "\n").split("\n", -1);
        String runtime = paragraph(lines, "Java runtime");
        List<Component> components = components(lines);
        List<Text> texts = texts(lines);
        if (components.isEmpty() || texts.isEmpty()) return List.of();

        // Family → its components and texts, in the order the components are listed.
        Map<String, List<Component>> byFamily = new LinkedHashMap<>();
        Map<String, List<Text>> textsByFamily = new LinkedHashMap<>();
        for (Component c : components) byFamily.computeIfAbsent(family(c.license()), k -> new ArrayList<>()).add(c);
        for (Text t : texts) {
            String family = family(t.title().split(DASH, 2)[0]);
            byFamily.computeIfAbsent(family, k -> new ArrayList<>());
            textsByFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(t);
        }

        List<Tab> tabs = new ArrayList<>();
        List<String> order = new ArrayList<>(byFamily.keySet());
        order.sort(Comparator.comparingInt((String f) -> -byFamily.get(f).size()));   // stable: ties keep list order
        for (String family : order) {
            tabs.add(new Tab(family, null, List.copyOf(byFamily.get(family)),
                    List.copyOf(textsByFamily.getOrDefault(family, List.of()))));
        }
        if (clouseauLicense != null) {
            tabs.add(0, new Tab("Clouseau", runtime, List.of(),
                    List.of(new Text("MIT License — Clouseau", clouseauLicense.strip()))));
        }
        return tabs;
    }

    /** The short tab label for a license name: "Apache License 2.0" → "Apache 2.0"; an unknown one keeps its name. */
    static String family(String licenseName) {
        String name = licenseName.strip();
        if (name.startsWith("Apache License")) return "Apache 2.0";
        if (name.startsWith("BSD 3-Clause")) return "BSD 3-Clause";
        if (name.contains("Open Font License")) return "OFL 1.1";
        if (name.startsWith("CC0")) return "CC0";
        if (MIT.matcher(name).find()) return "MIT";
        int paren = name.indexOf(" (");
        return paren > 0 ? name.substring(0, paren) : name;
    }

    /** The text under a section titled {@code title} (underlined with dashes), with its own line breaks. */
    private static String paragraph(String[] lines, String title) {
        int start = section(lines, title);
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length && !isSectionStart(lines, i) && !RULE.matcher(lines[i]).matches(); i++) {
            sb.append(lines[i].strip()).append('\n');
        }
        String text = stripBlankLines(sb.toString());
        return text.isEmpty() ? null : text;
    }

    /** The "Components" entries: a name line at the margin, then indented lines (license — copyright, website, notes). */
    private static List<Component> components(String[] lines) {
        List<Component> result = new ArrayList<>();
        int i = section(lines, "Components");
        if (i < 0) return result;
        while (i < lines.length && !RULE.matcher(lines[i]).matches()) {
            String line = lines[i];
            if (line.isBlank() || line.startsWith(" ") || !line.contains(" (")) { i++; continue; }
            // The name line, continued while its coordinates' parenthesis is open.
            StringBuilder head = new StringBuilder(line.strip());
            while (count(head, '(') > count(head, ')') && i + 1 < lines.length) head.append(' ').append(lines[++i].strip());
            i++;
            List<String> body = new ArrayList<>();
            while (i < lines.length && !RULE.matcher(lines[i]).matches()
                    && (lines[i].isBlank() || lines[i].startsWith(" "))) {
                body.add(lines[i].length() >= 4 ? lines[i].substring(4) : lines[i].strip());
                i++;
            }
            while (!body.isEmpty() && body.get(body.size() - 1).isBlank()) body.remove(body.size() - 1);
            if (body.isEmpty()) continue;

            String h = head.toString();
            int open = h.indexOf(" (");
            String name = h.substring(0, open);
            String coordinates = h.substring(open + 2, h.lastIndexOf(')')).replaceAll("\\s+", " ");
            String[] licenseLine = body.get(0).split(DASH, 2);
            String url = null;
            StringBuilder notes = new StringBuilder();
            for (String b : body.subList(1, body.size())) {
                if (url == null && notes.isEmpty() && b.startsWith("http")) url = b.strip();
                else notes.append(b).append('\n');
            }
            result.add(new Component(name, coordinates, licenseLine[0].strip(),
                    licenseLine.length > 1 ? licenseLine[1].strip() : "", url,
                    notes.toString().strip().isEmpty() ? null : notes.toString().stripTrailing()));
        }
        return result;
    }

    /** Each license text: the header between two {@code =} rules (title, then any indented lines), and what follows. */
    private static List<Text> texts(String[] lines) {
        List<Text> result = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (!RULE.matcher(lines[i]).matches()) { i++; continue; }
            int close = i + 1;
            while (close < lines.length && !RULE.matcher(lines[close]).matches()) close++;
            if (close >= lines.length || close == i + 1) break;
            String title = lines[i + 1].strip();
            int end = close + 1;
            while (end < lines.length && !RULE.matcher(lines[end]).matches()) end++;
            StringBuilder body = new StringBuilder();
            for (int j = close + 1; j < end; j++) body.append(lines[j]).append('\n');
            result.add(new Text(title, stripBlankLines(body.toString())));
            i = end;
        }
        return result;
    }

    /** The line after a section's title and its dashed underline, or -1. */
    private static int section(String[] lines, String title) {
        for (int i = 0; i + 1 < lines.length; i++) {
            if (lines[i].strip().equals(title) && UNDERLINE.matcher(lines[i + 1].strip()).matches()) return i + 2;
        }
        return -1;
    }

    private static boolean isSectionStart(String[] lines, int i) {
        return i + 1 < lines.length && !lines[i].isBlank() && UNDERLINE.matcher(lines[i + 1].strip()).matches()
                && !lines[i].startsWith(" ");
    }

    /** Drops blank lines at the start and end, keeping the text's own indentation. */
    private static String stripBlankLines(String text) {
        String[] lines = text.split("\n", -1);
        int from = 0, to = lines.length;
        while (from < to && lines[from].isBlank()) from++;
        while (to > from && lines[to - 1].isBlank()) to--;
        return String.join("\n", java.util.Arrays.asList(lines).subList(from, to));
    }

    private static int count(CharSequence s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
