# Free managed MQTT brokers for the grandpa-pager

Research date: 2026-10-10. All facts below come from vendor primary sources
(pricing pages, product docs, legal/DPA pages, official repos) unless a line is
explicitly marked **UNVERIFIED**.

## What the pager needs

- Android receiver + 1–3 sender clients (≈2–4 concurrent connections total).
- Full-screen text messages over MQTT, auto-expiring (default 4 h).
- Sender name shown; diagnostics published back.
- FREE tier, private broker (no shared/public test broker), TLS,
  username/password auth, per-topic ACLs *desirable*,
  retained messages + Last Will & Testament, EU/Romania residency preferred.
- Low volume: a few messages/day.

## Scope / exclusions

- Public/test brokers are **excluded by requirement**: `broker.emqx.io`,
  `test.mosquitto.org`, `broker.hivemq.com`, `mqtt.eclipseprojects.io`, etc.
  They are unauthenticated or wide open and must not carry family messages.

## Quick comparison

| | Free tier | Max payload | TLS endpoint (default) | Auth | Per-topic ACL | Retained | LWT | MQTT versions | Data residency | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| **EMQX Cloud Serverless** | Free forever: 1M session-min, 1 GB traffic, 1M rule-actions / month; up to 1000 connections | 1 MB | `<deployment-host>:8883` (mqtts), `:8084` (wss) — TLS **only** | Username/password (built-in DB) | Yes — built-in authorization (Serverless ✓ default, ✗ extended) | Yes | Yes | 3.1.1 & 5.0 | **US** (docs: data stored in California) | **Top pick** |
| **flespi** | Free forever: 10 devices, 50 MQTT sessions, 20k msgs/min, 100 MB retained + 100 MB session storage | 60 MB (single retained 16 MB) | `mqtt.flespi.io:8883` (SSL), `:1883` plain; WS `:443`/`:80` | Token as username (password unused) | Yes — token ACL (module/item/topic) | Yes | Yes (will-delay) | 3.1, 3.1.1, 5.0 | **EU** (Gurtam UAB, Lithuania; hosting Zylon, Netherlands) | **Runner-up — best privacy/EU** |
| **HiveMQ Cloud** | ❌ **No permanent free managed tier.** Serverless Free retired 2026-12-31; Starter = 15-day trial then paid; free Lab = self-hosted only | 1 MB (Serverless/Starter) | `<cluster-id>.s1.eu.hivemq.cloud:8883`, WSS `:8884` | Username/password, client-cert, or JWT (one at a time) | Yes (roles/permissions, topic filters) | Yes | Yes | 3.1, 3.1.1, 5 | EU (Frankfurt) or US (Oregon) | Not viable for free managed |
| **Mosquitto (self-hosted)** | Free software (EPL-2.0); cost = host only (e.g. Oracle Always Free EU, or €3–5/mo VPS) | up to MQTT max (message_size_limit 0); max_packet_size default 2 MB | your host `:8883` TLS, `:1883` plain | `password_file` or dynamic-security plugin | Yes (`acl_file`, patterns, or dynamic-security) | Yes | Yes (will-delay, session-expiry) | 3.1, 3.1.1, 5 | **Anywhere you choose** (EU/Romania possible) | **Max privacy / control; ops burden** |
| **MyQttHub (Open)** | Free: 100 users, 50 conns, 10 MB storage, 310k msgs/mo | **4 KB** | `…:8883` (per plan table) | username/password per device | Yes — topic/content "filters" | Yes (stash/retain) | Likely (MQTT standard) | 3.1.1 (docs) | **EU** (ASPL, Spain) | Feasible; tiny payload + provider longevity risk |
| **Shiftr.io Cloud (Basic)** | Free, 100 conns, 5K msg/s — but **free instances run only 6 h/day** and auto-sleep | UNVERIFIED | instance host, TLS | username/password | UNVERIFIED | UNVERIFIED | UNVERIFIED | MQTT + HTTP | Switzerland (Sternebauer) | Unsuitable: not always-on |

---

## 1. EMQX Cloud Serverless

Source: `emqx.com/en/pricing`, `docs.emqx.com/en/cloud/latest/…`.

