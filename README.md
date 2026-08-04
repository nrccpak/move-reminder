# Move Reminder

Sedentary reminder for Samsung / Android. Movement-based timer with a Meeting
mode that auto-expires. No server, no account, no internet permission.

## How it works

- Every 5 minutes an alarm fires and takes **one** reading from the hardware
  step counter (`TYPE_STEP_COUNTER`), then unregisters. No continuous sensing,
  no wake lock.
- `delta = currentSteps - anchorSteps`
  - `delta >= 25` -> you moved. The sitting bout is closed and logged, and the
    anchor resets.
  - otherwise, if you have been sitting past the threshold (default 25 min),
    inside the active window (default 07:00-17:00), in OFFICE mode -> one
    notification. Only one per bout, no escalation.
- Meeting mode suppresses the notification but keeps recording. On expiry it
  reverts to OFFICE and re-anchors to *now*, so you don't get an instant nudge
  for the meeting you just sat through.
- The step counter resets on reboot. The engine detects `steps < anchorSteps`
  and re-anchors instead of computing a negative delta.

Battery cost is effectively the sensor hub's own draw, well under 1% per day.

## Known limitations (accepted by design)

- If the phone stays on your desk while you walk away, no steps are counted and
  you may get a false nudge. Use the **I stood up** button or notification
  action to reset manually.
- In Doze the 5-minute alarm can slip to roughly 9 minutes. A 25-minute
  threshold may fire at 28-30 minutes. Acceptable.
- Short trips of under 25 steps do not reset the timer.

## Files

```
app/src/main/java/com/fiaz/movereminder/
  MainActivity.kt      UI, settings, history, log viewer
  SedentaryService.kt  foreground service (survival only)
  Engine.kt            all decision logic
  StepReader.kt        one-shot step counter read
  Scheduler.kt         5-minute alarm
  Notifier.kt          channels + notifications
  TickReceiver.kt      alarm handler
  ActionReceiver.kt    notification buttons
  BootReceiver.kt      restart after reboot
  Prefs.kt             live state
  BoutLog.kt           bouts.csv + debug.log
```

No third-party dependencies. Framework APIs only.

## Build (no Android Studio needed)

Push to GitHub. The Actions workflow installs the JDK, Android SDK and Gradle,
builds a debug APK, and attaches it to a **Release** so you can download the
`.apk` directly on your phone (Actions *artifacts* download as .zip, which is
awkward on mobile - that is why we use a release).

### From your Windows laptop

If you have Git installed:

```
cd path\to\move-reminder
git init
git add .
git commit -m "initial"
git branch -M main
git remote add origin https://github.com/YOURNAME/move-reminder.git
git push -u origin main
```

If you do not have Git: create the repo on github.com, click
**Add file > Upload files**, and drag the whole extracted folder contents into
the browser. Commit.

Either way, go to the **Actions** tab and watch the run (about 4 minutes), then
open **Releases** and download the APK.

## Install on the phone

1. Open the release page on the phone, tap the `.apk`
2. Allow "Install unknown apps" for your browser when prompted
3. Play Protect will warn about an unknown developer - tap **Install anyway**

## Samsung setup (do not skip)

One UI will kill the service within a day or two otherwise:

- Settings > Apps > Move Reminder > Battery > **Unrestricted**
- Settings > Battery > Background usage limits > **Never sleeping apps** > add it
- Grant **Physical activity** and **Notifications** permissions on first launch

The app has buttons for the first two under "Battery setup".

## Debugging without a PC

The History section shows the last 15 bouts and the last 25 engine decisions,
read from `debug.log`. If something misbehaves, that log tells you what the
engine saw.
