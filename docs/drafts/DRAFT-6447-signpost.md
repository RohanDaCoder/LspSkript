# Draft — #6447 "Proposal: LSP Support" (closed)

> **Recommendation: do not post this.** See the notes. If you decide to, post
> it last, at least two weeks after #6436 and #5608, and use the three lines
> below and nothing else.

```
for anyone who followed this thread: an skript language server now exists
(https://github.com/RohanDaCoder/LspSkript). it runs inside the server and uses
skript's own parser, so the errors match that server's /skript reload.
```

# Why the recommendation is to skip it

**The thread was closed for a reason we had backwards.** `sovdeeth` closed it
with:

> *"I am closing this issue since it has devolved into discussion about LSP4IJ,
> rather than the actual feasibility/addition of an LSP for Skript. **Open
> another if you still want to work on an LSP.**"*

Not because the proposal was answered, and not because anyone promised to
implement it. Our earlier internal note — *"it was closed with 'eveeifyeve will
implement it'"* — was simply wrong, and must not reach the public text.

**He has twice asked people to stop posting to it.** Ten weeks before closing:

> *"Please keep this issue relevant and avoid spamming messages, please. If you
> want to add another point, you can edit your previous message instead of
> posting a new one."*

A comment is not a reopen, but it functionally revives a thread he closed, and
GitHub will not surface it in his triage queue because the issue is marked
"Closed as completed".

**There is no tracker slot for an announcement.** `blank_issues_enabled: false`,
and the only templates are `bug-report` and `suggestion` — no showcase category.
The one thread that could have hosted one was closed for devolving.

**The reputational upside is available elsewhere** — our README, a Modrinth or
Hangar listing, and the Skript Hub addon listing — without any of the downside.

# What the earlier draft got wrong (do not repeat these)

1. **It claimed a "third option that nobody listed here".** `bluelhf` listed two:
   *"a language server would need to either spin up a Bukkit server or work
   independently of the Skript plugin... **A Bukkit server is clumsy**."* Our
   approach **is** his first option. Telling a maintainer we invented a third
   one proves we skimmed the thread. If you ever need to make this point, the
   honest version is: *"this is the option bluelhf listed - except you do not
   have to spin one up, you use the server you are already running. the clumsy
   part was the separate server, not the server itself."*

2. **It said "the parts that looked impossible in this thread turned out to be
   the easy parts."** That is a public dismissal of `Moderocky` and `bluelhf`,
   who wrote careful objections, on the thread they were thanked on.
   `.github/contributing.md` makes disrespect in the tracker sanctionable.
   The honest version credits them: *"Moderocky's point about the language
   changing with addons and server software is the reason this works - rather
   than answering that centrally, the tool asks the server it is attached to."*

3. **It was ~90 lines of markdown** — five headings, a table, bold, a fenced
   Java block. That is a launch post, not a comment, and its register does not
   match how any maintainer on that thread writes.

4. **It contradicted our own README.** The README advertises "Go to definition /
   find references" and "Rename & quick-fix code actions", while the draft said
   the tool "does not do rename, find-references". Both were wrong in the
   draft's favour — rename and references *are* implemented. The accurate
   version: *"rename and find-references work, but they are a text-level index
   over the open buffers rather than a real AST."*

5. **It linked a repo that looked empty on arrival.** At the time: 0 stars, 0
   forks, no description, no licence, and a README opening with "Full language
   support" and "byte-for-byte identical" — both now corrected, and the
   description, homepage, topics and MIT `LICENSE` added.

# If you do post it

Post it **last**, at least **two weeks** after #6436 and #5608, and use only the
three lines above. No headings, no table, no "status:", no link to the other
issues, and no claim about the thread being resolved. The maintainers' register
on that thread was short, lowercase and technical; a three-line pointer in that
register is the only version of this that is hard to dismiss.