- **Free tier.** "$0/month … free quota every month, pay-as-you-go beyond."
  Free quota: **1,000,000 session minutes / month**, **1 GB traffic / month**,
  **1,000,000 rule-action executions / month**; **up to 1000 connections** and
  TPS up to 1000 (pricing comparison table). Set the monthly **Spend Limit = 0**
  so only the free quota is ever consumed — the console documents that
  "a spend limit of 0 means the deployment can only consume the free quota".
  No credit card needed at spend-limit 0.
- **Fit for us.** 2–4 clients connected 24/7 ≈ 4 × 1440 × 30 ≈ **173k
  session-minutes/month**, i.e. ~17% of the free quota; traffic for a few text
  messages/day is negligible. The quota is far larger than this use case.
- **Max payload.** 1 MB (Serverless row of the plan comparison).
- **TLS + endpoint.** Serverless is **TLS-only**: MQTT over TLS on **8883**
  (`mqtts`) and WebSocket over TLS on **8084** (`wss`); ports 1883/8083 are
  refused. Host is a per-deployment address shown in the console ("Address");
  CNAME to your own domain is **not** supported. (Exact DNS suffix not stated in
  docs — see *Unverified*.) Server CA download: `emqxsl-ca.crt`.
- **Auth.** Username/password stored in the built-in database (no anonymous
  access; anonymous auth is not supported). Extended auth is **not** available
  on Serverless.
- **ACL.** **Default Authorization is supported on Serverless** (extended/ext.
  DB authz is not). Rules can be scoped by **Client ID**, **Username**, or
  **All Users** with topic + action (pub/sub/pubsub) + allow/deny, with
  `${clientid}` / `${username}` placeholders. Whitelist mode is achievable by
  adding an `#` deny-all rule on the *All Users* tab. This covers per-topic ACLs.
- **Retained + LWT.** Both supported (plan comparison shows "Retained message"
  and "Last will message").
- **MQTT versions.** 3.1.1 and 5.0; QoS 0/1/2.
- **Residency / privacy.** Privacy policy states **"We store data on servers
  in California, United States"**; cross-border transfers under SCCs; entity
  EMQ Technologies Inc. (Menlo Park, US). This is the main privacy trade-off.
- **Signup friction.** Email + password + **mobile-number verification** for
  real-name authentication; email confirmation link.
- **Anything paid?** No — Serverless is free within quota; spend-limit 0
  prevents charges.
- **Uptime/SLA.** Serverless has **no uptime SLA** (SLA appears only on
  Dedicated Flex at 99.99%; pricing table shows Serverless uptime SLA "—").
- **Shutdown/ToS risks.** Marketing describes Serverless as "for developers or
  small businesses to test and develop IoT applications." Idle policy: no
  active client connections for **30 consecutive days → deployment stopped**;
  if not reactivated within 30 days → **deleted**. (Trial deployments pause
  after 5 idle days.) A 24/7 receiver avoids this. Sharing a multi-tenant
  cluster is the architectural model.

**Connection parameters**

```
host:  <deployment-address from console>      # per-deployment MQTT endpoint
port:  8883                                    # mqtts (TLS required)
tls:   true, verify server cert (optionally use emqxsl-ca.crt)
user:  <username created under Access Control > Authentication>
pass:  <password>
clientId: any unique string
```

## 2. flespi

Source: `flespi.com/pricing`, `flespi.com/mqtt-broker`,
`flespi.com/kb/how-to-create-complex-flespi-token`, `flespi.com/terms-of-use`,
`flespi.com/privacy-policy`, restrictions PDF.

- **Free tier.** €0/month. Telematics limits: 10 devices, 2 channels, 1 stream,
  storage not extendable. MQTT-broker limits (restrictions PDF): **50 active
  MQTT sessions**, 200 subscriptions/session, **20,000 MQTT messages/minute**,
  **100 MB persistent-session storage**, **100 MB retained-message storage**;
  REST/MQTT traffic 50 MB/min; 100 access tokens. Explicitly labelled
  **"For testing & development purposes. Note that limits are not extendable."**
- **Fit for us.** Comfortably within limits; 50 sessions ≫ 4 clients.
- **Max payload.** Broker accepts messages up to **60 MB**; a single retained
  message max **16 MB**; 24 topic levels; 64 topics per subscribe/unsubscribe.
- **TLS + endpoint.** Host **`mqtt.flespi.io`**. MQTT over TCP: **8883 (SSL)**
  or 1883 (non-SSL). WebSockets: 443 (SSL) or 80. TLS is available; pick 8883.
