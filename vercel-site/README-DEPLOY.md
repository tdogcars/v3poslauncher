# Vercel host for the launcher APK + technician setup page

This folder is a static site, deployed by Vercel's Git integration from the `main` branch
(Vercel project **flo-pos-setup**, root directory `vercel-site`). It serves two files publicly:

- `/v3poslauncher-release.apk` — the signed launcher, which the QR tells the device to download.
- `/` — a technician "scan to set up" page that displays the QR and the steps.

`vercel.json` sets the right `Content-Type` for the APK. `.vercelignore` keeps this README out
of the deploy.

## How updates flow (automated)

1. Push a version tag: `git tag v3.0.2 && git push origin v3.0.2`
2. GitHub Actions builds + signs the APK, verifies the signing cert matches the keystore,
   publishes a GitHub Release, generates the QR, **and commits the APK + QR into this folder
   on `main`**.
3. Vercel sees the push to `main` and redeploys within about a minute.

The URL never changes (`https://flo-pos-setup.vercel.app/v3poslauncher-release.apk`), so printed
QRs keep working. Run `git pull` afterwards to pick up the bot's commit locally.

## Manual fallback

Drop a built `v3poslauncher-release.apk` and `provisioning-qr.png` in here, commit, and push
`main` — Vercel redeploys. Or, with the Vercel CLI: `cd vercel-site && vercel deploy --prod`.

> If the Vercel project name ever changes, set the repo variable `DOWNLOAD_URL` to the new APK
> URL and push a new tag so the QR is regenerated to match.
