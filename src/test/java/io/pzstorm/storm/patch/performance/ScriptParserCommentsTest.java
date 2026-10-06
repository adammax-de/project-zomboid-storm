package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import zombie.scripting.ScriptParser;

class ScriptParserCommentsTest implements UnitTest {
    private static String optimized(String input) {
        String value = ScriptCommentScanner.scan(input);
        return value == null ? ScriptParser.stripComments(input) : value;
    }

    @Test
    void exhaustiveShortInputsMatchInstalledParser() {
        char[] alphabet = {'/', '*', 'x'};
        for (int length = 0, combinations = 1; length <= 11; length++, combinations *= 3) {
            char[] text = new char[length];
            for (int code = 0; code < combinations; code++) {
                int n = code;
                for (int i = 0; i < length; i++, n /= 3) text[i] = alphabet[n % 3];
                String input = new String(text);
                assertEquals(ScriptParser.stripComments(input), optimized(input), input);
            }
        }
    }

    @Test
    void nestedCommentsUnicodeQuotesAndMalformedInputsMatch() {
        assertEquals(ScriptParser.stripComments(null), optimized(null));
        Random random = new Random(4221);
        String[] tokens = {
            "/*",
            "*/",
            "//",
            "\"",
            "'",
            "\n",
            "\r\n",
            "界",
            "😀",
            "module Base {",
            "/",
            "*",
            " ",
            "x"
        };
        for (int sample = 0; sample < 20_000; sample++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 60; i++) text.append(tokens[random.nextInt(tokens.length)]);
            String input = text.toString();
            assertEquals(ScriptParser.stripComments(input), optimized(input), input);
        }
        assertNotNull(ScriptCommentScanner.scan("a /* comment */ b /* another */ c"));
        assertNull(ScriptCommentScanner.scan("a /* outer /* inner */ end */ b"));
        String threeDeep = "/*\n/*'/**/\n*/ */";
        assertEquals(ScriptParser.stripComments(threeDeep), optimized(threeDeep));
        assertNull(ScriptCommentScanner.scan("/*/"));
        assertNull(ScriptCommentScanner.scan("a /* unfinished"));
        assertEquals("//keep\n\"\"", optimized("//keep\n\"/*remove*/\""));
    }

    @Test
    void transformedGameMethodExecutesBothFastAndFallbackPaths() throws Exception {
        Method method =
                ServerLoadTestSupport.woven(new ScriptParserCommentsPatch())
                        .getMethod("stripComments", String.class);
        for (String input :
                new String[] {
                    null,
                    "",
                    "a/* b */c",
                    "/* a /* b */ c */z",
                    "/*\n/*'/**/\n*/ */",
                    "/*/",
                    "*/a/*b",
                    "/*unclosed"
                }) {
            assertEquals(ScriptParser.stripComments(input), method.invoke(null, input));
        }
    }

    @Test
    void installedGameAndOptionalModScriptCorpusMatches() throws Exception {
        Path scripts = Path.of(System.getProperty("storm.server.path"), "media/scripts");
        assertTrue(Files.isDirectory(scripts), "Configured game scripts are required for parity");
        int count = corpus(scripts);
        String mods = System.getProperty("storm.loadtest.mods");
        if (mods != null) count += corpus(Path.of(mods));
        assertTrue(count > 100, "Expected the real Build 42 script corpus");
        System.out.println("Comment parser native parity: " + count + " script files");
    }

    private static int corpus(Path root) throws Exception {
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths =
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".txt"))
                            .filter(
                                    p ->
                                            p.toString()
                                                    .replace('\\', '/')
                                                    .contains("/media/scripts/"))
                            .toList();
        }
        int fast = 0;
        for (Path path : paths) {
            // Match InputStreamReader's replacement behavior for non-UTF-8 legacy mod text.
            String input = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            assertEquals(ScriptParser.stripComments(input), optimized(input), path.toString());
            if (ScriptCommentScanner.scan(input) != null) fast++;
        }
        System.out.println(
                "Script corpus: " + paths.size() + " files, " + fast + " use the linear path");
        return paths.size();
    }
}
