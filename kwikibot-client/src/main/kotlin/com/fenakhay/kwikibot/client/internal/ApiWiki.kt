package com.fenakhay.kwikibot.client.internal

import com.fenakhay.kwikibot.client.Wiki
import com.fenakhay.kwikibot.client.service.ExtensionService
import com.fenakhay.kwikibot.client.service.FileService
import com.fenakhay.kwikibot.client.service.ListService
import com.fenakhay.kwikibot.client.service.LogService
import com.fenakhay.kwikibot.client.service.MetaService
import com.fenakhay.kwikibot.client.service.PageService
import com.fenakhay.kwikibot.client.service.ProofreadService
import com.fenakhay.kwikibot.client.service.RenderService
import com.fenakhay.kwikibot.client.service.RevisionService
import com.fenakhay.kwikibot.client.service.UserService
import com.fenakhay.kwikibot.model.page.WikiId
import com.fenakhay.kwikibot.net.UserAgent
import com.fenakhay.kwikibot.net.auth.Identity
import com.fenakhay.kwikibot.net.auth.TokenStore
import com.fenakhay.kwikibot.net.transport.ApiEndpoint
import com.fenakhay.kwikibot.net.transport.MediaWikiTransport
import com.fenakhay.kwikibot.protocol.ParamInfo
import com.fenakhay.kwikibot.protocol.SiteInfo
import com.fenakhay.kwikibot.protocol.decode.ActivityDecoder
import com.fenakhay.kwikibot.protocol.decode.PageDecoder
import io.ktor.client.HttpClient

internal class ApiWiki(
    override val info: SiteInfo,
    override val identity: Identity,
    override val transport: MediaWikiTransport,
    override val tokens: TokenStore,
    private val http: HttpClient,
    private val endpoint: ApiEndpoint,
    private val userAgent: UserAgent,
) : Wiki {

    override val id: WikiId
        get() = info.id

    private val decoder = PageDecoder(info.id, info.namespaces)

    override val pages: PageService =
        ApiPageService(
            transport = transport,
            tokens = tokens,
            decoder = decoder,
            namespaces = info.namespaces,
        )

    override val lists: ListService =
        ApiListService(
            transport = transport,
            decoder = decoder,
            namespaces = info.namespaces,
        )

    override val revisions: RevisionService =
        ApiRevisionService(
            transport = transport,
            tokens = tokens,
            decoder = decoder,
            namespaces = info.namespaces,
        )

    override val users: UserService =
        ApiUserService(
            transport = transport,
            tokens = tokens,
            activity = ActivityDecoder(decoder),
        )

    override val logs: LogService =
        ApiLogService(
            transport = transport,
            activity = ActivityDecoder(decoder),
            namespaces = info.namespaces,
        )

    override val paramInfo: ParamInfo = ParamInfo(transport)

    override val renderer: RenderService =
        ApiRenderService(
            transport = transport,
            decoder = decoder,
            namespaces = info.namespaces,
        )

    override val meta: MetaService = ApiMetaService(transport, tokens)

    override val proofread: ProofreadService =
        ApiProofreadService(
            transport = transport,
            decoder = decoder,
            namespaces = info.namespaces,
            info = info,
        )

    override val extensions: ExtensionService =
        ApiExtensionService(
            transport = transport,
            tokens = tokens,
            decoder = decoder,
            namespaces = info.namespaces,
            info = info,
        )

    override val files: FileService =
        ApiFileService(
            transport = transport,
            tokens = tokens,
            decoder = decoder,
            namespaces = info.namespaces,
            http = http,
            endpoint = endpoint,
            userAgent = userAgent,
        )

    override fun toString(): String = "Wiki(${info.id} as ${identity.name})"
}
