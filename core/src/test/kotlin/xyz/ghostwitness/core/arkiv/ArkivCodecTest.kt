package xyz.ghostwitness.core.arkiv

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import xyz.ghostwitness.core.crypto.Bytes

/**
 * The path every receiving witness actually uses: a readonly create plus an ownership transfer to the
 * original owner in one execute batch. The vector comes from `src/test/resources/codec-vector.cjs`,
 * which encodes the same batch with viem exactly as Arkiv SDK 1.0.1 does.
 */
class ArkivCodecTest {
    private val vector: Map<String, String> = run {
        val text = checkNotNull(javaClass.getResource("/codec-vector.json")).readText()
        Regex("\"(\\w+)\": \"0x([0-9a-f]*)\"").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }
    private val salt = Bytes.fromHex("0102030405060708090a0b0c0d0e0f10")
    private val writer = Bytes.fromHex("2c7536e3605d9c16a7a3d7b1898e529396a65c23")
    private val owner = Bytes.fromHex("00000000000000000000000000000000000000aa")

    @Test
    fun `create plus ownership transfer matches the Arkiv SDK encoding`() {
        // Out of order on purpose: the codec must sort by name.
        val attributes = listOf(
            ArkivCodec.bytes("data", Bytes.fromHex("deadbeef")),
            ArkivCodec.number("i", 7),
            ArkivCodec.str("kind", "upload"),
            ArkivCodec.str("app", "ghostwitness"),
        )
        assertContentEquals(Bytes.fromHex(vector.getValue("key")), ArkivCodec.entityKey(7794095, writer, 5, salt))
        assertContentEquals(Bytes.fromHex(vector.getValue("exec")), ArkivCodec.createAndTransfer(attributes, salt, 7794095, writer, 5, owner))
    }

    @Test
    fun `names the query language reserves are refused`() {
        listOf("bytes", "key", "NOT", "a--b", "9a").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { ArkivCodec.number(name, 1) }
        }
        ArkivCodec.number("size", 1)
    }
}
