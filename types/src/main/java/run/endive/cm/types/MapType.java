package run.endive.cm.types;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

public final class MapType extends DefValType implements Specialized<MapType.DespecializedMapType> {

    private final ValType keyType;
    private final ValType valueType;

    private MapType(ValType keyType, ValType valueType) {
        super(Kind.MAP);
        this.keyType = requireNonNull(keyType, "keyType");
        this.valueType = requireNonNull(valueType, "valueType");
    }

    public ValType keyType() {
        return keyType;
    }

    public ValType valueType() {
        return valueType;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public DespecializedMapType despecialize() {
        var tuple = TupleType.builder().addElementType(keyType).addElementType(valueType).build();
        return new DespecializedMapType(tuple.despecialize());
    }

    @Override
    public int alignment(TypeResolver typeResolver, PointerType ptrType) {
        return despecialize().alignment(typeResolver, ptrType);
    }

    @Override
    public int elementSize(TypeResolver typeResolver, PointerType ptrType) {
        return despecialize().elementSize(typeResolver, ptrType);
    }

    @Override
    public List<run.endive.wasm.types.ValType> flatten(
            TypeResolver typeResolver, PointerType ptrType) {
        return despecialize().flatten(typeResolver, ptrType);
    }

    public static final class Builder {
        private ValType keyType;
        private ValType valueType;

        private Builder() {}

        public Builder withKeyType(ValType keyType) {
            this.keyType = keyType;
            return this;
        }

        public Builder withValueType(ValType valueType) {
            this.valueType = valueType;
            return this;
        }

        public MapType build() {
            return new MapType(keyType, valueType);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MapType)) {
            return false;
        }
        MapType that = (MapType) o;
        return Objects.equals(keyType, that.keyType) && Objects.equals(valueType, that.valueType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyType, valueType);
    }

    @Override
    public String toString() {
        return "MapType{" + "keyType=" + keyType + ", valueType=" + valueType + '}';
    }

    public static final class DespecializedMapType extends DefValType {

        private final RecordType recordType;

        private DespecializedMapType(RecordType recordType) {
            super(Kind.LIST);
            this.recordType = recordType;
        }

        public RecordType recordType() {
            return recordType;
        }

        @Override
        public int alignment(TypeResolver typeResolver, PointerType ptrType) {
            // Despecialized maps are always unbounded lists of (key, value) pairs;
            // alignment doesn't depend on the element type, same as any unbounded list.
            return ptrType.size();
        }

        @Override
        public int elementSize(TypeResolver typeResolver, PointerType ptrType) {
            return 2 * ptrType.size();
        }

        @Override
        public List<run.endive.wasm.types.ValType> flatten(
                TypeResolver typeResolver, PointerType ptrType) {
            return List.of(ptrType.coreValType(), ptrType.coreValType());
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DespecializedMapType)) {
                return false;
            }
            DespecializedMapType that = (DespecializedMapType) o;
            return Objects.equals(recordType, that.recordType);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(recordType);
        }

        @Override
        public String toString() {
            return "DespecializedMapType{" + "recordType=" + recordType + '}';
        }
    }
}
