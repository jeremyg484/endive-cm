package run.endive.cm.bindgen;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import run.endive.cm.parser.ComponentParser;
import run.endive.cm.tools.WitParser;
import run.endive.cm.types.ComponentDecl;
import run.endive.cm.types.ComponentType;
import run.endive.cm.types.Export;
import run.endive.cm.types.ExportSection;
import run.endive.cm.types.ExternDesc;
import run.endive.cm.types.ImportDecl;
import run.endive.cm.types.InstanceDecl;
import run.endive.cm.types.Section;
import run.endive.cm.types.Sort;
import run.endive.cm.types.Type;
import run.endive.cm.types.TypeSection;
import run.endive.cm.types.WasmComponent;

/**
 * Reads a world out of WIT text, by way of the binary encoding.
 *
 * <p>A WIT package encodes as a component whose exports name the package's items. A world arrives
 * wrapped twice over, as an exported component type holding one export that names the world under
 * its fully qualified id, so reaching the world's own declarations means stepping through both.
 *
 * <p>Each nesting level restarts its type numbering, so an index in a declaration counts the type
 * declarations preceding it in the same list rather than naming a slot in one flat space.
 */
final class WorldReader {

    private WorldReader() {}

    /**
     * @param world the world to read, or empty when the package declares exactly one
     */
    static WitWorld read(String wit, String world) {
        byte[] encoded;
        try {
            encoded = WitParser.encode(wit);
        } catch (RuntimeException e) {
            throw new BindgenException("WIT could not be encoded: " + e.getMessage(), e);
        }

        WasmComponent pkg =
                ComponentParser.builder()
                        .withValidation(false)
                        .build()
                        .parse(() -> new ByteArrayInputStream(encoded));

        Export item = packageItem(pkg, world);
        Type wrapper = types(pkg).get((int) item.sortIdx().idx());
        if (wrapper.componentType() == null) {
            throw new BindgenException("\"" + item.name() + "\" is not a world");
        }
        return readWorld(item.name(), wrapper.componentType());
    }

    /** The package's top-level item for {@code world}, or its only one when the name is empty. */
    private static Export packageItem(WasmComponent pkg, String world) {
        List<Export> exports = new ArrayList<>();
        for (Section section : pkg.sections()) {
            if (section instanceof ExportSection) {
                exports.addAll(((ExportSection) section).exports());
            }
        }
        if (world.isEmpty()) {
            if (exports.size() != 1) {
                throw new BindgenException(
                        "the package declares "
                                + exports.size()
                                + " items, so a world has to be named. Found "
                                + names(exports));
            }
            return exports.get(0);
        }
        return exports.stream()
                .filter(e -> e.name().equals(world))
                .findFirst()
                .orElseThrow(
                        () ->
                                new BindgenException(
                                        "world \""
                                                + world
                                                + "\" was not found. The package declares "
                                                + names(exports)));
    }

    /**
     * The wrapper holds the world as a type declaration and then exports it under its qualified id.
     */
    private static WitWorld readWorld(String name, ComponentType wrapper) {
        List<Type> declared = new ArrayList<>();
        String qualifiedName = null;
        ComponentType world = null;

        for (ComponentDecl decl : wrapper.getComponentDecls()) {
            InstanceDecl instanceDecl = decl.instanceDecl();
            if (instanceDecl == null) {
                continue;
            }
            if (instanceDecl.kind() == InstanceDecl.Kind.TYPE) {
                declared.add(instanceDecl.type());
            } else if (instanceDecl.exportDecl() != null) {
                ExternDesc desc = instanceDecl.exportDecl().externDesc();
                if (desc.kind() != ExternDesc.Kind.COMPONENT) {
                    throw new BindgenException(
                            "\"" + name + "\" is an interface, which is not yet supported");
                }
                qualifiedName = instanceDecl.exportDecl().name();
                world = componentTypeAt(declared, desc, name);
            }
        }

        if (world == null) {
            throw new BindgenException("world \"" + name + "\" holds no declarations");
        }
        return new WitWorld(name, qualifiedName, imports(world), exports(world));
    }

