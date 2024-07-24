package fr.acinq.lightning.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.sources.MapValueSource
import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.BuildVersions
import fr.acinq.lightning.bin.conf.readConfFile
import fr.acinq.lightning.bin.datadir
import fr.acinq.lightning.bin.payments.lnurl.helpers.LnurlParser
import fr.acinq.lightning.bin.payments.lnurl.models.Lnurl
import fr.acinq.lightning.bin.payments.lnurl.models.LnurlAuth
import fr.acinq.lightning.bin.payments.Parser
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.wire.OfferTypes
import io.ktor.client.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.util.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

fun main(args: Array<String>) =
    PhoenixCli()
        .versionOption(BuildVersions.phoenixdVersion, names = setOf("--version", "-v"))
        .subcommands(
            GetInfo(),
            GetBalance(),
            ListChannels(),
            GetOutgoingPayment(),
            ListOutgoingPayments(),
            GetIncomingPayment(),
            ListIncomingPayments(),
            CreateInvoice(),
            GetOffer(),
            GetLnAddress(),
            PayInvoice(),
            PayOffer(),
            PayLnAddress(),
            DecodeInvoice(),
            DecodeOffer(),
            LnurlPay(),
            LnurlWithdraw(),
            LnurlAuth(),
            SendToAddress(),
            CloseChannel()
        )
        .main(args)

data class HttpConf(val baseUrl: Url, val httpClient: HttpClient)

class PhoenixCli : CliktCommand() {
    private val confFile = datadir / "phoenix.conf"

    private val httpBindIp by option("--http-bind-ip", help = "Bind ip for the http api").default("127.0.0.1")
    private val httpBindPort by option("--http-bind-port", help = "Bind port for the http api").int().default(9740)
    private val httpPassword by option("--http-password", help = "Password for the http api (default: reads from $confFile)").required()

    init {
        context {
            valueSource = MapValueSource(readConfFile(confFile))
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    override fun run() {
        currentContext.obj = HttpConf(
            baseUrl = Url(
                url {
                    protocol = URLProtocol.HTTP
                    host = httpBindIp
                    port = httpBindPort
                }
            ),
            httpClient = HttpClient {
                install(ContentNegotiation) {
                    json(json = Json {
                        prettyPrint = true
                        isLenient = true
                    })
                }
                install(Auth) {
                    basic {
                        credentials {
                            BasicAuthCredentials("phoenix-cli", httpPassword)
                        }
                    }
                }
            }
        )
    }
}

abstract class PhoenixCliCommand(val name: String, val help: String, printHelpOnEmptyArgs: Boolean = false) : CliktCommand(name = name, help = help, printHelpOnEmptyArgs = printHelpOnEmptyArgs) {
    internal val commonOptions by requireObject<HttpConf>()
    abstract suspend fun httpRequest(): HttpResponse
    override fun run() {
        runBlocking {
            try {
                val res = httpRequest()
                echo(res.bodyAsText())
            } catch (e: Exception) {
                echo("[${this@PhoenixCliCommand.name}] error: ${e.message}", err = true)
            }
        }
    }
}

class GetInfo : PhoenixCliCommand(name = "getinfo", help = "Show basic info about your node") {
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.get(url = commonOptions.baseUrl / "getinfo")
    }
}

class GetBalance : PhoenixCliCommand(name = "getbalance", help = "Returns your current balance") {
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.get(url = commonOptions.baseUrl / "getbalance")
    }
}

class ListChannels : PhoenixCliCommand(name = "listchannels", help = "List all channels") {
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.get(url = commonOptions.baseUrl / "listchannels")
    }
}

class GetOutgoingPayment : PhoenixCliCommand(name = "getoutgoingpayment", help = "Get outgoing payment") {
    private val uuid by option("--uuid").convert { UUID.fromString(it) }.required()
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.get(url = commonOptions.baseUrl / "payments/outgoing/$uuid")
    }
}

class ListOutgoingPayments : PhoenixCliCommand(name = "listoutgoingpayments", help = "List outgoing payments") {
    private val from by option("--from").long().help { "start timestamp in millis since epoch" }
    private val to by option("--to").long().help { "end timestamp in millis since epoch" }
    private val limit by option("--limit").long().default(20).help { "number of payments in the page" }
    private val offset by option("--offset").long().default(0).help { "page offset" }
    private val all by option("--all").boolean().default(false).help { "if true, include failed payments" }
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.get(url = commonOptions.baseUrl / "payments/outgoing") {
            url {
                parameters.append("all", all.toString())
                from?.let { parameters.append("from", it.toString()) }
                to?.let { parameters.append("to", it.toString()) }
                parameters.append("limit", limit.toString())
                parameters.append("offset", offset.toString())
            }
        }
    }
}

