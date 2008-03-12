package hudson.plugins.build_publisher;

import hudson.Plugin;
import hudson.maven.MavenReporters;
import hudson.tasks.BuildStep;

import java.util.List;

/**
 * Entry point of a plugin.
 *
 * <p>
 * There must be one {@link Plugin} class in each plugin. See javadoc of
 * {@link Plugin} for more about what can be done on this class.
 *
 * @author Kohsuke Kawaguchi
 * @plugin
 */
public class PluginImpl extends Plugin {
    public void start() throws Exception {
        // plugins normally extend Hudson by providing custom implementations
        // of 'extension points'. In this example, we'll add one builder.
        BuildStep.PUBLISHERS.addNotifier(BuildPublisher.DESCRIPTOR);
        MavenReporters.LIST.add(MavenBuildPublisher.DESCRIPTOR);
    }

    // for Jelly
    public HudsonInstance[] getHudsonInstances() {
        return BuildPublisher.DESCRIPTOR.getPublicInstances();
    }
}
