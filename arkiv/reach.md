# Intended reach and trial status

GhostWitness is intended for recorders who may lose their phones, nearby people willing to preserve
encrypted evidence, and original owners recovering their recordings afterward.

Who it is for: people filming something they may not be allowed to keep (protests, police stops,
harassment, workplace incidents), and the bystanders near them who are willing to carry encrypted
pieces for a few minutes.

Can they try it today: yes, on Android, with the debug APK built from this repo. During the event we
ran it on real phones (recorder, witnesses and a recovery phone signed in with the same MetaMask
wallet). Witness phones wrote the session, segment and upload entities listed in `schema.md`, and the
shards they point to are on Swarm. Setup still needs a Bee endpoint, a postage batch and a funded
writer per witness; turning those into built-in defaults is the first post-event task.

No user numbers are claimed. After Mumbai we plan to keep building it: hosted Bee and key service,
sponsored witness writes so bystanders need no tokens, and automatic index renewal before expiry.
