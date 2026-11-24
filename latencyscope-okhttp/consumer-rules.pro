# Consumer ProGuard rules for latencyscope-okhttp
#
# No keep rules are needed here, and that is a finding rather than an omission.
#
# Everything this module exposes is reached by ordinary calls from consumer
# code, which R8 traces for itself. Nothing is constructed reflectively, looked
# up by name, or named in a manifest or layout.
#
# Blanket -keep rules on a library are not free: they pin classes in every
# consumer's release build and defeat exactly the shrinking that
# latencyscope-noop exists to make possible.
#
# The claim is checked rather than assumed. The :sample module has a `staging`
# build type that links the real library and runs R8 over it, so a missing rule
# shows up as a build or runtime failure there.
