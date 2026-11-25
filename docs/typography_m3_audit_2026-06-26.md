# Typography Audit: Current Implementation vs. M3 Guidelines
**Audit Date:** 2026-06-26
**Audited By:** AI (Antigravity)
**Status:** Open — Pending Implementation

---

## Audit Scope
- [Type.kt](../app/src/main/java/com/pocketfinancer/ui/theme/Type.kt) — centralized typography tokens
- [Theme.kt](../app/src/main/java/com/pocketfinancer/ui/theme/Theme.kt) — theme wiring
- [HomeScreen.kt](../app/src/main/java/com/pocketfinancer/ui/home/HomeScreen.kt) — inline overrides
- [TransactionsScreen.kt](../app/src/main/java/com/pocketfinancer/ui/transactions/TransactionsScreen.kt) — inline overrides
- [InsightsScreen.kt](../app/src/main/java/com/pocketfinancer/ui/insights/InsightsScreen.kt) — exemplary pattern
- [OnboardingScreen.kt](../app/src/main/java/com/pocketfinancer/ui/onboarding/OnboardingScreen.kt) — inline overrides
- [SettingsScreen.kt](../app/src/main/java/com/pocketfinancer/ui/settings/SettingsScreen.kt) — mixed pattern
- [SmsTelemetryViewer.kt](../app/src/main/java/com/pocketfinancer/ui/smsprocessing/SmsTelemetryViewer.kt) — current location of the telemetry viewer

