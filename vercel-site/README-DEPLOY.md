# Vercel host for the launcher APK + technician setup page

This folder is a static site. It serves two files publicly over HTTPS:

- `/v3poslauncher-release.apk` — the signed launcher, which the QR tells the device to download.
- `/` — a technician "scan to set up" page that displays the QR and the steps.

You drop two built files in here before deploying:

- `v3poslauncher-release.apk`  ← the signed APK from your build
- `provisioning-qr.png`        ← the QR generated for this Vercel URL

`vercel.json` already sets the right `Content-Type` for the APK.

## Deploy (macOS)

```bash
npm i -g vercel            # one-time
cd vercel-site
vercel login              # one-time, opens browser

# First deploy — name the project so the URL is stable:
vercel deploy --prod --yes --name flo-pos-setup
```

Your public URL will be `https://flo-pos-setup.vercel.app`, so the APK lands at
`https://flo-pos-setup.vercel.app/v3poslauncher-release.apk` — which is exactly the
`DOWNLOAD_URL` the QR is generated against.

> If you pick a different project name, use that name in the URL AND set the repo's
> `DOWNLOAD_URL` variable to match, then regenerate the QR so it points to the right place.

To publish an updated APK later, replace the file here and run `vercel deploy --prod` again.
The URL stays the same, so the printed QR keeps working.
