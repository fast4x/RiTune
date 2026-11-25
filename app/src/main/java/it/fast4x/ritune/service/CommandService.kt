package it.fast4x.ritune.service

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.server.netty.Netty
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import it.fast4x.ritune.appContext
import it.fast4x.ritune.models.PlayerState
import it.fast4x.ritune.models.RemoteCommand
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json

class CommandService(
    private val activity: Activity,
    private val onCommandLoad: (mediaId: String, position: Float) -> Unit,
    private val onCommandPlay: (mediaId: String) -> Unit,
    private val onCommandPause: () -> Unit,
    private val onCommandSeek: (Float) -> Unit
) {

    val connections = ConcurrentHashMap<DefaultWebSocketSession, Unit>()

    private val server by lazy {

        embeddedServer(Netty, configure = {

            // Extra connectors
//            connectors.add(EngineConnectorBuilder().apply {
//                //host = "127.0.0.1"
//                port = 8080
//            })

            //envConfigWithoutSSL()
            envConfigWithSSL()

            connectionGroupSize = 2
            workerGroupSize = 5
            callGroupSize = 10
            shutdownGracePeriod = 2000
            shutdownTimeout = 3000
        }) {
            install(WebSockets) {
                pingPeriod = 15.seconds
                timeout = 15.seconds
                maxFrameSize = Long.MAX_VALUE
            }
            module()
        }
    }

    fun stop() {
        isServiceRunning = false
        unregisterNetworkCallback()
        server.stop(1000, 5000)
    }

    fun Application.module() {
        install(IgnoreTrailingSlash)

        routing {
            get("/") {
                call.respondText("RiTune Server running on ${ipAddress()}")
            }

            webSocket("/ws") {
                connections[this] = Unit
                Timber.d("RiTune CommandServiceWS: Controller connesso. Totale connessi: ${connections.size}")

                try {
                    currentState?.let {
                        val json = Json.encodeToString(it)
                        send(json)
                    }

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val receivedText = frame.readText()
                            handleCommand(receivedText)
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    Timber.d("RiTune CommandServiceWS: Controller disconnesso")
                } catch (e: Exception) {
                    Timber.e(e, "RiTune CommandServiceWS: Errore WebSocket")
                } finally {
                    connections.remove(this)
                }
            }
        }
    }

    private suspend fun handleCommand(jsonString: String) {
        try {
            val cmd = Json.decodeFromString<RemoteCommand>(jsonString)
            Timber.d("RiTune CommandServiceWS: Comando ricevuto: $cmd")

            when (cmd.action) {
                "load" -> cmd.mediaId?.let { onCommandLoad(it, cmd.position ?: 0f) }
                "play" -> cmd.mediaId?.let { onCommandPlay(it)}
                "pause" -> onCommandPause()
                "seek" -> cmd.position?.let { onCommandSeek(it) }
                "sync" -> currentState?.let {
                    currentState?.let { broadcastState(it) }
                }
            }
        } catch (e: Exception) {
            Timber.e("RiTune CommandServiceWS: Errore parsing comando: ${e.message}")
        }
    }

//    private suspend fun DefaultWebSocketSession.sendState(state: PlayerState) {
//        try {
//            val jsonString = Json.encodeToString(state)
//            send(jsonString)
//        } catch (e: Exception) {
//            Timber.e(e, "CommandServiceWS: Errore invio stato sessione")
//        }
//    }

    suspend fun broadcastState(state: PlayerState) {
        currentState = state
        val jsonString = Json.encodeToString(state)

        for (session in connections.keys) {
            try {
                session.send(jsonString)
            } catch (e: Exception) {
                Timber.e("RiTune CommandServiceWS: Errore invio stato a client: ${e.message}")
                // Opzionale: rimuovi la sessione se l'invio fallisce
                // connections.remove(session)
            }
        }
    }

    fun ApplicationEngine.Configuration.envConfigWithoutSSL() {

        connector {
            host = "0.0.0.0"
            port = 13456
        }
        connector {
            host = "0.0.0.0"
            port = 9090
        }
    }

    fun ApplicationEngine.Configuration.envConfigWithSSL() {
        // Configurazione HTTP standard
        connector { port = 18000 }

        val currentIp = ipAddress ?: "127.0.0.1"
        Timber.d("RiTune CommandServiceWS: Generazione certificato per IP: $currentIp") // Verifica questo nel Logcat

        // Percorso del file certificato
        val keyStoreFile = File("${appContext().externalCacheDir?.absolutePath}/build/keystore.jks")

        // Assicurati che la cartella esista
        if (keyStoreFile.parentFile?.exists() == false) {
            keyStoreFile.parentFile?.mkdirs()
        }

        val keyStore = buildKeyStore {
            certificate("riPlayAlias") {
                password = "biPwd1@"
                domains = listOf(
                    "127.0.0.1",
                    "0.0.0.0",
                    "localhost",
                    ipAddress ?: "0.0.0.0"
                )
            }
        }
        keyStore.saveToFile(keyStoreFile, "123456")

        sslConnector(
            keyStore = keyStore,
            keyAlias = "riPlayAlias",
            keyStorePassword = { "wo0567#".toCharArray() },
            privateKeyPassword = { "biPwd1@".toCharArray() }
        ) {
            port = 18443
            keyStorePath = keyStoreFile
        }
    }

    fun start() {
        ipAddress = findIPAddress(activity)
        isServiceRunning = true
        registerNetworkCallback()
        CoroutineScope(Dispatchers.IO).launch {
            server.start(wait = true)
        }
    }

    /**
     * Individua l'indirizzo IPv4 locale del dispositivo in modo agnostico dal
     * mezzo di connessione (WiFi, ethernet, hotspot...).
     *
     * Bug originale: la detection usava solo WifiManager.connectionInfo, che
     * restituisce l'IP SOLO quando la connessione e' WiFi; con cavo ethernet
     * wifiInfo.ipAddress vale 0 e l'indirizzo mostrato diventava 0.0.0.0.
     *
     * Strategia a livelli:
     *  1. ConnectivityManager + LinkProperties (ethernet, WiFi, hotspot; richiede
     *     solo ACCESS_NETWORK_STATE, gia' presente nel manifest);
     *  2. enumerazione delle NetworkInterface (fallback agnostico dal trasporto);
     *  3. WifiManager (ultima spiaggia, solo WiFi, con controllo ip != 0).
     */
    private fun findIPAddress(context: Context): String? {
        findIpAddressFromConnectivity(context)?.let { return it }
        findIpAddressFromNetworkInterfaces()?.let { return it }
        return findIpAddressFromWifiManager(context)
    }

    /**
     * Estrae il primo IPv4 utilizzabile da un LinkProperties, scartando
     * esplicitamente 0.0.0.0 (isAnyLocalAddress), loopback e link-local.
     */
    private fun firstUsableIpv4(linkProperties: LinkProperties?): String? {
        if (linkProperties == null) return null
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (address is Inet4Address &&
                !address.isAnyLocalAddress &&  // 0.0.0.0
                !address.isLoopbackAddress &&  // 127.x.x.x
                !address.isLinkLocalAddress && // 169.254.x.x
                address.hostAddress != null
            ) {
                return address.hostAddress
            }
        }
        return null
    }

    /**
     * Livello 1: ConnectivityManager. Iterate tutte le reti note (attiva per
     * prima) ed estrae l'IPv4 da LinkProperties. Le VPN vengono ignorate:
     * per il controllo remoto conta l'IP sulla rete locale (LAN).
     */
    private fun findIpAddressFromConnectivity(context: Context): String? {
        return try {
            val connectivityManager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val candidates = connectivityManager.allNetworks.toMutableList()
            if (activeNetwork != null) {
                candidates.remove(activeNetwork)
                candidates.add(0, activeNetwork)
            }

            // Passata 1: ethernet (preferita: connessione via cavo, tipica di TV box)
            for (network in candidates) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                firstUsableIpv4(connectivityManager.getLinkProperties(network))?.let { return it }
            }

            // Passata 2: WiFi (incl. hotspot)
            for (network in candidates) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                firstUsableIpv4(connectivityManager.getLinkProperties(network))?.let { return it }
            }

            // Passata 3: qualsiasi altra rete non VPN con un IPv4 valido
            for (network in candidates) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                firstUsableIpv4(connectivityManager.getLinkProperties(network))?.let { return it }
            }
            null
        } catch (e: Exception) {
            Timber.e("RiTune CommandServiceWS: Error finding IpAddress via ConnectivityManager: ${e.message}")
            null
        }
    }

    /**
     * Livello 2: enumerazione delle NetworkInterface, indipendente da
     * ConnectivityManager. Vengono preferite le interfacce eth/wlan
     */
    private fun findIpAddressFromNetworkInterfaces(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var candidate: String? = null
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) continue
                val name = networkInterface.name?.lowercase() ?: ""
                for (address in networkInterface.inetAddresses) {
                    if (address !is Inet4Address) continue
                    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    if (name.startsWith("eth") || name.startsWith("wlan")) return address.hostAddress
                    if (candidate == null) candidate = address.hostAddress
                }
            }
            candidate
        } catch (e: Exception) {
            Timber.e("RiTune CommandServiceWS: Error finding IpAddress via NetworkInterface: ${e.message}")
            null
        }
    }

    /**
     * Livello 3 (ultima spiaggia, solo WiFi): WifiManager legacy. Il controllo
     * ipAddress == 0 evita di riproporre il bug del 0.0.0.0 con ethernet.
     */
    @Suppress("DEPRECATION")
    private fun findIpAddressFromWifiManager(context: Context): String? {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo ?: return null
            if (wifiInfo.ipAddress == 0) return null
            InetAddress.getByAddress(
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(wifiInfo.ipAddress).array()
            ).hostAddress
        } catch (e: Exception) {
            Timber.e("RiTune CommandServiceWS: Error finding IpAddress via WifiManager: ${e.message}")
            null
        }
    }

    /**
     * Registra (una sola volta per processo) un NetworkCallback di default: se
     * la rete cambia dopo l'avvio (es. cavo ethernet collegato o scollegato,
     * IP nuovo via DHCP) l'indirizzo mostrato a schermo viene aggiornato.
     * Il server Netty resta in ascolto su tutte le interfacce, quindi non e'
     * necessario riavviarlo.
     */
    private fun registerNetworkCallback() {
        synchronized(networkCallbackLock) {
            if (networkCallback != null) return
            try {
                val connectivityManager =
                    appContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        refreshIpAddress()
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        refreshIpAddress()
                    }
                }
                connectivityManager.registerDefaultNetworkCallback(callback)
                networkCallback = callback
                Timber.d("RiTune CommandServiceWS: NetworkCallback registrato per il refresh automatico dell'IP")
            } catch (e: Exception) {
                Timber.e("RiTune CommandServiceWS: Registrazione NetworkCallback fallita: ${e.message}")
            }
        }
    }

    private fun unregisterNetworkCallback() {
        synchronized(networkCallbackLock) {
            try {
                networkCallback?.let { callback ->
                    val connectivityManager =
                        appContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    connectivityManager.unregisterNetworkCallback(callback)
                }
            } catch (e: Exception) {
                Timber.e("RiTune CommandServiceWS: Deregistrazione NetworkCallback fallita: ${e.message}")
            } finally {
                networkCallback = null
            }
        }
    }

    private fun refreshIpAddress() {
        val newIp = findIPAddress(activity)
        if (newIp != null && newIp != ipAddress) {
            Timber.d("RiTune CommandServiceWS: IpAddress aggiornato: $ipAddress -> $newIp")
            ipAddress = newIp
        }
    }

    @Synchronized fun isServiceRunning() = isServiceRunning
    @Synchronized fun ipAddress() = ipAddress

    companion object {
        private val networkCallbackLock = Any()
        private var isServiceRunning = false
        private var ipAddress: String? = null
        private var currentState: PlayerState? = null
        private var networkCallback: ConnectivityManager.NetworkCallback? = null
    }
}
