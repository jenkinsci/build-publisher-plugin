package hudson.plugins.build_publisher;

import hudson.Plugin;
import hudson.model.Hudson;
import hudson.model.Jobs;
import hudson.model.ManagementLink;
import hudson.tasks.BuildStep;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

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
        Jobs.PROPERTIES.add(ExternalProjectProperty.DESCRIPTOR);

        ManagementLink.LIST.add(new ManagementLink() {
            public String getIconFileName() {
                return "redo.gif";
            }

            public String getUrlName() {
                return "plugin/build-publisher/";
            }

            public String getDisplayName() {
                return "Build Publishing Status";
            }

            public String getDescription() {
                return "Monitor the status of <a href='http://hudson.gotdns.com/wiki/display/HUDSON/Build+Publisher+Plugin'>the build-publisher plugin</a>";
            }
        });
    }

    // for Jelly
    public HudsonInstance[] getHudsonInstances() {
        return BuildPublisher.DESCRIPTOR.getPublicInstances();
    }

    public void doRetryNow(StaplerRequest req, StaplerResponse rsp, @QueryParameter("name") String name) throws IOException {
        Hudson.getInstance().checkPermission(Hudson.ADMINISTER);

        HudsonInstance h = BuildPublisher.DESCRIPTOR.getHudsonInstanceForName(name);
        if(h==null) {
            rsp.sendError(HttpServletResponse.SC_BAD_REQUEST,"No such name: "+name);
            return;
        }
        
        h.getPublisherThread().interrupt();

        rsp.sendRedirect(".");
    }

    public void doResurrect(StaplerRequest req, StaplerResponse rsp, @QueryParameter("name") String name) throws IOException {
        Hudson.getInstance().checkPermission(Hudson.ADMINISTER);

        HudsonInstance h = BuildPublisher.DESCRIPTOR.getHudsonInstanceForName(name);
        if(h==null) {
            rsp.sendError(HttpServletResponse.SC_BAD_REQUEST,"No such name: "+name);
            return;
        }

        h.initPublisherThread();

        rsp.sendRedirect(".");
    }
}
