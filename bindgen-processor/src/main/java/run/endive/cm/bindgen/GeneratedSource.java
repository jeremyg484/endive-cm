package run.endive.cm.bindgen;

import com.github.javaparser.ast.CompilationUnit;
import java.util.Objects;

/** One Java source file a world generates, under the name it will be written as. */
final class GeneratedSource {

    private final String qualifiedName;
    private final CompilationUnit unit;

    GeneratedSource(String qualifiedName, CompilationUnit unit) {
        this.qualifiedName = Objects.requireNonNull(qualifiedName, "qualifiedName");
        this.unit = Objects.requireNonNull(unit, "unit");
    }

    String qualifiedName() {
        return qualifiedName;
    }

    String contents() {
        return unit.toString();
    }
}
