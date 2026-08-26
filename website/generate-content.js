const fs = require('fs');
const path = require('path');

const API_KEY = process.argv[2] || 'YOUR_API_KEY';
if (!API_KEY || API_KEY === 'YOUR_API_KEY') {
    console.error("Please provide an API key as an argument. Example: node generate-content.js YOUR_API_KEY");
    process.exit(1);
}

const URL = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${API_KEY}`;
const seoCatalog = JSON.parse(fs.readFileSync(path.join(__dirname, 'seo-metadata.json'), 'utf8'));
const contentDir = path.join(__dirname, 'content');

if (!fs.existsSync(contentDir)) {
    fs.mkdirSync(contentDir);
}

const delay = ms => new Promise(res => setTimeout(res, ms));

async function generateArticle(post) {
    const prompt = `You are an expert cybersecurity and VPN copywriter. 
Write a 1,000-word SEO-optimized blog post for the title: "${post.title}".
Context about the product: It is called "Unlimited VPN", an Android VPN app using WireGuard, no-logs, and has a premium subscription.

You MUST follow these Generative Engine Optimization (GEO) rules:
1. Provide a "Quick Summary" (BLUF - Bottom Line Up Front) in 2-3 sentences.
2. Use clear, question-based H2 headings for sections.
3. Include at least one HTML <table> comparing relevant features or concepts in the content of one of the sections.
4. Conclude with a "Frequently Asked Questions (FAQ)" array with 3-4 questions and answers.

Output MUST be a valid JSON object matching this exact schema, with NO markdown wrapping, just the raw JSON:
{
  "summary": "Your 2-3 sentence summary...",
  "sections": [
    {
      "heading": "Clear, question-based H2 heading",
      "content": "Raw HTML content for this section (use <p>, <ul>, <table> etc.)"
    }
  ],
  "faqs": [
    { "q": "FAQ question?", "a": "FAQ answer." }
  ]
}`;

    try {
        const response = await fetch(URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                contents: [{ parts: [{ text: prompt }] }],
                generationConfig: {
                    temperature: 0.7,
                    responseMimeType: "application/json"
                }
            })
        });

        if (!response.ok) {
            console.error(`Failed to generate ${post.slug}: ${response.statusText}`);
            const errorText = await response.text();
            console.error(errorText);
            return null;
        }

        const data = await response.json();
        let content = data.candidates[0].content.parts[0].text;
        
        return content;
    } catch (error) {
        console.error(`Error generating ${post.slug}:`, error.message);
        return null;
    }
}

async function main() {
    const posts = seoCatalog.blogPosts;
    console.log(`Found ${posts.length} blog posts to generate.`);

    for (let i = 0; i < posts.length; i++) {
        const post = posts[i];
        const filePath = path.join(contentDir, `${post.slug}.json`);

        if (fs.existsSync(filePath)) {
            console.log(`[${i+1}/${posts.length}] Skipping ${post.slug}, already exists.`);
            continue;
        }

        console.log(`[${i+1}/${posts.length}] Generating content for: ${post.title}...`);
        const content = await generateArticle(post);

        if (content) {
            fs.writeFileSync(filePath, content, 'utf8');
            console.log(`✅ Saved ${post.slug}.json`);
        }

        // Wait 4 seconds between requests to avoid rate limits
        await delay(4000);
    }
    console.log("All content generated successfully! You can now run: node build-blogs.js");
}

main();