- **Auth.** The **flespi token** is the MQTT **username** (optionally the
  password); password field unused. ClientID must be unique per connection
  (duplicate IDs are disconnected round-robin). Tokens **must have a TTL/expire**
  — you cannot create a non-expiring token.
- **ACL.** Token-based ACLs: per module / item type / item id, with a topic-level
  ACL for MQTT; "what's not allowed is forbidden" (deny by default when ACL is
  enabled). IP-whitelist and origin restrictions per token. Supports topic
  selectors and `$filter/cid=…` subscription filtering.
- **Retained + LWT.** Retained messages supported (REST API can read/delete;
  wildcard bulk delete supported). LWT supported, including MQTT 5 will-delay /
  message-expiry. Broker signs each message with `token_id` user property
  (useful for "sender name" diagnostics).
- **MQTT versions.** 3.1, 3.1.1, 5.0. QoS 0 and 1 (broker config table lists
  "QoS 0, QoS 1"; QoS 2 support is **UNVERIFIED**).
- **Residency / privacy.** Strong EU story: operator **Gurtam UAB (Vilnius,
  Lithuania)**; DPA governed by Lithuanian law; hosting sub-processor **Zylon
  (Netherlands)**; documented technical/organisational measures and a 36-hour
  breach-notification window. Good fit for EU/Romania privacy preference.
- **Signup friction.** Free account at `flespi.io`, then create a token. No
  card. No real-name/mobile requirement found.
