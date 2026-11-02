# Tier 3: Inline Typography Override Elimination — Handoff Document
**Created:** 2026-06-26
**Purpose:** Comprehensive guide for the next session to systematically eliminate inline `fontSize`, `fontWeight`, `fontFamily`, and `letterSpacing` overrides across all screens, migrating them to use centralized `MaterialTheme.typography.*` tokens or `AppTypography.*` semantic aliases.

---

## Why This Matters

After Tier 1+2, the project now has:
- A fully M3-aligned [Type.kt](../app/src/main/java/com/pocketfinancer/ui/theme/Type.kt) with all 15 tokens, proper weights, letterSpacing, and lineHeight
- [AppTypography.kt](../app/src/main/java/com/pocketfinancer/ui/theme/AppTypography.kt) with semantic aliases like `amountHero`, `accountCode`, `screenHeader`

But **100+ inline overrides** across screens still bypass this system. These inline values:
1. **Cannot be updated centrally** — any future design changes require hunting through every screen
2. **Use sub-11sp sizes** (8sp, 9sp, 9.5sp) that violate M3 accessibility minimums
3. **Use inconsistent values** — the same visual concept uses different sizes on different screens
4. **Use `letterSpacing = 1.sp`** which is 2-4x larger than M3's maximum (0.5sp)
5. **Use `.copy(fontSize = ...)` on tokens** which defeats the purpose of using a token in the first place

The goal of Tier 3 is to make every `Text()` composable use either a raw M3 token (`MaterialTheme.typography.*`) or a semantic alias (`AppTypography.*`) **without any inline overrides**.

---

## Reference: Token → Use Case Mapping

When replacing inline styles, use this mapping to pick the right token/alias:

### Semantic Aliases (prefer these for domain-specific use cases)

| Alias | Backed By | Size | Font | Use For |
| :--- | :--- | :--- | :--- | :--- |
| `AppTypography.amountHero` | headlineLarge | 32sp | Mono+Bold | Primary balance, receipt detail amount |
| `AppTypography.amountMedium` | titleLarge | 22sp | Mono+Medium | Insights totals, category amounts |
| `AppTypography.amountSmall` | titleMedium | 16sp | Mono+Medium | Transaction list row amounts |
| `AppTypography.amountCompact` | bodyMedium | 14sp | Mono | Amounts in chips, dense lists |
| `AppTypography.accountCode` | labelMedium | 12sp | Mono+Medium | "HDFC A/c XX1234", account labels |
| `AppTypography.timestamp` | labelSmall | 11sp | Mono+Medium | "20:59", "SYNCED", date stamps |
| `AppTypography.monoBody` | bodySmall | 12sp | Mono | SMS excerpts, reference codes |
| `AppTypography.screenHeader` | titleLarge | 22sp | Sans+Bold | "pocketFinancer", "transactionLedger" |
| `AppTypography.sectionHeading` | titleMedium | 16sp | Sans+Medium | "Recent synced", "Cash Flow Summary" |

### Raw M3 Tokens (for generic UI text)

| Token | Size | Font | Use For |
| :--- | :--- | :--- | :--- |
| `titleLarge` | 22sp | Sans+Regular | Top app bar titles, dialog titles |
| `titleMedium` | 16sp | Sans+Medium | Section headers, card titles |
| `titleSmall` | 14sp | Sans+Medium | Sub-section headers |
| `bodyLarge` | 16sp | Sans+Regular | Primary body text, descriptions |
| `bodyMedium` | 14sp | Sans+Regular | Secondary body text, list item primary |
| `bodySmall` | 12sp | Sans+Regular | Supporting text, captions |
| `labelLarge` | 14sp | Sans+Medium | Buttons, action text, prominent labels |
| `labelMedium` | 12sp | Mono+Medium | Account codes (via alias: `accountCode`) |
| `labelSmall` | 11sp | Mono+Medium | Timestamps (via alias: `timestamp`) |

---

## Screen-by-Screen Plan

### Priority Order
1. **HomeScreen.kt** (40+ overrides, most complex)
2. **TransactionsScreen.kt** (25+ overrides, receipt detail view)
3. **OnboardingScreen.kt** (10+ overrides, large display text)
4. **TelemetryLogsViewer.kt** (15+ overrides, debug/telemetry view)
5. **SettingsScreen.kt** (mostly clean, some `.copy()` refinements)

Process for each screen: **Migrate → Build → Visually Verify → Commit**

---

### Screen 1: HomeScreen.kt
**File:** [HomeScreen.kt](../app/src/main/java/com/pocketfinancer/ui/home/HomeScreen.kt)
**Override count:** 40+
**Key issues:**

