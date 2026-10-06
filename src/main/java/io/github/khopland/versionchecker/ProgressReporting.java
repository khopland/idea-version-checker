package io.github.khopland.versionchecker;

import com.intellij.platform.util.progress.RawProgressReporter;
import com.intellij.platform.util.progress.StepsKt;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;

/** Calls the public SDK helper without copying its internal handle calls into plugin bytecode. */
public final class ProgressReporting {
    private ProgressReporting() {}

    public static <T> Object report(
            Function1<? super RawProgressReporter, ? extends T> action,
            Continuation<? super T> continuation) {
        return StepsKt.reportRawProgress(action, continuation);
    }
}
