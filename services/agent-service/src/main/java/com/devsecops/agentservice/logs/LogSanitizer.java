package com.devsecops.agentservice.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Prepares a CI log to be handed to a model.
 *
 * <p>A build log is untrusted input in two directions. It can carry a secret -
 * a token echoed by a debug step, a password in an environment dump, a private
 * key printed by a failing test - and it can be arbitrarily long. Both are
 * handled here, before the text ever reaches an engine, so no engine has to
 * remember to do it and the rule lives in one place.
 *
 * <p>This is redaction, not encryption: it removes values that match well-known
 * secret shapes and the agent's own configured token. It is a seatbelt, not a
 * guarantee - which is exactly why the spec says secrets reach the agent only
 * through the environment, so there is little for a log to leak in the first
 * place.
 */
@Component
public class LogSanitizer {

    /** Shown in place of anything removed, so a reader can see that a removal happened. */
    static final String REDACTED = "[REDACTED]";

    /**
     * Marker inserted where a long log was cut. Deliberately verbose: a model
     * that sees it can say "the log was truncated" instead of inventing the
     * missing middle, and a human reading the audit log can see the same.
     */
    static final String TRUNCATION_MARKER =
            "\n... [log truncated by the agent: %d characters omitted] ...\n";

    /**
     * The secret shapes worth knowing by sight. Order does not matter - every
     * pattern is applied to every log - but the list is grouped by source.
     *
     * <p>Each replacement keeps the key and drops the value ("token: xxx"
     * becomes "token: [REDACTED]") so the log stays readable and the diagnosis
     * can still say *which* credential was involved.
     */
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            // GitHub's own token prefixes. github_pat_ is checked by its own
            // pattern because it contains underscores, unlike the gh*_ family.
            Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{16,})\\b"),
            Pattern.compile("\\b(github_pat_[A-Za-z0-9_]{20,})\\b"),
            // AWS access key ids, which are a fixed, recognisable shape.
            Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b"),
            // "Bearer <token>" in a curl trace or a header dump.
            Pattern.compile("(?i)(bearer\\s+)([A-Za-z0-9._~+/-]{16,}=*)"),
            // key<separator>value pairs: password=, TOKEN:, api_key = , and the
            // JSON/YAML quoted forms "token":"...". The key and the separator
            // are kept, only the value is replaced. A trailing comma or brace is
            // excluded from the value so JSON stays parseable after redaction.
            Pattern.compile("(?i)\\b(pass(?:word|wd)?|secret|token|api[_-]?key|access[_-]?key|"
                    + "client[_-]?secret|private[_-]?key|credentials?)\\b"
                    + "(\\s*[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',}]{4,})"),
            // An entire PEM private key block, header to footer.
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"));

    /** The replacement for each pattern above, matched by index. */
    private static final List<String> REPLACEMENTS = List.of(
            REDACTED,
            REDACTED,
            REDACTED,
            "$1" + REDACTED,
            "$1$2" + REDACTED,
            REDACTED);

    /** Output must not exceed the configured budget, so a missing one still truncates. */
    private static final int FALLBACK_MAX_CHARS = 12000;

    /**
     * The head gets the larger share of the surviving budget: the first failure
     * in a log is usually the cause, and everything after it is fallout. The
     * tail is kept because a tool chain often prints its summary there.
     */
    private static final double HEAD_FRACTION = 0.6;

    /**
     * Redact, then truncate. Always both, always in this order - redacting first
     * means a secret cannot survive by being outside the window that truncation
     * would have kept.
     *
     * @param raw            the log as fetched, which may be null
     * @param knownSecret    a value the caller already holds and wants masked
     *                       unconditionally (the agent's own GitHub token);
     *                       null or blank is ignored
     * @param maxChars       the guardrail from {@code agent.limits.max-log-chars}
     * @return a redacted, truncated copy; never null
     */
    public String sanitize(String raw, String knownSecret, int maxChars) {
        String text = raw == null ? "" : raw;
        text = redactKnownSecret(text, knownSecret);
        text = redactPatterns(text);
        return truncate(text, maxChars);
    }

    /**
     * Masks a specific value the caller knows is a secret, wherever it appears.
     *
     * <p>Needed because a token has no distinctive shape: GitHub's opaque token
     * for this repository is just an alphanumeric string, so only literal
     * replacement can catch it. {@link String#replace} is used rather than a
     * pattern so the token is never compiled as regex - an unescaped token
     * containing {@code .} or {@code +} would otherwise match far too much.
     */
    public String redactKnownSecret(String text, String knownSecret) {
        if (text == null || knownSecret == null || knownSecret.isBlank()) {
            return text;
        }
        return text.replace(knownSecret, REDACTED);
    }

    /** Applies every known secret shape to the text. */
    public String redactPatterns(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (int i = 0; i < SECRET_PATTERNS.size(); i++) {
            Matcher matcher = SECRET_PATTERNS.get(i).matcher(result);
            if (matcher.find()) {
                result = matcher.replaceAll(REPLACEMENTS.get(i));
            }
        }
        return result;
    }

    /**
     * Keeps the head and the tail and drops the middle, rather than cutting the
     * text at the limit. A plain cut loses the summary a build prints at the
     * end, which is often the single most useful line in the file.
     *
     * <p>The marker between the halves is deliberate: it tells the model that
     * something is missing, which is what stops it from confidently explaining a
     * gap it cannot see.
     */
    public String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int limit = maxChars > 0 ? maxChars : FALLBACK_MAX_CHARS;
        if (text.length() <= limit) {
            return text;
        }

        String marker = String.format(TRUNCATION_MARKER, text.length() - limit);
        if (marker.length() >= limit) {
            return marker.substring(0, limit);
        }

        int contentLength = limit - marker.length();
        int headLength = (int) (contentLength * HEAD_FRACTION);
        int tailLength = contentLength - headLength;
        String head = text.substring(0, headLength);
        String tail = text.substring(text.length() - tailLength);
        int omitted = text.length() - headLength - tailLength;

        marker = String.format(TRUNCATION_MARKER, omitted);
        if (marker.length() > limit - headLength - tailLength) {
            contentLength = Math.max(0, limit - marker.length());
            headLength = Math.min(headLength, (int) (contentLength * HEAD_FRACTION));
            tailLength = contentLength - headLength;
            head = text.substring(0, headLength);
            tail = tailLength == 0 ? "" : text.substring(text.length() - tailLength);
            marker = String.format(TRUNCATION_MARKER, text.length() - headLength - tailLength);
        }

        return head + marker + tail;
    }

    /**
     * The last few non-blank lines of a log.
     *
     * <p>Used by the heuristic engine as a fallback "evidence" when no rule
     * matches: a build failure is almost always described by its own last lines,
     * so they are a more honest answer than an empty array.
     */
    public List<String> lastNonBlankLines(String text, int count) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.split("\r?\n");
        List<String> tail = new ArrayList<>();
        for (int i = lines.length - 1; i >= 0 && tail.size() < count; i--) {
            String trimmed = lines[i].strip();
            if (!trimmed.isEmpty()) {
                tail.add(0, trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed);
            }
        }
        return tail;
    }
}