| Line(s) | Current | Replacement | Rationale |
| :--- | :--- | :--- | :--- |
| ~109 | `fontSize = 16.sp` | `style = MaterialTheme.typography.titleMedium` | 16sp maps to titleMedium |
| ~173 | `fontSize = 20.sp` | `style = AppTypography.amountMedium` | Secondary amount display (22sp close enough, or use titleLarge) |
| ~374 | `fontSize = 14.sp` | `style = MaterialTheme.typography.bodyMedium` | Primary list text |
| ~391 | `.labelMedium.copy(fontSize = 10.sp)` | `style = AppTypography.timestamp` | Was below M3 floor; timestamp alias at 11sp |
| ~614 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Metadata at M3 minimum |
| ~621 | `fontSize = 13.sp` | `style = MaterialTheme.typography.bodyMedium` | 13→14sp, minor bump via token |
| ~628 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Metadata |
| ~650 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Metadata |
| ~695 | `fontSize = 9.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX**: 9→11sp |
| ~708 | `fontSize = 11.sp` | `style = AppTypography.timestamp` | Already at M3 floor |
| ~714 | `fontSize = 9.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX**: 9→11sp |
| ~722 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~753 | `fontSize = 11.sp` | `style = AppTypography.timestamp` | Metadata |
| ~806 | `.bodySmall.copy(fontSize = 11.sp)` | `style = AppTypography.timestamp` or `labelSmall` | Use token directly |
| ~827 | `.labelLarge.copy(fontSize = 11.sp)` | `style = AppTypography.timestamp` | Use token directly |
| ~872 | `fontSize = 9.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX** |
| ~886 | `fontSize = 13.sp` | `style = MaterialTheme.typography.bodyMedium` | 13→14sp via token |
| ~893 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~914 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~949 | `fontSize = 9.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX** |
| ~956 | `fontSize = 13.sp` | `style = MaterialTheme.typography.bodyMedium` | 13→14sp via token |
| ~963 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~975 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~1020 | `fontSize = 14.sp` | `style = MaterialTheme.typography.bodyMedium` | Already at correct size |
| ~1027 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` | Already at correct size |
| ~1043 | `fontSize = 10.sp, letterSpacing = 1.sp` | `style = AppTypography.timestamp` | Fixes both size AND excessive tracking |
| ~1143 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~1166 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` | Match |
| ~1172 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~1180 | `fontSize = 11.sp` | `style = AppTypography.timestamp` | Metadata |
| ~1200 | `fontSize = 9.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX** |
| ~1222 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~1228 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~1250 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` | Match |
| ~1265 | `fontSize = 9.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX** |
| ~1286 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` | Match |
| ~1350 | `fontSize = 9.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX** |
| ~1392 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~1403 | `fontSize = 9.5.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX**: non-standard half-size |
| ~1437 | `fontSize = 8.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX**: lowest size in app |
| ~1465 | `fontSize = 9.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX** |

**Warning:** Many of these Text composables also have inline `fontWeight`, `fontFamily`, `color`, and other style properties. When migrating to a token, all inline style properties should be checked:
- If the inline `fontFamily` matches the token's font family, remove it
- If the inline `fontWeight` matches the token's weight, remove it
- `color` should remain inline (not part of typography tokens)

---

### Screen 2: TransactionsScreen.kt
**File:** [TransactionsScreen.kt](../app/src/main/java/com/pocketfinancer/ui/transactions/TransactionsScreen.kt)
**Override count:** 25+
**Already fixed:** Line 469 (displayLarge → AppTypography.amountHero)

| Line(s) | Current | Replacement | Rationale |
| :--- | :--- | :--- | :--- |
| ~422 | `fontSize = 22.sp` | `style = AppTypography.amountMedium` or `titleLarge` | Receipt header icon-adjacent text |
| ~431 | `.titleLarge.copy(fontSize = 20.sp)` | `style = AppTypography.screenHeader` | Near titleLarge's 22sp, use screenHeader for emphasis |
| ~441 | `.bodySmall.copy(fontSize = 13.sp)` | `style = MaterialTheme.typography.bodyMedium` | 13→14sp via bodyMedium token |
| ~503 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` or `labelMedium` | Context-dependent |
| ~525 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` | Match |
| ~550 | `fontSize = 10.sp, letterSpacing = 1.sp` | `style = AppTypography.timestamp` | **FIXES**: sub-11sp + excessive tracking |
| ~578 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` | Match |
| ~585 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` | Match |
| ~592 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` | Match |
| ~619 | `fontSize = 14.sp` | `style = MaterialTheme.typography.bodyMedium` | Match |
| ~637 | `fontSize = 18.sp` | `style = AppTypography.amountSmall` | Financial value (16sp close match) |
| ~643 | `fontSize = 10.sp, letterSpacing = 1.sp` | `style = AppTypography.timestamp` | **FIXES**: sub-11sp + excessive tracking |
| ~815 | `fontSize = 10.sp, letterSpacing = 1.sp` | `style = AppTypography.timestamp` | **FIXES**: sub-11sp + excessive tracking |
| ~839 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~844 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~855 | `fontSize = 12.sp` | `style = MaterialTheme.typography.bodySmall` | Match |
| ~1062 | `fontSize = 14.sp` | `style = MaterialTheme.typography.bodyMedium` | Match |
| ~1085 | `.labelMedium.copy(fontSize = 10.sp)` | `style = AppTypography.timestamp` | **FIXES**: token abuse |
| ~1094 | `.labelSmall.copy(fontSize = 9.sp)` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX**: 9→11sp |
| ~1279 | `fontSize = 9.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX** |
| ~1286 | `fontSize = 13.sp` | `style = MaterialTheme.typography.bodyMedium` | 13→14sp via token |
| ~1294 | `fontSize = 8.sp` | `style = AppTypography.timestamp` | **ACCESSIBILITY FIX**: 8→11sp |
| ~1306 | `fontSize = 11.sp` | `style = AppTypography.timestamp` | Metadata |
| ~1328 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |
| ~1334 | `fontSize = 10.sp` | `style = AppTypography.timestamp` | Below M3 floor |

