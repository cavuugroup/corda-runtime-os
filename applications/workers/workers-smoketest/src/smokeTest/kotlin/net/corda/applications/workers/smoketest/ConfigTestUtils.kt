package net.corda.applications.workers.smoketest

import com.fasterxml.jackson.databind.JsonNode
import java.io.IOException
import java.time.Duration
import kong.unirest.UnirestException
import net.corda.applications.workers.smoketest.virtualnode.helpers.assertWithRetryIgnoringExceptions
import net.corda.applications.workers.smoketest.virtualnode.helpers.cluster
import net.corda.httprpc.ResponseCode.OK
import net.corda.test.util.eventually
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.fail

fun JsonNode.sourceConfigNode(): JsonNode =
    this["sourceConfig"].textValue().toJson()

fun JsonNode.configWithDefaultsNode(): JsonNode =
    this["configWithDefaults"].textValue().toJson()

/**
 * Get the current configuration (as a [JsonNode]) for the specified [section].
 */
fun getConfig(section: String): JsonNode {
    return cluster {
        endpoint(CLUSTER_URI, USERNAME, PASSWORD)

        assertWithRetryIgnoringExceptions {
            command { getConfig(section) }
            condition { it.code == OK.statusCode }
        }.body.toJson()
    }
}

/**
 * Update the cluster configuration with the specified [config] for the requested [section].
 * The currently installed schema and configuration versions are automatically obtained from the running system
 * before updating.
 */
fun updateConfig(config: String, section: String) {
    return cluster {
        endpoint(CLUSTER_URI, USERNAME, PASSWORD)

        val currentConfig = getConfig(section).body.toJson()
        val currentSchemaVersion = currentConfig["schemaVersion"]

        try {
            val result = putConfig(
                config,
                section,
                currentConfig["version"].toString(),
                currentSchemaVersion["major"].toString(),
                currentSchemaVersion["minor"].toString())

            if (result.code != 202) {
                fail<String>("Config update did not return 202. returned ${result.code} instead. Result ${result.body}")
            }

        } catch (ex: UnirestException) {
            //When a config request is sent with the section set to "corda.messaging" nearly all components in the system will respond to
            // this config change. This will cause the HttpGateway to go down bringing down the HttpServer. This will close all the
            // connections open from clients such as the one used in this test resulting in a UniRestException thrown
            // for the purposes of the test we will this exception and allow the test to proceed.
            // One solution would be for the config endpoint to return a successful result as soon as it receives confirmation the config
            // request is on kafka and to not bother to try return the updated config.
            // https://r3-cev.atlassian.net/browse/CORE-7930
        }
    }
}

/**
 * Wait for the REST API on the rpc-worker to become unavailable and available again, asserting that the expected
 * configuration change took place.
 */
fun waitForConfigurationChange(section: String, key: String, value: String, timeout: Duration = Duration.ofMinutes(1)) {
    cluster {
        endpoint(CLUSTER_URI, USERNAME, PASSWORD)

        // Wait for the service to become unavailable
        eventually(timeout) {
            assertThatThrownBy {
                getConfig(section)
            }.hasCauseInstanceOf(IOException::class.java)
        }

        // Wait for the service to become available again and have the expected configuration value
        assertWithRetryIgnoringExceptions {
            timeout(timeout)
            command { getConfig(section) }
            condition {
                it.code == OK.statusCode && it.body.toJson().sourceConfigNode()[key].asInt().toString() == value
            }
        }
    }
}
