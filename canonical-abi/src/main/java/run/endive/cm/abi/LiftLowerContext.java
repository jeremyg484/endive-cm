package run.endive.cm.abi;

import static java.util.Objects.requireNonNull;

import run.endive.cm.types.PointerType;
import run.endive.cm.types.TypeResolver;
import run.endive.runtime.Memory;

public final class LiftLowerContext {

    private final Memory memory;
    private final PointerType ptrType;
    private final StringEncoding stringEncoding;
    private final TypeResolver typeResolver;
    private final PostReturn postReturn;
    private final Realloc realloc;
    private final boolean async;
    private final Callback callback;

    private LiftLowerContext(
            Memory memory,
            PointerType ptrType,
            StringEncoding stringEncoding,
            TypeResolver typeResolver,
            PostReturn postReturn,
            Realloc realloc,
            boolean async,
            Callback callback) {
        this.memory = memory;
        this.ptrType = ptrType == null ? PointerType.I32 : ptrType;
        this.stringEncoding = stringEncoding == null ? StringEncoding.UTF8 : stringEncoding;
        this.typeResolver = requireNonNull(typeResolver, "typeResolver");
        this.postReturn = postReturn;
        this.realloc = realloc;
        this.async = async;
        this.callback = callback;
    }

    public Memory memory() {
        return memory;
    }

    public PointerType ptrType() {
        return ptrType;
    }

    public StringEncoding stringEncoding() {
        return stringEncoding;
    }

    public TypeResolver typeResolver() {
        return typeResolver;
    }

    public PostReturn postReturn() {
        return postReturn;
    }

    public Realloc realloc() {
        return realloc;
    }

    public boolean isAsync() {
        return async;
    }

    public Callback callback() {
        return callback;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Memory memory;
        private PointerType ptrType;
        private StringEncoding stringEncoding;
        private TypeResolver typeResolver;
        private PostReturn postReturn;
        private Realloc realloc;
        private boolean async;
        private Callback callback;

        private Builder() {}

        public Builder withMemory(Memory memory) {
            this.memory = memory;
            return this;
        }

        public Builder withPtrType(PointerType ptrType) {
            this.ptrType = ptrType;
            return this;
        }

        public Builder withStringEncoding(StringEncoding stringEncoding) {
            this.stringEncoding = stringEncoding;
            return this;
        }

        public Builder withTypeResolver(TypeResolver typeResolver) {
            this.typeResolver = typeResolver;
            return this;
        }

        public Builder withPostReturn(PostReturn postReturn) {
            this.postReturn = postReturn;
            return this;
        }

        public Builder withRealloc(Realloc realloc) {
            this.realloc = realloc;
            return this;
        }

        public Builder withAsync(boolean async) {
            this.async = async;
            return this;
        }

        public Builder withCallback(Callback callback) {
            this.callback = callback;
            return this;
        }

        public LiftLowerContext build() {
            return new LiftLowerContext(
                    memory,
                    ptrType,
                    stringEncoding,
                    typeResolver,
                    postReturn,
                    realloc,
                    async,
                    callback);
        }
    }
}
