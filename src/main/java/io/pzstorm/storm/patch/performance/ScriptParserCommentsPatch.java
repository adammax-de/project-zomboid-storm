package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Server-only comment stripping; native parsing and malformed-input behavior remain intact. */
public class ScriptParserCommentsPatch extends StormClassTransformer {
    public ScriptParserCommentsPatch() {
        super("zombie.scripting.ScriptParser");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.serverload.ScriptCommentsAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("stripComments")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(String.class))
                                        .and(ElementMatchers.returns(String.class))));
    }
}
