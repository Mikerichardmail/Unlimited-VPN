# Unlimited VPN GitHub Pages Website

This directory contains the production-ready static website for **Unlimited VPN** designed for hosting via **GitHub Pages**.

## 🚀 Quick Deployment Guide

### Option 1: Automatic Deployment via GitHub Actions (Recommended)
1. Push this repository to GitHub on the `main` branch.
2. Go to your GitHub Repository -> **Settings** -> **Pages**.
3. Under **Build and deployment** -> **Source**, select **GitHub Actions**.
4. The workflow in `.github/workflows/deploy-gh-pages.yml` will automatically build and publish your website to `https://<your-username>.github.io/<repo-name>/`.

---

### Option 2: Classic Branch / Subdirectory Publishing
If you prefer not to use GitHub Actions:
1. Go to your GitHub Repository -> **Settings** -> **Pages**.
2. Under **Source**, select **Deploy from a branch**.
3. Choose `main` branch and `/website` or copy contents into a `/docs` folder or `gh-pages` branch.
4. Click **Save**.

---

## 📄 Pages Overview

- **`index.html`**: Main Landing Page (Hero banner, phone mockup, live ping simulator, features, pricing, FAQ).
- **`features.html`**: Technical comparison between WireGuard® and legacy OpenVPN, ChaCha20 encryption specs.
- **`pricing.html`**: Subscription pricing plans (Monthly ₹600, 6-Month ₹3,000, Annual ₹5,000) highlighting the **Premium Subscription**.
- **`privacy.html`**: Google Play Store compliant Privacy Policy with an explicit Data Safety disclosure.
- **`terms.html`**: Terms of Service and acceptable use rules.
- **`support.html`**: Android installation guide and troubleshooting center.
