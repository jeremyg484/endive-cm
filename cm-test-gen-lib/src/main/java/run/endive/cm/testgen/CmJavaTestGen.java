package run.endive.cm.testgen;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import run.endive.cm.testgen.wast.CmCommand;
import run.endive.cm.testgen.wast.CmCommandType;
import run.endive.cm.testgen.wast.CmWast;

public final class CmJavaTestGen {

    private final List<String> excludedTests;

    public CmJavaTestGen(List<String> excludedTests) {
        this.excludedTests = excludedTests;
    }

    public CompilationUnit generate(String name, CmWast wast, String wasmClasspath) {
        var cu = new CompilationUnit("run.endive.cm.test.gen");
        var testName =
                "CmSpec" + CmStringUtils.capitalize(CmStringUtils.escapedCamelCase(name)) + "Test";

        cu.addImport("java.io.ByteArrayInputStream");
        cu.addImport("java.io.InputStream");
        cu.addImport("org.junit.jupiter.api.Disabled");
        cu.addImport("org.junit.jupiter.api.Test");
        cu.addImport("org.junit.jupiter.api.DisplayName");
        cu.addImport("org.junit.jupiter.api.MethodOrderer");
        cu.addImport("org.junit.jupiter.api.TestMethodOrder");
        cu.addImport("org.junit.jupiter.api.Order");
        cu.addImport("org.junit.jupiter.api.Assertions.assertArrayEquals", true, false);
        cu.addImport("org.junit.jupiter.api.Assertions.assertNotNull", true, false);
        cu.addImport("org.junit.jupiter.api.Assertions.assertThrows", true, false);
        cu.addImport("org.junit.jupiter.api.Assertions.fail", true, false);
        cu.addImport("run.endive.wasm.WasmEngineException");
        cu.addImport("run.endive.cm.parser.ComponentParser");
        cu.addImport("run.endive.cm.runtime.ComponentLinker");
        cu.addImport("run.endive.cm.runtime.ComponentInstance");
        cu.addImport("run.endive.cm.runtime.LinkageException");
        cu.addImport("run.endive.cm.tools.ComponentValidate");
        cu.addImport("run.endive.cm.tools.ComponentValidateException");

        var classDecl = cu.addClass(testName, Modifier.Keyword.PUBLIC);
        classDecl.addAnnotation(
                new SingleMemberAnnotationExpr(
                        new Name("TestMethodOrder"),
                        new FieldAccessExpr(
                                new FieldAccessExpr(
                                        new NameExpr("MethodOrderer"), "OrderAnnotation"),
                                "class")));

        addLoadBytesMethod(classDecl, wasmClasspath);

        String lastComponentFilename = null;
        var commands = wast.commands();
        for (var i = 0; i < commands.size(); i++) {
            var command = commands.get(i);
            var commandType = command.commandType();
            if (commandType == CmCommandType.MODULE
                    || commandType == CmCommandType.MODULE_DEFINITION
                    || commandType == CmCommandType.COMPONENT) {
                lastComponentFilename = command.filename();
            }
            addTestMethod(classDecl, testName, command, i, lastComponentFilename);
        }

        return cu;
    }

    private void addLoadBytesMethod(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl,
            String wasmClasspath) {
        var method =
                classDecl.addMethod("loadBytes", Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);
        method.setType("byte[]");
        method.addParameter("String", "resourcePath");

        var body = new BlockStmt();
        body.addStatement(
                "InputStream is = "
                        + classDecl.getNameAsString()
                        + ".class.getResourceAsStream(\""
                        + wasmClasspath
                        + "/\" + resourcePath);");
        body.addStatement("assertNotNull(is, \"Resource not found: \" + resourcePath);");
        body.addStatement(
                com.github.javaparser.StaticJavaParser.parseStatement(
                        "try { return is.readAllBytes(); }"
                                + " catch (java.io.IOException e) {"
                                + " throw new java.io.UncheckedIOException(e); }"));
        method.setBody(body);
    }

