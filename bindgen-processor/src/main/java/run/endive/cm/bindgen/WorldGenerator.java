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

/**
 * Generates the bindings for one world.
 *
 * <p>The world becomes a class of its own, and each interface it names becomes a Java package
 * mirroring the WIT id, holding the interface plus whatever types it declares. Exports sit under an
 * {@code exports} package, which is what lets a world import and export one name at once.
 */
final class WorldGenerator {

    /** A handle is carried by its value rather than by its Java class, whichever way it goes. */
    private static final String RESOURCE_DESCRIPTOR = "ResourceHostTypeDescriptor.instance()";

    private final WitWorld world;
    private final String base;
    private final String generatedBy;

    private WorldGenerator(WitWorld world, String base, String generatedBy) {
        this.world = world;
        this.base = base;
        this.generatedBy = generatedBy;
    }

    static List<GeneratedSource> generate(WitWorld world, String base, String generatedBy) {
        return new WorldGenerator(world, base, generatedBy).sources();
    }

    private List<GeneratedSource> sources() {
        List<GeneratedSource> sources = new ArrayList<>();
        for (WitInterface imported : world.importedInterfaces()) {
            imported.scope().withJavaPackage(imported.javaPackage(base, false));
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            exported.scope().withJavaPackage(exported.javaPackage(base, true));
        }
        for (WitInterface imported : world.importedInterfaces()) {
            sources.addAll(interfaceSources(imported, false));
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            sources.addAll(interfaceSources(exported, true));
        }
        sources.add(worldSource());
        return sources;
    }

    // ---------------------------------------------------------------- the world

