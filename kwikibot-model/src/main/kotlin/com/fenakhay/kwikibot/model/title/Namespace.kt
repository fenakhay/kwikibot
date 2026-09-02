package com.fenakhay.kwikibot.model.title

/**
 * A MediaWiki namespace, identified by its number.
 *
 * Numbers rather than names are the stable identity: a namespace's local name and aliases vary per wiki, but
 * `0` is main space everywhere. Names are resolved against a wiki's site info.
 */
@JvmInline
public value class Namespace(
    /** The number MediaWiki files pages under, which is the same on every wiki. */
    public val id: Int
) : Comparable<Namespace> {

    /**
     * Whether this is a talk namespace.
     *
     * Talk namespaces are the odd-numbered ones; the negative namespaces (`Media`, `Special`) are virtual and
     * have no talk space at all.
     */
    public val isTalk: Boolean
        get() = id >= 0 && id % 2 == 1

    /** Whether this namespace is virtual — it has no pages of its own in the database. */
    public val isVirtual: Boolean
        get() = id < 0

    /**
     * The talk namespace associated with this one, or `null` for virtual namespaces.
     *
     * Returns the receiver when it is already a talk namespace.
     */
    public val talkSpace: Namespace?
        get() =
            when {
                isVirtual -> null
                isTalk -> this
                else -> Namespace(id + 1)
            }

    /**
     * The subject (non-talk) namespace associated with this one, or `null` for virtual namespaces. Returns
     * the receiver when it is already a subject namespace.
     */
    public val subjectSpace: Namespace?
        get() =
            when {
                isVirtual -> null
                isTalk -> Namespace(id - 1)
                else -> this
            }

    /** Orders by number, which puts the virtual namespaces before main space. */
    override fun compareTo(other: Namespace): Int = id.compareTo(other.id)

    /** The number with a marker, `ns:14`. A wiki's own name for it needs its site info. */
    override fun toString(): String = "ns:$id"

    /**
     * The namespaces MediaWiki defines on every installation.
     *
     * A wiki may define more, which have no constant here because their numbers are the wiki's own; read
     * those from its site info through `NamespaceMap`.
     */
    public companion object {
        /**
         * Virtual: file pages addressed as media.
         *
         * A link here goes straight to the file rather than to its description page.
         */
        public val MEDIA: Namespace = Namespace(-2)
        /**
         * Virtual: generated pages such as `Special:RecentChanges`.
         *
         * They exist in no database table, so nothing here can be read or written as a page.
         */
        public val SPECIAL: Namespace = Namespace(-1)
        /** Article space, and the only namespace whose titles carry no prefix. */
        public val MAIN: Namespace = Namespace(0)
        /** Discussion of article-space pages. */
        public val TALK: Namespace = Namespace(1)
        /** Personal pages, and where a bot's own subpages belong. */
        public val USER: Namespace = Namespace(2)
        /** Where a message left for a person arrives, and where a bot is told to stop. */
        public val USER_TALK: Namespace = Namespace(3)
        /** The wiki about itself: policy, process, and the pages a bot is approved on. */
        public val PROJECT: Namespace = Namespace(4)
        /** Discussion of the wiki's own pages. */
        public val PROJECT_TALK: Namespace = Namespace(5)
        /** File description pages. The file itself is the media namespace. */
        public val FILE: Namespace = Namespace(6)
        /** Discussion of file description pages. */
        public val FILE_TALK: Namespace = Namespace(7)
        /** Interface messages, editable only by administrators. */
        public val MEDIAWIKI: Namespace = Namespace(8)
        /** Discussion of interface messages. */
        public val MEDIAWIKI_TALK: Namespace = Namespace(9)
        /** Templates, which are what most bot edits are ultimately about. */
        public val TEMPLATE: Namespace = Namespace(10)
        /** Discussion of templates. */
        public val TEMPLATE_TALK: Namespace = Namespace(11)
        /** Documentation written for readers and editors. */
        public val HELP: Namespace = Namespace(12)
        /** Discussion of help pages. */
        public val HELP_TALK: Namespace = Namespace(13)
        /** Category description pages. A category exists as a page only once described. */
        public val CATEGORY: Namespace = Namespace(14)
        /** Discussion of category description pages. */
        public val CATEGORY_TALK: Namespace = Namespace(15)
    }
}
