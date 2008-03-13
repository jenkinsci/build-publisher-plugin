package hudson.plugins.build_publisher;

import hudson.model.AbstractBuild;
import hudson.model.Action;
import hudson.plugins.build_publisher.StatusInfo.State;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Build action displaying publishing status.
 * 
 * @author dvrzalik
 */
public class StatusAction implements Action {

    private StatusInfo statusInfo;

    public static final String URL = "publishingStatus";

    private AbstractBuild owner;

    public StatusAction(StatusInfo status, AbstractBuild owner) {
        this.statusInfo = status;
        this.owner = owner;
    }

    public String getDisplayName() {
        return "Publishing status";
    }

    public String getIconFileName() {
        return null;// No menu entry
    }

    public String getIconName() {
        String baseDir = "/plugin/build-publisher/icons/48x48/";
        switch (statusInfo.state) {
        case FAILURE:
        case FAILURE_PENDING:
            return baseDir + "failure.png";
        case INPROGRESS:
            return baseDir + "in-progress.png";
        case SUCCESS:
            return baseDir + "success.png";
        case PENDING:
            return baseDir + "waiting.png";
        case INTERRUPTED:
            return baseDir + "interrupted.png";
        }
        return null;
    }

    public String getUrlName() {
        return URL;
    }

    /* Disable aborting until it is properly implemented
    public void doAbortTransfer(StaplerRequest req, StaplerResponse rsp)
            throws ServletException, IOException {
        HudsonInstance instance = BuildPublisher.DESCRIPTOR
                .getHudsonInstanceForName(statusInfo.serverName);
        if (instance != null) {
            instance.abortTransmission(owner);
        }
        rsp.forwardToPreviousPage(req);
    }*/

    /**
     * Sends the build once more.
     */
    public void doPublishAgain(StaplerRequest req, StaplerResponse rsp)
            throws ServletException, IOException {
        HudsonInstance instance = BuildPublisher.DESCRIPTOR
                .getHudsonInstanceForName(statusInfo.serverName);
        if (instance != null) {
            if (statusInfo.state == State.FAILURE) {
                statusInfo.state = State.FAILURE_PENDING;
            } else {
                statusInfo.state = State.PENDING;
            }
            instance.publishBuild(owner, statusInfo);
        }
        rsp.forwardToPreviousPage(req);
    }

    public StatusInfo getStatusInfo() {
        return statusInfo;
    }

    /**
     * Sets statusAction for the build
     */
    public static void setBuildStatusAction(AbstractBuild build,
            StatusInfo statusInfo) {
        if (statusInfo != null) {
            setBuildStatusAction(build, new StatusAction(statusInfo, build));
        }

    }

    /**
     * Sets statusAction for the build
     */
    public static void setBuildStatusAction(AbstractBuild build,
            StatusAction statusAction) {
        removeAction(build);

        if (statusAction != null) {
            build.addAction(statusAction);
        }

        try {
            build.save();
        } catch (IOException e) {
            e.printStackTrace();
            HudsonInstance.LOGGER.severe(e.getMessage());
        }
    }

    public static StatusAction removeAction(AbstractBuild build) {
        StatusAction action = build.getAction(StatusAction.class);
        if (action != null) {
            build.getActions().remove(action);
        }

        return action;

    }

}