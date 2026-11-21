package dev.marregui.comando.cdb.json;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// The JSON scanner against RFC 8259: each grammar rule's valid and breaking texts with the offset of the first byte
/// that breaks them, a random differential against a recursive-descent reference written here (random valid texts
/// whose paths and string values the generator knows, and single-byte mutations of them), nesting 100,000 deep,
/// `find` over every container shape, and `unescape` of every escape. Seed in the output; replay with
/// `-Dcdb.json.seed=<seed>`.
class JsonTest {

    private static final int DEEP = 100_000;
    private static final int TEXTS = 3_000;
    private static final int MUTATIONS = 12;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_WIDTH = 4;
    private static final String EMOJI = new String(Character.toChars(0x1F600));
    // member names drawn for generated objects, so names repeat (duplicates) and come in several encodings
    private static final List<String> NAMES = List.of("a", "b", "k", "\u00e9", "a.b", "x[0]", "q\"", "", EMOJI,
            "tab\t", "sl/sh", "back\\", "]");
    // a name no generated object holds
    private static final String MISSING = "zz";
    private static final byte[] MUTANTS = bytes("{}[],:\" \t\n\r0123456789-+.eEtrufalsnbxAF/\\'\u0001\u007f");

    private final Json json = new Json();

    static Stream<Arguments> breaking() {
        return Stream.of(
                // empty input and whitespace only: the value is missing at the end
                Arguments.of("", 0),
                Arguments.of("   ", 3),
                Arguments.of(" \t\r\n", 4),
                // numbers: no leading zero, digits after the point and the exponent, a sign only on the exponent
                Arguments.of("01", 1),
                Arguments.of("00", 1),
                Arguments.of("-01", 2),
                Arguments.of("1.", 2),
                Arguments.of("1.e5", 2),
                Arguments.of("1e", 2),
                Arguments.of("1e+", 3),
                Arguments.of("1E-x", 3),
                Arguments.of(".5", 0),
                Arguments.of("+1", 0),
                Arguments.of("-", 1),
                Arguments.of("-a", 1),
                Arguments.of("--1", 1),
                Arguments.of("1.5.2", 3),
                Arguments.of("0x1F", 1),
                Arguments.of("1 2", 2),
                // literals: lower case, whole, nothing after
                Arguments.of("tru", 3),
                Arguments.of("trUe", 2),
                Arguments.of("nul", 3),
                Arguments.of("nan", 1),
                Arguments.of("nulll", 4),
                Arguments.of("False", 0),
                Arguments.of("falsey", 5),
                // strings: closed, no raw control character, only the eight escapes and four hex digits
                Arguments.of("\"abc", 4),
                Arguments.of("\"a\u0001b\"", 2),
                Arguments.of("\"a\tb\"", 2),
                Arguments.of("\"a\nb\"", 2),
                Arguments.of("\"a\u001fb\"", 2),
                Arguments.of("\"\\x\"", 2),
                Arguments.of("\"\\U0041\"", 2),
                Arguments.of("\"\\u12g4\"", 5),
                Arguments.of("\"\\u12\"", 5),
                Arguments.of("\"\\u12", 5),
                Arguments.of("\"\\", 2),
                Arguments.of("'a'", 0),
                // arrays: no trailing or leading comma, a comma between elements, closed by its own bracket
                Arguments.of("[1,]", 3),
                Arguments.of("[,1]", 1),
                Arguments.of("[1 2]", 3),
                Arguments.of("[1", 2),
                Arguments.of("[1,2", 4),
                Arguments.of("[", 1),
                Arguments.of("]", 0),
                Arguments.of("[1}", 2),
                // objects: string names, a colon, a value, no trailing comma, closed by its own brace
                Arguments.of("{", 1),
                Arguments.of("}", 0),
                Arguments.of("{\"a\":1,}", 7),
                Arguments.of("{\"a\" 1}", 5),
                Arguments.of("{\"a\"}", 4),
                Arguments.of("{\"a\":}", 5),
                Arguments.of("{a:1}", 1),
                Arguments.of("{'a':1}", 1),
                Arguments.of("{1:2}", 1),
                Arguments.of("{\"a\":1 \"b\":2}", 7),
                Arguments.of("{\"a\":1]", 6),
                Arguments.of("{\"a", 3),
                // trailing garbage after one whole text
                Arguments.of("[1] x", 4),
                Arguments.of("{}{}", 2),
                Arguments.of("true false", 5),
                Arguments.of("\"a\"\"b\"", 3));
    }

