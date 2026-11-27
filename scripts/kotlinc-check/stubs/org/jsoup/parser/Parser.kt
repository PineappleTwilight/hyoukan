// Offline compile stubs - never shipped.
@file:Suppress("unused")
package org.jsoup.parser

class Parser {
    companion object {
        @JvmStatic
        fun xmlParser(): Parser = Parser()
        @JvmStatic
        fun htmlParser(): Parser = Parser()
    }
}
