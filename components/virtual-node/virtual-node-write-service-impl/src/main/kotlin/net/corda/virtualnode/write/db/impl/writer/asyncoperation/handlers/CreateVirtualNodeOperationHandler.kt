package net.corda.virtualnode.write.db.impl.writer.asyncoperation.handlers

import net.corda.data.virtualnode.VirtualNodeCreateRequest
import net.corda.db.connection.manager.VirtualNodeDbType
import net.corda.db.core.DbPrivilege.DML
import net.corda.libs.external.messaging.ExternalMessagingRouteConfigGenerator
import net.corda.libs.packaging.core.CpiMetadata
import net.corda.messaging.api.publisher.Publisher
import net.corda.virtualnode.HoldingIdentity
import net.corda.virtualnode.toCorda
import net.corda.virtualnode.write.db.VirtualNodeWriteServiceException
import net.corda.virtualnode.write.db.impl.writer.VirtualNodeConnectionStrings
import net.corda.virtualnode.write.db.impl.writer.VirtualNodeDb
import net.corda.virtualnode.write.db.impl.writer.VirtualNodeDbFactory
import net.corda.virtualnode.write.db.impl.writer.VirtualNodeWriterProcessor
import net.corda.virtualnode.write.db.impl.writer.asyncoperation.VirtualNodeAsyncOperationHandler
import net.corda.virtualnode.write.db.impl.writer.asyncoperation.factories.RecordFactory
import net.corda.virtualnode.write.db.impl.writer.asyncoperation.services.CreateVirtualNodeService
import org.slf4j.Logger
import java.time.Instant

@Suppress("LongParameterList")
internal class CreateVirtualNodeOperationHandler(
    private val createVirtualNodeService: CreateVirtualNodeService,
    private val virtualNodeDbFactory: VirtualNodeDbFactory,
    private val recordFactory: RecordFactory,
    statusPublisher: Publisher,
    private val externalMessagingRouteConfigGenerator: ExternalMessagingRouteConfigGenerator,
    private val logger: Logger
) : VirtualNodeAsyncOperationHandler<VirtualNodeCreateRequest>, AbstractVirtualNodeOperationHandler(statusPublisher, logger) {

    override fun handle(
        requestTimestamp: Instant,
        requestId: String,
        request: VirtualNodeCreateRequest
    ) {
        publishStartProcessingStatus(requestId)

        try {
            val holdingId = request.holdingId.toCorda()
            val x500Name = holdingId.x500Name.toString()

            logger.info("Create new Virtual Node: $x500Name and ${request.cpiFileChecksum}")
            val execLog = ExecutionTimeLogger("Update", x500Name, requestTimestamp.toEpochMilli(), logger)

            val requestValidationResult = execLog.measureExecTime("validation") {
                createVirtualNodeService.validateRequest(request)
            }

            if (requestValidationResult != null) {
                throw IllegalArgumentException(requestValidationResult)
            }

            val cpiMetadata = execLog.measureExecTime("get CPI metadata") {
                createVirtualNodeService.getCpiMetaData(request.cpiFileChecksum)
            }

            execLog.measureExecTime("check holding identity uniqueness") {
                createVirtualNodeService.ensureHoldingIdentityIsUnique(request)
            }

            val vNodeDbs = execLog.measureExecTime("get virtual node databases") {
                virtualNodeDbFactory.createVNodeDbs(
                    holdingId.shortHash,
                    with(request) {
                        VirtualNodeConnectionStrings(
                            vaultDdlConnection,
                            vaultDmlConnection,
                            cryptoDdlConnection,
                            cryptoDmlConnection,
                            uniquenessDdlConnection,
                            uniquenessDmlConnection
                        )
                    }
                )
            }

            // For each of the platform DB's run the creation process
            for (vNodeDb in vNodeDbs.values.filter { it.isPlatformManagedDb }) {
                execLog.measureExecTime("create schema and user in ${vNodeDb.dbType} DB") {
                    vNodeDb.createSchemasAndUsers()
                }
            }

            for (vNodeDb in vNodeDbs.values.filter { it.isPlatformManagedDb || it.ddlConnectionProvided }) {
                execLog.measureExecTime("apply DB migrations in ${vNodeDb.dbType} DB") {
                    vNodeDb.runDbMigration(VirtualNodeWriterProcessor.systemTerminatorTag)
                }

                if (vNodeDb.dbType == VirtualNodeDbType.VAULT) {
                    execLog.measureExecTime("apply CPI migrations in ${vNodeDb.dbType} DB") {
                        createVirtualNodeService.runCpiMigrations(
                            cpiMetadata,
                            vNodeDb,
                            holdingId
                        )
                    }
                }
            }

            checkSchemasArePresentOnExternalDbs(vNodeDbs.values, cpiMetadata, holdingId)

            val externalMessagingRouteConfig = externalMessagingRouteConfigGenerator.generateNewConfig(
                holdingId,
                cpiMetadata.cpiId,
                cpiMetadata.cpksMetadata
            )

            logger.info("Generated new ExternalMessagingRouteConfig as: $externalMessagingRouteConfig")

            val vNodeConnections = execLog.measureExecTime("persist holding ID and virtual node") {
                createVirtualNodeService.persistHoldingIdAndVirtualNode(
                    holdingId,
                    vNodeDbs,
                    cpiMetadata.cpiId,
                    request.updateActor,
                    externalMessagingRouteConfig
                )
            }

            execLog.measureExecTime("publish virtual node info") {
                createVirtualNodeService.publishRecords(
                    listOf(
                        recordFactory.createVirtualNodeInfoRecord(
                            holdingId,
                            cpiMetadata.cpiId,
                            vNodeConnections,
                            externalMessagingRouteConfig
                        )
                    )
                )
            }
        } catch (e: Exception) {
            publishErrorStatus(requestId, e.message ?: "Unexpected error")
            throw e
        }

        publishProcessingCompletedStatus(requestId)
    }

    private fun checkSchemasArePresentOnExternalDbs(
        vNodeDbs: Collection<VirtualNodeDb>,
        cpiMetadata: CpiMetadata,
        holdingId: HoldingIdentity
    ) {
        // Select externally managed VNode DBs
        val dbaManagedDbs = vNodeDbs.filterNot {
            it.isPlatformManagedDb || it.ddlConnectionProvided || it.dbConnections[DML] == null
        }

        // Are any platform schemas missing?
        val missingPlatformSchemas = dbaManagedDbs.filterNot { it.checkDbMigrationsArePresent() }
            .map { it.dbType.toString() }

        // Are any CPI schemas missing?
        val missingCpiSchemas = dbaManagedDbs.filter { it.dbType == VirtualNodeDbType.VAULT }
            .filterNot { createVirtualNodeService.checkCpiMigrations(cpiMetadata, it, holdingId) }
            .map { cpiMetadata.cpiId.name }

        // If any schemas are missing, throw exception listing them
        val allMissingSchemas = missingPlatformSchemas + missingCpiSchemas
        if (allMissingSchemas.any()) {
            throw VirtualNodeWriteServiceException(
                "DB schemas missing from external DB: ${allMissingSchemas.joinToString(",")}"
            )
        }
    }
}
