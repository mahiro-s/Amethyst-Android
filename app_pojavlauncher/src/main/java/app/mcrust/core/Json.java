package app.mcrust.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Небольшой разборщик JSON без зависимостей: Map (порядок ключей сохраняется), List, String, Double, Boolean, null. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) throws IOException {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw p.err("лишние символы после JSON");
        return v;
    }

    private IOException err(String what) {
        return new IOException("JSON: " + what + " на позиции " + i);
    }

    private void ws() {
        while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) i++;
    }

    private Object value(int depth) throws IOException {
        if (depth > 200) throw err("слишком глубокая вложенность");
        if (i >= s.length()) throw err("неожиданный конец");
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("ждал ':'");
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                } else if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return m;
                } else {
                    throw err("ждал ',' или '}'");
                }
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                } else if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                    return l;
                } else {
                    throw err("ждал ',' или ']'");
                }
            }
        }
        if (c == '"') return string();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            int st = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            try {
                return Double.valueOf(s.substring(st, i));
            } catch (NumberFormatException e) {
                i = st;
                throw err("плохое число");
            }
        }
        throw err("неожиданный символ");
    }

    private String string() throws IOException {
        if (i >= s.length() || s.charAt(i) != '"') throw err("ждал строку");
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("строка не закрыта");
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw err("обрыв после \\");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("плохая последовательность \\u");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw err("плохая последовательность \\u");
                    }
                    i += 4;
                    break;
                default: throw err("неизвестный escape");
            }
        }
        return b.toString();
    }

    // ---- удобные доступы ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    /** Вложенный поиск: at(root, "downloads", "client"). Null, если чего-то нет. */
    public static Object at(Object root, String... path) {
        Object cur = root;
        for (String k : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(k);
        }
        return cur;
    }

    public static String text(Object root, String... path) {
        Object v = at(root, path);
        return v instanceof String ? (String) v : null;
    }

    public static long num(Object root, long def, String... path) {
        Object v = at(root, path);
        return v instanceof Double ? ((Double) v).longValue() : def;
    }

    public static boolean bool(Object root, boolean def, String... path) {
        Object v = at(root, path);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    // ---- запись (для файла установленных модов) ----

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(o, b);
        return b.toString();
    }

    private static void write(Object o, StringBuilder b) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String) {
            b.append('"');
            for (char c : ((String) o).toCharArray()) {
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            b.append('"');
        } else if (o instanceof Double && ((Double) o) == Math.rint((Double) o) && Math.abs((Double) o) < 1e15) {
            b.append(((Double) o).longValue());
        } else if (o instanceof Number || o instanceof Boolean) {
            b.append(o);
        } else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                write(String.valueOf(e.getKey()), b);
                b.append(':');
                write(e.getValue(), b);
            }
            b.append('}');
        } else if (o instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object x : (List<?>) o) {
                if (!first) b.append(',');
                first = false;
                write(x, b);
            }
            b.append(']');
        } else {
            write(o.toString(), b);
        }
    }
}