**Reference:** [M3 Typography / Type Scale Tokens](https://m3.material.io/styles/typography/type-scale-tokens)

---

## 1. Official M3 Baseline Type Scale (Reference)

M3 defines 15 baseline type styles across 5 roles. For Android (Compose), `pt` maps directly to `sp`. Font families are categorized as **Brand** (display/geometric font) or **Plain** (text/readable font like Roboto).

| Token | Size | Line Height | Weight | Tracking | Font Class |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Display Large** | 57sp | 64sp | 400 | -0.25sp | Brand |
| **Display Medium** | 45sp | 52sp | 400 | 0 | Brand |
| **Display Small** | 36sp | 44sp | 400 | 0 | Brand |
| **Headline Large** | 32sp | 40sp | 400 | 0 | Brand |
| **Headline Medium** | 28sp | 36sp | 400 | 0 | Brand |
| **Headline Small** | 24sp | 32sp | 400 | 0 | Brand |
| **Title Large** | 22sp | 28sp | 400 | 0 | Brand |
| **Title Medium** | 16sp | 24sp | 500 | 0.15sp | Plain |
| **Title Small** | 14sp | 20sp | 500 | 0.1sp | Plain |
| **Body Large** | 16sp | 24sp | 400 | 0.5sp | Plain |
| **Body Medium** | 14sp | 20sp | 400 | 0.25sp | Plain |
| **Body Small** | 12sp | 16sp | 400 | 0.4sp | Plain |
| **Label Large** | 14sp | 20sp | 500 | 0.1sp | Plain |
| **Label Medium** | 12sp | 16sp | 500 | 0.5sp | Plain |
| **Label Small** | 11sp | 16sp | 500 | 0.5sp | Plain |

> **Note:** M3 also has 15 Emphasized variants (heavier weights: 500-700) for expressive moments. These are optional accent styles, not replacements for the baseline.

---

## 2. Current Implementation vs. M3: Token-by-Token Comparison

### Type.kt — PocketFinancerTypography

| Token | Current | M3 Spec | Deviations |
| :--- | :--- | :--- | :--- |
| **displayLarge** | 32sp / 40sp LH / Bold / Monospace / no tracking | 57sp / 64sp / 400 / Brand / -0.25sp | ⚠️ **Major**: Size 57→32, LH 64→40, weight 400→Bold, font Brand→Monospace |
| **titleLarge** | 18sp / 24sp LH / Bold / SansSerif / no tracking | 22sp / 28sp / 400 / Brand / 0 | Size 22→18, LH 28→24, weight 400→Bold |
| **titleMedium** | 14sp / 20sp LH / Bold / SansSerif / no tracking | 16sp / 24sp / 500 / Plain / 0.15sp | Size 16→14, LH 24→20, weight 500→Bold, missing tracking |
| **bodyMedium** | 14sp / 20sp LH / Medium(500) / SansSerif / no tracking | 14sp / 20sp / 400 / Plain / 0.25sp | Weight 400→500, missing 0.25sp tracking |
| **bodySmall** | 12sp / 16sp LH / Normal(400) / SansSerif / no tracking | 12sp / 16sp / 400 / Plain / 0.4sp | ✅ Closest match. Missing 0.4sp tracking |
| **labelLarge** | 12sp / 16sp LH / Bold / SansSerif / no tracking | 14sp / 20sp / 500 / Plain / 0.1sp | Size 14→12, LH 20→16, weight 500→Bold |
| **labelMedium** | 11sp / 14sp LH / SemiBold / Monospace / no tracking | 12sp / 16sp / 500 / Plain / 0.5sp | Size 12→11, LH 16→14, font Plain→Monospace, missing tracking |
| **labelSmall** | 10sp / 12sp LH / Bold / Monospace / no tracking | 11sp / 16sp / 500 / Plain / 0.5sp | Size 11→10, LH 16→12, weight 500→Bold, font Plain→Monospace |

### Missing Tokens (not defined — fall through to Compose/Roboto defaults)

- `displayMedium` (45sp), `displaySmall` (36sp)
- `headlineLarge` (32sp), `headlineMedium` (28sp), `headlineSmall` (24sp)
- `titleSmall` (14sp)
- `bodyLarge` (16sp)

---

## 3. Critical Issues Found

### 🔴 Issue 1: displayLarge is repurposed as a financial amount style
The current `displayLarge` (32sp/Bold/Monospace) is used as a "hero financial amount" token. In M3, `displayLarge` is the **largest text style** at 57sp — intended for short, impactful hero text on large screens. The app's "hero amount" at 32sp/40sp maps most closely to M3's `headlineLarge` (32sp/40sp/400).

- [ ] **TODO:** Realign displayLarge or create semantic aliases

### 🔴 Issue 2: Pervasive inline font size overrides (100+ occurrences)

| Screen | Inline `fontSize =` count | Non-standard sizes |
| :--- | :--- | :--- |
| HomeScreen.kt | 40+ | `8.sp`, `9.sp`, `9.5.sp`, `13.sp`, `16.sp`, `20.sp` |
| TransactionsScreen.kt | 25+ | `8.sp`, `9.sp`, `13.sp`, `18.sp`, `22.sp` |
| OnboardingScreen.kt | 10+ | `28.sp`, `30.sp` |
| TelemetryLogsViewer.kt | 15+ | `9.sp`, `11.sp`, `13.sp` |

Many use `.copy(fontSize = ...)` which takes a theme token and immediately overrides its size.

- [ ] **TODO:** Refactor inline overrides to use theme tokens (InsightsScreen pattern)

### 🔴 Issue 3: Sub-11sp font sizes violate M3 accessibility floor
M3's smallest token is `labelSmall` at **11sp**. The app uses:
- **8.sp** — HomeScreen.kt:1437, TransactionsScreen.kt:1294
- **9.sp** — 10+ occurrences across HomeScreen, TransactionsScreen, TelemetryLogsViewer
- **9.5.sp** — HomeScreen.kt:1403
- **10.sp** (current labelSmall) — still 1sp below M3's 11sp minimum

- [ ] **TODO:** Bump all text to minimum 11sp

### 🟡 Issue 4: No letter-spacing (tracking) defined in Type.kt
M3 defines specific `letterSpacing` for every token (-0.25sp to 0.5sp). Current Type.kt has **zero tracking**. Meanwhile 18+ inline `letterSpacing = 1.sp` scattered across screens is 2-4x higher than M3's maximum of 0.5sp.

- [ ] **TODO:** Add letterSpacing to all tokens in Type.kt, reduce inline overrides

### 🟡 Issue 5: Weight scale is consistently too heavy
M3 uses Regular(400) for Display/Headline/Body, Medium(500) for Title/Label. Current uses Bold(700) for almost everything — flattening the visual hierarchy.

- [ ] **TODO:** Adjust weights to M3 baseline or create intentional Emphasized variants

### 🟡 Issue 6: Font family conflicts with M3's Brand/Plain model
Monospace on `displayLarge`, `labelMedium`, `labelSmall` is an intentional brand choice for the financial aesthetic but conflicts with M3's Label expectations (readable plain font for small UI elements).

- [ ] **TODO:** Evaluate if Monospace on labels is hurting readability; consider semantic aliases instead

---

## 4. What InsightsScreen Gets Right (Exemplary Pattern)
- ✅ Zero inline `fontSize =` overrides
- ✅ Uses `MaterialTheme.typography.*` tokens throughout
- ✅ Applies `.copy(fontFamily = FontFamily.Monospace)` selectively for financial values
- ✅ Clean separation between content text and metadata

---

## 5. Recommendations (Tiered)

### Tier 1: High Priority (M3 Alignment + Accessibility)
- [ ] 1. Bump labelSmall minimum to 11sp; eliminate all sub-11sp font sizes
- [ ] 2. Add `letterSpacing` to every token in Type.kt per M3 values
- [ ] 3. Reduce inline letterSpacing from 1.sp to M3-appropriate values (0.1-0.5sp)
- [ ] 4. Refactor weight hierarchy: Display/Headline→Regular(400), Title/Label→Medium(500)

### Tier 2: Medium Priority (Consistency)
- [ ] 5. Align token sizes closer to M3: titleLarge→22sp, titleMedium→16sp, labelLarge→14sp
- [ ] 6. Define all 15 tokens in Type.kt explicitly
- [ ] 7. Refactor displayLarge or create semantic aliases for financial amounts

### Tier 3: Aspirational (Clean Architecture)
- [ ] 8. Eliminate inline fontSize overrides (follow InsightsScreen pattern)
- [ ] 9. Create semantic aliases (e.g., `val AmountHero = ...headlineLarge.copy(...)`)
- [ ] 10. Add lint rule to prevent new inline `fontSize =` declarations

---

## Changelog

| Date | Action | Status |
| :--- | :--- | :--- |
| 2026-06-26 | Initial audit completed | ✅ Done |
| — | Tier 1 implementation | ⬜ Pending |
| — | Tier 2 implementation | ⬜ Pending |
| — | Tier 3 implementation | ⬜ Pending |
