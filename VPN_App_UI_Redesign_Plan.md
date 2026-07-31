# VPN App — UI Redesign & Paywall-First Onboarding Plan

**Status:** App core is built (billing, VPN connect, backend). This plan covers the visual redesign + gated onboarding flow only.  
**Last Updated:** July 28, 2026

---

## 1. Goal

1. On first app launch, show subscription plans (monthly/6-month/annual) **before** any app access.
2. User can proceed only by: (a) paying for a plan, or (b) starting the 3-day free trial.
3. Give the whole app a modern, minimal visual identity — not a generic template look.

---

## 2. Design Direction (Token System)

**Grounding:** A VPN app's core promise is *quiet security* — invisible protection, not flashy tech. The design should feel calm, precise, and trustworthy rather than "hacker-green-on-black" (an overused VPN cliché) or generic SaaS blue.

**Signature element:** A single **connection pulse** — a soft, slow-breathing dot/ring around the connect button that's animated only when connected. This is the one moment of motion in the whole app; everything else stays still and disciplined.

### Color Palette
| Role | Hex | Use |
|------|-----|-----|
| Base (background) | `#0E1116` | Near-black, slightly blue-warm — main background |
| Surface | `#171B22` | Cards, sheets, elevated panels |
| Primary accent | `#4ADE80` → used sparingly | Connected state, success, active plan |
| Secondary accent | `#6C8CFF` | Interactive elements, links, selected plan border |
| Text primary | `#F3F5F7` | Headlines, body |
| Text muted | `#8B93A1` | Captions, secondary info |

*Avoid:* pure black (#000), neon acid-green-on-black cliché, cream/terracotta (overused AI-default palette).

### Typography
- **Display face:** Space Grotesk (or Söhne if licensing allows) — geometric, confident, used only for plan prices and headlines
- **Body face:** Inter — neutral, highly legible at small sizes for settings/legal text
- **Utility face:** JetBrains Mono — for server names, IPs, connection stats (gives a precise, technical feel exactly where it's earned)

### Layout Concept
- Full-bleed dark canvas, generous vertical spacing, one primary action per screen
- No shadows/gradients for depth — use flat elevation via subtle surface color shift only
- Rounded corners: 16dp for cards, 999dp (pill) for buttons — soft but not bubbly

---

## 3. Screen Flow (Paywall-First Onboarding)

```
App Launch
    │
    ▼
┌─────────────────────────────┐
│  Splash (logo, <1s)         │
└──────────┬───────────────────┘
           ▼
┌─────────────────────────────────────┐
│  Value Screen (1 of 2)               │
│  "Browse privately, anywhere."       │
│  — 3 short benefit bullets           │
│  [Continue]                          │
└──────────┬────────────────────────────┘
           ▼
┌─────────────────────────────────────┐
│  PAYWALL SCREEN (hard gate)          │
│                                       │
│  Plan cards (vertical stack):        │
│  ┌─────────────────────────────┐    │
│  │ Annual   ₹5,000/yr  BEST VALUE│   │
│  │ 6-Month  ₹3,000               │    │
│  │ Monthly  ₹600                 │    │
│  └─────────────────────────────┘    │
│                                       │
│  [Start 3-Day Free Trial]  ← primary │
│  [Subscribe Now]           ← secondary│
│                                       │
│  No access to app beyond this screen │
│  without one of the two actions above│
└──────────┬────────────────────────────┘
           │
     ┌─────┴──────┐
     ▼             ▼
 [Trial]      [Direct Purchase]
     │             │
     ▼             ▼
 Google Play Billing flow (both paths go through Play Billing —
 trial uses a free trial phase on the base plan, no separate SKU)
     │
     ▼
┌─────────────────────────────┐
│  Home / Connect Screen       │
│  (only screen reachable      │
│  after paywall passes)       │
└───────────────────────────────┘
```

**Rule:** Back button / swipe-away on the paywall screen should NOT allow bypass — no "skip" or "X" affordance anywhere on this screen. This is intentional and standard for subscription-gated apps (matches Play Store policy as long as the trial option is clearly free and easy to find).

---

## 4. Core Screens to Redesign

| Screen | Priority | Notes |
|--------|----------|-------|
| Paywall / plan selection | P0 | New screen — see flow above |
| Value/onboarding (1-2 slides) | P0 | New — sets up "why" before paywall |
| Home / Connect | P0 | Redesign existing — signature pulse animation lives here |
| Server picker | P1 | Redesign existing — use JetBrains Mono for server names/ping |
| Settings | P1 | Redesign existing — flat list, no unnecessary icons |
| Subscription management | P1 | Redesign existing — show current plan, renewal date, upgrade option |
| Connection stats / data usage | P2 | Redesign existing |

---

## 5. Paywall Enforcement Logic (App-Side)

```kotlin
// On every app launch, before showing Home screen:
val subscriptionStatus = CloudflareWorkerClient.getAccountStatus(userId)

when (subscriptionStatus) {
    is Active, is TrialActive -> navigateTo(HomeScreen)
    is Expired, is NeverStarted -> navigateTo(PaywallScreen) // hard block
}
```

- Paywall screen is the **start destination** in the nav graph whenever status is not `Active` or `TrialActive` — not just on first install, so lapsed subscribers see it again on next open.
- Trial button only shown if `trial_started_at` is null in Supabase (one trial per account, already tracked in your existing schema).

---

## 6. Implementation Checklist

**Design:**
- [ ] Build token system in Compose (`Color.kt`, `Type.kt`, `Shape.kt`)
- [ ] Design paywall screen mockup, review against "generic template" checklist below
- [ ] Design connection-pulse animation (Lottie or Compose `animateFloat`)
- [ ] Redesign Home/Connect screen around the pulse as focal point

**Build:**
- [ ] New `PaywallScreen.kt` composable
- [ ] New `OnboardingValueScreen.kt` (1-2 slides)
- [ ] Update nav graph: paywall as conditional start destination
- [ ] Wire trial button → existing trial creation flow (Worker `/api/purchase/trial`)
- [ ] Wire subscribe buttons → existing Play Billing flow per SKU
- [ ] Redesign `HomeScreen.kt`, `ServerPickerScreen.kt`, `SettingsScreen.kt` with new tokens

**QA:**
- [ ] Verify no way to reach Home without trial/purchase (fresh install test)
- [ ] Verify lapsed subscriber is routed back to paywall, not Home
- [ ] Test dark-mode-only rendering across all screens (no light mode needed if base is already dark)
- [ ] Accessibility: check contrast ratios on muted text (#8B93A1 on #0E1116 — verify ≥4.5:1)
- [ ] Reduced-motion setting disables the connection pulse animation

**Self-critique before shipping (generic-template check):**
- [ ] Does the paywall look like every other subscription app's paywall, or does the token system above actually show up in it?
- [ ] Is the pulse the *only* animated moment, or has motion crept in elsewhere?
- [ ] Cut one visual element from the busiest screen before calling it done (Chanel rule).

---

## 7. Copy Guidelines (Paywall Screen)

- Say what the user gets, not how the system works: "Unlimited data, every server" — not "Access to our OpenVPN infrastructure"
- Trial button label: **"Start 3-Day Free Trial"** — exact duration stated, no ambiguity
- Purchase button label: **"Subscribe — ₹{price}/{period}"** — price always visible on the button itself, never hidden
- No countdown pressure tactics ("Only 3 left!") — plain, honest, calm tone matches the security-product trust the app is selling
