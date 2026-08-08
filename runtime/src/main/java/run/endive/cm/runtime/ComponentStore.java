package run.endive.cm.runtime;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import run.endive.cm.abi.ResourceTypeRef;
import run.endive.cm.types.CoreType;
import run.endive.cm.types.Type;
import run.endive.cm.types.TypeResolver;
import run.endive.cm.types.WasmComponent;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.runtime.TagInstance;
import run.endive.runtime.TrapException;
import run.endive.wasm.WasmModule;

public final class ComponentStore implements TypeResolver, ResourceTypeRef.Resolver {

    private final boolean root;
    private final ComponentInstance rootInstance;
    private final Map<Type, ResourceTypeInstance> resourceTypes;
    private TypeMatcher.Space matcherSpace;
    private final List<WasmModule> coreModules = new ArrayList<>();
    private final List<CoreType> coreTypes = new ArrayList<>();
    private final List<CoreModuleInstance> coreInstances = new ArrayList<>();
    private final List<CoreFunction<?>> coreFunctions = new ArrayList<>();
    private final List<Memory> coreMemories = new ArrayList<>();
    private final List<TableInstance> coreTables = new ArrayList<>();
    private final List<GlobalInstance> coreGlobals = new ArrayList<>();
    private final List<TagInstance> coreTags = new ArrayList<>();
    private final List<ComponentFunction> functions = new ArrayList<>();
    private final List<Type> types = new ArrayList<>();
    private final List<WasmComponent> childComponents = new ArrayList<>();
    private final List<ComponentInstance> instances = new ArrayList<>();
    private final Map<String, Object> exports = new LinkedHashMap<>();
    private final Map<String, Object> imports = new LinkedHashMap<>();

    public ComponentStore(WasmComponent component, boolean root) {
        this(component, root, null);
    }

    /**
     * @param parent the store instantiating this one, whose resource type identities this store
     *     shares; {@code null} for a store that begins a fresh instantiation
     */
    ComponentStore(WasmComponent component, boolean root, ComponentStore parent) {
        this.rootInstance = new ComponentInstance(this, component);
        this.root = root;
        this.resourceTypes = parent == null ? new IdentityHashMap<>() : parent.resourceTypes;
    }

    /**
     * Records the resource type a declaration brings into existence, if it has not been
     * recorded already.
     *
     * <p>The identities are shared across every store descending from one root instantiation,
     * because a component that imports a resource type has to arrive at the same identity as
     * the component that exported it, and all either store has to go on is the declaration
     * object they both resolve to.
     *
     * <p>This means two instantiations of the same subcomponent within a single root currently
     * share one resource type where the Component Model says they should get distinct ones.
     * Separating them needs the identity to travel with the export value rather than being
     * recovered from the declaration, which is a change to how type imports are matched.
     */
    void declareResourceType(Type type) {
        declareResourceType(type, rootInstance, null);
    }

    private void declareResourceType(Type type, ComponentInstance impl, IntConsumer hostDtor) {
        if (type.resourceType() == null) {
            throw new LinkageException("Type is not a resource type: " + type);
        }
        resourceTypes.computeIfAbsent(
                type, t -> new ResourceTypeInstance(t.resourceType(), this, impl, hostDtor));
    }

    /**
     * Records a resource type the host implements, whose resources are destroyed by calling
     * {@code dtor} rather than by a core function in some component.
     */
    void declareHostResourceType(Type type, IntConsumer dtor) {
        declareResourceType(type, null, requireNonNull(dtor, "dtor"));
    }

    /**
     * Records a resource type this store neither declares nor implements, so that handles of it
     * can still be minted and validated here.
     */
    void adoptResourceType(Type type) {
        declareResourceType(type, null, null);
    }

    @Override
    public ResourceTypeRef resolveResourceType(int typeIdx) {
        return resourceTypeAt(typeIdx);
    }

