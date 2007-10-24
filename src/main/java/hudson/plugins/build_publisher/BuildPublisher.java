package hudson.plugins.build_publisher;

import hudson.Launcher;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.matrix.MatrixRun;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.Project;
import hudson.model.Result;
import hudson.tasks.Publisher;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.logging.Logger;

import org.jaxen.function.ext.MatrixConcatFunction;
import org.kohsuke.stapler.StaplerRequest;

/**
 * {@link Publisher} responsible for submitting build results to public Hudson
 * instance.
 *
 * @author dvrzalik@redhat.com
 *
 */
public class BuildPublisher extends Publisher {

    private String serverName;
    private String notificationRecipients;
    private boolean publishUnstableBuilds;
    private boolean publishFailedBuilds;

    private transient HudsonInstance publicHudsonInstance;

    /**
     * @stapler-constructor
     */
//    public BuildPublisher(String serverName, boolean publishUnstableBuilds,
//            boolean publishFailedBuilds, String notificationRecipients) {
//        this.serverName = serverName;
//        this.notificationRecipients = notificationRecipients;
//        this.publishFailedBuilds = publishFailedBuilds;
//        this.publishUnstableBuilds = publishUnstableBuilds;
//    }

    public BuildPublisher() {

    }


// @Override
    public boolean perform(Build build, Launcher launcher,
            BuildListener listener) throws InterruptedException, IOException {
        // don't send failed/unstable builds unless user wishes to do so
        if ((!publishUnstableBuilds && (Result.UNSTABLE == build.getResult()))
                || (!publishFailedBuilds && (Result.FAILURE == build
                        .getResult()))) {
            return true;
        }
        
        //It won't work for matrix project
        if(build instanceof MatrixRun) {
            return true;
        }

        publicHudsonInstance = BuildPublisher.DESCRIPTOR
                .getHudsonInstanceForName(serverName);

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

    }

    public String getServerName() {
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
}
