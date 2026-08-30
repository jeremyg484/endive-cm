package run.endive.cm.bindgen;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.Generated;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;

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

    // ---------------------------------------------------------------- imports

    private static void addImports(CompilationUnit unit, WitWorld world) {
        unit.addImport("java.util.LinkedHashMap");
        unit.addImport("java.util.Map");
        unit.addImport("run.endive.cm.runtime.ComponentInstance");
        unit.addImport("run.endive.cm.runtime.ComponentLinker");
        unit.addImport("run.endive.cm.runtime.ComponentStore");
        unit.addImport("run.endive.cm.types.WasmComponent");

        if (allImports(world).findAny().isPresent()) {
            unit.addImport("run.endive.cm.types.FuncType");
        }
        if (!world.imports().isEmpty()) {
            unit.addImport("run.endive.cm.runtime.HostFunction");
        }
        if (!world.importedInterfaces().isEmpty()) {
            unit.addImport("run.endive.cm.runtime.HostInstance");
        }
        if (allImports(world).anyMatch(f -> f.type().hasResult() || hasParams(f))) {
            unit.addImport("run.endive.cm.types.PrimValType");
            unit.addImport("run.endive.cm.types.ValType");
        }
        if (allImports(world).anyMatch(WorldGenerator::hasParams)) {
            unit.addImport("run.endive.cm.types.LabelValType");
        }
        if (world.importedInterfaces().stream().anyMatch(i -> !i.resources().isEmpty())) {
            unit.addImport("run.endive.cm.abi.ResourceValue");
            unit.addImport("run.endive.cm.runtime.HostResource");
            unit.addImport("run.endive.cm.runtime.HostResourceTable");
            unit.addImport("run.endive.cm.types.LabelValType");
        }
        if (declaresTypes(world)) {
            unit.addImport("run.endive.cm.types.Type");
            unit.addImport("run.endive.cm.types.ValType");
        }
        if (usesKind(world, DefValType.Kind.LIST)) {
            unit.addImport("java.util.List");
            unit.addImport("run.endive.cm.types.ListType");
        }
        if (usesKind(world, DefValType.Kind.ENUM)) {
            unit.addImport("run.endive.cm.abi.VariantValue");
            unit.addImport("run.endive.cm.types.EnumType");
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
        if (anyValue(world, WitTypes::needsCharValue)) {
            unit.addImport("run.endive.cm.abi.CharValue");
        }
    }

    private static boolean declaresTypes(WitWorld world) {
        return world.importedInterfaces().stream().anyMatch(i -> !compoundTypes(i).isEmpty());
    }

    private static boolean usesKind(WitWorld world, DefValType.Kind kind) {
        for (WitInterface imported : world.importedInterfaces()) {
            for (Type declared : compoundTypes(imported).values()) {
                if (declared.defValType().kind() == kind) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyValue(WitWorld world, java.util.function.Predicate<ValType> test) {
        return Stream.concat(allImports(world), allExports(world))
                .anyMatch(
                        f ->
                                test.test(f.type().result())
                                        || f.type().params().stream()
                                                .anyMatch(p -> test.test(p.valType())));
    }

    // ---------------------------------------------------------------- the imports side

    private static String importsInterface(WitWorld world) {
        StringBuilder body = new StringBuilder();
        body.append("/** The world's imports, which the embedder implements. */\n");
        body.append("public interface Imports {\n");
        for (WitFunction imported : world.imports()) {
            body.append("    ").append(signature(imported, 0)).append(";\n");
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
        return body.append("}").toString();
    }

    /** One Java interface per imported WIT interface, holding its types and its functions. */
    private static String hostInterface(WitInterface imported) {
        StringBuilder body = new StringBuilder();
        body.append("/** The imported interface {@code ").append(imported.name()).append("}. */\n");
        body.append("public interface ").append(Names.type(imported.simpleName())).append(" {\n");

        for (WitEnum declared : imported.enums()) {
            body.append(indent(enumType(declared))).append('\n');
        }
        for (WitFunction function : imported.functions()) {
            body.append("    ").append(signature(function, 0)).append(";\n");
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
                        .append(parameterList(resource.constructor(), 0))
                        .append(");\n");
            }
        }
        return body.append("}").toString();
    }

    /** A WIT enum becomes a Java enum, carrying the label the ABI knows it by. */
    private static String enumType(WitEnum declared) {
        String name = Names.type(declared.name());
        StringBuilder body = new StringBuilder();
        body.append("/** The enum {@code ").append(declared.name()).append("}. */\n");
        body.append("enum ").append(name).append(" {\n\n");
        List<String> constants = new ArrayList<>();
        for (String label : declared.labels()) {
            constants.add("    " + constantOf(label) + "(\"" + label + "\")");
        }
        body.append(String.join(",\n", constants)).append(";\n\n");
        body.append("    private final String label;\n\n");
        body.append("    ")
                .append(name)
                .append("(String label) {\n        this.label = label;\n    }\n\n");
        body.append(
                "    /** This case as the ABI carries it, which is a variant with no payload."
                        + " */\n");
        body.append("    public VariantValue toComponent() {\n")
                .append("        return VariantValue.of(label, null);\n    }\n\n");
        body.append("    /** The case a lifted value names. */\n");
        body.append("    public static ").append(name).append(" fromComponent(Object value) {\n");
        body.append("        String label = ((VariantValue) value).label();\n");
        body.append("        for (").append(name).append(" candidate : values()) {\n");
        body.append(
                "            if (candidate.label.equals(label)) {\n"
                        + "                return candidate;\n"
                        + "            }\n"
                        + "        }\n");
        body.append("        throw new IllegalArgumentException(\"unknown ")
                .append(declared.name())
                .append(": \" + label);\n    }\n}");
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
            body.append("    ").append(signature(method, 1)).append(";\n");
        }
        body.append(
                "\n    /** Called when the guest drops an owned handle to this resource. */\n"
                        + "    default void drop() {\n    }\n");
        return body.append("}").toString();
    }

    // ---------------------------------------------------------------- the exports side

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
                    .append(descriptors(function))
                    .append(");\n");
        }
        body.append("    }\n");
        for (WitFunction function : exported.functions()) {
            body.append('\n').append(indent(exportMethod(function))).append('\n');
        }
        return body.append("}").toString();
    }

    private static String exportMethod(WitFunction function) {
        String name = Names.member(function.name());
        StringBuilder body = new StringBuilder();
        body.append("public ").append(signature(function, 0)).append(" {\n");
        String call = "this." + name + ".apply(" + callArguments(function) + ")";
        if (function.type().hasResult()) {
            body.append("    return ")
                    .append(
                            WitTypes.fromComponent(
                                    call + "[0]", function.type().result(), function.scope()))
                    .append(";\n");
        } else {
            body.append("    ").append(call).append(";\n");
        }
        return body.append("}").toString();
    }

    /** Arguments as the component takes them, converting whatever Java does not carry directly. */
    private static String callArguments(WitFunction function) {
        return function.type().params().stream()
                .map(
                        p ->
                                WitTypes.toComponent(
                                        Names.member(p.label()), p.valType(), function.scope()))
                .collect(Collectors.joining(", "));
    }

    private static String descriptors(WitFunction function) {
        List<String> all = new ArrayList<>();
        FuncType type = function.type();
        all.add(
                WitTypes.descriptorSource(
                        type.hasResult() ? type.result() : null, function.scope()));
        for (LabelValType param : type.params()) {
            all.add(WitTypes.descriptorSource(param.valType(), function.scope()));
        }
        return String.join(", ", all);
    }

    // ---------------------------------------------------------------- instantiation

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
                    .append(descriptors(exported))
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
        return body.append("}").toString();
    }

    private static String instantiate(WitWorld world, String className) {
        StringBuilder body = new StringBuilder();
        body.append(
                "/** Instantiates {@code component}, satisfying its imports with {@code imports}."
                        + " */\n");
        if (usesKind(world, DefValType.Kind.LIST)) {
            body.append("@SuppressWarnings(\"unchecked\")\n");
        }
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
                    .append(importLambda("imports", imported, 0))
                    .append("));\n");
        }
        for (WitInterface imported : world.importedInterfaces()) {
            appendInterface(body, imported);
        }
        body.append("    return new ")
                .append(className)
                .append(
                        "(ComponentLinker.builder().build().instantiate(store, component,"
                                + " values));\n");
        return body.append("}").toString();
    }

    /**
     * An interface is built through a local, because a type has to be declared before anything
     * names it and the value type naming one is only known once it has been. That is also why the
     * function types here are built in place rather than held as constants.
     */
    private static void appendInterface(StringBuilder body, WitInterface imported) {
        String prefix = Names.member(imported.simpleName());
        String outer = Names.type(imported.simpleName());
        String builder = prefix + "Builder";

        // Resolved once rather than per call, so the embedder is asked for it a single time.
        body.append("    ")
                .append(outer)
                .append(' ')
                .append(prefix)
                .append(" = imports.")
                .append(prefix)
                .append("();\n");
        body.append("    HostInstance.Builder ")
                .append(builder)
                .append(" = HostInstance.builder(store);\n");

        Map<Integer, String> declared = new LinkedHashMap<>();
        Map<Type, String> byType = new IdentityHashMap<>();
        for (Map.Entry<Integer, Type> entry : compoundTypes(imported).entrySet()) {
            String existing = byType.get(entry.getValue());
            if (existing != null) {
                declared.put(entry.getKey(), existing);
                continue;
            }
            String local =
                    prefix + Names.type(typeName(imported, entry.getValue(), entry.getKey()));
            byType.put(entry.getValue(), local);
            declared.put(entry.getKey(), local);
            body.append("    ValType ")
                    .append(local)
                    .append(" = ")
                    .append(builder)
                    .append(".declareType(")
                    .append(
                            WitTypes.defValTypeSource(
                                    entry.getValue().defValType(), imported.scope(), declared))
                    .append(");\n");
        }

        for (WitResource resource : imported.resources()) {
            appendResource(body, imported, resource, prefix, outer, builder, declared);
        }
        for (WitFunction function : imported.functions()) {
            body.append("    ")
                    .append(builder)
                    .append(".addFunction(\"")
                    .append(function.name())
                    .append("\", ")
                    .append(funcTypeSource(function, 0, null, declared))
                    .append(", ")
                    .append(importLambda(prefix, function, 0))
                    .append(");\n");
        }
        body.append("    values.put(\"")
                .append(imported.name())
                .append("\", ")
                .append(builder)
                .append(".build());\n");
    }

    private static void appendResource(
            StringBuilder body,
            WitInterface imported,
            WitResource resource,
            String prefix,
            String outer,
            String builder,
            Map<Integer, String> declared) {
        String type = outer + "." + Names.type(resource.name());
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
            WitFunction constructor = resource.constructor();
            body.append("    ")
                    .append(builder)
                    .append(".addFunction(\"[constructor]")
                    .append(resource.name())
                    .append("\", ")
                    .append(funcTypeSource(constructor, 0, handle + ".own()", declared))
                    .append(", args -> new Object[] {ResourceValue.owned(")
                    .append(handle)
                    .append(".type(), ")
                    .append(table)
                    .append(".add(")
                    .append(prefix)
                    .append('.')
                    .append(Names.member(resource.name()))
                    .append('(')
                    .append(lambdaArguments(constructor, 0))
                    .append(")))});\n");
        }

        for (WitFunction method : resource.methods()) {
            String receiver = table + ".get((ResourceValue) args[0])";
            body.append("    ")
                    .append(builder)
                    .append(".addFunction(\"[method]")
                    .append(resource.name())
                    .append('.')
                    .append(method.name())
                    .append("\", ")
                    .append(funcTypeSource(method, 1, handle + ".borrow()", declared))
                    .append(", ")
                    .append(importLambda(receiver, method, 1))
                    .append(");\n");
        }
    }

    // ---------------------------------------------------------------- shared pieces

    /** The function type a world's bare import is registered with, which names no declared type. */
    private static String funcTypeField(String prefix, WitFunction function) {
        return "private static final FuncType "
                + constantName(prefix, function.name())
                + " =\n    "
                + funcTypeSource(function, 0, null, Map.of())
                + ";";
    }

    /**
     * @param skipParams leading parameters written by the caller, such as a borrowed receiver
     * @param leading source for that leading parameter, or the result when it replaces one
     */
    private static String funcTypeSource(
            WitFunction function, int skipParams, String leading, Map<Integer, String> declared) {
        FuncType type = function.type();
        StringBuilder source = new StringBuilder("FuncType.builder()");
        if (skipParams > 0) {
            source.append("\n    .addParam(LabelValType.builder().withLabel(\"")
                    .append(type.params().get(0).label())
                    .append("\").withValType(")
                    .append(leading)
                    .append(").build())");
        }
        for (LabelValType param : type.params().subList(skipParams, type.params().size())) {
            source.append("\n    .addParam(LabelValType.builder().withLabel(\"")
                    .append(param.label())
                    .append("\").withValType(")
                    .append(WitTypes.valTypeSource(param.valType(), function.scope(), declared))
                    .append(").build())");
        }
        if (skipParams == 0 && leading != null) {
            source.append("\n    .withResult(").append(leading).append(")");
        } else if (type.hasResult()) {
            source.append("\n    .withResult(")
                    .append(WitTypes.valTypeSource(type.result(), function.scope(), declared))
                    .append(")");
        }
        return source.append("\n    .build()").toString();
    }

    /** Adapts the embedder's method to the array of values a component function is called with. */
    private static String importLambda(String receiver, WitFunction function, int skip) {
        String call =
                receiver
                        + "."
                        + Names.member(function.name())
                        + "("
                        + lambdaArguments(function, skip)
                        + ")";
        if (function.type().hasResult()) {
            return "args -> new Object[] {"
                    + WitTypes.toComponent(call, function.type().result(), function.scope())
                    + "}";
        }
        return "args -> {\n        " + call + ";\n        return new Object[0];\n    }";
    }

    /** Each incoming value as the embedder's method takes it. */
    private static String lambdaArguments(WitFunction function, int skip) {
        List<LabelValType> params = function.type().params();
        List<String> values = new ArrayList<>();
        for (int i = skip; i < params.size(); i++) {
            values.add(
                    WitTypes.fromComponent(
                            "args[" + i + "]", params.get(i).valType(), function.scope()));
        }
        return String.join(", ", values);
    }

    private static String signature(WitFunction function, int skip) {
        return returnType(function)
                + " "
                + Names.member(function.name())
                + "("
                + parameterList(function, skip)
                + ")";
    }

    private static String returnType(WitFunction function) {
        return function.type().hasResult()
                ? WitTypes.javaType(function.type().result(), function.scope())
                : "void";
    }

    private static String parameterList(WitFunction function, int skip) {
        return function.type().params().stream()
                .skip(skip)
                .map(
                        p ->
                                WitTypes.javaType(p.valType(), function.scope())
                                        + " "
                                        + Names.member(p.label()))
                .collect(Collectors.joining(", "));
    }

    /** The compound types an interface declares, by index, in the order they were declared. */
    private static Map<Integer, Type> compoundTypes(WitInterface imported) {
        Map<Integer, Type> found = new LinkedHashMap<>();
        WitScope scope = imported.scope();
        for (int i = 0; i < scope.size(); i++) {
            Type declared = scope.at(i);
            if (declared != null && declared.defValType() != null && isCompound(declared)) {
                found.put(i, declared);
            }
        }
        return found;
    }

    private static boolean isCompound(Type type) {
        DefValType.Kind kind = type.defValType().kind();
        return kind == DefValType.Kind.LIST || kind == DefValType.Kind.ENUM;
    }

    /** A declared type takes its Java name from whichever index named it, or from its position. */
    private static String typeName(WitInterface imported, Type type, int index) {
        WitScope scope = imported.scope();
        for (int i = 0; i < scope.size(); i++) {
            if (scope.at(i) == type && scope.nameAt(i) != null) {
                return scope.nameAt(i);
            }
        }
        return "type-" + index;
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

    /** Prefixed by the interface, so that two interfaces may each declare a {@code tick}. */
    private static String constantName(String prefix, String witName) {
        String qualified = prefix.isEmpty() ? witName : prefix + "-" + witName;
        return qualified.toUpperCase().replace('-', '_') + "_FUNC";
    }

    private static String constantOf(String label) {
        return label.toUpperCase().replace('-', '_');
    }

    private static String indent(String block) {
        StringBuilder result = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            result.append(line.isEmpty() ? line : "    " + line).append('\n');
        }
        return result.substring(0, result.length() - 1);
    }
}
