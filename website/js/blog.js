/**
 * Unlimited VPN Blog Interactive Engine
 * Handles live search, category filtering, URL params, and Table of Contents scrollspy
 */

document.addEventListener('DOMContentLoaded', () => {
  initBlogHub();
  initTableOfContents();
});

function initBlogHub() {
  const searchInput = document.getElementById('blogSearchInput');
  const categoryChips = document.querySelectorAll('.category-chip');
  const blogCards = document.querySelectorAll('.blog-card[data-category]');
  const resultsCount = document.getElementById('resultsCount');
  const noResults = document.getElementById('noResultsMessage');
  const featuredCard = document.getElementById('featuredPostCard');

  if (!searchInput && categoryChips.length === 0) {
    // Not on the blog hub page
    return;
  }

  let currentCategory = 'all';
  let searchQuery = '';

  // Check URL param for ?category=xxx
  const urlParams = new URLSearchParams(window.location.search);
  const paramCategory = urlParams.get('category');
  if (paramCategory) {
    const matchingChip = document.querySelector(`.category-chip[data-filter="${paramCategory}"]`);
    if (matchingChip) {
      categoryChips.forEach(c => c.classList.remove('active'));
      matchingChip.classList.add('active');
      currentCategory = paramCategory;
    }
  }

  function filterPosts() {
    let visibleCount = 0;

    blogCards.forEach(card => {
      const cardCategory = card.getAttribute('data-category');
      const cardTitle = card.getAttribute('data-title') || '';
      const cardExcerpt = card.getAttribute('data-excerpt') || '';

      const matchesCategory = (currentCategory === 'all' || cardCategory === currentCategory);
      const matchesSearch = !searchQuery || cardTitle.includes(searchQuery) || cardExcerpt.includes(searchQuery);

      if (matchesCategory && matchesSearch) {
        card.style.display = 'flex';
        visibleCount++;
      } else {
        card.style.display = 'none';
      }
    });

    // Update count display
    if (resultsCount) {
      resultsCount.textContent = `Showing ${visibleCount} article${visibleCount === 1 ? '' : 's'}`;
    }

    // Show/hide no results message
    if (noResults) {
      noResults.style.display = (visibleCount === 0) ? 'block' : 'none';
    }

    // If searching or filtering by a specific category, hide the featured hero to keep view clean
    if (featuredCard) {
      if (searchQuery.length > 0 || currentCategory !== 'all') {
        featuredCard.style.display = 'none';
      } else {
        featuredCard.style.display = 'grid';
      }
    }
  }

  // Handle Search Input
  if (searchInput) {
    searchInput.addEventListener('input', (e) => {
      searchQuery = e.target.value.toLowerCase().trim();
      filterPosts();
    });
  }

  // Handle Category Chips
  categoryChips.forEach(chip => {
    chip.addEventListener('click', () => {
      categoryChips.forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      currentCategory = chip.getAttribute('data-filter');
      filterPosts();
    });
  });

  // Run initial filter
  filterPosts();
}

function initTableOfContents() {
  const tocLinks = document.querySelectorAll('.blog-toc-item a');
  if (tocLinks.length === 0) return;

  const headings = Array.from(document.querySelectorAll('.blog-prose h2[id]'));
  if (headings.length === 0) return;

  // Scrollspy to highlight current section in TOC
  function onScroll() {
    const scrollPos = window.scrollY + 140;

    let currentActiveId = null;
    for (let i = 0; i < headings.length; i++) {
      const heading = headings[i];
      if (heading.offsetTop <= scrollPos) {
        currentActiveId = heading.getAttribute('id');
      }
    }

    tocLinks.forEach(link => {
      const href = link.getAttribute('href');
      if (href === `#${currentActiveId}`) {
        link.classList.add('active');
      } else {
        link.classList.remove('active');
      }
    });
  }

  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
}
