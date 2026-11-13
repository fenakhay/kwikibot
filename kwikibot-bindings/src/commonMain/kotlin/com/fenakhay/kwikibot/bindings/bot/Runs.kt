package com.fenakhay.kwikibot.bindings.bot

import com.fenakhay.kwikibot.bindings.common.Setting
import com.fenakhay.kwikibot.bot.BotConfig
import com.fenakhay.kwikibot.bot.BotPolicy
import com.fenakhay.kwikibot.bot.CommonOptions
import com.fenakhay.kwikibot.bot.run.BotReport
import com.fenakhay.kwikibot.bot.run.BotRunBuilder
import com.fenakhay.kwikibot.bot.run.Edit
import com.fenakhay.kwikibot.bot.run.PageOutcome
import com.fenakhay.kwikibot.bot.run.RunState
import com.fenakhay.kwikibot.bot.run.StopPolicy
import com.fenakhay.kwikibot.bot.run.botRun
import com.fenakhay.kwikibot.bot.run.stopPage
import com.fenakhay.kwikibot.bot.source.PageSource
import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.model.page.PageContent
import com.fenakhay.kwikibot.model.page.PageRef
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map

private val botRunLog = KotlinLogging.logger("kwikibot.botRun")

private val runLog = KotlinLogging.logger("kwikibot.run")

/**
 * Runs a bot on this wiki with every setting a run has, as a binding's `botRun` does, and answers with the
 * report. What is not given keeps the run's own default: a dry run, honouring `{{nobots}}` as this account.
 *
 * The settings apply in this order, each later one winning: [common], then [config], then the settings given
 * here, then those about [state]. A run that saves with neither a [stopPolicy] nor a stop page in [common]
 * logs a warning that the wiki cannot stop it.
 *
 * The transform computes the edit for one page, or `null` to leave it alone, and may call
 * [BotRunBuilder.skip] to leave it alone with a reason; `createMissing` computes the page to create where the
 * source names one that does not exist. The exclusion policy says as which bot to honour `{{nobots}}` and
 * `{{bots}}`, and [Setting.Off] opts out.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
public suspend fun Wiki.botRunWithDefaults(
    transform: suspend BotRunBuilder.(PageContent) -> Edit?,
    source: PageSource? = null,
    createMissing: (suspend BotRunBuilder.(PageRef) -> Edit?)? = null,
    dryRun: Boolean? = null,
    readConcurrency: Int? = null,
    readBatch: Int? = null,
    writeConcurrency: Int? = null,
    limit: Int? = null,
    maxEdits: Int? = null,
    conflictRetries: Int? = null,
    stopPolicy: StopPolicy? = null,
    exclusionPolicy: Setting<BotPolicy> = Setting.Default,
    onOutcome: ((PageOutcome) -> Unit)? = null,
    state: RunState? = null,
    resume: Boolean? = null,
    common: CommonOptions? = null,
    config: BotConfig? = null,
): BotReport {
    if (dryRun == false && stopPolicy == null && common?.stopPage == null) {
        botRunLog.warn { "This run saves with no stopPolicy, so it cannot be stopped from the wiki" }
    }
    val wiki = this
    return botRun(stopPolicy ?: StopPolicy.NONE) {
        val builder = this
        common?.applyTo(builder, wiki)
        config?.applyTo(builder)
        source?.let { builder.source(it.pages(wiki)) }
        dryRun?.let { builder.dryRun = it }
        readConcurrency?.let { builder.readConcurrency = it }
        readBatch?.let { builder.readBatch = it }
        writeConcurrency?.let { builder.writeConcurrency = it }
        limit?.let { builder.limit = it }
        maxEdits?.let { builder.maxEdits = it }
        conflictRetries?.let { builder.conflictRetries = it }
        stopPolicy?.let { builder.stopPolicy = it }
        when (exclusionPolicy) {
            Setting.Default -> Unit
            Setting.Off -> builder.exclusionPolicy = null
            is Setting.Value -> builder.exclusionPolicy = exclusionPolicy.value
        }
        onOutcome?.let { builder.onOutcome = it }
        state?.let { builder.state = it }
        resume?.let { builder.resume = it }
        builder.transform { page -> transform(builder, page) }
        createMissing?.let { create -> builder.createMissing { ref -> create(builder, ref) } }
    }
}

/**
 * Runs a bot over [titles], as a binding's plain `run` does: gives each page's text and title to [transform]
 * and saves the text it answers with [summary], or leaves the page alone on `null`. A dry run unless [save],
 * ignoring `{{nobots}}`, stopped by [stopPolicy] or else by the page [stopPage] when given. A run that saves
 * with neither logs a warning that the wiki cannot stop it. `onChange` hears each change as the run computes
 * or saves it, and `onOutcome` every page's outcome after it.
 */
@Suppress("LongParameterList")
public suspend fun Wiki.runWithDefaults(
    titles: List<String>,
    summary: String,
    transform: suspend (text: String, title: String) -> String?,
    save: Boolean? = null,
    onChange: ((RunChange) -> Unit)? = null,
    stopPage: String? = null,
    stopPolicy: StopPolicy? = null,
    maxEdits: Int? = null,
    onOutcome: ((PageOutcome) -> Unit)? = null,
): BotReport {
    val saving = save ?: false
    if (saving && stopPage == null && stopPolicy == null) {
        runLog.warn { "This run saves with no stopPage, so it cannot be stopped from the wiki" }
    }
    val wiki = this
    return botRun(stopPolicy ?: stopPage?.let { stopPage(it) } ?: StopPolicy.NONE) {
        exclusionPolicy = null
        this.maxEdits = maxEdits
        source(titles.asFlow().map { wiki.ref(it) })
        dryRun = !saving
        this.onOutcome = { outcome ->
            if (onChange != null) changeOf(outcome, wiki.namespaces)?.let(onChange)
            onOutcome?.invoke(outcome)
        }
        transform { page ->
            transform(page.text, wiki.namespaces.format(page.ref.title))?.let { Edit(it, summary) }
        }
    }
}