    private GeneratedSource worldSource() {
        String className = Names.type(world.name());
        WitTypes types = new WitTypes(base);

        var unit = newUnit(base);
        unit.addImport("java.util.LinkedHashMap");
        unit.addImport("java.util.Map");
        unit.addImport("run.endive.cm.runtime.ComponentInstance");
        unit.addImport("run.endive.cm.runtime.ComponentLinker");
        unit.addImport("run.endive.cm.runtime.ComponentStore");
        unit.addImport("run.endive.cm.types.WasmComponent");
        addValueImports(unit, types, Stream.concat(world.imports().stream(), allImports(world)));
        addValueImports(unit, types, allExports(world));
        if (!world.imports().isEmpty()) {
            unit.addImport("run.endive.cm.runtime.HostFunction");
            unit.addImport("run.endive.cm.types.FuncType");
        }
        if (!world.exports().isEmpty()) {
            unit.addImport("run.endive.cm.runtime.ComponentFunction");
        }
        addDescriptorImports(unit, types, world.exports().stream());
        addWiringImports(unit);

        var type = declare(unit, className);
        type.setJavadocComment("Bindings for the WIT world {@code " + world.qualifiedName() + "}.");

        for (WitFunction imported : world.imports()) {
            type.addMember(StaticJavaParser.parseBodyDeclaration(funcTypeField(types, imported)));
        }
        type.addMember(StaticJavaParser.parseBodyDeclaration(importsInterface(types)));

        type.addMember(
                StaticJavaParser.parseBodyDeclaration("private final ComponentInstance instance;"));
        for (WitFunction exported : world.exports()) {
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(
                            "private final ComponentFunction " + fieldName(exported.name()) + ";"));
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(
                            "private final "
                                    + guestType(exported)
                                    + " "
                                    + fieldName(exported.simpleName())
                                    + ";"));
        }

        type.addMember(StaticJavaParser.parseBodyDeclaration(constructor(types, className)));
        type.addMember(StaticJavaParser.parseBodyDeclaration(instantiate(types, className)));
        type.addMember(
                StaticJavaParser.parseBodyDeclaration(
                        "/** The component instance behind these bindings. */\n"
                                + "public ComponentInstance instance() {\n"
                                + "    return instance;\n"
                                + "}"));

        for (WitFunction exported : world.exports()) {
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(
                            exportMethod(types, exported, fieldName(exported.name()))));
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            String name = fieldName(exported.simpleName());
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(
                            "/** The exported interface {@code "
                                    + exported.name()
                                    + "}. */\npublic "
                                    + guestType(exported)
                                    + " "
                                    + Names.member(exported.simpleName())
                                    + "() {\n    return "
                                    + name
                                    + ";\n}"));
        }
        return finish(base, className, unit);
    }

    /** What {@code instantiate} names while it builds the host side of the world's imports. */
    private void addWiringImports(CompilationUnit unit) {
        // A bare import carries its function type as a constant, whether or not any interface does.
        Stream<WitFunction> everything = Stream.concat(world.imports().stream(), allImports(world));
        List<WitFunction> all = everything.collect(Collectors.toList());
        if (all.stream().anyMatch(WorldGenerator::hasParams)) {
            unit.addImport("run.endive.cm.types.LabelValType");
        }
        if (all.stream().anyMatch(f -> f.type().hasResult() || hasParams(f))) {
            unit.addImport("run.endive.cm.types.PrimValType");
            unit.addImport("run.endive.cm.types.ValType");
        }
        if (world.importedInterfaces().isEmpty()) {
            return;
        }
        unit.addImport("run.endive.cm.runtime.HostInstance");
        unit.addImport("run.endive.cm.types.FuncType");
        if (world.importedInterfaces().stream().anyMatch(i -> !compoundTypes(i).isEmpty())) {
            unit.addImport("run.endive.cm.types.Type");
            unit.addImport("run.endive.cm.types.ValType");
        }
        if (usesKind(DefValType.Kind.ENUM)) {
            unit.addImport("run.endive.cm.types.EnumType");
        }
        if (usesKind(DefValType.Kind.LIST)) {
            unit.addImport("run.endive.cm.types.ListType");
        }
        if (world.importedInterfaces().stream().anyMatch(i -> !i.resources().isEmpty())) {
            unit.addImport("run.endive.cm.abi.ResourceValue");
            unit.addImport("run.endive.cm.runtime.HostResource");
            unit.addImport("run.endive.cm.runtime.HostResourceTable");
            unit.addImport("run.endive.cm.types.LabelValType");
        }
    }

    private String importsInterface(WitTypes types) {
        StringBuilder body = new StringBuilder();
        body.append("/** The world's imports, which the embedder implements. */\n");
        body.append("public interface Imports {\n");
        for (WitFunction imported : world.imports()) {
            body.append("    ").append(signature(types, imported, 0)).append(";\n");
        }
        for (WitInterface imported : world.importedInterfaces()) {
            body.append("\n    /** The imported interface {@code ")
                    .append(imported.name())
                    .append("}. */\n    ")
                    .append(hostType(imported))
                    .append(' ')
                    .append(Names.member(imported.simpleName()))
                    .append("();\n");
        }
        return body.append("}").toString();
    }

    private String constructor(WitTypes types, String className) {
        StringBuilder body = new StringBuilder();
        body.append("private ").append(className).append("(ComponentInstance instance) {\n");
        body.append("    this.instance = instance;\n");
        for (WitFunction exported : world.exports()) {
            body.append("    this.")
                    .append(fieldName(exported.name()))
                    .append(" = instance.export(\"")
                    .append(exported.name())
                    .append("\").typed(")
                    .append(descriptors(types, exported, null, null))
                    .append(");\n");
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            body.append("    this.")
                    .append(fieldName(exported.simpleName()))
                    .append(" = new ")
                    .append(guestType(exported))
                    .append("(instance.exportedInstance(\"")
                    .append(exported.name())
                    .append("\"));\n");
        }
        return body.append("}").toString();
    }

    private String instantiate(WitTypes types, String className) {
        StringBuilder body = new StringBuilder();
        body.append(
                "/** Instantiates {@code component}, satisfying its imports with {@code imports}."
                        + " */\n");
        if (usesKind(DefValType.Kind.LIST)) {
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
                    .append(importLambda(types, "imports", imported, 0))
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

    // ---------------------------------------------------------------- one interface package

    private List<GeneratedSource> interfaceSources(WitInterface iface, boolean exported) {
        String pkg = iface.scope().javaPackage();
        WitTypes types = new WitTypes(pkg);
        List<GeneratedSource> sources = new ArrayList<>();

        for (WitEnum declared : iface.enums()) {
            var unit = newUnit(pkg);
            unit.addImport("run.endive.cm.abi.VariantValue");
            var type =
                    unit.addEnum(Names.type(declared.name()))
                            .setPublic(true)
                            .addSingleMemberAnnotation(
                                    Generated.class, new StringLiteralExpr(generatedBy));
            type.setJavadocComment(
                    "The WIT enum {@code "
                            + declared.name()
                            + "}, declared by {@code "
                            + iface.name()
                            + "}.");
            for (String label : declared.labels()) {
                type.addEnumConstant(constantOf(label)).addArgument(new StringLiteralExpr(label));
            }
            for (String member : enumMembers(Names.type(declared.name()), declared)) {
                type.addMember(StaticJavaParser.parseBodyDeclaration(member));
            }
            sources.add(finish(pkg, Names.type(declared.name()), unit));
        }

        for (WitResource resource : iface.resources()) {
            sources.add(
                    exported
                            ? guestResourceSource(types, pkg, iface, resource)
                            : hostResourceSource(types, pkg, resource));
        }

        sources.add(exported ? guestSource(types, pkg, iface) : hostSource(types, pkg, iface));
        return sources;
    }

    private GeneratedSource hostSource(WitTypes types, String pkg, WitInterface iface) {
        var unit = newUnit(pkg);
        addValueImports(unit, types, interfaceFunctions(iface));

        var type = declareInterface(unit, "Host");
        type.setJavadocComment(
                "The WIT interface {@code " + iface.name() + "}, which the embedder implements.");
        for (WitFunction function : iface.functions()) {
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(signature(types, function, 0) + ";"));
        }
        for (WitResource resource : iface.resources()) {
            if (resource.constructor() != null) {
                type.addMember(
                        StaticJavaParser.parseBodyDeclaration(
                                "/** Makes a {@code "
                                        + resource.name()
                                        + "}. */\n"
                                        + Names.type(resource.name())
                                        + " "
                                        + Names.member(resource.name())
                                        + "("
                                        + parameterList(types, resource.constructor(), 0)
                                        + ");"));
            }
        }
        return finish(pkg, "Host", unit);
    }

    private GeneratedSource hostResourceSource(WitTypes types, String pkg, WitResource resource) {
        var unit = newUnit(pkg);
        addValueImports(unit, types, resource.methods().stream());

        var type = declareInterface(unit, Names.type(resource.name()));
        type.setJavadocComment(
                "The WIT resource {@code "
                        + resource.name()
                        + "}, which the embedder implements. A method's borrowed receiver is what"
                        + " Java carries as {@code this}, so it is not a parameter here.");
        for (WitFunction method : resource.methods()) {
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(signature(types, method, 1) + ";"));
        }
        type.addMember(
                StaticJavaParser.parseBodyDeclaration(
                        "/** Called when the guest drops an owned handle to this resource. */\n"
                                + "default void drop() {\n}"));
        return finish(pkg, Names.type(resource.name()), unit);
    }

    private GeneratedSource guestSource(WitTypes types, String pkg, WitInterface iface) {
        var unit = newUnit(pkg);
        unit.addImport("run.endive.cm.runtime.ComponentFunction");
        unit.addImport("run.endive.cm.runtime.ComponentInstance");
        addValueImports(unit, types, interfaceFunctions(iface));
        addDescriptorImports(unit, types, interfaceFunctions(iface));
        if (!iface.resources().isEmpty()) {
            unit.addImport("run.endive.cm.abi.ResourceValue");
            unit.addImport("run.endive.cm.runtime.ResourceHostTypeDescriptor");
        }

        var type = declare(unit, "Guest");
        type.setJavadocComment(
                "The WIT interface {@code " + iface.name() + "}, as the component exports it.");

        for (WitFunction function : iface.functions()) {
            type.addMember(
                    StaticJavaParser.parseBodyDeclaration(
                            "private final ComponentFunction "
                                    + Names.member(function.name())
                                    + ";"));
        }
        for (WitResource resource : iface.resources()) {
            for (WitFunction function : resourceFunctions(resource)) {
                // Read by the resource wrapper, which is a class of its own in this package.
                type.addMember(
                        StaticJavaParser.parseBodyDeclaration(
                                "final ComponentFunction "
                                        + resourceField(resource, function)
                                        + ";"));
            }
        }

        StringBuilder ctor = new StringBuilder();
        ctor.append(
                "/** Built by the world's bindings. Public only because they are another package."
                        + " */\npublic Guest(ComponentInstance instance) {\n");
        for (WitFunction function : iface.functions()) {
            ctor.append("    this.")
                    .append(Names.member(function.name()))
                    .append(" = instance.export(\"")
                    .append(function.name())
                    .append("\").typed(")
                    .append(descriptors(types, function, null, null))
                    .append(");\n");
        }
        for (WitResource resource : iface.resources()) {
            if (resource.constructor() != null) {
                ctor.append("    this.")
                        .append(resourceField(resource, resource.constructor()))
                        .append(" = instance.export(\"[constructor]")
                        .append(resource.name())
                        .append("\").typed(")
                        .append(
                                descriptors(
                                        types, resource.constructor(), RESOURCE_DESCRIPTOR, null))
                        .append(");\n");
            }
            for (WitFunction method : resource.methods()) {
                ctor.append("    this.")
                        .append(resourceField(resource, method))
                        .append(" = instance.export(\"[method]")
                        .append(resource.name())
                        .append('.')
                        .append(method.name())
                        .append("\").typed(")
                        .append(descriptors(types, method, null, RESOURCE_DESCRIPTOR))
                        .append(");\n");
            }
        }
        type.addMember(StaticJavaParser.parseBodyDeclaration(ctor.append("}").toString()));

        for (WitFunction function : iface.functions()) {
            type.addMember(StaticJavaParser.parseBodyDeclaration(exportMethod(types, function)));
        }
        for (WitResource resource : iface.resources()) {
            if (resource.constructor() != null) {
                type.addMember(
                        StaticJavaParser.parseBodyDeclaration(
                                guestResourceFactory(types, resource)));
            }
        }
        return finish(pkg, "Guest", unit);
    }

    private GeneratedSource guestResourceSource(
            WitTypes types, String pkg, WitInterface iface, WitResource resource) {
        String className = Names.type(resource.name());
        var unit = newUnit(pkg);
        unit.addImport("run.endive.cm.abi.ResourceValue");
        unit.addImport("run.endive.cm.runtime.GuestResource");
        addValueImports(unit, types, resource.methods().stream());

        var type = declare(unit, className);
        type.addImplementedType("AutoCloseable");
        type.setJavadocComment(
                "The WIT resource {@code "
                        + resource.name()
                        + "}, which {@code "
                        + iface.name()
                        + "} implements. Nothing destroys it on the embedder's behalf, so closing"
                        + " one is what runs the guest's destructor.");

        type.addMember(StaticJavaParser.parseBodyDeclaration("private final Guest owner;"));
        type.addMember(
                StaticJavaParser.parseBodyDeclaration("private final ResourceValue handle;"));
        type.addMember(StaticJavaParser.parseBodyDeclaration("private boolean dropped;"));
        type.addMember(
                StaticJavaParser.parseBodyDeclaration(
                        className
                                + "(Guest owner, ResourceValue handle) {\n"
                                + "    this.owner = owner;\n"
                                + "    this.handle = handle;\n}"));

        for (WitFunction method : resource.methods()) {
            StringBuilder body = new StringBuilder();
            body.append("public ").append(signature(types, method, 1)).append(" {\n");
            String call =
                    "owner."
                            + resourceField(resource, method)
                            + ".apply("
                            + String.join(", ", prepend("handle", callArguments(types, method, 1)))
                            + ")";
            if (method.type().hasResult()) {
                body.append("    return ")
                        .append(
                                types.fromComponent(
                                        call + "[0]", method.type().result(), method.scope()))
                        .append(";\n");
            } else {
                body.append("    ").append(call).append(";\n");
            }
            type.addMember(StaticJavaParser.parseBodyDeclaration(body.append("}").toString()));
        }

        type.addMember(
                StaticJavaParser.parseBodyDeclaration(
                        "/** Runs the guest's destructor. Doing so more than once does nothing."
                                + " */\n@Override\npublic void close() {\n"
                                + "    if (!dropped) {\n        dropped = true;\n"
                                + "        GuestResource.drop(handle);\n    }\n}"));
        return finish(pkg, className, unit);
    }

    private String guestResourceFactory(WitTypes types, WitResource resource) {
        String className = Names.type(resource.name());
        WitFunction constructor = resource.constructor();
        return "/** Makes a {@code "
                + resource.name()
                + "} inside the component. */\npublic "
                + className
                + " "
                + Names.member(resource.name())
                + "("
                + parameterList(types, constructor, 0)
                + ") {\n    return new "
                + className
                + "(this, (ResourceValue) this."
                + resourceField(resource, constructor)
                + ".apply("
                + String.join(", ", callArguments(types, constructor, 0))
                + ")[0]);\n}";
    }

    private List<String> enumMembers(String name, WitEnum declared) {
        List<String> members = new ArrayList<>();
        members.add("private final String label;");
        members.add(name + "(String label) {\n    this.label = label;\n}");
        members.add(
                "/** This case as the ABI carries it, which is a variant with no payload. */\n"
                        + "public VariantValue toComponent() {\n"
                        + "    return VariantValue.of(label, null);\n}");
        members.add(
                "/** The case a lifted value names. */\npublic static "
                        + name
                        + " fromComponent(Object value) {\n"
                        + "    String label = ((VariantValue) value).label();\n"
                        + "    for ("
                        + name
                        + " candidate : values()) {\n"
                        + "        if (candidate.label.equals(label)) {\n"
                        + "            return candidate;\n        }\n    }\n"
                        + "    throw new IllegalArgumentException(\"unknown "
                        + declared.name()
                        + ": \" + label);\n}");
        return members;
    }

    // ---------------------------------------------------------------- imports wiring

    private void appendInterface(StringBuilder body, WitInterface imported) {
        WitTypes types = new WitTypes(base);
        String prefix = Names.member(imported.simpleName());
        String builder = prefix + "Builder";

        body.append("    ")
                .append(hostType(imported))
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
                            types.defValTypeSource(
                                    entry.getValue().defValType(), imported.scope(), declared))
                    .append(");\n");
        }

        for (WitResource resource : imported.resources()) {
            appendResource(body, types, imported, resource, prefix, builder, declared);
        }
        for (WitFunction function : imported.functions()) {
            body.append("    ")
                    .append(builder)
                    .append(".addFunction(\"")
                    .append(function.name())
                    .append("\", ")
                    .append(funcTypeSource(types, function, 0, null, declared))
                    .append(", ")
                    .append(importLambda(types, prefix, function, 0))
                    .append(");\n");
        }
        body.append("    values.put(\"")
                .append(imported.name())
                .append("\", ")
                .append(builder)
                .append(".build());\n");
    }

    private void appendResource(
            StringBuilder body,
            WitTypes types,
            WitInterface imported,
            WitResource resource,
            String prefix,
            String builder,
            Map<Integer, String> declared) {
        String type = imported.scope().javaPackage() + "." + Names.type(resource.name());
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
                    .append(
                            funcTypeSource(
                                    types, resource.constructor(), 0, handle + ".own()", declared))
                    .append(", args -> new Object[] {ResourceValue.owned(")
                    .append(handle)
                    .append(".type(), ")
                    .append(table)
                    .append(".add(")
                    .append(prefix)
                    .append('.')
                    .append(Names.member(resource.name()))
                    .append('(')
                    .append(String.join(", ", lambdaArguments(types, resource.constructor(), 0)))
                    .append(")))});\n");
        }
        for (WitFunction method : resource.methods()) {
            body.append("    ")
                    .append(builder)
                    .append(".addFunction(\"[method]")
                    .append(resource.name())
                    .append('.')
                    .append(method.name())
                    .append("\", ")
                    .append(funcTypeSource(types, method, 1, handle + ".borrow()", declared))
                    .append(", ")
                    .append(importLambda(types, table + ".get((ResourceValue) args[0])", method, 1))
                    .append(");\n");
        }
    }

    // ---------------------------------------------------------------- shared pieces

    private CompilationUnit newUnit(String pkg) {
        var unit = new CompilationUnit();
        if (!pkg.isEmpty()) {
            unit.setPackageDeclaration(pkg);
        }
        return unit;
    }

    private com.github.javaparser.ast.body.ClassOrInterfaceDeclaration declare(
            CompilationUnit unit, String name) {
        return unit.addClass(name)
                .setPublic(true)
                .setFinal(true)
                .addSingleMemberAnnotation(Generated.class, new StringLiteralExpr(generatedBy));
    }

    private com.github.javaparser.ast.body.ClassOrInterfaceDeclaration declareInterface(
            CompilationUnit unit, String name) {
        return unit.addInterface(name)
                .setPublic(true)
                .addSingleMemberAnnotation(Generated.class, new StringLiteralExpr(generatedBy));
    }

    private GeneratedSource finish(String pkg, String simpleName, CompilationUnit unit) {
        // Sorted so that regenerating an unchanged world produces an unchanged file.
        unit.getImports().sort(Comparator.comparing(ImportDeclaration::getNameAsString));
        String qualified = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
        return new GeneratedSource(qualified, unit);
    }

    /**
     * A field named like the first segment of a qualified type shadows it, so the field is renamed
     * rather than the reference, which Java gives no way to write unambiguously.
     */
    private String fieldName(String witName) {
        String name = Names.member(witName);
        return shadowedSegments().contains(name) ? name + "_" : name;
    }

    private java.util.Set<String> shadowedSegments() {
        java.util.Set<String> segments = new java.util.HashSet<>();
        for (WitInterface imported : world.importedInterfaces()) {
            segments.add(firstSegment(hostType(imported)));
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            segments.add(firstSegment(guestType(exported)));
        }
        return segments;
    }

    private static String firstSegment(String qualified) {
        int dot = qualified.indexOf('.');
        return dot < 0 ? qualified : qualified.substring(0, dot);
    }

    private String hostType(WitInterface imported) {
        return imported.scope().javaPackage() + ".Host";
    }

    private String guestType(WitInterface exported) {
        return exported.scope().javaPackage() + ".Guest";
    }

    /** Imports for whatever the given functions name in their signatures. */
    private void addValueImports(
            CompilationUnit unit, WitTypes types, Stream<WitFunction> functions) {
        List<WitFunction> all = functions.collect(Collectors.toList());
        if (all.stream().anyMatch(f -> mentions(f, DefValType.Kind.LIST))) {
            unit.addImport("java.util.List");
        }
        if (all.stream().anyMatch(f -> mentions(f, DefValType.Kind.CHAR))) {
            unit.addImport("run.endive.cm.abi.CharValue");
        }
        if (all.stream().anyMatch(f -> mentions(f, DefValType.Kind.U64))) {
            unit.addImport("java.math.BigInteger");
        }
    }

    private void addDescriptorImports(
            CompilationUnit unit, WitTypes types, Stream<WitFunction> functions) {
        List<WitFunction> all = functions.collect(Collectors.toList());
        if (all.stream().anyMatch(f -> f.type().hasResult() || hasParams(f))) {
            unit.addImport("run.endive.cm.runtime.PrimitiveHostTypeDescriptor");
        }
        if (all.stream().anyMatch(f -> !f.type().hasResult())) {
            unit.addImport("run.endive.cm.runtime.VoidHostTypeDescriptor");
        }
        if (all.stream().anyMatch(f -> mentions(f, DefValType.Kind.ENUM))) {
            unit.addImport("run.endive.cm.runtime.VariantHostTypeDescriptor");
        }
        if (all.stream().anyMatch(f -> mentions(f, DefValType.Kind.LIST))) {
            unit.addImport("run.endive.cm.runtime.ListHostTypeDescriptor");
        }
    }

    private static boolean mentions(WitFunction function, DefValType.Kind kind) {
        return WitTypes.kindOf(function.type().result(), function.scope()) == kind
                || function.type().params().stream()
                        .anyMatch(p -> WitTypes.kindOf(p.valType(), function.scope()) == kind);
    }

    private boolean usesKind(DefValType.Kind kind) {
        for (WitInterface imported : world.importedInterfaces()) {
            for (Type declared : compoundTypes(imported).values()) {
                if (declared.defValType().kind() == kind) {
                    return true;
                }
            }
        }
        return false;
    }

    private String funcTypeField(WitTypes types, WitFunction function) {
        return "private static final FuncType "
                + constantName("", function.name())
                + " =\n    "
                + funcTypeSource(types, function, 0, null, Map.of())
                + ";";
    }

    private String funcTypeSource(
            WitTypes types,
            WitFunction function,
            int skipParams,
            String leading,
            Map<Integer, String> declared) {
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
                    .append(types.valTypeSource(param.valType(), function.scope(), declared))
                    .append(").build())");
        }
        if (skipParams == 0 && leading != null) {
            source.append("\n    .withResult(").append(leading).append(")");
        } else if (type.hasResult()) {
            source.append("\n    .withResult(")
                    .append(types.valTypeSource(type.result(), function.scope(), declared))
                    .append(")");
        }
        return source.append("\n    .build()").toString();
    }

    private String importLambda(WitTypes types, String receiver, WitFunction function, int skip) {
        String call =
                receiver
                        + "."
                        + Names.member(function.name())
                        + "("
                        + String.join(", ", lambdaArguments(types, function, skip))
                        + ")";
        if (function.type().hasResult()) {
            return "args -> new Object[] {"
                    + types.toComponent(call, function.type().result(), function.scope())
                    + "}";
        }
        return "args -> {\n        " + call + ";\n        return new Object[0];\n    }";
    }

    private List<String> lambdaArguments(WitTypes types, WitFunction function, int skip) {
        List<LabelValType> params = function.type().params();
        List<String> values = new ArrayList<>();
        for (int i = skip; i < params.size(); i++) {
            values.add(
                    types.fromComponent(
                            "args[" + i + "]", params.get(i).valType(), function.scope()));
        }
        return values;
    }

    private String exportMethod(WitTypes types, WitFunction function) {
        return exportMethod(types, function, Names.member(function.name()));
    }

    /**
     * @param field the field holding the narrowed function, which the world may have had to rename
     */
    private String exportMethod(WitTypes types, WitFunction function, String field) {
        StringBuilder body = new StringBuilder();
        body.append("public ").append(signature(types, function, 0)).append(" {\n");
        String call =
                "this."
                        + field
                        + ".apply("
                        + String.join(", ", callArguments(types, function, 0))
                        + ")";
        if (function.type().hasResult()) {
            body.append("    return ")
                    .append(
                            types.fromComponent(
                                    call + "[0]", function.type().result(), function.scope()))
                    .append(";\n");
        } else {
            body.append("    ").append(call).append(";\n");
        }
        return body.append("}").toString();
    }

    private List<String> callArguments(WitTypes types, WitFunction function, int skip) {
        return function.type().params().stream()
                .skip(skip)
                .map(p -> types.toComponent(Names.member(p.label()), p.valType(), function.scope()))
                .collect(Collectors.toList());
    }

    private String descriptors(
            WitTypes types, WitFunction function, String result, String firstParam) {
        List<String> all = new ArrayList<>();
        FuncType type = function.type();
        all.add(
                result != null
                        ? result
                        : types.descriptorSource(
                                type.hasResult() ? type.result() : null, function.scope()));
        List<LabelValType> params = type.params();
        for (int i = 0; i < params.size(); i++) {
            all.add(
                    i == 0 && firstParam != null
                            ? firstParam
                            : types.descriptorSource(params.get(i).valType(), function.scope()));
        }
        return String.join(", ", all);
    }

    private String signature(WitTypes types, WitFunction function, int skip) {
        return returnType(types, function)
                + " "
                + Names.member(function.name())
                + "("
                + parameterList(types, function, skip)
                + ")";
    }

    private String returnType(WitTypes types, WitFunction function) {
        return function.type().hasResult()
                ? types.javaType(function.type().result(), function.scope())
                : "void";
    }

    private String parameterList(WitTypes types, WitFunction function, int skip) {
        return function.type().params().stream()
                .skip(skip)
                .map(
                        p ->
                                types.javaType(p.valType(), function.scope())
                                        + " "
                                        + Names.member(p.label()))
                .collect(Collectors.joining(", "));
    }

    private Map<Integer, Type> compoundTypes(WitInterface imported) {
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

    private String typeName(WitInterface imported, Type type, int index) {
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

    private static Stream<WitFunction> allImports(WitWorld world) {
        return world.importedInterfaces().stream().flatMap(WorldGenerator::interfaceFunctions);
    }

    private static Stream<WitFunction> interfaceFunctions(WitInterface imported) {
        return Stream.concat(
                imported.functions().stream(),
                imported.resources().stream().flatMap(r -> resourceFunctions(r).stream()));
    }

    private static Stream<WitFunction> allExports(WitWorld world) {
        return Stream.concat(
                world.exports().stream(),
                world.exportedInterfaces().stream().flatMap(WorldGenerator::interfaceFunctions));
    }

    private static List<WitFunction> resourceFunctions(WitResource resource) {
        List<WitFunction> all = new ArrayList<>();
        if (resource.constructor() != null) {
            all.add(resource.constructor());
        }
        all.addAll(resource.methods());
        return all;
    }

    private static String resourceField(WitResource resource, WitFunction function) {
        return Names.member(resource.name()) + Names.type(function.name());
    }

    private static List<String> prepend(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private static String constantName(String prefix, String witName) {
        String qualified = prefix.isEmpty() ? witName : prefix + "-" + witName;
        return qualified.toUpperCase().replace('-', '_') + "_FUNC";
    }

    private static String constantOf(String label) {
        return label.toUpperCase().replace('-', '_');
    }
}