    /// Every value kind, the whitespace RFC 8259 allows around and inside it, and every escape, is one valid text.
    @ParameterizedTest
    @ValueSource(strings = {"0", "-0", "7", "-12", "1.5", "-0.0", "10.25", "1e5", "1E5", "1e+5", "1e-5", "-1.5E-10",
            "0e0", "123456789012345678901234567890", "true", "false", "null", "\"\"", "\"a\"", "\"\u00e9\"",
            "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"", "\"\\u00e9\\u00E9\\uD83D\\uDE00\"", "\"\\uD800\"", "\"a\u007fb\"",
            "[]", "{}", "[ ]", "{ }", " [1, 2] ", "\t{\"a\" : 1}\r\n", "[1,\"a\",true,false,null,{},[]]",
            "{\"a\":{\"b\":[null,{\"c\":-1}]}}", "{\"a\":1,\"a\":2}", "{\"\":0}", " 1 ", "\n\"x\"\n"})
    void everyValueKindIsValid(final String text) {
        final byte[] a = bytes(text);
        assertThat(json.invalidAt(a, 0, a.length)).isEqualTo(Json.VALID);
        assertThat(Reference.invalidAt(a, 0, a.length)).isEqualTo(Json.VALID);
    }

    /// Each grammar rule broken answers the offset of the first byte no valid text can have there: the end of the
    /// input when the text stops short.
    @ParameterizedTest(name = "{0} breaks at {1}")
    @MethodSource("breaking")
    void aBrokenTextAnswersItsFirstBreakingByte(final String text, final int at) {
        final byte[] a = bytes(text);
        assertThat(Reference.invalidAt(a, 0, a.length)).as("reference").isEqualTo(at);
        assertThat(json.invalidAt(a, 0, a.length)).isEqualTo(at);
    }

    /// The text is the range given: bytes before `from` and from `to` on are not read, and the offset is the array's.
    @Test
    void aRangeIsScannedAlone() {
        final byte[] a = bytes("xx[1,2]yy");
        assertThat(json.invalidAt(a, 2, 7)).isEqualTo(Json.VALID);
        assertThat(json.invalidAt(a, 2, 6)).isEqualTo(6);
        assertThat(json.invalidAt(a, 2, 8)).isEqualTo(7);
        assertThat(json.invalidAt(a, 1, 7)).isEqualTo(1);
        assertThat(json.invalidAt(a, 4, 4)).isEqualTo(4);
    }

    /// 100,000 nested arrays and objects scan without recursion: valid when closed, broken at the end when not, and
    /// `find` reaches into them.
    @Test
    void nestingHundredThousandDeepDoesNotOverflow() {
        final byte[] arrays = new byte[2 * DEEP];
        Arrays.fill(arrays, 0, DEEP, (byte) '[');
        Arrays.fill(arrays, DEEP, 2 * DEEP, (byte) ']');
        assertThat(json.invalidAt(arrays, 0, arrays.length)).isEqualTo(Json.VALID);
        assertThat(json.invalidAt(arrays, 0, DEEP)).isEqualTo(DEEP);
        assertThat(json.invalidAt(arrays, 0, arrays.length - 1)).isEqualTo(arrays.length - 1);
        final long inner = json.find(arrays, 0, arrays.length, JsonPath.parse("$[0]"));
        assertThat(Json.start(inner)).isEqualTo(1);
        assertThat(Json.end(inner)).isEqualTo(arrays.length - 1);

        final String open = "{\"a\":";
        final byte[] objects = bytes(open.repeat(DEEP) + "1" + "}".repeat(DEEP));
        assertThat(json.invalidAt(objects, 0, objects.length)).isEqualTo(Json.VALID);
        assertThat(json.invalidAt(objects, 0, objects.length - DEEP)).isEqualTo(objects.length - DEEP);
        final long a = json.find(objects, 0, objects.length, JsonPath.parse("$.a"));
        assertThat(Json.start(a)).isEqualTo(open.length());
        assertThat(Json.end(a)).isEqualTo(objects.length - 1);
        final long deepest = json.find(objects, 0, objects.length, JsonPath.parse("$" + ".a".repeat(DEEP)));
        assertThat(text(objects, deepest)).isEqualTo("1");
    }