    private void addTestMethod(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl,
            String testName,
            CmCommand command,
            int index,
            String lastComponentFilename) {
        var methodName = "test" + index;
        var method = classDecl.addMethod(methodName, Modifier.Keyword.PUBLIC);
        method.addAnnotation("Test");
        method.addAnnotation(
                new SingleMemberAnnotationExpr(
                        new Name("Order"), new IntegerLiteralExpr(String.valueOf(index))));

        var displayName = command.type() + " line:" + command.line();
        if (command.filename() != null) {
            displayName += " @ " + command.filename();
        }
        method.addAnnotation(
                new SingleMemberAnnotationExpr(
                        new Name("DisplayName"), new StringLiteralExpr(displayName)));

        var fullTestName = testName + "." + methodName;
        if (excludedTests.contains(fullTestName)) {
            method.addAnnotation("Disabled");
        }

        var body = new BlockStmt();
        var commandType = command.commandType();

        switch (commandType) {
            case MODULE:
            case COMPONENT:
                generateModuleTest(body, command);
                break;
            case MODULE_DEFINITION:
                generateModuleDefinitionTest(body, command);
                break;
            case ASSERT_MALFORMED:
            case ASSERT_UNLINKABLE:
            case ASSERT_INVALID:
                generateAssertInvalidTest(body, command);
                break;
            case ASSERT_RETURN:
                generateAssertReturnTest(body, command, lastComponentFilename);
                break;
            case ASSERT_TRAP:
                generateAssertTrapTest(body, command, lastComponentFilename);
                break;
            case ASSERT_UNINSTANTIABLE:
                generateAssertUninstantiableTest(body, command);
                break;
            case ACTION:
                generateUnsupportedTest(
                        body, "action at line " + command.line() + " not yet supported");
                break;
            case REGISTER:
                generateUnsupportedTest(
                        body, "register at line " + command.line() + " not yet supported");
                break;
            case MODULE_INSTANCE:
                generateUnsupportedTest(
                        body, "module_instance at line " + command.line() + " not yet supported");
                break;
            default:
                throw new UnsupportedOperationException(
                        "Unknown command type " + commandType + " at line " + command.line());
        }

        method.setBody(body);
    }

    private void generateUnsupportedTest(BlockStmt body, String failureMessage) {
        body.addStatement("fail(\"" + failureMessage + "\");");
    }

    private void generateAssertTrapTest(
            BlockStmt body, CmCommand command, String lastComponentFilename) {
        if (command.action() == null) {
            throw new IllegalStateException(
                    "assert_return at line " + command.line() + " has no action");
        }
        if (!"invoke".equals(command.action().type())) {
            throw new UnsupportedOperationException(
                    "assert_return at line "
                            + command.line()
                            + " has unsupported action type: "
                            + command.action().type());
        }
        if (command.action().field() == null) {
            throw new IllegalStateException(
                    "assert_return invoke at line " + command.line() + " has no field");
        }
        if (lastComponentFilename == null) {
            throw new IllegalStateException(
                    "assert_return at line " + command.line() + " has no preceding component");
        }
        body.addStatement("byte[] bytes = loadBytes(\"" + lastComponentFilename + "\");");
        body.addStatement("var parser = ComponentParser.builder().build();");
        body.addStatement("var component = parser.parse(() -> new ByteArrayInputStream(bytes));");
        body.addStatement("var linker = ComponentLinker.builder().build();");
        body.addStatement("ComponentInstance instance = linker.instantiate(component);");
        body.addStatement(
                "assertThrows(WasmEngineException.class, () -> instance.export(\""
                        + command.action().field()
                        + "\").apply("
                        + command.action().emitArgs()
                        + "));");
    }

