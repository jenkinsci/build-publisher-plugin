package hudson.plugins.build_publisher;

import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.matrix.MatrixRun;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModule;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.maven.MavenReporter;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Build;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Project;
import hudson.model.StreamBuildListener;
import hudson.tasks.Publisher;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.methods.FileRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.logging.Level;

/**
 * {@link Thread} responsible for reading the queue and sending builds.
 */
public class PublisherThread extends Thread {
    
    private AbstractBuild currentRequest = null;

    private volatile ThreadState state = ThreadState.IDLE;

    /**
     * The public Hudson that this thread is publishing to.
     */
    private final HudsonInstance hudsonInstance;

    /**
     * @param hudsonInstance
     */
    PublisherThread(HudsonInstance hudsonInstance) {
        super("Hudson - Build-Publisher Thread for "+hudsonInstance.getName());
        this.hudsonInstance = hudsonInstance;
    }

    @Override
    public void run() {
        try {
            while (true) {
                state = ThreadState.IDLE;
                currentRequest = hudsonInstance.nextRequest();

                state = new ThreadState.Publishing(currentRequest);
                
                StatusAction.setBuildStatusAction(currentRequest,
                        new StatusInfo(StatusInfo.State.INPROGRESS,
                                "Build is being transmitted", hudsonInstance
                                        .getName(), null));
                

                try {
                    // Proceed transmission
                    
                    String publicHudsonUrl = hudsonInstance.getUrl();
                    AbstractProject project  = currentRequest.getProject();
                    
                    if (project instanceof MatrixConfiguration) {
                        //We can't create remote parent project here (we might collide with another MatrixRun),
                        //just check if it exists...
                        String projectURL = publicHudsonUrl + 
                                "job/" + 
                                ((MatrixConfiguration) project).getParent().getName();
                        if (!urlExists(projectURL)) {
                            //...If not, stop here
                            HudsonInstance.LOGGER.log(Level.WARNING,
                                    "Build " + currentRequest.getNumber() +
                                    " of matrix configuration "+ project.getName() +
                                    " couldn't be published: Parent project " +
                                    project.getParent().getFullName() + 
                                    " doesn't exist on the remote instance.");
                            //Since user has to fix the problem first, it makes no sense to add the requeust to the queue immediately
                            hudsonInstance.removeRequest(
                                    currentRequest,
                                    new StatusInfo(StatusInfo.State.INTERRUPTED,
                                    "The parent project doesn't exist on the remote instance." +
                                    " Please create it (e.g. by publishing parent matrix build) and try again.",
                                    hudsonInstance.getName(), null)); 
                            
                        }
                    } else {
                        synchronizeProjectSettings(publicHudsonUrl,project);
                    }
                    
                    hudsonInstance.buildTransmitter.sendBuild(currentRequest,
                            hudsonInstance);
                    
                    //Publish maven module builds
                    if(currentRequest instanceof MavenModuleSetBuild) {
                        for(MavenBuild moduleBuild: ((MavenModuleSetBuild) currentRequest)
                                .getModuleLastBuilds().values()) {
                            hudsonInstance.buildTransmitter.sendBuild(moduleBuild, 
                                    hudsonInstance);
                        }
                    } 
                    //.. and all matrix runs as well
                    else if(currentRequest instanceof MatrixBuild)  {
                        for(MatrixRun run: ((MatrixBuild) currentRequest).getRuns()) {
                            if(run != null) {
                                hudsonInstance.publishNewBuild(run);
                            }
                        }
                    }
                    
                    
                 
                    runPostActions(currentRequest);
                    // Notify about success
                    HudsonInstance.LOGGER.info("Build #"
                            + currentRequest.getNumber() + " of project "
                            + currentRequest.getProject().getName()
                            + " was published.");

                    hudsonInstance
                            .removeRequest(
                                    currentRequest,
                                    new StatusInfo(
                                            StatusInfo.State.SUCCESS,
                                            "Build transmission was successfully completed",
                                            hudsonInstance.getName(), null));                       
                   
                } catch (Exception e) {
                    // Something's wrong. Let's wait awhile and try again.
                    HudsonInstance.LOGGER.log(Level.WARNING,"Error during build transmission: "+e.getMessage(),e);
                    StatusAction.setBuildStatusAction(currentRequest,
                            new StatusInfo(StatusInfo.State.FAILURE_PENDING,
                                    "Error during build publishing", hudsonInstance
                                            .getName(), e));
                    hudsonInstance.postponeRequest(currentRequest);

                    HttpMethod httpMethod = null;
                    if (e instanceof ServerFailureException)
                        httpMethod = ((ServerFailureException) e).getMethod();
                    
                    // TODO make this configurable
                    final long timeout = System.currentTimeMillis() + 1000*60*10;
                    state = new ThreadState.ErrorRecoveryWait(timeout,currentRequest,e,httpMethod);

                    try {
                        while(System.currentTimeMillis() < timeout)
                            Thread.sleep(timeout-System.currentTimeMillis());
                    } catch (InterruptedException e1) {
                        // note that this also happens when the administrator manually forced a retry,
                        // ignoring timeout
                        HudsonInstance.LOGGER.log(Level.SEVERE,"Build oublisher thread was interrupted",e1);
                    }
                }
            }
        } catch(Error e) {
            state = new ThreadState.Dead(e);
            throw e;
        } catch(RuntimeException e) {
            state = new ThreadState.Dead(e);
            throw e;
        }
    }

