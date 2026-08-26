const fs = require('fs');
const path = require('path');

const walkSync = function(dir, filelist) {
  let files = fs.readdirSync(dir);
  filelist = filelist || [];
  files.forEach(function(file) {
    if (fs.statSync(dir + '/' + file).isDirectory()) {
      if (file !== 'node_modules' && file !== '.git') {
        filelist = walkSync(dir + '/' + file, filelist);
      }
    } else {
      const ext = path.extname(file);
      if (['.html', '.json', '.js', '.md'].includes(ext)) {
        filelist.push(dir + '/' + file);
      }
    }
  });
  return filelist;
};

const replaceInFile = (filePath) => {
  let content = fs.readFileSync(filePath, 'utf8');
  let originalContent = content;

  // Replacements
  content = content.replace(/Premium Subscription/g, "Premium Subscription");
  content = content.replace(/premium subscription/g, "premium subscription");
  content = content.replace(/premium subscription/g, "premium subscription");
  content = content.replace(/Premium Subscription/g, "Premium Subscription");
  content = content.replace(/premium subscription/g, "premium subscription");
  content = content.replace(/Premium Subscription/g, "Premium Subscription");
  content = content.replace(/premium subscription/g, "premium subscription");
  content = content.replace(/premium subscription/gi, "premium subscription");
  content = content.replace(/premium subscription/gi, "Premium Subscription");

  if (content !== originalContent) {
    fs.writeFileSync(filePath, content, 'utf8');
    console.log(`Updated: ${filePath}`);
    return true;
  }
  return false;
};

const allFiles = walkSync(__dirname);
let updatedCount = 0;

allFiles.forEach(file => {
  if (replaceInFile(file)) {
    updatedCount++;
  }
});

console.log(`Total files updated: ${updatedCount}`);
