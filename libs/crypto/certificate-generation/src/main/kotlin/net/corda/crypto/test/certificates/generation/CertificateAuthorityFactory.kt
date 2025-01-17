package net.corda.crypto.test.certificates.generation

import java.io.File
import java.time.Duration

/**
 * A Factory object to create a [CertificateAuthority].
 */
object CertificateAuthorityFactory {

    /**
     * Create a new local authority in memory.
     *
     * @param keysFactoryDefinitions - The keys factory definitions (currently, only RSA and EC are supported)
     * @param validDuration - The duration after which the certificate will become invalid
     */
    fun createMemoryAuthority(
        keysFactoryDefinitions: KeysFactoryDefinitions,
        validDuration: Duration = Duration.ofDays(30),
    ): CertificateAuthority {
        return LocalCertificatesAuthority(keysFactoryDefinitions, validDuration, null, issuerName = null)
    }

    /**
     * Load any saved authority from the [home] directory, if none exists, generate a new one.
     * Using the [FileSystemCertificatesAuthority.save] will save the authority for future use.
     *
     * @param keysFactoryDefinitions - The keys factory definitions (currently, only RSA and EC are supported)
     * @param home - The home directory to save the CA private keys, certificate and serial numbers.
     *      If the specified directory already exists, the factory will attempt to reload the authority from the files in that directory.
     *      Otherwise, a new certificate authority will be created.
     * @param validDuration - The duration after which the certificate will become invalid
     */
    fun createFileSystemLocalAuthority(
        keysFactoryDefinitions: KeysFactoryDefinitions,
        home: File,
        validDuration: Duration = Duration.ofDays(30),
    ): FileSystemCertificatesAuthority {
        return FileSystemCertificatesAuthorityImpl.loadOrGenerate(
            keysFactoryDefinitions, validDuration, home
        )
    }
}
