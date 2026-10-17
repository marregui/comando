package dev.marregui.comando.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.marregui.comando.core.view.ViewSpec;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates view data from MCP calls into {@link ViewSpec} records. An invalid spec throws
 * {@link IllegalArgumentException} with a message Claude can act on; it never reaches the reducer.
 */
final class ViewSpecs {

    static final int MAX_ROWS = 100_000;
    static final int MAX_POINTS = 2_000_000;

    private ViewSpecs() {
    }

    static ViewSpec parse(String type, JsonNode data) {
        return switch (type) {
            case "table" -> table(data.path("columns"), data.path("rows"));
            case "plot" -> plot(data);
            case "diff" -> new ViewSpec.Diff(text(data, "path", false), text(data, "before", true), text(data, "after", true));
            case "markdown" -> new ViewSpec.Markdown(text(data, "markdown", true));
            case "text" -> new ViewSpec.Text(path(text(data, "path", false)), text(data, "text", true), text(data, "syntax", false),
                    data.path("line").asInt(0));
            case "tree" -> new ViewSpec.Tree(node(data.path("root"), 0));
            default -> throw new IllegalArgumentException("unknown view type '" + type + "'; use table, plot, diff, markdown, text or tree");
        };
    }

    /** A text view's path: empty, or one the file system can name, so the view can show it. */
    private static String path(String path) {
        try {
            Path.of(path);
            return path;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("path is not a file path: " + e.getReason());
        }
    }

    static ViewSpec.Table table(JsonNode columns, JsonNode rows) {
        if (!columns.isArray() || columns.isEmpty()) {
            throw new IllegalArgumentException("columns must be a non-empty array of names");
        }
        if (!rows.isArray()) {
            throw new IllegalArgumentException("rows must be an array of arrays");
        }
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("at most " + MAX_ROWS + " rows; got " + rows.size());
        }
        List<String> names = new ArrayList<>(columns.size());
        for (JsonNode c : columns) {
            names.add(c.asText());
        }
        List<List<String>> out = new ArrayList<>(rows.size());
        int i = 0;
        for (JsonNode r : rows) {
            if (!r.isArray() || r.size() != names.size()) {
                throw new IllegalArgumentException("row " + i + " must be an array of " + names.size() + " values");
            }
            List<String> row = new ArrayList<>(names.size());
            for (JsonNode v : r) {
                row.add(v.isNull() ? "null" : v.isValueNode() ? v.asText() : v.toString());
            }
            out.add(row);
            i++;
        }
        return new ViewSpec.Table(names, out);
    }

    static ViewSpec.Plot plot(JsonNode data) {
        JsonNode series = data.path("series");
        if (!series.isArray() || series.isEmpty()) {
            throw new IllegalArgumentException("series must be a non-empty array of {name, x, y}");
        }
        List<ViewSpec.Series> out = new ArrayList<>(series.size());
        long points = 0;
        for (JsonNode s : series) {
            double[] x = numbers(s.path("x"), "x");
            double[] y = numbers(s.path("y"), "y");
            points += x.length;
            if (points > MAX_POINTS) {
                throw new IllegalArgumentException("at most " + MAX_POINTS + " points in total");
            }
            out.add(new ViewSpec.Series(s.path("name").asText("series " + (out.size() + 1)), x, y));
        }
        return new ViewSpec.Plot(data.path("x_label").asText(""), data.path("y_label").asText(""), data.path("time_x").asBoolean(false), out);
    }

    private static double[] numbers(JsonNode array, String what) {
        if (!array.isArray()) {
            throw new IllegalArgumentException(what + " must be an array of numbers");
        }
        double[] out = new double[array.size()];
        int i = 0;
        for (JsonNode v : array) {
            if (!v.isNumber()) {
                throw new IllegalArgumentException(what + "[" + i + "] is not a number: " + v);
            }
            out[i++] = v.asDouble();
        }
        return out;
    }

    private static ViewSpec.TreeNode node(JsonNode n, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("tree deeper than 64 levels");
        }
        if (!n.isObject()) {
            throw new IllegalArgumentException("a tree node is an object {label, detail, children}");
        }
        List<ViewSpec.TreeNode> children = new ArrayList<>();
        for (JsonNode c : n.path("children")) {
            children.add(node(c, depth + 1));
        }
        return new ViewSpec.TreeNode(n.path("label").asText(""), n.path("detail").asText(""), children);
    }

    private static String text(JsonNode data, String field, boolean required) {
        JsonNode v = data.path(field);
        if (v.isMissingNode() || v.isNull()) {
            if (required) {
                throw new IllegalArgumentException(field + " is required");
            }
            return "";
        }
        return v.asText();
    }
}
