'use strict';
/**
 * e2e MQTT client for the Grandpa Pager (host side).
 *
 *   node mqtt.js listen                 # print everything on grandpa/#
 *   node mqtt.js status                 # print the receiver status once
 *   node mqtt.js send "Text" [min] [sender]
 *   node mqtt.js clear                  # clear the retained message
 *
 * Env: MQTT_HOST (default localhost), MQTT_PORT (8883), MQTT_USER, MQTT_PASS.
 */
const mqtt = require('mqtt');
const fs = require('fs');
const path = require('path');

const HOST = process.env.MQTT_HOST || 'localhost';
const PORT = process.env.MQTT_PORT || '8883';
const USER = process.env.MQTT_USER || 'grandpa';
const PASS = process.env.MQTT_PASS || 'grandpa';

const ca = fs.readFileSync(path.join(__dirname, '..', 'certs', 'ca.crt'));
const opts = {
  username: USER,
  password: PASS,
  ca,
  rejectUnauthorized: true,
  servername: 'localhost',
  clientId: 'e2e-' + Math.random().toString(16).slice(2),
};
const TOPIC = { message: 'grandpa/message', command: 'grandpa/command', status: 'grandpa/status' };

const cmd = process.argv[2] || 'listen';
const client = mqtt.connect(`mqtts://${HOST}:${PORT}`, opts);

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

client.on('error', (e) => { console.error('MQTT error:', e.message); process.exit(1); });

client.on('connect', () => {
  if (cmd === 'send') {
    const text = process.argv[3] || 'Hello Grandpa';
    const minutes = parseFloat(process.argv[4] || '240');
    const sender = process.argv[5] || 'E2E Bot';
    const now = Date.now();
    const payload = {
      id: uuid(),
      sender,
      text,
      created_at: new Date(now).toISOString().replace(/\.\d+Z$/, 'Z'),
      expires_at: new Date(now + minutes * 60000).toISOString().replace(/\.\d+Z$/, 'Z'),
      duration_minutes: Math.round(minutes),
    };
    client.publish(TOPIC.message, JSON.stringify(payload), { qos: 1, retain: true }, () => {
      console.log('sent:', JSON.stringify(payload));
      client.end();
    });
  } else if (cmd === 'clear') {
    client.publish(TOPIC.message, '', { qos: 1, retain: true }, () => {
      console.log('cleared retained message');
      client.end();
    });
  } else if (cmd === 'status') {
    client.subscribe(TOPIC.status, { qos: 1 }, () => console.log('waiting for status…'));
    client.on('message', (_t, buf) => { console.log(buf.toString()); client.end(); });
    setTimeout(() => { console.log('(no status received)'); client.end(); }, 8000);
  } else {
    client.subscribe('grandpa/#', { qos: 1 }, () => console.log('listening on grandpa/#…'));
    client.on('message', (t, buf) => console.log(`[${t}] ${buf.toString()}`));
  }
});