- **Anything paid?** No, within free limits.
- **Uptime/SLA.** Free = **"SLA Best Effort", No refund** (status page exists).
- **Shutdown/ToS risks.** Free tier is explicitly dev-oriented and
  non-extendable; platform "removes and deprecates obsolete functionality"
  (flespi's own notice) and "the Company has the right to change Restrictions
  at its discretion unilaterally." **Accounts inactive for 60 days are deleted.**
  A 24/7 receiver keeps the account "accessed", mitigating this. Retained
  storage is capped at 100 MB on free.

**Connection parameters**

```
host:  mqtt.flespi.io
port:  8883            # MQTT over TLS/SSL (or 1883 non-SSL)
user:  <flespi token>  # token as username
pass:  (empty / unused — token may instead go in password)
clientId: unique per client
```

## 3. HiveMQ Cloud

Source: `hivemq.com/pricing`, `docs.hivemq.com/hivemq-cloud/…` (index,
`serverless-end-of-life.html`, `quick-start-guide.html`, `authn-authz.html`).

- **Free tier — effectively none for managed hosting.**
  - **Serverless Free is retired: end-of-life 2026-12-31.** New Serverless
    clusters can no longer be created; existing clusters stop accepting MQTT
    clients after that date, then HiveMQ **permanently deletes the cluster and
    data** (retained messages, sessions, credentials, permissions).
  - **Starter** is a **15-day free trial**, then usage-based
    (**from $0.34/hour + $0.80 per million messages**) — i.e. paid.
  - The only free thing is the **self-managed** option: **Lab license**
    (25 connections, 25 msg/s, **30-day** license, renew manually) or a
    one-time **Migration license** (100 connections / 100 msg/s for 12 months,
    then drops to Lab limits). Both run **on your own infrastructure** — this is
    the same "self-hosted" path as Mosquitto, not a managed free broker.
- **Fit for us.** Historically Serverless Free was the exact fit, but it is
  being switched off during the project's lifetime.
- **Max payload.** 1 MB (Serverless/Starter service levels).
- **TLS + endpoint.** `<cluster-id>.s1.eu.hivemq.cloud` (EU/Frankfurt) or
  `<cluster-id>.s1.us.hivemq.cloud` (US/Oregon). TLS MQTT on **8883**; TLS
  WebSocket on **8884**. TLS-only.
- **Auth.** Username/password "access credentials" (default), client
  certificate, or JWT — only **one method at a time**.
- **ACL.** Roles + permissions; permissions are per topic filter with
  publish/subscribe, QoS, retained, shared-subscription flags; dynamic
  variables `${mqtt-username}` / `${mqtt-clientid}`.
- **Retained + LWT.** Both supported.
- **MQTT versions.** 3.1, 3.1.1, 5.
- **Residency / privacy.** Choose EU (Frankfurt) or US (Oregon) on Starter;
  HiveMQ GmbH is a German company (GDPR).
- **Anything paid?** Managed hosting beyond the 15-day trial is paid.
- **Uptime/SLA.** Starter advertises a **99.95% uptime SLA**; Enterprise up to
  99.99%. (Serverless Free carried no such SLA.)
- **Shutdown/ToS risks.** **This is the concrete proof that free managed MQTT
  tiers get retired** — HiveMQ is deleting all Serverless Free clusters on
  2026-12-31 with no automatic migration. Treat vendor free tiers as
  perishable.

**Connection parameters (Serverless, until EOL 2026-12-31)**

```
host:  <cluster-id>.s1.eu.hivemq.cloud
port:  8883        # mqtts (TLS only)
user:  <access credential username>
pass:  <access credential password>
wss:   8884
```

## 4. Mosquitto (self-hosted) — reference baseline

Source: `mosquitto.org/man/mosquitto-conf-5.html`, `mosquitto.org/download/`,
`eclipse-mosquitto/mosquitto` (GitHub).

- **Free tier.** The software is free/open source (Eclipse Public License;
  `mosquitto.org`). Current release **2.1.2**. Cost is only the host:
  - Fully free host: **Oracle Cloud Always Free** (Arm/AMD VM, block volume;
    "Always Free services are available for an unlimited period of time"), with
    EU regions (e.g. Frankfurt/Amsterdam/Zurich) — subject to free-tier capacity
    and account idleness rules (idle 30+ days may be reclaimed).
  - Cheap EU VPS: Hetzner/OVH/Netcup (Germany/France) or Romanian hosts,
    typically €3–5/month — not free but fully EU-resident.
- **Max payload.** `message_size_limit 0` (default) accepts all valid MQTT
  messages (MQTT max 268,435,455 bytes); `max_packet_size` defaults to
  2,000,000 bytes.
- **TLS + endpoint.** Configure a `listener 8883` with `cafile`/`certfile`/
  `keyfile`; use Let's Encrypt for certs. Plain MQTT on 1883 only inside the
  host/VPN. Endpoint = your domain.
- **Auth.** `password_file` (created with `mosquitto_passwd`) or the
  **dynamic-security** plugin for finer control. `allow_anonymous false`.
- **ACL.** `acl_file` supports `topic [read|write|readwrite|deny] <topic>` with
  `user` sections and `%u`/`%c` patterns; or the dynamic-security plugin for
  per-role topic ACLs. Fully meets "per-topic ACLs".
- **Retained + LWT.** `retain_available true` (default) with
  `retain_expiry_interval`; LWT and MQTT 5 will-delay/session-expiry supported.
- **MQTT versions.** 3.1, 3.1.1, 5 (`accept_protocol_versions 3,4,5`).
- **Residency / privacy.** Wherever you deploy — can be an EU datacentre in
  Romania/Germany/France. No third party sees message data.
- **Anything paid?** No license fee.
- **Uptime/SLA.** Whatever you engineer (no vendor SLA).
- **Shutdown/ToS risks.** None from a vendor; the risks are operational
  (patching, cert renewal, backups, DDoS, host loss).

**Connection parameters**

```
host:  <your-domain or IP>
port:  8883        # listener with TLS
user:  <entry in password_file>
pass:  <password>
tls:   true (Let's Encrypt cert), cacert optional
```

## 5. Other genuinely free options found

### MyQttHub (ASPLhosting)

Source: `myqtthub.com/en/pricing`.

- **Free "Open" plan:** 100 users/devices, **50 concurrent connections**,
  10 MB storage, **max message size 4 KB**, 300 msg/min, 10,000 msg/day,
  310,000 msg/month; TLS on **8883**; **fully private/isolated topic namespace
  with no prefix** ("dedicated MQTT broker"); topic/content "filters" for
  access control; stashing/retained. EU provider (ASPL, Spain). No technical
  support on the free plan.
- **Fit:** technically workable for short text messages, but the **4 KB payload
  cap** is tight and the provider is small/stale (support forum-driven). Treat
  as a fallback, with provider-longevity and ToS-lock risk.

### Shiftr.io Cloud

Source: `shiftr.io/cloud`.

- **Basic plan:** $0/month, 100 active connections, 5K messages/second —
  **but free instances may run only 6 hours a day and auto-sleep when not used.**
  That disqualifies it for an always-on elderly pager receiver (and for
  retained-message/4-hour expiry reliability). Paid Plus is $7/month. Hosted by
  Sternebauer (Switzerland — privacy-friendly jurisdiction). Retention/LWT/ACL
  on the free plan were **not verified**.

---

## Recommendation

### Ranking for this project

1. **EMQX Cloud Serverless — top pick.**
   The only option that is simultaneously free-forever, fully managed (no ops),
   TLS-only, username/password, with working **per-topic ACLs** (built-in
   authorization), **retained + LWT**, and MQTT 3.1.1/5. Its free quota
   (~1M session-minutes, 1 GB, 1M rule actions/month; 1000 connections) is
   ~6× this workload, no credit card is required, and a spend-limit of 0 makes
   accidental billing impossible.
   *Caveat:* multi-tenant infrastructure and message data is stored in the
   **US** per its privacy policy; there is no free-tier SLA, and a fully idle
   deployment is stopped after 30 days (irrelevant with an always-on receiver).

2. **flespi — runner-up, and the pick if EU data residency is a hard
   requirement.** Genuinely free, EU-based (Lithuania/Netherlands, GDPR DPA),
   token auth with fine-grained **per-topic ACLs**, retained + LWT, MQTT 5,
   TLS on 8883. Caveat: the free plan is explicitly "testing & development",
   non-extendable, capped at 100 MB retained storage, and deleted after 60 days
   of inactivity.

3. **Self-hosted Mosquitto on an EU VPS (e.g. Oracle Always Free in an EU
   region)** — the maximum-privacy option: EU residency, no third party, no
   vendor ToS risk, €0–5/month. Choose this if you accept the ops burden
   (TLS certs, patching, monitoring, backups).

4. MyQttHub (free but 4 KB payload, small vendor) — fallback.
   Shiftr.io free — unsuitable (6 h/day limit).
   HiveMQ Cloud — not viable for free managed hosting (Serverless Free is being
   shut down 2026-12-31; Starter is paid; only self-hosted Lab licenses are
   free).

### Exact connection parameter format (primary recommendation)

```
EMQX Cloud Serverless
  host     : <the "Address" shown on the deployment Overview page in the console>
  port     : 8883                       # MQTT over TLS (mqtts) — TLS required
  tls      : enabled; verify server certificate
             (optional CA bundle: https://assets.emqx.com/data/emqxsl-ca.crt)
  username : <client username created under Access Control > Authentication>
  password : <password for that user>
  clientId : unique per client (e.g. pager-receiver, pager-sender-alice)
  MQTT     : 3.1.1 or 5.0
  topics   : e.g. pager/messages (retained), pager/diagnostics (back-channel)
```

Runner-up (EU residency):

```
flespi
  host     : mqtt.flespi.io
  port     : 8883                       # MQTT over TLS/SSL
  username : <flespi token>             # token as username; password unused
  password : (leave empty)
  clientId : unique per client
  MQTT     : 3.1.1 or 5.0
```

## Unverified / uncertain facts

- **EMQX Serverless exact hostname suffix and EU-region availability.** The
  console shows a per-deployment "Address" and a "Region" field, but the public
  docs do not state the DNS suffix (commonly seen as `*.emqxsl.com`) nor the
  full list of Serverless regions. Whether a Serverless deployment can be
  pinned to an EU region (vs. always US) was not confirmed. The privacy policy's
  "servers in California" statement is the only residency claim found.
- **flespi QoS 2.** The broker config page lists "QoS 0, QoS 1"; the embedded
  web client offers QoS 2. QoS 2 support is unconfirmed.
- **MyQttHub endpoint host/port specifics** beyond "TLS 8883"; the per-hub
  hostname pattern and retained/LWT details were not fetched.
- **Shiftr.io free plan** details (retention, LWT, ACL, max payload) not
  published; only the 6 h/day free-instance limit was confirmed.
- **HiveMQ Starter** exact TLS port (assumed 8883) and whether the 15-day trial
  needs a card (docs say "No credit card required" for the trial).
- **Mosquitto managed offerings** (e.g. Cedalo Cloud) were not reviewed in this
  pass; the baseline here is self-hosted Mosquitto.
- **CloudMQTT** and other historical free brokers were not verified in this
  pass; HiveMQ's Serverless retirement is the concrete, verified precedent that
  free managed MQTT tiers can be withdrawn.
