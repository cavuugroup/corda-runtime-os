package net.corda.httprpc.tools.annotations.validation.utils

import net.corda.httprpc.annotations.RestPathParameter
import net.corda.httprpc.annotations.RestQueryParameter
import net.corda.httprpc.annotations.ClientRequestBodyParameter
import net.corda.httprpc.annotations.isRestParameterAnnotation
import net.corda.httprpc.tools.annotations.extensions.name
import net.corda.httprpc.tools.isDuplexChannel
import java.lang.reflect.Parameter
import kotlin.reflect.full.createInstance

/**
 * The path parameter regex exposed from the HTTP-RPC module ("/{param}/")
 */
val pathParamRegex = "\\{([^/?]*)}".toRegex()

val String.asPathParam
    get() = "{$this}"

fun Parameter.isPathOrQueryParameter() =
    this.annotations.any { annotation -> annotation is RestPathParameter || annotation is RestQueryParameter }

fun Parameter.isBodyParameter() = (this.annotations.any { it is ClientRequestBodyParameter } || !this.isPathOrQueryParameter())
        && !this.type.isDuplexChannel()

@Suppress("ComplexMethod")
fun getParameterName(parameter: Parameter) =
    parameter.annotations.singleOrNull { it.isRestParameterAnnotation() }?.let {
        when (it) {
            is RestPathParameter -> it.name(parameter).lowercase()
            is RestQueryParameter -> it.name(parameter).lowercase()
            is ClientRequestBodyParameter -> it.name(parameter).lowercase()
            else -> throw IllegalArgumentException("Unknown parameter type")
        }
    } ?: ClientRequestBodyParameter::class.createInstance().name(parameter).lowercase()
