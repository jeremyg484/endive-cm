package run.endive.cm.bindgen;

import java.util.Objects;
import run.endive.cm.types.FuncType;

/** One function a world imports or exports, under the name the world gives it. */
final class WitFunction {

    private final String name;
    private final FuncType type;

    WitFunction(String name, FuncType type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    String name() {
        return name;
    }

    FuncType type() {
        return type;
    }
}
