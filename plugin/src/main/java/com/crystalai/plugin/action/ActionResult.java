package com.crystalai.plugin.action;

/** Outcome of an NPC action. {@code reason} is a short machine-friendly code when it failed. */
public record ActionResult(boolean success, String reason) {

    public static final ActionResult OK = new ActionResult(true, "ok");

    public static ActionResult fail(String reason) {
        return new ActionResult(false, reason);
    }
}
