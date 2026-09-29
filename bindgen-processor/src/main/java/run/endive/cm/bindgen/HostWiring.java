package run.endive.cm.bindgen;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;

/**
 * Builds the host side of an interface a world imports, as statements inside {@code instantiate}.
 *
 * <p>An interface is built through a local rather than in one chained expression, because a type
 * has to be declared into the instance before a function type can name it, and a resource before
 * an {@code own} or a {@code borrow} can.
 */
final class HostWiring {

    /** The name of the parameter carrying a dropped resource's representation. */
    private static final String REP = "rep";

    private final GeneratedUnit unit;
    private final WitTypes types;
    private final FunctionBindings bindings;

    /** Every type local {@code instantiate} declares, so that no two share a name. */
    private final Set<String> typeLocals = new HashSet<>();

    HostWiring(GeneratedUnit unit, FunctionBindings bindings) {
        this.unit = unit;
        this.types = bindings.types();
        this.bindings = bindings;
    }

    /** The compound types an interface declares, which a host instance has to be told about. */
    static Map<Integer, Type> compoundTypes(WitInterface iface) {
        Map<Integer, Type> found = new LinkedHashMap<>();
        WitScope scope = iface.scope();
        for (int i = 0; i < scope.size(); i++) {
            Type declared = scope.at(i);
            if (isCompound(declared)) {
                found.put(i, declared);
            }
        }
        return found;
    }

    /**
     * Appends the statements building a host instance for {@code imported} and registering it
     * under the name the linker matches.
     *
     * @param values the local collecting what satisfies each import
     */
    void addTo(BlockStmt body, WitInterface imported, String values) {
        Locals locals = new Locals(imported);

        if (imported.needsHost()) {
            body.addStatement(
                    AstBuilders.declare(
                            AstBuilders.type(imported.scope().javaPackage() + ".Host"),
                            locals.host,
                            AstBuilders.call(new NameExpr("imports"), locals.host)));
        }
        body.addStatement(
                AstBuilders.declare(
                        unit.use(QualifiedTypes.HOST_INSTANCE, "Builder"),
                        locals.builder,
                        AstBuilders.call(
                                unit.useName(QualifiedTypes.HOST_INSTANCE),
                                "builder",
                                new NameExpr("store"))));

        declareTypes(body, imported, locals);
        for (WitResource resource : imported.resources()) {
            addResource(body, imported, resource, locals);
        }
        for (WitFunction function : imported.functions()) {
            body.addStatement(
                    AstBuilders.call(
                            locals.builder(),
                            "addFunction",
                            AstBuilders.text(function.name()),
                            bindings.funcType(function, 0, null, locals.declared),
                            bindings.importLambda(new NameExpr(locals.host), function, 0)));
        }
        body.addStatement(
                AstBuilders.call(
                        new NameExpr(values),
                        "put",
                        AstBuilders.text(imported.name()),
                        AstBuilders.call(locals.builder(), "build")));
    }

    /**
     * Declares each compound type into the instance, since a function type names one by index. A
     * named type is exported as well, since a component using it aliases it from the instance.
     */
    private void declareTypes(BlockStmt body, WitInterface imported, Locals locals) {
        WitScope scope = imported.scope();
        for (Map.Entry<Integer, Type> entry : compoundTypes(imported).entrySet()) {
            String preferred = typeName(scope, entry.getValue(), entry.getKey());
            String local =
                    declare(
                            body,
                            scope,
                            entry.getKey(),
                            locals.host + Names.type(preferred),
                            locals);
            locals.declared.put(entry.getKey(), local);
            String name = scope.nameAt(entry.getKey());
            if (name != null) {
                body.addStatement(
                        AstBuilders.call(
                                locals.builder(),
                                "addType",
                                AstBuilders.text(name),
                                new NameExpr(local)));
            }
        }
    }