    /// `find` gives a member's or an element's value without the whitespace around it, a string with its quotes, and
    /// the whole text for the empty path.
    @Test
    void findGivesTheValueAPathNames() {
        final String doc = " { \"a\" : 1 , \"b\" : { \"c\" : [ 10 , 20 , \"x y\" , [ ] , { } ] } , \"t\" : true } ";
        assertThat(find(doc, "$.a")).isEqualTo("1");
        assertThat(find(doc, "$.b.c[1]")).isEqualTo("20");
        assertThat(find(doc, "$.b.c[2]")).isEqualTo("\"x y\"");
        assertThat(find(doc, "$.b.c[3]")).isEqualTo("[ ]");
        assertThat(find(doc, "$.b.c[4]")).isEqualTo("{ }");
        assertThat(find(doc, "$.b")).isEqualTo("{ \"c\" : [ 10 , 20 , \"x y\" , [ ] , { } ] }");
        assertThat(find(doc, "$[\"b\"][\"c\"][0]")).isEqualTo("10");
        assertThat(find(doc, ".t")).isEqualTo("true");
        assertThat(find(doc, "$")).isEqualTo(doc.strip());
        assertThat(find(doc, "")).isEqualTo(doc.strip());
        assertThat(find("[[1,[2,3]],4]", "$[0][1][1]")).isEqualTo("3");
        assertThat(find("[[1,[2,3]],4]", "$[1]")).isEqualTo("4");
        assertThat(find("\"top\"", "$")).isEqualTo("\"top\"");
    }

    /// A member matches by its decoded name: an escaped name in the text matches its plain form in the path and the
    /// other way round, and a name only alike in bytes does not match.
    @Test
    void findMatchesEscapedNames() {
        final String doc = "{\"a\\\"b\":1,\"\\u00e9\":2,\"caf\u00e9\":3,\"\\uD83D\\uDE00\":4,\"x\\/y\":5}";
        assertThat(find(doc, "$[\"a\\\"b\"]")).isEqualTo("1");
        assertThat(find(doc, "$.a\"b")).isEqualTo("1");
        assertThat(find(doc, "$.\u00e9")).isEqualTo("2");
        assertThat(find(doc, "$[\"caf\\u00e9\"]")).isEqualTo("3");
        assertThat(find(doc, "$." + EMOJI)).isEqualTo("4");
        assertThat(find(doc, "$.x/y")).isEqualTo("5");
        assertThat(find(doc, "$[\"a\\\\\\\"b\"]")).isNull();
        final byte[] a = bytes(doc);
        assertThat(text(a, json.find(a, 0, a.length, JsonPath.member("a\"b")))).isEqualTo("1");
    }

    /// Of two members with one name the first is found, also when the two spell it differently, and a member of a
    /// nested object never answers for the outer one.
    @Test
    void findTakesTheFirstOfDuplicateNames() {
        assertThat(find("{\"k\":1,\"k\":2}", "$.k")).isEqualTo("1");
        assertThat(find("{\"\\u006b\":1,\"k\":2}", "$.k")).isEqualTo("1");
        assertThat(find("{\"b\":{\"a\":1},\"a\":2}", "$.a")).isEqualTo("2");
        assertThat(find("{\"b\":[{\"a\":1}],\"a\":2}", "$.a")).isEqualTo("2");
    }

    /// A path naming no value is absent: a missing member, an index past the end or negative, a member step on an
    /// array or a scalar, an index step on an object or a scalar, and any step into an empty container.
    @Test
    void findOfAMissingValueIsAbsent() {
        final String doc = "{\"a\":[1,2],\"s\":\"str\",\"n\":null}";
        assertThat(find(doc, "$.z")).isNull();
        assertThat(find(doc, "$.a[2]")).isNull();
        assertThat(find(doc, "$.a.x")).isNull();
        assertThat(find(doc, "$[0]")).isNull();
        assertThat(find(doc, "$.s[0]")).isNull();
        assertThat(find(doc, "$.s.x")).isNull();
        assertThat(find(doc, "$.n.x")).isNull();
        assertThat(find(doc, "$.a[0].x")).isNull();
        assertThat(find("[]", "$[0]")).isNull();
        assertThat(find("[ ]", "$[0]")).isNull();
        assertThat(find("{}", "$.a")).isNull();
        assertThat(find("{ }", "$.a")).isNull();
        assertThat(find("7", "$[0]")).isNull();
        assertThat(find("7", "$.a")).isNull();
        final byte[] a = bytes("[1,2]");
        assertThat(json.find(a, 0, a.length, JsonPath.element(-1L))).isEqualTo(Json.ABSENT);
        assertThat(json.find(a, 0, a.length, JsonPath.element(Integer.MAX_VALUE + 1L))).isEqualTo(Json.ABSENT);
        assertThat(text(a, json.find(a, 0, a.length, JsonPath.element(1L)))).isEqualTo("2");
    }

