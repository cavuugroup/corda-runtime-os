package net.corda.ledger.common.data.transaction.factory

import net.corda.ledger.common.data.transaction.TransactionMetadata
import net.corda.ledger.common.data.transaction.WireTransaction
import net.corda.v5.ledger.common.transaction.PrivacySalt

interface WireTransactionFactory {
    fun create(
        componentGroupLists: List<List<ByteArray>>,
        metadata: TransactionMetadata
    ): WireTransaction

    fun create(
        componentGroupLists: List<List<ByteArray>>,
        privacySalt: PrivacySalt
    ): WireTransaction
}