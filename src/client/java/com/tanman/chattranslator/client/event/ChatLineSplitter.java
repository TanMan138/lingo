package com.tanman.chattranslator.client.event;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a rendered chat line into the server's sender decoration and the message
 * body.
 *
 * <p>Servers stack several chunks in front of the text — channel, rank, a chat-head
 * placeholder, the name, then a delimiter:
 * {@code [Global] [GOLD] [detemo head]detemo → текст}. Feeding all of that to the
 * language detector skews it towards English (the junk is Latin, the message is not)
 * and, on a paid backend, bills characters for decoration nobody reads.
 *
 * <p>Pure logic so it can be unit tested without a running client.
 */
public final class ChatLineSplitter {

    /** Leading {@code <name>} and {@code [chunk]} groups, however many are stacked. */
    private static final Pattern DECORATION =
            Pattern.compile("^\\s*(?:<[^<>]{1,32}>\\s*|\\[[^\\[\\]]{1,48}]\\s*)+");

    /**
     * A bare sender name left after the bracket chunks, up to the delimiter the server
     * puts between name and message.
     */
    private static final Pattern NAME_AND_DELIMITER =
            Pattern.compile("^[A-Za-z0-9_.]{1,32}\\s*[→▸»>:|]+\\s*");

    /** A delimiter with no name in front of it, e.g. a line that starts {@code → hi}. */
    private static final Pattern LEADING_DELIMITER = Pattern.compile("^\\s*[→▸»>:|]+\\s*");

    private ChatLineSplitter() {
    }

    public record ChatLine(String prefix, String body) {
    }

    /**
     * @param rendered the whole line as shown
     * @return the decoration and the message body; the body falls back to the whole
     *         line whenever stripping would leave nothing to translate
     */
    public static ChatLine split(String rendered) {
        if (rendered == null || rendered.isBlank()) {
            return new ChatLine("", rendered == null ? "" : rendered);
        }

        String rest = rendered;
        int consumed = 0;

        consumed += consume(DECORATION, rest);
        rest = rendered.substring(consumed);

        int nameLength = consume(NAME_AND_DELIMITER, rest);
        if (nameLength == 0) {
            nameLength = consume(LEADING_DELIMITER, rest);
        }
        consumed += nameLength;

        String body = rendered.substring(consumed);
        if (body.isBlank()) {
            return new ChatLine("", rendered);
        }
        return new ChatLine(rendered.substring(0, consumed), body);
    }

    private static int consume(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.lookingAt() ? matcher.end() : 0;
    }
}
