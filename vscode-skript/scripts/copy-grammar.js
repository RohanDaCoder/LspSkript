// Builds the official Skript grammar and copies it into this extension's
// syntaxes/ folder. Run via `npm run grammar`.
//
// The skript-grammar/ directory is a git submodule pinned to the official
// SkriptLang grammar (source.cson). We never edit the grammar by hand here;
// we convert the canonical source into VS Code's tmLanguage JSON and copy it.
const fs = require('fs');
const path = require('path');
const CSON = require('cson-parser');

const repoRoot = path.resolve(__dirname, '..', '..');
const sourceCson = path.join(repoRoot, 'skript-grammar', 'source.cson');
const destDir = path.join(__dirname, '..', 'syntaxes');
const dest = path.join(destDir, 'skript.tmLanguage.json');

if (!fs.existsSync(sourceCson)) {
	console.error('Grammar source not found at', sourceCson, '\nThe skript-grammar submodule may not be initialised. Run: git submodule update --init');
	process.exit(1);
}

const parsed = CSON.parse(fs.readFileSync(sourceCson, 'utf8'));
fs.mkdirSync(destDir, { recursive: true });
fs.writeFileSync(dest, JSON.stringify(parsed));
console.log('Built and copied official grammar ->', dest);