    /**
     * Declares the type at {@code index} unless it was declared already, and gives back the local
     * holding it.
     *
     * <p>A type used from another interface is declared into this instance too, since the host
     * instance has a type index space of its own. Its definition is numbered by the interface that
     * declared it, so whatever it refers to is declared first, against that interface's space.
     *
     * @param local the preferred name for the local, or {@code null} to derive one from the
     *     declaring scope
     */
    private String declare(BlockStmt body, WitScope scope, int index, String local, Locals locals) {
        Map<Integer, String> declared = locals.declaredIn(scope);
        if (scope.isUsed(index)) {
            String used =
                    declare(
                            body,
                            scope.declaringScope(index),
                            scope.declaringIndex(index),
                            local,
                            locals);
            declared.put(index, used);
            return used;
        }
        String existing = declared.get(index);
        if (existing == null) {
            existing = locals.byType.get(scope.at(index));
        }
        if (existing != null) {
            declared.put(index, existing);
            return existing;
        }
        DefValType defined = scope.at(index).defValType();
        for (ValType reference : WitTypes.references(defined)) {
            if (reference.primValType() == null && isCompound(scope.at(reference.typeIdx()))) {
                declare(body, scope, reference.typeIdx(), null, locals);
            }
        }
        String name =
                Names.free(
                        local != null
                                ? local
                                : locals.host
                                        + Names.type(scope.owner())
                                        + Names.type(typeName(scope, scope.at(index), index)),
                        typeLocals);
        typeLocals.add(name);
        body.addStatement(
                AstBuilders.declare(
                        unit.use(QualifiedTypes.VAL_TYPE),
                        name,
                        AstBuilders.call(
                                locals.builder(),
                                "declareType",
                                types.defValType(defined, scope, declared))));
        locals.byType.put(scope.at(index), name);
        declared.put(index, name);
        return name;
    }

    /**
     * A handle carries an integer rather than an object, so a table maps one to the other and the
     * destructor hands the object to {@code drop} before forgetting it.
     */
    private void addResource(
            BlockStmt body, WitInterface imported, WitResource resource, Locals locals) {
        String implementation = imported.scope().javaPackage() + "." + Names.type(resource.name());
        String table = locals.table(resource);
        String handle = locals.handle(resource);

        body.addStatement(
                AstBuilders.declare(
                        AstBuilders.generic(
                                unit.use(QualifiedTypes.HOST_RESOURCE_TABLE),
                                AstBuilders.type(implementation)),
                        table,
                        AstBuilders.construct(
                                AstBuilders.diamond(
                                        unit.use(QualifiedTypes.HOST_RESOURCE_TABLE)))));
        Expression destructor =
                AstBuilders.lambda(
                        REP,
                        AstBuilders.call(
                                new NameExpr(table),
                                "drop",
                                new NameExpr(REP),
                                AstBuilders.methodReference(
                                        AstBuilders.name(implementation), "drop")));
        body.addStatement(
                AstBuilders.declare(
                        unit.use(QualifiedTypes.HOST_RESOURCE),
                        handle,
                        AstBuilders.call(locals.builder(), "declareResource", destructor)));
        body.addStatement(
                AstBuilders.call(
                        locals.builder(),
                        "addResource",
                        AstBuilders.text(resource.name()),
                        new NameExpr(handle)));

        if (resource.constructor() != null) {
            addConstructor(body, resource, locals);
        }
        for (WitFunction function : resource.statics()) {
            addStatic(body, resource, function, locals);
        }
        for (WitFunction method : resource.methods()) {
            Expression receiver =
                    AstBuilders.call(
                            new NameExpr(table),
                            "get",
                            AstBuilders.cast(
                                    unit.use(QualifiedTypes.RESOURCE_VALUE), bindings.argument(0)));
            body.addStatement(
                    AstBuilders.call(
                            locals.builder(),
                            "addFunction",
                            AstBuilders.text("[method]" + resource.name() + "." + method.name()),
                            bindings.funcType(
                                    method,
                                    1,
                                    AstBuilders.call(new NameExpr(handle), "borrow"),
                                    locals.declared),
                            bindings.importLambda(receiver, method, 1)));
        }
    }

