'use strict';
/**
 * Charge controller for an always-on Grandpa Pager.
 *
 * A phone that is plugged in 24/7 sits at 100% and cooks its battery. The
 * receiver already publishes its battery level, charging flag and temperature on
 * `grandpa/status`; this little daemon watches that topic and switches a mains
 * smart plug (Tasmota / ESPHome / Shelly / Home Assistant webhook / …) so the
 * battery hovers between CHARGE_RESUME_PCT and CHARGE_STOP_PCT instead of
 * sitting full forever. No root, no hardware mods.
 *
 *   [] plug OFF  when charging AND battery >= CHARGE_STOP_PCT   (default 70)
 *   [] plug ON   when NOT charging AND battery <= CHARGE_RESUME_PCT (default 65)
 *   [] plug OFF  as a heat guard when battery_temp_c >= CHARGE_TEMP_STOP_C (40)
 *   [] plug ON   again once it has cooled to <= CHARGE_TEMP_RESUME_C (38)
 *
 * The phone never powers off — it only loses the charger — so the pager keeps
 * working on its own battery while the plug is cut.
 *
 * Env:
 *   MQTT_HOST, MQTT_PORT, MQTT_USER, MQTT_PASS   broker (local defaults)
 *   MQTT_CA            path to a CA cert (defaults to ../certs/ca.crt if present)
 *   MQTT_INSECURE=1    skip TLS verification (throwaway setups only)
 *   PLUG_ON_URL        URL called to turn the plug ON   (required to act)
 *   PLUG_OFF_URL       URL called to turn the plug OFF  (required to act)
 *   PLUG_METHOD        GET (default) or POST
 *   CHARGE_STOP_PCT    stop charging at/above this % (default 70)
 *   CHARGE_RESUME_PCT  resume charging at/below this % (default 65)
 *   CHARGE_TEMP_STOP_C stop charging at/above this °C    (default 40)
 *   CHARGE_TEMP_RESUME_C resume once cooled to this °C   (default 38)
 *
 * Without PLUG_ON_URL / PLUG_OFF_URL it runs read-only and just logs the
 * decisions it *would* make — handy for testing the thresholds first.
 *
 *   node charge-controller.js
 */
const mqtt = require('mqtt');
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');

const HOST = process.env.MQTT_HOST || 'localhost';
const PORT = process.env.MQTT_PORT || '8883';
const USER = process.env.MQTT_USER || 'grandpa';
const PASS = process.env.MQTT_PASS || 'grandpa';
const STATUS = 'grandpa/status';

const STOP_PCT = num(process.env.CHARGE_STOP_PCT, 70);
const RESUME_PCT = num(process.env.CHARGE_RESUME_PCT, 65);
const TEMP_STOP_C = num(process.env.CHARGE_TEMP_STOP_C, 40);
const TEMP_RESUME_C = num(process.env.CHARGE_TEMP_RESUME_C, 38);

const PLUG_ON_URL = process.env.PLUG_ON_URL || '';
const PLUG_OFF_URL = process.env.PLUG_OFF_URL || '';
const PLUG_METHOD = (process.env.PLUG_METHOD || 'GET').toUpperCase();
const ACTING = Boolean(PLUG_ON_URL && PLUG_OFF_URL);

function num(v, d) { const n = parseFloat(v); return Number.isFinite(n) ? n : d; }
function log(...a) { console.log(new Date().toISOString(), ...a); }

if (RESUME_PCT >= STOP_PCT) {
  console.error(`CHARGE_RESUME_PCT (${RESUME_PCT}) must be below CHARGE_STOP_PCT (${STOP_PCT})`);
  process.exit(2);
}

/* ---- MQTT options (mirror tools/scripts/mqtt.js) ---- */
const opts = {
  username: USER,
  password: PASS,
  rejectUnauthorized: process.env.MQTT_INSECURE !== '1',
  servername: process.env.MQTT_TLS_SERVERNAME || HOST,
  clientId: 'charge-ctl-' + Math.random().toString(16).slice(2),
};
const caPath = process.env.MQTT_CA || path.join(__dirname, '..', 'certs', 'ca.crt');
if (process.env.MQTT_CA || fs.existsSync(caPath)) opts.ca = fs.readFileSync(caPath);

/* ---- plug state we believe we commanded (null until first observation) ---- */
let commanded = null;
let lastError = null;

function callPlug(on) {
  const url = on ? PLUG_ON_URL : PLUG_OFF_URL;
  return new Promise((resolve) => {
    let u;
    try { u = new URL(url); } catch (e) { lastError = 'bad URL ' + url; return resolve(false); }
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.request(u, { method: PLUG_METHOD }, (res) => {
      res.resume();
      res.on('end', () => resolve(res.statusCode >= 200 && res.statusCode < 300));
    });
    req.on('error', (e) => { lastError = e.message; resolve(false); });
    req.setTimeout(8000, () => { lastError = 'plug timeout'; req.destroy(); });
    req.end();
  });
}

async function setPlug(on, reason) {
  if (commanded === on) return;
  commanded = on; // optimistic; corrected below on failure
  if (!ACTING) { log(`[dry-run] would switch plug ${on ? 'ON' : 'OFF'} — ${reason}`); return; }
  const ok = await callPlug(on);
  if (!ok) commanded = null; // forget, so the next status retries
  log(`plug ${on ? 'ON' : 'OFF'} (${reason}) -> ${ok ? 'ok' : 'FAILED: ' + (lastError || 'http')}`);
}

function decide(battery, charging, temp) {
  const hot = typeof temp === 'number' && temp >= TEMP_STOP_C;
  const cooled = typeof temp !== 'number' || temp <= TEMP_RESUME_C;
  if ((charging && battery >= STOP_PCT) || hot) return { on: false, reason: hot ? `temp ${temp}°C` : `battery ${battery}%` };
  if (!charging && battery <= RESUME_PCT && cooled) return { on: true, reason: `battery ${battery}%` };
  return null;
}

log(`charge-controller: keep ${RESUME_PCT}–${STOP_PCT}%, temp<${TEMP_STOP_C}°C, ${ACTING ? 'plug: ' + PLUG_METHOD : 'dry-run (no PLUG_*_URL)'}`);

const client = mqtt.connect(`mqtts://${HOST}:${PORT}`, opts);
client.on('error', (e) => log('MQTT error:', e.message));
client.on('connect', () => client.subscribe(STATUS, { qos: 1 }, () => log('subscribed to ' + STATUS)));
client.on('message', async (_t, buf) => {
  let s;
  try { s = JSON.parse(buf.toString()); } catch (e) { return; }
  if (typeof s.battery !== 'number') return;
  if (commanded === null) commanded = Boolean(s.charging); // charging ⇒ plug is on
  const want = decide(s.battery, Boolean(s.charging), s.battery_temp_c);
  log(`battery ${s.battery}% charging=${s.charging}${typeof s.battery_temp_c === 'number' ? ' temp=' + s.battery_temp_c + '°C' : ''}${want ? ' ⇒ ' + (want.on ? 'ON' : 'OFF') : ' (hold)'}`);
  if (want) await setPlug(want.on, want.reason);
});
