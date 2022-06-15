package net.corda.libs.packaging

object PackagingConstants {
    const val JAR_FILE_EXTENSION = ".jar"
    private const val META_INF_FOLDER = "META-INF"

    const val CPK_FILE_EXTENSION = ".cpk"
    const val CPK_LIB_FOLDER = "lib" // The folder that contains a CPK's library JARs.
    const val CPK_DEPENDENCIES_FILE_NAME = "CPKDependencies"
    const val CPK_DEPENDENCY_CONSTRAINTS_FILE_NAME = "DependencyConstraints"
    const val CPK_DEPENDENCIES_FILE_ENTRY = "$META_INF_FOLDER/$CPK_DEPENDENCIES_FILE_NAME"
    const val CPK_DEPENDENCY_CONSTRAINTS_FILE_ENTRY = "$META_INF_FOLDER/$CPK_DEPENDENCY_CONSTRAINTS_FILE_NAME"
    const val CPK_FORMAT_ATTRIBUTE = "Corda-CPK-Format"
    const val CPK_NAME_ATTRIBUTE = "Corda-CPK-Cordapp-Name"
    const val CPK_VERSION_ATTRIBUTE = "Corda-CPK-Cordapp-Version"
    const val CPK_LICENCE_ATTRIBUTE = "Corda-CPK-Cordapp-Licence"
    const val CPK_VENDOR_ATTRIBUTE = "Corda-CPK-Cordapp-Vendor"
    const val WORKFLOW_LICENCE_ATTRIBUTE = "Cordapp-Workflow-Licence"
    const val WORKFLOW_NAME_ATTRIBUTE = "Cordapp-Workflow-Name"
    const val WORKFLOW_VENDOR_ATTRIBUTE = "Cordapp-Workflow-Vendor"
    const val WORKFLOW_VERSION_ATTRIBUTE = "Cordapp-Workflow-Version"

    const val CPB_FILE_EXTENSION = ".cpb"
    const val CPB_FORMAT_ATTRIBUTE = "Corda-CPB-Format"
    const val CPB_NAME_ATTRIBUTE = "Corda-CPB-Name"
    const val CPB_VERSION_ATTRIBUTE = "Corda-CPB-Version"

    const val CPI_GROUP_POLICY_ENTRY = "$META_INF_FOLDER/GroupPolicy.json"
    const val CPI_FORMAT_ATTRIBUTE = "Corda-CPI-Format"
    const val CPI_NAME_ATTRIBUTE = "Corda-CPI-Name"
    const val CPI_VERSION_ATTRIBUTE = "Corda-CPI-Version"
}
