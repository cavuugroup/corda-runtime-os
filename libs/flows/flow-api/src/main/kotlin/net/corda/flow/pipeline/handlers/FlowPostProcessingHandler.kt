package net.corda.flow.pipeline.handlers

import net.corda.flow.pipeline.events.FlowEventContext
import net.corda.messaging.api.records.Record

/**
 * Provides and extension point to the Global post-processing stage of the [FlowEventPipeline].
 */
interface FlowPostProcessingHandler {

    /**
     * Performs a post-processing step at the end of the [FlowEventPipeline].
     *
     * Components that need to apply cleanup behavior at the end of the [FlowEventPipeline] can
     * implement this interface. The platform will automatically execute the handler at the end of each invocation of
     * the [FlowEventPipeline]. The platform makes no guarantee of the order in which these handlers are called.
     *
     * @param context The current [FlowEventContext] .
     *
     * @return List of [Record] generated by the post-processing action.
     */
    fun postProcess(context: FlowEventContext<Any>): List<Record<*, *>>
}