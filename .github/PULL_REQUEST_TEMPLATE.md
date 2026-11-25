## What this changes

<!-- And why. If it fixes an issue, link it. -->

## How it was verified

<!--
Say what you ran, and what you could not. "Not tested on a device" is a
useful sentence; silence about it is not.
-->

- [ ] `./gradlew check` passes — this includes `apiCheck` (API parity with
      `latencyscope-noop`) and core's coverage floor
- [ ] `./gradlew lintDebug` is clean
- [ ] New tests were run against the **unfixed** code and seen to fail

## Public API

- [ ] No new `public` declaration — or, if there is one, it is mirrored in
      `latencyscope-noop` and justified by a workflow that needs it

## Anything you decided not to do

<!-- Often the most useful part of the description. -->
