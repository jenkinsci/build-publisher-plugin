package hudson.plugins.build_publisher;

import hudson.model.AbstractBuild;

import java.io.IOException;

/**
 * Base interface for build transmitters.
 * 
 * @author dvrzalik
 */
public interface BuildTransmitter {
    
    /**
     * Publishes the build onto the public istance. 
     * Implementing class can assume that the project is already configured.
     * 
     * @param build Build to publish
     * @param hudsonInstance Targetted public hudson
     * @throws java.io.IOException when an error occures
     */
    public void sendBuild(AbstractBuild build, HudsonInstance hudsonInstance) throws ServerFailureException;

    /**
     * Aborts transmission. (optional?)
     */
    public void abortTransmission();

}
