# Expiry Check

Android app for tracking expiry dates on a shop floor. Scan a barcode, the app finds the product name and photo online, you pick the date. No stock counts — just what's on the shelf and when it goes out of date.

## What it does

- **Scan** with Google's barcode scanner (no camera permission needed; you can also type the number inside the scanner).
- **Automatic name and photo** from Open Food Facts, Open Beauty Facts, Open Pet Food Facts and Open Products Facts, with UPCitemdb as a fallback. The photo is saved on the phone so it shows offline.
- **Fix anything**: change the name, brand/size or photo (take one or pick from the gallery). Your version is remembered, so the next scan of that barcode fills in instantly.
- **Not found?** Type the name and add a photo. Items with no barcode can be added by name (the **+** button).
- **Dates list** grouped Expired / Today / Tomorrow / Next 7 days / Next 30 days / Later, with search and filters. Several dates per product are fine.
- **Off the shelf**: mark Sold out or Thrown away (with Undo), mark as Reduced, change the date, add a note.
- **Daily reminder** notification at a time you choose.
- **Share** a PDF (with product photos and tick boxes) or a text list of expired + soon items; export everything as CSV.
- **Backup / restore** as one JSON file, photos included.

## Get the APK

Every push to `main` builds a new APK automatically.

1. Open the **Releases** section of this repository on your phone.
2. Download the latest `ExpiryCheck-1.0.N.apk` and open it. The first time, Android asks you to allow installs from your browser or Files app.

New versions install over the old one and keep your data, because every build is signed with the same key (`app/signing.keystore`). That key is for this personal sideloaded app only; don't reuse it for anything on Google Play.

The scanner needs Google Play services (standard on almost every Android phone).

## How it's built

- `app/src/main/assets/index.html` is the app's interface (HTML/JS, works fully offline). It also runs in a desktop browser for testing — scanning falls back to typing the barcode.
- `MainActivity.java` shows it in a WebView and adds what a web page can't do: the barcode scanner, online lookups, storing photos, the camera/gallery picker, saving and sharing files.
- `ProductLookup.java` queries the product databases and downloads the photo.
- `ReminderReceiver.java` posts the daily notification and re-arms it after a reboot.
- `.github/workflows/build.yml` builds the APK and publishes it as a release.

## Data

Everything is stored only on the phone. Use **Settings → Export backup** now and then.

Product data from the Open Food Facts family of databases is open data under the ODbL. Bundled third-party files: jsPDF and jsPDF-AutoTable (MIT), and Bricolage Grotesque, Atkinson Hyperlegible and JetBrains Mono fonts (SIL Open Font License).
