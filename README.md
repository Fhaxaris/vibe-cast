# Roku Mirror

Free, ad-free screen mirroring from an Android phone (including GrapheneOS) to a Roku.
No Google services needed.

**How it works:** the phone app captures your screen, encodes it to H.264 and serves it
as a live video stream over your Wi-Fi. A small receiver channel on the Roku plays that
stream. The app finds your Roku and opens the channel for you.

```
android/   Phone app (plain Java, no libraries)
roku/      Roku receiver channel (BrightScript / SceneGraph)
.github/   Cloud build that produces the APK and the Roku zip
```

## 1. Build the phone app (free, in the cloud)

1. Create a new GitHub repository and upload this whole folder to it
   (on a computer, drag the folder's contents onto the repo's "uploading an existing file" page).
2. GitHub builds it automatically (Actions tab, about 3–5 minutes).
3. Open the repo's **Releases → latest** page on your phone and download
   `RokuMirror.apk` and `roku-mirror-receiver.zip`.

Alternatively, open the `android` folder in Android Studio and choose Build → Build APK.

## 2. Install the receiver channel on the Roku (one time)

1. **Turn on developer mode.** On the Roku remote press:
   **Home ×3, Up ×2, Right, Left, Right, Left, Right.**
   Accept the agreement, enable the installer, choose a password, and let it restart.
   The screen shows the Roku's IP address — note it.
2. On your phone or computer browser (same Wi-Fi), go to `http://<roku-ip>`.
   Sign in as **rokudev** with the password you chose.
3. Upload `roku-mirror-receiver.zip` and press **Install**.
   "Phone Mirror" opens on the TV. It stays installed until you sideload something else.

Also check: Roku **Settings → System → Advanced system settings → Control by mobile apps**
must be **Default** or **Permissive** (not Disabled/Limited), so the phone can open the channel.

## 3. Install and use the phone app

1. Open `RokuMirror.apk`; allow your browser/Files app to install unknown apps when asked.
2. Open **Roku Mirror**, tap **Find my Roku** (or type its IP), then **Start mirroring**,
   and allow screen capture.
3. Stop from the app or the notification.

## Good to know

- **Delay of about 3–5 seconds.** Roku only plays HLS-style streams, which buffer.
  Great for videos and photos, not for games.
- **No sound yet.** This version mirrors video only. (Audio capture is a possible v2.)
- **Netflix, Disney+, banking apps etc. show black.** Those apps block screen capture on
  purpose. Use their own Roku channels instead.
- **Rotation:** if you rotate the phone, tap Start again so the picture fits the new shape.
- **Can't find the Roku / won't connect:** be on the same Wi-Fi (not a guest network) and
  turn off any VPN, or allow LAN access in it — the Roku has to reach the phone directly.
- Uses port 8765 on the phone (falls back to 8766–8774 if busy).
