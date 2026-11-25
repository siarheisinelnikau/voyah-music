package app.voyahmusic.shared.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

data class WifiLink(val address: InetAddress, val prefix: Int, val network: Network?) {
    fun includes(peer: InetAddress?): Boolean {
        return MusicWire.sameSubnet(address, peer, prefix)
    }

    companion object {
        fun available(context: Context, includeHotspot: Boolean): List<WifiLink> {
            val manager =
                context.getSystemService<ConnectivityManager?>(ConnectivityManager::class.java)
            val links: MutableMap<InetAddress, WifiLink> =
                LinkedHashMap<InetAddress, WifiLink>()
            for (network in manager.getAllNetworks()) {
                val capabilities = manager.getNetworkCapabilities(network)
                val properties = manager.getLinkProperties(network)
                if (capabilities == null || properties == null || !capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    )
                ) continue
                for (link in properties.getLinkAddresses()) {
                    WifiLink.add(
                        links,
                        link.getAddress(),
                        link.getPrefixLength(),
                        network
                    )
                }
            }
            if (includeHotspot) {
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    if (interfaces != null) {
                        for (iface in Collections.list<NetworkInterface?>(interfaces)) {
                            if (!iface.isUp() || !iface.getName()
                                    .matches("(wlan|swlan|softap|ap|wifi|br)[0-9]+".toRegex())
                            ) continue
                            for (link in iface.getInterfaceAddresses()) {
                                WifiLink.add(
                                    links,
                                    link.getAddress(),
                                    link.getNetworkPrefixLength().toInt(),
                                    null
                                )
                            }
                        }
                    }
                } catch (ignored: SocketException) {
                    // The connectivity API already supplied any active Wi-Fi client links.
                }
            }
            return links.values.toList()
        }

        private fun add(
            links: MutableMap<InetAddress, WifiLink>,
            address: InetAddress?,
            prefix: Int,
            network: Network?
        ) {
            if (address != null && MusicWire.isLocal(address) && prefix > 0 && prefix <= 32) {
                links.putIfAbsent(address, WifiLink(address, prefix, network))
            }
        }
    }
}
