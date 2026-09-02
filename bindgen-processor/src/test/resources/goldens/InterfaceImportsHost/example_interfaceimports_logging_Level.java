package endive.testing.example.interfaceimports.logging;

import javax.annotation.processing.Generated;
import run.endive.cm.abi.VariantValue;

/**
 * The WIT enum {@code level}, declared by {@code example:interface-imports/logging}.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public enum Level {

    DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error");

    private final String label;

    Level(String label) {
        this.label = label;
    }

    /**
     * This case as the ABI carries it, which is a variant with no payload.
     */
    public VariantValue toComponent() {
        return VariantValue.of(label, null);
    }

    /**
     * The case a lifted value names.
     */
    public static Level fromComponent(Object value) {
        String label = ((VariantValue) value).label();
        for (Level candidate : values()) {
            if (candidate.label.equals(label)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown level: " + label);
    }
}
