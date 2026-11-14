package dev.marregui.comando.cdb.exec.expr;

/**
 * A {@code to_str} format, compiled once. A run of one pattern letter is cut greedily into the longest tokens QuestDB
 * knows for it, so {@code SSSS} is {@code SSS} then {@code S}; any other character is copied. Tokens: {@code y} year,
 * {@code yy} two digits, {@code yyy} three, {@code yyyy} four; {@code M}, {@code MM}, {@code MMM} ({@code Mar}),
 * {@code MMMM} ({@code March}); {@code d}, {@code dd}; {@code E} ({@code Wed}), {@code EE} ({@code Wednesday});
 * {@code H}, {@code HH}; {@code h}, {@code hh} (12-hour, 0 shown as 12); {@code K} (0 to 11); {@code a} (AM, PM);
 * {@code m}, {@code mm}; {@code s}, {@code ss}; {@code S}, {@code SSS} milliseconds; {@code U}, {@code UUU} the
 * microseconds past the millisecond.
 */
final class DateFormat {

    private static final String LETTERS = "yMdEHhKamsSU";
    // per letter of LETTERS, the token lengths QuestDB's date format knows, longest first
    private static final int[][] WIDTHS = {
            {4, 3, 2, 1}, {4, 3, 2, 1}, {2, 1}, {2, 1}, {2, 1}, {2, 1}, {1}, {1}, {2, 1}, {2, 1}, {3, 1}, {3, 1},
    };
    private static final String[] MONTHS = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
    };
    private static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    // a token is letter * 8 + width; a literal character c is -(c + 1)
    private final int[] tokens;

    private DateFormat(final int[] tokens) {
        this.tokens = tokens;
    }

    static DateFormat compile(final String format) {
        final int[] tokens = new int[format.length()];
        int count = 0;
        int i = 0;
        while (i < format.length()) {
            final char c = format.charAt(i);
            final int letter = LETTERS.indexOf(c);
            if (letter < 0) {
                tokens[count++] = -(c + 1);
                i++;
                continue;
            }
            int run = 1;
            while (i + run < format.length() && format.charAt(i + run) == c) {
                run++;
            }
            i += run;
            while (run > 0) {
                for (final int width : WIDTHS[letter]) {
                    if (width <= run) {
                        tokens[count++] = letter * 8 + width;
                        run -= width;
                        break;
                    }
                }
            }
        }
        final int[] compiled = new int[count];
        System.arraycopy(tokens, 0, compiled, 0, count);
        return new DateFormat(compiled);
    }

    void format(final StringBuilder sink, final long micros) {
        final long packed = Civil.date(Math.floorDiv(micros, Civil.DAY_MICROS));
        final long timeOfDay = Math.floorMod(micros, Civil.DAY_MICROS);
        final int hour = (int) (timeOfDay / Civil.HOUR_MICROS);
        for (final int token : tokens) {
            if (token < 0) {
                sink.append((char) (-token - 1));
                continue;
            }
            final int width = token % 8;
            switch (LETTERS.charAt(token / 8)) {
                case 'y' -> {
                    final long year = Civil.year(packed);
                    switch (width) {
                        case 1 -> sink.append(year);
                        case 2 -> Civil.pad(sink, Math.floorMod(year, 100), 2);
                        case 3 -> Civil.pad(sink, Math.floorMod(year, 1000), 3);
                        default -> Civil.pad(sink, year, 4);
                    }
                }
                case 'M' -> {
                    final int month = Civil.month(packed);
                    switch (width) {
                        case 1, 2 -> Civil.pad(sink, month, width);
                        case 3 -> sink.append(MONTHS[month - 1], 0, 3);
                        default -> sink.append(MONTHS[month - 1]);
                    }
                }
                case 'd' -> Civil.pad(sink, Civil.day(packed), width);
                case 'E' -> {
                    final String day = DAYS[Civil.dayOfWeek(micros) - 1];
                    sink.append(day, 0, width == 1 ? 3 : day.length());
                }
                case 'H' -> Civil.pad(sink, hour, width);
                case 'h' -> Civil.pad(sink, hour % 12 == 0 ? 12 : hour % 12, width);
                case 'K' -> sink.append(hour % 12);
                case 'a' -> sink.append(hour < 12 ? "AM" : "PM");
                case 'm' -> Civil.pad(sink, timeOfDay / Civil.MINUTE_MICROS % 60, width);
                case 's' -> Civil.pad(sink, timeOfDay / Civil.SECOND_MICROS % 60, width);
                case 'S' -> Civil.pad(sink, timeOfDay / Civil.MILLI_MICROS % 1000, width);
                default -> Civil.pad(sink, timeOfDay % Civil.MILLI_MICROS, width);
            }
        }
    }
}
