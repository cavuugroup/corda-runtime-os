package net.corda.p2p.fake.ca

import net.corda.crypto.test.certificates.generation.toPem
import net.corda.p2p.fake.ca.Ca.Companion.CA_NAME
import net.corda.utilities.crypto.publicKeyFactory
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.File
import java.security.PublicKey

@Command(
    name = "create-certificate",
    aliases = ["certificate", "cert"],
    mixinStandardHelpOptions = true,
    showDefaultValues = true,
    description = ["Create TLS certificates"],
    showAtFileInUsageHelp = true,
    subcommandsRepeatable = true,
)
class CreateCertificate : Runnable {
    @Parameters(
        description = ["The DNS names that will be set as subject alternative names in the certificate."],
        arity = "1..*",
    )
    private lateinit var dnsNames: List<String>

    @Option(
        names = ["-n", "--name"],
        description = ["The filename of the certificate (defaults to the first host name)."],
    )
    private var name: String? = null

    @Option(
        names = ["-p", "--public-key"],
        description = ["The filename of the public key (in PEM format). If not specified, a new key pair is generated."],
    )
    private var publicKeyFile: File? = null

    @ParentCommand
    private lateinit var ca: Ca

    override fun run() {
        if (!ca.home.isDirectory) {
            throw FakeCaException("Please create the CA before attempting to create certificates")
        }
        val actualName = name ?: dnsNames.first()
        if (actualName == CA_NAME) {
            throw FakeCaException("The name '$CA_NAME' can not be used")
        }
        val dir = File(ca.home, actualName).also {
            it.mkdirs()
        }

        val certificate = if (publicKeyFile == null) {
            val keysAndCertificate = ca.authority.generateKeyAndCertificates(dnsNames)
            File(dir, "keys.pem").also {
                it.writeText(keysAndCertificate.privateKey.toPem())
                println("Wrote keys to $it")
            }
            keysAndCertificate.certificates.first()
        } else {
            ca.authority.generateCertificates(dnsNames, readPemPublicKey(publicKeyFile!!)).first()
        }

        // Save the authority because the serial number had changed.
        ca.authority.save()

        File(dir, "certificate.pem").also {
            it.writeText(certificate.toPem())
            println("Wrote certificate to $it")
        }
    }

    private fun readPemPublicKey(keyFile: File): PublicKey {
        return publicKeyFactory(keyFile.reader()) ?: throw FakeCaException("Invalid public key file")
    }
}
