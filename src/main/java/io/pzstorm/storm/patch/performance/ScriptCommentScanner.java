package io.pzstorm.storm.patch.performance;

/** Linear scan of simple block comments; nested/ambiguous/malformed input stays with vanilla. */
public final class ScriptCommentScanner {
    private ScriptCommentScanner() {}

    /**
     * Returns null to run the original parser. Build 42 strips comment markers even inside quotes
     * and does not strip // comments. Its backward scan has unusual unmatched/overlapping delimiter
     * behavior; do not reinterpret those inputs as a different script language.
     */
    public static String scan(String input) {
        if (input == null) return "null"; // StringBuilder.append(String)'s native behavior.
        StringBuilder output = null;
        int depth = 0;
        int copyFrom = 0;
        for (int i = 0; i + 1 < input.length(); i++) {
            char a = input.charAt(i), b = input.charAt(i + 1);
            boolean open = a == '/' && b == '*';
            boolean close = a == '*' && b == '/';
            if (!open && !close) continue;
            // /*/ and */* share a character between markers; the native reverse scan owns them.
            if (i + 2 < input.length() && input.charAt(i + 2) == a) return null;
            if (open) {
                // The native reverse algorithm is not a conventional balanced-nesting parser.
                if (depth != 0) return null;
                if (depth++ == 0) {
                    if (output == null) output = new StringBuilder(input.length());
                    output.append(input, copyFrom, i);
                }
            } else {
                if (depth == 0) return null;
                if (--depth == 0) copyFrom = i + 2;
            }
            i++;
        }
        if (depth != 0) return null;
        return output == null ? input : output.append(input, copyFrom, input.length()).toString();
    }
}
