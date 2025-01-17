package net.corda.flow.messaging.mediator

import net.corda.avro.serialization.CordaAvroSerializationFactory
import net.corda.data.crypto.wire.ops.flow.FlowOpsRequest
import net.corda.data.flow.event.FlowEvent
import net.corda.data.flow.event.mapper.FlowMapperEvent
import net.corda.data.flow.output.FlowStatus
import net.corda.data.flow.state.checkpoint.Checkpoint
import net.corda.data.ledger.persistence.LedgerPersistenceRequest
import net.corda.data.ledger.utxo.token.selection.event.TokenPoolCacheEvent
import net.corda.data.persistence.EntityRequest
import net.corda.data.uniqueness.UniquenessCheckRequestAvro
import net.corda.flow.fiber.cache.FlowFiberCache
import net.corda.flow.pipeline.factory.FlowEventProcessorFactory
import net.corda.ledger.utxo.verification.TransactionVerificationRequest
import net.corda.libs.configuration.SmartConfig
import net.corda.libs.configuration.getIntOrDefault
import net.corda.libs.platform.PlatformInfoProvider
import net.corda.libs.statemanager.api.StateManager
import net.corda.messaging.api.constants.WorkerRPCPaths.CRYPTO_PATH
import net.corda.messaging.api.constants.WorkerRPCPaths.LEDGER_PATH
import net.corda.messaging.api.constants.WorkerRPCPaths.PERSISTENCE_PATH
import net.corda.messaging.api.constants.WorkerRPCPaths.TOKEN_SELECTION_PATH
import net.corda.messaging.api.constants.WorkerRPCPaths.UNIQUENESS_PATH
import net.corda.messaging.api.constants.WorkerRPCPaths.VERIFICATION_PATH
import net.corda.messaging.api.mediator.MediatorMessage
import net.corda.messaging.api.mediator.MessageRouter
import net.corda.messaging.api.mediator.MessagingClient.Companion.MSG_PROP_TOPIC
import net.corda.messaging.api.mediator.RoutingDestination.Companion.routeTo
import net.corda.messaging.api.mediator.RoutingDestination.Type.ASYNCHRONOUS
import net.corda.messaging.api.mediator.RoutingDestination.Type.SYNCHRONOUS
import net.corda.messaging.api.mediator.config.EventMediatorConfigBuilder
import net.corda.messaging.api.mediator.factory.MediatorConsumerFactory
import net.corda.messaging.api.mediator.factory.MediatorConsumerFactoryFactory
import net.corda.messaging.api.mediator.factory.MessageRouterFactory
import net.corda.messaging.api.mediator.factory.MessagingClientFactoryFactory
import net.corda.messaging.api.mediator.factory.MultiSourceEventMediatorFactory
import net.corda.messaging.api.processor.StateAndEventProcessor
import net.corda.schema.Schemas.Flow.FLOW_EVENT_TOPIC
import net.corda.schema.Schemas.Flow.FLOW_MAPPER_SESSION_OUT
import net.corda.schema.Schemas.Flow.FLOW_SESSION
import net.corda.schema.Schemas.Flow.FLOW_START
import net.corda.schema.Schemas.Flow.FLOW_STATUS_TOPIC
import net.corda.schema.configuration.BootConfig.CRYPTO_WORKER_REST_ENDPOINT
import net.corda.schema.configuration.BootConfig.PERSISTENCE_WORKER_REST_ENDPOINT
import net.corda.schema.configuration.BootConfig.TOKEN_SELECTION_WORKER_REST_ENDPOINT
import net.corda.schema.configuration.BootConfig.UNIQUENESS_WORKER_REST_ENDPOINT
import net.corda.schema.configuration.BootConfig.VERIFICATION_WORKER_REST_ENDPOINT
import net.corda.schema.configuration.BootConfig.WORKER_MEDIATOR_REPLICAS_FLOW_SESSION
import net.corda.schema.configuration.MessagingConfig.Subscription.MEDIATOR_PROCESSING_MIN_POOL_RECORD_COUNT
import net.corda.schema.configuration.MessagingConfig.Subscription.MEDIATOR_PROCESSING_THREAD_POOL_SIZE
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.LoggerFactory
import java.util.UUID

