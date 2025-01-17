package net.corda.uniqueness.client.impl

import net.corda.data.flow.event.external.ExternalEventContext
import net.corda.data.uniqueness.UniquenessCheckRequestAvro
import net.corda.data.uniqueness.UniquenessCheckResponseAvro
import net.corda.flow.external.events.factory.ExternalEventFactory
import net.corda.flow.external.events.factory.ExternalEventRecord
import net.corda.flow.state.FlowCheckpoint
import net.corda.uniqueness.datamodel.common.toUniquenessResult
import net.corda.v5.application.uniqueness.model.UniquenessCheckResult
import net.corda.v5.base.annotations.CordaSerializable
import net.corda.virtualnode.toAvro
import org.osgi.service.component.annotations.Component
import java.time.Instant
import net.corda.data.uniqueness.UniquenessCheckType as UniquenessCheckTypeAvro

@Component(service = [ExternalEventFactory::class])
class UniquenessCheckExternalEventFactory :
    ExternalEventFactory<UniquenessCheckExternalEventParams, UniquenessCheckResponseAvro, UniquenessCheckResult> {

    override val responseType = UniquenessCheckResponseAvro::class.java

    override fun createExternalEvent(
        checkpoint: FlowCheckpoint,
        flowExternalEventContext: ExternalEventContext,
        parameters: UniquenessCheckExternalEventParams
    ): ExternalEventRecord {
        return ExternalEventRecord(
            payload = createRequest(parameters, flowExternalEventContext, checkpoint)
        )
    }

    override fun resumeWith(checkpoint: FlowCheckpoint, response: UniquenessCheckResponseAvro): UniquenessCheckResult {
        return response.toUniquenessResult()
    }

    private fun createRequest(
        params: UniquenessCheckExternalEventParams,
        context: ExternalEventContext,
        checkpoint: FlowCheckpoint
    ) = UniquenessCheckRequestAvro.newBuilder()
        .setHoldingIdentity(checkpoint.holdingIdentity.toAvro())
        .setFlowExternalEventContext(context)
        .setTxId(params.txId)
        .setOriginatorX500Name(params.originatorX500Name)
        .setInputStates(params.inputStates)
        .setReferenceStates(params.referenceStates)
        .setNumOutputStates(params.numOutputStates)
        .setTimeWindowLowerBound(params.timeWindowLowerBound)
        .setTimeWindowUpperBound(params.timeWindowUpperBound)
        .setUniquenessCheckType(params.uniquenessCheckType.toAvro())
        .build()
}

@CordaSerializable
data class UniquenessCheckExternalEventParams(
    val txId: String,
    val originatorX500Name: String,
    val inputStates: List<String>,
    val referenceStates: List<String>,
    val numOutputStates: Int,
    val timeWindowLowerBound: Instant?,
    val timeWindowUpperBound: Instant,
    val uniquenessCheckType: UniquenessCheckType
)

@CordaSerializable
enum class UniquenessCheckType {
    WRITE, READ;

    fun toAvro(): UniquenessCheckTypeAvro {
        return when (this) {
            WRITE -> UniquenessCheckTypeAvro.WRITE
            READ -> UniquenessCheckTypeAvro.READ
        }
    }
}
