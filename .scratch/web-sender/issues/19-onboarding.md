# 19 — First-run onboarding + settings box

Type: task
Status: resolved
Blocked by: 05

## Question

The page should first ask for the name and token, save them, then show only the
message box + time picker, with a settings box at the bottom.

## Answer

Implemented in `index.html` / `sender.js`:
- **First run** shows `setupView`: **Name + Token** (+ broker), Save.
- Afterwards it shows `mainView`: message box + duration stepper (B) + Send/Clear
  + device status, with a **collapsible "Setări" box at the bottom** to edit
  name/token/broker, reconnect, or forget the token.
- Credentials persist in `localStorage`; if present on load it goes straight to
  `mainView` and connects.
