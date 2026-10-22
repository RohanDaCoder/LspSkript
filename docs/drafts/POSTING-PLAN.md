# Posting plan

## Order and pacing

Never more than one comment per 4–5 days. Four comments from a zero-history
account inside a week, three of them on `sovdeeth`'s own issues and all linking
the same repo, is indistinguishable from a campaign — and a campaign is what got
#6447 closed the first time.

| # | When | Where | What | Channel |
|---|---|---|---|---|
| 1 | Day 1 | **Official Testing Discord** (`discord.gg/ZPsZAg6ygu`) | The testing offer | Discord, **not** the tracker |
| 2 | Day 5 | `#6436` | Character positions | Tracker |
| 3 | Day 10 | `#5608` | Parse-only API | Tracker |
| 4 | Day 25+ (optional) | `#6447` | Three-line signpost | Tracker — **recommend skipping** |

**#6447 should be last, and probably should not happen at all.** It is the only
one with a downside that cannot be undone: reviving a thread a maintainer closed
and was asked twice not to have spammed. The reputational upside is available
in our README, a Modrinth/Hangar listing, and the Skript Hub listing.

**The testing offer goes to the Discord.** Skript's README says: *"Wanting to
help test Skript's new features and releases? You can head on over to our
Official Testing Discord."* That is the designated channel for exactly this
offer. Note the repo lists three different Discord links — use
`discord.gg/ZPsZAg6ygu` (the Testing one from the README's "Help Us Test"
section), not the SkUnity or Skript Chat ones.

## The ten likely objections, and the answers

1. **"Who is this and why are you posting this here?"**
   > Fair — I'm new to the tracker. I maintain LspSkript, a Paper plugin that runs
   > an LSP against Skript's own parser. I'm not asking Skript to build an LSP;
   > I'm asking for two small API additions that would stop tools like mine
   > working around them.

2. **"There's already skript-parser for parsing without a server."**
   > It can't know what a given server has registered — which addons are
   > installed, which syntax they add — so its errors diverge from
   > `/skript reload` on an addon-heavy server. That's the whole reason for
   > running inside the server. Not a criticism of it; it solves a different
   > problem.

3. **"This is `priority: lowest` / `core task`."** (#5608)
   > Understood — happy for it to be lowest priority. If a PR is ever wanted I
   > can open one against `dev/feature`; it doesn't need to be mine to be useful.

4. **"Why does this need to change in Skript at all? You could work around it."**
   > Agreed, which is why this is a "would be nice" rather than a blocker. The
   > workaround is reimplementing a `.sk` lexer, which is where the third-party
   > projects end up disagreeing with each other.

5. **"There is already an LSP / someone else is building one."**
   > There are several. They're all standalone parsers, so they can only be right
   > for vanilla Skript. None of them can see addons.

6. **"Is your plugin compatible with the current release?"**
   > It builds against 2.17.1 now. Happy to move the hard dependency along with
   > whatever the target is.

7. **"We don't have the capacity / this isn't a priority."**
   > Understood. If it ever becomes relevant, the two asks are small — a
   > parse-only entry point, and exposing an offset that already exists in
   > `MatchResult`. I'll leave it here.

8. **"Your comment is AI-generated."**
   > Fair point, and the project's AGENTS.md says the right thing: I wrote this
   > myself; I used an LLM only to tidy wording. Any PR would disclose the
   > assistance.

9. **"Isn't this a question for the addon developers group / Discord?"**
   > The API side is the blocker, so the tracker is right for the API half. I'll
   > take the testing coordination to the testing Discord.

10. **"Does your tool actually run `on load:` for real? That sounds dangerous."**
    > It does today, and that is exactly the problem — the file you are typing
    > in is loaded as a real script on every keystroke. The temp file is written
    > outside `scripts/` and unloaded afterwards, but the `on load:` block still
    > executes, which is why the parse-only API matters. It is the first thing
    > on my list to fix.

## The disclosure requirement

Skript's `AGENTS.md` and `.github/contributing.md`:

> All communications in issues and pull requests must be human-authored. AI
> assistance may be used in translation and phrasing of messages, but this must
> also be disclosed and the underlying message must originate from real human
> thought, not an automated process.

So: **rewrite the drafts in your own words before posting.** The drafts carry
your strategy and the verified evidence; the sentences should be yours.

If a PR follows, `AGENTS.md` also asks for an `Authored-by` or `Co-authored-by`
footer on commits, and gives this disclosure format:

> - This PR was written primarily by Claude Code.
> - I consulted ChatGPT to understand the codebase but the solution was fully
>   authored manually by myself.

## Where each piece of content belongs

| Content | Where | Why |
|---|---|---|
| The two API asks | Tracker (#5608, #6436) | There is no developer forum. Discussions is enabled but dead — last activity 2018–2021, no API category, and `angelozerr` was politely told to take LSP discussion elsewhere |
| The testing offer | Testing Discord + PR #8253 | The designated channel |
| The announcement | README, Modrinth/Hangar, Skript Hub listing | No tracker slot exists for one |
| Our architecture and roadmap | Our repo's `docs/` | Keeps the tracker threads about the API ask |

## Before the first comment

- [x] Fix the "byte-for-byte identical" overclaim in both READMEs and the spec — **done** (`2355405`)
- [x] Add a `LICENSE` (MIT) — **done**
- [x] Add a repo description, homepage and topics — **done**
- [x] Push the Skript 2.17.1 bump so the pinned version isn't visibly stale — **done**
- [ ] Post the plugin and the VS Code extension to Modrinth — a maintainer's first
      instinct is "can I install this in 30 seconds?"
- [ ] Add a five-line "try it" to the README (download the jar, drop it in
      `plugins/`)
- [ ] Decide whether to keep `skript-studio` mentioned. The drafts do not mention
      it, which keeps the two projects cleanly separated. It also means the
      *community* framing — one engine, many clients — is not yet visible from
      the tracker.
