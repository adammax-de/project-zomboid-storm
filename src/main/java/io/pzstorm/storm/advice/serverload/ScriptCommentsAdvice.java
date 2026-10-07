package io.pzstorm.storm.advice.serverload;

import io.pzstorm.storm.patch.performance.ScriptCommentScanner;
import net.bytebuddy.asm.Advice;

public final class ScriptCommentsAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static String enter(@Advice.Argument(0) String input) {
        return ScriptCommentScanner.scan(input);
    }

    @Advice.OnMethodExit
    public static void exit(
            @Advice.Enter String scanned, @Advice.Return(readOnly = false) String result) {
        if (scanned != null) result = scanned;
    }
}
