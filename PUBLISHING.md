# Yadora — Publishing Guide (first-time, step by step)

This is written for someone who has never published an Android app. Follow it top to bottom.

---

## 0. Android version range you support (answer to "what range?")

- **Minimum: Android 8.0 (API 26).** Anyone on Android 8.0 or newer can install Yadora.
- **Target: Android 16 (API 36).** You build against the newest Android so the app follows current
  behavior and passes Play's target-API requirement.
- **In plain terms:** ~Android 8 through the latest Android — the large majority of phones in active
  use (Google's own Play distribution dashboard is where you can check the current exact figure). This
  covers nearly every phone in Iran, Germany, and elsewhere. Keeping the minimum at 8.0 (rather than
  raising it) is the pro-student choice: older/cheaper phones are common among students.

---

## 1. Identity that is now permanent (do not change after first publish)

- **applicationId:** `com.yadora.app` — this IS your app's identity on Play forever. Changing it
  later = a different app = every user loses their data. It's already correct; never touch it.
- **App name shown to users:** Yadora.
- **Version:** `versionName = "1.0"` (what users see), `versionCode = 3` (the machine counter).
  **Every upload to Play must have a versionCode strictly higher than the last.** So your first
  upload can stay 3; the next update becomes 4, then 5, etc. Bump it in `app/build.gradle.kts`.

---

## 2. Create your upload keystore (THE one thing only you can do)

The keystore is a file with a private key that signs your app. **If you lose it, you can never
update your app again.** Treat it like the deed to a house.

**In Android Studio:**
1. Build → Generate Signed App Bundle / APK → choose **Android App Bundle** → Next.
2. Under "Key store path" click **Create new…**
3. Fill in:
   - Key store path: save as `yadora-upload-key.jks` somewhere OUTSIDE the project folder (e.g.
     `C:\Users\Shayan\keys\`). Never commit it to git.
   - Key store password: a strong password. **Write it down somewhere permanent.**
   - Key alias: `upload`
   - Key password: another strong password (can be the same). **Write it down.**
   - Validity: 25+ years.
   - Certificate: your name / org (e.g. "Dr. Shayan Salehirad").
4. Finish creating. Then continue the wizard → select **release** build variant → Finish.
5. Android Studio produces a **signed .aab** in `app/release/`. That's your upload file.

**Back it up NOW:** copy the `.jks` file AND the two passwords to at least two safe places (a
password manager + an encrypted cloud file + a USB stick). This is the single most important thing
in this whole document.

> The project's `build.gradle.kts` is already set up so that release signing engages automatically
> when the environment variables `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` are set — but for
> a first release, the Android Studio wizard above is the simplest path and you don't need the env
> vars at all.

**Enroll in Play App Signing** (Play offers this during setup — say yes): Google stores a secure
copy of your app-signing key so a lost upload key can be reset. Highly recommended.

---

## 3. Verify the signed bundle before uploading

- Install the release build on a real phone first: Android Studio can install the signed APK, or
  run `bundletool` to generate an APK from the AAB. At minimum, install the **release** APK
  (`app/build/outputs/apk/release/`) after signing it, and click through the whole app once —
  R8/minification can occasionally break something that debug builds don't.
- Confirm: add a topic, review it, get a test reminder (Settings → "Send a test reminder"), toggle
  language to Deutsch and Persian, export a backup and re-import it.

---

## 4. Google Play Console setup (one-time, ~$25 one-time fee)

1. Go to **play.google.com/console**, pay the one-time $25 registration, create a developer account
   (personal is fine; you can use "Dr. Shayan Salehirad" as the developer name).
2. **Create app** → name "Yadora", default language, "App", "Free".
3. Fill the required sections (Play walks you through a checklist):
   - **Store listing:** title, short + full description (from MARKETING.md), screenshots (section 6
     below), feature graphic, app icon (already in the project).
   - **Privacy Policy:** REQUIRED. You need a public URL. Simplest: a free GitHub Pages / Google
     Sites / Telegraph page stating: "Yadora stores all data on your device. It has no account, no
     ads, and no analytics servers. Optionally, Android's own encrypted backup may store your data
     in your personal Google account (you control this in your device settings). You can export or
     delete all your data at any time inside the app. Contact: shayanay80@gmail.com." (A template is
     in section 7.)
   - **Data Safety form:** declare honestly. Yadora collects **no** data off-device. You DO enable
     Android Auto Backup (so tick "app data may be backed up" per the form's backup question) but
     you collect/transmit nothing to you. No data shared with third parties.
   - **Content rating:** fill the questionnaire → it'll come out "Everyone / PEGI 3".
   - **Target audience:** 13+ (or 18+ if you prefer to avoid child-privacy rules; a study app for
     "all learners including a German kid" — if you genuinely target under-13, that triggers extra
     Families-policy requirements, so **targeting 13+ is simpler**; the app still works for a
     younger user, you just don't market to under-13).
   - **Ads:** declare "No ads".
   - **Permissions:** Play will ask you to justify `SCHEDULE_EXACT_ALARM` and full-screen intent.
     Answer: "Optional alarm-clock-style study reminders that the user explicitly enables; the app
     falls back to standard notifications when not granted." If Play pushes back on full-screen
     intent, you can remove Alarm Mode from the release (it's already opt-in and default-off) — the
     normal reminder path is unaffected.

---

## 5. Release via internal testing FIRST (do not go straight to production)

1. Play Console → Testing → **Internal testing** → Create release → upload your `.aab`.
2. Add your own email (and a few friends) as testers → share the opt-in link.
3. Install from Play on your real phone via that link. This is the true "does the Play-delivered
   app work" test — including that the reminders fire on your actual device over several days.
4. Let it run through your 40-day study period on internal testing. Fix anything that surfaces.
5. When confident: promote the same build to **Closed testing** (more users), then **Production**.
   Use a **staged rollout** (start at 20%) so you can halt if a crash spikes.

---

## 6. Screenshots — what to add/do so they're self-explanatory (answer to your question)

Play shows 2–8 phone screenshots. Static screens of an *empty* app sell nothing. Do this instead:

**A. Seed realistic demo data first.** Before screenshotting, add ~8–12 real-looking topics across
2–3 subjects (e.g. "Nephrolithiasis", "Beta blockers", "Krebs cycle" under "Pathology" /
"Pharmacology"), review a few so the Progress charts and the growth plant show real shapes, and set
an exam countdown. An empty app looks dead; a lived-in app looks valuable.

**B. Add a caption banner to each screenshot** (do this in Canva/Figma, not in the app): put a short
headline above or below each screen on a cream background so the image explains itself even to
someone scrolling fast. This is the single biggest lever on install rate.

**Suggested 8-screenshot sequence, each with its caption:**
1. **Today screen** (with due topics) — caption: *"Your review plan, every morning."*
2. **Adding a topic** (the Add form with the guidance hints visible) — *"Log what you studied in
   10 seconds. Any subject, any source."*
3. **Review screen** (rating buttons + the interval preview showing "in 11 days") — *"Rate how it
   went. Yadora picks the perfect next date."*
4. **The 'Finished for today' card + upcoming calendar dialog open** — *"Done in minutes. See what's
   coming."*
5. **Progress: retention chart + growth plant** — *"Watch your memory hold — and your plant grow."*
6. **Reminder notification** (screenshot the actual notification on your lock screen) — *"Reminders
   that reach you, even weeks later."*
7. **Settings showing the 3 languages / calm options** — *"English, فارسی, Deutsch. Offline. Yours."*
8. **The knowledge library** — *"Every topic you've ever studied, in one place."*

**How to capture:** run the app on a phone or the Android Studio emulator (Pixel profile), use the
system screenshot, then drop each into a Canva "Google Play screenshot" template (1080×1920) with
your caption. Do one full set **per language** (EN/FA/DE) — localized screenshots dramatically
improve conversion in those markets, and for FA make sure the RTL layout shows.

**C. Optional but strong:** a 30-second promo video (use a video prompt from MARKETING.md, or just
screen-record the real add→review→done loop) — Play shows it above the screenshots.

---

## 7. Privacy policy template (paste into a public page, edit contact)

> **Yadora Privacy Policy**
> Yadora is an offline study-review planner. All your data — topics, notes, review history, settings
> — is stored only on your device. Yadora has no user accounts, no advertising, and no servers that
> collect your information; we (the developers) never receive your study data.
> Optional backups: Android's built-in encrypted backup may copy the app's data to your personal
> Google account if you have device backup enabled. This is controlled entirely by you in your
> Android settings, not by Yadora. You can export a full backup file or permanently delete all your
> data at any time from within the app (Settings → Data).
> Contact: shayanay80@gmail.com (or Telegram @shayan_salehirad).
> Last updated: [DATE].

---

## 8. After launch — operations

- **Monitor:** Play Console → Crashes & ANRs. If a crash appears on many devices, halt the staged
  rollout and fix.
- **Reviews:** reply to early reviews politely; ask happy testers to rate.
- **Updates:** each update = bump `versionCode` (+1) in `app/build.gradle.kts`, rebuild signed AAB
  with the SAME keystore, upload, staged rollout.
- **Rollback:** Play lets you halt a rollout, but you cannot "un-publish" a bad build to existing
  installs — you fix forward with a new higher versionCode. That's why internal testing first
  matters.

---

## 9. Your remaining to-do (the short version)

1. Create + back up the keystore (§2). ← only true blocker
2. Write the privacy policy page (§7) and get its URL.
3. Seed demo data, take + caption screenshots in EN/FA/DE (§6).
4. Play Console: $25, store listing, Data Safety, content rating (§4).
5. Upload signed AAB to **internal testing**, run it through your exam field-test (§5).
6. Promote to production with a staged rollout when confident.

Everything on the *code* side is done: builds are green, release AAB compiles, 76 tests pass,
0 lint errors, permissions trimmed, full German/English/Persian support.
