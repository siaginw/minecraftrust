package com.rustcraft.coremod;
import java.nio.file.Files;
import java.nio.file.Paths;
/** Exercises the generated V2 class, not the historical default plan. */
public final class GeneratedPlanV2Smoke {
    public static void main(String[] args) throws Exception {
        if (!"CANONICAL_ID_V2".equals(LiveWriterPlan.IDENTITY_MODE)
                || LiveWriterPlan.HOOKS.length != 1) throw new AssertionError("wrong generated plan");
        LiveHookSupport.verifyPreHookIdentity(LiveWriterPlan.HOOKS, Files.readAllBytes(Paths.get(args[0])));
        if (LiveWriterPlan.HOOKS[0].declarationOrderSha256 == null
                || LiveWriterPlan.HOOKS[0].canonicalIdentity) throw new AssertionError("V2 lost or downgraded");
        System.out.println("PASS generated V2 recipe identity; qualification and authority remain absent");
    }
}
