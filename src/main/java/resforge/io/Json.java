package resforge.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free JSON reader/writer covering exactly the value model
 * this tool needs: {@link LinkedHashMap} objects (insertion order preserved),
 * {@link ArrayList} arrays, {@link String}, {@link Long} (integers),
 * {@link Double} (reals), {@link Boolean}, and {@code null}.
 */
public final class Json {
    private Json() {
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeVal(sb, o, 0);
        sb.append('\n');
        return sb.toString();
    }

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if(!p.eof())
            throw new IllegalArgumentException("Trailing content at index " + p.i);
        return v;
    }

    private static void writeVal(StringBuilder sb, Object o, int ind) {
        if(o == null) {
            sb.append("null");
        } else if(o instanceof String) {
            writeStr(sb, (String) o);
        } else if(o instanceof Boolean) {
            sb.append(((Boolean) o) ? "true" : "false");
        } else if(o instanceof Double || o instanceof Float) {
            sb.append(Double.toString(((Number) o).doubleValue()));
        } else if(o instanceof Number) {
            sb.append(o.toString());
        } else if(o instanceof Map) {
            writeObj(sb, (Map<?, ?>) o, ind);
        } else if(o instanceof List) {
            writeArr(sb, (List<?>) o, ind);
        } else {
            throw new IllegalArgumentException("Cannot serialize " + o.getClass());
        }
    }

    private static void writeObj(StringBuilder sb, Map<?, ?> m, int ind) {
        if(m.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int n = 0;
        for(Map.Entry<?, ?> e : m.entrySet()) {
            indent(sb, ind + 1);
            writeStr(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            writeVal(sb, e.getValue(), ind + 1);
            if(++n < m.size())
                sb.append(',');
            sb.append('\n');
        }
        indent(sb, ind);
        sb.append('}');
    }

    private static void writeArr(StringBuilder sb, List<?> l, int ind) {
        if(l.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for(int i = 0; i < l.size(); i++) {
            indent(sb, ind + 1);
            writeVal(sb, l.get(i), ind + 1);
            if(i + 1 < l.size())
                sb.append(',');
            sb.append('\n');
        }
        indent(sb, ind);
        sb.append(']');
    }

    private static void writeStr(StringBuilder sb, String s) {
        sb.append('"');
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch(c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if(c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int n) {
        for(int i = 0; i < n; i++)
            sb.append("  ");
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return i >= s.length();
        }

        void ws() {
            while(i < s.length()) {
                char c = s.charAt(i);
                if(c == ' ' || c == '\t' || c == '\n' || c == '\r')
                    i++;
                else
                    break;
            }
        }

        Object value() {
            if(eof())
                throw err("unexpected end of input");
            char c = s.charAt(i);
            switch(c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return keyword("true", Boolean.TRUE);
                case 'f': return keyword("false", Boolean.FALSE);
                case 'n': return keyword("null", null);
                default:  return number();
            }
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            ws();
            if(peek() == '}') {
                i++;
                return m;
            }
            while(true) {
                ws();
                if(peek() != '"')
                    throw err("expected object key");
                String key = string();
                ws();
                expect(':');
                ws();
                m.put(key, value());
                ws();
                char c = next();
                if(c == '}')
                    break;
                if(c != ',')
                    throw err("expected ',' or '}'");
            }
            return m;
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            expect('[');
            ws();
            if(peek() == ']') {
                i++;
                return l;
            }
            while(true) {
                ws();
                l.add(value());
                ws();
                char c = next();
                if(c == ']')
                    break;
                if(c != ',')
                    throw err("expected ',' or ']'");
            }
            return l;
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while(true) {
                if(eof())
                    throw err("unterminated string");
                char c = s.charAt(i++);
                if(c == '"')
                    break;
                if(c == '\\') {
                    char e = s.charAt(i++);
                    switch(e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw err("invalid escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object number() {
            int start = i;
            boolean real = false;
            while(i < s.length()) {
                char c = s.charAt(i);
                if((c >= '0' && c <= '9') || c == '-' || c == '+') {
                    i++;
                } else if(c == '.' || c == 'e' || c == 'E') {
                    real = true;
                    i++;
                } else {
                    break;
                }
            }
            String tok = s.substring(start, i);
            if(tok.isEmpty())
                throw err("invalid value");
            return real ? (Object) Double.parseDouble(tok) : (Object) Long.parseLong(tok);
        }

        Object keyword(String word, Object val) {
            if(!s.regionMatches(i, word, 0, word.length()))
                throw err("invalid token");
            i += word.length();
            return val;
        }

        char peek() {
            return eof() ? '\0' : s.charAt(i);
        }

        char next() {
            if(eof())
                throw err("unexpected end of input");
            return s.charAt(i++);
        }

        void expect(char c) {
            if(eof() || s.charAt(i) != c)
                throw err("expected '" + c + "'");
            i++;
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("JSON parse error at index " + i + ": " + msg);
        }
    }
}
