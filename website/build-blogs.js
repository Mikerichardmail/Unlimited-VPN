const fs = require('fs');
const path = require('path');

const seoCatalog = JSON.parse(fs.readFileSync(path.join(__dirname, 'seo-metadata.json'), 'utf8'));
const SITE_URL = seoCatalog.website.baseUrl;
const PLAY_STORE_URL = seoCatalog.website.googlePlayUrl;
const APP_NAME = seoCatalog.website.name;

const allPosts = seoCatalog.blogPosts.map(p => {
  const slug = p.slug;
  const title = p.title;

  let sections = [];
  let faqs = [];
  let excerpt = p.metaDescription;

  const contentFilePath = path.join(__dirname, 'content', `${slug}.json`);
  if (fs.existsSync(contentFilePath)) {
    try {
      const generatedContent = JSON.parse(fs.readFileSync(contentFilePath, 'utf8'));
      sections = generatedContent.sections || [];
      faqs = generatedContent.faqs || [];
      if (generatedContent.summary) {
        excerpt = generatedContent.summary;
      }
    } catch (e) {
      console.error(`Error parsing ${contentFilePath}:`, e.message);
    }
  }

  // Fallback if not generated yet
  if (sections.length === 0) {
    sections = [
      {
        heading: `Understanding ${title}`,
        content: `<p>Choosing the right cybersecurity tools and understanding technical concepts is essential for maintaining your digital privacy and network performance in 2026.</p>
        <p>In this guide, we break down the key mechanisms, comparative advantages, and practical applications of this technology for mobile and desktop users.</p>`
      },
      {
        heading: "Key Architecture & Technical Breakdown",
        content: `<p>Modern networking requires a balance between ironclad cryptographic security and high-throughput data delivery. With protocols like <strong>WireGuard®</strong> and next-generation routing, users no longer need to sacrifice speed for privacy.</p>
        <ul>
          <li><strong>State-of-the-Art Encryption:</strong> Fixed cryptographic primitives minimize computational overhead.</li>
          <li><strong>Audited Security:</strong> Lean codebases ensure transparency and zero hidden vulnerabilities.</li>
          <li><strong>Seamless Mobile Roaming:</strong> Instant reconnects across cellular towers and Wi-Fi networks.</li>
        </ul>`
      }
    ];
    faqs = [
      { q: `What is the most important thing to know about ${title}?`, a: "Using an audited, modern WireGuard® VPN ensures your traffic remains completely private, unthrottled, and secure across all Android networks." }
    ];
  }

  return {
    ...p,
    readTime: "5 min read",
    date: "August 2026",
    author: "Cybersecurity Research Team",
    excerpt: excerpt,
    sections,
    faqs
  };
});

function generateSchema(post) {
  const schema = {
    "@context": "https://schema.org",
    "@type": "BlogPosting",
    "headline": post.title,
    "description": post.metaDescription,
    "keywords": post.keywords.join(', '),
    "datePublished": "2026-08-01T00:00:00+00:00",
    "dateModified": "2026-08-25T00:00:00+00:00",
    "author": {
      "@type": "Organization",
      "name": "Unlimited VPN Research Team",
      "url": SITE_URL
    },
    "publisher": {
      "@type": "Organization",
      "name": "Unlimited VPN",
      "logo": {
        "@type": "ImageObject",
        "url": `${SITE_URL}/favicon.ico`
      }
    },
    "mainEntityOfPage": {
      "@type": "WebPage",
      "@id": `${SITE_URL}/blog/${post.slug}`
    }
  };

  if (post.faqs && post.faqs.length > 0) {
    schema["mainEntity"] = post.faqs.map(f => ({
      "@type": "Question",
      "name": f.q,
      "acceptedAnswer": {
        "@type": "Answer",
        "text": f.a
      }
    }));
  }

  return JSON.stringify(schema, null, 2);
}