    /**
     * Gets an immutable object representing what this thread is currently doing.
     *
     * @return
     *      never null.
     */
    public ThreadState getCurrentState() {
        return state;
    }

    private void runPostActions(AbstractBuild build) {
        //run actions that are applicable every time
        for(PostActionDescriptor descriptor: BuildPublisherPostAction.POST_ACTIONS) {
            BuildPublisherPostAction action = descriptor.newInstance();
            if(action != null) {
                action.post(build, hudsonInstance);
            }
        }
        //TODO: actions configured per-project
        //use reflection to mimic project.getPublisher(descriptor)?
    }

    /**
     * Creates new project on the public server in case it doesn't already exist
     * and submit local config.xml.
     */
    private void synchronizeProjectSettings(String publicHudson,
            AbstractProject project) throws IOException, ServerFailureException {

        assertUrlExists(publicHudson);
        ExternalProjectProperty.applyToProject(project);
        createOrSynchronize(publicHudson, project);
                
        if (project instanceof MavenModuleSet) {
            //if this is main maven project, synchronize also its modules
            String parentURL = publicHudson + "job/" + project.getName();
            
            for(MavenModule module: ((MavenModuleSet) project).getItems()) {
                String moduleModuleSystemName = module
                    .getModuleName().toFileSystemName();
                
                submitConfig(parentURL + "/postBuild/acceptMavenModule?name="
                        + moduleModuleSystemName, module);
            }
        } else if(project instanceof MatrixProject) {
            //Synchronize active matrix configurarions
            String parentURL = publicHudson + "job/" + project.getName();
            for(MatrixConfiguration configuration: ((MatrixProject) project).getActiveConfigurations()) {
                    submitConfig(parentURL+"/"+configuration.getShortUrl() +"config.xml", configuration);
            }
        } else if(project instanceof ItemGroup) {
            String parentURL = publicHudson + "job/" + project.getName();
            for(Object item: ((ItemGroup) project).getItems()) {
                if(item instanceof Job) {
                    Job job = (Job) item;
                    submitConfig(parentURL+"/"+job.getShortUrl() +"config.xml", job);
                    
                }
            }
        }
    }

    private void createOrSynchronize(String publicHudson,
            AbstractProject project) throws IOException, ServerFailureException {

        String projectURL = publicHudson + "job/" + project.getName();
        String submitConfigUrl;

        if (!urlExists(projectURL)) {
            // if the project doesn't exist, create it
            submitConfigUrl = publicHudson + "createItem?name="
                    + project.getName();
        } else {
            // otherwise just synchronize config file
            submitConfigUrl = projectURL + "/config.xml";
        }

        submitConfig(submitConfigUrl, project);
    }

    private void submitConfig(String submitConfigUrl, Job project)
            throws IOException, ServerFailureException {
        PostMethod method = new PostMethod();
        method.setURI(new org.apache.commons.httpclient.URI(submitConfigUrl,
                                false));
        method.setRequestEntity(new FileRequestEntity(project.getConfigFile().getFile(),"text/xml"));
        executeMethod(method);
    }

    private void assertUrlExists(String url) throws IOException, ServerFailureException {
        if (!urlExists(url)) {
            // wrong address, give up
            throw new HttpException(url + ": URL doesn't exist");
        }
    }

    

    private boolean urlExists(String url) throws ServerFailureException, IOException {

        PostMethod method = new PostMethod();
        method.setURI(new org.apache.commons.httpclient.URI(url,false));

        try {
            executeMethod(method);
            return true;
        } catch (ServerFailureException e) {
            int statusCode = e.getMethod().getStatusCode();
            if ((statusCode == 400) || (statusCode == 404))
                return false;
            throw e;
        }
    }

    /* shortcut */
    private HttpMethod executeMethod(HttpMethodBase method) throws ServerFailureException {
        return HTTPBuildTransmitter.executeMethod(method, this.hudsonInstance);
    }

}