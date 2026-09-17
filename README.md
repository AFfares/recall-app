# Fekkerni (فكّرني) — Floating Edge Handle

The React Native + Expo SDK 57 app keeps its existing UI, while the floating
handle runs as an Android `WindowManager` overlay in a foreground service. It
therefore remains visible above the Home Screen and other applications.

## Native overlay requirement

Expo Go cannot host the system overlay. Build the native Android project with
Android Studio/SDK and test on a physical Android phone.

## 1. Project structure

```
fekkerni-app/
├── App.js                     # React Native screen + overlay permission bridge
├── android/                   # Native overlay service and Android project
├── app.json                   # Expo app config
├── babel.config.js            # Enables the Reanimated Babel plugin
├── package.json
└── src/
    ├── components/
    │   └── EdgeHandle.js      # Original in-app prototype, no longer mounted
    └── theme/
        └── colors.js          # Shared color palette
```

## 2. Install

Open this folder in VS Code, then in its integrated terminal:

```bash
npm install
```

Then let Expo double-check that every native-adjacent package matches your
installed Expo SDK version (important for Reanimated / Gesture Handler):

```bash
npx expo install react-native-gesture-handler react-native-reanimated react-native-safe-area-context
```

## 3. Get Expo Go on your Android phone

1. Install **Expo Go** from the Google Play Store on your phone.
2. Make sure your phone and your PC are on the **same Wi-Fi network**
   (this is the easiest path — no USB debugging needed).

## 4. Start Metro and run on your phone

```bash
npx expo start
```

This opens a terminal UI with a QR code.

- **Wi-Fi method (recommended):** Open Expo Go on your phone → "Scan QR code"
  → point it at the terminal/browser QR code. The app bundles and launches
  automatically.
- **USB method (if Wi-Fi isn't possible / firewall issues):**
  1. Enable Developer Options on your phone (Settings → About phone → tap
     "Build number" 7 times).
  2. Enable **USB debugging** in Developer Options.
  3. Plug the phone into your PC via USB and accept the "Allow USB
     debugging?" prompt on the phone.
  4. Run `adb devices` to confirm your PC sees the phone.
  5. In the Expo terminal UI, press `a` to run it directly on the connected
     Android device, or run:
     ```bash
     npx expo start --tunnel
     ```
     if your PC and phone can't reach each other directly (e.g. different
     networks, VPN, restrictive router).

## 5. Testing the interaction

- Press and hold the thin vertical bar on the right edge of the screen for
  about a third of a second.
- It morphs into a sphere with pulsing ripple rings.
- Drag it anywhere — up, down, left, right. Moving it fast shows a
  directional "trail" instead of the idle pulsing rings.
- Lift your finger: the sphere animates to whichever side (left or right)
  it's currently closer to, at the same height, then morphs back into the
  resting bar.

## 6. Design decisions worth knowing about

- **Ripple waves & the "continue after attaching" requirement.** The waves
  animate continuously the entire time the sphere is active — while held
  still, while dragging, and all the way through the snap/settle animation
  as it travels to the edge. They fade out as the sphere finishes morphing
  back into the resting bar. Keeping them looping forever on a static,
  attached bar would look like a stuck notification rather than an
  intentional interaction, so they're tied to "the sphere exists" rather
  than to a fixed timer.
- **No dotted target markers or permanent selection borders**, per your
  request — the only feedback is the ripple/trail animation on the sphere
  itself and the small status pill at the bottom of the screen.
- **Left/right only.** The vertical position (Y) is preserved and clamped to
  the safe area; only the side (left/right) is decided on release, based on
  which half of the screen the sphere was in when you let go.

## 7. Troubleshooting

**"Reanimated 2 failed to create a worklet" / animations don't run**
Make sure `react-native-reanimated/plugin` is the **last** entry in
`babel.config.js` (it already is in this project), then clear the Metro
cache and restart:

```bash
npx expo start -c
```

**Gestures don't respond at all**
`GestureHandlerRootView` must wrap the entire app (it does, in `App.js`).
If you ever add navigation libraries, make sure this stays the outermost
wrapper.

**Handle jumps or "teleports" at the start of a drag**
This happens if `onStart` of the Pan gesture doesn't run before `onUpdate`.
This project reads the handle's _current_ animated position in `onStart`
(`startPos.value = { x: posX.value, y: posY.value }`) specifically to avoid
that — if you modify the gesture, keep that pattern.

**Metro bundler can't find the phone / "Something went wrong" in Expo Go**

- Confirm phone and PC are on the same network, or use `--tunnel`.
- Disable any VPN on either device.
- Check your PC firewall isn't blocking Metro's port (default 8081).

**App installs but immediately crashes on the phone**
Usually a version mismatch between Expo SDK and one of the native packages.
Re-run:

```bash
npx expo install --check
```

and accept the suggested version fixes.

**`adb devices` shows nothing**

- Reconnect the USB cable, try a different port/cable (some are charge-only).
- Confirm "USB debugging" is still enabled and re-accept the RSA prompt if
  it reappears.

## 8. Where to go from here

If you outgrow Expo Go's sandbox (e.g. you need a custom native module),
run:

```bash
npx expo prebuild
```

This generates a real `android/` folder you can open directly in Android
Studio and build with Gradle, while keeping all the JS/TS code above
unchanged.
