package hudson.plugins.build_publisher;

import hudson.Launcher;
import hudson.matrix.MatrixAggregatable;
import hudson.matrix.MatrixAggregator;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixRun;
import hudson.maven.MavenBuild;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.tasks.Publisher;
import hudson.util.DescriptorList;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * {@link Publisher} responsible for submitting build results to public Hudson
 * instance.
 *
 * @author dvrzalik@redhat.com
 *
 */
public class BuildPublisher extends Publisher implements MatrixAggregatable {
    
    private String serverName;
    private String notificationRecipients;
    private boolean publishUnstableBuilds;
    private boolean publishFailedBuilds;
    private List<BuildPublisherPostAction> postActions = new Vector<BuildPublisherPostAction>();

    private transient HudsonInstance publicHudsonInstance;

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher,
            BuildListener listener) throws InterruptedException, IOException {
        // don't send failed/unstable builds unless user wishes to do so
        if ((!publishUnstableBuilds && (Result.UNSTABLE == build.getResult()))
                || (!publishFailedBuilds && (Result.FAILURE == build
                        .getResult()))) {
            return true;
        }
        
        //MatrixRun and MavenBuild can be published only after its parent build
        if(build.getProject().getParent() != Hudson.getInstance()) {
            return true;
        }

        publicHudsonInstance = BuildPublisher.DESCRIPTOR
                .getHudsonInstanceForName(getServerName());

        if (publicHudsonInstance == null) {
            listener
                    .getLogger()
                    .println(
                            "There is no public Hudson instance configured for this project");
            return true;
        }

        listener.getLogger().println(
                "Build was marked for publishing on "
                        + publicHudsonInstance.getUrl());

        publicHudsonInstance.publishNewBuild(build);

        return true;
    }  
    
    public MatrixAggregator createAggregator(final MatrixBuild matrixBuild, Launcher launcher, BuildListener listener) {
        
        // Publishing of a matrix project is a little bit tricky. When MatrixRun is published, 
        // parent MatrixProject has to be already created on the public side. Since MatrixRuns 
        // run independently (~ danger of collision) and it would be difficult to make the creation 
        // operation atomic, only MatrixBuilds are allowed to do it.
         
        return new MatrixAggregator(matrixBuild, launcher, listener) {

            @Override
            public boolean endBuild() throws InterruptedException, IOException {
                return perform(matrixBuild, launcher, listener);
            }
            
        };
    }

    /*---------------------------------*/
    /* ----- Descriptor stuff -------- */
    /*---------------------------------*/

    @Override
    public Descriptor<Publisher> getDescriptor() {
        return DESCRIPTOR;
    }

    public static final BuildPublisherDescriptor DESCRIPTOR = new BuildPublisherDescriptor();

    public static final class BuildPublisherDescriptor extends
            Descriptor<Publisher> {

        private HudsonInstance[] publicInstances = new HudsonInstance[0];

        protected BuildPublisherDescriptor() {
            super(BuildPublisher.class);
            load();
        }

        @Override
        protected void convert(Map<String, Object> oldPropertyBag) {
            if (oldPropertyBag.containsKey("publicInstances"))
                publicInstances = (HudsonInstance[]) oldPropertyBag
                        .get("publicInstances");
        }

        @Override
        public String getDisplayName() {
            return "Publish build";
        }

        @Override
        public Publisher newInstance(StaplerRequest req)
                throws hudson.model.Descriptor.FormException {
            //TODO post-actions
            BuildPublisher bp = new BuildPublisher();
            req.bindParameters(bp, "bp.");
            return bp;
        }
        
        @Override
        public boolean configure(StaplerRequest req) throws FormException {
            int i;
            String[] names = req.getParameterValues("name");
            String[] urls = req.getParameterValues("url");
            String[] logins = req.getParameterValues("login");
            String[] passwords = req.getParameterValues("password");
            int len;
            if (names != null && urls != null)
                len = Math.min(names.length, urls.length);
            else
                len = 0;
            HudsonInstance[] servers = new HudsonInstance[len];

            for (i = 0; i < len; i++) {

                if (urls[i].length() == 0) {
                    continue;
                }
                if (names[i].length() == 0) {
                    names[i] = urls[i];
                }

                servers[i] = new HudsonInstance(names[i], urls[i], logins[i],
                        passwords[i]);
            }

            this.publicInstances = servers;

            save();

            return true;
        }

        public HudsonInstance[] getPublicInstances() {
            return publicInstances;
        }

        public HudsonInstance getHudsonInstanceForName(String name) {
            for (HudsonInstance server : publicInstances) {
                if (server.getName().equals(name)) {
                    return server;
                }
            }

            return null;
        }

        @Override
        public String getHelpFile() {
            return "/plugin/build-publisher/help/config/publish.html";
        }
        
    }

    public String getServerName() {
        if(serverName==null) {
            // pick up the default instance
            HudsonInstance[] instances = DESCRIPTOR.getPublicInstances();
            if((instances != null) && (instances.length==1))
                return instances[0].getName();
        }
        return serverName;
    }

    public String getNotificationRecipients() {
        return notificationRecipients;
    }

    public void setNotificationRecipients(String notificationRecipients) {
        this.notificationRecipients = notificationRecipients;
    }

    public void setPublishUnstableBuilds(boolean publishUnstableBuilds) {
        this.publishUnstableBuilds = publishUnstableBuilds;
    }

    public void setPublishFailedBuilds(boolean publishFailedBuilds) {
        this.publishFailedBuilds = publishFailedBuilds;
    }

    public boolean isPublishUnstableBuilds() {
        return publishUnstableBuilds;
    }

    public boolean isPublishFailedBuilds() {
        return publishFailedBuilds;
    }

    public void setServerName(String name) {
        this.serverName = name;
    }

    public HudsonInstance getPublicHudsonInstance() {
        return publicHudsonInstance;
    }

    public Map<Descriptor<BuildPublisherPostAction>,BuildPublisherPostAction> getPostActions() {
        return Descriptor.toMap(postActions);
    }
}
