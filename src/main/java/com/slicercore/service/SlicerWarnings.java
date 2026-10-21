package com.slicercore.service;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects what the Bambu CLI warned about during a slice, from the two places it can say
 * so, and shapes the lines for storage and display.
 *
 * {@code result.json} is scanned for any field whose name contains "warning", wherever it
 * sits (top level, per plate, per object), because the file's warning layout is not pinned
 * by the feasibility note: the verified sample carried none. A successful run whose
 * {@code error_string} is not the usual "Success." is reported as well. The CLI's console
 * output contributes every line that starts with "warning".
 */
final class SlicerWarnings {

    /** Upper bound on the warnings kept per slice; the owner inspects the model anyway. */
    static final int MAX_WARNINGS = 10;
    /** Upper bound on one warning's length; longer lines are cut with an ellipsis. */
    static final int MAX_LENGTH = 200;

    private static final String SUCCESS = "Success.";
    private static final String ELLIPSIS = "...";
    /** "warning: ...", "Warning - ..." or "[warning] ...", with a message after the prefix. */
    private static final Pattern WARNING_LINE =
            Pattern.compile("^\\s*\\[?warning(?![a-z])\\]?[\\s:\\-]*([^\\s:\\-].*)$", Pattern.CASE_INSENSITIVE);
    private static final List<String> MESSAGE_FIELDS = List.of("message", "msg", "text", "detail", "error_string");

    private SlicerWarnings() {
    }

    /** The message of a console line that starts with "warning", if it is one. */
    static Optional<String> fromOutputLine(String line) {
        Matcher matcher = WARNING_LINE.matcher(line);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** Every warning {@code result.json} carries, in document order, unshaped. */
    static List<String> fromResultJson(JsonNode root) {
        List<String> found = new ArrayList<>();
        String errorString = root.path("error_string").asString("");
        if (root.path("return_code").asInt(0) == 0 && !errorString.isBlank() && !SUCCESS.equals(errorString)) {
            found.add(errorString);
        }
        collect(root, found);
        return found;
    }

    /**
     * Trims, drops blanks, removes duplicates while keeping the first occurrence, cuts long
     * lines and keeps at most {@link #MAX_WARNINGS}, in the order given.
     */
    @SafeVarargs
    static List<String> normalize(List<String>... sources) {
        Set<String> unique = new LinkedHashSet<>();
        for (List<String> source : sources) {
            for (String raw : source) {
                if (raw == null) {
                    continue;
                }
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.length() > MAX_LENGTH) {
                    line = line.substring(0, MAX_LENGTH - ELLIPSIS.length()).stripTrailing() + ELLIPSIS;
                }
                unique.add(line);
                if (unique.size() == MAX_WARNINGS) {
                    return List.copyOf(unique);
                }
            }
        }
        return List.copyOf(unique);
    }

    private static void collect(JsonNode node, List<String> into) {
        if (node.isObject()) {
            for (var property : node.properties()) {
                if (property.getKey().toLowerCase(Locale.ROOT).contains("warning")) {
                    messages(property.getValue(), into);
                } else {
                    collect(property.getValue(), into);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, into));
        }
    }

    /**
     * A warning field may be a string, a list of strings or objects, or one object. An empty
     * string is what an untroubled plate or object carries and is no warning.
     */
    private static void messages(JsonNode node, List<String> into) {
        if (node.isArray()) {
            node.forEach(child -> messages(child, into));
        } else if (node.isObject()) {
            into.add(MESSAGE_FIELDS.stream()
                    .map(node::path)
                    .filter(JsonNode::isString)
                    .map(JsonNode::asString)
                    .findFirst()
                    .orElseGet(node::toString));
        } else if (node.isValueNode() && !node.isNull() && !node.asString().isBlank()) {
            into.add(node.asString());
        }
    }
}
