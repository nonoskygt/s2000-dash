package com.nonosky.carsoc.selfupdate

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.nonosky.carsoc.Carro
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Encuentra el servidor de actualizaciones sin saber su IP.
 *
 * Se aprendio por las malas: la laptop del taller paso de Wi-Fi a Ethernet,
 * cambio de 192.168.2.194 a 192.168.2.20, y la URL que la app llevaba
 * clavada quedo muerta — sin nadie que pudiera arreglarla, porque el acceso
 * remoto al radio se habia caido en el mismo reinicio. Con DHCP de por
 * medio, cualquier IP fija vuelve a romperse tarde o temprano.
 *
 * **El radio escucha y la laptop anuncia**, y no al reves, porque el
 * firewall de Windows descarta el UDP entrante y abrirle un hueco exige
 * permisos de administrador. Anunciar es trafico saliente y no pide nada.
 * De todos modos se manda tambien una pregunta, que acelera el hallazgo
 * cuando el firewall si la deja pasar.
 */
object ServerDiscovery {

    private const val TAG = "ServerDiscovery"
    const val PUERTO = 8098

    /**
     * La palabra va POR CARRO. Antes estaba escrita aqui una sola, y la
     * usaban las dos apps: un radio buscaba el servidor de la otra app,
     * y si lo encontraba se bajaba un APK de otro paquete que el instalador
     * rechazaba — o sea, no se actualizaba nunca, sin decir por que.
     */
    val PREGUNTA: String get() = Carro.perfil.tokenDescubrimiento + "?"
    val PREFIJO_RESPUESTA: String get() = Carro.perfil.tokenDescubrimiento + "="

    /** @return URL base (p.ej. `http://192.168.2.20:8000`) o null. */
    fun discover(context: Context, timeoutMs: Int = 4_000): String? {
        // En Wi-Fi con ahorro de energia, Android descarta los paquetes de
        // difusion si nadie sostiene este candado.
        val lock = runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock("carsoc-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        return try {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.broadcast = true
                sock.bind(java.net.InetSocketAddress(PUERTO))
                sock.soTimeout = timeoutMs

                // Preguntar por si acaso; si el firewall la tira, seguimos
                // esperando el anuncio periodico igualmente.
                runCatching {
                    val q = PREGUNTA.toByteArray(Charsets.US_ASCII)
                    sock.send(
                        DatagramPacket(
                            q, q.size,
                            InetAddress.getByName("255.255.255.255"), PUERTO
                        )
                    )
                }

                val limite = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < limite) {
                    val paquete = DatagramPacket(buf, buf.size)
                    sock.receive(paquete)
                    val texto = String(paquete.data, 0, paquete.length, Charsets.US_ASCII).trim()
                    // Ignorar el eco de nuestra propia pregunta.
                    if (!texto.startsWith(PREFIJO_RESPUESTA)) continue

                    val base = texto.removePrefix(PREFIJO_RESPUESTA).trim().trimEnd('/')
                    // Viene de la red: no se confia a ciegas.
                    if (!base.startsWith("http://") && !base.startsWith("https://")) {
                        Log.w(TAG, "URL no valida: '$base'")
                        continue
                    }
                    Log.i(TAG, "Servidor anunciado en $base")
                    return base
                }
                null
            }
        } catch (e: Exception) {
            // Lo normal cuando nadie anuncia es SocketTimeoutException.
            Log.i(TAG, "Nadie anuncio: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { lock?.release() }
        }
    }
}
