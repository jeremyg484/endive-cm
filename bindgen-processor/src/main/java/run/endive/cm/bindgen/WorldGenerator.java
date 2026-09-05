package run.endive.cm.bindgen;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.Type;

/**
 * Generates the bindings for one world.
 *
 * <p>The world becomes a class of its own, and each interface it names becomes a Java package
 * mirroring the WIT id, holding the interface plus whatever types it declares. Exports sit under an
 * {@code exports} package, which is what lets a world import and export one name at once.
 */
final class WorldGenerator {

    /** The local {@code instantiate} collects what satisfies each of the world's imports. */
    private static final String VALUES = "values";

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
        InterfaceGenerator interfaces = new InterfaceGenerator(generatedBy);
        for (WitInterface imported : world.importedInterfaces()) {
            imported.scope().withJavaPackage(imported.javaPackage(base, false));
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            exported.scope().withJavaPackage(exported.javaPackage(base, true));
        }
        for (WitInterface imported : world.importedInterfaces()) {
            sources.addAll(interfaces.sources(imported, false));
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            sources.addAll(interfaces.sources(exported, true));
        }
        sources.add(worldSource());
        return sources;
    }

    private GeneratedSource worldSource() {
        String className = Names.type(world.name());
        JavaUnit unit = new JavaUnit(base, generatedBy);
        FunctionBindings bindings = FunctionBindings.forUnit(unit);

        ClassOrInterfaceDeclaration type = unit.addClass(className);
        type.setJavadocComment("Bindings for the WIT world {@code " + world.qualifiedName() + "}.");

        // Only an import needs its type written out, since an export's is read off the instance.
        for (WitFunction imported : world.imports()) {
            type.addFieldWithInitializer(
                    unit.use(Api.FUNC_TYPE),
                    constantName(imported.name()),
                    bindings.funcType(imported, 0, null, Map.of()),
                    Modifier.Keyword.PRIVATE,
                    Modifier.Keyword.STATIC,
                    Modifier.Keyword.FINAL);
        }
        type.addMember(importsInterface(bindings));

        type.addField(
                unit.use(Api.COMPONENT_INSTANCE),
                "instance",
                Modifier.Keyword.PRIVATE,
                Modifier.Keyword.FINAL);
        for (WitFunction exported : world.exports()) {
            type.addField(
                    unit.use(Api.COMPONENT_FUNCTION),
                    fieldName(exported.name()),
                    Modifier.Keyword.PRIVATE,
                    Modifier.Keyword.FINAL);
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            type.addField(
                    Ast.type(guestType(exported)),
                    fieldName(exported.simpleName()),
                    Modifier.Keyword.PRIVATE,
                    Modifier.Keyword.FINAL);
        }

        addConstructor(type, unit, bindings);
        addInstantiate(type, unit, bindings, className);

        MethodDeclaration accessor =
                type.addMethod("instance", Modifier.Keyword.PUBLIC)
                        .setType(unit.use(Api.COMPONENT_INSTANCE));
        accessor.setBody(returning(new NameExpr("instance")));
        accessor.setJavadocComment("The component instance behind these bindings.");

        for (WitFunction exported : world.exports()) {
            type.addMember(
                    bindings.callMethod(
                            exported, 0, Ast.thisField(fieldName(exported.name())), List.of()));
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            MethodDeclaration reader =
                    type.addMethod(Names.member(exported.simpleName()), Modifier.Keyword.PUBLIC)
                            .setType(Ast.type(guestType(exported)));
            reader.setBody(returning(new NameExpr(fieldName(exported.simpleName()))));
            reader.setJavadocComment("The exported interface {@code " + exported.name() + "}.");
        }
        return unit.finish(className);
    }

    /** One embedder object may implement the world and its imported interfaces together. */
    private ClassOrInterfaceDeclaration importsInterface(FunctionBindings bindings) {
        ClassOrInterfaceDeclaration imports = new ClassOrInterfaceDeclaration();
        imports.setInterface(true).setName("Imports").setPublic(true);
        imports.setJavadocComment("The world's imports, which the embedder implements.");
        for (WitFunction imported : world.imports()) {
            imports.addMember(bindings.signature(imported, 0));
        }
        for (WitInterface imported : world.importedInterfaces()) {
            MethodDeclaration reader = new MethodDeclaration();
            reader.setName(Names.member(imported.simpleName()));
            reader.setType(Ast.type(hostType(imported)));
            reader.removeBody();
            reader.setJavadocComment("The imported interface {@code " + imported.name() + "}.");
            imports.addMember(reader);
        }
        return imports;
    }

    /** Each export is narrowed once here, so a mismatch fails at instantiation, not at a call. */
    private void addConstructor(
            ClassOrInterfaceDeclaration type, JavaUnit unit, FunctionBindings bindings) {
        ConstructorDeclaration constructor = type.addConstructor(Modifier.Keyword.PRIVATE);
        constructor.addParameter(unit.use(Api.COMPONENT_INSTANCE), "instance");
        BlockStmt body = constructor.getBody();
        body.addStatement(Ast.assign(Ast.thisField("instance"), new NameExpr("instance")));
        for (WitFunction exported : world.exports()) {
            Expression export =
                    Ast.call(new NameExpr("instance"), "export", Ast.text(exported.name()));
            body.addStatement(
                    Ast.assign(
                            Ast.thisField(fieldName(exported.name())),
                            Ast.call(export, "typed", bindings.descriptors(exported, null, null))));
        }
        for (WitInterface exported : world.exportedInterfaces()) {
            Expression instance =
                    Ast.call(
                            new NameExpr("instance"),
                            "exportedInstance",
                            Ast.text(exported.name()));
            body.addStatement(
                    Ast.assign(
                            Ast.thisField(fieldName(exported.simpleName())),
                            Ast.construct(Ast.type(guestType(exported)), instance)));
        }
    }

    private void addInstantiate(
            ClassOrInterfaceDeclaration type,
            JavaUnit unit,
            FunctionBindings bindings,
            String className) {
        MethodDeclaration method =
                type.addMethod("instantiate", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                        .setType(Ast.type(className));
        method.addParameter(unit.use(Api.COMPONENT_STORE), "store");
        method.addParameter(unit.use(Api.WASM_COMPONENT), "component");
        method.addParameter(Ast.type("Imports"), "imports");
        method.setJavadocComment(
                "Instantiates {@code component}, satisfying its imports with {@code imports}.");
        if (usesKind(DefValType.Kind.LIST)) {
            // A list arrives raw, and only the generated cast names its element type.
            method.addSingleMemberAnnotation(SuppressWarnings.class, Ast.text("unchecked"));
        }

        BlockStmt body = new BlockStmt();
        body.addStatement(
                Ast.declare(
                        Ast.generic(unit.use(Api.MAP), Ast.type("String"), Ast.type("Object")),
                        VALUES,
                        Ast.construct(Ast.diamond(unit.use(Api.LINKED_HASH_MAP)))));
        for (WitFunction imported : world.imports()) {
            Expression function =
                    Ast.call(
                            unit.useName(Api.HOST_FUNCTION),
                            "of",
                            new NameExpr("store"),
                            new NameExpr(constantName(imported.name())),
                            bindings.importLambda(new NameExpr("imports"), imported, 0));
            body.addStatement(
                    Ast.call(new NameExpr(VALUES), "put", Ast.text(imported.name()), function));
        }
        HostWiring wiring = new HostWiring(unit, bindings);
        for (WitInterface imported : world.importedInterfaces()) {
            wiring.addTo(body, imported, VALUES);
        }

        Expression linker = Ast.call(unit.useName(Api.COMPONENT_LINKER), "builder");
        Expression instance =
                Ast.call(
                        Ast.call(linker, "build"),
                        "instantiate",
                        new NameExpr("store"),
                        new NameExpr("component"),
                        new NameExpr(VALUES));
        body.addStatement(new ReturnStmt(Ast.construct(Ast.type(className), instance)));
        method.setBody(body);
    }

    private static BlockStmt returning(Expression value) {
        BlockStmt body = new BlockStmt();
        body.addStatement(new ReturnStmt(value));
        return body;
    }

    /** Whether any imported interface declares a type of {@code kind}. */
    private boolean usesKind(DefValType.Kind kind) {
        for (WitInterface imported : world.importedInterfaces()) {
            for (Type declared : HostWiring.compoundTypes(imported).values()) {
                if (declared.defValType().kind() == kind) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A field named like the first segment of a qualified type shadows it, so the field is renamed
     * rather than the reference, which Java gives no way to write unambiguously.
     */
    private String fieldName(String witName) {
        String name = Names.member(witName);
        return shadowedSegments().contains(name) ? name + "_" : name;
    }

    private Set<String> shadowedSegments() {
        Set<String> segments = new HashSet<>();
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

    private static String hostType(WitInterface imported) {
        return imported.scope().javaPackage() + ".Host";
    }

    private static String guestType(WitInterface exported) {
        return exported.scope().javaPackage() + ".Guest";
    }

    private static String constantName(String witName) {
        return witName.toUpperCase().replace('-', '_') + "_FUNC";
    }
}