    private static List<WitFunction> imports(ComponentType world) {
        List<Type> declared = new ArrayList<>();
        List<WitFunction> functions = new ArrayList<>();
        for (ComponentDecl decl : world.getComponentDecls()) {
            track(declared, decl);
            ImportDecl importDecl = decl.importDecl();
            if (importDecl != null) {
                functions.add(function(declared, importDecl.name(), importDecl.externDesc()));
            }
        }
        return functions;
    }

    private static List<WitFunction> exports(ComponentType world) {
        List<Type> declared = new ArrayList<>();
        List<WitFunction> functions = new ArrayList<>();
        for (ComponentDecl decl : world.getComponentDecls()) {
            track(declared, decl);
            InstanceDecl instanceDecl = decl.instanceDecl();
            if (instanceDecl != null && instanceDecl.exportDecl() != null) {
                functions.add(
                        function(
                                declared,
                                instanceDecl.exportDecl().name(),
                                instanceDecl.exportDecl().externDesc()));
            }
        }
        return functions;
    }

    /**
     * Grows the type index space by whatever {@code decl} contributes to it. An alias would also
     * grow it, and silently mis-numbering every index after one is worse than refusing to read it.
     */
    private static void track(List<Type> declared, ComponentDecl decl) {
        InstanceDecl instanceDecl = decl.instanceDecl();
        if (instanceDecl == null) {
            return;
        }
        if (instanceDecl.kind() == InstanceDecl.Kind.TYPE) {
            declared.add(instanceDecl.type());
        } else if (instanceDecl.kind() == InstanceDecl.Kind.ALIAS) {
            Sort sort = instanceDecl.alias().sort();
            if (sort != null && sort.kind() == Sort.Kind.TYPE) {
                throw new BindgenException(
                        "a world using types from an interface is not yet supported");
            }
        }
    }

    private static WitFunction function(List<Type> declared, String name, ExternDesc desc) {
        if (desc.kind() != ExternDesc.Kind.FUNC) {
            throw new BindgenException(
                    "\""
                            + name
                            + "\" is "
                            + describe(desc.kind())
                            + ", and only functions are supported so far");
        }
        Type type = typeAt(declared, desc, name);
        if (type.funcType() == null) {
            throw new BindgenException("\"" + name + "\" does not name a function type");
        }
        return new WitFunction(name, type.funcType());
    }

    private static ComponentType componentTypeAt(
            List<Type> declared, ExternDesc desc, String name) {
        Type type = typeAt(declared, desc, name);
        if (type.componentType() == null) {
            throw new BindgenException("\"" + name + "\" does not name a component type");
        }
        return type.componentType();
    }

    private static Type typeAt(List<Type> declared, ExternDesc desc, String name) {
        int index = (int) desc.typeIdx();
        if (index < 0 || index >= declared.size()) {
            throw new BindgenException(
                    "\"" + name + "\" names type " + index + ", which was never declared");
        }
        return declared.get(index);
    }

    private static String describe(ExternDesc.Kind kind) {
        switch (kind) {
            case INSTANCE:
                return "an interface";
            case TYPE:
                return "a type";
            case VALUE:
                return "a value";
            case COMPONENT:
                return "a component";
            case CORE_MODULE:
                return "a core module";
            default:
                return "not a function";
        }
    }

    /** A WIT package encodes into a single type section, which every index here counts into. */
    private static List<Type> types(WasmComponent pkg) {
        List<Type> all = new ArrayList<>();
        for (Section section : pkg.sections()) {
            if (section instanceof TypeSection) {
                all.addAll(((TypeSection) section).types());
            }
        }
        return all;
    }

    private static String names(List<Export> exports) {
        return exports.stream().map(Export::name).collect(Collectors.joining(", ", "[", "]"));
    }
}
