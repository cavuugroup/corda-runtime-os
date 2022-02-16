package net.corda.chunking.db

import net.corda.libs.configuration.SmartConfig
import javax.persistence.EntityManagerFactory

interface ChunkDbWriterFactory {
    fun create(config: SmartConfig, entityManagerFactory: EntityManagerFactory): ChunkDbWriter
}
