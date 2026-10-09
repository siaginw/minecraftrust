package com.rustcraft.observability.fixtures;
/** Separate definitions keep lifecycle fault controls away from timing fixtures. */
public final class LifecycleVictims {
    public static final class Redefinition {
        public int value(int input) { return input+31; }
    }
    public static final class Duplicate {
        public int value(int input) { return input+47; }
    }
}
