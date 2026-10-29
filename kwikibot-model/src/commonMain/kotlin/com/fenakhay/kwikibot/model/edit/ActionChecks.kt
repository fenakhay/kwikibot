package com.fenakhay.kwikibot.model.edit

/**
 * What the logged-in account may do to a page, as the wiki answers before being asked to do it.
 *
 * Worth reading before a run over pages a bot does not control. A refused write still costs a request, a log
 * line and a token, and the wiki will happily answer the question in the same query that fetched the page.
 */
public data class ActionChecks(
    /**
     * Each action that was tested, mapped to the error codes refusing it.
     *
     * An empty list means the action is permitted; `protectedpage` and `cascadeprotected` are the codes seen
     * most often on a refusal.
     */
    val refusals: Map<String, List<String>>
) {
    /** Whether [action] is permitted. False for an action that was never tested. */
    public fun allows(action: String): Boolean = refusals[action]?.isEmpty() == true

    /** Why [action] was refused, or empty if it was permitted or never tested. */
    public fun reasons(action: String): List<String> = refusals[action].orEmpty()
}
