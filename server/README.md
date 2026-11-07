# Sohelbr online setup (free Firebase plan) - admin system

What you get: an admin web page where YOU add Luck Royale prizes and Events with start/end times, see player
reports with a replay of the last 45 seconds of play, and ban players by ID. The game downloads prizes/events/ban
status and uploads reports.

## 1. Create the Firebase project
1. https://console.firebase.google.com > Add project (name it e.g. "sohelbr-game"). Analytics not needed.
2. Build > Authentication > Get started > Sign-in method: enable **Anonymous** (for report uploads) and **Google** (for you, the admin).
3. Build > Firestore Database > Create database (Production mode, any region near you).
4. Firestore > Rules: paste the contents of `server/firestore.rules` and Publish. (Change the admin e-mail inside if needed.)

## 2. Connect the game
1. Project settings (gear) > General. Copy **Project ID** and **Web API Key**.
2. Put them in `Backend.kt` -> `BackendConfig.PROJECT_ID` and `BackendConfig.API_KEY`. Rebuild the game.
3. Make sure `AndroidManifest.xml` contains `<uses-permission android:name="android.permission.INTERNET"/>`.

## 3. Connect the admin page
1. Project settings > Your apps > add a **Web** app. Copy the `firebaseConfig` values into the top of `admin/index.html`.
2. Authentication > Settings > Authorized domains: add the domain you host the page on (e.g. `yourname.github.io`, or `localhost` to test).
3. Host `admin/index.html` for free: GitHub Pages (Settings > Pages) or Firebase Hosting. Open it and sign in with the admin Google account.

## Limits to know about
- A player id is created on the phone. A banned player can clear app data to get a new id. Real fix: sign-in with accounts (Google / phone number) - needed before real online play.
- Prize/event time windows use the phone's clock until a server checks time. Rewards and wallet are still saved on the phone, so they can be edited by hackers until the server owns the wallet.
- Reports need real human players. In the offline game the reported "player" is always BOT, so use it only to test the pipeline.
