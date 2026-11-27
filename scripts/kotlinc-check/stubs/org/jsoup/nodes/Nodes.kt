// Offline compile stubs - never shipped. See /tmp/opencode/synchk/check2.sh
@file:Suppress("unused")
package org.jsoup.nodes

class Elements : Iterable<Element> {
    override fun iterator(): Iterator<Element> = emptyList<Element>().iterator()
}

open class Node

class TextNode : Node() {
    fun text(): String = ""
}

open class Element : Node() {
    fun tagName(): String = ""
    fun attr(name: String): String = ""
    fun children(): Elements = Elements()
    fun childNodes(): List<Node> = emptyList()
    fun select(css: String): Elements = Elements()
    fun selectFirst(css: String): Element? = null
    fun text(): String = ""
    fun html(): String = ""
}

class Document : Element() {
    val allElements: Elements = Elements()
    fun getElementsByTag(name: String): Elements = Elements()
}

object Entities {
    @JvmStatic
    fun escape(string: String): String = string
}
