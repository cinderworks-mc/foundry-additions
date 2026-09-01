# foundry-additions

the foundry's own mod. built for one server, ours, and shaped entirely around
what a handful of friends on a whitelist actually need. mit licensed, so run
it if you want, but the defaults assume our pack and i'm not going to chase
bugs it causes in yours.

if something breaks while you're playing the foundry, tell me, not the author
of whatever mod it looks like it came from. a lot of this is glue against
other people's apis, and when my glue breaks apotheosis, that's my bug and
shouting at the apotheosis dev wastes their evening.

v0.1 is all server side plumbing. a unix socket so ops tooling can ask the
server how it's doing without rcon, a session logger that will eventually
replace our kubejs one (running side by side with it for now), the afk
detector, and one line in crash reports that stamps which pack version was
running. nothing in it reaches a client. the first version that needs a
client download is v0.3.

the details live in the code and in docs/. `tools/foundry-ops` is a small
python client for the socket.

## building

java 21 exactly, `./gradlew build`, jar lands in build/libs. `./gradlew test`
runs the suite. pushing a v* tag builds the jar and attaches it to a github
release, and the pack's manifest consumes that asset directly.