function generatePostHtml(post, relatedPosts) {
  const tocHtml = post.sections.map((sec, idx) => `
    <li class="blog-toc-item">
      <a href="#sec-${idx + 1}">${sec.heading}</a>
    </li>
  `).join('');

  const sectionsHtml = post.sections.map((sec, idx) => `
    <h2 id="sec-${idx + 1}">${sec.heading}</h2>
    ${sec.content}
  `).join('');

  const faqsHtml = (post.faqs && post.faqs.length > 0) ? `
    <h2>Frequently Asked Questions</h2>
    <div class="faq-list" style="margin-top: 20px;">
      ${post.faqs.map(f => `
        <div class="blog-callout info" style="flex-direction:column; gap:8px;">
          <strong style="color:var(--primary); font-size:1.05rem;">Q: ${f.q}</strong>
          <p style="margin-bottom:0; color:#e2e8f0;">${f.a}</p>
        </div>
      `).join('')}
    </div>
  ` : '';

  const relatedCardsHtml = relatedPosts.map(rel => `
    <a href="${rel.slug}" class="blog-card">
      <div>
        <span class="blog-card-badge cat-${rel.catSlug}">${rel.category}</span>
        <h3 class="blog-card-title" style="font-size:1.1rem;">${rel.title}</h3>
        <p class="blog-card-excerpt" style="-webkit-line-clamp: 2;">${rel.metaDescription}</p>
      </div>
      <div class="blog-card-meta">
        <span>${rel.readTime}</span>
        <span class="blog-card-readmore">Read Article →</span>
      </div>
    </a>
  `).join('');

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${post.metaTitle}</title>
  <meta name="description" content="${post.metaDescription}">
  <meta name="keywords" content="${post.keywords.join(', ')}">
  <meta name="robots" content="index, follow, max-snippet:-1, max-image-preview:large, max-video-preview:-1">
  <link rel="canonical" href="${post.canonical}">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=Outfit:wght@400;600;700;800&display=swap" rel="stylesheet">
  <link rel="stylesheet" href="../css/styles.css">
  <link rel="icon" href="data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 100%22><text y=%22.9em%22 font-size=%2290%22>⚡</text></svg>">
  
  <!-- OpenGraph / Social Media Meta Tags -->
  <meta property="og:locale" content="en_US">
  <meta property="og:type" content="article">
  <meta property="og:title" content="${post.metaTitle}">
  <meta property="og:description" content="${post.metaDescription}">
  <meta property="og:url" content="${post.canonical}">
  <meta property="og:site_name" content="Unlimited VPN">
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="${post.metaTitle}">
  <meta name="twitter:description" content="${post.metaDescription}">

  <!-- Structured Data JSON-LD -->
  <script type="application/ld+json">
${generateSchema(post)}
  </script>
</head>
<body>

  <!-- Navigation Bar -->
  <nav class="navbar">
    <div class="container">
      <a href="../index" class="nav-brand">
        <div class="brand-icon">
          <svg viewBox="0 0 24 24"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg>
        </div>
        <span>Unlimited<span class="gradient-text">VPN</span></span>
      </a>

      <ul class="nav-links" id="navLinks">
        <li><a href="../index">Home</a></li>
        <li><a href="../features">Features</a></li>
        <li><a href="../pricing">Pricing</a></li>
        <li><a href="../blog" class="active">Blog</a></li>
        <li><a href="../support">Support</a></li>
        <li><a href="../privacy">Privacy</a></li>
        <li><a href="../terms">Terms</a></li>
      </ul>

      <div class="nav-actions">
        <a href="${PLAY_STORE_URL}" target="_blank" class="btn btn-primary btn-sm">Get on Google Play</a>
        <button class="mobile-toggle" id="mobileToggle" aria-label="Toggle Navigation">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 6h16M4 12h16M4 18h16"/></svg>
        </button>
      </div>
    </div>
  </nav>

  <!-- Main Article Section -->
  <main class="blog-post-page">
    <div class="container">
      
      <!-- Breadcrumb Navigation -->
      <nav class="blog-breadcrumb" aria-label="Breadcrumb">
        <a href="../index">Home</a>
        <span class="blog-breadcrumb-separator">/</span>
        <a href="../blog">Blog</a>
        <span class="blog-breadcrumb-separator">/</span>
        <a href="../blog?category=${post.catSlug}">${post.category}</a>
        <span class="blog-breadcrumb-separator">/</span>
        <span style="color: var(--primary);">${post.title}</span>
      </nav>

      <!-- Article Header -->
      <header class="blog-article-hero">
        <span class="blog-card-badge cat-${post.catSlug}" style="margin-bottom: 16px;">${post.category}</span>
        <h1 class="blog-article-title">${post.title}</h1>
        <div class="blog-meta-bar">
          <div class="blog-meta-item">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
            <span>${post.readTime}</span>
          </div>
          <div class="blog-meta-item">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
            <span>Updated ${post.date}</span>
          </div>
          <div class="blog-meta-item">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
            <span>${post.author}</span>
          </div>
        </div>
      </header>

      <!-- Two Column Layout: Prose + Sticky Sidebar -->
      <div class="blog-content-layout">
        
        <article class="blog-prose">
          
          <div class="blog-callout tip" style="margin-top:0;">
            <div class="blog-callout-icon">⚡</div>
            <div>
              <strong>Quick Summary:</strong> ${post.metaDescription}
            </div>
          </div>

          ${sectionsHtml}

          ${faqsHtml}

          <!-- In-Content Play Store CTA Card -->
          <div class="blog-cta-banner">
            <div class="blog-cta-content">
              <h3>Protect Your Android Phone with Unlimited VPN</h3>
              <p>Experience ultra-fast WireGuard® speeds, zero bandwidth throttling, and ironclad privacy with our Premium Subscription.</p>
            </div>
            <a href="${PLAY_STORE_URL}" target="_blank" class="btn btn-primary" style="white-space:nowrap;">Download Free</a>
          </div>

          <!-- Social Share Bar -->
          <div class="blog-share-box">
            <span class="blog-share-title">Share this article:</span>
            <button class="share-btn" onclick="navigator.clipboard.writeText(window.location.href); alert('Article URL copied to clipboard!');">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
              Copy Link
            </button>
            <a class="share-btn" href="https://twitter.com/intent/tweet?text=${encodeURIComponent(post.title)}&url=${encodeURIComponent(post.canonical)}" target="_blank">Twitter / X</a>
            <a class="share-btn" href="https://api.whatsapp.com/send?text=${encodeURIComponent(post.title + ' ' + post.canonical)}" target="_blank">WhatsApp</a>
          </div>

          <!-- Author Box -->
          <div class="blog-author-box">
            <div class="author-avatar">⚡</div>
            <div class="author-info">
              <h4>Written by Unlimited VPN Research Team</h4>
              <p>Specialized cybersecurity researchers and Android engineers focused on next-generation networking, WireGuard® protocol optimization, and digital privacy rights.</p>
            </div>
          </div>

        </article>

        <!-- Sidebar -->
        <aside class="blog-sidebar">
          
          <div class="blog-toc-card">
            <h4>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
              Table of Contents
            </h4>
            <ul class="blog-toc-list">
              ${tocHtml}
            </ul>
          </div>

          <div class="sidebar-cta-card">
            <h4>Get Unlimited VPN</h4>
            <p>100% No-Logs, 10Gbps WireGuard® server nodes across the globe.</p>
            <a href="${PLAY_STORE_URL}" target="_blank" class="btn btn-primary btn-sm" style="width:100%;">Install on Android</a>
          </div>

        </aside>

      </div>

      <!-- Related Posts Section -->
      <section class="related-posts-section">
        <h3>Related Articles & Guides</h3>
        <div class="blog-grid" style="margin-top:20px;">
          ${relatedCardsHtml}
        </div>
      </section>

    </div>
  </main>

  <!-- Footer -->
  <footer class="footer">
    <div class="container">
      <div class="footer-grid">
        <div class="footer-brand">
          <a href="../index" class="nav-brand">
            <div class="brand-icon">
              <svg viewBox="0 0 24 24"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg>
            </div>
            <span>Unlimited<span class="gradient-text">VPN</span></span>
          </a>
          <p>Ultra-fast WireGuard® VPN built for privacy, low ping gaming, and unthrottled streaming on Android.</p>
        </div>

        <div class="footer-col">
          <h4>Navigation</h4>
          <ul class="footer-links">
            <li><a href="../index">Home</a></li>
            <li><a href="../features">Features</a></li>
            <li><a href="../pricing">Pricing</a></li>
            <li><a href="../blog">Blog</a></li>
            <li><a href="../support">Support & Setup</a></li>
          </ul>
        </div>

        <div class="footer-col">
          <h4>Legal & Compliance</h4>
          <ul class="footer-links">
            <li><a href="../privacy">Privacy Policy</a></li>
            <li><a href="../terms">Terms of Service</a></li>
            <li><a href="../sitemap.xml" target="_blank">Sitemap</a></li>
          </ul>
        </div>

        <div class="footer-col">
          <h4>Android App</h4>
          <ul class="footer-links">
            <li><a href="${PLAY_STORE_URL}" target="_blank">Google Play Store</a></li>
            <li><a href="../pricing">Premium Subscription</a></li>
          </ul>
        </div>
      </div>

      <div class="footer-bottom">
        <span>© 2026 Unlimited VPN. All rights reserved. WireGuard® is a registered trademark of Jason A. Donenfeld.</span>
        <span>Google Play and the Google Play logo are trademarks of Google LLC.</span>
      </div>
    </div>
  </footer>

  <script src="../js/main.js"></script>
  <script src="../js/blog.js"></script>
</body>
</html>`;
}

function generateBlogHubHtml(isSubdirectory = false) {
  const relPath = isSubdirectory ? '..' : '.';
  const postLinkPrefix = isSubdirectory ? '' : 'blog/';

  const categories = [
    { name: "All Topics", slug: "all", count: allPosts.length },
    { name: "Guides & Basics", slug: "general", count: allPosts.filter(p => p.catSlug === "general").length },
    { name: "Android VPN", slug: "android", count: allPosts.filter(p => p.catSlug === "android").length },
    { name: "Privacy & Security", slug: "privacy", count: allPosts.filter(p => p.catSlug === "privacy").length },
    { name: "Speed & Gaming", slug: "gaming", count: allPosts.filter(p => p.catSlug === "gaming").length },
    { name: "Streaming", slug: "streaming", count: allPosts.filter(p => p.catSlug === "streaming").length },
    { name: "Travel & Wi-Fi", slug: "travel", count: allPosts.filter(p => p.catSlug === "travel").length },
    { name: "Protocols & Tech", slug: "technical", count: allPosts.filter(p => p.catSlug === "technical").length }
  ];

  const featuredPost = allPosts[0];

  const initialCardsHtml = allPosts.map(post => `
    <a href="${postLinkPrefix}${post.slug}" class="blog-card" data-category="${post.catSlug}" data-title="${post.title.toLowerCase()}" data-excerpt="${post.metaDescription.toLowerCase()}">
      <div>
        <span class="blog-card-badge cat-${post.catSlug}">${post.category}</span>
        <h3 class="blog-card-title">${post.title}</h3>
        <p class="blog-card-excerpt">${post.metaDescription}</p>
      </div>
      <div class="blog-card-meta">
        <span>${post.readTime}</span>
        <span class="blog-card-readmore">Read Guide →</span>
      </div>
    </a>
  `).join('');

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>VPN Knowledge Hub & Android Tutorials - Unlimited VPN Blog</title>
  <meta name="description" content="Explore 100+ expert guides on Android VPN setup, WireGuard protocol, online privacy, DNS leak prevention, gaming ping optimization, and public Wi-Fi security.">
  <meta name="keywords" content="vpn blog, android vpn guides, vpn tutorials, cybersecurity blog, wireguard guides, online privacy tips">
  <meta name="robots" content="index, follow, max-snippet:-1, max-image-preview:large, max-video-preview:-1">
  <link rel="canonical" href="${SITE_URL}/blog.html">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=Outfit:wght@400;600;700;800&display=swap" rel="stylesheet">
  <link rel="stylesheet" href="${relPath}/css/styles.css">
  <link rel="icon" href="data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 100%22><text y=%22.9em%22 font-size=%2290%22>⚡</text></svg>">
  
  <meta property="og:locale" content="en_US">
  <meta property="og:type" content="blog">
  <meta property="og:title" content="VPN Knowledge Hub & Android Tutorials - Unlimited VPN Blog">
  <meta property="og:description" content="Explore 100+ expert guides on Android VPN setup, WireGuard protocol, online privacy, DNS leak prevention, gaming ping optimization, and public Wi-Fi security.">
  <meta property="og:url" content="${SITE_URL}/blog.html">
  <meta property="og:site_name" content="Unlimited VPN">
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="VPN Knowledge Hub & Android Tutorials - Unlimited VPN Blog">
  <meta name="twitter:description" content="Explore 100+ expert guides on Android VPN setup, WireGuard protocol, online privacy, DNS leak prevention, gaming ping optimization, and public Wi-Fi security.">
</head>
<body>

  <!-- Navigation Bar -->
  <nav class="navbar">
    <div class="container">
      <a href="${relPath}/index" class="nav-brand">
        <div class="brand-icon">
          <svg viewBox="0 0 24 24"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg>
        </div>
        <span>Unlimited<span class="gradient-text">VPN</span></span>
      </a>

      <ul class="nav-links" id="navLinks">
        <li><a href="${relPath}/index">Home</a></li>
        <li><a href="${relPath}/features">Features</a></li>
        <li><a href="${relPath}/pricing">Pricing</a></li>
        <li><a href="${relPath}/blog" class="active">Blog</a></li>
        <li><a href="${relPath}/support">Support</a></li>
        <li><a href="${relPath}/privacy">Privacy</a></li>
        <li><a href="${relPath}/terms">Terms</a></li>
      </ul>

      <div class="nav-actions">
        <a href="${PLAY_STORE_URL}" target="_blank" class="btn btn-primary btn-sm">Get on Google Play</a>
        <button class="mobile-toggle" id="mobileToggle" aria-label="Toggle Navigation">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 6h16M4 12h16M4 18h16"/></svg>
        </button>
      </div>
    </div>
  </nav>

  <!-- Blog Header & Controls -->
  <header class="blog-header">
    <div class="container">
      <div class="hero-badge">
        <span class="pulse-dot"></span>
        100+ Expert Guides & Tutorials
      </div>
      <h1 class="hero-title" style="font-size: clamp(2.2rem, 5vw, 3.4rem); margin-top: 16px;">
        VPN & Cybersecurity <br>
        <span class="gradient-text">Knowledge Hub</span>
      </h1>
      <p class="hero-subtitle" style="max-width: 680px; margin: 16px auto 0;">
        Master online privacy, speed optimization, WireGuard® cryptography, and step-by-step Android VPN setups.
      </p>

      <!-- Instant Search Bar -->
      <div class="blog-search-wrapper">
        <svg class="blog-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="text" id="blogSearchInput" class="blog-search-input" placeholder="Search guides (e.g. WireGuard, Fix disconnect, Gaming ping, No-logs)...">
      </div>

      <!-- Category Filter Chips -->
      <div class="blog-categories" id="categoryFilters">
        ${categories.map(c => `
          <button class="category-chip ${c.slug === 'all' ? 'active' : ''}" data-filter="${c.slug}">
            <span>${c.name}</span>
            <span class="chip-count">${c.count}</span>
          </button>
        `).join('')}
      </div>
    </div>
  </header>

  <!-- Content Section -->
  <main class="section" style="padding-top: 10px;">
    <div class="container">
      
      <!-- Featured Post Card -->
      <div class="featured-post-card" id="featuredPostCard">
        <div>
          <span class="featured-badge">🔥 Featured Cornerstone Guide</span>
          <h2 style="font-size: 1.8rem; margin-bottom: 12px; color: #fff;">${featuredPost.title}</h2>
          <p style="color: var(--text-muted); font-size: 1rem; line-height: 1.6; margin-bottom: 24px;">
            ${featuredPost.metaDescription}
          </p>
          <a href="${postLinkPrefix}${featuredPost.slug}" class="btn btn-primary">Read Full Guide →</a>
        </div>
        <div style="display:flex; flex-direction:column; justify-content:center; background: rgba(0,240,255,0.03); border:1px solid var(--border-glass); border-radius: var(--radius-md); padding: 24px;">
          <strong style="color: var(--primary); font-size: 1.1rem; margin-bottom: 8px;">Key Takeaways:</strong>
          <ul style="color: var(--text-muted); font-size: 0.92rem; padding-left: 20px; line-height: 1.7;">
            <li>How ChaCha20 encryption safeguards your Android device</li>
            <li>Eliminating ISP throttling and tracking</li>
            <li>Zero battery drain with native WireGuard® kernels</li>
          </ul>
        </div>
      </div>

      <!-- Live Results Counter & Grid -->
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
        <h2 style="font-size: 1.4rem; color: #fff;" id="sectionHeading">All Articles</h2>
        <span style="color: var(--text-dim); font-size: 0.9rem;" id="resultsCount">Showing ${allPosts.length} articles</span>
      </div>

      <div class="blog-grid" id="blogCardsGrid">
        ${initialCardsHtml}
      </div>

      <div id="noResultsMessage" style="display:none; text-align:center; padding: 60px 20px;">
        <h3 style="color: var(--text-muted); margin-bottom: 10px;">No articles found matching your query</h3>
        <p style="color: var(--text-dim);">Try searching with different keywords or clearing your filters.</p>
        <button class="btn btn-secondary btn-sm" style="margin-top:16px;" onclick="document.getElementById('blogSearchInput').value=''; document.querySelector('.category-chip[data-filter=all]').click();">Reset Filters</button>
      </div>

    </div>
  </main>

  <!-- Footer -->
  <footer class="footer">
    <div class="container">
      <div class="footer-grid">
        <div class="footer-brand">
          <a href="${relPath}/index" class="nav-brand">
            <div class="brand-icon">
              <svg viewBox="0 0 24 24"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg>
            </div>
            <span>Unlimited<span class="gradient-text">VPN</span></span>
          </a>
          <p>Ultra-fast WireGuard® VPN built for privacy, low ping gaming, and unthrottled streaming on Android.</p>
        </div>

        <div class="footer-col">
          <h4>Navigation</h4>
          <ul class="footer-links">
            <li><a href="${relPath}/index">Home</a></li>
            <li><a href="${relPath}/features">Features</a></li>
            <li><a href="${relPath}/pricing">Pricing</a></li>
            <li><a href="${relPath}/blog">Blog</a></li>
            <li><a href="${relPath}/support">Support & Setup</a></li>
          </ul>
        </div>

        <div class="footer-col">
          <h4>Legal & Compliance</h4>
          <ul class="footer-links">
            <li><a href="${relPath}/privacy">Privacy Policy</a></li>
            <li><a href="${relPath}/terms">Terms of Service</a></li>
            <li><a href="${relPath}/sitemap.xml" target="_blank">Sitemap</a></li>
          </ul>
        </div>

        <div class="footer-col">
          <h4>Android App</h4>
          <ul class="footer-links">
            <li><a href="${PLAY_STORE_URL}" target="_blank">Google Play Store</a></li>
            <li><a href="${relPath}/pricing">Premium Subscription</a></li>
          </ul>
        </div>
      </div>

      <div class="footer-bottom">
        <span>© 2026 Unlimited VPN. All rights reserved. WireGuard® is a registered trademark of Jason A. Donenfeld.</span>
        <span>Google Play and the Google Play logo are trademarks of Google LLC.</span>
      </div>
    </div>
  </footer>

  <script src="${relPath}/js/main.js" defer></script>
  <script src="${relPath}/js/blog.js" defer></script>
</body>
</html>`;
}

