package com.shelf.reader.ui.reader

import java.util.UUID

/** Only the reader's own inline script receives a fresh per-document nonce. */
internal object ReaderContentSecurity {
    fun nonce() = UUID.randomUUID().toString()
    fun meta(nonce: String) = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src https://shelf.localhost/res/; base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'; connect-src 'none'">"""
}
