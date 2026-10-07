package dev.whynotac.recording.cli;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;

/**
 * Minimal JSON rendering for the inspection tool. Records are rendered reflectively, so new event
 * and packet types show up without changes here. Floats and doubles are rendered twice: as the
 * shortest round-tripping decimal and as raw IEEE-754 bits, because "looks equal" is not evidence.
 */
final class Json {

    private Json() {}

    static String string(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    static String value(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> out.append(string(s));
            case Boolean b -> out.append(b);
            case Float f -> out.append("{\"f32\":").append(string(Float.toString(f)))
                    .append(",\"bits\":\"0x").append(Integer.toHexString(Float.floatToRawIntBits(f))).append("\"}");
            case Double d -> out.append("{\"f64\":").append(string(Double.toString(d)))
                    .append(",\"bits\":\"0x").append(Long.toHexString(Double.doubleToRawLongBits(d))).append("\"}");
            case Number n -> out.append(n);
            case Enum<?> e -> out.append(string(e.name()));
            case java.util.UUID u -> out.append(string(u.toString()));
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(string(String.valueOf(entry.getKey()))).append(':');
                    append(out, entry.getValue());
                }
                out.append('}');
            }
            case Collection<?> collection -> {
                out.append('[');
                boolean first = true;
                for (Object element : collection) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    append(out, element);
                }
                out.append(']');
            }
            case Record record -> {
                out.append("{\"type\":").append(string(record.getClass().getSimpleName()));
                for (RecordComponent component : record.getClass().getRecordComponents()) {
                    // "type" is the record's own name; a component of that name (Unmodeled.type, GameEvent.type) becomes "typeId".
                    String name = component.getName().equals("type") ? "typeId" : component.getName();
                    out.append(',').append(string(name)).append(':');
                    try {
                        append(out, component.getAccessor().invoke(record));
                    } catch (ReflectiveOperationException e) {
                        out.append(string("<" + e + ">"));
                    }
                }
                out.append('}');
            }
            default -> out.append(string(value.toString()));
        }
    }
}