    private void generateAssertUninstantiableTest(BlockStmt body, CmCommand command) {
        if (command.filename() == null) {
            throw new IllegalStateException(
                    "module command at line " + command.line() + " has no filename");
        }
        body.addStatement("byte[] bytes = loadBytes(\"" + command.filename() + "\");");
        body.addStatement("ComponentValidate.validate(new ByteArrayInputStream(bytes));");
        body.addStatement("var parser = ComponentParser.builder().build();");
        body.addStatement("var component = parser.parse(() -> new ByteArrayInputStream(bytes));");
        body.addStatement("assertNotNull(component);");
        body.addStatement("var linker = ComponentLinker.builder().build();");
        body.addStatement(
                "assertThrows(LinkageException.class, () -> linker.instantiate(component));");
    }

    private void generateModuleTest(BlockStmt body, CmCommand command) {
        if (command.filename() == null) {
            throw new IllegalStateException(
                    "module command at line " + command.line() + " has no filename");
        }
        body.addStatement("byte[] bytes = loadBytes(\"" + command.filename() + "\");");
        body.addStatement("ComponentValidate.validate(new ByteArrayInputStream(bytes));");
        body.addStatement("var parser = ComponentParser.builder().build();");
        body.addStatement("var component = parser.parse(() -> new ByteArrayInputStream(bytes));");
        body.addStatement("assertNotNull(component);");
        body.addStatement(
                "var linker = ComponentLinker.builder().withGenerateImports(true).build();");
        body.addStatement("ComponentInstance instance = linker.instantiate(component);");
        body.addStatement("assertNotNull(instance);");
    }

    private void generateModuleDefinitionTest(BlockStmt body, CmCommand command) {
        if (command.filename() == null) {
            throw new IllegalStateException(
                    "module command at line " + command.line() + " has no filename");
        }
        body.addStatement("byte[] bytes = loadBytes(\"" + command.filename() + "\");");
        body.addStatement("ComponentValidate.validate(new ByteArrayInputStream(bytes));");
        body.addStatement("var parser = ComponentParser.builder().build();");
        body.addStatement("var component = parser.parse(() -> new ByteArrayInputStream(bytes));");
        body.addStatement("assertNotNull(component);");
    }

    private void generateAssertInvalidTest(BlockStmt body, CmCommand command) {
        if (command.filename() == null) {
            throw new IllegalStateException(
                    command.type() + " command at line " + command.line() + " has no filename");
        }
        body.addStatement("byte[] bytes = loadBytes(\"" + command.filename() + "\");");
        body.addStatement(
                "assertThrows(ComponentValidateException.class, () ->"
                        + " ComponentValidate.validate("
                        + "new ByteArrayInputStream(bytes)));");
    }

    private void generateAssertReturnTest(
            BlockStmt body, CmCommand command, String lastComponentFilename) {
        if (command.action() == null) {
            throw new IllegalStateException(
                    "assert_return at line " + command.line() + " has no action");
        }
        if (!"invoke".equals(command.action().type())) {
            throw new UnsupportedOperationException(
                    "assert_return at line "
                            + command.line()
                            + " has unsupported action type: "
                            + command.action().type());
        }
        if (command.action().field() == null) {
            throw new IllegalStateException(
                    "assert_return invoke at line " + command.line() + " has no field");
        }
        if (lastComponentFilename == null) {
            throw new IllegalStateException(
                    "assert_return at line " + command.line() + " has no preceding component");
        }
        body.addStatement("byte[] bytes = loadBytes(\"" + lastComponentFilename + "\");");
        body.addStatement("var parser = ComponentParser.builder().build();");
        body.addStatement("var component = parser.parse(() -> new ByteArrayInputStream(bytes));");
        body.addStatement("var linker = ComponentLinker.builder().build();");
        body.addStatement("ComponentInstance instance = linker.instantiate(component);");
        body.addStatement(
                "Object[] result = instance.export(\""
                        + command.action().field()
                        + "\").apply("
                        + command.action().emitArgs()
                        + ");");
        body.addStatement(
                "assertArrayEquals(new Object[]{" + command.emitExpected() + "}, result);");
    }
}
