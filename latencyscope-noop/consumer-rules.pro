# Consumer ProGuard rules for latencyscope-noop
#
# The no-op's job is to disappear, so it keeps as little as it can. The one
# exception matches latencyscope-ui-view: LatencyReportFragment is re-created
# by name after process death, and a release build that stripped it would turn
# a line of leftover debug code into a ClassNotFoundException. The same
# reasoning put both report activities in this module's manifest.
#
# Everything else here is unreachable by design and R8 is meant to remove it.
-keep public class io.github.latencyscope.ui.view.LatencyReportFragment {
    public <init>();
}
