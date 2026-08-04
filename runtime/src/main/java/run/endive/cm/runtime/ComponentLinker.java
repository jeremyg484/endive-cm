package run.endive.cm.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import run.endive.cm.abi.BuiltinFunctionTypes;
import run.endive.cm.abi.CanonicalAbi;
import run.endive.cm.abi.Direction;
import run.endive.cm.abi.LiftLowerContext;
import run.endive.cm.abi.StringEncoding;
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
import run.endive.cm.types.ExportSection;
import run.endive.cm.types.ExternDesc;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.Import;
import run.endive.cm.types.ImportSection;
import run.endive.cm.types.InlineExport;
import run.endive.cm.types.InlineExportInstanceExpr;
import run.endive.cm.types.Instance;
import run.endive.cm.types.InstanceSection;
import run.endive.cm.types.InstantiateArg;
import run.endive.cm.types.InstantiateInstanceExpr;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.OuterAlias;
import run.endive.cm.types.ResourceType;
import run.endive.cm.types.Section;
import run.endive.cm.types.Sort;
import run.endive.cm.types.Type;
import run.endive.cm.types.TypeBound;
import run.endive.cm.types.TypeSection;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;
import run.endive.cm.types.canon.Canon;
import run.endive.cm.types.canon.CanonLift;
import run.endive.cm.types.canon.CanonLower;
import run.endive.cm.types.canon.CanonOpt;
import run.endive.cm.types.canon.CanonResource;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.ImportFunction;
import run.endive.runtime.ImportMemory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Machine;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.runtime.TagInstance;
import run.endive.runtime.WasmFunctionHandle;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;

public final class ComponentLinker {