    /// `find` reads no further than the value: a text broken after it still yields it, one broken before or inside
    /// it does not, and JSON `null` is found as a value.
    @Test
    void findStopsAtTheValue() {
        assertThat(find("{\"a\":1, garbage", "$.a")).isEqualTo("1");
        assertThat(find("{\"a\":[1,2]", "$.a")).isEqualTo("[1,2]");
        assertThat(find("[1,2", "$[0]")).isEqualTo("1");
        assertThat(find("[1,", "$[1]")).isNull();
        assertThat(find("{\"x\":tru, \"a\":1}", "$.a")).isNull();
        assertThat(find("{\"x\" 1, \"a\":1}", "$.a")).isNull();
        assertThat(find("{\"a\":[1,}", "$.a")).isNull();
        assertThat(find("{\"a\":\"open", "$.a")).isNull();
        assertThat(find("{\"a\":01}", "$.a")).isEqualTo("0");
        assertThat(find("{\"a\":null}", "$.a")).isEqualTo("null");
        assertThat(find("", "$")).isNull();
        assertThat(find("   ", "$")).isNull();
    }

    /// `find` within a range gives the array's offsets, and the bytes around the range are not read.
    @Test
    void findInARangeGivesArrayOffsets() {
        final byte[] a = bytes("{\"a\":9}{\"a\":42}{\"a\":7}");
        final long span = json.find(a, 7, 15, JsonPath.parse("$.a"));
        assertThat(Json.start(span)).isEqualTo(12);
        assertThat(Json.end(span)).isEqualTo(14);
        assertThat(json.find(a, 7, 12, JsonPath.parse("$.a"))).isEqualTo(Json.ABSENT);
    }

    /// Contents without a backslash answer `RAW`: their bytes are their value.
    @Test
    void unescapeOfPlainContentsIsRaw() {
        assertThat(json.unescape(bytes("abc"), 0, 3)).isEqualTo(Json.RAW);
        assertThat(json.unescape(bytes(""), 0, 0)).isEqualTo(Json.RAW);
        final byte[] e = bytes("caf\u00e9 " + EMOJI);
        assertThat(json.unescape(e, 0, e.length)).isEqualTo(Json.RAW);
    }

    /// The eight short escapes and u-escapes of one, two and three UTF-8 bytes decode, in either hex case, with the
    /// plain bytes around them kept.
    @Test
    void unescapeDecodesEveryEscape() {
        assertThat(unescape("\\\"\\\\\\/\\b\\f\\n\\r\\t")).isEqualTo("\"\\/\b\f\n\r\t");
        assertThat(unescape("abc\\ndef")).isEqualTo("abc\ndef");
        assertThat(unescape("\\u0041\\u00e9\\u00E9\\u20ac\\u20AC")).isEqualTo("A\u00e9\u00e9\u20ac\u20ac");
        assertThat(unescape("\\u0000x")).isEqualTo("\u0000x");
        assertThat(unescape("\\u007f\\u0080\\u07ff\\u0800\\uffff")).isEqualTo("\u007f\u0080\u07ff\u0800\uffff");
        assertThat(unescape("caf\u00e9\\t" + EMOJI)).isEqualTo("caf\u00e9\t" + EMOJI);
    }

    /// A surrogate pair decodes to its code point's four bytes; a lone surrogate, high or low, decodes to `?`, as
    /// `String.getBytes(UTF_8)` encodes one.
    @Test
    void unescapeJoinsSurrogatePairsAndMarksLoneOnes() {
        assertThat(unescape("\\uD83D\\uDE00")).isEqualTo(EMOJI);
        assertThat(unescape("\\ud83d\\ude00!")).isEqualTo(EMOJI + "!");
        assertThat(unescape("\\uD83D")).isEqualTo("?");
        assertThat(unescape("\\uD83Dx")).isEqualTo("?x");
        assertThat(unescape("\\uD83D\\n")).isEqualTo("?\n");
        assertThat(unescape("\\uD83D\\u0041")).isEqualTo("?A");
        assertThat(unescape("\\uDE00")).isEqualTo("?");
        assertThat(unescape("\\uDE00\\uD83D")).isEqualTo("??");
        assertThat(unescape("\\uD800\\uD83D\\uDE00")).isEqualTo("?" + EMOJI);
        final String lone = "\uD83D";
        assertThat(lone.getBytes(StandardCharsets.UTF_8)).isEqualTo(bytes("?"));
    }