    private void addConstructor(BlockStmt body, WitResource resource, Locals locals) {
        WitFunction constructor = resource.constructor();
        body.addStatement(
                AstBuilders.call(
                        locals.builder(),
                        "addFunction",
                        AstBuilders.text("[constructor]" + resource.name()),
                        bindings.funcType(
                                constructor,
                                0,
                                AstBuilders.call(new NameExpr(locals.handle(resource)), "own"),
                                locals.declared),
                        minting(resource, constructor, Names.member(resource.name()), locals)));
    }

    /**
     * A static function takes no receiver, so what it hands back is what decides its shape. One
     * returning an {@code own} handle to its own resource mints it the way a constructor does, and
     * one returning an ordinary value is wired like any other imported function.
     */
    private void addStatic(
            BlockStmt body, WitResource resource, WitFunction function, Locals locals) {
        String javaName = Names.qualifiedMember(resource.name(), function.name());
        boolean owns = resource.returnsOwnHandle(function);
        Expression result =
                owns ? AstBuilders.call(new NameExpr(locals.handle(resource)), "own") : null;
        Expression implementation =
                owns
                        ? minting(resource, function, javaName, locals)
                        : bindings.importLambda(new NameExpr(locals.host), javaName, function, 0);
        body.addStatement(
                AstBuilders.call(
                        locals.builder(),
                        "addFunction",
                        AstBuilders.text("[static]" + resource.name() + "." + function.name()),
                        bindings.funcType(function, 0, result, locals.declared),
                        implementation));
    }

    /** The lambda putting what the embedder made into the table and handing back a handle to it. */
    private Expression minting(
            WitResource resource, WitFunction function, String javaName, Locals locals) {
        Expression made =
                AstBuilders.call(
                        new NameExpr(locals.host), javaName, bindings.lambdaArguments(function, 0));
        Expression owned =
                AstBuilders.call(
                        unit.useName(QualifiedTypes.RESOURCE_VALUE),
                        "owned",
                        AstBuilders.call(new NameExpr(locals.handle(resource)), "type"),
                        AstBuilders.call(new NameExpr(locals.table(resource)), "add", made));
        return bindings.lambda(AstBuilders.objects(List.of(owned)));
    }

    private static boolean isCompound(Type type) {
        return type != null
                && type.defValType() != null
                && WitTypes.isCompound(type.defValType().kind());
    }

    /** Only the export declaring a type says what it is called, so an unnamed one gets an index. */
    private static String typeName(WitScope scope, Type type, int index) {
        for (int i = 0; i < scope.size(); i++) {
            if (scope.at(i) == type && scope.nameAt(i) != null) {
                return scope.nameAt(i);
            }
        }
        return "type-" + index;
    }

    /**
     * The locals one interface's wiring is written in terms of, all prefixed by the interface so
     * that two of them may declare a type or a resource of one name.
     */
    private static final class Locals {

        private final String host;
        private final String builder;
        private final WitScope scope;
        private final Map<Integer, String> declared = new LinkedHashMap<>();

        /** What types used from other interfaces were declared as, by the scope declaring them. */
        private final Map<WitScope, Map<Integer, String>> elsewhere = new IdentityHashMap<>();

        /** One local per type, however many indices name it. */
        private final Map<Type, String> byType = new IdentityHashMap<>();

        Locals(WitInterface imported) {
            this.host = Names.member(imported.simpleName());
            this.builder = host + "Builder";
            this.scope = imported.scope();
        }

        /** The locals holding the types {@code declaring} numbers, keyed by its indices. */
        Map<Integer, String> declaredIn(WitScope declaring) {
            return declaring == scope
                    ? declared
                    : elsewhere.computeIfAbsent(declaring, s -> new LinkedHashMap<>());
        }

        Expression builder() {
            return new NameExpr(builder);
        }

        String table(WitResource resource) {
            return handle(resource) + "Table";
        }

        String handle(WitResource resource) {
            return host + Names.type(resource.name());
        }
    }
}
