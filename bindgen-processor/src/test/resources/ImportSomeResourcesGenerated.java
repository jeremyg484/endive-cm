package endive.testing;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.processing.Generated;
import run.endive.cm.abi.ResourceValue;
import run.endive.cm.abi.VariantValue;
import run.endive.cm.runtime.ComponentInstance;
import run.endive.cm.runtime.ComponentLinker;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.runtime.HostInstance;
import run.endive.cm.runtime.HostResource;
import run.endive.cm.runtime.HostResourceTable;
import run.endive.cm.types.EnumType;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;

/**
 * Bindings for the WIT world {@code example:imported-resources/import-some-resources}.
 */
@Generated("run.endive.cm.bindgen.BindgenProcessor")
public final class ImportSomeResources {

    /**
     * The world's imports, which the embedder implements.
     */
    public interface Imports {

        /**
         * The imported interface {@code example:imported-resources/logging}.
         */
        Logging logging();
    }

    /**
     * The imported interface {@code example:imported-resources/logging}.
     */
    public interface Logging {

        /**
         * The enum {@code level}.
         */
        enum Level {

            DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error");

            private final String label;

            Level(String label) {
                this.label = label;
            }

            /**
             * This case as the ABI carries it, which is a variant with no payload.
             */
            public VariantValue toComponent() {
                return VariantValue.of(label, null);
            }

            /**
             * The case a lifted value names.
             */
            public static Level fromComponent(Object value) {
                String label = ((VariantValue) value).label();
                for (Level candidate : values()) {
                    if (candidate.label.equals(label)) {
                        return candidate;
                    }
                }
                throw new IllegalArgumentException("unknown level: " + label);
            }
        }

        /**
         * The resource {@code logger}.
         */
        interface Logger {

            Logging.Level getMaxLevel();

            void setMaxLevel(Logging.Level level);

            void log(Logging.Level level, String msg);

            /**
             * Called when the guest drops an owned handle to this resource.
             */
            default void drop() {
            }
        }

        /**
         * Makes a {@code logger}.
         */
        Logger logger(Logging.Level maxLevel);
    }

    private final ComponentInstance instance;

    private ImportSomeResources(ComponentInstance instance) {
        this.instance = instance;
    }

    /**
     * Instantiates {@code component}, satisfying its imports with {@code imports}.
     */
    public static ImportSomeResources instantiate(ComponentStore store, WasmComponent component, Imports imports) {
        Map<String, Object> values = new LinkedHashMap<>();
        Logging logging = imports.logging();
        HostInstance.Builder loggingBuilder = HostInstance.builder(store);
        ValType loggingLevel = loggingBuilder.declareType(Type.of(EnumType.builder().addLabel("debug").addLabel("info").addLabel("warn").addLabel("error").build()));
        HostResourceTable<Logging.Logger> loggingLoggerTable = new HostResourceTable<>();
        HostResource loggingLogger = loggingBuilder.declareResource(rep -> loggingLoggerTable.drop(rep, Logging.Logger::drop));
        loggingBuilder.addResource("logger", loggingLogger);
        loggingBuilder.addFunction("[constructor]logger", FuncType.builder().addParam(LabelValType.builder().withLabel("max-level").withValType(loggingLevel).build()).withResult(loggingLogger.own()).build(), args -> new Object[] { ResourceValue.owned(loggingLogger.type(), loggingLoggerTable.add(logging.logger(Logging.Level.fromComponent(args[0])))) });
        loggingBuilder.addFunction("[method]logger.get-max-level", FuncType.builder().addParam(LabelValType.builder().withLabel("self").withValType(loggingLogger.borrow()).build()).withResult(loggingLevel).build(), args -> new Object[] { loggingLoggerTable.get((ResourceValue) args[0]).getMaxLevel().toComponent() });
        loggingBuilder.addFunction("[method]logger.set-max-level", FuncType.builder().addParam(LabelValType.builder().withLabel("self").withValType(loggingLogger.borrow()).build()).addParam(LabelValType.builder().withLabel("level").withValType(loggingLevel).build()).build(), args -> {
            loggingLoggerTable.get((ResourceValue) args[0]).setMaxLevel(Logging.Level.fromComponent(args[1]));
            return new Object[0];
        });
        loggingBuilder.addFunction("[method]logger.log", FuncType.builder().addParam(LabelValType.builder().withLabel("self").withValType(loggingLogger.borrow()).build()).addParam(LabelValType.builder().withLabel("level").withValType(loggingLevel).build()).addParam(LabelValType.builder().withLabel("msg").withValType(ValType.builder().withPrimValType(PrimValType.STRING).build()).build()).build(), args -> {
            loggingLoggerTable.get((ResourceValue) args[0]).log(Logging.Level.fromComponent(args[1]), (String) args[2]);
            return new Object[0];
        });
        values.put("example:imported-resources/logging", loggingBuilder.build());
        return new ImportSomeResources(ComponentLinker.builder().build().instantiate(store, component, values));
    }

    /**
     * The instance these bindings call into.
     */
    public ComponentInstance instance() {
        return instance;
    }
}
