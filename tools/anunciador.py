"""
Anuncia por UDP donde bajar el APK, para que el radio no necesite saber la
IP de esta laptop.

Anuncia en vez de solo responder porque el firewall de Windows descarta el
UDP entrante y abrirle un hueco pide permisos de administrador. El trafico
saliente si esta permitido, asi que la difusion periodica funciona sin
tocar nada. Igual se contestan las preguntas, por si llegan.
"""
import socket
import sys
import time

PUERTO = 8098
PUERTO_HTTP = 8000
CADA_SEGUNDOS = 2.0

# La palabra es la de esta app (PerfilVehiculo.tokenDescubrimiento): el
# radio solo hace caso al servidor que la dice.
TOKEN = "S2000DASH"
PREGUNTA = (TOKEN + "?").encode("ascii")
PREFIJO = TOKEN + "="


def ip_local():
    """IP de la interfaz que de verdad sale a la red, sin adivinar."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # 8.8.8.8 y no la puerta de enlace del taller: no se manda ningun
        # paquete, solo se le pide al sistema por que interfaz saldria. Con una
        # IP fija de una red concreta esto devuelve None en cuanto se cambia de
        # red — y entonces el anuncio nunca sale, el radio no encuentra el
        # servidor, y /update contesta "started: false" sin decir por que.
        s.connect(("8.8.8.8", 53))
        return s.getsockname()[0]
    except Exception:
        return None
    finally:
        s.close()


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind(("0.0.0.0", 0))
    except Exception as e:
        print(f"no se pudo abrir el socket: {e}", file=sys.stderr, flush=True)
        return

    sock.settimeout(0.5)
    print(f"Anunciador activo; difundiendo cada {CADA_SEGUNDOS}s a UDP {PUERTO}", flush=True)

    ultimo = ""
    while True:
        # Se resuelve en cada vuelta: si la laptop cambia de red mientras
        # esto corre, el anuncio sigue siendo correcto sin reiniciar nada.
        ip = ip_local()
        if not ip:
            time.sleep(CADA_SEGUNDOS)
            continue
        mensaje = f"{PREFIJO}http://{ip}:{PUERTO_HTTP}".encode("ascii")

        try:
            sock.sendto(mensaje, ("255.255.255.255", PUERTO))
            if mensaje != ultimo:
                print(f"anunciando http://{ip}:{PUERTO_HTTP}", flush=True)
                ultimo = mensaje
        except Exception as e:
            print(f"fallo la difusion: {e}", file=sys.stderr, flush=True)

        # Atender preguntas que hayan llegado, si el firewall las dejo pasar.
        fin = time.time() + CADA_SEGUNDOS
        while time.time() < fin:
            try:
                datos, origen = sock.recvfrom(256)
            except socket.timeout:
                continue
            except Exception:
                break
            if datos.strip() == PREGUNTA:
                try:
                    sock.sendto(mensaje, origen)
                    print(f"contestado a {origen[0]}", flush=True)
                except Exception:
                    pass


if __name__ == "__main__":
    main()
