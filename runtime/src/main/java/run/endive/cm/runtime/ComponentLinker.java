package run.endive.cm.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import run.endive.cm.abi.BuiltinFunctionTypes;
import run.endive.cm.abi.CanonicalAbi;
import run.endive.cm.abi.Direction;
import run.endive.cm.abi.LiftLowerContext;
import run.endive.cm.abi.PostReturn;
import run.endive.cm.abi.ResourceTypeRef;
import run.endive.cm.abi.StringEncoding;
import run.endive.cm.abi.ValueTransfer;
import run.endive.cm.types.Alias;
import run.endive.cm.types.AliasSection;
import run.endive.cm.types.CanonSection;
import run.endive.cm.types.ComponentSection;
import run.endive.cm.types.CoreExportAlias;
import run.endive.cm.types.CoreInlineExport;
import run.endive.cm.types.CoreInlineExportInstanceExpr;
import run.endive.cm.types.CoreInstance;
import run.endive.cm.types.CoreInstanceExpr;
import run.endive.cm.types.CoreInstanceSection;
import run.endive.cm.types.CoreInstantiateArg;
import run.endive.cm.types.CoreInstantiateInstanceExpr;
import run.endive.cm.types.CoreModuleSection;
import run.endive.cm.types.CoreSort;
import run.endive.cm.types.CoreType;
import run.endive.cm.types.CoreTypeSection;
import run.endive.cm.types.CustomSection;
import run.endive.cm.types.Export;
import run.endive.cm.types.ExportAlias;
import run.endive.cm.types.ExportDecl;
import run.endive.cm.types.ExportSection;
import run.endive.cm.types.ExternDesc;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.Import;
import run.endive.cm.types.ImportSection;
import run.endive.cm.types.InlineExport;
import run.endive.cm.types.InlineExportInstanceExpr;
import run.endive.cm.types.Instance;
import run.endive.cm.types.InstanceDecl;
import run.endive.cm.types.InstanceSection;
import run.endive.cm.types.InstanceType;
import run.endive.cm.types.InstantiateArg;
import run.endive.cm.types.InstantiateInstanceExpr;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.OuterAlias;
import run.endive.cm.types.Section;
import run.endive.cm.types.Sort;
import run.endive.cm.types.Type;
import run.endive.cm.types.TypeBound;
import run.endive.cm.types.TypeResolver;
import run.endive.cm.types.TypeSection;
import run.endive.cm.types.TypeSpace;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;
import run.endive.cm.types.canon.Canon;
import run.endive.cm.types.canon.CanonLift;
import run.endive.cm.types.canon.CanonLower;
import run.endive.cm.types.canon.CanonOpt;
import run.endive.cm.types.canon.CanonResource;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.ImportFunction;
import run.endive.runtime.ImportGlobal;
import run.endive.runtime.ImportMemory;
import run.endive.runtime.ImportTable;
import run.endive.runtime.ImportTag;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Machine;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.runtime.TagInstance;
import run.endive.runtime.TrapException;
import run.endive.runtime.WasmFunctionHandle;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;

public final class ComponentLinker {

    private final Function<run.endive.runtime.Instance, Machine> machineFactory;
    private final boolean generateImports;
    private final HostImportGenerator hostImportGenerator = new HostImportGenerator();

    private static final long[] EMPTY_CORE_VALUES = new long[0];
    private static final Object[] EMPTY_COMPONENT_VALUES = new Object[0];
    private static final Map<String, Object> EMPTY_IMPORTS =
            Collections.unmodifiableMap(new LinkedHashMap<>());

