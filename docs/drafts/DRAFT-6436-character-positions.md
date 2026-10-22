# Draft — #6436 "Implement proper debugging tools and API"

> Draft for human rewrite. Rewrite in your own words before posting.
> Every fact below is verified with a file:line citation — see the notes at the
> bottom. ~25 lines, no headings, no bold, matching the register maintainers
> use on that issue.

```
adding a note from the tooling side, since this issue is about api hooks for
tools and one of them is missing.

i maintain a skript language server (https://github.com/RohanDaCoder/LspSkript) that
runs as a paper plugin and uses skript's own parser, so the diagnostics are
skript's own. what i cannot do is point at the right place in a file, because the
public api only gives line numbers.

i could not find a column exposed anywhere in the public api:

- ConfigReader records the line number and nothing positional - `private int ln`
  (ConfigReader.java:22), incremented in readLine() (ConfigReader.java:37)
- Node has getLine() but no column getter
- MatchResult has an exprOffset field, which is the character offset the
  matcher is currently at, but it is package-private, has no getter, and
  toParseResult() does not copy it (MatchResult.java:19 and :45-52)

so the offset is worked out during every match and then thrown away.

this matters because the rules for lexing .sk are not written down as a grammar.
a tool has to reverse engineer them from Node.splitLine, which is a state
machine over quotes, %, {}, #, ## escaping and ### block comments. several tools
keep their own copy, and they disagree with each other about what a .sk file
means.

we do not need a position per pattern group. even just knowing which part of a
line matched would save a tool from lexing the whole file - today the only
offsets that escape the parser are the ones for <regex> elements, via
MatchResult.getRegexResults().

not blocking us, we work around it and are carrying on. happy to test a branch
build if that is useful.
```

# Notes

**Verified facts used here**

| Claim | Evidence |
|---|---|
| `ConfigReader` records only a line number | `ch/njol/skript/config/ConfigReader.java:22` (`private int ln = 0;`), incremented at `:37` inside `readLine()` `:32-40` |
| `Node` has `getLine()`, no column getter | `ch/njol/skript/config/Node.java:329-331`. Repo-wide grep for `getColumn`/`columnNum`/`int column` in `src/main/java`: 0 hits |
| `MatchResult.exprOffset` exists, is package-private, has no getter, is dropped | `ch/njol/skript/patterns/MatchResult.java:19` (`int exprOffset;` — no modifier). Public getters are `:54,:58,:62,:66,:70` — no offset getter. `toParseResult()` `:45-52` copies `source`, `regexes`, `mark`, `tags` — **not** `exprOffset` |
| `Node.splitLine` is the only public piece of the lexing rules | `Node.java:108` / `:122` — `public static NonNullPair<String,String> splitLine(String, AtomicBoolean)`. The indentation/section rules live in `SectionNode.load(...)`, which is package-private (`SectionNode.java:260,264`) |
| The only offsets that escape the parser | `ParseResult.regexes` — a `java.util.regex.MatchResult` list, `SkriptParser.java:120`; populated only for `<regex>` elements (`RegexPatternElement.java:45`) |
| `getComment()`/`getIndentation()` became public in 2.17.1 | `Node.java:249-252`, `:263-265` in 2.17.1; `protected` in 2.16.2. Neither is a column — `getIndentation()` returns the *canonical* indent, not the line's actual leading whitespace |

**Deliberate choices**

- *"i could not find a column exposed anywhere in the public api"* rather than
  *"there is no column anywhere"* — an absolute about a 200k-line codebase we
  did not write is a cheap thing for a maintainer to rebut.
- *"the rules for lexing .sk are not written down as a grammar"* rather than
  *"not written down anywhere"* — Skript's user docs do cover `#` comments; the
  gap is the grammar.
- **No project names.** `SkriptLang/skript-core` 404s (the real path is
  `skript-studio/skript-core`, 0 stars), and the other tokenisers are 0–11 star
  repos. "several tools" is true, and does not stake credibility on thin links.
- *"not blocking us"* is the strongest anti-demand sentence available.
- The ask is deliberately the **weak** version — "which part of a line matched",
  not a position per pattern group. The weaker ask is the one that can be said
  yes to.
