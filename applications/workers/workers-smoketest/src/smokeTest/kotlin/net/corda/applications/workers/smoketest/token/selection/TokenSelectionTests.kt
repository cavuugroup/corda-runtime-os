package net.corda.applications.workers.smoketest.token.selection

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import net.corda.e2etest.utilities.ClusterReadiness
import net.corda.e2etest.utilities.ClusterReadinessChecker
import net.corda.e2etest.utilities.DEFAULT_CLUSTER
import net.corda.e2etest.utilities.REST_FLOW_STATUS_SUCCESS
import net.corda.e2etest.utilities.TEST_NOTARY_CPB_LOCATION
import net.corda.e2etest.utilities.TEST_NOTARY_CPI_NAME
import net.corda.e2etest.utilities.TestRequestIdGenerator
import net.corda.e2etest.utilities.awaitRestFlowFinished
import net.corda.e2etest.utilities.conditionallyUploadCordaPackage
import net.corda.e2etest.utilities.conditionallyUploadCpiSigningCertificate
import net.corda.e2etest.utilities.getHoldingIdShortHash
import net.corda.e2etest.utilities.getOrCreateVirtualNodeFor
import net.corda.e2etest.utilities.registerStaticMember
import net.corda.e2etest.utilities.startRestFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

@TestInstance(PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TokenSelectionTests : ClusterReadiness by ClusterReadinessChecker() {

    private companion object {
        const val TEST_CPI_NAME = "ledger-utxo-demo-app"
        const val TEST_CPB_LOCATION = "/META-INF/ledger-utxo-demo-app.cpb"
        const val NOTARY_SERVICE_X500 = "O=MyNotaryService, L=London, C=GB"

        val testRunUniqueId = UUID.randomUUID().toString()
        val groupId = UUID.randomUUID().toString()
        val cpiName = "${TEST_CPI_NAME}_$testRunUniqueId"
        val notaryCpiName = "${TEST_NOTARY_CPI_NAME}_$testRunUniqueId"

        val aliceX500 = "CN=Alice-$testRunUniqueId, OU=Application, O=R3, L=London, C=GB"
        val bobX500 = "CN=Bob-$testRunUniqueId, OU=Application, O=R3, L=London, C=GB"
        val notaryX500 = "CN=Notary-$testRunUniqueId, OU=Application, O=R3, L=London, C=GB"

        val aliceHoldingId: String = getHoldingIdShortHash(aliceX500, groupId)
        val bobHoldingId: String = getHoldingIdShortHash(bobX500, groupId)
        val notaryHoldingId: String = getHoldingIdShortHash(notaryX500, groupId)

        val staticMemberList = listOf(
            aliceX500,
            bobX500,
            notaryX500
        )

        val objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
        }
    }

    private fun convertToTokenBalanceQueryResponseMsg(tokenBalanceQueryResponseMsgStr: String) =
        objectMapper.readValue(
            tokenBalanceQueryResponseMsgStr,
            TokenBalanceQueryResponseMsg::class.java
        )

    private fun runTokenBalanceQueryFlow(flowRequestId: String): TokenBalanceQueryResponseMsg {

        val tokenBalanceQueryFlowName = "com.r3.corda.demo.utxo.token.selection.TokenBalanceQueryFlow"

        val tokenBalanceQueryRestStartArgs = mapOf(
            "tokenType" to "com.r3.corda.demo.utxo.contract.CoinState",
            "issuerBankX500" to bobX500,
            "currency" to "USD"
        )

        startRestFlow(aliceHoldingId, tokenBalanceQueryRestStartArgs, tokenBalanceQueryFlowName, requestId = flowRequestId)

        val flowResult = awaitRestFlowFinished(aliceHoldingId, flowRequestId)
        assertThat(flowResult.flowStatus).isEqualTo(REST_FLOW_STATUS_SUCCESS)

        return convertToTokenBalanceQueryResponseMsg(flowResult.flowResult!!)
    }

    @BeforeAll
    fun beforeAll() {
        // check cluster is ready
        assertIsReady(Duration.ofMinutes(2), Duration.ofMillis(100))

        DEFAULT_CLUSTER.conditionallyUploadCpiSigningCertificate()

        conditionallyUploadCordaPackage(
            cpiName,
            TEST_CPB_LOCATION,
            groupId,
            staticMemberList
        )
        conditionallyUploadCordaPackage(
            notaryCpiName,
            TEST_NOTARY_CPB_LOCATION,
            groupId,
            staticMemberList
        )

        val aliceActualHoldingId = getOrCreateVirtualNodeFor(aliceX500, cpiName)
        val bobActualHoldingId = getOrCreateVirtualNodeFor(bobX500, cpiName)
        val notaryActualHoldingId = getOrCreateVirtualNodeFor(notaryX500, notaryCpiName)

        assertThat(aliceActualHoldingId).isEqualTo(aliceHoldingId)
        assertThat(bobActualHoldingId).isEqualTo(bobHoldingId)
        assertThat(notaryActualHoldingId).isEqualTo(notaryHoldingId)

        registerStaticMember(aliceHoldingId)
        registerStaticMember(bobHoldingId)
        registerStaticMember(notaryHoldingId, NOTARY_SERVICE_X500)

        println("Alice: $aliceX500 - $aliceHoldingId")
        println("Alice: $bobX500 - $bobHoldingId")
        println("Alice: $notaryX500 - $notaryHoldingId")
    }

    @Test
    @Order(1)
    fun `ensure it is possible to send a balance query request and receive a response`(testInfo: TestInfo) {
        val idGenerator = TestRequestIdGenerator(testInfo)

        // Start the flow that will send the request and receive the response
        val tokenBalanceQuery = runTokenBalanceQueryFlow(idGenerator.nextId)

        // Check that the balance of the token cache is zero since no token has been created
        assertThat(tokenBalanceQuery.availableBalance).isEqualTo(BigDecimal.ZERO)
        assertThat(tokenBalanceQuery.totalBalance).isEqualTo(BigDecimal.ZERO)
    }

    @Test
    @Order(2)
    fun `Claim a token in a flow and let the flow finish to validate the token claim is automatically released`(testInfo: TestInfo){
        val idGenerator = TestRequestIdGenerator(testInfo)
        // Create a simple UTXO transaction
        val input = "token test input"
        val utxoFlowRequestId = startRestFlow(
            bobHoldingId,
            mapOf("input" to input, "members" to listOf(aliceX500), "notary" to NOTARY_SERVICE_X500),
            "com.r3.corda.demo.utxo.UtxoDemoFlow",
            requestId = idGenerator.nextId
        )
        val utxoFlowResult = awaitRestFlowFinished(bobHoldingId, utxoFlowRequestId)
        assertThat(utxoFlowResult.flowStatus).isEqualTo(REST_FLOW_STATUS_SUCCESS)
        assertThat(utxoFlowResult.flowError).isNull()

        // Attempt to select the token created by the transaction
        val tokenSelectionFlowId1 = startRestFlow(
            aliceHoldingId,
            mapOf(),
            "com.r3.corda.demo.utxo.token.selection.TokenSelectionFlow2",
            requestId = idGenerator.nextId
        )
        val tokenSelectionResult1 = awaitRestFlowFinished(aliceHoldingId, tokenSelectionFlowId1)
        assertThat(tokenSelectionResult1.flowStatus).isEqualTo(REST_FLOW_STATUS_SUCCESS)
        assertThat(tokenSelectionResult1.flowError).isNull()
        assertThat(tokenSelectionResult1.flowResult).isEqualTo("SUCCESS")

        // Attempt to select the token created by the transaction
        val tokenSelectionFlowId2 = startRestFlow(
            aliceHoldingId,
            mapOf(),
            "com.r3.corda.demo.utxo.token.selection.TokenSelectionFlow2",
            requestId = idGenerator.nextId
        )
        val tokenSelectionResult2 = awaitRestFlowFinished(aliceHoldingId, tokenSelectionFlowId2)
        assertThat(tokenSelectionResult2.flowStatus).isEqualTo(REST_FLOW_STATUS_SUCCESS)
        assertThat(tokenSelectionResult2.flowError).isNull()
        assertThat(tokenSelectionResult2.flowResult).isEqualTo("SUCCESS")
    }
}

private data class TokenBalanceQueryResponseMsg(
    val availableBalance: BigDecimal,
    val totalBalance: BigDecimal
)
