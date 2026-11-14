package dev.marregui.comando.core.json.bind;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * A generic type captured whole, for {@link Binder#fromTree(dev.marregui.comando.core.json.JsonNode, Type)}: an
 * anonymous subclass, {@code new TypeRef<List<FolderRoot>>() {}}, keeps its type argument where erasure would drop
 * it, as Jackson's {@code TypeReference} does.
 */
public abstract class TypeRef<T> {

    private final Type type;

    /**
     * Reads the type argument of the anonymous subclass being built.
     *
     * @throws IllegalStateException when the subclass names no type argument (a raw {@code TypeRef})
     */
    protected TypeRef() {
        if (!(getClass().getGenericSuperclass() instanceof final ParameterizedType p)) {
            throw new IllegalStateException("a type reference needs its type argument [class=" + getClass().getName()
                    + ']');
        }
        this.type = p.getActualTypeArguments()[0];
    }

    /** The captured type. */
    public final Type type() {
        return type;
    }
}
