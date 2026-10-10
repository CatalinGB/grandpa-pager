# 08 — Sender app

Type: task
Status: resolved
Blocked by: 01, 03, 04

## Question

Build the sender: compose (text, sender name, duration), publish retained,
clear, and show the receiver's status/offline state.

## Answer

Implemented in `SenderActivity` + shared `PagerService.publishMessage`. Admin
role can clear; family role has a fixed sender name and no clear button. Verified
on the phone: it published a retained `grandpa/message` with
`sender:"PhoneSender"` and connected/subscribed to `grandpa/status`.
(Note: automated `adb shell input text` triggers Android autocorrect, so the test
text was garbled — an input artifact, not an app bug.)