class GetIncomingPayment : PhoenixCliCommand(name = "getincomingpayment", help = "Get incoming payment") {
    private val paymentHash by option("--paymentHash", "--h").convert { it.toByteVector32() }.required()
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.get(url = commonOptions.baseUrl / "payments/incoming/$paymentHash")
    }
}

class ListIncomingPayments : PhoenixCliCommand(name = "listincomingpayments", help = "List incoming payments") {
    private val from by option("--from").long().help { "start timestamp in millis since epoch" }
    private val to by option("--to").long().help { "end timestamp in millis since epoch" }
    private val limit by option("--limit").long().default(20).help { "number of payments in the page" }
    private val offset by option("--offset").long().default(0).help { "page offset" }
    private val all by option("--all").boolean().default(false).help { "if true, include unpaid invoices" }
    private val externalId by option("--externalId").help { "optional external id tied to the payments" }
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.get(url = commonOptions.baseUrl / "payments/incoming") {
            url {
                parameters.append("all", all.toString())
                externalId?.let { parameters.append("externalId", it) }
                from?.let { parameters.append("from", it.toString()) }
                to?.let { parameters.append("to", it.toString()) }
                parameters.append("limit", limit.toString())
                parameters.append("offset", offset.toString())
            }
        }
    }
}

class CreateInvoice : PhoenixCliCommand(name = "createinvoice", help = "Create a Lightning invoice", printHelpOnEmptyArgs = true) {
    private val amountSat by option("--amountSat").long()
    private val description by mutuallyExclusiveOptions(
        option("--description", "--desc").convert { Either.Left(it) },
        option("--descriptionHash", "--desc-hash").convert { Either.Right(it.toByteVector32()) }
    ).single().required()

    private val externalId by option("--externalId")
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "createinvoice").toString(),
            formParameters = parameters {
                amountSat?.let { append("amountSat", it.toString()) }
                externalId?.let { append("externalId", it) }
                when(val d = description) {
                    is Either.Left -> append("description", d.value)
                    is Either.Right -> append("descriptionHash", d.value.toHex())
                }
            }
        )
    }
}

class GetOffer : PhoenixCliCommand(name = "getoffer", help = "Return a Lightning offer (static invoice)") {
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.get(url = commonOptions.baseUrl / "getoffer")
    }
}

class GetLnAddress : PhoenixCliCommand(name = "getlnaddress", help = "Return a BIP-353 Lightning address (there must be a channel)") {
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.get(url = commonOptions.baseUrl / "getlnaddress")
    }
}

class PayInvoice : PhoenixCliCommand(name = "payinvoice", help = "Pay a Lightning invoice", printHelpOnEmptyArgs = true) {
    private val amountSat by option("--amountSat").long()
    private val invoice by option("--invoice").required().check { Bolt11Invoice.read(it).isSuccess }
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "payinvoice").toString(),
            formParameters = parameters {
                amountSat?.let { append("amountSat", amountSat.toString()) }
                append("invoice", invoice)
            }
        )
    }
}

class PayOffer : PhoenixCliCommand(name = "payoffer", help = "Pay a Lightning offer", printHelpOnEmptyArgs = true) {
    private val amountSat by option("--amountSat").long()
    private val offer by option("--offer").required().check { OfferTypes.Offer.decode(it).isSuccess }
    private val message by option("--message").help { "Optional payer note" }
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "payoffer").toString(),
            formParameters = parameters {
                amountSat?.let { append("amountSat", amountSat.toString()) }
                append("offer", offer)
                message?.let { append("message", message.toString()) }
            }
        )
    }
}

class PayLnAddress : PhoenixCliCommand(name = "paylnaddress", help = "Pay a Lightning address (BIP353 or LNURL)", printHelpOnEmptyArgs = true) {
    private val amountSat by option("--amountSat").long().required()
    private val address by option("--address").required().check { Parser.parseEmailLikeAddress(it) != null }
    private val message by option("--message").help { "Optional payer note" }
    override suspend fun httpRequest(): HttpResponse = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "paylnaddress").toString(),
            formParameters = parameters {
                append("amountSat", amountSat.toString())
                append("address", address)
                message?.let { append("message", message.toString()) }
            }
        )
    }
}