    private ComponentLinker(
            Function<run.endive.runtime.Instance, Machine> machineFactory,
            boolean generateImports) {
        this.machineFactory = machineFactory;
        this.generateImports = generateImports;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ComponentInstance instantiate(WasmComponent component) {
        return instantiate(component, EMPTY_IMPORTS);
    }

    public ComponentInstance instantiate(WasmComponent component, Map<String, Object> imports) {
        try {
            return instantiate(component, imports, true, null);
        } catch (WasmEngineException e) {
            throw new LinkageException("Failed to instantiate component: " + e.getMessage(), e);
        }
    }

    private ComponentInstance instantiate(
            WasmComponent component,
            Map<String, Object> imports,
            boolean root,
            ComponentStore parent) {
        ComponentStore store = new ComponentStore(component, root, parent);

        if (!imports.isEmpty()) {
            store.addImports(imports);
        }

        for (Section section : component.sections()) {
            if (section instanceof CoreModuleSection) {
                processCoreModuleSection(store, (CoreModuleSection) section);
            } else if (section instanceof CoreInstanceSection) {
                processCoreInstanceSection(store, (CoreInstanceSection) section);
            } else if (section instanceof AliasSection) {
                processAliasSection(store, (AliasSection) section);
            } else if (section instanceof TypeSection) {
                processTypeSection(store, (TypeSection) section);
            } else if (section instanceof CanonSection) {
                processCanonSection(store, (CanonSection) section);
            } else if (section instanceof ExportSection) {
                processExportSection(store, (ExportSection) section);
            } else if (section instanceof ComponentSection) {
                processComponentSection(store, (ComponentSection) section);
            } else if (section instanceof CoreTypeSection) {
                processCoreTypeSection(store, (CoreTypeSection) section);
            } else if (section instanceof CustomSection) {
                // Custom sections are ignored
            } else if (section instanceof ImportSection) {
                processImportSection(store, (ImportSection) section);
            } else if (section instanceof InstanceSection) {
                processInstanceSection(store, (InstanceSection) section);
            } else {
                throw new LinkageException("Unknown section type: " + section.getClass().getName());
            }
        }

        return store.getInstance();
    }

    private void processCoreTypeSection(ComponentStore store, CoreTypeSection section) {
        for (CoreType type : section.coreTypes()) {
            store.addCoreType(type);
        }
    }

    private void processImportSection(ComponentStore store, ImportSection section) {
        for (Import imp : section.imports()) {
            switch (imp.externDesc().kind()) {
                case TYPE:
                    processTypeImport(store, imp);
                    break;
                case CORE_MODULE:
                    processCoreModuleImport(store, imp);
                    break;
                case INSTANCE:
                    processInstanceImport(store, imp);
                    break;
                case COMPONENT:
                    processComponentImport(store, imp);
                    break;
                case FUNC:
                    processFunctionImport(store, imp);
                    break;
                default:
                    throw new LinkageException(
                            "Import type " + imp.externDesc().kind() + " not supported yet");
            }
        }
    }

    private final class HostImportGenerator {

        private void generateImport(ComponentStore store, Import imp) {
            switch (imp.externDesc().kind()) {
                case TYPE:
                    generateTypeImport(store, imp);
                    break;
                case INSTANCE:
                    generateInstanceImport(store, imp);
                    break;
                case FUNC:
                    generateFunctionImport(store, imp);
                    break;
                default:
                    throw new LinkageException(
                            "Generation of import type "
                                    + imp.externDesc().kind()
                                    + " not supported yet");
            }
        }

        private void generateFunctionImport(ComponentStore store, Import imp) {
            var type = store.getType((int) imp.externDesc().typeIdx());
            if (type.funcType() == null) {
                throw new LinkageException(
                        "Function type not found at index "
                                + imp.externDesc().typeIdx()
                                + " for import '"
                                + imp.name()
                                + "'");
            }

            var funcType = type.funcType();
            var func =
                    ComponentFunctionInstance.builder()
                            .withComponentStore(store)
                            .withFuncType(funcType)
                            .withTypeResolver(store)
                            .withCall((args) -> new Object[0])
                            .build();
            store.addImport(imp.name(), func);
        }

        private void generateInstanceImport(ComponentStore store, Import imp) {
            var type = store.getType((int) imp.externDesc().typeIdx());
            if (type.instanceType() == null) {
                throw new LinkageException(
                        "Instance type not found at index "
                                + imp.externDesc().typeIdx()
                                + " for import '"
                                + imp.name()
                                + "'");
            }
            var hostStore = new ComponentStore(WasmComponent.builder().build(), true);
            for (var decl : type.instanceType().getInstanceDecls()) {
                if (decl.coreType() != null) {
                    hostStore.addCoreType(decl.coreType());
                } else if (decl.type() != null) {
                    hostStore.addType(decl.type());
                } else if (decl.alias() != null) {
                    processAlias(hostStore, decl.alias());
                } else if (decl.exportDecl() != null) {
                    var name = decl.exportDecl().name();
                    var externalDesc = decl.exportDecl().externDesc();
                    if (externalDesc.kind() == ExternDesc.Kind.TYPE) {
                        if (externalDesc.typeBound() == null) {
                            throw new LinkageException(
                                    "External type bound missing for import '" + name + "'");
                        }
                        if (externalDesc.typeBound().kind() == TypeBound.Kind.EQ) {
                            Type exportedType =
                                    hostStore.getType((int) externalDesc.typeBound().typeIdx());
                            hostStore.addType(exportedType);
                            hostStore.addExport(name, exportedType);
                        } else {
                            throw new LinkageException(
                                    "External type bound sub not supported yet for import '"
                                            + name
                                            + "' for import generation");
                        }
                    } else if (externalDesc.kind() == ExternDesc.Kind.INSTANCE) {
                        Type instanceType = store.getType((int) externalDesc.typeIdx());
                        if (instanceType == null) {
                            throw new LinkageException(
                                    "Host instance export of type "
                                            + externalDesc.kind()
                                            + " not yet supported for import '"
                                            + name
                                            + "' for import generation");
                        }
                    } else {
                        throw new LinkageException(
                                "Host instance export of type "
                                        + externalDesc.kind()
                                        + " not yet supported for import generation");
                    }
                }
            }
            var hostInstance = hostStore.getInstance();
            store.addImport(imp.name(), hostInstance);
        }

        private void generateTypeImport(ComponentStore store, Import imp) {
            TypeBound typeBound = imp.externDesc().typeBound();
            if (typeBound.kind() == TypeBound.Kind.EQ) {
                Type type = store.getType((int) typeBound.typeIdx());
                store.addImport(imp.name(), type);
            } else {
                throw new LinkageException(
                        "Type bound kind "
                                + typeBound.kind()
                                + " not supported yet for import generation");
            }
        }
    }

    /**
     * Narrows a supplied import to the sort its declaration calls for.
     *
     * <p>Casting straight to the expected type would detect the same mismatch, but only as a
     * {@link ClassCastException} naming Java classes — which escapes the linker uncaught and
     * says nothing about which import went wrong. The condition is a linkage failure and reads
     * as one here.
     */
    private static <T> T requireImportSort(
            Import imp, Object value, Class<T> type, String expectedSort) {
        if (!type.isInstance(value)) {
            throw new LinkageException(
                    "Import \""
                            + imp.name()
                            + "\" does not match - expected "
                            + expectedSort
                            + " found "
                            + sortOf(value));
        }
        return type.cast(value);
    }

    /**
     * The declaration behind a type arriving as a value. A resource type travels as its runtime
     * identity, because that identity <em>is</em> the type; anything else travels as itself.
     */
    private static Type typeOf(Object value) {
        if (value instanceof ResourceTypeInstance) {
            return ((ResourceTypeInstance) value).type();
        }
        if (value instanceof TypeValue) {
            return ((TypeValue) value).type();
        }
        return value instanceof Type ? (Type) value : null;
    }

    /**
     * The space a type arriving as a value resolves in. A resource type has no indices of its
     * own to resolve, so only an ordinary type needs to say where it was written.
     */
    private static TypeMatcher.Space spaceOf(Object value) {
        return value instanceof TypeValue ? ((TypeValue) value).space() : null;
    }

    /** The space the type at {@code index} of {@code store} resolves in. */
    private static TypeMatcher.Space originOf(ComponentStore store, int index) {
        TypeMatcher.Space origin = store.typeOriginAt(index);
        return origin == null ? store.asMatcherSpace() : origin;
    }

    /** The resource type a value names, or {@code null} if it names an ordinary type. */
    private static ResourceTypeInstance resourceOf(Object value) {
        return value instanceof ResourceTypeInstance ? (ResourceTypeInstance) value : null;
    }

    /** How the Component Model's linkage diagnostics name the sort of a supplied import. */
    private static String sortOf(Object value) {
        if (value instanceof ComponentInstance) {
            return "instance";
        }
        if (value instanceof ComponentFunction) {
            return "func";
        }
        if (value instanceof WasmModule) {
            return "module";
        }
        if (value instanceof ComponentClosure || value instanceof WasmComponent) {
            return "component";
        }
        if (value instanceof ResourceTypeInstance) {
            return "resource";
        }
        if (value instanceof TypeValue) {
            return ((TypeValue) value).type().simpleName();
        }
        if (value instanceof Type) {
            return ((Type) value).simpleName();
        }
        return value == null ? "nothing" : value.getClass().getSimpleName();
    }

    private void processFunctionImport(ComponentStore store, Import imp) {
        var type = store.getType((int) imp.externDesc().typeIdx());
        if (type.funcType() == null) {
            throw new LinkageException(
                    "Function type not found at index "
                            + imp.externDesc().typeIdx()
                            + " for import '"
                            + imp.name()
                            + "'");
        }

        if (store.hasImport(imp.name())) {
            var functionImport =
                    requireImportSort(
                            imp, store.getImport(imp.name()), ComponentFunction.class, "function");
            store.addFunction(functionImport);
        } else {
            throw new LinkageException(
                    "Unable to resolve component function import "
                            + imp.name()
                            + " with description "
                            + imp.externDesc());
        }
    }

    private void processComponentImport(ComponentStore store, Import imp) {
        var type = store.getType((int) imp.externDesc().typeIdx());
        if (type.componentType() == null) {
            throw new LinkageException(
                    "Component type not found at index "
                            + imp.externDesc().typeIdx()
                            + " for import '"
                            + imp.name()
                            + "'");
        }
        if (store.hasImport(imp.name())) {
            Object componentImport = store.getImport(imp.name());
            if (componentImport instanceof WasmComponent) {
                // Supplied by the host, so it encloses nothing and closes over nothing.
                componentImport = new ComponentClosure((WasmComponent) componentImport, null);
            }
            store.addComponent(
                    requireImportSort(imp, componentImport, ComponentClosure.class, "component"));
        } else {
            throw new LinkageException(
                    "Unable to resolve component import "
                            + imp.name()
                            + " with description "
                            + imp.externDesc());
        }
    }

    private void processInstanceImport(ComponentStore store, Import imp) {
        var type = store.getType((int) imp.externDesc().typeIdx());
        if (type.instanceType() == null) {
            throw new LinkageException(
                    "Instance type not found at index "
                            + imp.externDesc().typeIdx()
                            + " for import '"
                            + imp.name()
                            + "'");
        }

        ComponentInstance instanceImport;
        if (store.hasImport(imp.name())) {
            instanceImport =
                    requireImportSort(
                            imp, store.getImport(imp.name()), ComponentInstance.class, "instance");
        } else if (demandsNothing(type.instanceType())) {
            instanceImport = synthesizeEmptyInstance(store, type.instanceType());
        } else {
            throw new LinkageException(
                    "Unable to resolve component instance import "
                            + imp.name()
                            + " with description "
                            + imp.externDesc());
        }
        matchInstanceType(store, imp, type.instanceType(), instanceImport);
        store.addChildInstance(instanceImport);
    }

    /**
     * Whether an instance type asks nothing of whoever supplies it.
     *
     * <p>An instance import is a request for something the embedder holds, but an instance type
     * with no exports describes a value with no observable content — there is nothing a supplier
     * could put in it and nothing the importer could get out. Requiring one to be passed in
     * would be ceremony over an empty box, so the Component Model lets it go unsupplied. The
     * same holds however deeply the emptiness nests: an instance exporting only empty instances
     * is still empty.
     *
     * <p>Declarations that merely <em>define</em> types demand nothing either. It is exports of
     * any other sort — a function, a module, a type the supplier must choose — that make an
     * instance something only the embedder can produce.
     */
    private static boolean demandsNothing(InstanceType type) {
        // Only `type` declarations and type-kinded exports occupy this index space, and the
        // latter are a demand in their own right, so tracking the former alone keeps it aligned.
        List<Type> localTypes = new ArrayList<>();
        for (var decl : type.getInstanceDecls()) {
            switch (decl.kind()) {
                case CORE_TYPE:
                    break;
                case TYPE:
                    localTypes.add(decl.type());
                    break;
                case EXPORT_DECL:
                    {
                        var externDesc = decl.exportDecl().externDesc();
                        if (externDesc.kind() == ExternDesc.Kind.TYPE) {
                            // An `eq` bound already says which type this is, so the supplier
                            // has nothing to choose; a `sub resource` bound is the opposite —
                            // naming a type only the supplier can produce.
                            if (externDesc.typeBound().kind() != TypeBound.Kind.EQ) {
                                return false;
                            }
                            int boundIdx = (int) externDesc.typeBound().typeIdx();
                            if (boundIdx < 0 || boundIdx >= localTypes.size()) {
                                return false;
                            }
                            localTypes.add(localTypes.get(boundIdx));
                            break;
                        }
                        if (externDesc.kind() != ExternDesc.Kind.INSTANCE) {
                            return false;
                        }
                        int idx = (int) externDesc.typeIdx();
                        if (idx < 0 || idx >= localTypes.size()) {
                            return false;
                        }
                        InstanceType nested = localTypes.get(idx).instanceType();
                        if (nested == null || !demandsNothing(nested)) {
                            return false;
                        }
                        break;
                    }
                default:
                    // An alias reaches outside this declaration for something. Rather than
                    // guess that it is satisfiable, leave it to the supplier.
                    return false;
            }
        }
        return true;
    }

    /**
     * Builds the instance an unsupplied {@link #demandsNothing empty} instance import stands
     * for, mirroring whatever nesting of empty instances the declaration describes so that
     * matching it afterwards finds what it expects.
     */
    private ComponentInstance synthesizeEmptyInstance(ComponentStore parent, InstanceType type) {
        var definition = WasmComponent.builder().build();
        var store = new ComponentStore(definition, false, parent);
        // The stand-in mirrors the declaration's own type index space, so that a type written
        // in terms of an earlier slot — a record whose field names the type exported above it,
        // say — still resolves once something looks it up here.
        List<Type> localTypes = new ArrayList<>();
        for (var decl : type.getInstanceDecls()) {
            if (decl.kind() == InstanceDecl.Kind.TYPE) {
                localTypes.add(decl.type());
                store.addType(decl.type());
            } else if (decl.kind() == InstanceDecl.Kind.EXPORT_DECL) {
                var exportDecl = decl.exportDecl();
                var externDesc = exportDecl.externDesc();
                if (externDesc.kind() == ExternDesc.Kind.TYPE) {
                    // An `eq` bound fixes which type this is, so the stand-in can export
                    // exactly that — and must, because an alias may pull it out by name.
                    Type bound = localTypes.get((int) externDesc.typeBound().typeIdx());
                    localTypes.add(bound);
                    store.addType(bound);
                    store.addExport(exportDecl.name(), bound);
                    continue;
                }
                InstanceType nested = localTypes.get((int) externDesc.typeIdx()).instanceType();
                store.addExport(exportDecl.name(), synthesizeEmptyInstance(store, nested));
            }
        }
        return new ComponentInstance(store, definition);
    }

    /**
     * Checks that {@code provider} satisfies the instance type an import declares.
     *
     * <p>The declaration is read in order, building up {@link InstanceTypeSpace the little type
     * index space it defines} as it goes. That ordering is not incidental: a type export earlier
     * in the declaration is what a later {@code (own N)} refers to, and only by matching the
     * earlier one against the provider do we learn which resource type {@code N} actually names.
     * So each declaration is both checked and, where it names a type, bound.
     */
    private void matchInstanceType(
            ComponentStore store,
            Import imp,
            InstanceType instanceType,
            ComponentInstance provider) {
        ComponentStore providerStore = provider.store();
        var space = new InstanceTypeSpace();
        for (var decl : instanceType.getInstanceDecls()) {
            switch (decl.kind()) {
                case CORE_TYPE:
                    // Its own index space, which a `core module` export declaration indexes
                    // into to say what shape of module it wants.
                    space.addCoreType(decl.coreType());
                    break;
                case TYPE:
                    // A type the instance type defines for its own use. Nothing to check
                    // against the provider — it only becomes observable where an export
                    // mentions it.
                    space.addLocalType(decl.type());
                    break;
                case ALIAS:
                    matchInstanceAliasDecl(space, store, providerStore, imp, decl.alias());
                    break;
                case EXPORT_DECL:
                    matchInstanceExportDecl(space, providerStore, imp, decl.exportDecl());
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Instance import type checking for " + decl + " not supported yet");
            }
        }
    }

    private void matchInstanceAliasDecl(
            InstanceTypeSpace space,
            ComponentStore declStore,
            ComponentStore providerStore,
            Import imp,
            Alias alias) {
        if (alias.kind() != Alias.Kind.OUTER) {
            throw new LinkageException(
                    "Instance alias of kind " + alias.kind() + " not supported yet");
        }
        var outerAlias = (OuterAlias) alias;
        if (!matchInstanceOuterAliasDecl(providerStore, declStore, outerAlias)) {
            throw new LinkageException(
                    "Instance alias mismatch for '"
                            + imp.name()
                            + "' with description "
                            + imp.externDesc());
        }
        if (outerAlias.sort().kind() == Sort.Kind.CORE
                && outerAlias.sort().coreSort() == CoreSort.TYPE) {
            // Core types have an index space of their own, which a `core module` export
            // declaration indexes into to say what shape of module it wants.
            space.addCoreType(
                    resolveOuterAliasStore(declStore, outerAlias)
                            .getCoreType((int) outerAlias.index()));
            return;
        }
        if (outerAlias.sort().kind() == Sort.Kind.TYPE) {
            // An aliased type occupies a slot in the instance type's index space just as a
            // declared one does, so later declarations keep referring to the right thing. It
            // still resolves in the space it came from, though: the indices inside it were
            // written against that component's types, not against this declaration's.
            ComponentStore origin = resolveOuterAliasStore(declStore, outerAlias);
            space.addForeignType(
                    origin.getType((int) outerAlias.index()),
                    origin.asMatcherSpace(),
                    origin.resourceTypeAtOrNull((int) outerAlias.index()));
        }
    }

    /**
     * Checks one export a declared instance type requires against what the provider actually
     * exports, binding any type it introduces into {@code space}.
     */
    private void matchInstanceExportDecl(
            InstanceTypeSpace space,
            ComponentStore providerStore,
            Import imp,
            ExportDecl exportDecl) {
        String name = exportDecl.name();
        ExternDesc externDesc = exportDecl.externDesc();
        if (externDesc.kind() == ExternDesc.Kind.TYPE) {
            matchInstanceTypeExportDecl(space, providerStore, imp, exportDecl);
            return;
        }
        if (!providerStore.hasExport(name)) {
            throw instanceExportNotFound(imp, name);
        }
        Object export = providerStore.getExport(name);
        switch (externDesc.kind()) {
            case CORE_MODULE:
                {
                    // A core module export is the module itself, not an instance of one --
                    // CoreModuleInstance is what instantiating a module produces, and never
                    // appears among a component's exports.
                    requireInstanceExport(export instanceof WasmModule, imp, name, export);
                    CoreType declaredModule = space.coreTypeAt((int) externDesc.typeIdx());
                    if (declaredModule.moduleType() == null) {
                        throw new LinkageException(
                                "Instance export '"
                                        + name
                                        + "' of '"
                                        + imp.name()
                                        + "' is declared with a non-module core type");
                    }
                    CoreModuleMatcher.requireSubtype(
                            declaredModule.moduleType(), (WasmModule) export);
                    return;
                }
            case COMPONENT:
                requireInstanceExport(export instanceof ComponentClosure, imp, name, export);
                return;
            case INSTANCE:
                requireInstanceExport(export instanceof ComponentInstance, imp, name, export);
                return;
            case FUNC:
                {
                    requireInstanceExport(export instanceof ComponentFunction, imp, name, export);
                    var provided = (ComponentFunction) export;
                    var slot = space.slotAt((int) externDesc.typeIdx());
                    FuncType declared = slot.type().funcType();
                    if (declared == null) {
                        throw new LinkageException(
                                "Instance export '"
                                        + name
                                        + "' of '"
                                        + imp.name()
                                        + "' is declared with a non-function type");
                    }
                    if (!TypeMatcher.funcTypesMatch(
                            slot.space(),
                            declared,
                            providerStore.asMatcherSpace(),
                            provided.funcType(),
                            !provided.hostProvided())) {
                        throw new LinkageException(
                                "Instance export '"
                                        + name
                                        + "' of '"
                                        + imp.name()
                                        + "' does not match - expected "
                                        + declared
                                        + ", got "
                                        + provided.funcType());
                    }
                    return;
                }
            default:
                throw new IllegalArgumentException("Unhandled export kind: " + externDesc.kind());
        }
    }

    /**
     * Matches a {@code type} export, which both constrains the provider and introduces a name
     * the rest of the instance type can use.
     *
     * <p>A {@code sub resource} bound says only "some resource type goes here", so the provider
     * decides which; an {@code eq} bound names one already fixed, so the provider — if it
     * supplies the export at all — has to agree. Either way, what gets recorded is the runtime
     * resource type, since that identity is what every later {@code own} and {@code borrow} in
     * this declaration will be checked against.
     */
    private void matchInstanceTypeExportDecl(
            InstanceTypeSpace space,
            ComponentStore providerStore,
            Import imp,
            ExportDecl exportDecl) {
        String name = exportDecl.name();
        TypeBound typeBound = exportDecl.externDesc().typeBound();
        switch (typeBound.kind()) {
            case SUB_RESOURCE:
                {
                    if (!providerStore.hasExport(name)) {
                        throw instanceExportNotFound(imp, name);
                    }
                    Object export = providerStore.getExport(name);
                    // Anything that is not a resource type fails the same way, whether it is
                    // some other kind of type or not a type at all — a function, say, which is
                    // what an instance is most likely to be offering under the name.
                    ResourceTypeInstance resourceType = resourceOf(export);
                    if (resourceType == null) {
                        throw new LinkageException(
                                "instance exports do not match - expected resource found "
                                        + sortOf(export));
                    }
                    space.addForeignType(
                            resourceType.type(), providerStore.asMatcherSpace(), resourceType);
                    return;
                }
            case EQ:
                {
                    var bound = space.slotAt((int) typeBound.typeIdx());
                    if (!providerStore.hasExport(name)) {
                        // An `eq` bound pins the type down on its own, so the provider does not
                        // have to supply it under this name as well.
                        space.addForeignType(bound.type(), bound.space(), bound.resourceType());
                        return;
                    }
                    Object export = providerStore.getExport(name);
                    Type exported = typeOf(export);
                    requireInstanceExport(exported != null, imp, name, export);
                    if (bound.resourceType() != null) {
                        if (resourceOf(export) != bound.resourceType()) {
                            throw new LinkageException("mismatched resource types");
                        }
                        space.addForeignType(
                                exported, providerStore.asMatcherSpace(), bound.resourceType());
                        return;
                    }
                    if (!TypeMatcher.typesMatch(
                            bound.space(),
                            bound.type(),
                            providerStore.asMatcherSpace(),
                            exported)) {
                        throw new LinkageException(
                                "Instance export '"
                                        + name
                                        + "' of '"
                                        + imp.name()
                                        + "' does not match - expected "
                                        + bound.type()
                                        + ", got "
                                        + exported);
                    }
                    space.addForeignType(exported, providerStore.asMatcherSpace(), null);
                    return;
                }
            default:
                throw new IllegalArgumentException(
                        "Unhandled type bound kind: " + typeBound.kind());
        }
    }

    private static void requireInstanceExport(
            boolean condition, Import imp, String name, Object export) {
        if (!condition) {
            throw new LinkageException(
                    "Instance export '"
                            + name
                            + "' of '"
                            + imp.name()
                            + "' is of the wrong sort - got "
                            + (export == null ? "nothing" : export.getClass().getSimpleName()));
        }
    }

    private static LinkageException instanceExportNotFound(Import imp, String name) {
        return new LinkageException(
                "Instance export '"
                        + name
                        + "' was not found for '"
                        + imp.name()
                        + "' with description "
                        + imp.externDesc());
    }

    private ComponentStore resolveOuterAliasStore(ComponentStore declStore, OuterAlias alias) {
        if (alias.count() == 0) {
            throw new LinkageException(
                    "Outer alias count 0 could not be resolved for instance outer alias decl");
        }
        // Inside an instance type declaration a count of one already means the enclosing
        // component, so the walk starts one level in.
        return outerScope(declStore, alias, 2);
    }

    /**
     * Walks out {@code alias.count()} enclosing scopes.
     *
     * <p>Following the chain of stores rather than of definitions is what makes an outer alias
     * mean the right thing. A component instantiated more than once has one definition but a
     * store per instantiation, and an alias reaching out of a nested component must land in the
     * instantiation it was reached through — the same definition instantiated with different
     * arguments encloses different things.
     */
    private ComponentStore outerScope(ComponentStore from, OuterAlias alias, int firstLevel) {
        ComponentStore scope = from;
        for (int i = firstLevel; i <= alias.count(); i++) {
            scope = scope.lexicalScope();
            if (scope == null) {
                throw new LinkageException(
                        "Outer alias count " + alias.count() + " failed to resolve");
            }
        }
        return scope;
    }

    private boolean matchInstanceOuterAliasDecl(
            ComponentStore instanceStore, ComponentStore declStore, OuterAlias alias) {
        ComponentStore containingStore = resolveOuterAliasStore(declStore, alias);
        switch (alias.sort().kind()) {
            case CORE:
                CoreSort coreSort = alias.sort().coreSort();
                switch (coreSort) {
                    case MODULE:
                        WasmModule declaredModule =
                                containingStore.getCoreModule((int) alias.index());
                        return instanceStore.getCoreModules().stream()
                                .anyMatch(module -> module.equals(declaredModule));
                    case TYPE:
                        // Reaching out for a core type only names a shape the declarations
                        // below will be written against — it asks the provider for nothing, so
                        // there is nothing here to match. Bringing it into scope is the work,
                        // and matchInstanceAliasDecl does that.
                        containingStore.getCoreType((int) alias.index());
                        return true;
                    default:
                        throw new UnsupportedOperationException(
                                "Outer alias core sort " + coreSort + " not yet supported");
                }
            case COMPONENT:
                WasmComponent declaredComponent =
                        containingStore.getChildComponent((int) alias.index()).definition();
                return instanceStore.getChildComponents().stream()
                        .anyMatch(component -> component.definition().equals(declaredComponent));
            case TYPE:
                Type declaredType = containingStore.getType((int) alias.index());
                return instanceStore.getTypes().stream()
                        .anyMatch(type -> type.equals(declaredType));
            default:
                throw new UnsupportedOperationException(
                        "Outer alias sort " + alias.sort() + " not yet supported");
        }
    }

    private void processCoreModuleImport(ComponentStore store, Import imp) {
        var coreType = store.getCoreType((int) imp.externDesc().typeIdx());
        if (coreType.moduleType() == null) {
            throw new LinkageException(
                    "Core module type not found at index "
                            + imp.externDesc().typeIdx()
                            + " for import '"
                            + imp.name()
                            + "'");
        }
        var moduleImport =
                requireImportSort(imp, store.getImport(imp.name()), WasmModule.class, "module");
        // Should we use coreType.moduleType() to do a type check here?
        store.addCoreModule(moduleImport);
    }

    private void processTypeImport(ComponentStore store, Import imp) {
        TypeBound typeBound = imp.externDesc().typeBound();
        if (typeBound.kind() == TypeBound.Kind.EQ) {
            Type bound = store.getType((int) typeBound.typeIdx());
            if (!store.hasImport(imp.name())) {
                // An `eq` bound says which type this is, so there is nothing left for anyone
                // to decide and nothing to supply. The import resolves to the bound itself.
                store.addType(store.slotAt((int) typeBound.typeIdx()));
                return;
            }
            // Supplying one anyway is allowed, but then it has to agree.
            Object suppliedValue = store.getImport(imp.name());
            Type supplied = typeOf(suppliedValue);
            if (supplied == null) {
                throw new LinkageException(
                        "Import \""
                                + imp.name()
                                + "\" does not match - expected type found "
                                + sortOf(suppliedValue));
            }
            // The supplied type was written elsewhere, so its indices count in that space, not
            // in this one. Comparing both sides against the importer's space would read its
            // field types against whatever this component happens to have at those indices.
            TypeMatcher.Space suppliedSpace = spaceOf(suppliedValue);
            if (suppliedSpace == null) {
                suppliedSpace = store.asMatcherSpace();
            }
            // A resource type is only ever itself: two of them agree when they are the same
            // runtime type, never because their declarations look alike. Structural comparison
            // would accept any other `resource (rep i32)` in its place.
            ResourceTypeInstance boundResource =
                    store.resourceTypeAtOrNull((int) typeBound.typeIdx());
            ResourceTypeInstance suppliedResource = resourceOf(suppliedValue);
            if (boundResource != null || suppliedResource != null) {
                if (boundResource != suppliedResource) {
                    throw new LinkageException("mismatched resource types");
                }
            } else if (!TypeMatcher.typesMatch(
                    store.asMatcherSpace(), bound, suppliedSpace, supplied)) {
                throw new LinkageException(
                        "Type eq bound check failed on import '"
                                + imp.name()
                                + "' - expected "
                                + bound
                                + ", got "
                                + supplied);
            }
            store.addType(supplied, resourceOf(suppliedValue), spaceOf(suppliedValue));
        } else if (typeBound.kind() == TypeBound.Kind.SUB_RESOURCE) {
            var importValue = store.getImport(imp.name());
            ResourceTypeInstance resourceType = resourceOf(importValue);
            if (resourceType == null) {
                throw new LinkageException(
                        "Type sub-resource bound check failed on import '"
                                + imp.name()
                                + "' - expected resource type, got "
                                + sortOf(importValue));
            }
            store.addType(resourceType.type(), resourceType);
        } else {
            throw new LinkageException(
                    "Type bound kind " + typeBound.kind() + " not supported yet");
        }
    }

    private void processInstanceSection(ComponentStore store, InstanceSection section) {
        for (Instance instance : section.instances()) {
            switch (instance.expr().kind()) {
                case INSTANTIATE:
                    instantiateComponent(store, (InstantiateInstanceExpr) instance.expr());
                    break;
                case INLINE_EXPORT:
                    instantiateInlineComponent(store, (InlineExportInstanceExpr) instance.expr());
                    break;
                default:
                    throw new LinkageException(
                            "Unknown instance expression kind: " + instance.expr().kind());
            }
        }
    }

    private void instantiateInlineComponent(ComponentStore store, InlineExportInstanceExpr expr) {
        WasmComponent inlineComponent =
                WasmComponent.builder().build().withParent(store.getComponent());
        ComponentStore inlineStore = new ComponentStore(inlineComponent, false, store);
        for (InlineExport export : expr.inlineExports()) {
            switch (export.sortIdx().sort().kind()) {
                case CORE:
                    var coreSort = export.sortIdx().sort().coreSort();
                    if (coreSort == CoreSort.FUNC) {
                        inlineStore.addExport(
                                export.name(), store.getCoreFunction((int) export.sortIdx().idx()));
                    } else if (coreSort == CoreSort.MODULE) {
                        inlineStore.addExport(
                                export.name(), store.getCoreModule((int) export.sortIdx().idx()));
                    } else {
                        throw new LinkageException(
                                "Import core sort " + coreSort + " not supported yet");
                    }
                    break;
                case FUNC:
                    inlineStore.addExport(
                            export.name(), store.getFunction((int) export.sortIdx().idx()));
                    break;
                case COMPONENT:
                    inlineStore.addExport(
                            export.name(), store.getChildComponent((int) export.sortIdx().idx()));
                    break;
                case TYPE:
                    {
                        int typeIdx = (int) export.sortIdx().idx();
                        ResourceTypeInstance resourceType = store.resourceTypeAtOrNull(typeIdx);
                        inlineStore.addExport(
                                export.name(),
                                resourceType != null ? resourceType : store.getType(typeIdx));
                        break;
                    }
                case INSTANCE:
                    inlineStore.addExport(
                            export.name(), store.getChildInstance((int) export.sortIdx().idx()));
                    break;
                case VALUE:
                    throw new LinkageException("VALUE instantiate args not supported yet");
                default:
                    throw new LinkageException(
                            "Unknown instantiate arg sort kind: " + export.sortIdx().sort().kind());
            }
        }
        ComponentInstance inlineInstance = inlineStore.getInstance();
        store.addChildInstance(inlineInstance);
    }

    private void instantiateComponent(ComponentStore store, InstantiateInstanceExpr expr) {
        ComponentClosure component = store.getChildComponent((int) expr.componentIdx());
        Map<String, Object> imports = new LinkedHashMap<>();
        for (InstantiateArg arg : expr.instantiateArgs()) {
            switch (arg.sortIdx().sort().kind()) {
                case CORE:
                    var coreSort = arg.sortIdx().sort().coreSort();
                    if (coreSort == CoreSort.FUNC) {
                        imports.put(arg.name(), store.getCoreFunction((int) arg.sortIdx().idx()));
                    } else if (coreSort == CoreSort.MODULE) {
                        imports.put(arg.name(), store.getCoreModule((int) arg.sortIdx().idx()));
                    } else {
                        throw new LinkageException(
                                "Import core sort " + coreSort + " not supported yet");
                    }
                    break;
                case FUNC:
                    imports.put(arg.name(), store.getFunction((int) arg.sortIdx().idx()));
                    break;
                case COMPONENT:
                    imports.put(arg.name(), store.getChildComponent((int) arg.sortIdx().idx()));
                    break;
                case TYPE:
                    {
                        int idx = (int) arg.sortIdx().idx();
                        ResourceTypeInstance resourceType = store.resourceTypeAtOrNull(idx);
                        imports.put(
                                arg.name(),
                                resourceType != null
                                        ? resourceType
                                        : new TypeValue(store.getType(idx), originOf(store, idx)));
                        break;
                    }
                case INSTANCE:
                    imports.put(arg.name(), store.getChildInstance((int) arg.sortIdx().idx()));
                    break;
                case VALUE:
                    throw new LinkageException("VALUE instantiate args not supported yet");
                default:
                    throw new LinkageException(
                            "Unknown instantiate arg sort kind: " + arg.sortIdx().sort().kind());
            }
        }
        store.addChildInstance(
                instantiate(component.definition(), imports, false, component.definingScope()));
    }

    public static final class Builder {

        private Function<run.endive.runtime.Instance, Machine> machineFactory;
        private boolean generateImports;

        private Builder() {}

        public Builder withMachineFactory(
                Function<run.endive.runtime.Instance, Machine> machineFactory) {
            this.machineFactory = machineFactory;
            return this;
        }

        public Builder withGenerateImports(boolean generateImports) {
            this.generateImports = generateImports;
            return this;
        }

        public ComponentLinker build() {
            return new ComponentLinker(machineFactory, generateImports);
        }
    }

    private void processCoreModuleSection(ComponentStore store, CoreModuleSection section) {
        store.addCoreModule(section.module());
    }

    private void processCoreInstanceSection(ComponentStore store, CoreInstanceSection section) {
        for (CoreInstance coreInstance : section.instances()) {
            CoreInstanceExpr expr = coreInstance.expr();
            if (expr instanceof CoreInstantiateInstanceExpr) {
                instantiateCoreModule(store, (CoreInstantiateInstanceExpr) expr);
            } else if (expr instanceof CoreInlineExportInstanceExpr) {
                instantiateCoreInlineInstance(store, (CoreInlineExportInstanceExpr) expr);
            } else {
                throw new UnsupportedOperationException(
                        "Core instance expression kind " + expr.kind() + " not yet supported");
            }
        }
    }

    private void instantiateCoreInlineInstance(
            ComponentStore store, CoreInlineExportInstanceExpr expr) {
        var builder = CoreInlineInstance.builder();
        for (CoreInlineExport export : expr.inlineExports()) {
            int idx = (int) export.sortIdx().idx();
            switch (export.sortIdx().sort()) {
                case FUNC:
                    builder.addExport(export.name(), store.getCoreFunction(idx));
                    break;
                case MEMORY:
                    builder.addExport(export.name(), store.getCoreMemory(idx));
                    break;
                case MODULE:
                    builder.addExport(export.name(), store.getCoreModule(idx));
                    break;
                case TABLE:
                    builder.addExport(export.name(), store.getCoreTable(idx));
                    break;
                case GLOBAL:
                    builder.addExport(export.name(), store.getCoreGlobal(idx));
                    break;
                case TAG:
                    builder.addExport(export.name(), store.getCoreTag(idx));
                    break;
                default:
                    throw new LinkageException(
                            ("core inline export of sort "
                                    + export.sortIdx().sort()
                                    + " not yet supported"));
            }
        }
        store.addCoreInstance(builder.build());
    }

    private void instantiateCoreModule(ComponentStore store, CoreInstantiateInstanceExpr expr) {
        int moduleIdx = (int) expr.moduleIdx();
        var module = store.getCoreModule(moduleIdx);

        ImportValues importValues;
        if (expr.instantiateArgs().isEmpty()) {
            importValues = ImportValues.empty();
        } else {
            var builder = ImportValues.builder();
            for (CoreInstantiateArg arg : expr.instantiateArgs()) {
                if (arg.sortIdx().sort() != CoreSort.INSTANCE) {
                    throw new LinkageException("core instantiate args must be of instance sort");
                }
                CoreModuleInstance coreInstance = store.getCoreInstance((int) arg.sortIdx().idx());
                if (coreInstance instanceof CoreEndiveInstance) {
                    var moduleInstance = ((CoreEndiveInstance) coreInstance).getModuleInstance();
                    module.importSection().stream()
                            .filter(i -> i.module().equals(arg.name()))
                            .forEach(
                                    i -> {
                                        switch (i.importType()) {
                                            case FUNCTION:
                                                ImportFunction func =
                                                        ImportFunction.exportAsImport(
                                                                arg.name(),
                                                                i.name(),
                                                                moduleInstance,
                                                                i.name());
                                                builder.addFunction(func);
                                                break;
                                            case MEMORY:
                                                var memory =
                                                        moduleInstance.exports().memory(i.name());
                                                builder.addMemory(
                                                        new ImportMemory(
                                                                arg.name(), i.name(), memory));
                                                break;
                                            case GLOBAL:
                                                var global =
                                                        moduleInstance.exports().global(i.name());
                                                builder.addGlobal(
                                                        new ImportGlobal(
                                                                arg.name(), i.name(), global));
                                                break;
                                            case TABLE:
                                                var table =
                                                        moduleInstance.exports().table(i.name());
                                                builder.addTable(
                                                        new ImportTable(
                                                                arg.name(), i.name(), table));
                                                break;
                                            case TAG:
                                                var tag = moduleInstance.exports().tag(i.name());
                                                builder.addTag(
                                                        new ImportTag(arg.name(), i.name(), tag));
                                                break;
                                            default:
                                                throw new LinkageException(
                                                        "core instantiate module import of type "
                                                                + i.importType()
                                                                + " not yet supported");
                                        }
                                    });
                } else if (coreInstance instanceof CoreInlineInstance) {
                    var inlineInstance = ((CoreInlineInstance) coreInstance);
                    module.importSection().stream()
                            .filter(i -> i.module().equals(arg.name()))
                            .forEach(
                                    i -> {
                                        // An inline instance is a bag of already-resolved
                                        // exports, so each sort is simply handed straight
                                        // through to the import it satisfies.
                                        Object exported = inlineInstance.getExport(i.name());
                                        switch (i.importType()) {
                                            case FUNCTION:
                                                builder.addFunction(
                                                        ((CoreFunction<?>) exported)
                                                                .importFunction(
                                                                        arg.name(), i.name()));
                                                break;
                                            case MEMORY:
                                                builder.addMemory(
                                                        new ImportMemory(
                                                                arg.name(),
                                                                i.name(),
                                                                (Memory) exported));
                                                break;
                                            case GLOBAL:
                                                builder.addGlobal(
                                                        new ImportGlobal(
                                                                arg.name(),
                                                                i.name(),
                                                                (GlobalInstance) exported));
                                                break;
                                            case TABLE:
                                                builder.addTable(
                                                        new ImportTable(
                                                                arg.name(),
                                                                i.name(),
                                                                (TableInstance) exported));
                                                break;
                                            case TAG:
                                                builder.addTag(
                                                        new ImportTag(
                                                                arg.name(),
                                                                i.name(),
                                                                (TagInstance) exported));
                                                break;
                                            default:
                                                throw new LinkageException(
                                                        "core instantiate module import of type "
                                                                + i.importType()
                                                                + " not yet supported");
                                        }
                                    });
                }
            }
            importValues = builder.build();
        }

        run.endive.runtime.Instance.Builder builder =
                run.endive.runtime.Instance.builder(module).withImportValues(importValues);
        if (machineFactory != null) {
            builder.withMachineFactory(machineFactory);
        }
        store.addCoreInstance(new CoreEndiveInstance(builder.build()));
    }

    private void processAliasSection(ComponentStore store, AliasSection section) {
        for (Alias alias : section.aliases()) {
            processAlias(store, alias);
        }
    }

    private void processAlias(ComponentStore store, Alias alias) {
        if (alias instanceof CoreExportAlias) {
            processCoreExportAlias(store, (CoreExportAlias) alias);
        } else if (alias instanceof ExportAlias) {
            processExportAlias(store, (ExportAlias) alias);
        } else if (alias instanceof OuterAlias) {
            processOuterAlias(store, (OuterAlias) alias);
        } else {
            throw new UnsupportedOperationException(
                    "Alias kind " + alias.kind() + " not yet supported");
        }
    }

    private void processExportAlias(ComponentStore store, ExportAlias alias) {
        Sort sort = alias.sort();
        ComponentInstance instance = store.getChildInstance((int) alias.instanceIdx());
        ComponentStore linkedStore = instance.store();

        switch (sort.kind()) {
            case CORE:
                if (sort.coreSort() == null || sort.coreSort() != CoreSort.MODULE) {
                    throw new LinkageException(
                            "CORE export alias only allows module sort but got " + sort.coreSort());
                }
                store.addCoreModule((WasmModule) linkedStore.getExport(alias.name()));
                break;
            case FUNC:
                store.addFunction((ComponentFunction) linkedStore.getExport(alias.name()));
                break;
            case COMPONENT:
                store.addComponent((ComponentClosure) linkedStore.getExport(alias.name()));
                break;
            case INSTANCE:
                store.addChildInstance((ComponentInstance) linkedStore.getExport(alias.name()));
                break;
            case TYPE:
                {
                    Object exported = linkedStore.getExport(alias.name());
                    store.addType(
                            typeOf(exported), resourceOf(exported), linkedStore.asMatcherSpace());
                    break;
                }
            case VALUE:
                throw new LinkageException("VALUE sort not yet supported for export alias");
            default:
                throw new LinkageException("Unknown Sort kind " + sort.kind());
        }
    }

    private void processCoreExportAlias(ComponentStore store, CoreExportAlias alias) {
        Sort sort = alias.sort();
        if (sort.kind() != Sort.Kind.CORE) {
            throw new LinkageException(
                    "Expected CORE sort for core export alias, got " + sort.kind());
        }

        CoreSort coreSort = sort.coreSort();
        int instanceIdx = (int) alias.instanceIdx();
        String name = alias.name();
        CoreModuleInstance coreInstance = store.getCoreInstance(instanceIdx);

        // A core instance is either a module that was instantiated or a bag of exports gathered
        // inline, and either can be aliased through. The inline kind already holds resolved
        // values, so it needs no lookup into an instantiation that never happened.
        if (coreInstance instanceof CoreInlineInstance) {
            aliasInlineCoreExport(store, (CoreInlineInstance) coreInstance, coreSort, name);
            return;
        }
        run.endive.runtime.Instance instance =
                ((CoreEndiveInstance) coreInstance).getModuleInstance();

        switch (coreSort) {
            case FUNC:
                CoreExportFunction fn =
                        new CoreExportFunction(instance.exportType(name), instance.export(name));
                store.addCoreFunction(fn);
                break;
            case MEMORY:
                Memory memory = instance.exports().memory(alias.name());
                store.addCoreMemory(memory);
                break;
            case TABLE:
                TableInstance table = instance.exports().table(alias.name());
                store.addCoreTable(table);
                break;
            case GLOBAL:
                GlobalInstance global = instance.exports().global(alias.name());
                store.addCoreGlobal(global);
                break;
            case TAG:
                TagInstance tag = instance.exports().tag(alias.name());
                store.addCoreTag(tag);
                break;
            default:
                throw new UnsupportedOperationException(
                        "Core export alias for sort " + coreSort + " not yet supported");
        }
    }

    private void aliasInlineCoreExport(
            ComponentStore store, CoreInlineInstance instance, CoreSort coreSort, String name) {
        Object exported = instance.getExport(name);
        switch (coreSort) {
            case FUNC:
                store.addCoreFunction((CoreFunction<?>) exported);
                return;
            case MEMORY:
                store.addCoreMemory((Memory) exported);
                return;
            case TABLE:
                store.addCoreTable((TableInstance) exported);
                return;
            case GLOBAL:
                store.addCoreGlobal((GlobalInstance) exported);
                return;
            case TAG:
                store.addCoreTag((TagInstance) exported);
                return;
            case MODULE:
                store.addCoreModule((WasmModule) exported);
                return;
            default:
                throw new UnsupportedOperationException(
                        "Core export alias for sort " + coreSort + " not yet supported");
        }
    }

    private void processOuterAlias(ComponentStore store, OuterAlias alias) {
        ComponentStore containingStore = outerScope(store, alias, 1);
        switch (alias.sort().kind()) {
            case CORE:
                CoreSort coreSort = alias.sort().coreSort();
                switch (coreSort) {
                    case MODULE:
                        WasmModule module = containingStore.getCoreModule((int) alias.index());
                        store.addCoreModule(module);
                        break;
                    default:
                        throw new UnsupportedOperationException(
                                "Outer alias core sort " + coreSort + " not yet supported");
                }
                break;
            case COMPONENT:
                store.addComponent(containingStore.getChildComponent((int) alias.index()));
                break;
            case TYPE:
                store.addType(
                        containingStore.getType((int) alias.index()),
                        containingStore.resourceTypeAtOrNull((int) alias.index()),
                        originOf(containingStore, (int) alias.index()));
                break;
            default:
                throw new UnsupportedOperationException(
                        "Outer alias sort " + alias.sort() + " not yet supported");
        }
    }

    private void processTypeSection(ComponentStore store, TypeSection section) {
        for (Type type : section.types()) {
            // A resource declaration brings a new resource type into existence, distinct from
            // the one any other instantiation of this component declares, and implemented by
            // this instance.
            store.addType(
                    type, type.resourceType() == null ? null : store.declareResourceType(type));
        }
    }

    private void processCanonSection(ComponentStore store, CanonSection section) {
        for (Canon canon : section.canons()) {
            if (canon instanceof CanonLift) {
                processCanonLift(store, (CanonLift) canon);
            } else if (canon instanceof CanonLower) {
                processCanonLower(store, (CanonLower) canon);
            } else if (canon instanceof CanonResource) {
                processCanonResource(store, (CanonResource) canon);
            } else {
                throw new UnsupportedOperationException(
                        "Canon kind " + canon.kind() + " not yet supported");
            }
        }
    }

    private void processCanonResource(ComponentStore store, CanonResource canon) {
        Type type = store.getType((int) canon.typeIdx());
        if (type.resourceType() == null) {
            throw new LinkageException(
                    "Type at index " + canon.typeIdx() + " is not a resource type");
        }

        var repType = type.resourceType().rep();
        if (!run.endive.wasm.types.ValType.I32.equals(repType)) {
            throw new LinkageException("Resource rep type " + repType + " is not supported");
        }

        ResourceTypeInstance resourceType = store.resourceTypeAt((int) canon.typeIdx());
        switch (canon.kind()) {
            case RESOURCE_NEW:
                processCanonResourceNew(store, resourceType);
                break;
            case RESOURCE_REP:
                processCanonResourceRep(store, resourceType);
                break;
            case RESOURCE_DROP:
                processCanonResourceDrop(store, resourceType);
                break;
            default:
                throw new LinkageException("Canon kind " + canon.kind() + " is not supported yet");
        }
    }

    private void processCanonResourceNew(ComponentStore store, ResourceTypeInstance resourceType) {
        WasmFunctionHandle coreFunc =
                (instance, args) ->
                        new long[] {
                            store.getInstance().add(resourceType, (int) args[0], true, null)
                        };
        store.addCoreFunction(
                new CoreImportFunction(BuiltinFunctionTypes.CANON_RESOURCE_NEW, coreFunc));
    }

    private void processCanonResourceRep(ComponentStore store, ResourceTypeInstance resourceType) {
        WasmFunctionHandle coreFunc =
                (instance, args) -> {
                    int index = (int) args[0];
                    var handle =
                            requireResourceHandle(
                                    store.getInstance().getHandle(index), index, resourceType);
                    return new long[] {handle.rep()};
                };
        store.addCoreFunction(
                new CoreImportFunction(BuiltinFunctionTypes.CANON_RESOURCE_REP, coreFunc));
    }

    private void processCanonResourceDrop(ComponentStore store, ResourceTypeInstance resourceType) {
        WasmFunctionHandle coreFunc =
                (instance, args) -> {
                    int index = (int) args[0];
                    var handle =
                            requireResourceHandle(
                                    store.getInstance().removeHandle(index), index, resourceType);
                    if (handle.numLends() != 0) {
                        throw new TrapException(
                                "cannot drop handle index " + index + " while it is lent");
                    }
                    if (handle.own()) {
                        // Dropping the last owning handle destroys the resource.
                        if (resourceType.hasDtor()) {
                            resourceType.runDtor(handle.rep());
                        }
                    } else {
                        // Dropping a borrow discharges it from the call it was lowered into,
                        // which is what eventually lets that call return.
                        handle.borrowScope().unborrow();
                    }
                    return EMPTY_CORE_VALUES;
                };
        store.addCoreFunction(
                new CoreImportFunction(BuiltinFunctionTypes.CANON_RESOURCE_DROP, coreFunc));
    }

    /**
     * Checks that a handle table index names a resource handle of the expected type. Resource
     * types are compared by identity: two instantiations declaring the same resource are
     * distinct types, and a handle from one is not usable against the other.
     */
    private static ResourceHandle requireResourceHandle(
            Object handle, int index, ResourceTypeInstance expected) {
        if (!(handle instanceof ResourceHandle)) {
            throw new TrapException("handle index " + index + " is not a resource handle");
        }
        var resourceHandle = (ResourceHandle) handle;
        if (resourceHandle.resourceType() != expected) {
            throw new TrapException(
                    "handle index "
                            + index
                            + " used with the wrong type, "
                            + ResourceTypeRef.mismatch(expected, resourceHandle.resourceType()));
        }
        return resourceHandle;
    }

    private static CoreFunction<?> resolveCoreFunction(ComponentStore store, int coreFuncIdx) {
        CoreFunction<?> coreFunc = store.getCoreFunction(coreFuncIdx);
        if (coreFunc == null) {
            throw new LinkageException("Core function at index " + coreFuncIdx + " not found");
        }
        return coreFunc;
    }

    /**
     * The index space {@code typeResolver} stands for.
     *
     * <p>A store knows where each of its slots was written, so a type it hands back that came
     * from somewhere else keeps resolving there. Anything else can only resolve locally, which
     * is all a hand-built resolver ever has to do.
     */
    private static TypeSpace typeSpaceOf(TypeResolver typeResolver) {
        return typeResolver instanceof ComponentStore
                ? ((ComponentStore) typeResolver).asMatcherSpace()
                : TypeSpace.of(typeResolver);
    }

    private static LiftLowerContext processCanonOpts(
            ComponentStore store, TypeResolver typeResolver, List<CanonOpt> opts) {
        var contextBuilder =
                LiftLowerContext.builder()
                        .withTypeResolver(typeResolver)
                        .withTypeSpace(typeSpaceOf(typeResolver))
                        // A handle is an index into the table of the instance this canonical
                        // definition belongs to, so the table comes from this store. The
                        // resource type an `own` or `borrow` names, though, is an index into
                        // the type space its function type was written in — which for `canon
                        // lower` is the callee's, not this one's — so it resolves against the
                        // same resolver as every other type here.
                        .withHandles(store.getInstance())
                        .withResourceTypes(
                                typeResolver instanceof ResourceTypeRef.Resolver
                                        ? (ResourceTypeRef.Resolver) typeResolver
                                        : store);
        for (var opt : opts) {
            switch (opt.kind()) {
                case STRING_ENCODING_UTF8:
                    contextBuilder.withStringEncoding(StringEncoding.UTF8);
                    break;
                case STRING_ENCODING_UTF16:
                    contextBuilder.withStringEncoding(StringEncoding.UTF16);
                    break;
                case STRING_ENCODING_LATIN1_UTF16:
                    contextBuilder.withStringEncoding(StringEncoding.LATIN1_UTF16);
                    break;
                case MEMORY:
                    contextBuilder.withMemory(store.getCoreMemory((int) opt.index()));
                    break;
                case REALLOC:
                    CoreFunction<?> realloc = resolveCoreFunction(store, (int) opt.index());
                    contextBuilder.withRealloc(
                            (oldPtr, oldSize, align, newSize) -> {
                                var result = realloc.apply(oldPtr, oldSize, align, newSize);
                                return (int) result[0];
                            });
                    break;
                case POST_RETURN:
                    CoreFunction<?> postReturn = resolveCoreFunction(store, (int) opt.index());
                    contextBuilder.withPostReturn(postReturn::apply);
                    break;
                case ASYNC:
                    contextBuilder.withAsync(true);
                    break;
                case CALLBACK:
                    // TODO - Look up callback func by index
                    throw new UnsupportedOperationException("Callback canon opt not yet supported");
                default:
                    throw new IllegalArgumentException("Unknown canon opt kind " + opt.kind());
            }
        }
        return contextBuilder.build();
    }

    /** Whether any parameter or result of {@code ft} contains a {@code borrow}. */
    private static boolean containsBorrow(TypeResolver typeResolver, FuncType ft) {
        for (LabelValType p : ft.params()) {
            if (CanonicalAbi.containsBorrow(
                    typeResolver, typeResolver.resolveDefValType(p.valType()))) {
                return true;
            }
        }
        return ft.hasResult()
                && CanonicalAbi.containsBorrow(
                        typeResolver, typeResolver.resolveDefValType(ft.result()));
    }

    private FuncType resolveComponentFuncType(ComponentStore store, int funcIdx) {
        Type type = store.getType(funcIdx);
        if (type.funcType() == null) {
            throw new LinkageException("Type at index " + funcIdx + " is not a function type");
        }
        return type.funcType();
    }

    private void processCanonLower(ComponentStore store, CanonLower lower) {
        int funcIdx = (int) lower.funcIdx().idx();
        ComponentFunction func = store.getFunction(funcIdx);
        if (func == null) {
            throw new LinkageException("Function at index " + funcIdx + " not found");
        }
        FuncType componentFuncType = func.funcType();

        LiftLowerContext callerContext = processCanonOpts(store, func.typeResolver(), lower.opts());

        FunctionType coreFuncType =
                CanonicalAbi.flattenFuncType(callerContext, componentFuncType, Direction.LOWER);

        // Direct call path
        if (func.isLifted()
                && ValueTransfer.isIdentityTransfer(
                        callerContext, func.context(), componentFuncType)
                && coreFuncType.equals(func.liftedFunction().funcType())) {
            CoreFunction<?> callee = func.liftedFunction();
            PostReturn postReturn = func.context().postReturn();
            if (postReturn == null) {
                store.addCoreFunction(callee);
                return;
            }
            store.addCoreFunction(
                    new CoreImportFunction(
                            coreFuncType,
                            (instance, args) -> {
                                long[] results = callee.apply(args);
                                postReturn.call(results != null ? results : EMPTY_CORE_VALUES);
                                return results;
                            }));
            return;
        }

        boolean resultsSpill = componentFuncType.hasResult() && coreFuncType.returns().isEmpty();
        WasmFunctionHandle coreFuncHandle;

        if (func.isLifted()
                && ValueTransfer.canTransfer(callerContext, func.context(), componentFuncType)) {
            var transfer = ValueTransfer.compile(callerContext, func.context(), componentFuncType);
            coreFuncHandle =
                    (instance, args) -> {
                        // Memory copy path
                        long[] calleeArgs = transfer.transferParams(args);
                        long[] calleeResults = func.liftedFunction().apply(calleeArgs);

                        long[] outParam = resultsSpill ? new long[] {args[args.length - 1]} : null;
                        long[] callerResults = transfer.transferResults(calleeResults, outParam);

                        var postReturn = func.context().postReturn();
                        if (postReturn != null) {
                            postReturn.call(
                                    calleeResults != null ? calleeResults : EMPTY_CORE_VALUES);
                        }
                        return callerResults;
                    };
        } else {
            List<ValType> componentFuncParams =
                    componentFuncType.params().stream()
                            .map(LabelValType::valType)
                            .collect(Collectors.toList());
            // Lifting a `borrow` argument out of the caller holds the caller's handle lent for
            // the duration of the call, so that the resource behind it cannot be given away
            // while the callee still has a borrow of it.
            boolean scopesBorrows = containsBorrow(func.typeResolver(), componentFuncType);
            coreFuncHandle =
                    (instance, args) -> {
                        // Full trampoline path
                        Subtask subtask = scopesBorrows ? new Subtask() : null;
                        LiftLowerContext callContext =
                                subtask == null
                                        ? callerContext
                                        : callerContext.withBorrowScope(subtask);
                        try {
                            Object[] liftedArgValues =
                                    CanonicalAbi.liftFlatParams(
                                                    callContext, args, componentFuncParams)
                                            .toArray();
                            Object[] results = func.apply(liftedArgValues);
                            if (!componentFuncType.hasResult()) {
                                return EMPTY_CORE_VALUES;
                            }
                            long[] outParam =
                                    resultsSpill ? new long[] {args[args.length - 1]} : null;
                            return CanonicalAbi.lowerFlatResults(
                                    callContext,
                                    Arrays.asList(results),
                                    List.of(componentFuncType.result()),
                                    outParam);
                        } finally {
                            if (subtask != null) {
                                subtask.releaseLenders();
                            }
                        }
                    };
        }
        store.addCoreFunction(new CoreImportFunction(coreFuncType, coreFuncHandle));
    }

    private void processCanonLift(ComponentStore store, CanonLift lift) {
        int coreFuncIdx = (int) lift.funcIdx().idx();

        CoreFunction<?> coreFunc = store.getCoreFunction(coreFuncIdx);
        if (coreFunc == null) {
            throw new LinkageException("Core function at index " + coreFuncIdx + " not found");
        }
        FuncType componentFuncType = resolveComponentFuncType(store, (int) lift.typeIdx());
        // TODO - Do we need to validate that the component function signature flattens to the same
        //  signature as the core export function? Does wasm-tools validation already do that for
        // us?

        List<ValType> componentFuncParams =
                componentFuncType.params().stream()
                        .map(LabelValType::valType)
                        .collect(Collectors.toList());
        ValType resultValType = componentFuncType.result();

        LiftLowerContext context = processCanonOpts(store, store, lift.opts());

        // Lowering a `borrow` parameter into the callee charges it to this call, which then
        // may not return until the callee has dropped it. Functions with no `borrow` anywhere
        // in their signature need no such bookkeeping, so they skip it entirely.
        boolean scopesBorrows = containsBorrow(store, componentFuncType);

        ComponentFunctionCall call =
                (args) -> {
                    Task task = scopesBorrows ? new Task() : null;
                    LiftLowerContext callContext =
                            task == null ? context : context.withBorrowScope(task);

                    long[] loweredArgs =
                            CanonicalAbi.lowerFlatParams(
                                    callContext, Arrays.asList(args), componentFuncParams);
                    long[] result = coreFunc.apply(loweredArgs);

                    // Lift before post_return, which is free to release the very buffers the
                    // results point into.
                    Object[] lifted =
                            resultValType == null
                                    ? EMPTY_COMPONENT_VALUES
                                    : CanonicalAbi.liftFlatResults(
                                                    callContext, result, List.of(resultValType))
                                            .toArray();

                    if (task != null) {
                        task.requireBorrowsReleased();
                    }
                    if (context.postReturn() != null) {
                        context.postReturn().call(result != null ? result : EMPTY_CORE_VALUES);
                    }
                    return lifted;
                };
        store.addFunction(
                ComponentFunctionInstance.builder()
                        .withComponentStore(store)
                        .withFuncType(componentFuncType)
                        .withTypeResolver(store)
                        .withCall(call)
                        .withLiftLowerContext(context)
                        .withLiftedFunction(coreFunc)
                        .build());
    }

    private void processExportSection(ComponentStore store, ExportSection section) {
        for (Export export : section.exports()) {
            String name = export.name();
            Sort sort = export.sortIdx().sort();
            int idx = (int) export.sortIdx().idx();

            switch (sort.kind()) {
                case CORE:
                    switch (sort.coreSort()) {
                        case MODULE:
                            WasmModule module = store.getCoreModule(idx);
                            store.addCoreModule(module);
                            store.addExport(name, module);
                            break;
                        case FUNC:
                            CoreFunction<?> coreFunc = store.getCoreFunction(idx);
                            store.addCoreFunction(coreFunc);
                            store.addExport(name, coreFunc);
                            break;
                        case MEMORY:
                            Memory memory = store.getCoreMemory(idx);
                            store.addCoreMemory(memory);
                            store.addExport(name, memory);
                            break;
                        default:
                            throw new UnsupportedOperationException(
                                    "Export of core sort "
                                            + sort.coreSort()
                                            + " not yet supported");
                    }
                    break;
                case FUNC:
                    ComponentFunction func = store.getFunction(idx);
                    store.addFunction(func);
                    store.addExport(name, func);
                    break;
                case TYPE:
                    {
                        ComponentStore.TypeSlot slot = store.slotAt(idx);
                        store.addType(slot);
                        store.addExport(
                                name,
                                slot.resourceType() != null ? slot.resourceType() : slot.type());
                        break;
                    }
                case INSTANCE:
                    ComponentInstance instance = store.getChildInstance(idx);
                    store.addChildInstance(instance);
                    store.addExport(name, instance);
                    break;
                case COMPONENT:
                    ComponentClosure component = store.getChildComponent(idx);
                    store.addComponent(component);
                    store.addExport(name, component);
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Export of sort " + sort.kind() + " not yet supported");
            }
        }
    }

    private void processComponentSection(ComponentStore store, ComponentSection section) {
        store.addComponent(new ComponentClosure(section.component(), store));
    }
}
