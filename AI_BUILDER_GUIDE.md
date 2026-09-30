# LogiMind (Logapp_android) — Guide for AI Builders

Audit date: 2026-09-30 · Commit audited: `2b28f0a` (branch `main`)

Coverage: every logic and screen file read in full (data layer, ViewModel, services, `MainActivity`, `HomeScreen`, `FormSolveScreen`, `TemplateDesignerScreen`), plus build files, manifest and backup rules; theme files and `EmptyStateCard` skimmed. Static review only — the app was **not** built or run; verify each fix on a device.

## Progress — branch `fix/data-safety` (2026-09-30)

Verified: `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes (15 tests). Database identity hash unchanged (`9fd2b3085e40b8491799279cd33b12c8`, version 3), guarded by `SchemaFreezeTest`. Emulator demo (Android 16): old v1.0 build → data created → `adb install -r` to v1.1 → entry, patients and templates intact, safety backup written; rename-section, delete confirms, template preview and patient card checked on screen. Not yet run on a real phone.

Found during the demo and fixed: patient cards cached their log list by list position, so after adding a patient another patient's logs showed on the wrong card (now keyed by patient UID); long rule badges pushed a question's Duplicate/Delete buttons off screen (now shortened).

Done: F1, F2, F3 (backup v2 + safe v1/v2 restore + recovered templates), F4 (delete confirm with entry count; no archive), F5, F6 (honest text, badge no longer toggles tiers; default tier still Premium), F7, F8, F9, F10, F13, F14, F15, F16 (answer unset until chosen when the question is asked; unchanged when not asked), F17, M1–M7, M9–M12. Screen fixes: drag reorder, delete confirms, rule validation and readable badges, enroll-cancel, template preview, local-day date filters, tile-order merge, entry viewer (date, MRN, Yes/No, attribution by key), procedure stats grouped by current label, dropdown clear, number filter, double-save guard, clear-answers confirm, Gemini consent, API key in header, CSV injection guard, export cache cleanup, off-main-thread exports, one-time pre-upgrade backup to `filesDir/backups/`.

Build setup added: Gradle wrapper 9.3.1; debug signing uses `debug.keystore` when present, otherwise the local Android Studio debug key; `versionCode 2` / `1.1`; Room schema exported to `app/schemas/`.

Branding & theme (2026-09-30): new vector logo (clipboard + teal cross) used for the launcher icon (adaptive + monochrome + API 24–25 fallback) and the in-app header (`drawable/ic_logo.xml`); the old raster header image with a fictitious doctor's name is no longer used. Top bar refined in place (same elements and order). Complete dark colour scheme plus `BrandTheme.colors` for tile and badge colours; window background follows night mode (no white flash). Appearance setting System (default) / Light / Dark in Settings, stored as `theme_mode` in `logimind_prefs`.

Round 3 (2026-09-30), all without schema or layout changes: daily automatic backup in `filesDir/backups/auto_*.json` (newest 7 kept) and a snackbar reminder when no backup was exported for 7 days; Undo after deleting an entry (with its answers) or a patient; drafts of unsaved new entries in prefs `draft_<templateUid>` with a "Resume unsaved entry?" prompt (an empty form never removes a draft); archive/unarchive templates (`isActive`) and "Archive instead" in the delete dialog; pinned templates (`pinned_templates`) first in New Entry; "Repeat as New Entry"; quick "New patient" in the New Entry wizard; edit patient (also updates the name stored on their entries) with duplicate-MRN warning; search in Recent Logs (title, patient, MRN, template, answers); answers in template order in viewer, PDF and CSV; CSV with one labelled column per question; supervisor/witness + verified on completed procedures (stored in `attributionsJson`, shown in viewer and PDF); procedure report PDF (totals by procedure and month, performed vs assisted, case list); question hints (designer + form); AI summary runs only on request, GPT-4/Claude hidden, plain-language errors; Gemini key encrypted with Android Keystore (`gemini_api_key_enc`, migrated from the old plain value); optional app lock with the phone's PIN/fingerprint (`app_lock_enabled`, re-locks after 60 s in background). 18 unit tests pass; draft, repeat, settings and template options checked on the emulator.

AI summary rework (2026-09-30): three purposes (Case summary, Patient timeline, Logbook) with an automatic default from the selection (1 log → case; several logs of one patient → timeline; otherwise logbook). Input is structured and de-identified (`SummaryInputBuilder` → sections/questions in template order, only visible questions, blanks as "Not recorded", procedures with performed / assisted / role-not-recorded and supervisor); patients are "Patient 1…" and names/MRNs/phones/entry titles are never sent — a patient key is shown on the phone only. Gemini gets fixed rules in `systemInstruction`, a per-purpose format and length, temperature 0.2, model Fast (`gemini-2.5-flash-lite`, default) or Better (`gemini-2.5-flash`) in Settings. Offline summary uses the same headings. "What will be sent?" preview, Share, and an app-added "AI draft" footer. Tested by `SummaryTest`; a live Gemini call has not been run yet (no key on the test device).

Still open: F11 / M13 (backup-rules decision for owner), F12 / M14 (R8), touch-target sizes, strings to `strings.xml`, AI summary count persistence, sample patients on first run.

---

You are improving an Android app that is **already installed on users' phones with real clinical data**.
The new version must install **over** the old one and keep every record. Read Part 1 before touching any code.

App summary: Kotlin + Jetpack Compose + Room. EMR form builder (templates → sections → fields → options)
and clinical logbook (entries filled from templates, linked to patients, with procedure attribution).
Single activity, manual screen switching, one ViewModel.

---

## Part 1 — Hard rules (do not break)

### 1.1 Database: frozen

The Room database must open the old file with **zero schema change**.

| Item | Frozen value |
| --- | --- |
| DB file name | `medical_form_database` ([AppDatabase.kt](app/src/main/java/com/example/data/local/AppDatabase.kt)) |
| DB version | `3` |
| Entities | `FormTemplate`, `FormSection`, `FormField`, `FormFieldOption`, `Patient`, `FormEntry`, `EntryAnswer` |
| Tables | `form_templates`, `form_sections`, `form_fields`, `form_field_options`, `patients`, `form_entries`, `entry_answers` |
| Foreign keys | sections→templates, fields→sections, options→fields, entries→templates, answers→entries — all `ON DELETE CASCADE` |
| Indices | `templateId`, `sectionId`, `fieldId`, `templateId`, `entryId` (one per child table) |

Rules:
- Do **not** add, rename, remove or retype any column, table, index or foreign key in [Entities.kt](app/src/main/java/com/example/data/model/Entities.kt). Do not change default values or nullability. Room hashes the schema; any change without a version bump crashes on open, and a bump with the current config **wipes all data** (see F1).
- Do **not** change the `@Database` version.
- Add new DAO queries freely (queries do not change the schema).
- New data you need to store goes into **existing JSON columns** (`answersJson`, `attributionsJson`, `customAnswersJson`, `conditionalLogicJson`, `defaultValue`) or into SharedPreferences — never new columns.
- Keep the stored JSON formats readable by old code paths:
  - `form_entries.answersJson`: flat object `{ "<fieldUid>": "<string value>" }`. Procedure values are nested JSON strings with keys `procedure`, `completed`, `performedByMe`, or `choices`, `choices_perf`.
  - `form_entries.attributionsJson`: `{ "<fieldUid>": { fieldId, providerType, providerName, providerId, supervisorWitness, isWitnessVerified } }`.
  - `form_fields.conditionalLogicJson`: **not JSON**, a plain string `"<fieldUid>==<value>"`.
  - `form_fields.defaultValue`: also used as a layout flag `"horizontal"` / `"vertical"` for choice fields.
- Seeded IDs (`default-airway-template-1`, `default-icu-discharge-2`, `section-a-clinical`, `field-age`, etc.) must stay the same.

### 1.2 App identity: frozen

Data survives an update only if Android treats the new APK as the **same app**.

| Item | Must be |
| --- | --- |
| `applicationId` | `com.example` (same as the old build — changing it installs a second app with an empty DB) |
| Signing key | the **same keystore** used for the build users have (`my-upload-key.jks`, alias `upload`) |
| `versionCode` | greater than the installed one (currently `1`) |
| SharedPreferences file | `logimind_prefs` |
| Pref keys | `db_seeded_v1`, `gemini_api_key`, `dashboard_tile_order` |
| FileProvider authority | `com.example.fileprovider` |

**Signing reality (checked 2026-09-30):** the installed app was built and installed by Google AI Studio (developer mode) and signed with AI Studio's `debug.keystore` (alias `androiddebugkey`, password `android`). That file is **not** in the GitHub repo nor in AI Studio's ZIP export, and the exported README says to remove `signingConfig = signingConfigs.getByName("debugConfig")` for local builds — so a build made in Android Studio is signed with a **different** key and **cannot** install over the existing app. Therefore:
- Preferred: apply the changes inside the same AI Studio project (https://ai.studio/apps/c04b1c95-c81f-4d2e-b9da-970da4c5a840) and install from there, so the signature matches and data stays.
- Otherwise: copy the data out before switching builds. The installed app is a debug build, so `adb shell run-as com.example` can copy `databases/medical_form_database` (+ `-wal`, `-shm`) and `shared_prefs/logimind_prefs.xml` off the phone, then back into the new debuggable build after install. Do this per phone.
- Keep whichever key the new builds use (and its passwords) outside git and backed up; losing it repeats this problem.

Note: `com.example` cannot be published on Google Play. If the app ever moves to Play with a new ID, users must go through export → import, which is currently broken (F3). Fix F3 before any ID change.

### 1.3 UI: frozen layout

- Do not move, reorder, remove or restyle the position of existing screens, tabs, buttons, cards, fields or dialogs.
- Allowed: fixing behaviour behind an element, adding confirm dialogs, adding error/empty/loading states inside the existing slot, adding `contentDescription`, fixing text, moving hardcoded strings to `strings.xml` with identical text.
- Dashboard tile order is user-configurable (`dashboard_tile_order`); keep the tile keys `patients,new_entry,templates,sync,my_procedures`.

### 1.4 How an old-app user gets their data into the new app

There are two paths. Path A is the default and needs no action from the user.

| Path | When | What carries over | Requirements |
| --- | --- | --- | --- |
| **A. In-place update** | Same phone, new APK installed over old one | Everything: DB file, prefs, tile order, API key | Same `applicationId`, same signing key, higher `versionCode`, DB version stays `3`, entities unchanged, destructive fallback removed (F1) |
| **B. Export → import** | New phone, new `applicationId`, or a reinstall | Only what the backup file contains | Old app's backup is **v1** (templates, patients, logs only — no questions). New app must read v1 and v2, and must not delete anything on restore (F2, F3) |

Path B from the **old** app cannot recover template questions (sections/fields/options), because the old export never wrote them. Mitigations, in order of preference:
1. Tell users to update in place (Path A) first, then make a v2 backup from the new app.
2. When restoring a v1 file, if a template UID already exists locally, keep the local structure and only add missing entries/patients.
3. When a v1 template has no local structure, rebuild a read-only section from the entries: one field per answer key, label = key. Mark it "Recovered from backup" in the template name suffix. Entries then display and export correctly.

### 1.5 Migration change register

Every change below is safe for existing data **as specified**. "Schema" = does it touch the Room schema (must always be No).

| # | Change | Files | Schema | Old data handling |
| --- | --- | --- | --- | --- |
| M1 | Remove `fallbackToDestructiveMigration()`; `exportSchema = true`; commit `3.json` | `AppDatabase.kt`, `app/build.gradle.kts` | No | Old DB opens unchanged; a future bad migration now fails loudly instead of wiping data |
| M2 | Add `@Update` / `@Upsert` DAO methods; use them for existing rows | `Daos.kt`, `FormRepository.kt`, `FormBuilderViewModel.kt` | No | Stops CASCADE deletes when editing sections, restoring templates |
| M3 | Wrap multi-step writes in `withTransaction` | `FormRepository.kt` (needs `AppDatabase`), VM | No | A crash mid-save leaves old rows intact |
| M4 | Backup format v2 (adds `sections`, `fields`, `options`, `answers`) | `ExportService.kt` | No | v1 files still accepted; v2 is a superset, same key names |
| M5 | Safe restore: FK order, upsert, transaction, skip bad rows, report counts, v1 fallback rules above | `FormBuilderViewModel.kt` `restoreBackup` | No | Never deletes existing local rows |
| M6 | Pre-upgrade safety backup on first launch of new version | VM `init` + new pref `safety_backup_done_v2` | No | Snapshot of old data in `filesDir/backups/` before any new code writes |
| M7 | Persist intake field config | VM + new pref `intake_fields_v1` | No | Absent key → current in-memory defaults (same as old behaviour) |
| M8 | Move Gemini key to encrypted prefs | VM, new dep `androidx.security:security-crypto` | No | Read `gemini_api_key` from `logimind_prefs`, write to encrypted store, then remove old key. Never drop it before the copy succeeds |
| M9 | Template delete → warn with entry count / archive via `isActive=false` | `HomeScreen.kt` dialog, VM | No (`isActive` already exists) | Old templates have `isActive = true`, unchanged |
| M10 | Duplicate template remaps conditional UIDs | VM `duplicateTemplate` | No | Existing copies stay broken; optional one-time repair: for each field whose rule UID is not in the same template, match by label within the template |
| M11 | AI summary uses field labels | `AiSummaryService.kt`, caller in `HomeScreen.kt` | No | Reads existing `answersJson` as-is |
| M12 | Context leak fix (`applicationContext`) | `MainActivity.kt` | No | None |
| M13 | Backup rules exclude API key | `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | No | DB backup decision left to owner (keeping it helps phone-to-phone restore) |
| M14 | R8 minify with keep rules | `app/build.gradle.kts`, `proguard-rules.pro` | No | Entity class names must be kept or Room/Moshi break |

