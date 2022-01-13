package net.corda.membership.grouppolicy

import net.corda.cpiinfo.read.CpiInfoReadService
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleEventHandler
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.RegistrationStatusChangeEvent
import net.corda.lifecycle.StartEvent
import net.corda.lifecycle.StopEvent
import net.corda.membership.GroupPolicy
import net.corda.packaging.CPI
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.membership.identity.MemberX500Name
import net.corda.virtualnode.HoldingIdentity
import net.corda.virtualnode.VirtualNodeInfo
import net.corda.virtualnode.read.VirtualNodeInfoListener
import net.corda.virtualnode.read.VirtualNodeInfoReaderComponent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for [GroupPolicyProviderImpl]
 */
class GroupPolicyProviderImplTest {
    lateinit var groupPolicyProvider: GroupPolicyProviderImpl

    val groupIdKey = "groupId"
    val testAttrKey = "testAttribute"

    val groupId1 = "ABC123"
    val groupId2 = "DEF456"

    val testAttr1 = "foo"
    val testAttr2 = "bar"
    val testAttr3 = "baz"

    val alice = MemberX500Name("Alice", "London", "GB")
    val bob = MemberX500Name("Bob", "London", "GB")

    val groupPolicy1 = "{\"$testAttrKey\": \"$testAttr1\", \"$groupIdKey\": \"$groupId1\"}"
    val groupPolicy2 = "{\"$testAttrKey\": \"$testAttr2\", \"$groupIdKey\": \"$groupId1\"}"
    val groupPolicy3 = "{\"$testAttrKey\": \"$testAttr3\", \"$groupIdKey\": \"$groupId2\"}"
    val groupPolicy4: String? = null

    val holdingIdentity1 = HoldingIdentity(alice.toString(), groupId1)
    val holdingIdentity2 = HoldingIdentity(bob.toString(), groupId1)
    val holdingIdentity3 = HoldingIdentity(alice.toString(), groupId2)
    val holdingIdentity4 = HoldingIdentity(bob.toString(), groupId2)

    val cpiMetadata1 = mock<CPI.Metadata>().apply { doReturn(groupPolicy1).whenever(this).groupPolicy }
    val cpiMetadata2 = mock<CPI.Metadata>().apply { doReturn(groupPolicy2).whenever(this).groupPolicy }
    val cpiMetadata3 = mock<CPI.Metadata>().apply { doReturn(groupPolicy3).whenever(this).groupPolicy }
    val cpiMetadata4 = mock<CPI.Metadata>().apply { doReturn(groupPolicy4).whenever(this).groupPolicy }

    val cpiIdentifier1: CPI.Identifier = mock()
    val cpiIdentifier2: CPI.Identifier = mock()
    val cpiIdentifier3: CPI.Identifier = mock()
    val cpiIdentifier4: CPI.Identifier = mock()

    var virtualNodeListener: VirtualNodeInfoListener? = null

    val virtualNodeInfoReader: VirtualNodeInfoReaderComponent = mock<VirtualNodeInfoReaderComponent>().apply {
        doReturn(VirtualNodeInfo(holdingIdentity1, cpiIdentifier1)).whenever(this).get(eq(holdingIdentity1))
        doReturn(VirtualNodeInfo(holdingIdentity2, cpiIdentifier2)).whenever(this).get(eq(holdingIdentity2))
        doReturn(VirtualNodeInfo(holdingIdentity3, cpiIdentifier3)).whenever(this).get(eq(holdingIdentity3))
        doReturn(VirtualNodeInfo(holdingIdentity4, cpiIdentifier4)).whenever(this).get(eq(holdingIdentity4))
        doAnswer {
            virtualNodeListener = it.arguments[0] as VirtualNodeInfoListener
            mock<AutoCloseable>()
        }
            .whenever(this)
            .registerCallback(any())
    }

    val cpiInfoReader = mock<CpiInfoReadService>().apply {
        doReturn(cpiMetadata1).whenever(this).get(cpiIdentifier1)
        doReturn(cpiMetadata2).whenever(this).get(cpiIdentifier2)
        doReturn(cpiMetadata3).whenever(this).get(cpiIdentifier3)
        doReturn(cpiMetadata4).whenever(this).get(cpiIdentifier4)
    }

    var handler: LifecycleEventHandler? = null

    val coordinator: LifecycleCoordinator = mock<LifecycleCoordinator>().apply {
        doAnswer { handler?.processEvent(StartEvent(), this) }.whenever(this).start()
        doAnswer { handler?.processEvent(StopEvent(), this) }.whenever(this).stop()
    }
    val lifecycleCoordinatorFactory: LifecycleCoordinatorFactory = mock<LifecycleCoordinatorFactory>().apply {
        doAnswer {
            handler = it.arguments[1] as LifecycleEventHandler
            coordinator
        }.whenever(this)
            .createCoordinator(any(), any())
    }

    @BeforeEach
    fun setUp() {
        groupPolicyProvider = GroupPolicyProviderImpl(
            virtualNodeInfoReader,
            cpiInfoReader,
            lifecycleCoordinatorFactory
        )
    }

