package run.endive.cm.bindgen;

import java.util.Objects;
import run.endive.cm.types.FuncType;

/** One function a world imports or exports, under the name the world gives it. */
final class WitFunction {

    private final String name;
    private final FuncType type;
    private final WitScope scope;

    WitFunction(String name, FuncType type, WitScope scope) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    String name() {
        return name;
    }

    FuncType type() {
        return type;
    }

    /** The index space this function's value types resolve against. */
    WitScope scope() {
        return scope;
    }
}
