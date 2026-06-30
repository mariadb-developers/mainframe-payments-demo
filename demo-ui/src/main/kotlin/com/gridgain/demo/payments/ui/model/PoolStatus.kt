package com.gridgain.demo.payments.ui.model

/**
 * Wire shape for the generator-pool warmup endpoints. [state] is one of
 * "cold" / "scaling" / "warm" — see GeneratorPoolService.classify.
 */
data class PoolStatus(
    val poolName: String,
    val currentNodes: Int,
    val maxNodes: Int,
    val state: String,
)