    /// A string longer than the decode buffer grows it, and a later shorter one decodes over it.
    @Test
    void unescapeGrowsItsBuffer() {
        final String plain = "x".repeat(1_000);
        assertThat(unescape(plain + "\\n" + plain)).isEqualTo(plain + '\n' + plain);
        assertThat(unescape("\\t")).isEqualTo("\t");
    }

    /// Random valid texts of every value kind, escape and whitespace: valid alone and inside a larger array, every
    /// path the generator built finds the value it put there (first of duplicate names), a missing step is absent,
    /// every string decodes to the generator's value, and each single-byte mutation (replace, insert, delete, cut)
    /// is judged as the reference judges it, to the offset.
    @Test
    void randomTextsAgreeWithTheReference() {
        final long seed = Long.getLong("cdb.json.seed", System.nanoTime());
        System.out.println("randomTextsAgreeWithTheReference seed=" + seed);
        final Random random = new Random(seed);
        int paths = 0;
        int strings = 0;
        int mutants = 0;
        int brokenMutants = 0;
        for (int t = 0; t < TEXTS; t++) {
            final Generator generator = new Generator(random);
            final Node root = generator.value(0);
            final byte[] text = generator.write(root);
            final String about = "seed=" + seed + " text=" + new String(text, StandardCharsets.UTF_8);
            assertThat(Reference.invalidAt(text, 0, text.length)).as(about).isEqualTo(Json.VALID);
            assertThat(json.invalidAt(text, 0, text.length)).as(about).isEqualTo(Json.VALID);

            final int pad = random.nextInt(4);
            final byte[] framed = new byte[pad + text.length + 3];
            Arrays.fill(framed, (byte) '}');
            System.arraycopy(text, 0, framed, pad, text.length);
            assertThat(json.invalidAt(framed, pad, pad + text.length)).as(about).isEqualTo(Json.VALID);

            for (final Probe probe : generator.probes(root)) {
                final JsonPath path = JsonPath.parse(probe.path());
                final String where = about + " path=" + probe.path();
                if (probe.span() == null) {
                    assertThat(json.find(text, 0, text.length, path)).as(where).isEqualTo(Json.ABSENT);
                    continue;
                }
                final long found = json.find(framed, pad, pad + text.length, path);
                assertThat(found).as(where).isNotEqualTo(Json.ABSENT);
                assertThat(Json.start(found) - pad).as(where).isEqualTo(probe.span()[0]);
                assertThat(Json.end(found) - pad).as(where).isEqualTo(probe.span()[1]);
                paths++;
            }
            for (final Map.Entry<Node, int[]> e : generator.spans.entrySet()) {
                if (e.getKey() instanceof Text(final String decoded)) {
                    final int[] span = e.getValue();
                    final int n = json.unescape(text, span[0] + 1, span[1] - 1);
                    final byte[] value = n == Json.RAW ? Arrays.copyOfRange(text, span[0] + 1, span[1] - 1)
                            : Arrays.copyOf(json.buffer(), n);
                    assertThat(value).as(about + " string at " + span[0])
                            .isEqualTo(decoded.getBytes(StandardCharsets.UTF_8));
                    strings++;
                }
            }
            for (int m = 0; m < MUTATIONS; m++) {
                final byte[] mutant = mutate(text, random);
                final int expected = Reference.invalidAt(mutant, 0, mutant.length);
                assertThat(json.invalidAt(mutant, 0, mutant.length))
                        .as("seed=" + seed + " mutant=" + new String(mutant, StandardCharsets.ISO_8859_1))
                        .isEqualTo(expected);
                mutants++;
                if (expected != Json.VALID) {
                    brokenMutants++;
                }
            }
        }
        System.out.println("randomTextsAgreeWithTheReference texts=" + TEXTS + " paths=" + paths + " strings="
                + strings + " mutants=" + mutants + " broken=" + brokenMutants);
        assertThat(paths).isGreaterThan(TEXTS);
        // about 0.8 strings a text over two seeds; half a string a text still shows the decoder was exercised
        assertThat(strings).isGreaterThan(TEXTS / 2);
        assertThat(brokenMutants).isGreaterThan(mutants / 4);
    }

    private String find(final String doc, final String path) {
        final byte[] a = bytes(doc);
        final long span = json.find(a, 0, a.length, JsonPath.parse(path));
        return span == Json.ABSENT ? null : text(a, span);
    }

