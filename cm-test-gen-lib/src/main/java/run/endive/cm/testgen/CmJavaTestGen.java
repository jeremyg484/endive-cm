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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        cu.addImport("java.util.LinkedHashMap");
        cu.addImport("java.util.Map");
        cu.addImport("org.junit.jupiter.api.Disabled");
        cu.addImport("org.junit.jupiter.api.Test");
        cu.addImport("org.junit.jupiter.api.DisplayName");
        cu.addImport("org.junit.jupiter.api.MethodOrderer");
        cu.addImport("org.junit.jupiter.api.TestMethodOrder");
        cu.addImport("org.junit.jupiter.api.Order");
        cu.addImport("org.junit.jupiter.api.Assertions.assertArrayEquals", true, false);
        cu.addImport("org.junit.jupiter.api.Assertions.assertNotNull", true, false);
        cu.addImport("org.junit.jupiter.api.Assertions.assertThrows", true, false);
        cu.addImport("org.junit.jupiter.api.Assertions.assertTrue", true, false);
        cu.addImport("org.junit.jupiter.api.Assertions.fail", true, false);
        cu.addImport("run.endive.wasm.WasmEngineException");
        cu.addImport("run.endive.cm.parser.ComponentParser");
        cu.addImport("run.endive.cm.runtime.ComponentLinker");
        cu.addImport("run.endive.cm.abi.CharValue");
        cu.addImport("run.endive.cm.runtime.ComponentInstance");
        cu.addImport("run.endive.cm.runtime.LinkageException");
        cu.addImport("run.endive.cm.runtime.SpecTestImports");
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

        addCurrentInstanceField(classDecl);
        addRegisteredInstancesField(classDecl);
        addLoadBytesMethod(classDecl, wasmClasspath);
        addImportsMethod(classDecl);
        addInstantiateMethod(classDecl);
        addCurrentInstanceMethod(classDecl);

        // A wast script names its component definitions and instantiates them separately, so
        // resolve each `module_instance` back to the file its definition was compiled to.
        var definitionFilenames = new HashMap<String, String>();
        var commands = wast.commands();
        for (var i = 0; i < commands.size(); i++) {
            var command = commands.get(i);
            if (command.commandType() == CmCommandType.MODULE_DEFINITION
                    && command.name() != null) {
                definitionFilenames.put(command.name(), command.filename());
            }
            addTestMethod(classDecl, testName, command, i, definitionFilenames);
        }

        return cu;
    }

    /**
     * The instance later commands act on.
     *
     * <p>A wast script is a sequence of commands sharing state: a {@code component} command
     * instantiates, and every {@code assert_return} or {@code assert_trap} after it invokes
     * <em>that</em> instance until the next one replaces it. Handle tables, resource
     * representations and linear memory all persist across those commands, and several tests
     * turn on exactly that — allocating in one command and freeing in the next. Each command
     * becomes its own ordered test method, so the instance has to outlive the method that
     * created it, which means static state.
     */
    private void addCurrentInstanceField(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl) {
        classDecl
                .addField(
                        "ComponentInstance",
                        "currentInstance",
                        Modifier.Keyword.PRIVATE,
                        Modifier.Keyword.STATIC)
                .setJavadocComment(
                        "The instance created by the most recent component command, which the"
                                + " commands after it invoke.");
    }

    /**
     * The instances a wast script names, which later commands may import.
     *
     * <p>Naming a component command — {@code (component $foo ...)} — both instantiates it and
     * binds the result to that name for the rest of the script, so a later {@code (import "foo"
     * (instance ...))} resolves to this very instance. That matters beyond convenience: the
     * importer is type-checked against what the named instance actually exports, and for
     * resource types it is checked against the identity that instantiation created.
     */
    private void addRegisteredInstancesField(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl) {
        classDecl
                .addFieldWithInitializer(
                        "Map<String, Object>",
                        "registeredInstances",
                        com.github.javaparser.StaticJavaParser.parseExpression(
                                "new LinkedHashMap<>()"),
                        Modifier.Keyword.PRIVATE,
                        Modifier.Keyword.STATIC,
                        Modifier.Keyword.FINAL)
                .setJavadocComment(
                        "Instances bound to a name by an earlier command, available to later"
                                + " commands as imports.");
    }

    private void addImportsMethod(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl) {
        var method =
                classDecl.addMethod("imports", Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);
        method.setType("Map<String, Object>");

        var body = new BlockStmt();
        body.addStatement(
                "Map<String, Object> imports = new LinkedHashMap<>(SpecTestImports.build());");
        // Anything the script itself defined is the real article, so it wins over the stand-in
        // the harness supplies for hosts the script expects but does not build.
        body.addStatement("imports.putAll(registeredInstances);");
        body.addStatement("return imports;");
        method.setBody(body);
    }

    private void addInstantiateMethod(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl) {
        var method =
                classDecl.addMethod(
                        "instantiate", Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);
        method.setType("ComponentInstance");
        method.addParameter("String", "resourcePath");

        var body = new BlockStmt();
        body.addStatement("byte[] bytes = loadBytes(resourcePath);");
        body.addStatement("ComponentValidate.validate(new ByteArrayInputStream(bytes));");
        body.addStatement("var parser = ComponentParser.builder().build();");
        body.addStatement("var component = parser.parse(() -> new ByteArrayInputStream(bytes));");
        body.addStatement("assertNotNull(component);");
        body.addStatement("var linker = ComponentLinker.builder().build();");
        body.addStatement("return linker.instantiate(component, imports());");
        method.setBody(body);
    }

    private void addCurrentInstanceMethod(
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl) {
        var method =
                classDecl.addMethod(
                        "currentInstance", Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);
        method.setType("ComponentInstance");

        var body = new BlockStmt();
        body.addStatement(
                "assertNotNull(currentInstance, \"no instance is current - the component command"
                        + " before this one did not run or did not succeed\");");
        body.addStatement("return currentInstance;");
        method.setBody(body);
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
            Map<String, String> definitionFilenames) {
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
            case ASSERT_INVALID:
                generateAssertInvalidTest(body, command);
                break;
            case ASSERT_RETURN:
                generateAssertReturnTest(body, command);
                break;
            case ASSERT_TRAP:
                generateAssertTrapTest(body, command);
                break;
            case ASSERT_UNINSTANTIABLE:
            case ASSERT_UNLINKABLE:
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
                generateModuleInstanceTest(body, command, definitionFilenames);
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

    private void generateAssertTrapTest(BlockStmt body, CmCommand command) {
        requireInvokeAction(command);
        body.addStatement("ComponentInstance instance = currentInstance();");
        body.addStatement(
                "assertThrows(WasmEngineException.class, () -> instance.export(\""
                        + command.action().field()
                        + "\").apply("
                        + command.action().emitArgs()
                        + "));");
    }

    private void requireInvokeAction(CmCommand command) {
        if (command.action() == null) {
            throw new IllegalStateException(
                    command.type() + " at line " + command.line() + " has no action");
        }
        if (!"invoke".equals(command.action().type())) {
            throw new UnsupportedOperationException(
                    command.type()
                            + " at line "
                            + command.line()
                            + " has unsupported action type: "
                            + command.action().type());
        }
        if (command.action().field() == null) {
            throw new IllegalStateException(
                    command.type() + " invoke at line " + command.line() + " has no field");
        }
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
                "var ex = assertThrows(LinkageException.class, () -> linker.instantiate(component,"
                        + " imports()));");
        body.addStatement(
                "assertTrue(ex.getMessage().contains(\""
                        + command.text()
                        + "\"), \"Expected exception message to contain '"
                        + command.text()
                        + "', but"
                        + " was: '\"+ex.getMessage()+\"'\");");
    }

    private void generateModuleTest(BlockStmt body, CmCommand command) {
        if (command.filename() == null) {
            throw new IllegalStateException(
                    "module command at line " + command.line() + " has no filename");
        }
        generateInstantiation(body, command.filename(), command.name());
    }

    /**
     * Instantiates a named {@code module_definition}, which becomes the instance the following
     * commands invoke. The same definition may be instantiated more than once, each time
     * yielding a fresh instance with its own handle table.
     */
    private void generateModuleInstanceTest(
            BlockStmt body, CmCommand command, Map<String, String> definitionFilenames) {
        if (command.module() == null) {
            throw new IllegalStateException(
                    "module_instance at line " + command.line() + " names no definition");
        }
        String filename = definitionFilenames.get(command.module());
        if (filename == null) {
            throw new IllegalStateException(
                    "module_instance at line "
                            + command.line()
                            + " names definition '"
                            + command.module()
                            + "', which was not defined earlier in this script");
        }
        generateInstantiation(body, filename, command.instance());
    }

    /**
     * @param name the name this command binds the new instance to, or {@code null} if it is
     *     anonymous and only becomes current
     */
    private void generateInstantiation(BlockStmt body, String filename, String name) {
        // Cleared first so that a failure here leaves neither an instance current nor a stale
        // one bound to this name, rather than leaving the previous command's in place for the
        // commands that follow to pick up.
        body.addStatement("currentInstance = null;");
        if (name != null) {
            body.addStatement("registeredInstances.remove(\"" + name + "\");");
        }
        body.addStatement("currentInstance = instantiate(\"" + filename + "\");");
        body.addStatement("assertNotNull(currentInstance);");
        if (name != null) {
            body.addStatement("registeredInstances.put(\"" + name + "\", currentInstance);");
        }
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

    private void generateAssertReturnTest(BlockStmt body, CmCommand command) {
        requireInvokeAction(command);
        body.addStatement(
                "Object[] result = currentInstance().export(\""
                        + command.action().field()
                        + "\").apply("
                        + command.action().emitArgs()
                        + ");");
        body.addStatement(
                "assertArrayEquals(new Object[]{" + command.emitExpected() + "}, result);");
    }
}
