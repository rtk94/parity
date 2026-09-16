# Play Store listing material

Copy for the Google Play listing, plus the legal pages Play requires to
be publicly hosted. Build/upload mechanics live in
[`../DEPLOYMENT.md`](../DEPLOYMENT.md#android-release-builds-play-store).

## Which file is authoritative

Two documents exist in a draft and a filled-in pair. **The `-final`
version is the one to publish**; the unsuffixed sibling is the earlier
draft, kept for its margin notes on choices that were still open
(GDPR scoping, jurisdiction).

| Publish this | Superseded draft |
| --- | --- |
| `parity-privacy-policy-final.md` | `parity-privacy-policy.md` |
| `parity-terms-of-service-final.md` | `parity-terms-of-service.md` |

The remaining files have no draft/final split:

- `parity-description.md` — short + full store description
- `parity-closed-testing-description.md` — what testers are told
- `parity-data-safety-form.md` — answers for Play's Data safety form
- `parity-account-deletion-page.md` — the deletion-instructions page
  Play requires to be reachable without installing the app

## Before submitting

Each item below is still a `[FILL IN: …]` placeholder in the `-final`
documents. Play review rejects listings whose policy URLs 404 or whose
legal pages carry visible placeholder text.

- [ ] Host the privacy policy at a public URL
- [ ] Host the terms of service at a public URL
- [ ] Host the account-deletion page at a public URL
- [ ] Set the effective/"Last updated" date in both `-final` documents
- [ ] Replace the deletion-page and privacy-policy cross-links once the
      URLs above exist
- [ ] Decide the feedback channel quoted to closed testers
- [ ] Move the contact address off the personal Gmail to a domain
      address once that mail service exists (noted inline in the policy)
