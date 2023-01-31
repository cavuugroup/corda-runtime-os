package net.corda.httprpc.server.impl.rest.resources.impl

import net.corda.httprpc.server.impl.rest.resources.MultipleParamAnnotationApi
import net.corda.httprpc.PluggableRestResource

class MultipleParamAnnotationApiImpl : MultipleParamAnnotationApi, PluggableRestResource<MultipleParamAnnotationApi> {

    override val targetInterface: Class<MultipleParamAnnotationApi>
        get() = MultipleParamAnnotationApi::class.java

    override val protocolVersion: Int
        get() = 2

    override fun test(twoAnnotations: String) {
    }
}