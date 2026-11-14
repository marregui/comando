package dev.marregui.comando.core.json.bind;

import java.io.File;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;

/**
 * Binds records to JSON trees and back, by reflection: the object mapping of comando's files and its intent log, in
 * the format Jackson wrote them in, so a file written before reads the same and an unchanged value writes the same
 * bytes.
 * <p>
 * Writing: a tree is itself, not copied; a record is an object of its components in declaration order; a
 * {@code null} component is written as JSON null unless its record is one the builder named in
 * {@link Builder#omitNulls}; an empty {@link Optional} is null; a {@link Path} is its {@code file:} URI; an enum is its
 * {@link Enum#name()}; a collection or an array is an array; a map, keyed by text or by an enum, is an object in its
 * iteration order. An implementation of one of the {@linkplain Builder#typed typed} interfaces carries its class name
 * first, as {@value #TYPE}.
 * <p>
 * Reading: unknown properties are ignored; a missing or null property gives a primitive's default, an empty
 * {@code Optional}, or {@code null}. Scalars are coerced as Jackson did: a number or boolean read as text gives its
 * text, text holding a number reads as that number, a fraction read as an integer is truncated, and the text
 * {@code "NaN"} or {@code "Infinity"} reads as that double. An enum reads by its name only, never by its position. A
 * List or Collection is built as an {@link ArrayList}, a Set as a {@link HashSet}, a sorted one as a
 * {@link TreeSet}, a Map as a {@link LinkedHashMap}. A value whose declared type is a typed interface, or an
 * implementation of one, needs its {@value #TYPE}: a class name outside the allowed prefix is refused before any
 * class is looked up.
 * <p>
 * Every failure is a {@link JsonException}. Its message names types, properties and JSON kinds, never a value read: a
 * file can hold a token where a number belongs. A binder is immutable and safe to share between threads.
 */
public final class Binder {

    /** The property that names the class of a typed value. */
    public static final String TYPE = "@type";

    private final List<Class<?>> typed;
    private final String allowPrefix;
    private final boolean fields;
    private final Set<Class<?>> omitNulls;
    private final ClassValue<Shape> shapes = new ClassValue<>() {
        @Override
        protected Shape computeValue(final Class<?> type) {
            return Shape.of(type, fields);
        }
    };

    private Binder(final Builder b) {
        this.typed = List.copyOf(b.typed);
        this.allowPrefix = b.allowPrefix;
        this.fields = b.fields;
        this.omitNulls = Set.copyOf(b.omitNulls);
    }

    /** A builder of a binder over record components, with no typed interface and no class allowed by name. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@code value} as a tree; a {@code null} is JSON null.
     *
     * @throws JsonException for a value that is neither a record nor one of the types the class comment lists
     */
    public JsonNode toTree(final Object value) {
        return write(value);
    }

    /**
     * The value of {@code type} that {@code node} holds. A {@code null}, missing or JSON null node gives a primitive's
     * default and {@code null} for any other class.
     *
     * @throws JsonException when the node does not hold a value of {@code type}
     */
    @SuppressWarnings("unchecked")
    public <T> T fromTree(final JsonNode node, final Class<T> type) {
        // a primitive class cannot cast its box, so Class.cast would refuse what this unchecked cast lets through
        return (T) read(node, type);
    }

    /**
     * The value of {@code type}, a generic one such as {@code List<FolderRoot>} included, that {@code node} holds.
     *
     * @throws JsonException when the node does not hold a value of {@code type}
     */
    public Object fromTree(final JsonNode node, final Type type) {
        return read(node, type);
    }

    // ---- writing

    private JsonNode write(final Object value) {
        return switch (value) {
            case null -> Json.nullNode();
            case JsonNode n -> n;
            case String s -> Json.text(s);
            case Boolean b -> Json.bool(b);
            case Integer i -> Json.number(i.intValue());
            case Long l -> Json.number(l.longValue());
            case Double d -> Json.number(d.doubleValue());
            case Float f -> Json.number(f.floatValue());
            case Enum<?> e -> Json.text(e.name());
            case Optional<?> o -> o.isPresent() ? write(o.get()) : Json.nullNode();
            case Path p -> Json.text(p.toUri().toString());
            case Collection<?> c -> writeAll(c);
            case Map<?, ?> m -> writeMap(m);
            default -> value.getClass().isArray() ? writeArray(value) : writeRecord(value);
        };
    }

