package net.corda.ledger.utxo.flow.impl.persistence

import net.corda.ledger.common.data.transaction.TransactionStatus
import net.corda.ledger.utxo.flow.impl.transaction.UtxoSignedLedgerTransaction
import net.corda.v5.application.crypto.DigitalSignatureAndMetadata
import net.corda.v5.application.persistence.CordaPersistenceException
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.utxo.StateRef
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction
import net.corda.v5.ledger.utxo.transaction.filtered.UtxoFilteredTransaction
import net.corda.v5.ledger.utxo.transaction.filtered.UtxoFilteredTransactionAndSignatures
import java.security.PublicKey
import java.time.Instant

/**
 * [UtxoLedgerPersistenceService] allows to insert and find UTXO signed transactions in the persistent store provided
 * by the platform.
 */
interface UtxoLedgerPersistenceService {

    /**
     * Find a UTXO signed transaction in the persistence context given it's [id].
     *
     * @param id UTXO signed transaction ID.
     * @param transactionStatus filter for this status.
     *
     * @return The found UTXO signed transaction, null if it could not be found in the persistence context.
     *
     * @throws CordaPersistenceException if an error happens during find operation.
     */
    @Suspendable
    fun findSignedTransaction(id: SecureHash, transactionStatus: TransactionStatus = TransactionStatus.VERIFIED): UtxoSignedTransaction?

    /**
     * Find a [UtxoSignedTransaction] in the persistence context given it's [id] and return it with the status it is stored with.
     *
     * @param id transaction ID.
     * @param transactionStatus filter for this status.
     *
     * @return The found [UtxoSignedTransaction] and its status, null if it could not be found in the persistence context.
     *
     * @throws CordaPersistenceException if an error happens during find operation.
     */
    @Suspendable
    fun findSignedTransactionWithStatus(
        id: SecureHash,
        transactionStatus: TransactionStatus
    ): Pair<UtxoSignedTransaction?, TransactionStatus>?

    /**
     * Find transactions with the given [ids] that are present in the persistence context and return their IDs and statuses.
     *
     * @param ids IDs of transactions to find.
     *
     * @return A list of the transaction IDs found and their statuses.
     */
    @Suspendable
    fun findTransactionIdsAndStatuses(ids: Collection<SecureHash>): Map<SecureHash, TransactionStatus>

    /**
     * Find a verified [UtxoSignedLedgerTransaction] in the persistence context given it's [id]. This involves resolving its input and
     * reference state and fetching the transaction's signatures.
     *
     * @param id transaction ID.
     *
     * @return The found [UtxoSignedLedgerTransaction], null if it could not be found in the persistence context.
     *
     * @throws CordaPersistenceException if an error happens during find operation.
     */
    @Suspendable
    fun findSignedLedgerTransaction(id: SecureHash): UtxoSignedLedgerTransaction?

    /**
     * Find a [UtxoSignedLedgerTransaction] in the persistence context given it's [id] and return it with the status it is stored with.
     * This involves resolving its input and reference state and fetching the transaction's signatures.
     *
     * @param id transaction ID.
     * @param transactionStatus filter for this status.
     *
     * @return The found [UtxoSignedLedgerTransaction] and its status, null if it could not be found in the persistence context.
     *
     * @throws CordaPersistenceException if an error happens during find operation.
     */
    @Suspendable
    fun findSignedLedgerTransactionWithStatus(
        id: SecureHash,
        transactionStatus: TransactionStatus
    ): Pair<UtxoSignedLedgerTransaction?, TransactionStatus>?

    /**
     * Retrieve a map of transaction id to its corresponding filtered transaction and notary signature.
     *
     * @param stateRefs a list of [StateRef]
     * @param notaryKey an expected notary key of stateRefs
     * @param notaryName an expected notary name of stateRefs
     * @return The fetch result in a map of transaction ID to [UtxoFilteredTransaction] and [DigitalSignatureAndMetadata]
     * */
    @Suspendable
    fun findFilteredTransactionsAndSignatures(
        stateRefs: List<StateRef>,
        notaryKey: PublicKey,
        notaryName: MemberX500Name
    ): Map<SecureHash, UtxoFilteredTransactionAndSignatures>

    /**
     * Persist a [UtxoSignedTransaction] to the store.
     *
     * @param transaction UTXO signed transaction to persist.
     * @param transactionStatus Transaction's status
     * @param visibleStatesIndexes Indexes of visible states.
     *
     * @return [Instant] timestamp of when the transaction is stored in DB.
     *
     * @throws CordaPersistenceException if an error happens during persist operation.
     */
    @Suspendable
    fun persist(
        transaction: UtxoSignedTransaction,
        transactionStatus: TransactionStatus,
        visibleStatesIndexes: List<Int> = emptyList()
    ): Instant

    @Suspendable
    fun updateStatus(id: SecureHash, transactionStatus: TransactionStatus)

    /**
     * Persist a [UtxoSignedTransaction] to the store.
     *
     * @param transaction UTXO signed transaction to persist.
     * @param transactionStatus Transaction's status
     *
     * @return list of [String] that represents transaction's status that tells us whether it existed or not.
     * if it exists already it'll be the status in db, if not empty string to represent non-existent
     *
     * @throws CordaPersistenceException if an error happens during persist operation.
     */
    @Suspendable
    fun persistIfDoesNotExist(
        transaction: UtxoSignedTransaction,
        transactionStatus: TransactionStatus
    ): TransactionExistenceStatus

    @Suspendable
    fun persistTransactionSignatures(id: SecureHash, startingIndex: Int, signatures: List<DigitalSignatureAndMetadata>)

    /**
     * Persists a list of filtered transactions and their signatures represented as [UtxoFilteredTransactionAndSignatures]
     * objects.
     *
     * @param filteredTransactionsAndSignatures A list containing the filtered transactions and signatures to persist.
     *
     * @throws CordaPersistenceException if an error happens during persist operation.
     */
    @Suspendable
    fun persistFilteredTransactionsAndSignatures(
        filteredTransactionsAndSignatures: List<UtxoFilteredTransactionAndSignatures>
    )

    @Suspendable
    fun findTransactionsWithStatusCreatedBetweenTime(
        status: TransactionStatus,
        from: Instant,
        until: Instant,
        limit: Int,
    ): List<SecureHash>

    @Suspendable
    fun incrementTransactionRepairAttemptCount(id: SecureHash)
}