    /** The runtime identity this store associates with a resource type declaration. */
    ResourceTypeInstance resourceTypeFor(Type type) {
        ResourceTypeInstance resourceType = resourceTypes.get(type);
        if (resourceType == null) {
            throw new LinkageException("Resource type was never brought into existence: " + type);
        }
        return resourceType;
    }

    /** This store's type index space and resource identities, for handing to {@link TypeMatcher}. */
    TypeMatcher.Space asMatcherSpace() {
        if (matcherSpace == null) {
            matcherSpace = TypeMatcher.spaceOf(this, this);
        }
        return matcherSpace;
    }

    /** The runtime identity of the resource type at {@code typeIdx} in this store's index space. */
    ResourceTypeInstance resourceTypeAt(int typeIdx) {
        Type type = getType(typeIdx);
        if (type.resourceType() == null) {
            throw new TrapException("Type at index " + typeIdx + " is not a resource type");
        }
        ResourceTypeInstance resourceType = resourceTypes.get(type);
        if (resourceType == null) {
            throw new TrapException(
                    "Resource type at index " + typeIdx + " was never brought into existence");
        }
        return resourceType;
    }

    boolean isRoot() {
        return root;
    }

    public ComponentInstance getInstance() {
        return rootInstance;
    }

    public WasmComponent getComponent() {
        return rootInstance.definition();
    }

    void addCoreModule(WasmModule module) {
        coreModules.add(module);
    }

    WasmModule getCoreModule(int index) {
        if (index < 0 || index >= coreModules.size()) {
            throw new LinkageException(
                    "Core module index "
                            + index
                            + " out of bounds (size "
                            + coreModules.size()
                            + ")");
        }
        return coreModules.get(index);
    }

    List<WasmModule> getCoreModules() {
        return coreModules;
    }

    void addCoreType(CoreType type) {
        coreTypes.add(type);
    }

    CoreType getCoreType(int index) {
        if (index < 0 || index >= coreTypes.size()) {
            throw new LinkageException(
                    "Core type index " + index + " out of bounds (size " + coreTypes.size() + ")");
        }
        return coreTypes.get(index);
    }

    void addCoreInstance(CoreModuleInstance instance) {
        coreInstances.add(instance);
    }

    CoreModuleInstance getCoreInstance(int index) {
        if (index < 0 || index >= coreInstances.size()) {
            throw new LinkageException(
                    "Core instance index "
                            + index
                            + " out of bounds (size "
                            + coreInstances.size()
                            + ")");
        }
        return coreInstances.get(index);
    }

    void addCoreFunction(CoreFunction<?> function) {
        coreFunctions.add(function);
    }

    CoreFunction<?> getCoreFunction(int index) {
        if (index < 0 || index >= coreFunctions.size()) {
            throw new LinkageException(
                    "Core function index "
                            + index
                            + " out of bounds (size "
                            + coreFunctions.size()
                            + ")");
        }
        return coreFunctions.get(index);
    }

    void addFunction(ComponentFunction function) {
        functions.add(function);
    }

    ComponentFunction getFunction(int index) {
        if (index < 0 || index >= functions.size()) {
            throw new LinkageException(
                    "Component function index "
                            + index
                            + " out of bounds (size "
                            + functions.size()
                            + ")");
        }
        return functions.get(index);
    }

    void addType(Type type) {
        types.add(type);
        if (type.resourceType() != null) {
            // A resource type can enter an index space by being declared, imported or aliased.
            // Only the first of those confers an identity of its own — see
            // declareResourceType, which the type section calls first — so anything arriving by
            // one of the other routes adopts the identity already on record, or, having none,
            // is treated as belonging to no instance here.
            adoptResourceType(type);
        }
    }

    @Override
    public Type getType(int index) {
        if (index < 0 || index >= types.size()) {
            throw new LinkageException(
                    "Type index " + index + " out of bounds (size " + types.size() + ")");
        }
        return types.get(index);
    }

    public List<Type> getTypes() {
        return types;
    }

    void addComponent(WasmComponent component) {
        childComponents.add(component);
    }