    private String unescape(final String contents) {
        final byte[] a = bytes(contents);
        final int n = json.unescape(a, 0, a.length);
        assertThat(n).isNotEqualTo(Json.RAW);
        return new String(json.buffer(), 0, n, StandardCharsets.UTF_8);
    }

    private static String text(final byte[] a, final long span) {
        assertThat(span).isNotEqualTo(Json.ABSENT);
        return new String(a, Json.start(span), Json.end(span) - Json.start(span), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // one byte replaced, inserted or deleted, or the text cut short
    private static byte[] mutate(final byte[] text, final Random random) {
        final int at = random.nextInt(text.length);
        final byte b = MUTANTS[random.nextInt(MUTANTS.length)];
        return switch (random.nextInt(4)) {
            case 0 -> {
                final byte[] m = text.clone();
                m[at] = b;
                yield m;
            }
            case 1 -> {
                final int where = random.nextInt(text.length + 1);
                final byte[] m = new byte[text.length + 1];
                System.arraycopy(text, 0, m, 0, where);
                m[where] = b;
                System.arraycopy(text, where, m, where + 1, text.length - where);
                yield m;
            }
            case 2 -> {
                final byte[] m = new byte[text.length - 1];
                System.arraycopy(text, 0, m, 0, at);
                System.arraycopy(text, at + 1, m, at, text.length - at - 1);
                yield m;
            }
            default -> Arrays.copyOf(text, at);
        };
    }

    /// A generated value: what the generator wrote, as it means it.
    private sealed interface Node permits Scalar, Text, Arr, Obj {
    }

    /// A number or a literal, its text as written.
    private record Scalar(String written) implements Node {
    }

    /// A string, its decoded value.
    private record Text(String decoded) implements Node {
    }

    private record Arr(List<Node> elements) implements Node {
    }

    private record Obj(List<Member> members) implements Node {
    }

    private record Member(String name, Node value) {
    }

    /// A path in `JsonPath` text and the span it names, `[start, end)` in the text, or null when it names none.
    private record Probe(String path, int[] span) {
    }

    /// Random JSON values, written with random whitespace and escapes; it records the byte span of each value it
    /// writes, so the answer of every path is known without parsing.
    private static final class Generator {

        private final Random random;
        private final Map<Node, int[]> spans = new IdentityHashMap<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Generator(final Random random) {
            this.random = random;
        }

        Node value(final int depth) {
            final int kind = random.nextInt(depth >= MAX_DEPTH ? 3 : 5);
            return switch (kind) {
                case 0 -> new Scalar(number());
                case 1 -> new Scalar(List.of("true", "false", "null").get(random.nextInt(3)));
                case 2 -> new Text(decoded());
                case 3 -> {
                    final List<Node> elements = new ArrayList<>();
                    for (int i = random.nextInt(MAX_WIDTH + 1); i > 0; i--) {
                        elements.add(value(depth + 1));
                    }
                    yield new Arr(elements);
                }
                default -> {
                    final List<Member> members = new ArrayList<>();
                    for (int i = random.nextInt(MAX_WIDTH + 1); i > 0; i--) {
                        members.add(new Member(NAMES.get(random.nextInt(NAMES.size())), value(depth + 1)));
                    }
                    yield new Obj(members);
                }
            };
        }

        byte[] write(final Node root) {
            whitespace();
            node(root);
            whitespace();
            return out.toByteArray();
        }

        // a path to every value, each step a member's first of its name, plus a missing step below each value
        List<Probe> probes(final Node root) {
            final List<Probe> probes = new ArrayList<>();
            probe(root, random.nextBoolean() ? "$" : "", probes);
            return probes;
        }

        private void probe(final Node node, final String path, final List<Probe> probes) {
            probes.add(new Probe(path, spans.get(node)));
            switch (node) {
                case Arr(final List<Node> elements) -> {
                    for (int i = 0; i < elements.size(); i++) {
                        probe(elements.get(i), path + '[' + i + ']', probes);
                    }
                    probes.add(new Probe(path + '[' + (elements.size() + random.nextInt(3)) + ']', null));
                    probes.add(new Probe(path + ".a", null));
                }
                case Obj(final List<Member> members) -> {
                    for (final Member member : members) {
                        probe(first(members, member.name()), path + step(member.name()), probes);
                    }
                    probes.add(new Probe(path + step(MISSING), null));
                    probes.add(new Probe(path + "[0]", null));
                }
                case Scalar _, Text _ -> {
                    probes.add(new Probe(path + "[0]", null));
                    probes.add(new Probe(path + ".a", null));
                }
            }
        }

        private static Node first(final List<Member> members, final String name) {
            for (final Member member : members) {
                if (member.name().equals(name)) {
                    return member.value();
                }
            }
            throw new AssertionError(name);
        }

        // a member step: .name where the grammar allows it and the draw says so, else ["name"] escaped at random
        private String step(final String name) {
            if (!name.isEmpty() && name.indexOf('.') < 0 && name.indexOf('[') < 0 && random.nextBoolean()) {
                return '.' + name;
            }
            return "[" + encoded(name) + ']';
        }

        private void node(final Node node) {
            final int start = out.size();
            switch (node) {
                case Scalar(final String written) -> out.writeBytes(bytes(written));
                case Text(final String decoded) -> out.writeBytes(bytes(encoded(decoded)));
                case Arr(final List<Node> elements) -> {
                    out.write('[');
                    whitespace();
                    for (int i = 0; i < elements.size(); i++) {
                        if (i > 0) {
                            out.write(',');
                            whitespace();
                        }
                        node(elements.get(i));
                        whitespace();
                    }
                    out.write(']');
                }
                case Obj(final List<Member> members) -> {
                    out.write('{');
                    whitespace();
                    for (int i = 0; i < members.size(); i++) {
                        if (i > 0) {
                            out.write(',');
                            whitespace();
                        }
                        out.writeBytes(bytes(encoded(members.get(i).name())));
                        whitespace();
                        out.write(':');
                        whitespace();
                        node(members.get(i).value());
                        whitespace();
                    }
                    out.write('}');
                }
            }
            spans.put(node, new int[]{start, out.size()});
        }

        private void whitespace() {
            if (random.nextBoolean()) {
                for (int i = random.nextInt(3); i >= 0; i--) {
                    out.write(" \t\n\r".charAt(random.nextInt(4)));
                }
            }
        }

        private String number() {
            final StringBuilder sb = new StringBuilder();
            if (random.nextBoolean()) {
                sb.append('-');
            }
            if (random.nextInt(4) == 0) {
                sb.append('0');
            } else {
                sb.append((char) ('1' + random.nextInt(9)));
                digits(sb, random.nextInt(6));
            }
            if (random.nextInt(3) == 0) {
                sb.append('.');
                digits(sb, 1 + random.nextInt(4));
            }
            if (random.nextInt(4) == 0) {
                sb.append(random.nextBoolean() ? 'e' : 'E');
                final int sign = random.nextInt(3);
                if (sign > 0) {
                    sb.append(sign == 1 ? '+' : '-');
                }
                digits(sb, 1 + random.nextInt(3));
            }
            return sb.toString();
        }

        private void digits(final StringBuilder sb, final int n) {
            for (int i = 0; i < n; i++) {
                sb.append((char) ('0' + random.nextInt(10)));
            }
        }

        // a string's value: printable ASCII, the characters that must be escaped, controls, two- three- and
        // four-byte characters, and lone surrogates
        private String decoded() {
            final StringBuilder sb = new StringBuilder();
            for (int i = random.nextInt(9); i > 0; i--) {
                switch (random.nextInt(10)) {
                    case 0 -> sb.append("\"\\/".charAt(random.nextInt(3)));
                    case 1 -> sb.append((char) random.nextInt(0x20));
                    case 2 -> sb.append('\u00e9');
                    case 3 -> sb.append('\u20ac');
                    case 4 -> sb.append(EMOJI);
                    case 5 -> sb.append(random.nextBoolean() ? '\uD83D' : '\uDE00');
                    default -> sb.append((char) (0x20 + random.nextInt(0x5F)));
                }
            }
            return sb.toString();
        }

        // value as a JSON string: what must be escaped is, a lone surrogate as a u-escape, anything else at random
        private String encoded(final String value) {
            final StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                final boolean pair = Character.isHighSurrogate(c) && i + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(i + 1));
                if (pair) {
                    if (random.nextBoolean()) {
                        sb.append(c).append(value.charAt(i + 1));
                    } else {
                        sb.append(hex(c)).append(hex(value.charAt(i + 1)));
                    }
                    i++;
                } else if (Character.isSurrogate(c)) {
                    sb.append(hex(c));
                } else if (c == '"' || c == '\\') {
                    sb.append(random.nextInt(4) == 0 ? hex(c) : "\\" + c);
                } else if (c < 0x20) {
                    final int at = "\b\f\n\r\t".indexOf(c);
                    sb.append(at >= 0 && random.nextBoolean() ? "\\" + "bfnrt".charAt(at) : hex(c));
                } else if (random.nextInt(8) == 0) {
                    sb.append(c == '/' && random.nextBoolean() ? "\\/" : hex(c));
                } else {
                    sb.append(c);
                }
            }
            return sb.append('"').toString();
        }

        private String hex(final char c) {
            final String digits = String.format("%04x", (int) c);
            return "\\u" + (random.nextBoolean() ? digits : digits.toUpperCase());
        }
    }

