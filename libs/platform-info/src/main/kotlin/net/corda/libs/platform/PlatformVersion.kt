package net.corda.libs.platform

enum class PlatformVersion(val value: Int) {
    CORDA_5_0(50000),
    CORDA_5_1(50100),
    CORDA_5_2(50200),
    CORDA_5_2_1(50201),
    CORDA_JSON_BLOB_HEADER(CORDA_5_2_1.value)
}