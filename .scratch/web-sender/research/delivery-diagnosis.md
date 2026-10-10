# Web sender → phone delivery diagnosis

## Root cause
The two ends are on **different brokers**. The live web sender (catalingb.github.io/grandpa-pager) dials the **Flespi cloud** (`wss://mqtt.flespi.io:443`, `sender.js:11`, confirmed served live), while the phone connects to the **local broker** on `ssl://127.0.0.1:8883` via `adb reverse`. The web app's "connected" is a clean Flespi socket; the phone subscribes on the local Aedes. Messages never meet.

## Evidence
1. **Phone = local broker.** logcat: `MqttController: connecting to ssl://127.0.0.1:8883 as grandpa-rx-… (cleanSession=false)`, subscribed `[grandpa/message, grandpa/command]`. PID 10818 alive; `adb reverse` `tcp:8883 tcp:8883`. (Prefs unreadable: app not debuggable, `run-as`/direct cat denied; pushed `prefs-receiver.xml` = 127.0.0.1:8883, user `grandpa`.)
2. **Local publish reaches phone.** Published retained "PHONE-BROKER TEST 7721" → logcat `showing message 123f7a06-… from LocalBot`; screenshot shows the text.
3. **Flespi publish does NOT.** Published "FLESPI-BROKER TEST 9931" to `grandpa/message` (publish ok) → phone logcat empty, screenshot unchanged. 8s Flespi subscribe saw **no** `grandpa/status` (phone absent). Local retained status shows phone idle/online.

## Fix
Put both ends on one broker: either set the web Settings broker URL to the reachable local `wss://<host>:8084`, or point the phone at Flespi (`mqtt.flespi.io:8883` TLS, token user) and drop the reverse.

## Secondary
Token mismatch: web username = `FLESPI_TOKEN`; phone user = `grandpa`. Stale retained state on both brokers.
