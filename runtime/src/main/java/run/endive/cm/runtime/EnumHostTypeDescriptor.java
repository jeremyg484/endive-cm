package run.endive.cm.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import run.endive.cm.types.EnumType;
import run.endive.cm.types.FlagsType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;

public final class EnumHostTypeDescriptor extends HostTypeDescriptor {

    private final Class<?> hostType;

    private EnumHostTypeDescriptor(Class<?> hostType) {
        Objects.requireNonNull(hostType, "hostType");
        if (!Enum.class.isAssignableFrom(hostType)) {
            throw new IllegalArgumentException("hostType must be an enum");
        }
        this.hostType = hostType;
    }

    public static EnumHostTypeDescriptor forClass(Class<?> hostType) {
        return new EnumHostTypeDescriptor(hostType);
    }

    @Override
    boolean matches(Class<?> hostType) {
        return this.hostType == hostType;
    }

    @Override
    boolean isCompatibleWith(ComponentInstance instance, ValType componentType) {
        if (componentType.primValType() != null) {
            return false;
        }
        Type type = instance.getType(componentType.typeIdx());
        if (type == null || type.defValType() == null) {
            return false;
        }
        List<String> labels = null;
        switch (type.defValType().kind()) {
            case ENUM:
                var enumType = (EnumType) type.defValType();
                labels = enumType.labels();
                break;
            case FLAGS:
                var flagsType = (FlagsType) type.defValType();
                labels = flagsType.labels();
                break;
            default:
                return false;
        }

        Object[] constants = hostType.getEnumConstants();
        if (constants == null || constants.length != labels.size()) {
            return false;
        }

        return Arrays.stream(constants)
                .map(constant -> toKebabCase(((Enum<?>) constant).name()))
                .allMatch(labels::contains);
    }

    static boolean supports(Class<?> hostType) {
        return Enum.class.isAssignableFrom(hostType);
    }

    private static String toKebabCase(String name) {
        return name.toLowerCase().replace("_", "-");
    }
}
