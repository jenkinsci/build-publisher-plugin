package hudson.plugins.build_publisher;

import hudson.model.Descriptor;

public abstract class PostActionDescriptor extends Descriptor<BuildPublisherPostAction> {

    public PostActionDescriptor() {
        super(BuildPublisherPostAction.class);
    }
    
    /**
     * Create "stateless" post action
     */
    public abstract BuildPublisherPostAction newInstance();
}
