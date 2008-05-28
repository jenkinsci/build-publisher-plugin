package hudson.plugins.build_publisher;

import hudson.model.AbstractBuild;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.DescriptorList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Classes implementing this interface can perform additional operations after 
 * build transmision (via the build-publisher plugin) is completed.
 * 
 * @author dvrzalik
 */
public interface BuildPublisherPostAction extends Describable<BuildPublisherPostAction> {

    /**
     * Executed after the build is published
     * 
     * @param build Published build
     * @param instance Remote Hudson instance
     */
    public void post(AbstractBuild build, HudsonInstance instance);
    
    public static final List<PostActionDescriptor> POST_ACTIONS = new CopyOnWriteArrayList<PostActionDescriptor>();
}