    WasmComponent getChildComponent(int index) {
        if (index < 0 || index >= childComponents.size()) {
            throw new LinkageException(
                    "Component index "
                            + index
                            + " out of bounds (size "
                            + childComponents.size()
                            + ")");
        }
        return childComponents.get(index);
    }

    List<WasmComponent> getChildComponents() {
        return childComponents;
    }

    void addChildInstance(ComponentInstance instance) {
        instances.add(instance);
    }

    ComponentInstance getChildInstance(int index) {
        if (index < 0 || index >= instances.size()) {
            throw new LinkageException(
                    "Component instance index "
                            + index
                            + " out of bounds (size "
                            + instances.size()
                            + ")");
        }
        return instances.get(index);
    }

    void addCoreMemory(Memory memory) {
        coreMemories.add(memory);
    }

    Memory getCoreMemory(int index) {
        if (index < 0 || index >= coreMemories.size()) {
            throw new LinkageException(
                    "Core memory index "
                            + index
                            + " out of bounds (size "
                            + coreMemories.size()
                            + ")");
        }
        return coreMemories.get(index);
    }

    void addExport(String name, Object value) {
        exports.put(name, value);
    }

    void addExports(Map<String, Object> exports) {
        this.exports.putAll(exports);
    }

    Object getExport(String name) {
        if (!exports.containsKey(name)) {
            throw new LinkageException("Export was not found: " + name);
        }
        return exports.get(name);
    }

    boolean hasExport(String name) {
        return exports.containsKey(name);
    }

    void addImport(String name, Object importValue) {
        imports.put(name, importValue);
        absorbResourceTypes(importValue);
    }

    void addImports(Map<String, Object> imports) {
        this.imports.putAll(imports);
        for (Object importValue : imports.values()) {
            absorbResourceTypes(importValue);
        }
    }

    /**
     * Takes on the resource type identities of an instance being imported, so that a resource
     * type exported by one instance and imported by another is the same type on both sides.
     *
     * <p>Without this the importer would mint an identity of its own the first time it saw the
     * declaration, and a handle it received from the exporter would then fail every guard it
     * met. Identities already on record win, so absorbing never overwrites a type this store
     * declared itself.
     */
    private void absorbResourceTypes(Object importValue) {
        if (!(importValue instanceof ComponentInstance)) {
            return;
        }
        Map<Type, ResourceTypeInstance> imported =
                ((ComponentInstance) importValue).store().resourceTypes;
        if (imported == resourceTypes) {
            return;
        }
        for (Map.Entry<Type, ResourceTypeInstance> entry : imported.entrySet()) {
            resourceTypes.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    boolean hasImport(String name) {
        return imports.containsKey(name);
    }

    Object getImport(String name) {
        if (!imports.containsKey(name)) {
            throw new LinkageException("Import \"" + name + "\" was not found");
        }
        return imports.get(name);
    }

    void addCoreTable(TableInstance table) {
        coreTables.add(table);
    }

    TableInstance getCoreTable(int index) {
        if (index < 0 || index >= coreTables.size()) {
            throw new LinkageException(
                    "Core table index "
                            + index
                            + " out of bounds (size "
                            + coreTables.size()
                            + ")");
        }
        return coreTables.get(index);
    }

    void addCoreGlobal(GlobalInstance global) {
        coreGlobals.add(global);
    }

    GlobalInstance getCoreGlobal(int index) {
        if (index < 0 || index >= coreGlobals.size()) {
            throw new LinkageException(
                    "Core global index "
                            + index
                            + " out of bounds (size "
                            + coreGlobals.size()
                            + ")");
        }
        return coreGlobals.get(index);
    }

    void addCoreTag(TagInstance tag) {
        coreTags.add(tag);
    }

    TagInstance getCoreTag(int index) {
        if (index < 0 || index >= coreTags.size()) {
            throw new LinkageException(
                    "Core tag index " + index + " out of bounds (size " + coreTags.size() + ")");
        }
        return coreTags.get(index);
    }
}
