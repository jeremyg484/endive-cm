package run.endive.cm.types;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import run.endive.wasm.types.ValType;

public final class FlagsType extends DefValType {

    private final List<String> labels;

    private FlagsType(List<String> labels) {
        super(Kind.FLAGS);
        this.labels = List.copyOf(labels);
    }

    public List<String> labels() {
        return labels;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public int alignment(TypeResolver typeResolver, PointerType ptrType) {
        return sizeForLabelCount();
    }

    @Override
    public int elementSize(TypeResolver typeResolver, PointerType ptrType) {
        return sizeForLabelCount();
    }

    private int sizeForLabelCount() {
        int n = labels.size();
        if (n <= 8) {
            return 1;
        }
        if (n <= 16) {
            return 2;
        }
        return 4;
    }

    @Override
    public List<ValType> flatten(TypeResolver typeResolver, PointerType ptrType) {
        return List.of(ValType.I32);
    }

    public static final class Builder {
        private final List<String> labels = new ArrayList<>();

        private Builder() {}

        public Builder addLabel(String label) {
            labels.add(requireNonNull(label, "label"));
            return this;
        }

        public FlagsType build() {
            return new FlagsType(labels);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FlagsType)) {
            return false;
        }
        FlagsType that = (FlagsType) o;
        return Objects.equals(labels, that.labels);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(labels);
    }

    @Override
    public String toString() {
        return "FlagsType{" + "labels=" + labels + '}';
    }
}
