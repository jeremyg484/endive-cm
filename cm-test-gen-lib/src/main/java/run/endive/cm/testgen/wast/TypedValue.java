package run.endive.cm.testgen.wast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class TypedValue {

    private final String type;
    private final String value;

    public TypedValue(@JsonProperty("type") String type, @JsonProperty("value") String value) {
        this.type = type;
        this.value = value;
    }

    public String type() {
        return type;
    }

    public String value() {
        return value;
    }

    public String emitValue() {
        switch (type) {
            case "bool":
            case "u16":
            case "s32":
                return value;
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
                return "'" + value + "'";
            case "string":
                return "\"" + value + "\"";
            default:
                throw new UnsupportedOperationException("Unsupported type: " + type);
        }
    }
}
