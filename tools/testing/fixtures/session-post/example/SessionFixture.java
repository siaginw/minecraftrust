package example;

import java.util.Objects;

/**
 * POST-writer form of the session-bound engine fixture.
 *
 * <p>Same binary name as {@code tools/testing/fixtures/session/example/SessionFixture.java},
 * one extra declared writer effect: a second
 * {@code java/util/Objects.requireNonNull} invokestatic inside the same
 * {@code finally} bracket, standing in for a {@code WRITE_BEGIN}/{@code WRITE_END}
 * pair.</p>
 *
 * <p>It is a separate source file rather than a mutation of the compiled pre
 * bytes because the control is meant to show that the engine tells two real,
 * separately compiled classes apart and binds the admission certificate to the
 * one the writer was handed. A single classfile described two different ways
 * would prove nothing about Blocker A.</p>
 *
 * <p>Nothing outside {@code exercise} differs, so the placement validator's
 * undeclared-edit check has a real negative to state: any other method changing
 * would be an undeclared edit.</p>
 */
public class SessionFixture {
    static final String SESSION_ID = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    private int invocations;

    public void exercise(int value) {
        try {
            Objects.requireNonNull(SESSION_ID);
            Objects.requireNonNull(Integer.valueOf(value));
            if (value < 0) {
                throw new IllegalStateException(SESSION_ID);
            }
        } finally {
            invocations++;
        }
    }

    @org.spongepowered.asm.mixin.transformer.meta.MixinMerged(sessionId = SESSION_ID)
    public void handler$zzf000(Object callbackInfo) {
    }
}
