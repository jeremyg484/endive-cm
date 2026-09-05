package run.endive.cm.bindgen;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.Comparator;
import javax.annotation.processing.Generated;

/**
 * One compilation unit being built, which imports a type as a consequence of naming it.
 *
 * <p>Naming a type through {@link #use} rather than writing it out is what keeps the import list
 * right, since an import is added exactly where the type is needed.
 */
final class JavaUnit {

    private final CompilationUnit unit = new CompilationUnit();
    private final String packageName;
    private final String generatedBy;

    JavaUnit(String packageName, String generatedBy) {
        this.packageName = packageName;
        this.generatedBy = generatedBy;
        if (!packageName.isEmpty()) {
            unit.setPackageDeclaration(packageName);
        }
    }

    /** The package being generated into, so a reference knows whether it has to qualify. */
    String packageName() {
        return packageName;
    }

    /** Imports {@code qualifiedName} and names the type by its simple name. */
    ClassOrInterfaceType use(String qualifiedName) {
        unit.addImport(qualifiedName);
        return Ast.type(simpleName(qualifiedName));
    }

    /** Names a type nested in {@code qualifiedName}, which is what gets imported. */
    ClassOrInterfaceType use(String qualifiedName, String nested) {
        return new ClassOrInterfaceType(use(qualifiedName), nested);
    }

    /** The same as {@link #use}, as the expression a static member is reached through. */
    Expression useName(String qualifiedName) {
        unit.addImport(qualifiedName);
        return new NameExpr(simpleName(qualifiedName));
    }

    ClassOrInterfaceDeclaration addClass(String name) {
        return generated(unit.addClass(name).setPublic(true).setFinal(true));
    }

    ClassOrInterfaceDeclaration addInterface(String name) {
        return generated(unit.addInterface(name).setPublic(true));
    }

    EnumDeclaration addEnum(String name) {
        return generated(unit.addEnum(name).setPublic(true));
    }

    GeneratedSource finish(String simpleName) {
        // Sorted so that regenerating an unchanged world produces an unchanged file.
        unit.getImports().sort(Comparator.comparing(ImportDeclaration::getNameAsString));
        String qualified = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        return new GeneratedSource(qualified, unit);
    }

    private <T extends NodeWithAnnotations<?>> T generated(T declaration) {
        declaration.addSingleMemberAnnotation(Generated.class, new StringLiteralExpr(generatedBy));
        return declaration;
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }
}