New SharedPreferences keys (all in `logimind_prefs`; absent = old behaviour): `intake_fields_v1`, `safety_backup_done_v2`, `draft_<templateUid>`, `ai_summary_count`, `ai_consent_v1`.

**Forbidden changes** (each would lose or strand old data): new/renamed columns or tables; changing `@Database(version)`; changing `applicationId`; signing with a different key; renaming `logimind_prefs` or its keys; changing the `answersJson` / `attributionsJson` / `conditionalLogicJson` formats; changing seeded UIDs; changing the FileProvider authority.

---

## Part 2 — Features and whether they work

Status: **Works** · **Partial** (works with real defects) · **Broken** (loses data or wrong result) · **Stub** (fake).

| Feature | Status | Evidence / problem |
| --- | --- | --- |
| Template list, create, rename | Works | `addTemplate`, `updateTemplateName` in [FormBuilderViewModel.kt](app/src/main/java/com/example/ui/viewmodel/FormBuilderViewModel.kt) |
| Template duplicate | Partial | Copies fields but `conditionalLogicJson` still points at the **original** field UIDs, so show/hide rules silently stop working in the copy (L640-689) |
| Template delete / delete all | Broken (data loss) | CASCADE on `form_entries.templateId` deletes **every log entry** made with that template, with no warning (L755, L795) |
| Section/field add, edit, reorder, delete | Partial | Section updates use `insertSection` = `REPLACE`; SQLite REPLACE deletes the row first, which CASCADE-deletes all fields and options in that section (see F2). `duplicateField` drops `isAdvanced` on options (L389) |
| Designer draft save | Partial | `saveTemplateDraft` deletes all sections then re-inserts, not in a transaction; a crash mid-save leaves an empty template (L692-713) |
| Form filling + save entry | Partial | Works, but save is multi-step without a transaction; answers table rewritten each save (L544-624) |
| Conditional show/hide | Partial | Single `fieldUid==value` rule only; see screen audit below |
| Patients add/delete | Partial | Delete leaves entries pointing at a missing patient (no FK on `patientId`); no confirm |
| Custom patient intake fields | Broken | Configuration held only in memory (`_customIntakeFields`, L84-98); resets on every app restart |
| PDF export | Partial | Works for selected entries. ZIP export calls `generatePdf` **without** `fieldLabelMap`, so PDF in the ZIP shows raw UUIDs instead of question labels ([ExportService.kt](app/src/main/java/com/example/data/service/ExportService.kt) L383) |
| CSV export | Partial | Answers dumped as raw JSON in one cell; no per-question columns; no CSV-injection guard (`=`,`+`,`-`,`@`) |
| JSON backup | **Broken** | Labelled "lossless" but omits sections, fields, options and entry_answers (L309-374). A restore gives templates with **no questions** |
| Restore backup | **Broken (data loss)** | `insertTemplate` is `REPLACE`: restoring a template that already exists deletes its sections, fields, options **and all its entries** via CASCADE. No transaction, FK failure mid-way leaves partial data (VM L803-871) |
| AI summary — Local offline | Partial / unsafe | Classifies answers by **field UID text** (`key.contains("symptom")`). User-made fields have UUID keys, so almost nothing is picked up. Also invents clinical statements not in the data ("care team has authorized standard ongoing follow-ups", "Maintenance fluids only", default outcome "Stable / Routine") ([AiSummaryService.kt](app/src/main/java/com/example/data/service/AiSummaryService.kt) L63-285). Timeline sorts by date **string** ("MMM d, yyyy"), so order is wrong |
| AI summary — Gemini | Works (needs key) | Real call to `gemini-2.5-flash-lite`. Same UUID problem as above, so prompt is mostly empty. Key sent as URL query param; patient name sent to cloud without consent notice |
| AI summary — OpenAI / Claude | Stub | Return hardcoded marketing text, no API call (L356-386) |
| AI summary free limit | Broken | Counter `_aiSummaryCount` is in-memory, resets each launch |
| Cloud sync | Stub | `SyncPlaceholderService` waits 1.5 s and reports success; nothing is uploaded ([SyncService.kt](app/src/main/java/com/example/data/service/SyncService.kt)). Users may believe data is backed up |
| Subscription / Premium | Stub | Starts as `PREMIUM`; `toggleSubscription()` flips tier with no billing ([MonetizationService.kt](app/src/main/java/com/example/data/service/MonetizationService.kt)) |
| First-run sample data | Works, but | Seeds fake patients "Jane Doe" / "Marcus Aurelius" into a real clinical logbook |
| Template preview (read-only) | Stub | Shows fixed placeholder text, not the template (HomeScreen L1801-1835) |
| Procedure attribution / witness entry | Missing | No UI calls `updateProcedureAttribution`; new entries always have empty attribution; viewer/PDF only show old data |
| "Did I perform this?" procedure logging | Partial | Works for single checkbox; checkbox groups default every choice to "performed by me" (inflates stats) |
| My Procedures dashboard | Partial | Counts, search, 7d/30d and template filters work; inflated by the default above; splits a procedure when its question is renamed |
| Patient card demographics | Broken (safety) | "Drug Allergy Alert" row shows the notes field; real allergies shown under raw key |
| Enroll patient | Partial | Validation works; Cancel with typed data is stuck; no MRN duplicate check; no edit |
| Recent logs list, date filter, select mode | Partial | Works; date filter off by the UTC offset for non-UTC users |
| Dashboard tile drag & drop | Works | Saved to `dashboard_tile_order`; new tiles in future versions must be appended |
| Rotation / process death | Broken | Rotating returns to Home from any screen |