class DecodeInvoice : PhoenixCliCommand(name = "decodeinvoice", help = "Decode a Lightning invoice", printHelpOnEmptyArgs = true) {
    private val invoice by option("--invoice").required().check { Bolt11Invoice.read(it).isSuccess }
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "decodeinvoice").toString(),
            formParameters = parameters {
                append("invoice", invoice)
            }
        )
    }
}

class DecodeOffer : PhoenixCliCommand(name = "decodeoffer", help = "Decode a Lightning offer", printHelpOnEmptyArgs = true) {
    private val invoice by option("--offer").required().check { OfferTypes.Offer.decode(it).isSuccess }
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "decodeoffer").toString(),
            formParameters = parameters {
                append("offer", invoice)
            }
        )
    }
}

class LnurlPay : PhoenixCliCommand(name = "lnurlpay", help = "Pay a LNURL", printHelpOnEmptyArgs = true) {
    private val amountSat by option("--amountSat").long()
    private val lnurl by option("--lnurl").required()
        .check("not a valid lnurl-pay link") {
        val url = kotlin.runCatching { LnurlParser.extractLnurl(it) }.getOrNull()
        url is Lnurl.Request && (url.tag == Lnurl.Tag.Pay || url.tag == null)
    }
    private val message by option("--message").help { "Optional comment" }
    override suspend fun httpRequest(): HttpResponse = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "lnurlpay").toString(),
            formParameters = parameters {
                amountSat?.let { append("amountSat", amountSat.toString()) }
                append("lnurl", lnurl)
                message?.let { append("message", message.toString()) }
            }
        )
    }
}

class LnurlWithdraw : PhoenixCliCommand(name = "lnurlwithdraw", help = "Withdraw funds from a LNURL service", printHelpOnEmptyArgs = true) {
    private val lnurl by option("--lnurl").required()
        .check("not a valid lnurl-withdraw link") {
            val url = kotlin.runCatching { LnurlParser.extractLnurl(it) }.getOrNull()
            url is Lnurl.Request && (url.tag == Lnurl.Tag.Withdraw || url.tag == null)
        }
    override suspend fun httpRequest(): HttpResponse = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "lnurlwithdraw").toString(),
            formParameters = parameters {
                append("lnurl", lnurl)
            }
        )
    }
}

class LnurlAuth : PhoenixCliCommand(name = "lnurlauth", help = "Authenticate on a LNURL service", printHelpOnEmptyArgs = true) {
    private val lnurl by option("--lnurl").required()
        .check("not a valid lnurl-auth link") {
            val url = kotlin.runCatching { LnurlParser.extractLnurl(it) }.getOrNull()
            url is LnurlAuth
        }
    override suspend fun httpRequest(): HttpResponse = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "lnurlauth").toString(),
            formParameters = parameters {
                append("lnurl", lnurl)
            }
        )
    }
}

class SendToAddress : PhoenixCliCommand(name = "sendtoaddress", help = "Send to a Bitcoin address", printHelpOnEmptyArgs = true) {
    private val amountSat by option("--amountSat").long().required()
    private val address by option("--address").required().check { runCatching { Base58Check.decode(it) }.isSuccess || runCatching { Bech32.decodeWitnessAddress(it) }.isSuccess }
    private val feerateSatByte by option("--feerateSatByte").int().required()
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "sendtoaddress").toString(),
            formParameters = parameters {
                append("amountSat", amountSat.toString())
                append("address", address)
                append("feerateSatByte", feerateSatByte.toString())
            }
        )
    }
}

class CloseChannel : PhoenixCliCommand(name = "closechannel", help = "Close channel", printHelpOnEmptyArgs = true) {
    private val channelId by option("--channelId").convert { it.toByteVector32() }.required()
    private val address by option("--address").required().check { runCatching { Base58Check.decode(it) }.isSuccess || runCatching { Bech32.decodeWitnessAddress(it) }.isSuccess }
    private val feerateSatByte by option("--feerateSatByte").int().required()
    override suspend fun httpRequest() = commonOptions.httpClient.use {
        it.submitForm(
            url = (commonOptions.baseUrl / "closechannel").toString(),
            formParameters = parameters {
                append("channelId", channelId.toHex())
                append("address", address)
                append("feerateSatByte", feerateSatByte.toString())
            }
        )
    }
}

operator fun Url.div(path: String) = Url(URLBuilder(this).appendPathSegments(path))

fun String.toByteVector32(): ByteVector32 = kotlin.runCatching { ByteVector32.fromValidHex(this) }.recover { error("'$this' is not a valid 32-bytes hex string") }.getOrThrow()