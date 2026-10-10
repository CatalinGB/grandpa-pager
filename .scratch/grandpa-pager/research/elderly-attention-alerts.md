# Drawing Attention to a New Message for an Elderly, Low‑Tech User — Research

**Date:** Oct 2026
**Scope:** A full‑screen, non‑dismissable "pager" on an elderly, non‑technical person's
Xiaomi Mi A1 (Android 9 / API 28): a short family message appears full‑screen in huge
text with the sender's name. He is used to a feature phone with physical buttons, has very
low tech literacy, and may **glance** at the screen rather than watch it. Two design
questions: (1) how to draw attention to a newly arrived message, and for how long; and
(2) how to show a gentle "connection problem" line on the idle clock screen in language he
will understand.

All claims are traced to primary sources (W3C specs/notes, Android developer docs, WHO fact
sheets, peer‑reviewed studies via Europe PMC). Uncertainties are collected at the end.

---

## Recommendation (short)

- **Attention treatment:** a **congruent, tri‑modal "arrival" burst** — one gentle **chime**
  + one clear **double‑pulse vibration** + the message card doing **one slow fade/scale‑in**
  with a soft **edge glow** — fired once when the message arrives. Run the active cue for
  **≈ 5–8 seconds** (a few slow cycles, well under 3 Hz), then **settle to a static,
  high‑contrast card** (message + sender name). Optionally **re‑cue once at ~60 s**; never
  loop, and never re‑alert the same message after the reminder window.
- **No fast flashing, no endless blinking, no moving/marquee elements, no custom icons.**
  Use plain Romanian words in huge text; make the screen look **non‑interactive**.
- **Connection line:** model it on the feature‑phone "semnal" mental model, calm and literal:
  **RO:** `Nu am semnal. Un mesaj nou poate să întârzie.` /
  **EN:** `No signal. A new message may be late.`

---

## 1. Attention cue types — evidence comparison