    private ArrayNode writeAll(final Collection<?> values) {
        final ArrayNode a = Json.array();
        for (final Object v : values) {
            a.add(write(v));
        }
        return a;
    }

    private ArrayNode writeArray(final Object array) {
        final ArrayNode a = Json.array();
        final int n = Array.getLength(array);
        for (int i = 0; i < n; i++) {
            a.add(write(Array.get(array, i)));
        }
        return a;
    }

    private ObjectNode writeMap(final Map<?, ?> map) {
        final ObjectNode o = Json.object();
        for (final Map.Entry<?, ?> e : map.entrySet()) {
            final String key = switch (e.getKey()) {
                case String s -> s;
                case Enum<?> k -> k.name();
                case null -> throw new JsonException("cannot write a map with a null key", "");
                default -> throw new JsonException("cannot write a map key of this type",
                        "type=" + className(e.getKey()));
            };
            o.set(key, write(e.getValue()));
        }
        return o;
    }

    private ObjectNode writeRecord(final Object value) {
        final Class<?> type = value.getClass();
        if (!type.isRecord()) {
            throw new JsonException("cannot write a value that is not a record", "type=" + type.getName());
        }
        final Shape shape = shapes.get(type);
        final ObjectNode o = Json.object();
        if (isTyped(type)) {
            o.put(TYPE, type.getName());
        }
        final boolean skipNulls = omitNulls.contains(type);
        for (int i = 0; i < shape.names.length; i++) {
            final Object component = shape.get(i, value);
            if (component != null || !skipNulls) {
                o.set(shape.names[i], write(component));
            }
        }
        return o;
    }

    // ---- reading

    private Object read(final JsonNode node, final Type type) {
        final Class<?> raw = raw(type);
        final boolean absent = node == null || node.isMissingNode() || node.isNull();
        if (raw == Optional.class) {
            return absent ? Optional.empty() : Optional.ofNullable(read(node, argument(type, 0)));
        }
        if (absent) {
            return raw.isPrimitive() ? zero(raw) : null;
        }
        if (raw == String.class) {
            return text(node, raw);
        }
        if (raw == int.class || raw == Integer.class) {
            final Long value = integral(node, raw, Integer.MIN_VALUE, Integer.MAX_VALUE);
            return value == null ? null : value.intValue();
        }
        if (raw == long.class || raw == Long.class) {
            return integral(node, raw, Long.MIN_VALUE, Long.MAX_VALUE);
        }
        if (raw == double.class || raw == Double.class) {
            return floating(node, raw);
        }
        if (raw == float.class || raw == Float.class) {
            final Double value = floating(node, raw);
            return value == null ? null : value.floatValue();
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return bool(node, raw);
        }
        if (raw.isEnum()) {
            return constant(node, raw);
        }
        if (raw == Path.class) {
            return path(node);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return readAll(node, raw, argument(type, 0));
        }
        if (Map.class.isAssignableFrom(raw)) {
            return readMap(node, raw, argument(type, 0), argument(type, 1));
        }
        if (raw.isArray()) {
            final Type component = type instanceof final GenericArrayType g ? g.getGenericComponentType()
                    : raw.getComponentType();
            return readArray(node, raw, component);
        }
        if (isTyped(raw)) {
            return readRecord(node, subtype(node, raw));
        }
        if (raw.isRecord()) {
            return readRecord(node, raw);
        }
        throw new JsonException("cannot read a value of this type", "type=" + type.getTypeName());
    }

