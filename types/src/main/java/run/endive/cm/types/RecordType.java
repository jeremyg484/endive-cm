package run.endive.cm.types;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import run.endive.wasm.types.ValType;

public final class RecordType extends DefValType {

    private final List<LabelValType> fields;

    private RecordType(List<LabelValType> fields) {
        super(Kind.RECORD);
        this.fields = List.copyOf(fields);
    }

    public List<LabelValType> fields() {
        return fields;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public int alignment(TypeResolver typeResolver, PointerType ptrType) {
        int a = 1;
        for (LabelValType f : fields) {
            a =
                    Math.max(
                            a,
                            typeResolver
                                    .resolveDefValType(f.valType())
                                    .alignment(typeResolver, ptrType));
        }
        return a;
    }

    @Override
    public int elementSize(TypeResolver typeResolver, PointerType ptrType) {
        int s = 0;
        for (LabelValType f : fields) {
            var ft = typeResolver.resolveDefValType(f.valType());
            s = alignTo(s, ft.alignment(typeResolver, ptrType));
            s += ft.elementSize(typeResolver, ptrType);
        }
        return alignTo(s, alignment(typeResolver, ptrType));
    }

    @Override
    public List<ValType> flatten(TypeResolver typeResolver, PointerType ptrType) {
        List<ValType> flat = new ArrayList<>();
        for (LabelValType f : fields) {
            flat.addAll(typeResolver.resolveDefValType(f.valType()).flatten(typeResolver, ptrType));
        }
        return List.copyOf(flat);
    }

    public static final class Builder {
        private final List<LabelValType> fields = new ArrayList<>();

        private Builder() {}

        public Builder addField(LabelValType field) {
            fields.add(requireNonNull(field, "field"));
            return this;
        }

        public RecordType build() {
            return new RecordType(fields);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RecordType)) {
            return false;
        }
        RecordType that = (RecordType) o;
        return Objects.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(fields);
    }

    @Override
    public String toString() {
        return "RecordType{" + "fields=" + fields + '}';
    }
}
