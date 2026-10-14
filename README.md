# Jay's Room (Android app)

A tiny WebView app that works like the WLED app: it scans your Wi‑Fi network for
the ESP32 (no typing an IP address), shows it as "Jay's Room", and tapping it
opens the same dashboard page your browser shows today.

## 1. Flash the updated firmware first

This app finds the ESP32 using **mDNS** (the same tech Bonjour/WLED use). Your
current `FINAL36a.ino` doesn't advertise itself over mDNS yet, so the app would
never find it. Use the updated `FINAL36a.ino` provided alongside this project —
it adds just this, nothing else changes:

```cpp
bool mdnsStarted = false;

void startMDNSIfNeeded() {
  if (!mdnsStarted && WiFi.status() == WL_CONNECTED) {
    if (MDNS.begin("jaysroom")) {               // reachable at jaysroom.local too
      MDNS.addService("jaysroom", "tcp", 80);   // custom service type the app looks for
      MDNS.addServiceTxt("jaysroom", "tcp", "name", "Jay's Room");
      mdnsStarted = true;
      Serial.println("mDNS responder started (jaysroom.local)");
    }
  }
}
```
...called once per loop() iteration (cheap - it's just a flag check after the
first success). Flash it, then check the Serial Monitor for
`mDNS responder started` after Wi‑Fi connects.

## 2. Build the app

### Option A — no install at all: let GitHub build it for you

This project includes `.github/workflows/build-apk.yml`, which compiles the
APK on GitHub's own servers (they have the Android SDK and full internet
access, unlike this chat) and hands you back a downloadable file. No Android
Studio, no local setup:

1. Create a new repo on [github.com](https://github.com) (free account,
   public or private both work) and push/upload this `JaysRoomApp` folder to
   it.
2. Open the repo's **Actions** tab — a "Build APK" run starts automatically.
3. When it finishes (~2 minutes, green checkmark), open that run and scroll
   to **Artifacts** → download `jaysroom-debug-apk` — it's a zip containing
   `app-debug.apk`.
4. Copy that `.apk` to your phone (via USB, email to yourself, Drive, etc.)
   and tap it to install (you'll need to allow "install from this source"
   once).

If the build fails, open the failed step's log and send it to me — it's
almost always a one-line fix (SDK/Gradle version mismatch on the runner).

### Option B — Android Studio (more control, easier to tweak)

You need **Android Studio** (free) — this is a standard Gradle project, so:

1. Open Android Studio → **Open** → select this `JaysRoomApp` folder.
2. Let it sync (first time it may ask to install the Gradle wrapper / a missing
   SDK platform — accept both; a one-time download).
3. Plug in your phone (USB debugging on) or use an emulator **on the same Wi‑Fi**
   as the ESP32, then press **Run ▶**.
4. To get an installable `.apk` without a cable: **Build → Build Bundle(s) / APK(s)
   → Build APK(s)**, then grab it from `app/build/outputs/apk/debug/app-debug.apk`
   and copy it to your phone.

I can't produce the compiled `.apk` binary myself in this chat environment — it
needs the Android SDK/build tools, which aren't available here. This project is
complete and ready to build as-is, though; Android Studio does the rest in about
a minute.

## 3. Using it

- Open the app → it shows "Looking for Jay's Room on your Wi‑Fi…"
- As soon as the ESP32 responds (usually under a couple of seconds), a card
  labeled **Jay's Room** appears — tap it to open the dashboard.
- Your phone and the ESP32 must be on the **same Wi‑Fi network**. Some routers
  have "AP/client isolation" or a "guest network" setting that blocks devices
  from seeing each other — turn that off for this to work.
- If you add more ESP32 boards later running the same `MDNS.addService("jaysroom", ...)`
  call (with a different TXT `name`), they'll just show up as more cards in the
  same list automatically.

## Project layout

```
app/src/main/java/com/jayu/jaysroom/
  DeviceDiscovery.kt   mDNS/NSD scanning - the "find it automatically" logic
  DeviceAdapter.kt     RecyclerView list adapter
  MainActivity.kt      Home screen: device list
  WebViewActivity.kt   Full-screen WebView that loads the chosen device's page
```
