package net.corda.simulator.exceptions

import net.corda.v5.base.exceptions.CordaRuntimeException

class FlowClassNotFoundException(flowClass: String) : CordaRuntimeException(
    "Flow class $flowClass not found"
)