    fun assertExpectedGroupPolicy(
        groupPolicy: GroupPolicy,
        groupId: String?,
        testAttr: String?,
        expectedSize: Int = 2
    ) {
        assertEquals(expectedSize, groupPolicy.size)
        assertEquals(groupId, groupPolicy[groupIdKey])
        assertEquals(testAttr, groupPolicy[testAttrKey])
    }

    @Test
    fun `Correct group policy is returned when CPI metadata contains group policy string and service has started`() {
        groupPolicyProvider.start()
        assertExpectedGroupPolicy(
            groupPolicyProvider.getGroupPolicy(holdingIdentity1),
            groupId1,
            testAttr1
        )
        assertExpectedGroupPolicy(
            groupPolicyProvider.getGroupPolicy(holdingIdentity2),
            groupId1,
            testAttr2
        )
        assertExpectedGroupPolicy(
            groupPolicyProvider.getGroupPolicy(holdingIdentity3),
            groupId2,
            testAttr3
        )
        assertThrows<CordaRuntimeException> { groupPolicyProvider.getGroupPolicy(holdingIdentity4) }
    }

    @Test
    fun `Group policy read fails if service hasn't started`() {
        assertThrows<CordaRuntimeException> {
            groupPolicyProvider.getGroupPolicy(holdingIdentity1)
        }
    }

    @Test
    fun `Same group policy is returned if it has already been parsed`() {
        groupPolicyProvider.start()
        val result1 = groupPolicyProvider.getGroupPolicy(holdingIdentity1)
        val result2 = groupPolicyProvider.getGroupPolicy(holdingIdentity1)

        assertEquals(result1, result2)
    }

    @Test
    fun `Different group policy is returned if the service restarts`() {
        groupPolicyProvider.start()
        val result1 = groupPolicyProvider.getGroupPolicy(holdingIdentity1)
        groupPolicyProvider.stop()
        groupPolicyProvider.start()
        val result2 = groupPolicyProvider.getGroupPolicy(holdingIdentity1)

        assertNotEquals(result1, result2)
    }

    @Test
    fun `isRunning is set as expected when restarting the service`() {
        assertFalse(groupPolicyProvider.isRunning)
        groupPolicyProvider.start()
        assertTrue(groupPolicyProvider.isRunning)
        groupPolicyProvider.stop()
        assertFalse(groupPolicyProvider.isRunning)
        groupPolicyProvider.start()
        assertTrue(groupPolicyProvider.isRunning)
    }

    @Test
    fun `Cached group policy is updated when a holding identity updates their CPI`() {
        assertNull(virtualNodeListener)
        groupPolicyProvider.start()
        assertNotNull(virtualNodeListener)
        val original = groupPolicyProvider.getGroupPolicy(holdingIdentity1)
        assertExpectedGroupPolicy(original, groupId1, testAttr1)

        virtualNodeListener?.onUpdate(
            setOf(holdingIdentity1),
            mapOf(holdingIdentity1 to VirtualNodeInfo(holdingIdentity1, cpiIdentifier2))
        )

        val updated = groupPolicyProvider.getGroupPolicy(holdingIdentity1)
        assertNotEquals(original, updated)
        assertExpectedGroupPolicy(updated, groupId1, testAttr2)
    }

    @Test
    fun `Group policy not yet cached is created when a holding identity updates their CPI`() {
        assertNull(virtualNodeListener)
        groupPolicyProvider.start()
        assertNotNull(virtualNodeListener)

        virtualNodeListener?.onUpdate(
            setOf(holdingIdentity1),
            mapOf(holdingIdentity1 to VirtualNodeInfo(holdingIdentity1, cpiIdentifier2))
        )

        val updated = groupPolicyProvider.getGroupPolicy(holdingIdentity1)
        assertExpectedGroupPolicy(updated, groupId1, testAttr2)
    }

    @Test
    fun `Component goes down when followed components go down and data can't be accessed`() {
        groupPolicyProvider.start()
        assertTrue(groupPolicyProvider.isRunning)
        assertNotNull(handler)

        handler?.processEvent(
            RegistrationStatusChangeEvent(mock(), LifecycleStatus.DOWN),
            coordinator
        )

        assertFalse(groupPolicyProvider.isRunning)
        assertThrows<CordaRuntimeException> {
            groupPolicyProvider.getGroupPolicy(holdingIdentity1)
        }
    }

    @Test
    fun `Component goes down and then comes back up when followed components go down and up again`() {
        groupPolicyProvider.start()
        assertTrue(groupPolicyProvider.isRunning)
        assertNotNull(handler)

        handler?.processEvent(
            RegistrationStatusChangeEvent(mock(), LifecycleStatus.DOWN),
            coordinator
        )
        handler?.processEvent(
            RegistrationStatusChangeEvent(mock(), LifecycleStatus.UP),
            coordinator
        )

        assertTrue(groupPolicyProvider.isRunning)
        assertExpectedGroupPolicy(
            groupPolicyProvider.getGroupPolicy(holdingIdentity1),
            groupId1,
            testAttr1
        )
    }
}