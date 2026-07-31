package run.endive.cm.types;

import java.util.List;
import run.endive.wasm.types.ValType;

public final class PrimValType extends DefValType {

    public static PrimValType BOOL = new PrimValType(Kind.BOOL);
    public static PrimValType S8 = new PrimValType(Kind.S8);
    public static PrimValType U8 = new PrimValType(Kind.U8);
    public static PrimValType S16 = new PrimValType(Kind.S16);
    public static PrimValType U16 = new PrimValType(Kind.U16);
    public static PrimValType S32 = new PrimValType(Kind.S32);
    public static PrimValType U32 = new PrimValType(Kind.U32);
    public static PrimValType S64 = new PrimValType(Kind.S64);
    public static PrimValType U64 = new PrimValType(Kind.U64);
    public static PrimValType F32 = new PrimValType(Kind.F32);
    public static PrimValType F64 = new PrimValType(Kind.F64);
    public static PrimValType CHAR = new PrimValType(Kind.CHAR);
    public static PrimValType STRING = new PrimValType(Kind.STRING);
    public static PrimValType ERROR_CONTEXT = new PrimValType(Kind.ERROR_CONTEXT);

    private PrimValType(Kind kind) {
        super(kind);
    }

    @Override
    public int alignment(TypeResolver typeResolver, PointerType ptrType) {
        switch (kind()) {
            case BOOL:
            case S8:
            case U8:
                return 1;
            case S16:
            case U16:
                return 2;
            case S32:
            case U32:
                return 4;
            case S64:
            case U64:
                return 8;
            case F32:
                return 4;
            case F64:
                return 8;
            case CHAR:
                return 4;
            case STRING:
                return ptrType.size();
            case ERROR_CONTEXT:
                return 4;
            default:
                throw new IllegalStateException("unhandled kind " + kind());
        }
    }

    @Override
    public int elementSize(TypeResolver typeResolver, PointerType ptrType) {
        switch (kind()) {
            case BOOL:
            case S8:
            case U8:
                return 1;
            case S16:
            case U16:
                return 2;
            case S32:
            case U32:
                return 4;
            case S64:
            case U64:
                return 8;
            case F32:
                return 4;
            case F64:
                return 8;
            case CHAR:
                return 4;
            case STRING:
                return 2 * ptrType.size();
            case ERROR_CONTEXT:
                return 4;
            default:
                throw new IllegalStateException("unhandled kind " + kind());
        }
    }

    @Override
    public List<ValType> flatten(TypeResolver typeResolver, PointerType ptrType) {
        switch (kind()) {
            case BOOL:
            case S8:
            case U8:
            case S16:
            case U16:
            case S32:
            case U32:
            case CHAR:
            case ERROR_CONTEXT:
                return List.of(ValType.I32);
            case S64:
            case U64:
                return List.of(ValType.I64);
            case F32:
                return List.of(ValType.F32);
            case F64:
                return List.of(ValType.F64);
            case STRING:
                return List.of(ptrType.coreValType(), ptrType.coreValType());
            default:
                throw new IllegalStateException("unhandled kind " + kind());
        }
    }
}
