package dev.collabedit.crdt;

import java.util.Set;

public final class MarkTypes {
    private MarkTypes() {
    }

    public static final String BOLD = "bold";
    public static final String ITALIC = "italic";
    public static final String UNDERLINE = "underline";
    public static final String STRIKETHROUGH = "strike";
    public static final String CODE = "code";
    public static final String LINK = "link";

    public static final String COMMENT = "comment";

    public static final String SUGGEST_INSERT = "suggest-insert";

    public static final String SUGGEST_DELETE = "suggest-delete";

    public static final String HEADING = "heading";

    public static final String QUOTE = "quote";

    public static final String LIST = "list";

    private static final Set<String> EXPANDING =
        Set.of(BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, HEADING, QUOTE, LIST);

    public static boolean expandsAtEnd(String type) {
        return EXPANDING.contains(type);
    }
}
