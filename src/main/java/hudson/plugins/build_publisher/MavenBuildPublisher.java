package hudson.plugins.build_publisher;

import hudson.Launcher;
import hudson.maven.MavenBuild;
import hudson.maven.MavenReporter;
import hudson.maven.MavenReporterDescriptor;
import hudson.model.BuildListener;
import hudson.model.Result;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;

public class MavenBuildPublisher extends MavenReporter {

    private String serverName;
    private String notificationRecipients;
    private boolean publishUnstableBuilds;
    private boolean publishFailedBuilds;

    private transient HudsonInstance publicHudsonInstance;

    @Override
    public boolean end(MavenBuild build, Launcher launcher,
            BuildListener listener) throws InterruptedException, IOException {
        if ((!publishUnstableBuilds && (Result.UNSTABLE == build.getResult()))
                || (!publishFailedBuilds && (Result.FAILURE == build
                        .getResult()))) {
            return true;
        }

        publicHudsonInstance = BuildPublisher.DESCRIPTOR.getHudsonInstanceForName(serverName);

        if (publicHudsonInstance == null) {
            return true;
        }

        listener.getLogger().println(
                "Build was marked for publishing on "
                        + publicHudsonInstance.getUrl());

        publicHudsonInstance.publishNewBuild(build);

        return true;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }


    /*---------------------------------*/
    /* ----- Descriptor stuff -------- */
    /*---------------------------------*/

    @Override
    public MavenReporterDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    public static final MavenBuildPublisherDescriptor DESCRIPTOR = new MavenBuildPublisherDescriptor();

    public static class MavenBuildPublisherDescriptor extends
            MavenReporterDescriptor {

        public MavenBuildPublisherDescriptor() {
            super(MavenBuildPublisher.class);
        }

        public HudsonInstance[] getPublicInstances() {
            return BuildPublisher.DESCRIPTOR.getPublicInstances();
        }

        @Override
        public MavenReporter newInstance(StaplerRequest req)
                throws FormException {
            MavenBuildPublisher mbp = new MavenBuildPublisher();
            req.bindParameters(mbp, "bp.");
            return mbp;
        }

        @Override
        public String getDisplayName() {
            return "Publish build";
        }

    }

    public String getNotificationRecipients() {
        return notificationRecipients;
    }

    public boolean isPublishUnstableBuilds() {
        return publishUnstableBuilds;
    }

    public void setPublishUnstableBuilds(boolean publishUnstableBuilds) {
        this.publishUnstableBuilds = publishUnstableBuilds;
    }

    public boolean isPublishFailedBuilds() {
        return publishFailedBuilds;
    }

    public void setPublishFailedBuilds(boolean publishFailedBuilds) {
        this.publishFailedBuilds = publishFailedBuilds;
    }

    public void setNotificationRecipients(String notificationRecipients) {
        this.notificationRecipients = notificationRecipients;
    }

}
