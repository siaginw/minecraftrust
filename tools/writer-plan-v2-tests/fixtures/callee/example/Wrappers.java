package example;

/**
 * Wrapper variants for the ISOLATED_CALLEE behavioural control.
 *
 * <p>Written as real Java and compiled by the real compiler on purpose. The
 * production wrappers are real Java too, and the point of the control is that a
 * wrapper of that shape actually contains a failing observation at runtime --
 * so building the fixtures by hand would test a shape nobody ships.</p>
 *
 * <p>Each observation throws. The safe wrappers must return normally anyway; the
 * others must be refused by the verifier, and one of them must be seen letting
 * the failure escape.</p>
 */
public final class Wrappers {

    private Wrappers() { }

    /** The observation every wrapper below claims to isolate. Always fails. */
    public static void observation() {
        throw new IllegalStateException("observation failed");
    }

    /** A different observation, for checking which call is actually contained. */
    public static void otherObservation() {
        throw new IllegalStateException("other observation failed");
    }

    /** A caller operation that must keep throwing through all of this. */
    public static void applicationOperation() {
        throw new IllegalStateException("application failure");
    }

    /** The production shape: a Throwable catch that contains and returns. */
    public static void safeObservation() {
        try {
            observation();
        } catch (Throwable contained) {
            // Deliberately contained.
        }
    }

    /** The production shape over a different observation. */
    public static void safeOther() {
        try {
            otherObservation();
        } catch (Throwable contained) {
            // Deliberately contained.
        }
    }

    /** Too narrow: an Error would escape, so this is not containment. */
    public static void safeNarrow() {
        try {
            observation();
        } catch (Exception contained) {
            // Deliberately contained, but only for Exceptions.
        }
    }

    /** No catch at all. */
    public static void safeBare() {
        observation();
    }

    /** Catches and rethrows: a lifecycle contract, not containment. */
    public static void safeRethrow() {
        try {
            observation();
        } catch (Throwable failure) {
            throw failure;
        }
    }

    /** A catch that covers a different range than the observation call. */
    public static void safeRangeMissesCall() {
        int marker = 0;
        try {
            marker = 1;
        } catch (Throwable contained) {
            marker = 2;
        }
        observation();
        if (marker < 0) throw new IllegalStateException("unreachable");
    }

    /** Recursive: a wrapper that calls itself is not isolating anything. */
    public static void safeRecursive() {
        safeRecursive();
    }

    /** Application failure BEFORE the wrapper must be untouched. */
    public static void applicationFailureThenWrapper() {
        applicationOperation();
        safeObservation();
    }

    /** Application failure AFTER the wrapper must be untouched. */
    public static void wrapperThenApplicationFailure() {
        safeObservation();
        applicationOperation();
    }
}