### Screen-level findings (verified)

| Where | Problem | Severity |
| --- | --- | --- |
| [TemplateDesignerScreen.kt:238](app/src/main/java/com/example/ui/screens/TemplateDesignerScreen.kt#L238), L206, L247-248 | Rename section, move up/down and drag-reorder call `updateSectionTitle` / `moveSection*` / `swapSectionOrders` → `insertSection` (REPLACE) → CASCADE deletes that section's fields and options. Real daily-use data loss path for F2 | Critical |
| [TemplateDesignerScreen.kt:576-593](app/src/main/java/com/example/ui/screens/TemplateDesignerScreen.kt#L576-L593) | "Save changes before leaving?" dialog says Discard rolls back, but every edit is already written to the DB; Save and Discard do the same thing. Fix: keep dialog, make Discard real (snapshot template structure on open, restore it in a transaction on Discard) or reword text to "Changes are saved automatically" | High |
| [TemplateDesignerScreen.kt:954-956](app/src/main/java/com/example/ui/screens/TemplateDesignerScreen.kt#L954-L956) | Question label `OutlinedTextField` is bound to `field.label` from the DB and calls `updateFieldLabel` on **every keystroke** → a DB write + full template reload per character; async round-trip causes dropped characters / cursor jumps and lag on large templates. Hold local text state, save on focus loss / Done (same element) | High |
| TemplateDesignerScreen.kt:160-216 | Drag-reorder: `pointerInput(id)` captures the `sections` list from first composition (stale), and calls `swapSectionOrders` on every drag step (each is a REPLACE → F2 cascade). Use `rememberUpdatedState(sections)`, reorder in memory during drag, persist once on drag end with `@Update` | High |
| TemplateDesignerScreen.kt:947, L784, L1080-1086 | Delete question / delete section / delete option: no confirm. Section delete cascades all its questions; option "×" is a 14 dp target next to the label. Add confirm dialogs (and "N entries have answers to this question") | High |
| VM `deleteField` + FormSolveScreen.kt:107-117 | Deleting a question leaves other questions whose rule points at it; they can never appear again (parent answer is always null). On delete, clear `conditionalLogicJson` of dependents (with confirm) | Medium |
| TemplateDesignerScreen.kt:463-468, L481-482 | Conditional value is free text; must exactly match an option label (case-sensitive) or the question never shows. Keep the text field but validate against the parent's options and offer them as suggestions; reject save if half-filled (field chosen, value blank) — today it silently saves no rule | Medium |
| Designer (no UI) | A rule can only be set when creating a question; there is no way to view/edit/remove it afterwards except delete + recreate | Medium |
| ExportService.kt:217 + deleted fields | Old entries keep answers for deleted questions; PDF label map no longer has them so the raw UID is printed. Fallback label "Deleted question" | Low |
| TemplateDesignerScreen.kt:926 | Rule badge shows raw `IF <uuid>==Yes`. Render parent question label instead (same badge) | Low |
| TemplateDesignerScreen.kt:944-946 | Duplicate question uses the `Add` icon, identical to "Add option". Use `ContentCopy` icon (same slot) | Low |
| TemplateDesignerScreen.kt:938-948, L769-788, L1091 | Icon buttons 24–32 dp (below 48 dp touch target). Keep visual size, add `Modifier.minimumInteractiveComponentSize()` | Low |
| TemplateDesignerScreen.kt:313-471 | "Create Visual Field" dialog content is not scrollable; with procedure + conditional options open, buttons can be pushed off small screens. Wrap content in `verticalScroll` | Low |
| TemplateDesignerScreen.kt:160 | `items(sections)` without `key` → title-edit state can attach to the wrong section after reorder. Add `key = { it.uid }` | Low |
| [HomeScreen.kt:1458](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L1458) | **Clinical safety:** patient card row "Drug Allergy Alert" displays `patient.notes` (entered as "Critical Clinical Background Issues"). Real allergies are saved in `customAnswersJson["allergies"]` and shown further down under the raw key `allergies`. Relabel the row to the notes config label and show allergies (by config label) in that same row slot. No data change needed | Critical |
| HomeScreen.kt:1460-1477 + F7 | Custom intake answers are displayed by raw key (`allergies`, `bloodGroup`, `custom_intake_1712…`). After a restart the custom question configs are gone (F7), so labels can't be recovered. Persist configs (F7) and map key → label; fallback: prettify known keys | High |
| [HomeScreen.kt:1623-1645](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L1623-L1645), L2841-2848, L2999-3006 | Enroll-patient Cancel/outside-tap with a name or MRN typed opens "Save changes before leaving?" whose Save Draft / Discard buttons only close the warning — the stored lambda is never run. The enroll dialog cannot be cancelled without clearing the fields. Make Discard call the lambda; Save Draft = keep dialog open | Medium |
| [HomeScreen.kt:1801-1835](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L1801-L1835) | "Preview template read-only" is a stub: shows fixed text "A. Demographic Block Information", not the template's sections/questions. Load structure and list sections → questions → options inside the same dialog | Medium |
| HomeScreen.kt:2046-2056, L2034 | Sync tab shows "Fully Merged with Clinical Cloud Server" / "Real-time synchronization over Cloud SQL" — nothing is uploaded (F6). Replace text in the same slots | High |
| HomeScreen.kt:2436 | Gemini dialog claims the key is "written encrypt-ready"; it is plain SharedPreferences (and in auto-backup). Fix text or implement M8 | Medium |
| HomeScreen.kt:3506-3561 | Procedure statistics key on the stored `procedure` label captured at fill time → renaming a question splits one procedure into two rows. Group by field UID (answersJson key) and display the current label from `fieldLabelMap`. Parses every entry's JSON on the main thread on each entries change — move to ViewModel `Dispatchers.Default` | Medium |
| HomeScreen.kt:2828-3012 | No duplicate-MRN check on enroll; no way to edit a patient after creation (typos in name/MRN are permanent; `insertPatient` REPLACE is safe for patients — no FK children). Add MRN duplicate warning; add Edit inside the existing expanded card | Medium |
| HomeScreen.kt:675-727, L2641-2694 | Date filters convert entry timestamps to **UTC** calendar days; for UTC+5 users entries made 00:00–05:00 local fall on the previous day. Compare in local time zone (picker millis are UTC midnight → convert with `ZoneOffset.UTC` to a `LocalDate`, entries with `ZoneId.systemDefault()`). Also recomputed every recomposition in the home list (no `remember`) | Medium |
| HomeScreen.kt:125-133, L415-427 | Tile order: tiles missing from the saved `dashboard_tile_order` are never shown. **Migration rule:** any tile added in a new version must be appended to the saved order on load. Order is also written to prefs on every drag step (write once on drag end) | Medium |
| HomeScreen.kt:3226 | Entry sheet line "Filing Date: Blueprint X" shows no date. Show `createdAt` (and `updatedAt` if edited) in the same line | Low |
| HomeScreen.kt:3186-3200, L3280 | Entry sheet: answers in JSON key order (not template order); non-procedure checkbox shows raw `true`/`false`; attribution matched by answer **value** (`filterValues { it == answer }`) so two "Yes" answers get mixed. Sort by section/field `sortOrder`, format booleans, iterate by key | Low |
| HomeScreen.kt:3248 | "Patient MRN Profile linked" shown even for walk-in/Theatre entries; MRN never displayed. Show MRN when `patientId` resolves | Low |
| HomeScreen.kt:1204-1209 | AI engine chip says "Gemini 1.5" but code calls `gemini-2.5-flash-lite` | Low |
| HomeScreen.kt:1402-1404, L1570 | `items(filteredPatients)` / `items(templates)` without `key`; each patient card filters all entries (`O(patients × entries)`) on every entries change. Add keys; precompute `entries.groupBy { it.patientId }` once | Low |
| HomeScreen.kt:3020-3022 vs VM `duplicateTemplate` | Free-tier 3-template limit only checked in Create dialog; Duplicate bypasses it. Moot while F6 keeps everyone Premium | Low |
| HomeScreen.kt:3952 | `Modifier.scale()` redefined as a no-op, shadowing Compose's `scale`; delete it | Low |
| [HomeScreen.kt:1745](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L1745) | Delete template: one tap, no confirm, cascades all its log entries (F4) | Critical |
| [HomeScreen.kt:1507](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L1507) | Delete patient: one tap, no confirm | High |
| [HomeScreen.kt:348](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L348) | Header PRO badge toggles Premium for free for any user (F6) | High |
| [HomeScreen.kt:2133-2186](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L2133-L2186), L3446-3460 | PDF / CSV / ZIP / JSON built inside `onClick` on the main thread → ANR with large logbooks. Launch in a coroutine on `Dispatchers.IO`, show progress in the same button | Medium |
| [HomeScreen.kt:197](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L197) | Backup file read on main thread | Low |
| [HomeScreen.kt:2177](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L2177), L2186 | Text claims "completely lossless" backup — false until F3 is done | High |
| [HomeScreen.kt:222-257](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L222-L257) | AI summary runs automatically in a `LaunchedEffect` whenever selection, style, provider or tier changes → a cloud call (and patient data upload) on every toggle. Trigger only on an explicit button press in the existing panel | Medium |
| [FormSolveScreen.kt:103-124](app/src/main/java/com/example/ui/screens/FormSolveScreen.kt#L103-L124) | Answers of fields that become hidden are still saved (and exported / sent to AI). Chained rules evaluate on a hidden parent's stale answer. Fix: compute visibility iteratively (a field is visible only if its parent is visible), and drop hidden answers from the map in `saveEntry` | High |
| [FormSolveScreen.kt:425-442](app/src/main/java/com/example/ui/screens/FormSolveScreen.kt#L425-L442) | Back → "Save" skips required-field validation; if title is blank and no patient chosen it navigates away **without saving**, losing all filled answers. Fix: run the same validation as the main button; always save when any answer exists | High |
| [FormSolveScreen.kt:394](app/src/main/java/com/example/ui/screens/FormSolveScreen.kt#L394) | Save button not disabled while `saveEntry` runs → double tap creates two entries with different UIDs. Add an `isSaving` state (same button, disabled + spinner) | Medium |
| FormSolveScreen.kt:573-1286 | All 8 designer field types (`text`, `textarea`, `number`, `dropdown`, `radio_group`, `checkbox`, `checkbox_group`, `date`) have a renderer | OK |
| FormSolveScreen.kt:111 | Rule parsing uses `split("==")`; an option label containing `==` breaks the rule. Use `substringBefore/After("==")` (same stored format) | Low |
| [MainActivity.kt:41](app/src/main/java/com/example/MainActivity.kt#L41) | Current screen held in `remember` and no `configChanges` → rotating the phone mid-form or mid-design returns the user to Home. Use `rememberSaveable` for `currentScreenIndex` (answers already survive in the ViewModel) | High |
| [FormSolveScreen.kt:1025](app/src/main/java/com/example/ui/screens/FormSolveScreen.kt#L1025) | Procedure `checkbox_group`: every newly ticked choice defaults to `performedByMe = true`, even when `askIfPerformedByMe` is off → personal procedure logbook is inflated. Single procedure `checkbox` defaults to `false`. Make both default to `false` (or unset) and only set `true` when the user answers. Do not rewrite existing stored answers | High |
| FormSolveScreen.kt:1006-1030 vs VM L596-603, AiSummaryService L76-86 | Group procedures store `choices` + `choices_perf`, but `saveEntry` (→ `entry_answers.performedByMe`) and `normalizeLogs` read only top-level `performedByMe` → group procedures counted as "by others" and listed under the question label, not the choices. Read `choices_perf` in both places (ExportService already does) | Medium |
| ViewModel `updateProcedureAttribution` (never called) | No UI writes procedure attributions (provider type/name/ID, supervisor witness, verified). New entries always save `attributionsJson = {}`; the entry viewer (HomeScreen L3113, L3326) can only show old data. Either build the input inside the existing procedure card's expanded area or mark the feature as not available | Medium |
| FormSolveScreen.kt:575-589, L133-134 | Required `checkbox` can never block save: unticked stores `"false"` / `{"completed":false}`, which counts as answered. Treat `false` / `completed:false` as empty for required checks | Medium |
| [FormSolveScreen.kt:164](app/src/main/java/com/example/ui/screens/FormSolveScreen.kt#L164) | "Clear Answers" icon: no confirm, and it calls `clearSolveAnswers()` which also clears `editingEntryUid` → saving after a clear **creates a duplicate** instead of updating. Clear only answers/attributions, keep edit identity; add confirm | Medium |
| FormSolveScreen.kt:356 | `items(sectionFields)` has no `key` → per-card `remember` state (minimized, showAdvanced, dropdown expanded) jumps to other questions when conditional fields appear/disappear. Use `items(sectionFields, key = { it.uid })` | Medium |
| FormSolveScreen.kt:950, L1017, L1032 | `checkbox_group` values joined with `,` → an option label containing a comma splits into two bogus choices. Keep format for compatibility; block commas in new option labels in the designer | Low |
| FormSolveScreen.kt:755-784 | `number` accepts any text (paste, multiple dots, letters on some keyboards). Filter input to a valid decimal | Low |
| FormSolveScreen.kt:785, L813, L1286 | Dropdown / radio / date answers cannot be cleared once set (matters when a wrong answer reveals conditional fields). Add a "Clear" item inside the existing menu / field | Low |
| FormSolveScreen.kt:186 | Success card always says "Submitted" (edit UID is cleared before it renders). Capture `isEdit` before save | Low |
| FormSolveScreen.kt:1314-1318 | Date field is a disabled `OutlinedTextField` inside a clickable box → TalkBack announces it as disabled. Use `readOnly` + `interactionSource` click instead | Low |
| FormSolveScreen.kt:1082-1166 | 85 lines of commented-out dead code; delete | Low |
| [HomeScreen.kt:3412](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L3412) | Edit entry: `selectEntryForEdit` loads answers in a coroutine while the solve screen is already shown; anything typed in that first moment is overwritten when the load finishes. Load first, then navigate (or show a loading state in the same screen) | Low |
| HomeScreen.kt:1672, L1942 | New entry correctly calls `clearSolveAnswers()` first — no answer leakage between forms | OK |
| HomeScreen.kt:2611 | Delete log entry has a proper confirm dialog — keep as the pattern for other deletes | OK |


---

## Part 3 — Faults, ranked

### Critical (data loss / wrong clinical output)

**F1. Database wipes itself on any version change.**
[AppDatabase.kt:34](app/src/main/java/com/example/data/local/AppDatabase.kt#L34) uses `.fallbackToDestructiveMigration()`.
Fix: remove it. Set `exportSchema = true` and add `room { schemaDirectory("$projectDir/schemas") }` (Room Gradle plugin) or the KSP arg `room.schemaLocation`, then commit `schemas/.../3.json` as the frozen baseline. Removing the fallback does **not** change the schema. Any future schema change must ship a real `Migration(3, 4)` plus a migration test.

**F2. `REPLACE` + `CASCADE` deletes children.**
`insertFormTemplate`, `insertFormSection`, `insertFormField`, `insertFormFieldOption`, `insertFormEntry` all use `OnConflictStrategy.REPLACE` ([Daos.kt](app/src/main/java/com/example/data/local/Daos.kt)). In SQLite, REPLACE deletes the old row, and `ON DELETE CASCADE` fires. Affected:
- `updateSectionTitle`, `swapSectionOrders`, `moveSectionUp/Down` → wipe the section's fields and options.
- `restoreBackup` over an existing template → wipes its structure and all its entries.
- `saveEntry` on edit → cascades answers (currently harmless because they are rewritten).
Fix (no schema change): add `@Upsert` (Room 2.5+) or `@Update` DAO methods and use them for existing rows; keep `@Insert(REPLACE)` only for brand-new UIDs. Verify with an instrumented test that renaming a section keeps its fields.

**F3. Backup/restore is not a real backup.**
Export: add `sections`, `fields`, `options`, `answers` arrays to `generateJsonBackup`, bump `schemaVersion` to 2. Keep reading v1 files.
Restore: run inside `database.withTransaction { }`, insert in FK order (templates → sections → fields → options → patients → entries → answers), use upsert (not REPLACE) so existing children survive, validate before writing, report counts. Add a "merge vs replace" choice only inside the existing restore dialog.

**F4. Deleting a template silently deletes its log entries.**
Keep schema. In the existing delete confirm (or add one), show "This will also delete N log entries". Better: when entries exist, set `isActive = false` (archive) instead of deleting; hide inactive templates from the "new entry" picker only.

**F5. AI summary cannot read user fields and fabricates text.**
- Build a `fieldUid → label` map from `allFields` and pass labels (not UIDs) into `normalizeLogs`; classify by label, and include every answered field under its label.
- Remove every hardcoded clinical claim from `LocalOfflineProvider`; when data is missing print "Not recorded".
- Sort timeline by `createdAt` (Long), not `dateStr`.
- Show a one-time notice before sending patient data to Gemini; send the key via `x-goog-api-key` header instead of `?key=`.

**F13. Allergy information shown under the wrong label.**
Patient card "Drug Allergy Alert" shows `notes`; real allergies sit in `customAnswersJson["allergies"]` under a raw key ([HomeScreen.kt:1458](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L1458)). Display-only fix, no data change.

### High

- **F6. Fake sync and fake premium.** Hide nothing (layout is frozen), but make the Sync tile say "Cloud sync coming soon — use Export backup" instead of reporting success. Default tier to FREE or remove gating until billing exists; remove the free toggle from user-reachable UI.
- **F7. Custom intake field config not persisted.** Save `customIntakeFields` as JSON in `logimind_prefs` (new key `intake_fields_v1`); load in `init`.
- **F8. Activity context leaked into ViewModel.** [MainActivity.kt:35](app/src/main/java/com/example/MainActivity.kt#L35) passes `this`. Pass `applicationContext` (or use `AndroidViewModel`).
- **F9. No transactions.** Wrap `saveEntry`, `duplicateTemplate`, `saveTemplateDraft`, `restoreBackup` in `withTransaction` (needs `AppDatabase` access in the repository).
- **F10. Duplicate template breaks conditional logic.** Keep an `oldFieldUid → newFieldUid` map while copying and rewrite `conditionalLogicJson`.
- **F11. PHI exposure.** `allowBackup="true"` with empty rules backs up the DB and the Gemini key to Google Drive; exports stay in `cacheDir` forever. Add exclude rules for `sharedpref/logimind_prefs.xml` (key) and decide on DB backup explicitly (keeping DB backup helps phone-to-phone moves — ask the owner). Delete old export files from `cacheDir` on each export. Store the API key with `EncryptedSharedPreferences` **only after** migrating the existing value from `logimind_prefs`.
- **F14. Rotation throws the user out of the current screen.** `currentScreenIndex` in `remember` ([MainActivity.kt:41](app/src/main/java/com/example/MainActivity.kt#L41)). Use `rememberSaveable`.
- **F15. Designer writes on every keystroke.** Question label field saves + reloads the whole template per character ([TemplateDesignerScreen.kt:954](app/src/main/java/com/example/ui/screens/TemplateDesignerScreen.kt#L954)). Local state, save on focus loss.
- **F16. Procedure logbook inflated.** Checkbox-group choices default to "performed by me" ([FormSolveScreen.kt:1025](app/src/main/java/com/example/ui/screens/FormSolveScreen.kt#L1025)). New answers only; never rewrite stored ones.
- **F17. Back-dialog Save loses answers / skips validation** ([FormSolveScreen.kt:425-442](app/src/main/java/com/example/ui/screens/FormSolveScreen.kt#L425-L442)).
- **F12. Release build unhardened.** `isMinifyEnabled = false`. Enable R8 with keep rules for Room entities, Moshi codegen adapters and Retrofit interfaces, then test the release APK end-to-end.

### Medium

- `SimpleDateFormat` held in `object` singletons (not thread-safe) in `ExportService` and `AiSummaryService` → use `java.time` / `DateTimeFormatter` (needs desugaring on minSdk 24) or create per call.
- `restoreBackup` / `selectEntryForEdit` parse JSON with `getString` → one bad row aborts the whole restore. Use `opt*` and skip-with-report.
- `normalizeLogs` lowercases the key then looks it up (`answers.optString(key)`) → misses any mixed-case key.
- Export writes files on the main thread path callers choose; move PDF/ZIP generation to `Dispatchers.IO` with a progress state.
- CSV: prefix cells starting with `=,+,-,@` with `'`.
- Deleting a patient: confirm, and show "N entries keep this patient's name".
- Sample patients: seed only templates, or mark sample patients clearly.
- Process death (app killed in background) loses an unsaved form even after F14; autosave drafts (Part 4 item 2).

### Low

- `rootProject.name = "My Application"`, theme `Theme.MyApplication`: cosmetic, leave the theme name (changing it is safe but pointless).
- `GreetingScreenshotTest` and `ExampleInstrumentedTest` are template leftovers.
- `logging-interceptor` dependency unused; `okhttp` 4.10.0 and Compose BOM 2024.09.00 are old.
- Hardcoded English strings across screens; move to `strings.xml` with identical text.

---

## Part 4 — Improvements that keep layout and schema

1. Add a **pre-upgrade safety export**: on first launch of the new version (pref `upgraded_from_v1` absent), silently write a full v2 JSON backup to app-private storage before anything else runs.
2. Autosave form drafts to prefs (`draft_<templateUid>` JSON) every few seconds; offer "Resume draft" inside the existing solve screen header.
3. Validation: block save when required visible fields are empty; scroll to the first missing field (no layout change, just behaviour).
4. Search/filter entries by patient, template, date using new DAO queries (no schema change).
5. Undo snackbar for deletes of entries and patients (re-insert the captured rows).
6. PDF: include template name, patient MRN, page numbers, "Performed by me" summary table.
7. Procedure logbook stats: counts by procedure and by `performedByMe` from `entry_answers` (new DAO query).
8. Error states for AI and export (`Result` type instead of strings starting with emoji).
9. Accessibility: content descriptions on icon buttons; minimum 48 dp touch targets without moving elements.

---

## Part 5 — Work order and test gate

Do these in order. Each step is a separate PR.

1. **Freeze**: export Room schema `3.json` from the current code, commit it, add a unit test that fails if the schema hash changes.
2. **F1** remove destructive fallback. **F8** context fix. **F13** allergy label. **F14** rotation.
3. **F2** upsert DAOs + switch callers (incl. drag-reorder rewrite). **F15** designer keystroke saves. Instrumented test: rename/move/drag section keeps fields; edit entry keeps entry.
4. **F9** transactions. **F3** full backup v2 + safe restore. **F4** template delete warning/archive.
5. **F5** AI summary labels + no fabricated text. **F10** duplicate logic remap. **F7** intake persistence. **F16** procedure default. **F17** back-dialog save.
6. **F6** honest sync/premium. **F11** backup rules + cache cleanup. **F12** R8.
7. Screen fixes from the audit section, Medium/Low items, Part 4 improvements.

### Upgrade test (must pass before release)

1. Install the **old** APK (commit `2b28f0a`, same keystore). Create: 2 templates with conditional fields and procedure fields, 3 patients (one with allergies and a custom intake question), 5 entries using single and group procedure checkboxes, custom tile order, a Gemini key.
2. Install the **new** APK over it (`adb install -r`). Do not uninstall.
3. Check: all templates, sections, fields, options, patients, entries, answers, attributions, tile order and key are present and identical. Edit an entry and a section; nothing disappears.
4. Export v2 backup → uninstall → install new APK → restore → compare counts per table.
5. Restore a **v1** backup file from the old app; no crash, nothing existing deleted.
6. Release build (minified) passes steps 2–4.
