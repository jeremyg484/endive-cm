package run.endive.cm.types;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import run.endive.wasm.types.ValType;

public final class VariantType extends DefValType {

    private final List<Case> cases;

    private VariantType(List<Case> cases) {
        super(Kind.VARIANT);
        this.cases = List.copyOf(cases);
    }

    public List<Case> cases() {
        return cases;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public int alignment(TypeResolver typeResolver, PointerType ptrType) {
        return Math.max(
                discriminantType().alignment(typeResolver, ptrType),
                maxCaseAlignment(typeResolver, ptrType));
    }

    @Override
    public int elementSize(TypeResolver typeResolver, PointerType ptrType) {
        int s = discriminantType().elementSize(typeResolver, ptrType);
        s = alignTo(s, maxCaseAlignment(typeResolver, ptrType));
        int cs = 0;
        for (Case c : cases) {
            if (c.hasValType()) {
                cs =
                        Math.max(
                                cs,
                                typeResolver
                                        .resolveDefValType(c.valType())
                                        .elementSize(typeResolver, ptrType));
            }
        }
        s += cs;
        return alignTo(s, alignment(typeResolver, ptrType));
    }

    /**
     * Number of cases is asserted small in practice (well under 2^24), so the byte
     * count is computed directly from the size thresholds rather than porting the
     * Python reference's {@code ceil(log2(n)/8)}, which is precision-fragile at
     * power-of-two boundaries in floating point.
     */
    public DefValType discriminantType() {
        int n = cases.size();
        if (n <= 256) {
            return PrimValType.U8;
        }
        if (n <= 65536) {
            return PrimValType.U16;
        }
        return PrimValType.U32;
    }

    public int maxCaseAlignment(TypeResolver typeResolver, PointerType ptrType) {
        int a = 1;
        for (Case c : cases) {
            if (c.hasValType()) {
                a =
                        Math.max(
                                a,
                                typeResolver
                                        .resolveDefValType(c.valType())
                                        .alignment(typeResolver, ptrType));
            }
        }
        return a;
    }

    @Override
    public List<ValType> flatten(TypeResolver typeResolver, PointerType ptrType) {
        List<ValType> flat = new ArrayList<>();
        for (Case c : cases) {
            if (c.hasValType()) {
                var payloadFlat =
                        typeResolver.resolveDefValType(c.valType()).flatten(typeResolver, ptrType);
                for (int i = 0; i < payloadFlat.size(); i++) {
                    if (i < flat.size()) {
                        flat.set(i, join(flat.get(i), payloadFlat.get(i)));
                    } else {
                        flat.add(payloadFlat.get(i));
                    }
                }
            }
        }
        List<ValType> result = new ArrayList<>(discriminantType().flatten(typeResolver, ptrType));
        result.addAll(flat);
        return List.copyOf(result);
    }

    private static ValType join(ValType a, ValType b) {
        if (a == b) {
            return a;
        }
        if ((a == ValType.I32 && b == ValType.F32) || (a == ValType.F32 && b == ValType.I32)) {
            return ValType.I32;
        }
        return ValType.I64;
    }

    public static final class Builder {
        private final List<Case> cases = new ArrayList<>();

        private Builder() {}

        public Builder addCase(Case variantCase) {
            cases.add(requireNonNull(variantCase, "variantCase"));
            return this;
        }

        public VariantType build() {
            return new VariantType(cases);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VariantType)) {
            return false;
        }
        VariantType that = (VariantType) o;
        return Objects.equals(cases, that.cases);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(cases);
    }

    @Override
    public String toString() {
        return "VariantType{" + "cases=" + cases + '}';
    }
}
