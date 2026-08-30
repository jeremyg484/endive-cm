package run.endive.cm.bindgen;

import java.util.List;
import java.util.Objects;

/** A world's imports and exports, as much of one as the generator can yet read. */
final class WitWorld {

    private final String name;
    private final String qualifiedName;
    private final List<WitFunction> imports;
    private final List<WitFunction> exports;

    WitWorld(
            String name,
            String qualifiedName,
            List<WitFunction> imports,
            List<WitFunction> exports) {
        this.name = Objects.requireNonNull(name, "name");
        this.qualifiedName = Objects.requireNonNull(qualifiedName, "qualifiedName");
        this.imports = List.copyOf(imports);
        this.exports = List.copyOf(exports);
    }

    /** The world's WIT name, such as {@code hello-world}. */
    String name() {
        return name;
    }

    /** The world's fully qualified id, such as {@code my:project/hello-world}. */
    String qualifiedName() {
        return qualifiedName;
    }

    List<WitFunction> imports() {
        return imports;
    }

    List<WitFunction> exports() {
        return exports;
    }
}
