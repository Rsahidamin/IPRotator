# IP Rotator (SOCKS5 rotating VPN)

## Get the APK (no Android Studio needed)
1. Create a free GitHub repo, upload ALL files of this folder (keep the `.github` folder).
2. Open the repo -> Actions -> "Build APK" -> wait ~5-8 min (or press "Run workflow").
3. Open the finished run -> Artifacts -> download `IPRotator-debug-apk` -> unzip -> install `app-debug.apk`
   (allow "install unknown apps").

## Use
1. Proxies tab -> Import a .txt/.csv (`ip:port` or `ip:port:user:pass`, one per line) -> Test.
2. Rotator tab -> tap the big button -> accept the VPN prompt.
3. Settings: interval, kill switch, per-app tunneling.

## How it works
Android VpnService (TUN stays open) -> hev-socks5-tunnel (native) -> local SOCKS5 relay 127.0.0.1:1080
-> currently active upstream proxy. Every interval the relay switches proxy and resets live connections.
DNS goes through the proxy (mapdns). UDP/QUIC is not supported, apps fall back to TCP.
