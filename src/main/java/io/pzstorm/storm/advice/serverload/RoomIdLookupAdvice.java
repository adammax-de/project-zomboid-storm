package io.pzstorm.storm.advice.serverload;

import io.pzstorm.storm.patch.performance.RoomIdLookup;
import net.bytebuddy.asm.Advice;

public final class RoomIdLookupAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static RoomIdLookup.Scope enter(@Advice.Argument(0) Object cell) {
        return RoomIdLookup.begin(cell);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter RoomIdLookup.Scope scope) {
        RoomIdLookup.end(scope);
    }
}
