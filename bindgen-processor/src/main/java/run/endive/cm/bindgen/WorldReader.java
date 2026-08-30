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
import run.endive.cm.types.InstanceType;
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
        Type wrapper = typeSpace(pkg).get((int) item.sortIdx().idx());
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
                            "\"" + name + "\" is an interface rather than a world");
                }
                qualifiedName = instanceDecl.exportDecl().name();
                world = componentTypeAt(declared, desc, name);
            }
        }

        if (world == null) {
            throw new BindgenException("world \"" + name + "\" holds no declarations");
        }

        return build(name, qualifiedName, world);
    }

    /**
     * One walk of the world's declarations, filling every list. The type index space grows as the
     * walk goes, so an index in a declaration is resolved against what came before it.
     */
    private static WitWorld build(String name, String qualifiedName, ComponentType world) {
        List<Type> declared = new ArrayList<>();
        List<WitFunction> importedFunctions = new ArrayList<>();
        List<WitInterface> importedInterfaces = new ArrayList<>();
        List<WitFunction> exportedFunctions = new ArrayList<>();
        List<WitInterface> exportedInterfaces = new ArrayList<>();

        for (ComponentDecl decl : world.getComponentDecls()) {
            track(declared, decl);
            ImportDecl importDecl = decl.importDecl();
            if (importDecl != null) {
                collect(
                        declared,
                        importDecl.name(),
                        importDecl.externDesc(),
                        importedFunctions,
                        importedInterfaces);
                continue;
            }
            InstanceDecl instanceDecl = decl.instanceDecl();
            if (instanceDecl != null && instanceDecl.exportDecl() != null) {
                collect(
                        declared,
                        instanceDecl.exportDecl().name(),
                        instanceDecl.exportDecl().externDesc(),
                        exportedFunctions,
                        exportedInterfaces);
            }
        }
        return new WitWorld(
                name,
                qualifiedName,
                importedFunctions,
                importedInterfaces,
                exportedFunctions,
                exportedInterfaces);
    }

    /** A world reaches a function directly and an interface as an instance, both ways round. */
    private static void collect(
            List<Type> declared,
            String name,
            ExternDesc desc,
            List<WitFunction> functions,
            List<WitInterface> interfaces) {
        if (desc.kind() == ExternDesc.Kind.INSTANCE) {
            interfaces.add(readInterface(name, instanceTypeAt(declared, desc, name)));
        } else {
            functions.add(function(declared, name, desc));
        }
    }

    /** An interface's functions are the functions its instance type exports. */
    private static WitInterface readInterface(String name, InstanceType type) {
        List<Type> declared = new ArrayList<>();
        List<WitFunction> functions = new ArrayList<>();
        for (InstanceDecl decl : type.getInstanceDecls()) {
            if (decl.kind() == InstanceDecl.Kind.TYPE) {
                declared.add(decl.type());
            } else if (decl.kind() == InstanceDecl.Kind.ALIAS) {
                throw new BindgenException(
                        "interface \""
                                + name
                                + "\" uses types from elsewhere, which is not yet supported");
            } else if (decl.exportDecl() != null) {
                functions.add(
                        function(
                                declared,
                                decl.exportDecl().name(),
                                decl.exportDecl().externDesc()));
            }
        }
        return new WitInterface(name, functions);
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

    private static InstanceType instanceTypeAt(List<Type> declared, ExternDesc desc, String name) {
        Type type = typeAt(declared, desc, name);
        if (type.instanceType() == null) {
            throw new BindgenException("\"" + name + "\" does not name an interface");
        }
        return type.instanceType();
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

    /**
     * The package component's type index space.
     *
     * <p>It grows both by the types the package defines and by the exports naming them, so an
     * exported world sits at a higher index than its position among the defined types. Walking the
     * sections in order is what puts an index on the type it actually names.
     */
    private static List<Type> typeSpace(WasmComponent pkg) {
        List<Type> space = new ArrayList<>();
        for (Section section : pkg.sections()) {
            if (section instanceof TypeSection) {
                space.addAll(((TypeSection) section).types());
            } else if (section instanceof ExportSection) {
                for (Export export : ((ExportSection) section).exports()) {
                    if (export.sortIdx().sort().kind() == Sort.Kind.TYPE) {
                        space.add(space.get((int) export.sortIdx().idx()));
                    }
                }
            }
        }
        return space;
    }

    private static String names(List<Export> exports) {
        return exports.stream().map(Export::name).collect(Collectors.joining(", ", "[", "]"));
    }
}
