# Draft — #5608 "Additional ScriptLoader API"

> Draft for human rewrite. Rewrite in your own words before posting.
> Every fact below is verified with a file:line citation — see the notes at the
> bottom. ~28 lines, no headings, no bold.

```
adding to this rather than opening a separate issue - the "don't require a file
for a script" half of it is exactly what i need.

i maintain a skript language server (https://github.com/RohanDaCoder/LspSkript) that
runs as a paper plugin and uses skript's own parser. to get skript's parse errors
i currently have to call ScriptLoader.loadScripts(...), and that also loads the
script for real: it registers the script, registers its commands and listeners,
and runs any on load: block.

on load: is EvtScript, and it fires in postLoad():

    https://github.com/SkriptLang/Skript/blob/master/src/main/java/ch/njol/skript/events/EvtScript.java

so for a tool that checks a file as you type, the file gets loaded as a real
script on every keystroke.

what i am asking for is a public method that parses and returns the errors
without loading anything:

    public static List<LogEntry> parse(Config config)

Config already accepts an InputStream and a name with no file (Config.java:84), so
this covers the "not explicitly requiring a file" part of the issue too.

the mechanism mostly exists already - a tool can build a Config, call
Structure.parse(...) and ScriptLoader.loadItems(...), and wrap it in
SkriptLogger.startRetainingLog() to get the LogEntry list back. what is missing
is a supported way to do that without knowing which load phases to skip and
without relying on internals.

skript-parser can parse standalone, but it cannot know what a given server has
registered, so its errors diverge from /skript reload on a server with addons -
which is the whole reason for running inside the server.

happy to open a pr against dev/feature if this is wanted. not blocking us, we
work around it and are carrying on.
```

# Notes

**Verified facts used here**

| Claim | Evidence |
|---|---|
| `on load:` is `EvtScript` and fires in `postLoad()` | `ch/njol/skript/events/EvtScript.java:16-18` registers the event with patterns `[:async] [script] (load\|init\|enable)`; `:36` `load = matchedPattern == 0`; `:40-45` `postLoad()` → `runTrigger(trigger, new ScriptEvent())` |
| `loadScripts` registers the script, its commands and its listeners | `ScriptLoader.java:753-754` (`loadedScripts.add`, `scriptsByName.put`), `:756-757` fires `ScriptInitEvent`; `:580/:609/:633` run `preLoad`/`load`/`postLoad`. Commands: `StructCommand.java:100` `load()` → `:122` `ScriptCommandRegistrar.register(command)`. Listeners: `SkriptEvent.java:152-155` `postLoad()` → `:153` `SkriptEventHandler.registerBukkitEvents(...)` |
| `Config` accepts an InputStream and a name with no file | `ch/njol/skript/config/Config.java:84-87` → `:58-82`, where `:62-63` only stores `file` if non-null. It is never read during parsing |
| `Config` is the only thing that insists on a file | `ScriptLoader.java:688-689` — `loadScript` throws `IllegalArgumentException("A config must have a file to be loaded.")` if `config.getFile() == null` |
| The mechanism mostly exists | `Structure.parse(String, Node, String)` — `Structure.java:191`, public. `ScriptLoader.loadItems(SectionNode)` — `ScriptLoader.java:1036`, public. `SkriptLogger.startRetainingLog()` — `SkriptLogger.java:45`, public; `RetainingLogHandler.getLog()` — `RetainingLogHandler.java:188`, public. `StructParse.load()` — `ch/njol/skript/test/runner/StructParse.java:89-108` does exactly this sequence |
| `skript-parser` cannot know what a server has registered | `Mwexim/skript-parser` reimplements Skript's pattern system standalone; it has no access to a running server's `SyntaxRegistry` |

**Deliberate choices**

- **One signature, not two.** An earlier draft proposed both
  `parse(String, String)` and `parse(Config)`. `parse(Config)` alone satisfies
  the issue's own wording, because `Config` already takes text and no file.
- **"the mechanism mostly exists already"** is the strongest framing available:
  it is not "we need a feature", it is "the feature is 90% there and the
  missing 10% is the footgun". That is much easier to say yes to.
- **The `skript-parser` sentence pre-empts a known objection.** TheLimeGlass
  already wrote *"Just use the skript-parser"* on #8253, so a maintainer will
  reach for it here. Answering it first, with the addon-divergence reason, is
  better than being asked.
- **"happy to open a pr against dev/feature"** is concrete and time-bound in
  the way a maintainer can act on. Per `CLOCKWORK_RELEASE_MODEL.md`, feature
  releases land on the 15th (next: 15 Jan 2027) and content must be in a
  pre-release first (~1 Jan), so a PR merged in December makes 2.18.0.
- **No "it is a small change".** The API shape is small; the semantics are not
  — which load phase has to run for an error to be a real error, and which
  structures must be excluded because their `load()` has global side effects
  (`StructFunction.load()` → `Functions.loadFunction(...)`;
  `StructCommand.load()` → `ScriptCommandRegistrar.PENDING_REGISTRATIONS`).
  Claiming "small" invites a maintainer to say yes and then discover it is not.

**Known label on this issue:** `enhancement`, `priority: lowest`, and
`core task` ("Tasks that may be handled only by the core developer team"). The
"happy to open a pr" line is the answer to `core task` — it removes the
process objection, because a PR is the core team's call to merge.
