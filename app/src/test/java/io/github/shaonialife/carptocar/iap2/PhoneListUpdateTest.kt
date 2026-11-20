package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneListUpdateTest {
    private val number = PhoneListUpdate.Number("12345", "测试", "手机")
    private val recent = PhoneListUpdate.Recent(number, 3, 1_700_000_000L, 0)

    @Test
    fun requestReadsOnlySubscribedListsAndTheirLimits() {
        val recents = Iap2Csm.group(1, Iap2Csm.param(0, byteArrayOf())) + Iap2Csm.u16(3, 4)
        val favorites = Iap2Csm.group(6, Iap2Csm.param(0, byteArrayOf())) + Iap2Csm.u16(8, 0)
        assertEquals(PhoneListUpdate.Request(4, null), PhoneListUpdate.Request.parse(recents))
        assertEquals(PhoneListUpdate.Request(4, Int.MAX_VALUE), PhoneListUpdate.Request.parse(recents + favorites))
    }

    @Test
    fun encodesBothListsAsRepeatedEntryGroupsWithinLinkBudget() {
        val first = PhoneListUpdate.Snapshot(listOf(recent), listOf(number))
        val limit = PhoneListUpdate.encode(first, 2048).size
        val snapshot = PhoneListUpdate.Snapshot(listOf(recent, recent), listOf(number, number))
        val encoded = PhoneListUpdate.encode(snapshot, limit)
        assertEquals(limit, encoded.size)
        val params = Iap2Csm.parseParams(encoded)
        assertEquals(listOf(0, 1, 2, 5, 6, 7), params.map { it.id })
        val call = Iap2Csm.parseParams(params.single { it.id == 1 }.value).associateBy { it.id }
        assertArrayEquals(byteArrayOf(3), call.getValue(6).value)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0x65, 0x53, 0xf1.toByte(), 0), call.getValue(7).value)
        val favorite = Iap2Csm.parseParams(params.single { it.id == 6 }.value).associateBy { it.id }
        assertArrayEquals("12345\u0000".encodeToByteArray(), favorite.getValue(1).value)
        assertArrayEquals("测试\u0000".encodeToByteArray(), favorite.getValue(2).value)
        assertTrue(PhoneListUpdate.encode(snapshot, limit - 1).size < limit)
    }

    @Test
    fun emptySnapshotRetainsExistingReply() {
        assertArrayEquals(
            Iap2SubscriptionReplies.idleBodyFor(Iap2SubscriptionReplies.LIST_UPDATE),
            PhoneListUpdate.encode(PhoneListUpdate.Snapshot(emptyList(), emptyList()), 2048),
        )
    }
}
