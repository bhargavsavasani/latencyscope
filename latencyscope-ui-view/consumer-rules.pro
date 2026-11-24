# Consumer ProGuard rules for latencyscope-ui-view
#
# Unlike the other modules, two classes here are reached by name rather than by
# a call R8 can trace.

# The FragmentManager re-creates fragments reflectively from their class name
# when a process is restored after death. A consumer who puts
# LatencyReportFragment in a debug menu would otherwise see the screen work
# until the process is killed, and crash with ClassNotFoundException on the way
# back — the hardest kind of release-only bug to reproduce.
-keep public class io.github.latencyscope.ui.view.LatencyReportFragment {
    public <init>();
}

# PhaseBarView is named in latencyscope_item_row.xml and inflated by
# LayoutInflater through its (Context, AttributeSet) constructor. AGP generates
# an equivalent rule from the merged resources, so this is belt and braces —
# but a library should not depend on its consumer's toolchain doing that.
-keep class io.github.latencyscope.ui.view.PhaseBarView {
    public <init>(android.content.Context, android.util.AttributeSet);
}
