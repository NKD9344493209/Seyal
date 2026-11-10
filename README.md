# Seyal (செயல்)

**An agent that uses your Android phone for you.** Say what you want in Tamil, Tanglish or English. Seyal works it out, uses the phone and any app on it, and tells you the result in one line.

```
"Message Ram that I'm coming home, then call him"
"Open Amazon, find black shoes under 500 and show me the best one"   → drives the app itself
"What did I miss?"  →  "Reply to Amma that I'll be there by 8"       → answers from the notification
"Every morning at 8, give me AI news in 5 points"                    → a skill that runs by itself
"When Amma messages on WhatsApp, reply that I'm in class"            → a skill triggered by a notification
"Book 2 tickets for Ramayana at Broadway, 6:30 show"                 → everything up to payment, then hands over
"How long was I on YouTube today?"   "Any important mail from college?"
```

## How it works

Seyal is an **agent loop** (the idea behind Claude Code, on a phone):

1. Your request goes to the AI (your own Gemini / Claude / OpenAI-compatible key) with the tools Seyal has.
2. The AI calls tools. Independent calls run **at the same time**; big jobs split into **sub-agents**.
3. Each result goes back to the AI, which decides the next step, until the job is done.
4. You watch every step live, approve the sensitive ones, answer questions inline, and can add to a task while it runs.

**Screen control (Full edition).** Seyal reads the screen as a short numbered list and taps, types, scrolls and swipes like a person. Every action returns the new screen, so it moves quickly. When a screen has nothing readable (maps, seat layouts) it takes a screenshot and looks. A small pill floats over the app it is using: what it is doing, and a Stop button.

**Direct tools (40+).** Contacts, calls, SMS, WhatsApp, email through Gmail (read, search, send), calendar, alarms, reminders, timers, notifications (read, reply in place, open, clear), apps and deep links, settings (brightness, volume, DND, torch…), screen time, location, weather, news, web search that reads the pages, Wikipedia, clipboard, speech.

**Memory and skills.** Seyal keeps lasting facts ("dad = Appa Jio", your theatre). A **skill** is a saved automation: recorded steps that replay without the AI, or a goal an agent works out each time. Skills start by hand, at a time, when an app notifies you, when the charger is plugged in, at boot, or when an app opens. "Save this as a skill" turns what it just did into one.

**Start it from anywhere.** As the phone's assistant (long-press power/home), a Quick Settings tile, Share → Seyal (text or a picture), select text → Ask Seyal, or the app.

**You stay in charge.** Every tool has a level: Auto, Ask (Allow / Always / No), Off. Seyal never types passwords, PINs or OTPs, never presses pay / place-order buttons, and never opens payment links: it brings you to that screen and stops. "Pause all" and "Stop" freeze everything. Nothing goes to a Seyal server; there isn't one.

## Editions

| | Lite | Full |
|---|---|---|
| Installs from | phone browser | USB (`tools/install-full.cmd`) |
| Agent, direct tools, skills by time/charging/boot | ✓ | ✓ |
| Screen control: operate any app, auto-send WhatsApp | – | ✓ |
| Notifications: read, reply, "when X messages…" skills | – | ✓ |
| Send SMS without opening Messages | – | ✓ |

Play Protect blocks browser-installed apps that ask for Accessibility or Notification access, which is why Full goes over USB. One-time phone setup: *About phone → tap Build number 7×*, then *Developer options → USB debugging*. Then double-click `tools/install-full.cmd` (Windows) or run `tools/install-full.sh`.

## Build and test

GitHub Actions builds both editions on every push, runs the unit tests, publishes a release `build-N`, then installs the Full edition on an emulator with screen control on and runs a 20-step end-to-end test with real AI calls (repository secret `GEMINI_API_KEY`): direct tools, follow-ups, notifications, and driving Calculator, Settings, Chrome, Clock and Contacts through the screen. The report, screenshots and transcripts are attached to the release. If the build fails, the errors are published as `failed-N/build-errors.txt`.

Locally: Android Studio, or `./gradlew assembleDebug`.

## Code map

```
app/src/main/java/com/seyal/app/
  agent/   Agent (loop, permissions, sub-agents, stuck detection), Toolbox, Prompts, Memory, Skills, Scheduler
  ai/      Llm: native tool calling for Gemini, Anthropic and OpenAI-compatible APIs
  tools/   PhoneTools, MailTools, WebTools, NotificationTools, ScreenTools, AgentTools
  device/  ScreenDriver + accessibility service, notification listener, foreground service, mail, speech, tile
  data/    Models (Session, Msg, Part, Skill, Trigger), Store (JSON files), Settings
  ui/      Chat, Activity, Skills, Control (Compose)
```
