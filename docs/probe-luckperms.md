# probe: does luckperms install itself as the neoforge PermissionAPI handler

status: NOT RUN. this file is the v0.1 deliverable slot for the answer.

the plan's wording, kept verbatim so the scope cannot creep: "flan is a
spike, not a feature. no CommandEvent interceptor in v0.1. the probe
question is narrow: does the pinned luckperms 5.5 neoforge jar install
itself as the neoforge PermissionAPI handler on our server."

## the question

on the pinned server (neoforge 21.1.248, the pack's exact luckperms jar):
does PermissionAPI report luckperms as the active handler, and does a node
registered during PermissionGatherEvent.Nodes resolve through it?

## what the answer decides

- yes -> the eventual fix is flan-scoped only: a small explicit map over a
  verified subtree, default-deny on anything not listed.
- no -> we do nothing, because the precedents research ruled permissions out
  of scope for four friends and the v1 plan quietly walked that back.

either way, no listener code lands in v0.1 regardless of the outcome.

## how to run it

on the pc rig, not in prod. a throwaway mod or a debugger session that logs
the active handler identity at PermissionGatherEvent time is enough. record
the exact luckperms version and the handler class name below.

## the answer

(unfilled)
