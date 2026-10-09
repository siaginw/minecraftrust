package example;

import java.util.Objects;

/**
 * Synthetic class observed by the qualification-engine integration controls.
 *
 * This is NOT a Minecraft class and carries no game semantics. It exists so the
 * engine suite can run the real Java CANONICAL_ID_V2 parser over a real classfile
 * without starting a game JVM. Three properties are load-bearing:
 *
 * <ul>
 *   <li>the binary name must be {@code example/Fixture}, because the engine
 *       fixture profile, the writer site and the placement validator all name
 *       that class;</li>
 *   <li>{@code exercise(int)} must contain exactly one
 *       {@code java/util/Objects.requireNonNull} invokestatic, because the
 *       writer-site control asserts on that call;</li>
 *   <li>that call must sit inside a {@code finally} handler range without
 *       being the handler's first instruction, because the placement control
 *       asserts the writer's exception paths were preserved;</li>
 *   <li>the string literal {@code "literal"} must survive into the constant
 *       pool, because the {@code class-drift} negative mode rewrites exactly
 *       those bytes in place and the engine must notice that the canonical
 *       identity moved with them.</li>
 * </ul>
 *
 * Compiled with the pinned Java 8 toolchain so the classfile carries the
 * StackMapTable the identity contract reads; see
 * {@code tools/testing/build_engine_fixture.py}.
 */
public class Fixture {
    static final String LITERAL = "literal";
    private int invocations;

    public void exercise(int value) {
        try {
            Objects.requireNonNull(LITERAL);
            if (value < 0) {
                throw new IllegalStateException(LITERAL);
            }
        } finally {
            invocations++;
        }
    }
}
