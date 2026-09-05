package run.endive.cm.bindgen;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;

/**
 * The pieces common to every binding for a WIT function, whichever way the call runs.
 *
 * <p>A resource method's first parameter is the borrowed receiver, which Java carries as {@code
 * this}, so most of these take the count of leading parameters to drop.
 */
final class FunctionBindings {

    /** The name of the lambda parameter carrying an imported call's arguments. */
    private static final String ARGS = "args";

    private final JavaUnit unit;
    private final WitTypes types;

    private FunctionBindings(JavaUnit unit, WitTypes types) {
        this.unit = unit;
        this.types = types;
    }

    static FunctionBindings forUnit(JavaUnit unit) {
        return new FunctionBindings(unit, new WitTypes(unit));
    }

    /** The type mapping these bindings are written against. */
    WitTypes types() {
        return types;
    }

    /** The Java signature of {@code function}, with neither modifiers nor a body. */
    MethodDeclaration signature(WitFunction function, int skip) {
        MethodDeclaration method = new MethodDeclaration();
        method.setName(Names.member(function.name()));
        method.setType(returnType(function));
        method.removeBody();
        return addParameters(method, function, skip);
    }

    /**
     * Adds {@code function}'s parameters to a method whose name and return type are given rather
     * than read off the function, which is how a resource's constructor is bound, since neither
     * the Java name nor the handle it returns is anything its own type says.
     */
    MethodDeclaration addParameters(MethodDeclaration method, WitFunction function, int skip) {
        for (LabelValType param : parameters(function, skip)) {
            method.addParameter(
                    types.javaType(param.valType(), function.scope()), Names.member(param.label()));
        }
        return method;
    }

    /**
     * A method calling the component through {@code callee}, converting what crosses in each
     * direction.
     *
     * @param leading arguments the call takes ahead of the function's own, such as a receiver
     */
    MethodDeclaration callMethod(
            WitFunction function, int skip, Expression callee, List<Expression> leading) {
        MethodDeclaration method = signature(function, skip).setPublic(true);
        List<Expression> arguments = new ArrayList<>(leading);
        arguments.addAll(callArguments(function, skip));

        BlockStmt body = new BlockStmt();
        Expression call = Ast.call(callee, "apply", arguments);
        if (function.type().hasResult()) {
            body.addStatement(
                    new ReturnStmt(
                            types.fromComponent(
                                    Ast.element(call, 0),
                                    function.type().result(),
                                    function.scope())));
        } else {
            body.addStatement(call);
        }
        return method.setBody(body);
    }

    /** The lambda satisfying an imported function, whose parameter carries the lowered call. */
    LambdaExpr lambda(Expression body) {
        return Ast.lambda(ARGS, body);
    }

    /** One argument an imported call arrives with, before any conversion. */
    Expression argument(int index) {
        return Ast.element(new NameExpr(ARGS), index);
    }

    /** The lambda handing an imported call to the embedder. */
    LambdaExpr importLambda(Expression receiver, WitFunction function, int skip) {
        Expression call =
                Ast.call(receiver, Names.member(function.name()), lambdaArguments(function, skip));
        if (function.type().hasResult()) {
            Expression lifted = types.toComponent(call, function.type().result(), function.scope());
            return lambda(Ast.objects(List.of(lifted)));
        }
        BlockStmt body = new BlockStmt();
        body.addStatement(call);
        body.addStatement(new ReturnStmt(Ast.objects(List.of())));
        return Ast.lambda(ARGS, body);
    }

    /** Arguments handed to the embedder, converted from what the ABI carries. */
    List<Expression> lambdaArguments(WitFunction function, int skip) {
        List<LabelValType> params = function.type().params();
        List<Expression> values = new ArrayList<>();
        for (int i = skip; i < params.size(); i++) {
            values.add(types.fromComponent(argument(i), params.get(i).valType(), function.scope()));
        }
        return values;
    }

    /** Arguments handed to the component, converted from the Java values a caller passes. */
    List<Expression> callArguments(WitFunction function, int skip) {
        List<Expression> values = new ArrayList<>();
        for (LabelValType param : parameters(function, skip)) {
            values.add(
                    types.toComponent(
                            new NameExpr(Names.member(param.label())),
                            param.valType(),
                            function.scope()));
        }
        return values;
    }

    /** A descriptor for a handle, which is carried by its value rather than by its Java class. */
    Expression resourceDescriptor() {
        return types.resourceDescriptor();
    }

    /**
     * The descriptors {@link run.endive.cm.runtime.ComponentFunction#typed} checks against the
     * component's own type, the result first.
     *
     * @param result the descriptor for a handle the function returns, or {@code null} for none
     * @param receiver the descriptor for a borrowed receiver, or {@code null} for none
     */
    List<Expression> descriptors(WitFunction function, Expression result, Expression receiver) {
        FuncType type = function.type();
        List<Expression> all = new ArrayList<>();
        all.add(
                result != null
                        ? result
                        : types.descriptor(
                                type.hasResult() ? type.result() : null, function.scope()));
        List<LabelValType> params = type.params();
        for (int i = 0; i < params.size(); i++) {
            all.add(
                    i == 0 && receiver != null
                            ? receiver
                            : types.descriptor(params.get(i).valType(), function.scope()));
        }
        return all;
    }

    /**
     * Rebuilds {@code function}'s type, for declaring it into a host instance.
     *
     * @param leading the type of a receiver or of a returned handle, neither of which the
     *     function's own type can name
     * @param declared the locals holding the compound types the enclosing instance declared
     */
    Expression funcType(
            WitFunction function, int skip, Expression leading, Map<Integer, String> declared) {
        FuncType type = function.type();
        Expression builder = Ast.call(unit.useName(Api.FUNC_TYPE), "builder");
        if (skip > 0) {
            builder = Ast.call(builder, "addParam", param(type.params().get(0).label(), leading));
        }
        for (LabelValType param : parameters(function, skip)) {
            Expression valType = types.valType(param.valType(), function.scope(), declared);
            builder = Ast.call(builder, "addParam", param(param.label(), valType));
        }
        if (skip == 0 && leading != null) {
            builder = Ast.call(builder, "withResult", leading);
        } else if (type.hasResult()) {
            builder =
                    Ast.call(
                            builder,
                            "withResult",
                            types.valType(type.result(), function.scope(), declared));
        }
        return Ast.call(builder, "build");
    }

    private Expression param(String label, Expression valType) {
        Expression builder = Ast.call(unit.useName(Api.LABEL_VAL_TYPE), "builder");
        builder = Ast.call(builder, "withLabel", Ast.text(label));
        return Ast.call(Ast.call(builder, "withValType", valType), "build");
    }

    private Type returnType(WitFunction function) {
        FuncType type = function.type();
        return type.hasResult() ? types.javaType(type.result(), function.scope()) : new VoidType();
    }

    private static List<LabelValType> parameters(WitFunction function, int skip) {
        List<LabelValType> params = function.type().params();
        return params.subList(skip, params.size());
    }
}
