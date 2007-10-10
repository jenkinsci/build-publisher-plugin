package hudson.plugins.build_publisher;

import java.io.IOException;

import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Build;
import hudson.util.IOException2;

public abstract class BuildTransmitter {

    public void sendBuild(AbstractBuild build, AbstractProject project,HudsonInstance hudsonInstance)
            throws IOException {
        try {
            proceedTransmission(build, project,hudsonInstance);

        } catch (Exception e) {
            throw new IOException2("Build transmission failed", e);
        }
    }

    protected abstract void proceedTransmission(AbstractBuild build, AbstractProject project,HudsonInstance hudsonInstance)
            throws IOException;

    public abstract void abortTransmission();

}
