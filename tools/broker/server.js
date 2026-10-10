'use strict';
/**
 * Local broker for testing the Grandpa Pager.
 *
 *  - TLS MQTT on 8883 (Aedes over tls)
 *  - WSS MQTT on 8084 (Aedes over secure WebSocket) — mirrors Flespi's wss://
 *
 * NOT for production: production uses Flespi (see docs/decisions.md).
 *   MQTT_USER=grandpa MQTT_PASS=grandpa node server.js
 */
const fs = require('fs');
const path = require('path');
const tls = require('tls');
const aedesFactory = require('aedes-server-factory');
const aedes = require('aedes')();

const PORT = parseInt(process.env.MQTT_PORT || '8883', 10);
const WSS_PORT = parseInt(process.env.MQTT_WSS_PORT || '8084', 10);
const USER = process.env.MQTT_USER || 'grandpa';
const PASS = process.env.MQTT_PASS || 'grandpa';

// Accept the username; password optional (mirrors token-as-username, like Flespi).
aedes.authenticate = (client, username, password, done) => {
  const u = username ? username.toString() : '';
  const p = password ? password.toString() : '';
  if (u === USER && (p === '' || p === PASS)) return done(null, true);
  const err = new Error('auth failed');
  err.returnCode = 4;
  console.log('[auth] rejected', client.id, u);
  done(err, false);
};

aedes.on('client', (c) => console.log('[client+]', c.id));
aedes.on('clientDisconnect', (c) => console.log('[client-]', c.id));
aedes.on('publish', (packet, client) => {
  if (client) {
    const p = packet.payload ? packet.payload.toString() : '';
    console.log('[pub]', packet.topic, packet.retain ? '(retained)' : '', p.slice(0, 200));
  }
});

const key = fs.readFileSync(path.join(__dirname, '..', 'certs', 'server.key'));
const cert = fs.readFileSync(path.join(__dirname, '..', 'certs', 'server.crt'));

const tlsServer = tls.createServer({ key, cert }, aedes.handle);
tlsServer.listen(PORT, () => console.log(`TLS MQTT on ${PORT} (user=${USER})`));

const wssServer = aedesFactory.createServer(aedes, { ws: true, https: { key, cert } });
wssServer.listen(WSS_PORT, () => console.log(`WSS MQTT on ${WSS_PORT}`));