---

### Screen 3: OnboardingScreen.kt
**File:** [OnboardingScreen.kt](../app/src/main/java/com/pocketfinancer/ui/onboarding/OnboardingScreen.kt)
**Override count:** 10+

Key patterns:
- **Large display text** (`28.sp`, `30.sp`): Should use `headlineMedium` (28sp) or `headlineLarge` (32sp)
- **`letterSpacing = 1.sp`**: Reduce to M3-appropriate 0-0.5sp range
- **`letterSpacing = 1.2.sp`**: Excessive — reduce to 0.5sp max
- **Small labels** with `letterSpacing = 0.5.sp`: Already M3-compliant ✓

---

### Screen 4: TelemetryLogsViewer.kt
**Current file:** [SmsTelemetryViewer.kt](../app/src/main/java/com/pocketfinancer/ui/smsprocessing/SmsTelemetryViewer.kt)
**Override count:** 15+

This is a developer/debug screen, so smaller text is somewhat justified. However:
- All `9.sp` should bump to `11.sp` minimum → `AppTypography.timestamp`
- `11.sp` entries → `AppTypography.timestamp` or `labelSmall`
- `13.sp` entries → `bodyMedium` (14sp)
- `letterSpacing = 1.sp` → reduce via token

---

### Screen 5: SettingsScreen.kt
**File:** [SettingsScreen.kt](../app/src/main/java/com/pocketfinancer/ui/settings/SettingsScreen.kt)
**Override count:** Minimal (mostly `.copy()` weight overrides)

This screen is mostly clean — it uses `MaterialTheme.typography.*` throughout. Key refinements:
- `.titleMedium.copy(fontWeight = FontWeight.SemiBold)` → evaluate if `titleMedium` alone (Medium weight) suffices
- `.bodySmall.copy(fontWeight = FontWeight.Bold)` → consider `labelLarge` instead (Medium weight, 14sp)
- `.labelSmall.copy(letterSpacing = 1.sp, ...)` → token already has 0.5sp tracking, remove inline
- No sub-11sp sizes ✓

---

## Execution Checklist Per Screen

For each screen, follow this process:

```
1. Open the screen file
2. Search for `fontSize =` — list all occurrences
3. Search for `.copy(` — list all token overrides
4. Search for `letterSpacing =` — list all inline tracking
5. For each occurrence:
   a. Determine the semantic purpose (amount? label? timestamp? body text?)
   b. Pick the matching token/alias from the mapping table above
   c. Replace the inline style with the token
   d. Remove redundant inline properties (fontWeight, fontFamily, letterSpacing)
   e. Keep non-typography properties (color, textAlign, maxLines, overflow)
6. Build: `.\gradlew.bat compileDebugKotlin`
7. Deploy to emulator and visually verify the screen
8. Commit with message: "refactor(typography): migrate [ScreenName] to M3 tokens"
```

---

## Visual Verification Checklist

After migrating each screen, verify:
- [ ] No text is clipped or overflowing its container
- [ ] Spacing between text elements looks proportional
- [ ] Financial amounts are still monospaced and visually distinct
- [ ] Timestamps and metadata are readable (11sp minimum)
- [ ] Screen headers have appropriate visual weight
- [ ] Filter chips/buttons are not too large or too small
- [ ] Overall visual hierarchy: headlines > titles > body > labels

---

## What NOT to Change

- **`color = ...`** — Colors are not part of typography tokens; keep inline
- **`textAlign = ...`** — Layout property; keep inline
- **`maxLines = ...`** / **`overflow = ...`** — Layout properties; keep inline
- **`modifier = ...`** — Not typography; keep as-is
- **Custom styles in non-UI code** — Don't touch ViewModel/Repository/etc.

---

## Known Risks & Mitigations

| Risk | Mitigation |
| :--- | :--- |
| Text overflow after size bump (e.g. 9sp→11sp) | Check container padding; may need minor padding adjustments |
| Visual density loss in compact areas | Consider using `bodySmall` (12sp) instead of `labelSmall` (11sp) if layout allows |
| Monospace tracking too wide at 0.5sp | If Monospace looks too spaced out, can override tracking to 0.25sp on specific aliases |
| Onboarding screen hero text too small/large | Test `headlineLarge` (32sp) vs `headlineMedium` (28sp) and pick the better fit |
