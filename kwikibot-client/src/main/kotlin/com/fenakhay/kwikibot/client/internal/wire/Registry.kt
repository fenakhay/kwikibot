package com.fenakhay.kwikibot.client.internal.wire

import com.fenakhay.kwikibot.client.internal.wire.Wire.Choice
import com.fenakhay.kwikibot.client.internal.wire.Wire.Parameter
import com.fenakhay.kwikibot.client.internal.wire.Wire.Values
import com.fenakhay.kwikibot.protocol.SiteInfo

/**
 * Every fixed value list and every respelled value this library sends.
 *
 * `WireContractTest` finds each declaration here by reflection, so one added here is checked without being
 * listed anywhere else. A recent-changes list and a watchlist look alike but take different values, so each
 * module gets its own declaration.
 */
internal object Registry {

    private val REVISION = arrayOf("ids", "timestamp", "user", "comment", "size", "flags", "sha1", "tags")

    val LOG_EVENTS =
        Values(
            "query+logevents",
            "leprop",
            "ids",
            "title",
            "type",
            "user",
            "timestamp",
            "comment",
            "details",
            "tags",
        )

    /**
     * What a recent-changes row and a watchlist row both report, short of the patrol flag.
     *
     * MediaWiki refuses that flag to a session without `patrol` or `patrolmarks`, failing the whole query, so
     * each list is declared twice: with it for a session that may see it, without it for any other.
     */
    private val CHANGE =
        arrayOf("ids", "title", "timestamp", "user", "comment", "flags", "sizes", "loginfo", "tags")

    val RECENT_CHANGES = Values("query+recentchanges", "rcprop", *CHANGE, "patrolled")

    /** [RECENT_CHANGES] for a session that may not ask whether a change was patrolled. */
    val RECENT_CHANGES_WITHOUT_PATROL = Values("query+recentchanges", "rcprop", *CHANGE)

    /** The same fields as [RECENT_CHANGES], except that the watchlist calls the patrol flag `patrol`. */
    val WATCHLIST = Values("query+watchlist", "wlprop", *CHANGE, "patrol")

    /** [WATCHLIST] for a session that may not ask whether a change was patrolled. */
    val WATCHLIST_WITHOUT_PATROL = Values("query+watchlist", "wlprop", *CHANGE)

    val REVISIONS = Values("query+revisions", "rvprop", *REVISION)

    val REVISIONS_WITH_CONTENT = Values("query+revisions", "rvprop", *REVISION, "content")

    /** What an expansion reports: the text, and with it the categories the text would put a page in. */
    val EXPANSION = Values("expandtemplates", "prop", "wikitext", "categories")

    val PAGE_CONTENT =
        Values("query+revisions", "rvprop", "ids", "timestamp", "user", "comment", "size", "flags", "content")

    val ALL_REVISIONS = Values("query+allrevisions", "arvprop", *REVISION)

    val ALL_DELETED_REVISIONS = Values("query+alldeletedrevisions", "adrprop", *REVISION)

    val DELETED_REVISIONS = Values("query+deletedrevisions", "drvprop", *REVISION)

    val USERS =
        Values(
            "query+users",
            "usprop",
            "blockinfo",
            "groups",
            "rights",
            "editcount",
            "registration",
            "emailable",
            "gender",
        )

    /** The fields of [USERS] that `allusers` also takes: it has no `emailable` and no `gender`. */
    val ALL_USERS =
        Values("query+allusers", "auprop", "blockinfo", "groups", "rights", "editcount", "registration")

    val CURRENT_USER =
        Values("query+userinfo", "uiprop", "groups", "rights", "editcount", "registrationdate", "blockinfo")

    val BLOCKS =
        Values("query+blocks", "bkprop", "id", "user", "by", "timestamp", "expiry", "reason", "flags")

    val IMAGE_INFO =
        Values(
            "query+imageinfo",
            "iiprop",
            "timestamp",
            "user",
            "comment",
            "url",
            "size",
            "dimensions",
            "sha1",
            "mime",
            "mediatype",
        )

    /** What a session reads first. Public as [SiteInfo.PROPERTIES], and declared here to be checked. */
    val SITE_INFO = Values(Parameter("query+siteinfo", "siprop"), SiteInfo.PROPERTIES.split('|'))

    /** What parsing a page the way this wiki does needs to know, read when first asked. */
    val PARSER_INFO =
        Values(
            Parameter("query+siteinfo", "siprop"),
            listOf(
                "general",
                "namespaces",
                "namespacealiases",
                "extensiontags",
                "protocols",
                "functionhooks",
                "variables",
                "magicwords",
            ),
        )

    /**
     * A page's headings. `tocdata` arrived in MediaWiki 1.43.6, 1.44.3 and 1.45 (T328605), and `sections` was
     * deprecated for it in 1.46 (T319141).
     */
    val TABLE_OF_CONTENTS = Choice(Parameter("parse", "prop"), listOf(listOf("tocdata"), listOf("sections")))

    /**
     * Whether an abuse filter is enabled, deleted, private, protected or suppressed.
     *
     * AbuseFilter folded the separate properties into `flags` in MediaWiki 1.47 (T435834). An older wiki
     * cannot report suppression through the API, and one older than 1.43 has no `protected` either, so it
     * reports only whether a filter is enabled, deleted or private.
     */
    val ABUSE_FILTER_FLAGS =
        Choice(
            Parameter("query+abusefilters", "abfprop"),
            listOf(listOf("flags"), listOf("status", "private", "protected"), listOf("status", "private")),
        )

    /** Every [Choice], whose modules a wiki is asked about together the first time one is needed. */
    val CHOICES: List<Choice> = listOf(TABLE_OF_CONTENTS, ABUSE_FILTER_FLAGS)
}
