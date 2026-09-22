package com.hermes.adapter;

public final class ForgeLifecycleAdapter {
    public enum Stage {
        PRE_INIT,
        INIT,
        POST_INIT,
        SERVER_STARTING,
        SERVER_STARTED,
        SERVER_STOPPING
    }

    private Stage currentStage = Stage.PRE_INIT;

    public void onStageTransition(Stage newStage) {
        System.out.println("[Hermes-Forge] Lifecycle transition: " + this.currentStage + " -> " + newStage);
        this.currentStage = newStage;
    }

    public Stage getCurrentStage() {
        return currentStage;
    }
}
