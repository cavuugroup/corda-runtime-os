package net.corda.httprpc.tools.annotations.validation

import net.corda.httprpc.RestResource
import net.corda.httprpc.annotations.HttpPOST
import net.corda.httprpc.annotations.HttpPUT
import net.corda.httprpc.durablestream.api.returnsDurableCursorBuilder
import java.lang.reflect.Method

/**
 * Validates that durable stream methods are POST or PUT. This is required, as an implicit DurableStreamContext parameter
 * will be added to the call.
 */
internal class DurableStreamsEndPointValidator(private val clazz: Class<out RestResource>) : RestValidator {

    companion object {
        val error = "Methods returning DurableCursorBuilder or FiniteDurableCursorBuilder " +
                "can only be exposed via ${HttpPOST::class.simpleName} or ${HttpPUT::class.simpleName}."
    }

    override fun validate(): RestValidationResult =
        clazz.methods.fold(RestValidationResult()) { total, method ->
            total + if (method.annotations.none { it is HttpPOST || it is HttpPUT }) {
                validateReturnTypeOnWrongMethod(method)
            } else RestValidationResult()
        }

    private fun validateReturnTypeOnWrongMethod(method: Method) =
        if (method.returnsDurableCursorBuilder()) {
            RestValidationResult(listOf(error))
        } else RestValidationResult()
}