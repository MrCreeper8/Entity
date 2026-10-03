package dev.entity.core.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON codec shared by the durable local data model. */
public final class SimpleJson {
    private SimpleJson() {
    }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder(512);
        write(value, output, 0);
        output.append('\n');
        return output.toString();
    }

    public static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.value();
        parser.whitespace();
        if (!parser.end()) {
            throw parser.error("trailing content");
        }
        return value;
    }

    private static void write(Object value, StringBuilder output, int depth) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            quote(text, output);
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            if (!map.isEmpty()) {
                int index = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("JSON object key must be text");
                    }
                    output.append(index++ == 0 ? '\n' : ",\n");
                    indent(output, depth + 1);
                    quote(key, output);
                    output.append(": ");
                    write(entry.getValue(), output, depth + 1);
                }
                output.append('\n');
                indent(output, depth);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            output.append('[');
            int index = 0;
            for (Object element : iterable) {
                output.append(index++ == 0 ? '\n' : ",\n");
                indent(output, depth + 1);
                write(element, output, depth + 1);
            }
            if (index > 0) {
                output.append('\n');
                indent(output, depth);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
        }
    }

    private static void quote(String text, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static void indent(StringBuilder output, int depth) {
        output.append("  ".repeat(depth));
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object value() {
            whitespace();
            if (end()) {
                throw error("expected a value");
            }
            return switch (input.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                whitespace();
                if (end() || input.charAt(index) != '"') {
                    throw error("expected object key");
                }
                String key = string();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> result = new ArrayList<>();
            whitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(value());
                whitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!end()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character != '\\') {
                    if (character < 0x20) {
                        throw error("unescaped control character");
                    }
                    result.append(character);
                    continue;
                }
                if (end()) {
                    throw error("unfinished escape");
                }
                char escape = input.charAt(index++);
                result.append(switch (escape) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw error("invalid escape");
                });
            }
            throw error("unterminated string");
        }

        private char unicode() {
            if (index + 4 > input.length()) {
                throw error("unfinished unicode escape");
            }
            String digits = input.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException invalid) {
                throw error("invalid unicode escape");
            }
        }

        private Object number() {
            int start = index;
            if (consume('-')) {
                // sign consumed
            }
            digits();
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                digits();
            }
            if (!end() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (!end() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                digits();
            }
            if (start == index) {
                throw error("expected a number");
            }
            String encoded = input.substring(start, index);
            try {
                if (decimal) {
                    return Double.parseDouble(encoded);
                }
                return Long.parseLong(encoded);
            } catch (NumberFormatException invalid) {
                throw error("invalid number");
            }
        }

        private void digits() {
            int start = index;
            while (!end() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw error("expected a digit");
            }
        }

        private Object literal(String text, Object value) {
            if (!input.startsWith(text, index)) {
                throw error("invalid literal");
            }
            index += text.length();
            return value;
        }

        private void whitespace() {
            while (!end() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (!end() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean end() {
            return index >= input.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + index);
        }
    }
}
