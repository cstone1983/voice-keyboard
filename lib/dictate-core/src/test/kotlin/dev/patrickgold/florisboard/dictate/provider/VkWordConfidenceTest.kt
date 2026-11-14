package dev.patrickgold.florisboard.dictate.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import kotlin.io.path.createTempFile

class VkWordConfidenceTest : FunSpec({

    fun audio(): File = createTempFile(suffix = ".wav").toFile().apply {
        writeBytes("RIFF-test-audio".encodeToByteArray())
        deleteOnExit()
    }

    fun client(server: MockWebServer) = OpenAiCompatibleClient(
        ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
    )

    test("asks for verbose_json with word timestamps and reports unsure words") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"text":" Meet Jenny at Proxmox.","words":[
                        {"word":" Meet","probability":0.98},{"word":" Jenny","probability":0.41},
                        {"word":" at","probability":0.95},{"word":" Proxmox.","probability":0.52}]}""",
                ),
            )
            val result = client(server).transcribe(TranscriptionRequest(audio(), "whisper", wordConfidence = true))
            val body = server.takeRequest().body.readUtf8()
            body shouldContain "verbose_json"
            body shouldContain "timestamp_granularities[]"
            result.text shouldBe "Meet Jenny at Proxmox."
            result.lowConfidenceWords shouldBe listOf("Jenny", "Proxmox")
        }
    }

    test("without the flag nothing changes, and a plain answer still parses with the flag") {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello"}"""))
            client(server).transcribe(TranscriptionRequest(audio(), "whisper")).text shouldBe "hello"
            server.takeRequest().body.readUtf8() shouldNotContain "verbose_json"
            val r = client(server).transcribe(TranscriptionRequest(audio(), "whisper", wordConfidence = true))
            r.text shouldBe "hello"
            r.lowConfidenceWords shouldBe emptyList()
        }
    }
})

class VkCleanupRequestTest : FunSpec({
    test("a cleanup request carries temperature and max_tokens") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"Hello."}}]}""",
                ),
            )
            val client = OpenAiCompatibleClient(ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"))
            client.complete(ChatRequest.ofUser("qwen2.5:3b", "clean this").copy(temperature = 0.2, maxTokens = 70))
            val body = server.takeRequest().body.readUtf8()
            body shouldContain "\"temperature\":0.2"
            body shouldContain "\"max_tokens\":70"
        }
    }
})
