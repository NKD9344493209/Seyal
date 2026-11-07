# Seyal (செயல்) — say it, it does it

An Android automation app for people who already use Claude/ChatGPT and want their phone to act.
You type or speak one sentence; the AI turns it into a plan; a fixed engine runs it in parallel lanes;
you get one line back.

**Understand · Execute · Control** — the AI only proposes. The engine decides what runs, asks you before anything risky, and never touches passwords, OTPs or payments.

## Phase 1 (this build)

| You say | What happens |
|---|---|
| "Message Ram on WhatsApp that I'm coming home, then call him" | finds Ram → opens WhatsApp with the text (auto-sends for contacts you allow) → calls after you approve |
| "Make a meeting link for Ram tomorrow 4 PM and email it to him" | meeting link → calendar event → email, all in order |
| "Every morning at 8, give me AI news" | saves a **recipe**: fetch → summarise → one-line notification, daily |
| "Has Ramayana released yet?" / "what's new today?" | searches the web + Wikipedia, AI decides, one line |
| "On Wednesday, set an alarm for Friday 7 AM" | a one-time recipe fires Wednesday and sets Seyal's own alarm for Friday |
| "Remind me in 20 minutes to drink water" | exact reminder |
| any question | plain answer, no tools |

Tools: contacts · WhatsApp · email · call · meeting link · calendar · alarm · reminder · news · web search · read page · movie info · AI · notify · open app/link.

## Install

1. Download the latest `Seyal-*.apk` from **Releases** and install it (allow "unknown apps" once).
2. Open **Control → AI**, choose Gemini (free key at aistudio.google.com) or Claude / any OpenAI-style server, paste the key, tap **Test key**.
3. Allow contacts and notifications when asked. Optional: calendar, calls, mic, exact alarms, battery unrestricted (needed on Xiaomi/Vivo/Oppo).
4. Say something.

## How it's built

- Kotlin + Jetpack Compose, minSdk 26, no backend. Your key stays on the phone (Android Keystore).
- `ai/Planner.kt` — prompt + JSON plan (`mode`: now / schedule / answer / clarify; steps with `depends_on`).
- `engine/Engine.kt` — lanes, approvals, choices, retries (never for side-effect steps), cancel, emergency stop, one-line results.
- `engine/Tools.kt` — every action the AI may pick, with a default level (Auto / Ask / Off) you can change.
- `engine/Scheduler.kt` — exact alarms + WorkManager for recipes; reschedules after reboot.
- `device/` — own alarm screen, WhatsApp auto-send (accessibility, only for contacts you allow), Gmail notification alerts, voice input.
- `ui/` — Claude-style chat home, Recipes (card / code view), Activity, Control.

Build: `./gradlew assembleDebug` (or push to `main`: GitHub Actions builds and publishes the APK).

## Known limits (Phase 1)

- WhatsApp has no API: Seyal opens the chat with the text; you tap Send, unless you allow auto-send for that contact.
- Meeting links are Jitsi (no account). Google Meet needs Google sign-in → Phase 2.
- Email opens Gmail pre-filled. Reading mail = Gmail notifications only. Full Gmail API → Phase 2.
- Booking, shopping, screen taps for apps without an API → Phase 3–4.
