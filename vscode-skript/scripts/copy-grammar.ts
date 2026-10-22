// Builds the official Skript grammar and copies it into this extension's
// syntaxes/ folder. Run via `bun run grammar`.
//
// The skript-grammar/ directory is a git submodule pinned to the official
// SkriptLang grammar (source.cson). We never edit the grammar by hand here;
// we convert the canonical source into VS Code's tmLanguage JSON and copy it.
import { join, resolve } from 'node:path';

import * as CSON from 'cson-parser';

const repoRoot = resolve(import.meta.dir, '..', '..');
const sourceCson = join(repoRoot, 'skript-grammar', 'source.cson');
const dest = join(import.meta.dir, '..', 'syntaxes', 'skript.tmLanguage.json');

const source = Bun.file(sourceCson);
if (!(await source.exists())) {
    console.error(
        'Grammar source not found at',
        sourceCson,
        '\nThe skript-grammar submodule may not be initialised. Run: git submodule update --init',
    );
    process.exit(1);
}

const parsed: unknown = CSON.parse(await source.text());

// Bun.write creates the parent directory when it is missing, so the
// syntaxes/ folder does not have to exist beforehand.
await Bun.write(dest, JSON.stringify(parsed));
console.log('Built and copied official grammar ->', dest);
