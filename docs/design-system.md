# Design System — Typography & Spacing

Pocket Financer uses a dark Material 3 visual language balancing high-clarity
financial readability with precise technical structure.

Centralized definitions live in:

- [Type.kt](../app/src/main/java/com/pocketfinancer/ui/theme/Type.kt) — full
  15-token Material 3 baseline (`PocketFinancerTypography`).
- [AppTypography.kt](../app/src/main/java/com/pocketfinancer/ui/theme/AppTypography.kt) —
  domain-specific semantic aliases.
- [Theme.kt](../app/src/main/java/com/pocketfinancer/ui/theme/Theme.kt) — color
  scheme and theme wiring.

## 1. Dual-Font Pairing Rule

- **Sans-Serif (`FontFamily.SansSerif`)**: Descriptions, labels, navigation,
  headings, merchant names, and body copy.
- **Monospace (`FontFamily.Monospace`)**: Currency amounts, counts, account
  codes, timestamps, dates, performance metrics, and technical metadata tags.

Keep this pairing consistent across Home, Transactions, Review, Insights,
Onboarding, and Settings.

## 2. Core Typography Rules

1. **Zero inline typography overrides**: Every `Text()` composable must use
   either `AppTypography.*` or `MaterialTheme.typography.*`. Do not pass inline
   `fontSize`, `fontFamily`, `letterSpacing`, or `.copy(fontSize = ...)` in
   screens.
2. **Keep layout & color inline**: `color`, `textAlign`, `maxLines`, `overflow`,
   and `modifier` remain at the call site.
3. **11sp accessibility floor**: Never use text smaller than `11.sp`
   (`labelSmall` / `AppTypography.timestamp` / `AppTypography.eyebrow`).
4. **M3 tracking bounds**: Letter spacing stays within `-0.25.sp` to `0.5.sp`
   as defined in `Type.kt`.

## 3. Semantic Aliases (`AppTypography`)

Prefer `AppTypography.*` whenever text has a financial or structural role:

| Alias | Backing M3 Token | Size / Line Height | Weight | Font | Typical Usage |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `amountHero` | `headlineLarge` | `32.sp` / `40.sp` | Bold | Monospace | Home hero balance, transaction detail amount |
| `amountMedium` | `titleLarge` | `22.sp` / `28.sp` | Medium | Monospace | Insights totals, inflow/outflow summaries |
| `amountSmall` | `titleMedium` | `16.sp` / `24.sp` | Medium | Monospace | Transaction row amounts, card summary figures |
| `amountCompact` | `bodyMedium` | `14.sp` / `20.sp` | SemiBold | Monospace | Amounts inside chips, badges, compact rows |
| `accountCode` | `labelMedium` | `12.sp` / `16.sp` | Medium | Monospace | Account labels (`HDFC ••1234`), chip counts |
| `timestamp` | `labelSmall` | `11.sp` / `16.sp` | Medium | Monospace | Timestamps, dates, status stamps |
| `monoBody` | `bodySmall` | `12.sp` / `16.sp` | Normal | Monospace | Reference codes, monospaced excerpts |
| `monoBodyBold` | `bodySmall` | `12.sp` / `16.sp` | Bold | Monospace | Performance metrics, emphasized technical lines |
| `eyebrow` | `labelSmall` | `11.sp` / `16.sp` | Medium | Monospace | Uppercase section eyebrows and badges |
| `eyebrowBold` | `labelSmall` | `11.sp` / `16.sp` | Bold | Monospace | Active pipeline indicators, emphasized badges |
| `bodySmallBold` | `bodySmall` | `12.sp` / `16.sp` | Bold | Sans-Serif | Sender names, emphasized captions |
| `bodyMediumBold` | `bodyMedium` | `14.sp` / `20.sp` | Bold | Sans-Serif | Emphasized body lines |
| `screenHeader` | `titleLarge` | `22.sp` / `28.sp` | Bold | Sans-Serif | Top-level screen headers |
| `sectionHeading` | `titleMedium` | `16.sp` / `24.sp` | Medium | Sans-Serif | Card and section titles |
| `sectionHeadingBold` | `titleMedium` | `16.sp` / `24.sp` | Bold | Sans-Serif | Emphasized section titles and stat headers |
| `titleSmallBold` | `titleSmall` | `14.sp` / `20.sp` | Bold | Sans-Serif | Emphasized compact titles |
| `headlineLargeBold` | `headlineLarge` | `32.sp` / `40.sp` | Bold | Sans-Serif | Prominent hero headlines |
| `headlineMediumBold` | `headlineMedium` | `28.sp` / `36.sp` | Bold | Sans-Serif | Medium emphasized headlines |
| `headlineSmallBold` | `headlineSmall` | `24.sp` / `32.sp` | Bold | Sans-Serif | Small emphasized headlines |

## 4. Material 3 Baseline Scale (`PocketFinancerTypography`)

All 15 M3 tokens are defined explicitly in `Type.kt`:

| Role | Large | Medium | Small | Font Family |
| :--- | :--- | :--- | :--- | :--- |
| **Display** | `57sp / 64sp` (400, -0.25sp) | `45sp / 52sp` (400) | `36sp / 44sp` (400) | Sans-Serif |
| **Headline** | `32sp / 40sp` (400) | `28sp / 36sp` (400) | `24sp / 32sp` (400) | Sans-Serif |
| **Title** | `22sp / 28sp` (400) | `16sp / 24sp` (500, 0.15sp) | `14sp / 20sp` (500, 0.1sp) | Sans-Serif |
| **Body** | `16sp / 24sp` (400, 0.5sp) | `14sp / 20sp` (400, 0.25sp) | `12sp / 16sp` (400, 0.4sp) | Sans-Serif |
| **Label** | `14sp / 20sp` (500, 0.1sp) | `12sp / 16sp` (500, 0.5sp) | `11sp / 16sp` (500, 0.5sp) | `labelLarge` Sans-Serif; `labelMedium`/`labelSmall` Monospace |

## 5. Spacing Grid (8dp System)

Use an **8dp spacing grid** with a `4dp` sub-unit for tightly coupled pairs:

- **Outer screen horizontal margin**: `16.dp` on all top-level columns, lists,
  and headers.
- **Between cards**: `16.dp` vertical spacing (`Arrangement.spacedBy(16.dp)`).
- **Internal card padding**: `16.dp` on all sides.
- **Header to section content**: `12.dp` or `16.dp`.
- **Title to supporting text**: `8.dp`.
- **Label to value**: `4.dp`.
- **Edge-to-edge system bars**: Keep transparent dark system bar styling
  (`SystemBarStyle.dark`) and respect window insets so status/navigation bars
  remain readable across OS light/dark modes.
