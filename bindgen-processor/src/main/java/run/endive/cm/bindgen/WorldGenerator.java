package run.endive.cm.bindgen;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.Generated;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;

/**
 * Generates the bindings for one world, as a class holding an interface the embedder implements for
 * the world's imports and a typed method for each of its exports.
 */
final class WorldGenerator {

    private WorldGenerator() {}

    static CompilationUnit generate(WitWorld world, String packageName, String generatedBy) {
        String className = Names.type(world.name());

        var unit = new CompilationUnit();
        if (!packageName.isEmpty()) {
            unit.setPackageDeclaration(packageName);
        }
        addImports(unit, world);

        var type =
                unit.addClass(className)
                        .setPublic(true)
                        .setFinal(true)
                        .addSingleMemberAnnotation(
                                Generated.class, new StringLiteralExpr(generatedBy));
        type.setJavadocComment("Bindings for the WIT world {@code " + world.qualifiedName() + "}.");

        for (WitFunction imported : world.imports()) {
            type.addMember(StaticJavaParser.parseBodyDeclaration(funcTypeField("", imported)));
        }
        for (WitInterface imported : world.importedInterfaces()) {
            for (WitFunction function : imported.functions()) {
                type.addMember(
                        StaticJavaParser.parseBodyDeclaration(
                                funcTypeField(imported.simpleName(), function)));
            }
        }

        type.addMember(StaticJavaParser.parseBodyDeclaration(importsInterface(world)));

        for (WitInterface imported : world.importedInterfaces()) {
            type.addMember(StaticJavaParser.parseBodyDeclaration(hostInterface(imported)));
        }

        type.addMember(
                StaticJavaParser.parseBodyDeclaration("private final ComponentInstance instance;"));
        for (WitFunction exported : world.exports()) {
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(
                            "private final ComponentFunction "
                                    + Names.member(exported.name())
                                    + ";"));
        }

        type.addMember(StaticJavaParser.parseBodyDeclaration(constructor(world, className)));
        type.addMember(StaticJavaParser.parseBodyDeclaration(instantiate(world, className)));
        type.addMember(
                StaticJavaParser.parseBodyDeclaration(
                        "/** The instance these bindings call into. */\n"
                                + "public ComponentInstance instance() {\n"
                                + "    return instance;\n"
                                + "}"));

        for (WitFunction exported : world.exports()) {
            type.addMember(StaticJavaParser.parseBodyDeclaration(exportMethod(exported)));
        }

