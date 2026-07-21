# Product Design QA

- source visual truth path: `docs/design-qa/mobile-reference.png`
- implementation screenshot path: `docs/design-qa/xiagu-main-final.png`
- combined comparison evidence: `docs/design-qa/design-comparison.png`
- overlay evidence: `docs/design-qa/overlay-result-final-panel.png`
- viewport: source 375 x 812; implementation captured at 1080 x 2337 and normalized to 375 x 812
- state: dark mobile entry screen, then expanded landscape overlay after a completed portrait scan

## Findings

- No actionable P0, P1, or P2 differences remain. The Android screen intentionally replaces the website draft-entry content with native permission and floating-assistant setup, while retaining the source product's dark navy palette, blue/cyan accents, bold hierarchy, outlined cards, large tap targets, logo asset, and compact esports-dashboard density.
- P3: the native screen uses Android's sans fallback and omits the website's faint decorative grid. This does not reduce readability, brand recognition, or core task completion.

## Required fidelity surfaces

- Fonts and typography: bold display headings, compact uppercase eyebrow text, muted explanatory copy, and clear selected-state labels retain the source hierarchy. Chinese wrapping and line height remain readable at the normalized mobile viewport.
- Spacing and layout rhythm: 16 dp page gutters, consistent card padding, border radii, chip gaps, and 44-56 dp controls are aligned and do not overlap after system-bar insets were applied.
- Colors and visual tokens: navy background/panels, pale foreground, muted blue-gray copy, cyan status, blue active controls, amber warnings, and red destructive controls match the source semantics and maintain contrast.
- Image quality and asset fidelity: the visible app mark reuses the captured source logo at native scale; no placeholder, emoji, CSS drawing, or substitute illustration is used.
- Copy and content: permission, privacy, portrait recognition, lane-text fallback, abnormal-lineup, multi-position, and recommendation labels are concise and specific to the Android workflow.
- Interaction and accessibility: overlay authorization, entire-screen capture consent, lane chips, team-side switch, draggable/collapsible overlay, scan CTA, full-panel navigation, and stop action were exercised. Tap targets are at least 38 dp in the dense landscape overlay and 44 dp on the main screen.

## Focused comparison

The combined 750 x 812 comparison keeps the logo, headings, body copy, cards, chips, borders, and bottom navigation readable on both halves, so a separate crop was not required for source fidelity. The denser overlay result was checked independently in `docs/design-qa/overlay-result-final-panel.png` for wrapping, overflow, state colors, and recommendation hierarchy.

## Comparison history

1. P2: the first Android capture (`docs/design-qa/xiagu-main.png`) placed the header under Android 15's status bar. Fix: applied system-window insets to the root screen. Post-fix evidence: `docs/design-qa/xiagu-main-final.png` and `docs/design-qa/design-comparison.png`.
2. P2: the first landscape OCR fixture (`docs/design-qa/ocr-fixture-bubble.png`) clipped the lower hero rows. Fix: reduced fixture row height, vertical gaps, and heading height while preserving OCR-readable type. Post-fix evidence: `docs/design-qa/ocr-fixture-full.png`.
3. Final comparison: no P0/P1/P2 issues in the normalized entry screen or the completed overlay result.

## Primary interactions tested

- Public live metadata synchronized 131 heroes from the summit-top-1000 source mode.
- Android overlay and notification permissions were granted in the emulator.
- Entire-screen MediaProjection consent started a foreground capture service.
- Overlay bubble expanded in landscape, then hid for capture and returned after local recognition.
- The supplied 2559×1186 real BP screenshot detected the six actually selected portraits as 百里玄策、嫦娥、孙权、刘邦、韩信、少司缘; four empty pick slots were rejected.
- Nine occupied BAN slots were detected (seven unique heroes after legitimate duplicate bans); the single empty top-right BAN slot was rejected by the confidence and contrast gates.
- The central hero browser is a hard exclusion rectangle. Layout tests cover 16:9 through 20:9 and assert that none of the 20 recognition slots intersects it.
- The supplied enemy example ranked 庄周 first using live matchup, ally synergy, situational lift, and compressed tier scoring.
- Partial/non-standard team counts continued scoring without synthetic role filling; multi-position heroes were soft-weighted.
- Unit tests and APK assembly passed; Android runtime logs showed no fatal exception during the tested flow.

## Implementation checklist

- [x] Preserve the existing dark visual system and source logo.
- [x] Avoid status/navigation bar collisions at the mobile viewport.
- [x] Keep primary actions usable in portrait and landscape.
- [x] Verify portrait recognition, candidate-browser exclusion, BAN exclusion, abnormal counts, multi-position weighting, and recommendation output.
- [x] Verify 庄周 is recommendation number one for the supplied enemy lineup.

final result: passed
