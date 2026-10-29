package com.fenakhay.kwikibot.testkit

import com.fenakhay.kwikibot.client.service.ListService
import com.fenakhay.kwikibot.client.service.LogService
import com.fenakhay.kwikibot.client.service.MetaService
import com.fenakhay.kwikibot.client.service.RenderService
import com.fenakhay.kwikibot.client.service.RevisionService
import com.fenakhay.kwikibot.client.service.UserService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class FakeWikiJvmTest {

    @Test
    fun `a list service handed in is the one the fake wiki lists with`() = runTest {
        val member = FakePageService().ref("Volcano")
        val lists = stub<ListService> { method -> if (method == "categoryMembers") flowOf(member) else null }
        val wiki = FakeWiki(pages = FakePageService(), lists = lists)

        wiki.lists shouldBeSameInstanceAs lists
        wiki.lists.categoryMembers(wiki.ref("Category:Volcanoes")).toList() shouldBe listOf(member)
    }

    @Test
    fun `every other service handed in is the one the fake wiki uses`() {
        val revisions = stub<RevisionService>()
        val users = stub<UserService>()
        val logs = stub<LogService>()
        val renderer = stub<RenderService>()
        val meta = stub<MetaService>()

        val wiki =
            FakeWiki(
                pages = FakePageService(),
                revisions = revisions,
                users = users,
                logs = logs,
                renderer = renderer,
                meta = meta,
            )

        wiki.revisions shouldBeSameInstanceAs revisions
        wiki.users shouldBeSameInstanceAs users
        wiki.logs shouldBeSameInstanceAs logs
        wiki.renderer shouldBeSameInstanceAs renderer
        wiki.meta shouldBeSameInstanceAs meta
    }

    /**
     * A service that answers [answer] for the methods it knows and refuses every other call, for checking
     * which instance the fake hands out: the service interfaces are too wide to write out by hand for that.
     */
    private inline fun <reified T : Any> stub(crossinline answer: (String) -> Any? = { null }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "stub ${T::class.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> answer(method.name) ?: throw UnsupportedOperationException(method.name)
            }
        } as T
}