function generateSitemapXml() {
  const currentDate = new Date().toISOString().split('T')[0];
  const staticPages = seoCatalog.corePages;

  let xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.sitemaps.org/schemas/sitemap/0.9
        http://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd">

    <!-- Core Website Pages -->
${staticPages.map(p => `    <url>
        <loc>${p.canonical}</loc>
        <lastmod>${currentDate}</lastmod>
        <changefreq>${p.changefreq}</changefreq>
        <priority>${p.priority}</priority>
    </url>`).join('\n')}

    <!-- 100 Blog Article Pages -->
${allPosts.map(p => `    <url>
        <loc>${p.canonical}</loc>
        <lastmod>${currentDate}</lastmod>
        <changefreq>${p.changefreq}</changefreq>
        <priority>${p.priority}</priority>
    </url>`).join('\n')}

</urlset>
`;
  return xml;
}

function build() {
  console.log(`Building Unlimited VPN Blog System (${allPosts.length} posts with enhanced SEO metadata)...App`);
  
  const websiteDir = __dirname;
  const blogDir = path.join(websiteDir, 'blog');
  const jsDir = path.join(websiteDir, 'js');

  if (!fs.existsSync(blogDir)) {
    fs.mkdirSync(blogDir, { recursive: true });
  }

  fs.writeFileSync(path.join(blogDir, 'index.html'), generateBlogHubHtml(true), 'utf8');
  fs.writeFileSync(path.join(websiteDir, 'blog.html'), generateBlogHubHtml(false), 'utf8');
  console.log('✅ Generated blog/index.html & blog.html with updated SEO tags');

  allPosts.forEach((post, i) => {
    const related = allPosts
      .filter(p => p.id !== post.id)
      .sort((a, b) => (a.catSlug === post.catSlug ? -1 : 1))
      .slice(0, 3);

    const postHtml = generatePostHtml(post, related);
    const postFile = path.join(blogDir, `${post.slug}.html`);
    fs.writeFileSync(postFile, postHtml, 'utf8');
  });
  console.log(`✅ Generated ${allPosts.length} static HTML post pages with unique SEO metadata`);

  const blogDataJs = `window.ALL_BLOG_POSTS = ${JSON.stringify(allPosts.map(p => ({
    id: p.id,
    title: p.title,
    slug: p.slug,
    category: p.category,
    catSlug: p.catSlug,
    readTime: p.readTime,
    excerpt: p.metaDescription,
    keywords: p.keywords
  })), null, 2)};`;
  fs.writeFileSync(path.join(jsDir, 'blog-data.js'), blogDataJs, 'utf8');
  console.log('✅ Generated js/blog-data.js');

  fs.writeFileSync(path.join(websiteDir, 'sitemap.xml'), generateSitemapXml(), 'utf8');
  console.log('✅ Updated sitemap.xml with all 100 blog URLs');

  console.log('🎉 Full SEO build completed successfully!');
}

build();
