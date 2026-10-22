# Draft — #8253 "SkriptParser refactor, add ParsingConstraints"

> Draft for human rewrite. Rewrite in your own words before posting.
> This is a **pull request** comment, not an issue. ~8 lines, no headings, no
> bold, and deliberately *one ask* — the earlier draft bundled three and read
> as a drive-by.

```
happy to help with testing.

i maintain a skript language server (https://github.com/RohanDaCoder/LspSkript) that
parses scripts through skript's own parser, so i can run a corpus of scripts
against a branch build and report where the new parser behaves differently -
especially the parts that depend on what the server has registered, which is
where i would expect the differences to be.

my corpus is thin at the moment - skript's own example scripts and what i have
been writing - so treat early results as indicative rather than exhaustive. i can
build your branch and run it if that is useful.
```

# Notes

**Why this is now only one ask.** An earlier version of this comment also
carried the parse-only API note and the character-position note, plus a link to
the project. In a PR review that reads as *drive-by: unrelated API requests +
self-promotion, embedded in an offer of help* — and it can sour the review of
the PR itself. Those two notes belong in #5608 and #6436, where they are the
subject rather than a footnote.

**The PR's own "Testing Completed" box is empty**, and the "Supporting
Information" checklist still has unticked items, so an offer of test coverage
against real scripts is genuinely wanted rather than redundant.

**"a corpus of scripts"** replaces the earlier *"a pile of real scripts"*, which
was not true: the only `.sk` file in the tree is `skript-grammar/test.sk`, which
lives in a **git submodule** pointing at `SkriptLang/skript-grammar` and is not
tracked by us. It is upstream's syntax-demo file, not a corpus.

**"my corpus is thin at the moment"** is included deliberately. The red-team
review flagged that promising *"a few addon setups"* is not supportable today —
there is no server harness, no addon fixture and no CI in the repo. Saying so
up front is honest and it also makes the offer more credible, because it shows
the author knows what they do and do not have.

**"the parts that depend on what the server has registered"** is the specific,
useful thing to say. `SkriptParser`'s constraint handling, event restrictions
(`EventRestrictedSyntax`), and experimental gating (`ExperimentalSyntax`) all
read live server state, so those are the areas where a rewrite could change
behaviour in ways a corpus would catch.

**"could be picked up easily"** was cut from the earlier draft — it is a
maintainer-shaped judgement made by an outsider, and it is the kind of sentence
that gets quoted back.

# If a PR follows

Skript's `.github/contributing.md` requires AI assistance to be **disclosed in
the pull request**, and `AGENTS.md` states:

> All communications in issues and pull requests must be human-authored. AI
> assistance may be used in translation and phrasing of messages, but this must
> also be disclosed and the underlying message must originate from real human
> thought, not an automated process.

The example disclosure format they give:

> - This PR was written primarily by Claude Code.
> - I consulted ChatGPT to understand the codebase but the solution was fully
>   authored manually by myself.

If you write a PR with our help, disclose it. `AGENTS.md` also asks for an
`Authored-by` or `Co-authored-by` footer on commits, whichever fits your
involvement.

# Branch note

PRs for API additions target `dev/feature` (per `code-conventions.md`: *"Deprecation
PRs are typically merged for a feature release, so the PR should target
`dev/feature` branch"*), and per `CLOCKWORK_RELEASE_MODEL.md` content must be
included in a prior pre-release — which for the next cycle means before roughly
**1 January**, for **2.18.0** on 15 January 2027. PRs also need two core
approvals.
