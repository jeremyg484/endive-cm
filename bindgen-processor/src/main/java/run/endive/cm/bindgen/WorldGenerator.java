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
        for (WitInterface exported : world.exportedInterfaces()) {
            type.addMember(StaticJavaParser.parseBodyDeclaration(guestInterface(exported)));
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
        for (WitInterface exported : world.exportedInterfaces()) {
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(
                            "private final "
                                    + Names.type(exported.simpleName())
                                    + " "
                                    + Names.member(exported.simpleName())
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
        for (WitInterface exported : world.exportedInterfaces()) {
            String name = Names.member(exported.simpleName());
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(
                            "/** The exported interface {@code "
                                    + exported.name()
                                    + "}. */\npublic "
                                    + Names.type(exported.simpleName())
                                    + " "
                                    + name
                                    + "() {\n    return "
                                    + name
                                    + ";\n}"));
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
        if (world.importedInterfaces().stream().anyMatch(i -> !i.resources().isEmpty())) {
            unit.addImport("run.endive.cm.abi.ResourceValue");
            unit.addImport("run.endive.cm.runtime.HostResource");
            unit.addImport("run.endive.cm.runtime.HostResourceTable");
            unit.addImport("run.endive.cm.types.LabelValType");
        }
        if (!world.imports().isEmpty()) {
            unit.addImport("run.endive.cm.runtime.HostFunction");
        }
        if (allExports(world).findAny().isPresent()) {
            unit.addImport("run.endive.cm.runtime.ComponentFunction");
        }
        if (allExports(world).anyMatch(f -> f.type().hasResult() || hasParams(f))) {
            unit.addImport("run.endive.cm.runtime.PrimitiveHostTypeDescriptor");
        }
        if (allExports(world).anyMatch(f -> !f.type().hasResult())) {
            unit.addImport("run.endive.cm.runtime.VoidHostTypeDescriptor");
        }
        if (usesCharValue(world)) {
            unit.addImport("run.endive.cm.abi.CharValue");
        }
    }

    private static boolean hasParams(WitFunction function) {
        return !function.type().params().isEmpty();
    }

    /** Everything the world imports, however it is reached, resource functions included. */
    private static Stream<WitFunction> allImports(WitWorld world) {
        return Stream.concat(
                world.imports().stream(),
                world.importedInterfaces().stream().flatMap(WorldGenerator::interfaceFunctions));
    }

    private static Stream<WitFunction> interfaceFunctions(WitInterface imported) {
        return Stream.concat(
                imported.functions().stream(),
                imported.resources().stream()
                        .flatMap(
                                r ->
                                        Stream.concat(
                                                r.constructor() == null
                                                        ? Stream.empty()
                                                        : Stream.of(r.constructor()),
                                                r.methods().stream())));
    }

    /** Everything the world exports, however it is reached. */
    private static Stream<WitFunction> allExports(WitWorld world) {
        return Stream.concat(
                world.exports().stream(),
                world.exportedInterfaces().stream().flatMap(i -> i.functions().stream()));
    }

    private static boolean usesCharValue(WitWorld world) {
        List<WitFunction> all = new ArrayList<>();
        allImports(world).forEach(all::add);
        allExports(world).forEach(all::add);
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
        for (WitResource resource : imported.resources()) {
            body.append('\n').append(indent(resourceInterface(resource))).append('\n');
            if (resource.constructor() != null) {
                body.append("\n    /** Makes a {@code ")
                        .append(resource.name())
                        .append("}. */\n    ")
                        .append(Names.type(resource.name()))
                        .append(' ')
                        .append(Names.member(resource.name()))
                        .append('(')
                        .append(parameterList(resource.constructor().type()))
                        .append(");\n");
            }
        }
        body.append("}");
        return body.toString();
    }

    /**
     * One Java interface per WIT resource. A method's borrowed receiver is what Java carries as
     * {@code this}, so it is dropped from the signature.
     */
    private static String resourceInterface(WitResource resource) {
        StringBuilder body = new StringBuilder();
        body.append("/** The resource {@code ").append(resource.name()).append("}. */\n");
        body.append("interface ").append(Names.type(resource.name())).append(" {\n");
        for (WitFunction method : resource.methods()) {
            body.append("    ")
                    .append(returnType(method.type()))
                    .append(' ')
                    .append(Names.member(method.name()))
                    .append('(')
                    .append(parameterList(method.type(), 1))
                    .append(");\n");
        }
        body.append(
                "\n    /** Called when the guest drops an owned handle to this resource. */\n"
                        + "    default void drop() {\n    }\n");
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
        for (WitInterface exported : world.exportedInterfaces()) {
            body.append("    this.")
                    .append(Names.member(exported.simpleName()))
                    .append(" = new ")
                    .append(Names.type(exported.simpleName()))
                    .append("(instance.exportedInstance(\"")
                    .append(exported.name())
                    .append("\"));\n");
        }
        body.append("}");
        return body.toString();
    }

    /** One wrapper class per exported WIT interface, narrowing each of its functions once. */
    private static String guestInterface(WitInterface exported) {
        String className = Names.type(exported.simpleName());
        StringBuilder body = new StringBuilder();
        body.append("/** The exported interface {@code ").append(exported.name()).append("}. */\n");
        body.append("public static final class ").append(className).append(" {\n");
        for (WitFunction function : exported.functions()) {
            body.append("    private final ComponentFunction ")
                    .append(Names.member(function.name()))
                    .append(";\n");
        }
        body.append("\n    private ").append(className).append("(ComponentInstance instance) {\n");
        for (WitFunction function : exported.functions()) {
            body.append("        this.")
                    .append(Names.member(function.name()))
                    .append(" = instance.export(\"")
                    .append(function.name())
                    .append("\").typed(")
                    .append(descriptors(function.type()))
                    .append(");\n");
        }
        body.append("    }\n");
        for (WitFunction function : exported.functions()) {
            body.append("\n").append(indent(exportMethod(function))).append("\n");
        }
        body.append("}");
        return body.toString();
    }

    private static String indent(String block) {
        StringBuilder result = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            result.append(line.isEmpty() ? line : "    " + line).append('\n');
        }
        return result.substring(0, result.length() - 1);
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
            if (imported.resources().isEmpty()) {
                appendChainedInterface(body, imported, local);
            } else {
                appendResourcefulInterface(body, imported, local);
            }
        }
        body.append("    return new ")
                .append(className)
                .append(
                        "(ComponentLinker.builder().build().instantiate(store, component,"
                                + " values));\n");
        body.append("}");
        return body.toString();
    }

    /** An interface of plain functions needs no local, so it is built in one expression. */
    private static void appendChainedInterface(
            StringBuilder body, WitInterface imported, String receiver) {
        body.append("    values.put(\"")
                .append(imported.name())
                .append("\", HostInstance.builder(store)\n");
        for (WitFunction function : imported.functions()) {
            body.append("        .addFunction(\"")
                    .append(function.name())
                    .append("\", ")
                    .append(constantName(imported.simpleName(), function.name()))
                    .append(", ")
                    .append(importLambda(receiver, function))
                    .append(")\n");
        }
        body.append("        .build());\n");
    }

    /**
     * A resource has to be declared before anything naming it, and its {@code own} and
     * {@code borrow} are only known once it is, so an interface declaring one is built through a
     * local rather than in a single expression. That is also why the function types of its
     * constructor and methods are built here rather than held as constants.
     */
    private static void appendResourcefulInterface(
            StringBuilder body, WitInterface imported, String receiver) {
        String prefix = Names.member(imported.simpleName());
        String builder = prefix + "Builder";
        body.append("    HostInstance.Builder ")
                .append(builder)
                .append(" = HostInstance.builder(store);\n");

        for (WitResource resource : imported.resources()) {
            String type = Names.type(imported.simpleName()) + "." + Names.type(resource.name());
            String table = prefix + Names.type(resource.name()) + "Table";
            String handle = prefix + Names.type(resource.name());

            body.append("    HostResourceTable<")
                    .append(type)
                    .append("> ")
                    .append(table)
                    .append(" = new HostResourceTable<>();\n");
            body.append("    HostResource ")
                    .append(handle)
                    .append(" = ")
                    .append(builder)
                    .append(".declareResource(rep -> ")
                    .append(table)
                    .append(".drop(rep, ")
                    .append(type)
                    .append("::drop));\n");
            body.append("    ")
                    .append(builder)
                    .append(".addResource(\"")
                    .append(resource.name())
                    .append("\", ")
                    .append(handle)
                    .append(");\n");

            if (resource.constructor() != null) {
                body.append("    ")
                        .append(builder)
                        .append(".addFunction(\"[constructor]")
                        .append(resource.name())
                        .append("\", ")
                        .append(constructorFuncType(resource.constructor(), handle))
                        .append(", args -> new Object[] {ResourceValue.owned(")
                        .append(handle)
                        .append(".type(), ")
                        .append(table)
                        .append(".add(")
                        .append(receiver)
                        .append('.')
                        .append(Names.member(resource.name()))
                        .append('(')
                        .append(arguments(resource.constructor().type(), 0))
                        .append(")))});\n");
            }

            for (WitFunction method : resource.methods()) {
                String call =
                        table
                                + ".get((ResourceValue) args[0])."
                                + Names.member(method.name())
                                + "("
                                + arguments(method.type(), 1)
                                + ")";
                body.append("    ")
                        .append(builder)
                        .append(".addFunction(\"[method]")
                        .append(resource.name())
                        .append('.')
                        .append(method.name())
                        .append("\", ")
                        .append(methodFuncType(method, handle))
                        .append(", ")
                        .append(
                                method.type().hasResult()
                                        ? "args -> new Object[] {" + call + "}"
                                        : "args -> {\n        "
                                                + call
                                                + ";\n        return new Object[0];\n    }")
                        .append(");\n");
            }
        }

        for (WitFunction function : imported.functions()) {
            body.append("    ")
                    .append(builder)
                    .append(".addFunction(\"")
                    .append(function.name())
                    .append("\", ")
                    .append(constantName(imported.simpleName(), function.name()))
                    .append(", ")
                    .append(importLambda(receiver, function))
                    .append(");\n");
        }

        body.append("    values.put(\"")
                .append(imported.name())
                .append("\", ")
                .append(builder)
                .append(".build());\n");
    }

    /** A constructor takes the resource's declared parameters and hands back an {@code own}. */
    private static String constructorFuncType(WitFunction constructor, String handle) {
        return "FuncType.builder()"
                + params(constructor.type(), 0)
                + ".withResult("
                + handle
                + ".own()).build()";
    }

    /** A method borrows its receiver, which is what its first parameter always is. */
    private static String methodFuncType(WitFunction method, String handle) {
        String self = method.type().params().get(0).label();
        StringBuilder source = new StringBuilder("FuncType.builder()");
        source.append(".addParam(LabelValType.builder().withLabel(\"")
                .append(self)
                .append("\").withValType(")
                .append(handle)
                .append(".borrow()).build())");
        source.append(params(method.type(), 1));
        if (method.type().hasResult()) {
            source.append(".withResult(")
                    .append(WitTypes.valTypeSource(method.type().result()))
                    .append(")");
        }
        return source.append(".build()").toString();
    }

    /** Parameter sources for a function type, past whatever the caller has already written. */
    private static String params(FuncType type, int skip) {
        StringBuilder source = new StringBuilder();
        for (LabelValType param : type.params().subList(skip, type.params().size())) {
            source.append(".addParam(LabelValType.builder().withLabel(\"")
                    .append(param.label())
                    .append("\").withValType(")
                    .append(WitTypes.valTypeSource(param.valType()))
                    .append(").build())");
        }
        return source.toString();
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
        return parameterList(type, 0);
    }

    /**
     * @param skip leading parameters the Java signature does not carry, such as a receiver
     */
    private static String parameterList(FuncType type, int skip) {
        return type.params().stream()
                .skip(skip)
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
        return arguments(type, 0);
    }

    /**
     * @param skip leading arguments the embedder's method does not take, such as a receiver
     */
    private static String arguments(FuncType type, int skip) {
        List<LabelValType> params = type.params();
        List<String> casts = new ArrayList<>();
        for (int i = skip; i < params.size(); i++) {
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
