package net.corda.entityprocessor.impl.internal

import net.corda.crypto.cipher.suite.sha256Bytes
import net.corda.crypto.core.parseSecureHash
import net.corda.data.KeyValuePairList
import net.corda.data.flow.event.FlowEvent
import net.corda.data.persistence.DeleteEntities
import net.corda.data.persistence.DeleteEntitiesById
import net.corda.data.persistence.EntityRequest
import net.corda.data.persistence.EntityResponse
import net.corda.data.persistence.FindAll
import net.corda.data.persistence.FindEntities
import net.corda.data.persistence.FindWithNamedQuery
import net.corda.data.persistence.MergeEntities
import net.corda.data.persistence.PersistEntities
import net.corda.flow.utils.RequestIDGenerator
import net.corda.flow.utils.toMap
import net.corda.libs.virtualnode.datamodel.repository.RequestsIdsRepository
import net.corda.messaging.api.records.Record
import net.corda.metrics.CordaMetrics
import net.corda.orm.utils.transaction
import net.corda.persistence.common.EntitySandboxService
import net.corda.persistence.common.ResponseFactory
import net.corda.persistence.common.getEntityManagerFactory
import net.corda.persistence.common.getSerializationService
import net.corda.sandboxgroupcontext.CurrentSandboxGroupContext
import net.corda.sandboxgroupcontext.SandboxGroupContext
import net.corda.utilities.MDC_CLIENT_ID
import net.corda.utilities.MDC_EXTERNAL_EVENT_ID
import net.corda.utilities.translateFlowContextToMDC
import net.corda.utilities.withMDC
import net.corda.v5.application.flows.FlowContextPropertyKeys
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.util.EncodingUtils.toBase64
import net.corda.virtualnode.toCorda
import org.slf4j.Logger
import java.time.Duration
import javax.persistence.EntityManager
import javax.persistence.PersistenceException

@SuppressWarnings("LongParameterList")
class ProcessorService {

    fun processEvent(
        logger: Logger,
        request: EntityRequest,
        entitySandboxService: EntitySandboxService,
        currentSandboxGroupContext: CurrentSandboxGroupContext,
        responseFactory: ResponseFactory,
        requestsIdsRepository: RequestsIdsRepository
    ): Record<*, *> {
        val startTime = System.nanoTime()
        val clientRequestId = request.flowExternalEventContext.contextProperties.toMap()[MDC_CLIENT_ID] ?: ""
        val holdingIdentity = request.holdingIdentity.toCorda()

        val result = withMDC(
            mapOf(
                MDC_CLIENT_ID to clientRequestId, MDC_EXTERNAL_EVENT_ID to request.flowExternalEventContext.requestId
            ) + translateFlowContextToMDC(request.flowExternalEventContext.contextProperties.toMap())
        ) {
            var requestOutcome = "FAILED"
            try {
                logger.info("Handling ${request.request::class.java.name} for holdingIdentity ${holdingIdentity.shortHash.value}")

                val cpkFileHashes = request.flowExternalEventContext.contextProperties.items.filter {
                    it.key.startsWith(FlowContextPropertyKeys.CPK_FILE_CHECKSUM)
                }.map { it.value.toSecureHash() }.toSet()

                val sandbox = entitySandboxService.get(holdingIdentity, cpkFileHashes)

                currentSandboxGroupContext.set(sandbox)

                val persistenceServiceInternal = PersistenceServiceInternal(sandbox::getClass)

                processRequestWithSandbox(
                    sandbox, request, responseFactory, persistenceServiceInternal, requestsIdsRepository
                ).also { requestOutcome = "SUCCEEDED" }
            } catch (e: Exception) {
                responseFactory.errorResponse(request.flowExternalEventContext, e)
            } finally {
                currentSandboxGroupContext.remove()
            }.also {
                CordaMetrics.Metric.Db.EntityPersistenceRequestTime.builder()
                    .withTag(CordaMetrics.Tag.OperationName, request.request::class.java.name)
                    .withTag(CordaMetrics.Tag.OperationStatus, requestOutcome).build()
                    .record(Duration.ofNanos(System.nanoTime() - startTime))
            }
        }
        return result
    }

    private fun String.toSecureHash() = parseSecureHash(this)

