package com.cramdungeon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-to-speech on a card. AnkiDroid's provider hands over TTS only as numbered
 * placeholders ({@code [anki:play:q:2]}) without their text, so the speech is
 * rebuilt from the card template and the note's fields: {@code {{tts ja_JP:Field}}}
 * filters and inline {@code [anki:tts lang=ja_JP]...[/anki:tts]} tags, in the order
 * Anki numbers them. The answer side starts with the question's ({@code {{FrontSide}}}).
 */
public final class CardTts {

    public static final class Speech {
        public final String lang;
        public final float rate;
        public final String text;

        Speech(String lang, float rate, String text) {
            this.lang = lang;
            this.rate = rate;
            this.text = text;
        }

        @Override
        public String toString() {
            return lang + "@" + rate + ":" + text;
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\\{\\{(.*?)\\}\\}", Pattern.DOTALL);
    private static final Pattern INLINE = Pattern.compile("\\[anki:tts([^\\]]*)\\](.*?)\\[/anki:tts\\]",
            Pattern.DOTALL);
    private static final Pattern CLOZE = Pattern.compile("\\{\\{c(\\d+)::(.*?)(?:::(.*?))?\\}\\}", Pattern.DOTALL);
    private static final Pattern RUBY = Pattern.compile(" ?([^ >\\[]+?)\\[(.+?)\\]");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private CardTts() {
    }

    /**
     * Speech on one side of a card, in order.
     *
     * @param frontSide the question side's speech, for {{FrontSide}} on the answer
     * @param clozeOrd  the card's cloze number (1-based), or 0 for normal note types
     */
    public static List<Speech> side(String template, Map<String, String> fields, List<Speech> frontSide,
                                    int clozeOrd, boolean question) {
        List<Speech> out = new ArrayList<>();
        if (template == null) {
            return out;
        }
        Deque<Boolean> sections = new ArrayDeque<>();
        boolean active = true;
        Matcher m = TOKEN.matcher(template);
        int last = 0;
        while (m.find()) {
            if (active) {
                inline(template.substring(last, m.start()), out);
            }
            last = m.end();
            String inner = m.group(1).trim();
            if (inner.isEmpty()) {
                continue;
            }
            char c = inner.charAt(0);
            if (c == '#' || c == '^') {
                boolean filled = !isEmpty(field(fields, inner.substring(1).trim()));
                sections.push(active);
                active = active && (c == '#') == filled;
            } else if (c == '/') {
                if (!sections.isEmpty()) {
                    active = sections.pop();
                }
            } else if (c == '!' || !active) {
                // comment, or inside a section that doesn't render
            } else if (inner.equals("FrontSide")) {
                out.addAll(frontSide);
            } else {
                replacement(inner, fields, clozeOrd, question, out);
            }
        }
        if (active) {
            inline(template.substring(last), out);
        }
        return out;
    }

    private static void replacement(String inner, Map<String, String> fields, int clozeOrd, boolean question,
                                    List<Speech> out) {
        String[] parts = inner.split(":");
        String name = parts[parts.length - 1].trim();
        String value = field(fields, name);
        if (value == null) {
            return;
        }
        List<String> filters = new ArrayList<>();
        for (int i = 0; i < parts.length - 1; i++) {
            filters.add(parts[i].trim());
        }
        if (!filters.isEmpty() && filters.get(0).startsWith("tts ")) {
            String[] opts = filters.get(0).split("\\s+");
            String text = value;
            for (int i = filters.size() - 1; i >= 1; i--) {
                text = filter(filters.get(i), text, clozeOrd, question);
            }
            text = plain(text);
            if (opts.length >= 2 && !text.isEmpty()) {
                out.add(new Speech(opts[1], speed(opts), text));
            }
        } else if (!filters.contains("type")) {
            inline(value, out);
        }
    }

    private static String filter(String name, String text, int clozeOrd, boolean question) {
        switch (name) {
            case "cloze": {
                Matcher m = CLOZE.matcher(text);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    boolean current = Integer.toString(clozeOrd).equals(m.group(1));
                    String shown = current && question ? "..." : m.group(2);
                    m.appendReplacement(sb, Matcher.quoteReplacement(shown));
                }
                m.appendTail(sb);
                return sb.toString();
            }
            case "kana":
                return RUBY.matcher(text).replaceAll("$2");
            case "kanji":
            case "furigana":
                return RUBY.matcher(text).replaceAll("$1");
            default:
                return text;
        }
    }

    private static void inline(String text, List<Speech> out) {
        if (text == null || !text.contains("[anki:tts")) {
            return;
        }
        Matcher m = INLINE.matcher(text);
        while (m.find()) {
            String lang = null;
            String[] attrs = m.group(1).trim().split("\\s+");
            for (String attr : attrs) {
                if (attr.startsWith("lang=")) {
                    lang = attr.substring(5);
                }
            }
            String spoken = plain(m.group(2));
            if (lang != null && !spoken.isEmpty()) {
                out.add(new Speech(lang, speed(attrs), spoken));
            }
        }
    }

    private static float speed(String[] opts) {
        for (String o : opts) {
            if (o.startsWith("speed=")) {
                try {
                    return Math.max(0.1f, Math.min(4f, Float.parseFloat(o.substring(6))));
                } catch (NumberFormatException ignored) {
                    return 1f;
                }
            }
        }
        return 1f;
    }

    private static String field(Map<String, String> fields, String name) {
        if (fields.containsKey(name)) {
            return fields.get(name);
        }
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean isEmpty(String value) {
        return value == null || plain(value).isEmpty();
    }

    /** Field HTML to the words a voice should read. */
    static String plain(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>", " ");
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** "ja_JP" or "ja-JP" as a Locale. */
    public static Locale locale(String lang) {
        String[] p = lang.replace('-', '_').split("_");
        return p.length >= 2 ? new Locale(p[0], p[1]) : new Locale(p[0]);
    }

    public static List<Speech> none() {
        return Collections.emptyList();
    }
}