@Suppress("LongParameterList")
@Component(service = [FlowEventMediatorFactory::class])
class FlowEventMediatorFactoryImpl @Activate constructor(
    @Reference(service = FlowEventProcessorFactory::class)
    private val flowEventProcessorFactory: FlowEventProcessorFactory,
    @Reference(service = MediatorConsumerFactoryFactory::class)
    private val mediatorConsumerFactoryFactory: MediatorConsumerFactoryFactory,
    @Reference(service = MessagingClientFactoryFactory::class)
    private val messagingClientFactoryFactory: MessagingClientFactoryFactory,
    @Reference(service = MultiSourceEventMediatorFactory::class)
    private val eventMediatorFactory: MultiSourceEventMediatorFactory,
    @Reference(service = CordaAvroSerializationFactory::class)
    private val cordaAvroSerializationFactory: CordaAvroSerializationFactory,
    @Reference(service = PlatformInfoProvider::class)
    val platformInfoProvider: PlatformInfoProvider,
    @Reference(service = FlowFiberCache::class)
    val flowFiberCache: FlowFiberCache,
) : FlowEventMediatorFactory {
    companion object {
        private const val CONSUMER_GROUP = "FlowEventConsumer"
        private const val MESSAGE_BUS_CLIENT = "MessageBusClient"
        private const val RPC_CLIENT = "RpcClient"

        private val logger = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    private val deserializer = cordaAvroSerializationFactory.createAvroDeserializer({}, Any::class.java)

    override fun create(
        configs: Map<String, SmartConfig>,
        messagingConfig: SmartConfig,
        bootConfig: SmartConfig,
        stateManager: StateManager,
    ) = eventMediatorFactory.create(
        createEventMediatorConfig(
            messagingConfig,
            bootConfig,
            flowEventProcessorFactory.create(configs),
            stateManager,
        )
    )

    @Suppress("SpreadOperator")
    private fun createEventMediatorConfig(
        messagingConfig: SmartConfig,
        bootConfig: SmartConfig,
        messageProcessor: StateAndEventProcessor<String, Checkpoint, FlowEvent>,
        stateManager: StateManager,
    ) = EventMediatorConfigBuilder<String, Checkpoint, FlowEvent>()
        .name("FlowEventMediator")
        .messagingConfig(messagingConfig)
        .consumerFactories(
            *createMediatorConsumerFactories(messagingConfig, bootConfig).toTypedArray()
        )
        .clientFactories(
            messagingClientFactoryFactory.createMessageBusClientFactory(
                MESSAGE_BUS_CLIENT, messagingConfig
            ),
            messagingClientFactoryFactory.createRPCClientFactory(
                RPC_CLIENT
            )
        )
        .messageProcessor(messageProcessor)
        .messageRouterFactory(createMessageRouterFactory(messagingConfig))
        .threads(messagingConfig.getInt(MEDIATOR_PROCESSING_THREAD_POOL_SIZE))
        .threadName("flow-event-mediator")
        .stateManager(stateManager)
        .minGroupSize(messagingConfig.getInt(MEDIATOR_PROCESSING_MIN_POOL_RECORD_COUNT))
        .build()

    private fun createMediatorConsumerFactories(messagingConfig: SmartConfig, bootConfig: SmartConfig): List<MediatorConsumerFactory> {
        val mediatorConsumerFactory: MutableList<MediatorConsumerFactory> = mutableListOf(
            mediatorConsumerFactory(FLOW_START, messagingConfig),
            mediatorConsumerFactory(FLOW_EVENT_TOPIC, messagingConfig)
        )

        val mediatorReplicas = bootConfig.getIntOrDefault(WORKER_MEDIATOR_REPLICAS_FLOW_SESSION, 1)
        logger.info("Creating $mediatorReplicas mediator(s) consumer factories for $FLOW_SESSION")
        for(i in 1..mediatorReplicas) {
            mediatorConsumerFactory.add(mediatorConsumerFactory(FLOW_SESSION, messagingConfig))
        }

        return mediatorConsumerFactory
    }

    private fun mediatorConsumerFactory(
        topic: String,
        messagingConfig: SmartConfig
    ): MediatorConsumerFactory {
        val clientId = "MultiSourceSubscription--$CONSUMER_GROUP--$topic--${UUID.randomUUID()}"
        return mediatorConsumerFactoryFactory.createMessageBusConsumerFactory(
            topic, CONSUMER_GROUP, clientId, messagingConfig, FlowMediatorRebalanceListener(clientId, flowFiberCache)
        )
    }


    private fun createMessageRouterFactory(messagingConfig: SmartConfig) = MessageRouterFactory { clientFinder ->
        val messageBusClient = clientFinder.find(MESSAGE_BUS_CLIENT)
        val rpcClient = clientFinder.find(RPC_CLIENT)

        fun rpcEndpoint(endpoint: String, path: String) : String {
            val platformVersion = platformInfoProvider.localWorkerSoftwareShortVersion
            return "http://${messagingConfig.getString(endpoint)}/api/${platformVersion}$path"
        }

        MessageRouter { message ->
            when (val event = message.event()) {
                is EntityRequest -> routeTo(rpcClient,
                    rpcEndpoint(PERSISTENCE_WORKER_REST_ENDPOINT, PERSISTENCE_PATH), SYNCHRONOUS)
                is FlowMapperEvent -> routeTo(messageBusClient,
                    FLOW_MAPPER_SESSION_OUT, ASYNCHRONOUS)
                is FlowOpsRequest -> routeTo(rpcClient,
                    rpcEndpoint(CRYPTO_WORKER_REST_ENDPOINT, CRYPTO_PATH), SYNCHRONOUS)
                is FlowStatus -> routeTo(messageBusClient,
                    FLOW_STATUS_TOPIC, ASYNCHRONOUS)
                is LedgerPersistenceRequest -> routeTo(rpcClient,
                    rpcEndpoint(PERSISTENCE_WORKER_REST_ENDPOINT, LEDGER_PATH), SYNCHRONOUS)
                is TokenPoolCacheEvent -> routeTo(rpcClient,
                    rpcEndpoint(TOKEN_SELECTION_WORKER_REST_ENDPOINT, TOKEN_SELECTION_PATH), SYNCHRONOUS)
                is TransactionVerificationRequest -> routeTo(rpcClient,
                    rpcEndpoint(VERIFICATION_WORKER_REST_ENDPOINT, VERIFICATION_PATH), SYNCHRONOUS)
                is UniquenessCheckRequestAvro -> routeTo(rpcClient,
                    rpcEndpoint(UNIQUENESS_WORKER_REST_ENDPOINT, UNIQUENESS_PATH), SYNCHRONOUS)
                is FlowEvent -> routeTo(messageBusClient,
                    FLOW_EVENT_TOPIC, ASYNCHRONOUS)
                is String -> routeTo(messageBusClient, // Handling external messaging
                    message.properties[MSG_PROP_TOPIC] as String, ASYNCHRONOUS)
                else -> {
                    val eventType = event?.let { it::class.java }
                    throw IllegalStateException("No route defined for event type [$eventType]")
                }
            }
        }
    }

    /**
     * Deserializes message payload if it is a [ByteArray] (seems to be the case for external events).
     */
    private fun MediatorMessage<Any>.event(): Any? {
        val event = payload
        return if (event is ByteArray) {
            deserializer.deserialize(event)
        } else {
            event
        }
    }
}