WCAG distinguishes **flashing** (a seizure risk, tied to frequency/area) from **blinking**
(a distraction issue that is acceptable only briefly or if it can be stopped)
([WCAG 2.3.1](https://www.w3.org/WAI/WCAG21/Understanding/three-flashes-or-below-threshold.html);
[WCAG 2.2.2](https://www.w3.org/WAI/WCAG21/Understanding/pause-stop-hide.html)).

- **WCAG 2.3.1 (Three Flashes or Below Threshold, Level A):** content must not flash more
  than **three times in any one second**, or must stay under the general/red‑flash
  thresholds. The Understanding doc states flashing "cannot be allowed even for a second
  or it could cause a seizure," people are **more sensitive to red flashing**, and the test
  area is a 341 × 256 CSS‑px block (~10° of central vision). Any content that fails can
  interfere with the whole page, so it must not be used even as a decorative help.
- **WCAG 2.2.2 (Pause, Stop, Hide, Level A):** auto‑starting moving/blinking/scrolling
  content that **lasts more than five seconds** and runs in parallel with other content
  must offer a pause/stop/hide mechanism. "Five seconds was chosen because it is long
  enough to get a user's attention, but not so long that a user cannot wait out the
  distraction." The doc explicitly warns that blinking content is a barrier for people with
  **low literacy, reading and intellectual disabilities, and attention deficits**.
  Techniques **G11** (blink < 5 s) and failure **F112** (blinking > 5 s without a stop
  control) make this concrete.
- **W3C COGA ("Making Content Usable for People with Cognitive and Learning Disabilities",
  W3C Note 2021)** adds design patterns for this audience: use **familiar** icons/symbols
  only; "I cannot decipher a lot of words or **unfamiliar icons**"; learning new icons
  "takes a long time"; **Limit Interruptions**; **let users control when content moves or
  changes**; use clear, literal language; **keep text succinct**
  ([COGA](https://www.w3.org/TR/coga-usable/)).
- **Android accessibility principles:** "To assist users with color vision deficiencies,
  **use cues other than color** … shapes or sizes, … text or visual patterns, or adding
  **audio‑ or touch‑based (haptic) feedback**."
  ([principles](https://developer.android.com/guide/topics/ui/accessibility/principles)).

Comparison for this audience:

| Cue | Standards status | Evidence / behaviour for older, low‑literacy users | Main risk | Verdict |
|---|---|---|---|---|
| **Slow pulse / border glow** (luminance fade, ≤ ~0.5–1 Hz) | Below flash threshold; not the "blinking" that WCAG 2.2.2 targets if brief and settled | Low distractibility; uses **brightness**, not colour; readable at a glance | If looped indefinitely it becomes wallpaper → ignored | **Recommended** (as a short burst, then static) |
| **Fast flashing / blinking** | 2.3.1 bans > 3 flashes/s; 2.2.2 bans blinking > 5 s without stop | Reduces usability for low‑literacy / attention‑impaired users per WCAG itself | Photosensitive seizures, migraine, distraction, fast habituation | **Avoid** (only a brief, slow cue is tolerable) |
| **Colour / brightness change** | Safe; Android requires cues **other than colour alone** | Age‑related lens yellowing + colour‑blindness reduce colour discrimination; **luminance** contrast is the reliable channel | Colour‑only signal fails for many | **Recommended as a complement**, never the sole cue |
| **Single scale‑in / fade‑in animation** | One‑off motion is fine; 2.2.2 applies to auto‑starting motion > 5 s | A single entrance animation reads as "something changed" without ongoing distraction | Looping motion can bother motion‑sensitive users | **Recommended** (once, ~0.3–0.6 s) |
| **Moving elements** (bounce, marquee) | Worst case for 2.2.2; COGA "content does not move unexpectedly" | Hard to track for low vision; strongly distracting; vestibular sensitivity | High distraction; disorientation | **Avoid** |
| **Static "NEW" badge** | Safe (no motion) | Very low salience from across a room; a word the user may not know; "NEW" is a **web‑ad convention** (ad/banner blindness) and COGA warns about unfamiliar text/symbols | Missed entirely at a glance | **Weak on its own**; at most a small tag in Romanian alongside the message |

**Habituation (the outcome‑driving factor).** Attention capture from visual onsets
**decays with repetition**: Dissegna et al. found peripheral visual onsets lose their
attention‑grabbing power (habituation) across repeated exposures, and that capture is
largely abolished after repeated presentation
([Atten Percept Psychophys 2025, PMID 41381956](https://doi.org/10.3758/s13414-025-03185-5)).
The clinical "alarm fatigue" literature documents the same effect for repeated alarms and
is serious enough to be the subject of systematic reviews
([PMID 42687700](https://doi.org/10.1111/nicc.70666);
[PMID 42736878](https://doi.org/10.3390/healthcare14172720)). Android's design response is
that users simply turn noisy notification channels off — "Users can change these settings
and decide which notification channels … can be intrusive or visible"
([channels](https://developer.android.com/develop/ui/views/notifications/channels)).
**Conclusion:** a cue that repeats forever is a cue that gets ignored (or disabled).

---

## 2. Multi‑modal cues (sound, vibration, visual) vs visual‑only

**Neither single channel is reliable for this age group.**
- WHO: among people **older than 60, over 25%** have disabling hearing loss; by 2050 ~2.5 B
  people are projected to have some hearing loss
  ([WHO hearing, Mar 2026](https://www.who.int/news-room/fact-sheets/detail/deafness-and-hearing-loss)).
- WHO: **most people with vision impairment/blindness are over 50**, and presbyopia affects
  ~826 M people
  ([WHO vision, Feb 2026](https://www.who.int/news-room/fact-sheets/detail/blindness-and-visual-impairment)).
- Age‑related hearing loss (presbycusis) is typically **high‑frequency** first, so a shrill
  beep is a poor choice; a **lower/mid‑frequency, warm chime** is more audible.

**Redundant multi‑sensory cues help and are largely preserved in ageing.**
- Audio‑visual stimulation **facilitated change detection** (especially in depth), and
  "the use of multisensory stimuli … appears to be largely preserved in aging"; older
  adults maintained performance via compensatory attentional mechanisms
  ([Stodt et al., Front Aging Neurosci 2026, PMID 42643263](https://doi.org/10.3389/fnagi.2026.1860627)).
- Informative **precues improved performance in both age groups**, but older adults showed
  reduced preparatory engagement and were faster‑but‑less‑accurate after spatial cues —
  i.e. advance warning helps, but ageing changes how preparation is used
  ([Huang et al., Psychophysiology 2026, PMID 42581024](https://doi.org/10.1111/psyp.70377)).
- **Vibrotactile** feedback was feasible/usable for **older adults with hearing loss**
  (a direct substitute channel when audio is impaired)
  ([Foster et al., Am J Audiol 2025, PMID 40532073](https://doi.org/10.1044/2025_aja-25-00006)).
- Android's own design guidance is to **co‑design visual, audio and haptic effects** so they
  are "harmonious or congruent," and to **correlate event importance and frequency with
  strength**; for an event "intended to grab the user's attention, such as an incoming
  notification or call," a buzzy fallback is acceptable
  ([haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)).
  The same page warns "**less is more** — too much vibration can be annoying and even
  numbing … which may lead the user to quickly turn off all haptics."

**Guidance on volume / duration / combination**
- No normative dB exists in these sources; use the device's own notification/alarm volume at
  a **moderate** level (not maximum), avoid a harsh alarm tone, chime length ~**1–2 s**.
- Vibration: a **clear, repeatable, distinct pattern** (e.g., a short double‑pulse) rather
  than one long buzzy buzz; make it **consistent** so it acquires meaning (Android haptics
  guidance). Android's predefined `VibrationEffect`/`HapticFeedbackConstants` are preferred
  for cross‑device consistency.
- **Combine all three, time‑locked and congruent**: sound + vibration + visual onset
  together, with the *same* meaning every time. This covers hearing loss (vibration +
  visual), vision loss (sound + vibration), and a muted phone (visual + vibration).

---

## 3. Duration & repetition

- **Active cue should be short and bounded.** WCAG's 5‑second figure is "long enough to get
  attention, but not so long you must wait it out," and blinking/moving content > 5 s is
  required to be stoppable — which a no‑interaction kiosk cannot offer. So confine the
  active cue to **roughly 5–8 seconds** (a few slow cycles) and then **stop**.
- **Then settle to a steady state**: a static, high‑contrast message card. The message is
  itself the persistent indicator, so no looping "NEW" animation is needed; this also
  satisfies COGA's "limit interruptions" and "control when content moves" patterns.
- **Repetition should be sparse and bounded.** Because capture **habituates** with
  repetition (Dissegna 2025) and repeated alerts cause **alarm fatigue** and channel
  disabling, do **not** loop. Recommended compromise given the "glance, don't watch"
  behaviour: fire the full cue **once on arrival**, optionally **one** spaced reminder
  (e.g. **~60 s** later), then **stop**. Never re‑alert the **same** message after that
  window; a genuinely new message gets its own single burst.
- **Keep the cue distinct from "wallpaper":** slow (≤ ~0.5–1 Hz), gentle, and finite; and
  never faster than 3 flashes/second in any case.

---

## 4. Accessibility for very low tech literacy

- **Prefer words over icons; avoid custom iconography.** COGA: use only **familiar**
  symbols; users cannot decipher unfamiliar icons; learning new icons "takes a long time";
  each symbol should carry a **single** meaning. The COGA persona "Gopal" is literally about
  "Coping with Icons that are not Recognizable." For this user, the sender's **name in plain
  text** (in Romanian) plus the **message text** in huge type is better than any envelope or
  bell glyph. If a glyph is used at all, pair it with a familiar word.
- **Avoid tappable‑looking affordances.** Because interaction is prohibited in the kiosk,
  do not show buttons, underlined/blue "links," ripples, press states, hover states, or
  small controls. (Note the deliberate inversion of the usual Android **48 dp** minimum
  touch‑target advice, [Android apps](https://developer.android.com/guide/topics/ui/accessibility/apps):
  here *nothing* should look tappable, so there is nothing to mis‑tap.) COGA "Clearly
  Identify Controls" implies making it visually obvious there is **nothing to do**.
- **Contrast ratios.** WCAG **1.4.3 (AA):** 4.5:1 normal text, 3:1 large. WCAG **1.4.6
  (AAA):** **7:1** normal, **4.5:1** large. The AAA rationale is directly about this
  audience: 20/40 (~"typical visual acuity of elders at roughly age 80") needs ~4.5:1, and
  20/80 needs **7:1** ([1.4.6](https://www.w3.org/WAI/WCAG21/Understanding/contrast-enhanced.html)).
  **Use ≥ 7:1, aiming for 12–21:1** (e.g. near‑white on black, or the reverse). Avoid red‑on‑
  black and colour‑only cues.
- **"The user won't know to tap."** The safest design removes the need to act: the message
  simply **is** the screen, and it clears by **remote command or timer**. If any dismissal
  must ever exist, prefer a **physical hardware button** (his existing mental model) over a
  touch gesture, and make it large and labelled — but per the kiosk requirement, the best
  choice is **no on‑screen affordance at all**.

---

## 5. Concrete recommendation for THIS pager

**Ranked set**

1. **(Best) Congruent tri‑modal arrival burst.** On message arrival, simultaneously:
   - play one **gentle mid/low chime** (~1 s, moderate volume),
   - run one **clear double‑pulse vibration**,
   - fade/scale the message card in **once** with a soft **edge glow** (brightness pulse,
     ≤ ~1 Hz).
   Then settle to a **static** message: huge text + sender name, **≥ 7:1 contrast**,
   on a dark background, with **no buttons**. Run the active cue **≈ 5–8 s**; optionally
   **one** reminder at ~60 s; then no more alerts for that message.
2. **(Fallback if audio may be muted / DND) Vibration + visual glow** only.
3. **(Last resort) Slow visual luminance pulse only** — ≤ ~0.5 Hz, ≤ 5 s, then static.

**Recommended duration:** active cue **≈ 5–8 seconds**, few slow cycles, **not looped**;
settle to steady state; at most **one** spaced re‑cue (~60 s); never re‑alert the same
message; never exceed **3 flashes/second** (stay near 0.5–1 Hz).

**Connection‑problem line (idle clock screen).** Model it on the feature‑phone **"semnal"**
(signal) concept — not "connection/offline/MQTT/server". Keep it **calm** (muted grey/amber,
not red, no flashing), short, literal, and useful to a passing family member without
alarming the user.

- **Idle, healthy:** RO `Totul este în regulă` · EN `Everything is OK`
- **Connection problem (preferred):**
  - **RO:** `Nu am semnal. Un mesaj nou poate să întârzie.`
  - **EN:** `No signal. A new message may be late.`
- **Gentler, sender‑oriented variant:**
  - **RO:** `Momentan nu am semnal. Dacă trimite cineva un mesaj, poate întârzia.`
  - **EN:** `No signal right now. If someone sends a message, it may be late.`

Avoid: "conexiune", "internet", "offline", "server", "MQTT", "eroare". Do **not** use a
red warning triangle or any unfamiliar icon; plain text is safest (COGA).

---

## Uncertainties / caveats

1. **No spec fixes an exact number of repetitions/seconds for this population.** The
   "5–8 s + one reminder" figure is a synthesis of WCAG's 5‑second attention window, the
   habituation evidence, and alarm‑fatigue research — **field‑test with him**.
2. **Channel availability is the weakest link.** A kiosk full‑screen app may find the
   media/notification stream **muted or in Do Not Disturb**; haptics can be disabled in
   settings; the Mi A1's speaker and vibration motor are modest; vibration is attenuated if
   the phone sits on a soft surface. Verify sound + vibration actually fire on the target
   device/stream.
3. **Over‑ vs under‑alerting trade‑off.** Too strong/repeated → habituation and channel
   disabling (Android/COGA); too subtle → the glance is missed. The bounded, spaced,
   distinct cue is the compromise, not a validated optimum.
4. **WCAG 2.2.2's pause/stop requirement is unmeetable in a no‑interaction kiosk**; the
   mitigation is to keep animated cues very short and then static.
5. **Colour alone is insufficient** (Android + age‑related lens yellowing); always pair
   colour changes with luminance/text changes.
6. **Apple HIG** pages are client‑rendered and could not be retrieved as primary text in
   this environment; the Apple HIG accessibility guidance (e.g. **Reduce Motion**) is
   consistent with the WCAG/Android conclusions above but was not quotable here
   ([Apple HIG — Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)).

---

## Sources

**Standards & platform guidance**
- W3C, *Understanding SC 2.3.1: Three Flashes or Below Threshold* —
  https://www.w3.org/WAI/WCAG21/Understanding/three-flashes-or-below-threshold.html
- W3C, *Understanding SC 2.2.2: Pause, Stop, Hide* —
  https://www.w3.org/WAI/WCAG21/Understanding/pause-stop-hide.html
- W3C, *Understanding SC 1.4.6: Contrast (Enhanced)* —
  https://www.w3.org/WAI/WCAG21/Understanding/contrast-enhanced.html
- W3C, *Making Content Usable for People with Cognitive and Learning Disabilities* (COGA,
  W3C Note 2021) — https://www.w3.org/TR/coga-usable/
- Android, *Principles for improving app accessibility* —
  https://developer.android.com/guide/topics/ui/accessibility/principles
- Android, *Make apps more accessible* (contrast ratios, 48 dp touch targets) —
  https://developer.android.com/guide/topics/ui/accessibility/apps
- Android, *Haptics design principles* —
  https://developer.android.com/develop/ui/views/haptics/haptics-principles
- Android, *Create and manage notification channels* —
  https://developer.android.com/develop/ui/views/notifications/channels
- Android, *Time-sensitive notifications* —
  https://developer.android.com/develop/ui/views/notifications/time-sensitive
- Apple, *Human Interface Guidelines — Accessibility* —
  https://developer.apple.com/design/human-interface-guidelines/accessibility

**World Health Organization (prevalence)**
- WHO, *Deafness and hearing loss* fact sheet, 3 Mar 2026 —
  https://www.who.int/news-room/fact-sheets/detail/deafness-and-hearing-loss
- WHO, *Blindness and vision impairment* fact sheet, 10 Feb 2026 —
  https://www.who.int/news-room/fact-sheets/detail/blindness-and-visual-impairment

**Peer‑reviewed studies (via Europe PMC)**
- Dissegna A. et al. *The dual impact of irrelevant visual onsets: Habituation of capture
  unlocks onset facilitation.* Atten Percept Psychophys, 2025. PMID 41381956 —
  https://doi.org/10.3758/s13414-025-03185-5
- Stodt B. et al. *Change detection in audio‑visual space: neural evidence for age effects.*
  Front Aging Neurosci, 2026. PMID 42643263 —
  https://doi.org/10.3389/fnagi.2026.1860627
- Huang P‑C. et al. *Age‑Related Differences in Cue‑Guided Preparation and Target Processing
  in Crossmodal Response Precueing.* Psychophysiology, 2026. PMID 42581024 —
  https://doi.org/10.1111/psyp.70377
- Foster J.I. et al. *Perspectives of Older Adults With Hearing Loss on Vibrotactile
  Neurofeedback Balance Training.* Am J Audiol, 2025. PMID 40532073 —
  https://doi.org/10.1044/2025_aja-25-00006
- Zhu L. et al. *Severity and Associated Factors of Alarm Fatigue Among Intensive Care Unit
  Nurses: A Systematic Review and Meta‑Analysis.* Nurs Crit Care, 2026. PMID 42687700 —
  https://doi.org/10.1111/nicc.70666
- Ferreira V. et al. *Improving the Quality of Nursing Care Through Clinical Alarm
  Management in Intensive Care Units: An Integrative Review.* Healthcare (Basel), 2026.
  PMID 42736878 — https://doi.org/10.3390/healthcare14172720