    private final Map<WasmComponent, ComponentStore> stores = new HashMap<>();

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
            return instantiate(component, imports, true);
        } catch (WasmEngineException e) {
            throw new LinkageException("Failed to instantiate component", e);
        }
    }

    private ComponentInstance instantiate(
            WasmComponent component, Map<String, Object> imports, boolean root) {
        ComponentStore store = new ComponentStore(component, root);
        stores.put(component, store);

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
            if (store.isRoot() && generateImports) {
                hostImportGenerator.generateImport(store, imp);
            }
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
            var func = new ComponentFunctionInstance(store, funcType, (args) -> new Object[0]);
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
                    } else {
                        throw new LinkageException(
                                "Host instance export of type "
                                        + ExternDesc.Kind.TYPE
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
            var functionImport = (ComponentFunction) store.getImport(imp.name());
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
            var componentImport = (WasmComponent) store.getImport(imp.name());
            store.addComponent(componentImport);
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

        if (store.hasImport(imp.name())) {
            var instanceImport = (ComponentInstance) store.getImport(imp.name());
            store.addChildInstance(instanceImport);
        } else {
            throw new LinkageException(
                    "Unable to resolve component instance import "
                            + imp.name()
                            + " with description "
                            + imp.externDesc());
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
        var moduleImport = (WasmModule) store.getImport(imp.name());
        // Should we use coreType.moduleType() to do a type check here?
        store.addCoreModule(moduleImport);
    }

    private void processTypeImport(ComponentStore store, Import imp) {
        TypeBound typeBound = imp.externDesc().typeBound();
        if (typeBound.kind() == TypeBound.Kind.EQ) {
            Type typeCheck = store.getType((int) typeBound.typeIdx());
            var importValue = store.getImport(imp.name());
            if (!(importValue instanceof Type) || !typeCheck.equals(importValue)) {
                throw new LinkageException(
                        "Type eq bound check failed on import '"
                                + imp.name()
                                + "' - expected "
                                + typeCheck
                                + ", got "
                                + importValue);
            }
            store.addType((Type) importValue);
        } else if (typeBound.kind() == TypeBound.Kind.SUB_RESOURCE) {
            var importValue = store.getImport(imp.name());
            if (!(importValue instanceof Type) || ((Type) importValue).resourceType() == null) {
                throw new LinkageException(
                        "Type sub-resource bound check failed on import '"
                                + imp.name()
                                + "' - expected resource type, got "
                                + importValue);
            }
            store.addType((Type) importValue);
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
        ComponentStore inlineStore = new ComponentStore(inlineComponent, false);
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
                    inlineStore.addExport(
                            export.name(), store.getType((int) export.sortIdx().idx()));
                    break;
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
        WasmComponent component = store.getChildComponent((int) expr.componentIdx());
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
                    imports.put(arg.name(), store.getType((int) arg.sortIdx().idx()));
                    break;
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
        store.addChildInstance(instantiate(component, imports, false));
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
                                        switch (i.importType()) {
                                            case FUNCTION:
                                                var exportedFunc =
                                                        inlineInstance.getExport(i.name());
                                                CoreFunction<?> func =
                                                        (CoreFunction<?>) exportedFunc;
                                                builder.addFunction(
                                                        func.importFunction(arg.name(), i.name()));
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
                store.addComponent((WasmComponent) linkedStore.getExport(alias.name()));
                break;
            case INSTANCE:
                store.addChildInstance((ComponentInstance) linkedStore.getExport(alias.name()));
                break;
            case TYPE:
                store.addType((Type) linkedStore.getExport(alias.name()));
                break;
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
        run.endive.runtime.Instance instance =
                ((CoreEndiveInstance) store.getCoreInstance(instanceIdx)).getModuleInstance();

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

    private void processOuterAlias(ComponentStore store, OuterAlias alias) {
        WasmComponent containingComponent = store.getComponent();
        for (int i = 1; i <= alias.count(); i++) {
            if (containingComponent.parent() == null) {
                throw new LinkageException(
                        "Outer alias count " + alias.count() + " failed to resolve");
            }
            containingComponent = containingComponent.parent();
        }
        if (!stores.containsKey(containingComponent)) {
            throw new LinkageException(
                    "Outer alias count "
                            + alias.count()
                            + " failed to resolve to a root component that is associated with a"
                            + " store");
        }
        ComponentStore containingStore = stores.get(containingComponent);
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
                WasmComponent component = containingStore.getChildComponent((int) alias.index());
                store.addComponent(component);
                break;
            case TYPE:
                Type type = containingStore.getType((int) alias.index());
                store.addType(type);
                break;
            default:
                throw new UnsupportedOperationException(
                        "Outer alias sort " + alias.sort() + " not yet supported");
        }
    }

    private void processTypeSection(ComponentStore store, TypeSection section) {
        for (Type type : section.types()) {
            store.addType(type);
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

        switch (canon.kind()) {
            case RESOURCE_NEW:
                processCanonResourceNew(store, type.resourceType());
                break;
            case RESOURCE_REP:
                processCanonResourceRep(store, type.resourceType());
                break;
            case RESOURCE_DROP:
                processCanonResourceDrop(store, type.resourceType());
                break;
            default:
                throw new LinkageException("Canon kind " + canon.kind() + " is not supported yet");
        }
    }

    private void processCanonResourceNew(ComponentStore store, ResourceType resourceType) {
        WasmFunctionHandle coreFunc =
                (instance, args) -> {
                    var resourceHandle =
                            new ResourceHandle(resourceType, (int) args[0], true, null);
                    return new long[] {store.getInstance().addHandle(resourceHandle)};
                };
        store.addCoreFunction(
                new CoreImportFunction(BuiltinFunctionTypes.CANON_RESOURCE_NEW, coreFunc));
    }

    private void processCanonResourceRep(ComponentStore store, ResourceType resourceType) {
        WasmFunctionHandle coreFunc =
                (instance, args) -> {
                    var handle = store.getInstance().getHandle((int) args[0]);
                    if (!(handle instanceof ResourceHandle)) {
                        throw new IllegalStateException(
                                "Handle at slot " + args[0] + " is not a resource handle");
                    }
                    var resourceHandle = (ResourceHandle) handle;
                    if (!resourceType.equals(resourceHandle.resourceType())) {
                        throw new IllegalStateException(
                                "Handle at slot "
                                        + args[0]
                                        + " has different resource type - got: "
                                        + resourceHandle.resourceType()
                                        + ", expected: "
                                        + resourceType);
                    }
                    return new long[] {resourceHandle.rep()};
                };
        store.addCoreFunction(
                new CoreImportFunction(BuiltinFunctionTypes.CANON_RESOURCE_REP, coreFunc));
    }

    private void processCanonResourceDrop(ComponentStore store, ResourceType resourceType) {
        WasmFunctionHandle coreFunc =
                (instance, args) -> {
                    var handle = store.getInstance().removeHandle((int) args[0]);
                    if (!(handle instanceof ResourceHandle)) {
                        throw new IllegalStateException(
                                "Handle at slot " + args[0] + " is not a resource handle");
                    }
                    var resourceHandle = (ResourceHandle) handle;
                    if (!resourceType.equals(resourceHandle.resourceType())) {
                        throw new IllegalStateException(
                                "Handle at slot "
                                        + args[0]
                                        + " has different resource type - got: "
                                        + resourceHandle.resourceType()
                                        + ", expected: "
                                        + resourceType);
                    }
                    if (resourceHandle.numLends() != 0) {
                        throw new IllegalStateException(
                                "Handle at slot " + args[0] + " is currently lent");
                    }
                    if (resourceHandle.own()) {
                        if (resourceHandle.borrowScope() != null) {
                            throw new IllegalStateException(
                                    "Owned handle at slot "
                                            + args[0]
                                            + " should have no borrow scope");
                        }
                        if (resourceType.hasDtor()) {
                            throw new UnsupportedOperationException(
                                    "Owned handles with destructors are not supported yet");
                        }
                    } else {
                        resourceHandle.borrowScope().unborrow();
                    }
                    return new long[0];
                };
        store.addCoreFunction(
                new CoreImportFunction(BuiltinFunctionTypes.CANON_RESOURCE_DROP, coreFunc));
    }

    private static CoreExportFunction resolveCoreExportFunction(
            ComponentStore store, int coreFuncIdx) {
        CoreFunction<?> coreFunc = store.getCoreFunction(coreFuncIdx);
        if (coreFunc == null) {
            throw new LinkageException("Core function at index " + coreFuncIdx + " not found");
        }
        if (!(coreFunc instanceof CoreExportFunction)) {
            throw new LinkageException(
                    "Core function at index " + coreFuncIdx + " is not an exported function");
        }
        return (CoreExportFunction) coreFunc;
    }

    private static LiftLowerContext processCanonOpts(ComponentStore store, List<CanonOpt> opts) {
        var contextBuilder = LiftLowerContext.builder().withTypeResolver(store);
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
                // TODO - How do we get to the needed core references?
                case MEMORY:
                    contextBuilder.withMemory(store.getCoreMemory((int) opt.index()));
                    break;
                case REALLOC:
                    CoreExportFunction realloc =
                            resolveCoreExportFunction(store, (int) opt.index());
                    contextBuilder.withRealloc(
                            (oldPtr, oldSize, align, newSize) -> {
                                var result =
                                        realloc.getFunctionInstance()
                                                .apply(oldPtr, oldSize, align, newSize);
                                return (int) result[0];
                            });
                    break;
                case POST_RETURN:
                    CoreExportFunction postReturn =
                            resolveCoreExportFunction(store, (int) opt.index());
                    contextBuilder.withPostReturn(postReturn.getFunctionInstance());
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

        LiftLowerContext context = processCanonOpts(store, lower.opts());

        FunctionType coreFuncType =
                CanonicalAbi.flattenFuncType(context, componentFuncType, Direction.LOWER);

        List<ValType> componentFuncParams =
                componentFuncType.params().stream()
                        .map(LabelValType::valType)
                        .collect(Collectors.toList());

        WasmFunctionHandle coreFuncHandle =
                (instance, args) -> {
                    Object[] liftedArgValues =
                            CanonicalAbi.liftFlatParams(context, args, componentFuncParams)
                                    .toArray();
                    Object[] results = func.apply(liftedArgValues);
                    return componentFuncType.result() == null
                            ? EMPTY_CORE_VALUES
                            : CanonicalAbi.lowerFlatResults(
                                    context,
                                    Arrays.asList(results),
                                    List.of(componentFuncType.result()),
                                    null);
                };
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

        LiftLowerContext context = processCanonOpts(store, lift.opts());

        ComponentFunctionCall call =
                (args) -> {
                    long[] loweredArgs =
                            CanonicalAbi.lowerFlatParams(
                                    context, Arrays.asList(args), componentFuncParams);
                    long[] result =
                            coreFunc.apply(loweredArgs);

                    if (context.postReturn() != null) {
                        context.postReturn().apply(result);
                    }

                    return resultValType == null
                            ? EMPTY_COMPONENT_VALUES
                            : CanonicalAbi.liftFlatResults(context, result, List.of(resultValType))
                            .toArray();
                };
        store.addFunction(new ComponentFunctionInstance(store, componentFuncType, call));
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
                    Type type = store.getType(idx);
                    store.addType(type);
                    store.addExport(name, type);
                    break;
                case INSTANCE:
                    ComponentInstance instance = store.getChildInstance(idx);
                    store.addChildInstance(instance);
                    store.addExport(name, instance);
                    break;
                case COMPONENT:
                    WasmComponent component = store.getChildComponent(idx);
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
        store.addComponent(section.component());
    }
}
