package run.endive.cm.testgen.wast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class TypedValue {

    private final String type;
    private final Object value;

    public TypedValue(@JsonProperty("type") String type, @JsonProperty("value") Object value) {
        this.type = type;
        this.value = value;
    }

    public String type() {
        return type;
    }

    public Object value() {
        return value;
    }

    public String emitValue() {
        switch (type) {
            case "bool":
            case "u16":
            case "s32":
                return value.toString();
            case "s8":
                return "(byte) " + value;
            case "u8":
            case "s16":
                return "(short) " + value;
            case "u32":
            case "s64":
                return value + "L";
            case "u64":
                return "new BigInteger(" + value + ")";
            case "f32":
                return value + "f";
            case "f64":
                return value + "d";
            case "char":
                // A component char is any Unicode scalar value up to U+10FFFF, so it cannot
                // be emitted as a Java char literal: charAt(0) would truncate anything above
                // the BMP to a lone surrogate. CharValue carries the whole range.
                String text = (String) value;
                return "CharValue.of(0x"
                        + Integer.toHexString(text.codePointAt(0)).toUpperCase()
                        + ")";
            case "string":
                return "\"" + value + "\"";
            case "list":
                return emitList();
            default:
                throw new UnsupportedOperationException("Unsupported type: " + type);
        }
    }

    private String emitList() {
        if (!(value instanceof java.util.List)) {
            throw new UnsupportedOperationException("list value must be an array, got: " + value);
        }
        var elements = (java.util.List<?>) value;
        var out = new StringBuilder("List.of(");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(element(elements.get(i)).emitValue());
        }
        return out.append(')').toString();
    }

    private static TypedValue element(Object element) {
        if (!(element instanceof java.util.Map)) {
            throw new UnsupportedOperationException(
                    "list element must be a typed value, got: " + element);
        }
        var map = (java.util.Map<?, ?>) element;
        return new TypedValue((String) map.get("type"), map.get("value"));
    }
}
