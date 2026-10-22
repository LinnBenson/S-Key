package io.secure.keyboard

import io.secure.keyboard.data.NetworkJson
import org.junit.Assert.fail
import org.junit.Test

class NetworkJsonTest {
    private fun rejected(value: String) {
        try { NetworkJson.validate(value); fail("Malformed response accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun deepSmallResponseIsRejectedBeforePlatformParser() {
        rejected("[".repeat(10000) + "0" + "]".repeat(10000))
        NetworkJson.validate("[".repeat(64) + "0" + "]".repeat(64))
        rejected("[".repeat(65) + "0" + "]".repeat(65))
    }

    @Test fun quotedContentDoesNotCountAsStructure() {
        NetworkJson.validate("""{"text":"[] {} https://example.com/a 'quote' \"text\" \\"}""")
        NetworkJson.validate("{\"text\":\"" + "[".repeat(1000) + "\"}")
    }

    @Test fun malformedAndNonstandardResponsesAreRejected() {
        listOf("{]", "[}", "}", "{", "\"unfinished", "{'x':1}", "{/*x*/\"a\":1}")
            .forEach(::rejected)
        rejected(" ".repeat(1024 * 1024 + 1))
    }

    @Test fun failureDoesNotAffectNextResponse() {
        rejected("[".repeat(1000))
        NetworkJson.validate("""{"data":{"translations":[{"translatedText":"你好"}]}}""")
    }
}