        // Sorted so that regenerating an unchanged world produces an unchanged file.
        unit.getImports().sort(Comparator.comparing(ImportDeclaration::getNameAsString));
        return unit;
    }

    private static void addImports(CompilationUnit unit, WitWorld world) {
        unit.addImport("java.util.LinkedHashMap");
        unit.addImport("java.util.Map");
        unit.addImport("run.endive.cm.runtime.ComponentInstance");
        unit.addImport("run.endive.cm.runtime.ComponentLinker");
        unit.addImport("run.endive.cm.runtime.ComponentStore");
        unit.addImport("run.endive.cm.types.FuncType");
        unit.addImport("run.endive.cm.types.WasmComponent");

        // Only what the generated body actually names, so that nothing is imported unused.
        if (allImports(world).anyMatch(f -> f.type().hasResult() || hasParams(f))) {
            unit.addImport("run.endive.cm.types.PrimValType");
            unit.addImport("run.endive.cm.types.ValType");
        }
        if (allImports(world).anyMatch(WorldGenerator::hasParams)) {
            unit.addImport("run.endive.cm.types.LabelValType");
        }
        if (!world.importedInterfaces().isEmpty()) {
            unit.addImport("run.endive.cm.runtime.HostInstance");
        }
        if (!world.imports().isEmpty()) {
            unit.addImport("run.endive.cm.runtime.HostFunction");
        }
        if (!world.exports().isEmpty()) {
            unit.addImport("run.endive.cm.runtime.ComponentFunction");
        }
        if (world.exports().stream().anyMatch(f -> f.type().hasResult() || hasParams(f))) {
            unit.addImport("run.endive.cm.runtime.PrimitiveHostTypeDescriptor");
        }
        if (world.exports().stream().anyMatch(f -> !f.type().hasResult())) {
            unit.addImport("run.endive.cm.runtime.VoidHostTypeDescriptor");
        }
        if (usesCharValue(world)) {
            unit.addImport("run.endive.cm.abi.CharValue");
        }
    }

    private static boolean hasParams(WitFunction function) {
        return !function.type().params().isEmpty();
    }

    /** Everything the world imports, however it is reached. */
    private static Stream<WitFunction> allImports(WitWorld world) {
        return Stream.concat(
                world.imports().stream(),
                world.importedInterfaces().stream().flatMap(i -> i.functions().stream()));
    }

    private static boolean usesCharValue(WitWorld world) {
        List<WitFunction> all = new ArrayList<>(world.imports());
        all.addAll(world.exports());
        return all.stream()
                .anyMatch(
                        f ->
                                WitTypes.needsCharValue(f.type().result())
                                        || f.type().params().stream()
                                                .anyMatch(
                                                        p -> WitTypes.needsCharValue(p.valType())));
    }

    /** The function type a host import is registered with, rebuilt as source. */
    private static String funcTypeField(String prefix, WitFunction function) {
        StringBuilder builder = new StringBuilder("FuncType.builder()");
        for (LabelValType param : function.type().params()) {
            builder.append("\n    .addParam(LabelValType.builder().withLabel(\"")
                    .append(param.label())
                    .append("\").withValType(")
                    .append(WitTypes.valTypeSource(param.valType()))
                    .append(").build())");
        }
        if (function.type().hasResult()) {
            builder.append("\n    .withResult(")
                    .append(WitTypes.valTypeSource(function.type().result()))
                    .append(")");
        }
        builder.append("\n    .build()");

        return "private static final FuncType "
                + constantName(prefix, function.name())
                + " =\n    "
                + builder
                + ";";
    }

    private static String importsInterface(WitWorld world) {
        StringBuilder body = new StringBuilder();
        body.append("/** The world's imports, which the embedder implements. */\n");
        body.append("public interface Imports {\n");
        for (WitFunction imported : world.imports()) {
            body.append("    ")
                    .append(returnType(imported.type()))
                    .append(' ')
                    .append(Names.member(imported.name()))
                    .append('(')
                    .append(parameterList(imported.type()))
                    .append(");\n");
        }
        for (WitInterface imported : world.importedInterfaces()) {
            body.append("\n    /** The imported interface {@code ")
                    .append(imported.name())
                    .append("}. */\n    ")
                    .append(Names.type(imported.simpleName()))
                    .append(' ')
                    .append(Names.member(imported.simpleName()))
                    .append("();\n");
        }
        body.append("}");
        return body.toString();
    }

    /** One Java interface per imported WIT interface, implemented by the embedder. */
    private static String hostInterface(WitInterface imported) {
        StringBuilder body = new StringBuilder();
        body.append("/** The imported interface {@code ").append(imported.name()).append("}. */\n");
        body.append("public interface ").append(Names.type(imported.simpleName())).append(" {\n");
        for (WitFunction function : imported.functions()) {
            body.append("    ")
                    .append(returnType(function.type()))
                    .append(' ')
                    .append(Names.member(function.name()))
                    .append('(')
                    .append(parameterList(function.type()))
                    .append(");\n");
        }
        body.append("}");
        return body.toString();
    }

    private static String constructor(WitWorld world, String className) {
        StringBuilder body = new StringBuilder();
        body.append("private ").append(className).append("(ComponentInstance instance) {\n");
        body.append("    this.instance = instance;\n");
        for (WitFunction exported : world.exports()) {
            body.append("    this.")
                    .append(Names.member(exported.name()))
                    .append(" = instance.export(\"")
                    .append(exported.name())
                    .append("\").typed(")
                    .append(descriptors(exported.type()))
                    .append(");\n");
        }
        body.append("}");
        return body.toString();
    }

    private static String instantiate(WitWorld world, String className) {
        StringBuilder body = new StringBuilder();
        body.append(
                "/** Instantiates {@code component}, satisfying its imports with {@code imports}."
                        + " */\n");
        body.append("public static ")
                .append(className)
                .append(
                        " instantiate(ComponentStore store, WasmComponent component, Imports"
                                + " imports) {\n");
        body.append("    Map<String, Object> values = new LinkedHashMap<>();\n");
        for (WitFunction imported : world.imports()) {
            body.append("    values.put(\"")
                    .append(imported.name())
                    .append("\", HostFunction.of(store, ")
                    .append(constantName("", imported.name()))
                    .append(", ")
                    .append(importLambda("imports", imported))
                    .append("));\n");
        }
        for (WitInterface imported : world.importedInterfaces()) {
            // Resolved once rather than per call, so the embedder is asked for it a single time.
            String local = Names.member(imported.simpleName());
            body.append("    ")
                    .append(Names.type(imported.simpleName()))
                    .append(' ')
                    .append(local)
                    .append(" = imports.")
                    .append(local)
                    .append("();\n");
            body.append("    values.put(\"")
                    .append(imported.name())
                    .append("\", HostInstance.builder(store)\n");
            for (WitFunction function : imported.functions()) {
                body.append("        .addFunction(\"")
                        .append(function.name())
                        .append("\", ")
                        .append(constantName(imported.simpleName(), function.name()))
                        .append(", ")
                        .append(importLambda(local, function))
                        .append(")\n");
            }
            body.append("        .build());\n");
        }
        body.append("    return new ")
                .append(className)
                .append(
                        "(ComponentLinker.builder().build().instantiate(store, component,"
                                + " values));\n");
        body.append("}");
        return body.toString();
    }

    /** Adapts the embedder's method to the array of values a component function is called with. */
    private static String importLambda(String receiver, WitFunction function) {
        String call =
                receiver
                        + "."
                        + Names.member(function.name())
                        + "("
                        + arguments(function.type())
                        + ")";
        if (function.type().hasResult()) {
            return "args -> new Object[] {" + call + "}";
        }
        return "args -> {\n        " + call + ";\n        return new Object[0];\n    }";
    }

    private static String exportMethod(WitFunction function) {
        String name = Names.member(function.name());
        StringBuilder body = new StringBuilder();
        body.append("public ")
                .append(returnType(function.type()))
                .append(' ')
                .append(name)
                .append('(')
                .append(parameterList(function.type()))
                .append(") {\n");
        String call = "this." + name + ".apply(" + parameterNames(function.type()) + ")";
        if (function.type().hasResult()) {
            body.append("    return (")
                    .append(WitTypes.javaType(function.type().result()))
                    .append(") ")
                    .append(call)
                    .append("[0];\n");
        } else {
            body.append("    ").append(call).append(";\n");
        }
        body.append("}");
        return body.toString();
    }

    private static String descriptors(FuncType type) {
        List<String> all = new ArrayList<>();
        all.add(WitTypes.descriptorSource(type.hasResult() ? type.result() : null));
        for (LabelValType param : type.params()) {
            all.add(WitTypes.descriptorSource(param.valType()));
        }
        return String.join(", ", all);
    }

    private static String returnType(FuncType type) {
        return type.hasResult() ? WitTypes.javaType(type.result()) : "void";
    }

    private static String parameterList(FuncType type) {
        return type.params().stream()
                .map(p -> WitTypes.javaType(p.valType()) + " " + Names.member(p.label()))
                .collect(Collectors.joining(", "));
    }

    private static String parameterNames(FuncType type) {
        return type.params().stream()
                .map(p -> Names.member(p.label()))
                .collect(Collectors.joining(", "));
    }

    /** Casts each element of the incoming array to what the embedder's method declares. */
    private static String arguments(FuncType type) {
        List<LabelValType> params = type.params();
        List<String> casts = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            casts.add("(" + WitTypes.javaType(params.get(i).valType()) + ") args[" + i + "]");
        }
        return String.join(", ", casts);
    }

    /** Prefixed by the interface, so that two interfaces may each declare a {@code tick}. */
    private static String constantName(String prefix, String witName) {
        String qualified = prefix.isEmpty() ? witName : prefix + "-" + witName;
        return qualified.toUpperCase().replace('-', '_') + "_FUNC";
    }
}
