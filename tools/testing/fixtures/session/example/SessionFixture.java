package example;

import java.util.Objects;

/**
 * PRE-writer form of the session-bound engine fixture.
 *
 * <p>Like {@code tools/testing/fixtures/example/Fixture.java} this is not a
 * Minecraft class and carries no game semantics. It exists so the engine's
 * session-bound path can be driven over real classfiles: the whole point of
 * Blocker A is that the admission certificate describes the bytes the writer was
 * handed, so the control harness needs two genuinely different real classfiles
 * with the same binary name rather than one file described twice.</p>
 *
 * <p>Load-bearing properties:</p>
 * <ul>
 *   <li>the binary name is {@code example/SessionFixture};</li>
 *   <li>{@code handler$zzf000} carries a {@code MixinMerged.sessionId}
 *       annotation, because that annotation is what
 *       {@code CanonicalClassIdentityV2.identifySessionBound} masks to form the
 *       session invariant. A class without it would make every invariant
 *       vacuously equal to the exact identity and the control would prove
 *       nothing;</li>
 *   <li>{@code exercise(int)} contains exactly one
 *       {@code java/util/Objects.requireNonNull} invokestatic inside a
 *       {@code finally} handler range, so the placement validator has an anchor
 *       and an exception path to check. The POST form adds a second one, which
 *       is the writer's declared effect.</li>
 * </ul>
 *
 * <p>The MixinMerged annotation type is deliberately absent from the
 * classpath: a missing annotation class does not stop the classfile from being
 * written or read, and the identity contract reads the descriptor straight out
 * of the constant pool.</p>
 */
public class SessionFixture {
    static final String SESSION_ID = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    private int invocations;

    public void exercise(int value) {
        try {
            Objects.requireNonNull(SESSION_ID);
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