    /// A recursive-descent reader of RFC 8259's grammar, written from the RFC and not from the scanner: it answers
    /// the offset where its reading of a text fails (the end of the input when it ends early), or `VALID`. Bytes from
    /// 0x80 up are taken as string content, as the scanner takes its input to be well-formed UTF-8.
    private static final class Reference {

        private final byte[] a;
        private final int to;
        private int p;

        private Reference(final byte[] a, final int from, final int to) {
            this.a = a;
            this.to = to;
            this.p = from;
        }

        static int invalidAt(final byte[] a, final int from, final int to) {
            final Reference r = new Reference(a, from, to);
            r.ws();
            if (!r.value()) {
                return r.p;
            }
            r.ws();
            return r.p == to ? Json.VALID : r.p;
        }

        private boolean value() {
            if (p >= to) {
                return false;
            }
            return switch (a[p]) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true");
                case 'f' -> word("false");
                case 'n' -> word("null");
                default -> number();
            };
        }

        private boolean object() {
            p++;
            ws();
            if (p < to && a[p] == '}') {
                p++;
                return true;
            }
            while (true) {
                if (p >= to || a[p] != '"' || !string()) {
                    return false;
                }
                ws();
                if (p >= to || a[p] != ':') {
                    return false;
                }
                p++;
                ws();
                if (!value()) {
                    return false;
                }
                ws();
                if (p >= to) {
                    return false;
                }
                if (a[p] == '}') {
                    p++;
                    return true;
                }
                if (a[p] != ',') {
                    return false;
                }
                p++;
                ws();
            }
        }

