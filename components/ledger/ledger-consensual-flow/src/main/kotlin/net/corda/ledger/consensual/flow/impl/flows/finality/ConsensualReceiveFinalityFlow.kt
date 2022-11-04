package net.corda.ledger.consensual.flow.impl.flows.finality

import net.corda.ledger.common.flow.flows.Payload
import net.corda.ledger.consensual.flow.impl.persistence.ConsensualLedgerPersistenceService
import net.corda.ledger.consensual.flow.impl.persistence.TransactionStatus
import net.corda.v5.application.flows.CordaInject
import net.corda.v5.application.flows.SubFlow
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.application.messaging.receive
import net.corda.v5.application.serialization.SerializationService
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.util.contextLogger
import net.corda.v5.base.util.debug
import net.corda.v5.base.util.trace
import net.corda.v5.ledger.consensual.transaction.ConsensualSignedTransaction
import net.corda.v5.ledger.consensual.transaction.ConsensualSignedTransactionVerifier

class ConsensualReceiveFinalityFlow(
    private val session: FlowSession,
    private val verifier: ConsensualSignedTransactionVerifier
) : SubFlow<ConsensualSignedTransaction> {

    private companion object {
        val log = contextLogger()
    }

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var persistenceService: ConsensualLedgerPersistenceService

    @CordaInject
    lateinit var serializationService: SerializationService

    @Suspendable
    override fun call(): ConsensualSignedTransaction {
        val signedTransaction = session.receive<ConsensualSignedTransaction>()
        val transactionId = signedTransaction.id

        // TODO [CORE-5982] Verify Ledger Transaction
        // TODO [CORE-5982] Verify already added signatures.
        val signaturesPayload = if (verify(signedTransaction)) {
            // TODO [CORE-7029] Record unfinalised transaction

            // We check which of our keys are required.
            val myExpectedSigningKeys = signedTransaction
                .getMissingSignatories()
                .intersect(
                    memberLookup
                        .myInfo()
                        .ledgerKeys
                        .toSet()
                )

            if (myExpectedSigningKeys.isEmpty()) {
                log.debug { "We are not required signer of $transactionId." }
            }

            // We sign the transaction with all of our keys which is required.
            val newSignatures = myExpectedSigningKeys.map {
                signedTransaction.addSignature(it).second
            }

            Payload.Success(newSignatures)
        } else {
            Payload.Failure("Transaction verification failed for transaction $transactionId when signature was requested")
        }

        session.send(signaturesPayload)

        if (signaturesPayload is Payload.Failure) {
            throw CordaRuntimeException(signaturesPayload.message)
        }

        val signedTransactionToFinalize = session.receive<ConsensualSignedTransaction>()

        // A [require] block isn't the correct option if we want to do something with the error on the peer side
        require(signedTransactionToFinalize.id == transactionId) {
            "Expected to received transaction $transactionId from ${session.counterparty} to finalise but received " +
                    "${signedTransactionToFinalize.id} instead"
        }

        signedTransactionToFinalize.verifySignatures()

        persistenceService.persist(signedTransactionToFinalize, TransactionStatus.VERIFIED)
        log.debug { "Recorded signed transaction $transactionId" }

        session.send(Unit)
        log.trace { "Sent acknowledgement to initiator of finality for signed transaction ${signedTransactionToFinalize.id}" }

        return signedTransactionToFinalize
    }

    @Suspendable
    private fun verify(signedTransaction: ConsensualSignedTransaction): Boolean {
        return try {
            verifier.verify(signedTransaction)
            true
        } catch (e: Exception) {
            // Should we only catch a specific exception type? Otherwise some errors can be swallowed by this warning.
            // Means contracts can't use [check] or [require] unless we provide our own functions for this.
            if (e is IllegalStateException || e is IllegalArgumentException || e is CordaRuntimeException) {
                log.debug { "Transaction ${signedTransaction.id} failed verification. Message: ${e.message}" }
                false
            } else {
                throw e
            }
        }
    }
}