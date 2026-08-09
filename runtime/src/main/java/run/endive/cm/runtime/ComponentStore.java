package run.endive.cm.runtime;

import java.util.ArrayList;
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
    private final ComponentStore lexicalScope;
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
    private final List<ResourceTypeInstance> typeResources = new ArrayList<>();
    private final List<TypeMatcher.Space> typeOrigins = new ArrayList<>();
    private final List<ComponentClosure> childComponents = new ArrayList<>();
    private final List<ComponentInstance> instances = new ArrayList<>();
    private final Map<String, Object> exports = new LinkedHashMap<>();
    private final Map<String, Object> imports = new LinkedHashMap<>();

    public ComponentStore(WasmComponent component, boolean root) {
        this(component, root, null);
    }

    /**
     * @param parent the store this one was written inside, which its outer aliases resolve
     *     against and whose resource type identities it shares; {@code null} for a store that
     *     begins a fresh instantiation
     */
    ComponentStore(WasmComponent component, boolean root, ComponentStore parent) {
        this.rootInstance = new ComponentInstance(this, component);
        this.root = root;
        this.lexicalScope = parent;
    }

    /**
     * Brings a new resource type into existence, implemented by this instance.
     *
     * <p>Resource types are generative: this is called once per declaration per
     * <em>instantiation</em>, so two instantiations of one component get two types however
     * identical their declarations. The caller records the result in the type index space,
     * which is what carries it from here on.
     */
    ResourceTypeInstance declareResourceType(Type type) {
        return new ResourceTypeInstance(type, this, rootInstance, null);
    }

    /**
     * Brings a resource type the host implements into existence, whose resources are destroyed
     * by calling {@code dtor} rather than by a core function in some component. A {@code null}
     * dtor means dropping one does nothing observable.
     */
    ResourceTypeInstance declareHostResourceType(Type type, IntConsumer dtor) {
        return new ResourceTypeInstance(type, this, null, dtor);
    }

    @Override
    public ResourceTypeRef resolveResourceType(int typeIdx) {
        return resourceTypeAt(typeIdx);
    }

    /** This store's type index space and resource identities, for handing to {@link TypeMatcher}. */
    TypeMatcher.Space asMatcherSpace() {
        if (matcherSpace == null) {
            matcherSpace = new StoreSpace();
        }
        return matcherSpace;
    }

    /**
     * Resolves this store's type indices, handing each type back with the space <em>its</em>
     * indices count in.
     *
     * <p>Usually that is this store, but a type that arrived from somewhere else — imported,
     * aliased, re-exported — was written against the index space it came from and still means
     * what it meant there. Following the recorded origin rather than assuming this one is what
     * lets a record defined in one component be compared against a declaration in another.
     */
    private final class StoreSpace implements TypeMatcher.Space {

        @Override
        public TypeMatcher.Resolved resolve(run.endive.cm.types.ValType valType) {
            if (valType.primValType() != null) {
                return new TypeMatcher.Resolved(valType.primValType(), this);
            }
            int index = valType.typeIdx();
            Type type = getType(index);
            if (type.defValType() == null) {
                throw new LinkageException(
                        "Type index " + index + " must resolve to a value type but got " + type);
            }
            TypeMatcher.Space origin = typeOrigins.get(index);
            return new TypeMatcher.Resolved(type.defValType(), origin == null ? this : origin);
        }

        @Override
        public ResourceTypeRef resourceType(int typeIdx) {
            return resourceTypeAt(typeIdx);
        }
    }

    /** The runtime identity of the resource type at {@code typeIdx} in this store's index space. */
    ResourceTypeInstance resourceTypeAt(int typeIdx) {
        ResourceTypeInstance resourceType = resourceTypeAtOrNull(typeIdx);
        if (resourceType == null) {
            throw new TrapException("Type at index " + typeIdx + " is not a resource type");
        }
        return resourceType;
    }

    /**
     * The runtime identity at {@code typeIdx}, or {@code null} if that slot holds an ordinary
     * type.
     *
     * <p>Identity lives on the slot rather than on the declaration, because one declaration can
     * reach a single index space more than once carrying different identities — aliasing the
     * same resource out of two instantiations of the component that declares it does exactly
     * that.
     */
    ResourceTypeInstance resourceTypeAtOrNull(int typeIdx) {
        if (typeIdx < 0 || typeIdx >= typeResources.size()) {
            throw new LinkageException(
                    "Type index " + typeIdx + " out of bounds (size " + typeResources.size() + ")");
        }
        return typeResources.get(typeIdx);
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
        addType(type, null, null);
    }

    void addType(Type type, ResourceTypeInstance resourceType) {
        addType(type, resourceType, null);
    }

    /**
     * Appends a type to the index space, together with the resource type it names if it names
     * one. Every route a resource type takes into a space — declared here, imported, aliased,
     * re-exported — has to bring its identity along, since that identity is the type.
     */
    void addType(Type type, ResourceTypeInstance resourceType, TypeMatcher.Space origin) {
        types.add(type);
        typeResources.add(resourceType);
        typeOrigins.add(origin);
    }

    /** The space the type at {@code index} resolves in, or {@code null} if it resolves here. */
    TypeMatcher.Space typeOriginAt(int index) {
        if (index < 0 || index >= typeOrigins.size()) {
            throw new LinkageException(
                    "Type index " + index + " out of bounds (size " + typeOrigins.size() + ")");
        }
        return typeOrigins.get(index);
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

    /** The scope this component was written inside; {@code null} at the top of a tree. */
    ComponentStore lexicalScope() {
        return lexicalScope;
    }

    void addComponent(ComponentClosure component) {
        childComponents.add(component);
    }

    ComponentClosure getChildComponent(int index) {
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

    List<ComponentClosure> getChildComponents() {
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
    }

    void addImports(Map<String, Object> imports) {
        this.imports.putAll(imports);
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