        private boolean array() {
            p++;
            ws();
            if (p < to && a[p] == ']') {
                p++;
                return true;
            }
            while (true) {
                if (!value()) {
                    return false;
                }
                ws();
                if (p >= to) {
                    return false;
                }
                if (a[p] == ']') {
                    p++;
                    return true;
                }
                if (a[p] != ',') {
                    return false;
                }
                p++;
                ws();
            }
        }

        private boolean string() {
            p++;
            while (p < to) {
                final int b = a[p] & 0xFF;
                if (b == '"') {
                    p++;
                    return true;
                }
                if (b < 0x20) {
                    return false;
                }
                p++;
                if (b == '\\') {
                    if (p >= to) {
                        return false;
                    }
                    final byte e = a[p];
                    if (e == 'u') {
                        p++;
                        for (int i = 0; i < 4; i++) {
                            if (p >= to || !isHex(a[p])) {
                                return false;
                            }
                            p++;
                        }
                    } else if ("\"\\/bfnrt".indexOf(e) >= 0) {
                        p++;
                    } else {
                        return false;
                    }
                }
            }
            return false;
        }

        private boolean number() {
            if (a[p] == '-') {
                p++;
            }
            if (p >= to || !isDigit(a[p])) {
                return false;
            }
            if (a[p] == '0') {
                p++;
            } else {
                skipDigits();
            }
            if (p < to && a[p] == '.') {
                p++;
                if (p >= to || !isDigit(a[p])) {
                    return false;
                }
                skipDigits();
            }
            if (p < to && (a[p] == 'e' || a[p] == 'E')) {
                p++;
                if (p < to && (a[p] == '+' || a[p] == '-')) {
                    p++;
                }
                if (p >= to || !isDigit(a[p])) {
                    return false;
                }
                skipDigits();
            }
            return true;
        }

        private boolean word(final String w) {
            for (int i = 0; i < w.length(); i++) {
                if (p >= to || a[p] != w.charAt(i)) {
                    return false;
                }
                p++;
            }
            return true;
        }

        private void skipDigits() {
            while (p < to && isDigit(a[p])) {
                p++;
            }
        }

        private void ws() {
            while (p < to && (a[p] == ' ' || a[p] == '\t' || a[p] == '\n' || a[p] == '\r')) {
                p++;
            }
        }

        private static boolean isDigit(final byte b) {
            return b >= '0' && b <= '9';
        }

        private static boolean isHex(final byte b) {
            return isDigit(b) || b >= 'a' && b <= 'f' || b >= 'A' && b <= 'F';
        }
    }
}