    @Suppress("ComplexMethod")
    private fun processRequestWithSandbox(
        sandbox: SandboxGroupContext,
        request: EntityRequest,
        responseFactory: ResponseFactory,
        persistenceServiceInternal: PersistenceServiceInternal,
        requestsIdsRepository: RequestsIdsRepository
    ): Record<String, FlowEvent> {
        // get the per-sandbox entity manager and serialization services
        val entityManagerFactory = sandbox.getEntityManagerFactory()
        val serializationService = sandbox.getSerializationService()
        val entityManager = entityManagerFactory.createEntityManager()

        return when (val entityRequest = request.request) {
            is PersistEntities -> {
                val entities = entityRequest.entities
                    .map { serializationService.deserialize(it.array(), Any::class.java) }

                // originalRequestId is set client side based on a combination of entity parameters and suspend count
                // in order that we can filter out duplicate requests in case this external command was received as the result
                // of a retry when a previous attempt already succeeded.
                val originalRequestId = request.flowExternalEventContext.requestId

                // If there is a nondeterministic argument, for example a timestamp, originalRequestId would be different
                // for retries and not be flagged as duplicate to filter out. To accommodate this we try to modify the request
                // id hash component to be the hash of the entity id, if there is one, rather than the request parameters.
                // Any entity ID must be unique, being bound to the primary key of the db table.
                val requestId = entities
                    .mapNotNull { entityManagerFactory.persistenceUnitUtil.getIdentifier(it) }
                    .takeIf { it.isNotEmpty() }?.let { entityPks ->
                        RequestIDGenerator.replaceRequestIdHash(
                            originalRequestId,
                            entityPks,
                            serializationService
                        ) { toBase64(it.sha256Bytes()) }
                        // Where there are no ids to use as a basis for a replacement hash, we fall back to the originalRequestId set
                        // by the client.
                    } ?: originalRequestId

                // Note if the user code tried to write the same entity twice (as opposed to a retry scenario) the user code should
                // get an error returned, it shouldn't be filtered as a duplicate. This will work because the suspend count
                // is written into the request ID and so the request ID would always be different.

                val entityResponse = withDeduplicationCheck(requestId, entityManager, onDuplication = {
                    EntityResponse(emptyList(), KeyValuePairList(emptyList()), null)
                }, requestsIdsRepository) {
                    persistenceServiceInternal.persist(it, entities)
                }

                responseFactory.successResponse(
                    request.flowExternalEventContext, entityResponse
                )
            }

            is DeleteEntities -> entityManager.transaction {
                responseFactory.successResponse(
                    request.flowExternalEventContext,
                    persistenceServiceInternal.deleteEntities(serializationService, it, entityRequest)
                )
            }

            is DeleteEntitiesById -> entityManager.transaction {
                responseFactory.successResponse(
                    request.flowExternalEventContext, persistenceServiceInternal.deleteEntitiesByIds(
                        serializationService, it, entityRequest
                    )
                )
            }

            is MergeEntities -> {
                val entityResponse = entityManager.transaction {
                    persistenceServiceInternal.merge(serializationService, it, entityRequest)
                }
                responseFactory.successResponse(
                    request.flowExternalEventContext, entityResponse
                )
            }

            is FindEntities -> entityManager.transaction {
                responseFactory.successResponse(
                    request.flowExternalEventContext,
                    persistenceServiceInternal.find(serializationService, it, entityRequest)
                )
            }

            is FindAll -> entityManager.transaction {
                responseFactory.successResponse(
                    request.flowExternalEventContext,
                    persistenceServiceInternal.findAll(serializationService, it, entityRequest)
                )
            }

            is FindWithNamedQuery -> entityManager.transaction {
                responseFactory.successResponse(
                    request.flowExternalEventContext,
                    persistenceServiceInternal.findWithNamedQuery(serializationService, it, entityRequest)
                )
            }

            else -> {
                responseFactory.fatalErrorResponse(
                    request.flowExternalEventContext, CordaRuntimeException("Unknown command")
                )
            }
        }
    }

    private fun withDeduplicationCheck(
        requestId: String,
        entityManager: EntityManager,
        onDuplication: () -> EntityResponse,
        requestsIdsRepository: RequestsIdsRepository,
        block: (EntityManager) -> EntityResponse
    ): EntityResponse {
        return entityManager.transaction {
            try {
                requestsIdsRepository.persist(requestId, it)
                it.flush()
            } catch (e: PersistenceException) {
                // A persistence exception thrown in the de-duplication check means we have already
                // performed the operation and can therefore treat the request as successful
                it.transaction.setRollbackOnly()
                return@transaction onDuplication()
            }
            block(entityManager)
        }
    }
}