    private static String text(final JsonNode node, final Class<?> type) {
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isValueNode()) {
            return node.asString();
        }
        throw mismatch(node, type);
    }

    /** Text a scalar reads as nothing: the empty string, or the word {@code null}; Jackson coerces both. */
    private static boolean blank(final String text) {
        return text.isEmpty() || "null".equals(text);
    }

    /** A number in {@code [min, max]}; {@code null} where the text read is blank and the type is a box. */
    private static Long integral(final JsonNode node, final Class<?> type, final long min, final long max) {
        final long value;
        if (node.isIntegralNumber()) {
            final Number n = node.numberValue();
            if (n instanceof final BigInteger b && b.bitLength() >= Long.SIZE) {
                throw outOfRange(type);
            }
            value = n.longValue();
        } else if (node.isNumber()) {
            final double d = node.numberValue().doubleValue();
            // a fraction is truncated toward zero, as Jackson's float-as-int coercion does
            if (!(d >= min && d <= max)) {
                throw outOfRange(type);
            }
            value = (long) d;
        } else if (node.isString()) {
            final String t = node.stringValue().trim();
            if (blank(t)) {
                return type.isPrimitive() ? 0L : null;
            }
            try {
                value = Long.parseLong(t);
            } catch (final NumberFormatException e) {
                throw new JsonException("cannot read this text as a number", "type=" + type.getName(), e);
            }
        } else {
            throw mismatch(node, type);
        }
        if (value < min || value > max) {
            throw outOfRange(type);
        }
        return value;
    }

    private static Double floating(final JsonNode node, final Class<?> type) {
        if (node.isNumber()) {
            // a BigDecimal beyond a double's range gives an infinity, as Jackson's double coercion does
            return node.numberValue().doubleValue();
        }
        if (node.isString()) {
            final String t = node.stringValue().trim();
            if (blank(t)) {
                return type.isPrimitive() ? 0.0 : null;
            }
            try {
                // the JDK's parser reads "NaN", "Infinity" and "-Infinity", as Jackson's does
                return Double.parseDouble(t);
            } catch (final NumberFormatException e) {
                throw new JsonException("cannot read this text as a number", "type=" + type.getName(), e);
            }
        }
        throw mismatch(node, type);
    }

    private static Boolean bool(final JsonNode node, final Class<?> type) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            // zero is false and any other integer true, as Jackson coerces them
            final Number n = node.numberValue();
            return n instanceof final BigInteger b ? b.signum() != 0 : n.longValue() != 0L;
        }
        if (node.isString()) {
            final String t = node.stringValue().trim();
            return switch (t) {
                case "true", "True", "TRUE" -> true;
                case "false", "False", "FALSE" -> false;
                default -> {
                    if (blank(t)) {
                        yield type.isPrimitive() ? false : null;
                    }
                    throw new JsonException("cannot read this text as a boolean", "type=" + type.getName());
                }
            };
        }
        throw mismatch(node, type);
    }

    private static Object constant(final JsonNode node, final Class<?> type) {
        if (node.isString()) {
            final String name = node.stringValue();
            for (final Object c : type.getEnumConstants()) {
                if (((Enum<?>) c).name().equals(name)) {
                    return c;
                }
            }
            throw new JsonException("cannot read this text as a constant of the enum", "type=" + type.getName());
        }
        throw mismatch(node, type);
    }

    /**
     * A path from its text, read as Jackson read it: text with no colon is a local path, a {@code file:} URI is the
     * path it names, and any other scheme is refused, so no file system provider is reached from a file's content.
     */
    private static Path path(final JsonNode node) {
        if (!node.isValueNode()) {
            throw mismatch(node, Path.class);
        }
        final String value = node.asString().trim();
        if (value.isEmpty()) {
            return null;
        }
        final int colon = value.indexOf(':');
        final boolean drive = colon == 1 && Character.isLetter(value.charAt(0)) && File.separatorChar == '\\';
        if (colon < 0 || drive) {
            return Path.of(value);
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (final URISyntaxException e) {
            throw new JsonException("cannot read this text as a path", "type=" + Path.class.getName(), e);
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new JsonException("cannot read a path of a scheme other than file", "type=" + Path.class.getName());
        }
        try {
            return Path.of(uri);
        } catch (final IllegalArgumentException e) {
            throw new JsonException("cannot read this URI as a path", "type=" + Path.class.getName(), e);
        }
    }

    private Collection<Object> readAll(final JsonNode node, final Class<?> raw, final Type element) {
        if (!node.isArray()) {
            throw mismatch(node, raw);
        }
        final Collection<Object> out = collection(raw);
        for (final JsonNode e : node) {
            out.add(read(e, element));
        }
        return out;
    }

    private static Collection<Object> collection(final Class<?> raw) {
        if (raw.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>();
        }
        if (raw.isAssignableFrom(HashSet.class)) {
            return new HashSet<>();
        }
        if (raw.isAssignableFrom(LinkedHashSet.class)) {
            return new LinkedHashSet<>();
        }
        if (raw.isAssignableFrom(TreeSet.class) || raw == SortedSet.class) {
            return new TreeSet<>();
        }
        throw new JsonException("cannot read a collection of this type", "type=" + raw.getName());
    }

    private Map<Object, Object> readMap(final JsonNode node, final Class<?> raw, final Type key, final Type value) {
        if (!node.isObject()) {
            throw mismatch(node, raw);
        }
        final Map<Object, Object> out;
        if (raw.isAssignableFrom(LinkedHashMap.class)) {
            out = new LinkedHashMap<>();
        } else if (raw.isAssignableFrom(TreeMap.class) || raw == SortedMap.class) {
            out = new TreeMap<>();
        } else {
            throw new JsonException("cannot read a map of this type", "type=" + raw.getName());
        }
        final Class<?> keyType = raw(key);
        if (keyType != String.class && !keyType.isEnum()) {
            throw new JsonException("cannot read a map key of this type", "type=" + key.getTypeName());
        }
        for (final Map.Entry<String, JsonNode> e : node.properties()) {
            final Object k = keyType == String.class ? e.getKey() : constant(Json.text(e.getKey()), keyType);
            out.put(k, read(e.getValue(), value));
        }
        return out;
    }

    private Object readArray(final JsonNode node, final Class<?> raw, final Type component) {
        if (!node.isArray()) {
            throw mismatch(node, raw);
        }
        final Object out = Array.newInstance(raw.getComponentType(), node.size());
        int i = 0;
        for (final JsonNode e : node) {
            Array.set(out, i++, read(e, component));
        }
        return out;
    }

    private Object readRecord(final JsonNode node, final Class<?> type) {
        if (!node.isObject()) {
            throw mismatch(node, type);
        }
        final Shape shape = shapes.get(type);
        final Object[] args = new Object[shape.names.length];
        for (int i = 0; i < args.length; i++) {
            try {
                args[i] = read(node.get(shape.names[i]), shape.types[i]);
            } catch (final JsonException e) {
                throw new JsonException("cannot read a property",
                        "type=" + type.getName() + ", property=" + shape.names[i], e);
            }
        }
        return shape.construct(args);
    }

    /**
     * The class {@code node}'s {@value #TYPE} names, checked against the allowed prefix before it is looked up, then
     * against {@code declared}.
     */
    private Class<?> subtype(final JsonNode node, final Class<?> declared) {
        if (!node.isObject()) {
            throw mismatch(node, declared);
        }
        final JsonNode id = node.get(TYPE);
        if (id == null || !id.isValueNode() || id.isNull()) {
            throw new JsonException("cannot read a typed value without its " + TYPE, "type=" + declared.getName());
        }
        final String name = id.asString();
        if (allowPrefix.isEmpty() || !name.startsWith(allowPrefix)) {
            // the id is left out: text outside the prefix is not known to be a class name
            throw new JsonException("cannot read a type id outside the allowed prefix",
                    "type=" + declared.getName() + ", prefix=" + allowPrefix);
        }
        final Class<?> c;
        try {
            c = Class.forName(name, false, declared.getClassLoader());
        } catch (final ClassNotFoundException | LinkageError e) {
            throw new JsonException("cannot find the class a type id names", "id=" + name, e);
        }
        if (!declared.isAssignableFrom(c) || c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
            throw new JsonException("cannot read a type id that is not a concrete subtype",
                    "id=" + name + ", type=" + declared.getName());
        }
        return c;
    }

    private boolean isTyped(final Class<?> type) {
        for (final Class<?> t : typed) {
            if (t.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    private static JsonException mismatch(final JsonNode node, final Class<?> type) {
        return new JsonException("cannot read this JSON as the type",
                "type=" + type.getName() + ", json=" + kind(node));
    }

    private static String kind(final JsonNode node) {
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isString()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        return node.isBoolean() ? "boolean" : "value";
    }

    private static JsonException outOfRange(final Class<?> type) {
        return new JsonException("cannot read a number out of the type's range", "type=" + type.getName());
    }

    private static String className(final Object value) {
        return value.getClass().getName();
    }

    private static Object zero(final Class<?> primitive) {
        return Array.get(Array.newInstance(primitive, 1), 0);
    }

    private static Class<?> raw(final Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p -> raw(p.getRawType());
            case GenericArrayType g -> raw(g.getGenericComponentType()).arrayType();
            case WildcardType w -> raw(w.getUpperBounds()[0]);
            case TypeVariable<?> v -> raw(v.getBounds()[0]);
            default -> throw new JsonException("cannot read a value of this type", "type=" + type.getTypeName());
        };
    }

    private static Type argument(final Type type, final int index) {
        if (type instanceof final ParameterizedType p) {
            final Type a = p.getActualTypeArguments()[index];
            return a instanceof final WildcardType w ? w.getUpperBounds()[0] : a;
        }
        return Object.class;
    }

    /** A record's components, as names, generic types and readers, and its canonical constructor. */
    private record Shape(String[] names, Type[] types, Member[] readers, Constructor<?> constructor) {

        static Shape of(final Class<?> type, final boolean fields) {
            if (!type.isRecord()) {
                throw new JsonException("cannot bind a type that is not a record", "type=" + type.getName());
            }
            final RecordComponent[] components = type.getRecordComponents();
            final String[] names = new String[components.length];
            final Type[] types = new Type[components.length];
            final Member[] readers = new Member[components.length];
            final Class<?>[] raws = new Class<?>[components.length];
            try {
                for (int i = 0; i < components.length; i++) {
                    names[i] = components[i].getName();
                    types[i] = components[i].getGenericType();
                    raws[i] = components[i].getType();
                    final AccessibleObject reader = fields ? type.getDeclaredField(names[i])
                            : components[i].getAccessor();
                    reader.setAccessible(true);
                    readers[i] = (Member) reader;
                }
                final Constructor<?> constructor = type.getDeclaredConstructor(raws);
                constructor.setAccessible(true);
                return new Shape(names, types, readers, constructor);
            } catch (final ReflectiveOperationException | RuntimeException e) {
                throw new JsonException("cannot bind the record", "type=" + type.getName(), e);
            }
        }

        Object get(final int i, final Object value) {
            try {
                return readers[i] instanceof final Field f ? f.get(value) : ((Method) readers[i]).invoke(value);
            } catch (final InvocationTargetException e) {
                throw new JsonException("cannot read a component", details(value, i), e.getCause());
            } catch (final IllegalAccessException e) {
                throw new JsonException("cannot read a component", details(value, i), e);
            }
        }

        Object construct(final Object[] args) {
            final String details = "type=" + constructor.getDeclaringClass().getName();
            try {
                return constructor.newInstance(args);
            } catch (final InvocationTargetException e) {
                throw new JsonException("cannot construct the record", details, e.getCause());
            } catch (final ReflectiveOperationException | IllegalArgumentException e) {
                throw new JsonException("cannot construct the record", details, e);
            }
        }

        private String details(final Object value, final int i) {
            return "type=" + value.getClass().getName() + ", component=" + names[i];
        }
    }

    /** How a binder finds properties and which classes carry their type. */
    public static final class Builder {

        private final List<Class<?>> typed = new ArrayList<>();
        private final Set<Class<?>> omitNulls = new HashSet<>();
        private String allowPrefix = "";
        private boolean fields;

        private Builder() {
        }

        /** Implementations of {@code interfaces} are written with their class name as {@value #TYPE}, read by it. */
        public Builder typed(final Class<?>... interfaces) {
            typed.addAll(List.of(interfaces));
            return this;
        }

        /** The prefix every class name read from {@value #TYPE} must start with; with none, no typed value reads. */
        public Builder allowPrefix(final String prefix) {
            this.allowPrefix = prefix;
            return this;
        }

        /** A record's components are read from its fields, not its accessors: the mapping of the intent log. */
        public Builder fields() {
            this.fields = true;
            return this;
        }

        /** Components of {@code type} that are {@code null} are left out of what it writes. */
        public Builder omitNulls(final Class<?> type) {
            omitNulls.add(type);
            return this;
        }

        /** The binder. */
        public Binder build() {
            return new Binder(this);
        }
    }
}
