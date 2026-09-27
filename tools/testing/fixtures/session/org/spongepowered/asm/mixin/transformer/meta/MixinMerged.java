package org.spongepowered.asm.mixin.transformer.meta;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * COMPILE-ONLY STUB of the Mixin annotation the session-bound fixture carries.
 *
 * <p>This is not Mixin and is not on any runtime classpath. It exists solely so
 * javac will accept the annotation on the engine's session-bound fixture; the
 * identity contract never loads it. It reads the annotation straight out of the
 * classfile's constant pool, and the fixture's whole purpose is to carry a real
 * {@code org/spongepowered/asm/mixin/transformer/meta/MixinMerged} entry with a
 * real {@code sessionId} element so masking is exercised on real bytes.</p>
 *
 * <p>Keep the package, simple name and {@code sessionId} element name exactly as
 * they are. The descriptor is load-bearing: changing it would silently turn the
 * fixture into a class with no session provenance, and
 * {@code identifySessionBound} would then refuse it rather than mask it.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface MixinMerged {
    String value() default "";

    String sessionId() default "";
}
