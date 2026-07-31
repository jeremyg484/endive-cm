package run.endive.cm.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import run.endive.cm.types.CoreType;
import run.endive.cm.types.Type;
import run.endive.cm.types.TypeResolver;
import run.endive.cm.types.WasmComponent;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.runtime.TagInstance;
import run.endive.wasm.WasmModule;

public final class ComponentStore implements TypeResolver {

    private final boolean root;
    private final ComponentInstance rootInstance;
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
        this.rootInstance = new ComponentInstance(this, component);
        this.root = root;
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
    }

    @Override
    public Type getType(int index) {
        if (index < 0 || index >= types.size()) {
            throw new LinkageException(
                    "Type index " + index + " out of bounds (size " + types.size() + ")");
        }
        return types.get(index);
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
            throw new LinkageException("Export not found: " + name);
        }
        return exports.get(name);
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
            throw new LinkageException("Import not found: " + name);
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
